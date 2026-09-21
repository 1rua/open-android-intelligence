"""Independent Python Gateway Core for the Hermes host.

The module intentionally owns its SQLite connection and account directory.  It
does not import another host implementation or a legacy runtime.
"""

from __future__ import annotations

import base64
import hashlib
import json
import logging
import re
import secrets
import shutil
import sqlite3
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Iterator, Mapping
from urllib.parse import urlsplit

from .account_paths import (
    AccountPaths,
    account_paths,
    default_hermes_gateway_root,
    ensure_account_directories,
)
from .audit import AuditStore
from .credentials import hash_password, verify_password


logger = logging.getLogger("open_android_intelligence_gateway.core")

# One transport that wants committed events as they happen. The account id is
# the account the event belongs to, so a stream never has to guess it.
EventSink = Callable[[str, Mapping[str, Any]], None]


# Contract section 4 core schema digest: a domain-separated, name-sorted listing
# of each named schema document's raw byte digest. Hashing the checked-in bytes
# keeps the value reproducible in every language that can read the files, which
# is what lets the phone and the Gateway compare the same number.
CORE_SCHEMA_HASH_DOMAIN = "open-android-intelligence/v2/core-schema-hash"
CORE_SCHEMA_FILE_NAMES = (
    "attachment.schema.json",
    "conversation.schema.json",
    "device-request.schema.json",
    "envelope.schema.json",
    "event.schema.json",
    "negotiate.schema.json",
    "session.schema.json",
)

# The reserved `/new` invocation (contract §7.1). The catalog declares it with
# `acceptsArguments: false`, so only this exact text is the new-conversation
# command; `/new <anything>` stays ordinary text the Agent has to interpret.
NEW_CONVERSATION_COMMAND = "/new"

# The exact six shared vector documents of contract section 16. The enumeration
# is closed: its `schemaName` set does not include the conversation-UI schemas,
# so `conversation-ui.json` stays a local suite and is not a conformance input.
SHARED_VECTOR_FILE_NAMES = (
    "request-signatures.json", "protocol-negotiation.json", "auth-sessions.json",
    "attachments.json", "sse-events.json", "device-requests.json",
)


class GatewayError(Exception):
    """A protocol or state-machine error with a stable code."""

    def __init__(self, code: str, details: Mapping[str, Any] | None = None):
        super().__init__(code)
        self.code = code
        self.details = dict(details or {})


class TransactionOutcomeUnknown(GatewayError):
    def __init__(self, details: Mapping[str, Any] | None = None):
        super().__init__("OUTCOME_UNKNOWN", details)


@dataclass(frozen=True)
class AttachmentPolicy:
    """Immutable attachment limits shared by negotiation and every data path."""

    max_single_attachment_bytes: int = 26_214_400
    max_message_attachment_bytes: int = 52_428_800
    allowed_media_types: tuple[str, ...] = (
        "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif",
        "image/heic", "image/heif", "image/bmp", "image/x-ms-bmp",
        "image/svg+xml", "image/tiff",
        "audio/mp4", "audio/mpeg", "audio/mp3", "audio/wav", "audio/ogg",
        "audio/aac", "audio/m4a", "audio/x-m4a", "audio/flac",
        "video/mp4", "video/webm", "video/quicktime", "video/3gpp",
        "text/plain", "text/markdown", "text/x-markdown", "text/csv",
        "text/html", "text/xml",
        "application/pdf", "application/json", "application/xml",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/zip", "application/x-zip-compressed", "application/x-tar",
        "application/gzip", "application/octet-stream",
    )
    attachment_ttl_seconds: int = 3600

    def __post_init__(self) -> None:
        object.__setattr__(self, "allowed_media_types", tuple(self.allowed_media_types))
        if self.max_single_attachment_bytes <= 0:
            raise ValueError("max_single_attachment_bytes must be positive")
        if self.max_message_attachment_bytes < self.max_single_attachment_bytes:
            raise ValueError("message attachment limit must cover one attachment")
        if self.attachment_ttl_seconds <= 0 or not self.allowed_media_types:
            raise ValueError("attachment policy must define positive limits and media types")


DEFAULT_ATTACHMENT_POLICY = AttachmentPolicy()


@dataclass(frozen=True, init=False)
class VerifiedRequestContext:
    account_id: str
    device_id: str
    session_id: str
    request_id: str
    correlation_id: str
    pairing_generation: int
    grant_revision: int
    negotiation_id: str | None
    installation_id: str | None

    def __init__(
        self, account_id: str | None = None, device_id: str | None = None,
        session_id: str | None = None, request_id: str | None = None,
        correlation_id: str | None = None, pairing_generation: int | None = None,
        grant_revision: int | None = None, negotiation_id: str | None = None,
        installation_id: str | None = None, **aliases: Any,
    ):
        def take(primary: Any, alias: str) -> Any:
            alternate = aliases.pop(alias, None)
            if primary is not None and alternate is not None:
                raise TypeError(f"duplicate context field: {alias}")
            return primary if primary is not None else alternate

        fields = {
            "account_id": take(account_id, "accountId"),
            "device_id": take(device_id, "deviceId"),
            "session_id": take(session_id, "sessionId"),
            "request_id": take(request_id, "requestId"),
            "correlation_id": take(correlation_id, "correlationId"),
            "pairing_generation": take(pairing_generation, "pairingGeneration"),
            "grant_revision": take(grant_revision, "grantRevision"),
            "negotiation_id": take(negotiation_id, "negotiationId"),
            "installation_id": take(installation_id, "installationId"),
        }
        if aliases:
            raise TypeError(f"unknown context fields: {sorted(aliases)}")
        for name in ("account_id", "device_id", "session_id", "request_id", "correlation_id"):
            if not isinstance(fields[name], str) or not fields[name]:
                raise ValueError(f"missing or invalid context field: {name}")
        for name in ("pairing_generation", "grant_revision"):
            if (
                not isinstance(fields[name], int) or isinstance(fields[name], bool)
                or fields[name] < 0
            ):
                raise ValueError(f"missing or invalid context field: {name}")
        for name in ("negotiation_id", "installation_id"):
            if fields[name] is not None and (not isinstance(fields[name], str) or not fields[name]):
                raise ValueError(f"invalid context field: {name}")
        for name, value in fields.items():
            object.__setattr__(self, name, value)

    @property
    def accountId(self) -> str:
        return self.account_id

    @property
    def deviceId(self) -> str:
        return self.device_id

    @property
    def sessionId(self) -> str:
        return self.session_id

    @property
    def requestId(self) -> str:
        return self.request_id

    @property
    def correlationId(self) -> str:
        return self.correlation_id

    @property
    def pairingGeneration(self) -> int:
        return self.pairing_generation

    @property
    def grantRevision(self) -> int:
        return self.grant_revision

    @property
    def negotiationId(self) -> str | None:
        return self.negotiation_id

    @property
    def installationId(self) -> str | None:
        return self.installation_id


@dataclass(frozen=True, init=False)
class VerifiedGatewayRequest:
    context: VerifiedRequestContext
    method: str
    target: str
    body: Any
    idempotency_key: str | None
    last_event_id: str | None
    now: datetime | str | None

    def __init__(self, context: VerifiedRequestContext, method: str, target: str, body: Any = None,
                 idempotency_key: str | None = None, last_event_id: str | None = None,
                 now: datetime | str | None = None, **aliases: Any):
        if not isinstance(context, VerifiedRequestContext):
            raise TypeError("GatewayCore accepts only VerifiedRequestContext")
        if not isinstance(method, str) or not isinstance(target, str):
            raise TypeError("verified request method and target must be strings")
        idempotency_alias = aliases.pop("idempotencyKey", None)
        last_event_alias = aliases.pop("lastEventId", None)
        if idempotency_key is not None and idempotency_alias is not None:
            raise TypeError("duplicate request field: idempotencyKey")
        if last_event_id is not None and last_event_alias is not None:
            raise TypeError("duplicate request field: lastEventId")
        if aliases:
            raise TypeError(f"unknown request fields: {sorted(aliases)}")
        object.__setattr__(self, "context", context)
        object.__setattr__(self, "method", method)
        object.__setattr__(self, "target", target)
        object.__setattr__(self, "body", _freeze(body))
        object.__setattr__(self, "idempotency_key", idempotency_key if idempotency_key is not None else idempotency_alias)
        object.__setattr__(self, "last_event_id", last_event_id if last_event_id is not None else last_event_alias)
        object.__setattr__(self, "now", now)

    @property
    def idempotencyKey(self) -> str | None:
        return self.idempotency_key

    @property
    def lastEventId(self) -> str | None:
        return self.last_event_id


class _FrozenDict(dict[str, Any]):
    def _readonly(self, *_: Any, **__: Any) -> None:
        raise TypeError("GatewayResponse is immutable")

    __setitem__ = _readonly
    __delitem__ = _readonly
    clear = _readonly
    pop = _readonly
    popitem = _readonly
    setdefault = _readonly
    update = _readonly
    __ior__ = _readonly


class _FrozenList(list[Any]):
    def _readonly(self, *_: Any, **__: Any) -> None:
        raise TypeError("GatewayResponse is immutable")

    __setitem__ = _readonly
    __delitem__ = _readonly
    __iadd__ = _readonly
    __imul__ = _readonly
    append = _readonly
    clear = _readonly
    extend = _readonly
    insert = _readonly
    pop = _readonly
    remove = _readonly
    reverse = _readonly
    sort = _readonly


def _freeze(value: Any) -> Any:
    if isinstance(value, Mapping):
        frozen = _FrozenDict()
        dict.__init__(frozen, ((key, _freeze(child)) for key, child in value.items()))
        return frozen
    if isinstance(value, list):
        frozen = _FrozenList()
        list.__init__(frozen, (_freeze(child) for child in value))
        return frozen
    if isinstance(value, tuple):
        return _FrozenList(_freeze(child) for child in value)
    return value


class GatewayResponse(dict[str, Any]):
    def __init__(self, value: Mapping[str, Any] | None = None, **kwargs: Any):
        dict.__init__(self, _freeze(dict(value or {}, **kwargs)))

    def _readonly(self, *_: Any, **__: Any) -> None:
        raise TypeError("GatewayResponse is immutable")

    __setitem__ = _readonly
    __delitem__ = _readonly
    clear = _readonly
    pop = _readonly
    popitem = _readonly
    setdefault = _readonly
    update = _readonly
    __ior__ = _readonly
    @property
    def requestId(self) -> str:
        return str(self["requestId"])

    @property
    def request_id(self) -> str:
        return self.requestId

    @property
    def correlationId(self) -> str:
        return str(self["correlationId"])

    @property
    def correlation_id(self) -> str:
        return self.correlationId

    @property
    def protocol(self) -> str:
        return str(self["protocol"])

    @property
    def data(self) -> Mapping[str, Any] | None:
        return self.get("data")

    @property
    def error(self) -> Mapping[str, Any] | None:
        return self.get("error")


def _jcs(value: Any) -> str:
    """The contract fixtures use JSON values whose JCS form is this subset."""

    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _contract_root(explicit: str | Path | None = None) -> Path:
    candidates: list[Path] = []
    if explicit is not None:
        given = Path(explicit).resolve()
        candidates.extend([given, given / "gateway-contract"])
    source = Path(__file__).resolve()
    candidates.extend(parent / "gateway-contract" for parent in source.parents)
    cwd = Path.cwd().resolve()
    candidates.extend(parent / "gateway-contract" for parent in (cwd, *cwd.parents))
    for candidate in candidates:
        if (candidate / "schemas" / "envelope.schema.json").is_file() and (candidate / "vectors" / "vector-set-1.0.0.schema.json").is_file():
            return candidate
    raise GatewayError("INTERNAL_ERROR", {"reason": "gateway-contract assets unavailable"})


def canonicalize_target(target: str) -> str:
    """Canonicalize the protocol origin-form target without URL normalization."""

    if not isinstance(target, str) or not target or target == "*" or "#" in target:
        raise GatewayError("SCHEMA_INVALID")
    if any(ord(char) <= 0x20 or ord(char) == 0x7F or ord(char) > 0x7F for char in target):
        raise GatewayError("SCHEMA_INVALID")
    if not target.startswith("/") or target.startswith("//"):
        raise GatewayError("SCHEMA_INVALID")
    path, separator, query = target.partition("?")
    if not path.startswith("/open-android-intelligence/v2") or (len(path) > len("/open-android-intelligence/v2") and not path.startswith("/open-android-intelligence/v2/")):
        raise GatewayError("SCHEMA_INVALID")

    def encoded_bytes(value: str) -> bytes:
        result = bytearray()
        index = 0
        while index < len(value):
            char = value[index]
            if char == "%":
                if index + 2 >= len(value) or not re.fullmatch(r"[0-9A-Fa-f]{2}", value[index + 1:index + 3]):
                    raise GatewayError("SCHEMA_INVALID")
                result.append(int(value[index + 1:index + 3], 16))
                index += 3
                continue
            result.extend(char.encode("ascii"))
            index += 1
        return bytes(result)

    def encode_component(value: str) -> str:
        raw = encoded_bytes(value)
        return "".join(chr(byte) if (65 <= byte <= 90 or 97 <= byte <= 122 or 48 <= byte <= 57 or byte in b"._~-") else f"%{byte:02X}" for byte in raw)

    segments = path.split("/")
    if any(segment == "" for segment in segments[1:]):
        raise GatewayError("SCHEMA_INVALID")
    canonical_segments: list[str] = []
    for segment in segments:
        decoded = encoded_bytes(segment)
        if decoded in (b".", b"..") or b"/" in decoded or b"\\" in decoded:
            raise GatewayError("SCHEMA_INVALID")
        canonical_segments.append(encode_component(segment))
    canonical_path = "/".join(canonical_segments)
    if separator == "" and query:
        raise GatewayError("SCHEMA_INVALID")
    if separator and not query:
        raise GatewayError("SCHEMA_INVALID")
    if not separator:
        return canonical_path
    pairs: list[tuple[str, str]] = []
    for item in query.split("&"):
        if not item:
            raise GatewayError("SCHEMA_INVALID")
        name, equals, value = item.partition("=")
        if not name:
            raise GatewayError("SCHEMA_INVALID")
        encoded_name = encode_component(name)
        encoded_value = encode_component(value if equals else "")
        pairs.append((encoded_name, encoded_value))
    pairs.sort(key=lambda pair: (pair[0].encode("ascii"), pair[1].encode("ascii")))
    return canonical_path + "?" + "&".join(f"{name}={value}" for name, value in pairs)


def request_signature_preimage(input: Mapping[str, Any]) -> bytes:
    method = input.get("method")
    if method not in {"GET", "POST", "PUT", "DELETE", "PATCH"}:
        raise GatewayError("SCHEMA_INVALID")
    target = input.get("target")
    canonical = canonicalize_target(target)
    if target != canonical:
        raise GatewayError("NON_CANONICAL_TARGET")
    body_hex = input.get("bodyHex")
    if not isinstance(body_hex, str) or re.fullmatch(r"(?:[0-9a-f]{2})*", body_hex) is None:
        raise GatewayError("SCHEMA_INVALID")
    fields = [
        "OPEN-ANDROID-INTELLIGENCE-REQUEST-V2", method, canonical, input.get("accountId"), input.get("deviceId"),
        input.get("sessionId"), input.get("requestId"), input.get("timestamp"), input.get("nonce"),
        hashlib.sha256(bytes.fromhex(body_hex)).hexdigest(),
    ]
    if not all(isinstance(field, str) for field in fields):
        raise GatewayError("SCHEMA_INVALID")
    try:
        return "\n".join(fields).encode("ascii")
    except UnicodeEncodeError as exc:
        raise GatewayError("SCHEMA_INVALID") from exc


class ContractRegistry:
    """Read-only consumer of the repository's shared Schema and fixture registry."""

    dispatched_registry_id = "gateway-core-fixtures-v1"
    dispatched_fixture_ids = (
        "event.gateway-notice.v1",
        "device.sms-query.v1",
        "response.conversation-create.v1",
        "error.cursor-expired.v1",
        "event.conversation-command-result.v1",
    )

    schema_definitions = {
        "negotiate.request": ("negotiate.schema.json", "request"),
        "negotiate.response": ("negotiate.schema.json", "response"),
        "session.password": ("session.schema.json", "password"),
        "session.refresh": ("session.schema.json", "refresh"),
        "session.device": ("session.schema.json", "device"),
        "conversation.create": ("conversation.schema.json", "create"),
        "message.create": ("conversation.schema.json", "messageCreate"),
        "attachment.create": ("attachment.schema.json", "create"),
        "event": ("event.schema.json", "event"),
        "device.request": ("device-request.schema.json", "request"),
        "response.success": ("envelope.schema.json", "success"),
        "response.failure": ("envelope.schema.json", "failure"),
        "conversation.commandCatalog": ("conversation.schema.json", "commandCatalog"),
        "conversation.cancel": ("conversation.schema.json", "generationCancel"),
        "conversation.generationCancel": ("conversation.schema.json", "generationCancel"),
        "conversation.mirror": ("conversation.schema.json", "mirrorSync"),
        "conversation.mirrorSync": ("conversation.schema.json", "mirrorSync"),
        "attachment.status": ("attachment.schema.json", "status"),
    }

    @property
    def core_schema_hash(self) -> str:
        lines = [CORE_SCHEMA_HASH_DOMAIN]
        schema_dir = self.root / "schemas"
        for name in CORE_SCHEMA_FILE_NAMES:
            digest = hashlib.sha256((schema_dir / name).read_bytes()).hexdigest()
            lines.append(f"{name}\tsha256:{digest}")
        payload = "\n".join(lines) + "\n"
        return "sha256:" + hashlib.sha256(payload.encode("utf-8")).hexdigest()

    def __init__(self, contract_root: str | Path | None = None):
        self.root = _contract_root(contract_root)
        schema_dir = self.root / "schemas"
        self.documents: dict[str, dict[str, Any]] = {}
        for path in schema_dir.glob("*.schema.json"):
            document = json.loads(path.read_text(encoding="utf-8"))
            self.documents[document["$id"]] = document
        self._catalog: dict[tuple[Any, ...], dict[str, Any]] = {}
        self._bindings: dict[str, dict[tuple[Any, ...], str]] = {}
        self._load_dispatched_registry()

    @staticmethod
    def _logical_key(key: Mapping[str, Any]) -> tuple[Any, ...]:
        return tuple((name, key[name]) for name in sorted(key) if name != "schemaSha256")

    def _load_dispatched_registry(self) -> None:
        def invalid(reason: str) -> None:
            raise GatewayError("DISPATCHED_REGISTRY_INVALID", {"reason": reason})

        try:
            vectors = self.root / "vectors"
            meta_path = vectors / "dispatched-schema-fixtures-1.0.0.schema.json"
            registry_path = vectors / "dispatched-schema-fixtures.json"
            if not meta_path.is_file() or not registry_path.is_file():
                invalid("registry assets missing")
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            registry = json.loads(registry_path.read_text(encoding="utf-8"))
            if not self._valid(meta, registry, meta):
                invalid("registry does not satisfy shared meta-schema")
            if registry.get("formatVersion") != "1.0.0":
                invalid("unsupported registry format")
            catalog_entries = registry.get("catalogEntries")
            binding_sets = registry.get("bindingSets")
            if not isinstance(catalog_entries, list) or not isinstance(binding_sets, list):
                invalid("registry collections are not arrays")
            fixture_ids = [entry.get("fixtureId") for entry in catalog_entries if isinstance(entry, Mapping)]
            if tuple(fixture_ids) != self.dispatched_fixture_ids:
                invalid("catalog entry order or identity mismatch")
            if len(binding_sets) != 1 or not isinstance(binding_sets[0], Mapping):
                invalid("binding set count mismatch")
            binding_set = binding_sets[0]
            if binding_set.get("id") != self.dispatched_registry_id:
                invalid("binding set identity mismatch")
            bindings = binding_set.get("bindings")
            if not isinstance(bindings, list) or len(bindings) != len(catalog_entries):
                invalid("binding count mismatch")

            catalog_keys: list[tuple[Any, ...]] = []
            catalog_by_key: dict[tuple[Any, ...], dict[str, Any]] = {}
            for entry in catalog_entries:
                if not isinstance(entry, Mapping) or not isinstance(entry.get("key"), Mapping) or not isinstance(entry.get("schema"), Mapping):
                    invalid("catalog entry shape invalid")
                key = self._logical_key(entry["key"])
                if key in catalog_by_key:
                    invalid("duplicate catalog logical key")
                expected = entry["key"].get("schemaSha256")
                actual = "sha256:" + hashlib.sha256(_jcs(entry["schema"]).encode("utf-8")).hexdigest()
                if expected != actual:
                    invalid("catalog schema digest mismatch")
                catalog_keys.append(key)
                catalog_by_key[key] = {"schemaSha256": expected, "schema": entry["schema"]}

            binding_map: dict[tuple[Any, ...], str] = {}
            binding_keys: list[tuple[Any, ...]] = []
            for binding in bindings:
                if not isinstance(binding, Mapping) or not isinstance(binding.get("key"), Mapping):
                    invalid("binding shape invalid")
                key = self._logical_key(binding["key"])
                if key in binding_map:
                    invalid("duplicate binding logical key")
                if key not in catalog_by_key:
                    invalid("binding points to missing catalog entry")
                digest = binding.get("schemaSha256")
                if catalog_by_key[key]["schemaSha256"] != digest:
                    invalid("binding digest mismatch")
                binding_keys.append(key)
                binding_map[key] = digest
            if binding_keys != catalog_keys:
                invalid("binding order or logical key mismatch")
            self._catalog = catalog_by_key
            self._bindings[self.dispatched_registry_id] = binding_map
        except GatewayError as exc:
            if exc.code == "DISPATCHED_REGISTRY_INVALID":
                raise
            invalid("registry validation failed")
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            invalid(f"registry parsing failed: {type(exc).__name__}")

    def _resolve_ref(self, ref: str, current_document: Mapping[str, Any]) -> tuple[Mapping[str, Any], Mapping[str, Any]]:
        if ref.startswith("#/$defs/"):
            document = current_document
            name = ref[len("#/$defs/"):]
        else:
            marker = "#/$defs/"
            if marker not in ref:
                raise GatewayError("SCHEMA_INVALID")
            document_id, name = ref.split(marker, 1)
            document = self.documents.get(document_id)
            if document is None:
                raise GatewayError("SCHEMA_INVALID")
        definition = document.get("$defs", {}).get(name)
        if not isinstance(definition, Mapping):
            raise GatewayError("SCHEMA_INVALID")
        return document, definition

    def _valid(self, schema: Any, value: Any, current_document: Mapping[str, Any]) -> bool:
        if schema is True:
            return True
        if schema is False or not isinstance(schema, Mapping):
            return False
        if "$ref" in schema:
            document, resolved = self._resolve_ref(str(schema["$ref"]), current_document)
            return self._valid(resolved, value, document)
        if "const" in schema and value != schema["const"]:
            return False
        if "enum" in schema and value not in schema["enum"]:
            return False
        if "allOf" in schema and not all(self._valid(child, value, current_document) for child in schema["allOf"]):
            return False
        if "anyOf" in schema and not any(self._valid(child, value, current_document) for child in schema["anyOf"]):
            return False
        if "oneOf" in schema and sum(self._valid(child, value, current_document) for child in schema["oneOf"]) != 1:
            return False
        schema_type = schema.get("type")
        if schema_type == "object":
            if not isinstance(value, Mapping):
                return False
        elif schema_type == "array":
            if not isinstance(value, list):
                return False
        elif schema_type == "string":
            if not isinstance(value, str):
                return False
        elif schema_type == "integer":
            if not isinstance(value, int) or isinstance(value, bool):
                return False
        elif schema_type == "number":
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                return False
        elif schema_type == "boolean" and not isinstance(value, bool):
            return False
        elif schema_type == "null" and value is not None:
            return False
        if isinstance(value, str):
            if len(value) < int(schema.get("minLength", 0)) or ("maxLength" in schema and len(value) > int(schema["maxLength"])):
                return False
            pattern = schema.get("pattern")
            if pattern is not None and re.fullmatch(str(pattern), value) is None:
                return False
            if schema.get("format") == "date-time":
                try:
                    datetime.fromisoformat(value.replace("Z", "+00:00"))
                except ValueError:
                    return False
        if isinstance(value, (int, float)) and "minimum" in schema and value < schema["minimum"]:
            return False
        if isinstance(value, list):
            if len(value) < int(schema.get("minItems", 0)):
                return False
            if schema.get("uniqueItems") and len({_jcs(item) for item in value}) != len(value):
                return False
            if "items" in schema and not all(self._valid(schema["items"], item, current_document) for item in value):
                return False
        if isinstance(value, Mapping):
            required = schema.get("required", [])
            if any(key not in value for key in required):
                return False
            properties = schema.get("properties", {})
            if schema.get("additionalProperties") is False and any(key not in properties for key in value):
                return False
            for key, child in properties.items():
                if key in value and not self._valid(child, value[key], current_document):
                    return False
            additional = schema.get("additionalProperties")
            if isinstance(additional, Mapping):
                if any(key not in properties and not self._valid(additional, child, current_document) for key, child in value.items()):
                    return False
        return True

    def validate(self, name: str, value: Any) -> bool:
        target = self.schema_definitions.get(name)
        if target is None:
            raise GatewayError("SCHEMA_INVALID")
        document = self.documents.get(next((doc_id for doc_id, doc in self.documents.items() if doc_id.endswith(target[0])), ""))
        if document is None:
            raise GatewayError("INTERNAL_ERROR", {"reason": f"missing schema document: {target[0]}"})
        return self._valid(document["$defs"][target[1]], value, document)

    def validate_dispatched(self, binding_set_id: str, dispatch: Mapping[str, Any], value: Any) -> bool:
        if not isinstance(dispatch, Mapping) or binding_set_id != self.dispatched_registry_id:
            return False
        kind = dispatch.get("kind")
        if kind == "event":
            if set(dispatch) != {"kind", "eventType"}:
                return False
            if not self.validate("event", value):
                return False
            logical = (('eventType', dispatch.get("eventType")), ('kind', "event"))
            payload = value.get("payload") if isinstance(value, Mapping) else None
        elif kind == "device.request":
            if set(dispatch) != {"kind"}:
                return False
            if not self.validate("device.request", value):
                return False
            provider = value.get("provider", {})
            capability = value.get("capability", {})
            logical = (('authorKeyId', provider.get("authorKeyId")), ('capabilityId', capability.get("id")), ('capabilityVersion', capability.get("version")), ('kind', "device.request"), ('pluginId', provider.get("pluginId")))
            payload = value.get("parameters")
        elif kind == "response.success":
            if set(dispatch) != {"kind", "operation", "status"}:
                return False
            if not self.validate("response.success", value):
                return False
            logical = (('kind', "response.success"), ('operation', dispatch.get("operation")), ('status', dispatch.get("status")))
            payload = value.get("data") if isinstance(value, Mapping) else None
        elif kind == "response.failure":
            if set(dispatch) != {"kind"}:
                return False
            if not self.validate("response.failure", value):
                return False
            error = value.get("error", {})
            logical = (('errorCode', error.get("code")), ('kind', "response.failure"))
            payload = error.get("details") if isinstance(error, Mapping) else None
        else:
            return False
        schema = self._bindings[self.dispatched_registry_id].get(logical)
        if schema is None:
            return False
        entry = self._catalog.get(logical)
        if entry is not None and entry["schemaSha256"] != schema:
            entry = None
        return entry is not None and self._valid(entry["schema"], payload, self.documents[next(iter(self.documents))])


def _now(value: datetime | str | None = None) -> datetime:
    if value is None:
        return datetime.now(timezone.utc)
    if isinstance(value, str):
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    else:
        parsed = value
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def iso_millis(value: datetime | str | None = None) -> str:
    return _now(value).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _epoch_millis(value: datetime | str | None = None) -> int:
    if value is None:
        return 0
    if isinstance(value, (int, float)):
        return int(value)
    try:
        dt = _now(value)
        return int(dt.timestamp() * 1000)
    except Exception:
        try:
            text = str(value).replace("Z", "+00:00")
            dt = datetime.fromisoformat(text)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return int(dt.timestamp() * 1000)
        except Exception:
            return 0


def _json(value: Any) -> str:
    def encode(child: Any) -> Any:
        if isinstance(child, (bytes, bytearray, memoryview)):
            return {"bytesSha256": hashlib.sha256(bytes(child)).hexdigest()}
        if isinstance(child, Mapping):
            return {str(key): encode(item) for key, item in child.items()}
        if isinstance(child, (list, tuple)):
            return [encode(item) for item in child]
        return child

    encoded = encode(value)
    return json.dumps(encoded, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _hash_input(method: str, target: str, body: Any) -> str:
    return hashlib.sha256(_json({"method": method, "target": target, "body": body}).encode("utf-8")).hexdigest()


def _value(value: Any, *names: str, default: Any = None) -> Any:
    for name in names:
        if isinstance(value, Mapping) and name in value:
            return value[name]
        if hasattr(value, name):
            return getattr(value, name)
    return default


def _context_value(context: Any, camel: str, snake: str, default: Any = None) -> Any:
    return _value(context, camel, snake, default=default)


def _request_context(request: Any) -> dict[str, Any]:
    context = _value(request, "context")
    if not isinstance(context, VerifiedRequestContext):
        raise GatewayError("AUTHENTICATION_REQUIRED")
    result = {
        "accountId": context.accountId,
        "deviceId": context.deviceId,
        "sessionId": context.sessionId,
        "requestId": context.requestId,
        "correlationId": context.correlationId,
        "pairingGeneration": context.pairingGeneration,
        "grantRevision": context.grantRevision,
    }
    negotiation_id = context.negotiationId
    installation_id = context.installationId
    if negotiation_id is not None:
        result["negotiationId"] = negotiation_id
    if installation_id is not None:
        result["installationId"] = installation_id
    return result


def _request_body(request: Any) -> Any:
    return _value(request, "body", default=None)


def _request_headers(request: Any) -> dict[str, str]:
    """Case-folded headers of a pre-auth request, last value winning per name."""
    headers = _value(request, "headers")
    if headers is None:
        return {}
    if not isinstance(headers, Mapping):
        return {}
    return {str(name).lower(): str(value) for name, value in headers.items()}


def _bearer_token(headers: Mapping[str, str]) -> str | None:
    authorization = headers.get("authorization")
    if not isinstance(authorization, str):
        return None
    scheme, separator, token = authorization.partition(" ")
    if not separator or scheme.strip().lower() != "bearer":
        return None
    token = token.strip()
    return token or None


def _query_value(target: Any, name: str) -> str | None:
    if not isinstance(target, str):
        return None
    query = target.partition("?")[2]
    for item in query.split("&") if query else []:
        key, equals, value = item.partition("=")
        if key == name and equals:
            return value
    return None


def _query_flag(target: Any, name: str) -> bool:
    return (_query_value(target, name) or "").strip().lower() == "true"


def _request_now(request: Any) -> datetime:
    return _now(_value(request, "now", default=None))


def _success(context: Mapping[str, Any], data: Mapping[str, Any]) -> GatewayResponse:
    return GatewayResponse({
        "requestId": context["requestId"],
        "correlationId": context["correlationId"],
        "protocol": "2.0",
        "data": dict(data),
    })


def _failure(context: Mapping[str, Any], code: str, details: Mapping[str, Any] | None = None) -> GatewayResponse:
    return GatewayResponse({
        "requestId": context["requestId"],
        "correlationId": context["correlationId"],
        "protocol": "2.0",
        "error": {
            "code": code,
            "message": code,
            "retryable": False,
            "retryAfterSeconds": None,
            "details": dict(details or {}),
        },
    })


class AccountStore:
    """One SQLite connection for one already-resolved account directory."""

    def __init__(
        self, paths: AccountPaths, master_key_ref: str | None = None,
        commit_hook: Any = None, aead: Any = None,
        account_id: str | None = None, event_sink: EventSink | None = None,
    ):
        self.paths = paths
        self.master_key_ref = str(master_key_ref or "")
        self.commit_hook = commit_hook
        self.aead = aead
        self.account_id = str(account_id or "")
        self.event_sink = event_sink
        self._pending_events: list[dict[str, Any]] = []
        self.fail_next_commit = False
        ensure_account_directories(paths)
        self.database = sqlite3.connect(str(paths.database), isolation_level=None, check_same_thread=False)
        self.database.row_factory = sqlite3.Row
        self.database.execute("PRAGMA journal_mode = WAL")
        self.database.execute("PRAGMA foreign_keys = ON")
        self._transaction_depth = 0
        self.database.executescript(
            """
            CREATE TABLE IF NOT EXISTS account_metadata (
              key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS refresh_credentials (
              credential_hash TEXT PRIMARY KEY NOT NULL,
              installation_id TEXT NOT NULL, device_id TEXT NOT NULL,
              session_id TEXT NOT NULL, status TEXT NOT NULL,
              created_at TEXT NOT NULL, replaced_by_hash TEXT
            );
            CREATE TABLE IF NOT EXISTS access_sessions (
              session_id TEXT PRIMARY KEY NOT NULL,
              installation_id TEXT NOT NULL, device_id TEXT NOT NULL,
              access_token_hash TEXT, status TEXT NOT NULL,
              created_at TEXT NOT NULL, expires_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS device_keys (
              device_id TEXT PRIMARY KEY NOT NULL,
              installation_id TEXT NOT NULL, public_key TEXT NOT NULL,
              pairing_generation INTEGER NOT NULL DEFAULT 1,
              grant_revision INTEGER NOT NULL DEFAULT 1,
              registered_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS idempotency_ledger (
              device_id TEXT NOT NULL, request_id TEXT NOT NULL,
              input_hash TEXT NOT NULL, outcome_json TEXT NOT NULL,
              expires_at TEXT NOT NULL,
              PRIMARY KEY (device_id, request_id)
            );
            CREATE TABLE IF NOT EXISTS events (
              event_id TEXT PRIMARY KEY NOT NULL, event_type TEXT NOT NULL,
              correlation_id TEXT NOT NULL, occurred_at TEXT NOT NULL,
              payload_json TEXT NOT NULL, expires_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS attachments (
              attachment_id TEXT PRIMARY KEY NOT NULL,
              client_attachment_id TEXT NOT NULL, filename TEXT NOT NULL,
              media_type TEXT NOT NULL, size_bytes INTEGER NOT NULL,
              sha256 TEXT NOT NULL, state TEXT NOT NULL,
              content_path TEXT, cas_path TEXT, created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL, delivered_at TEXT,
              acknowledged_at TEXT
            );
            CREATE TABLE IF NOT EXISTS conversations (
              conversation_id TEXT PRIMARY KEY NOT NULL,
              client_conversation_id TEXT NOT NULL, title TEXT,
              created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS messages (
              message_id TEXT PRIMARY KEY NOT NULL,
              conversation_id TEXT NOT NULL, client_message_id TEXT NOT NULL,
              sender TEXT NOT NULL DEFAULT 'user',
              text TEXT NOT NULL DEFAULT '',
              created_at TEXT NOT NULL, attachment_ids_json TEXT NOT NULL,
              state TEXT NOT NULL DEFAULT 'CONFIRMED',
              FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id)
            );
            CREATE TABLE IF NOT EXISTS conversation_agent_sessions (
              conversation_id TEXT PRIMARY KEY NOT NULL,
              agent_session_id TEXT, session_key TEXT,
              created_at TEXT NOT NULL, created_via TEXT NOT NULL,
              request_id TEXT,
              FOREIGN KEY (conversation_id) REFERENCES conversations(conversation_id)
            );
            CREATE TABLE IF NOT EXISTS device_requests (
              request_id TEXT PRIMARY KEY NOT NULL, device_id TEXT NOT NULL,
              pairing_generation INTEGER NOT NULL, grant_revision INTEGER NOT NULL,
              risk TEXT NOT NULL, state TEXT NOT NULL,
              capability_json TEXT NOT NULL, provider_json TEXT NOT NULL,
              parameters_json TEXT NOT NULL, created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL, requires_foreground_confirmation INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS claim_receipts (
              claim_id TEXT PRIMARY KEY NOT NULL, request_id TEXT NOT NULL UNIQUE,
              device_id TEXT NOT NULL, pairing_generation INTEGER NOT NULL,
              grant_revision INTEGER NOT NULL, created_at TEXT NOT NULL,
              FOREIGN KEY (request_id) REFERENCES device_requests(request_id)
            );
            CREATE TABLE IF NOT EXISTS audit_events (
              audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
              event_type TEXT NOT NULL, actor_json TEXT NOT NULL,
              subject_json TEXT NOT NULL, correlation_id TEXT NOT NULL,
              occurred_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS identity_rotation_receipts (
              receipt_id TEXT PRIMARY KEY NOT NULL,
              previous_identity_ref TEXT NOT NULL, next_identity_ref TEXT NOT NULL,
              proof_hash TEXT NOT NULL, master_key_ref TEXT NOT NULL,
              rotated_at TEXT NOT NULL, correlation_id TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS plugin_registry (
              plugin_id TEXT PRIMARY KEY NOT NULL, manifest_json TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS account_credentials (
              credential_id TEXT PRIMARY KEY NOT NULL,
              password_hash TEXT NOT NULL, updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS negotiations (
              negotiation_id TEXT PRIMARY KEY NOT NULL, response_json TEXT NOT NULL,
              created_at TEXT NOT NULL, expires_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS negotiation_bindings (
              negotiation_id TEXT PRIMARY KEY NOT NULL, account_id TEXT NOT NULL,
              installation_id TEXT NOT NULL, bound_at TEXT NOT NULL,
              expires_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS uncertain_outcomes (
              device_id TEXT NOT NULL, request_id TEXT NOT NULL,
              input_hash TEXT NOT NULL, created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL,
              PRIMARY KEY (device_id, request_id)
            );
            """
        )
        existing_cols = {row[1] for row in self.database.execute("PRAGMA table_info(messages)").fetchall()}
        for col_name, col_type in [
            ("sender", "TEXT NOT NULL DEFAULT 'user'"),
            ("text", "TEXT NOT NULL DEFAULT ''"),
            ("state", "TEXT NOT NULL DEFAULT 'CONFIRMED'"),
        ]:
            if col_name not in existing_cols:
                self.database.execute(f"ALTER TABLE messages ADD COLUMN {col_name} {col_type}")
        if self.aead is None:
            existing_ref = self.database.execute(
                "SELECT value FROM account_metadata WHERE key = 'master_key_ref'"
            ).fetchone()
            if existing_ref is None or not existing_ref[0] or str(existing_ref[0]).startswith("host-secret:"):
                self.database.execute(
                    "INSERT INTO account_metadata(key, value) VALUES ('master_key_ref', '') "
                    "ON CONFLICT(key) DO UPDATE SET value = ''"
                )
        else:
            existing_ref = self.database.execute(
                "SELECT value FROM account_metadata WHERE key = 'master_key_ref'"
            ).fetchone()
            if (
                existing_ref is not None
                and existing_ref[0]
                and not str(existing_ref[0]).startswith("host-secret:")
                and str(existing_ref[0]) != self.master_key_ref
            ):
                raise GatewayError("MASTER_KEY_REFERENCE_MISMATCH")
            self.database.execute(
                "INSERT INTO account_metadata(key, value) VALUES ('master_key_ref', ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (self.master_key_ref,),
            )
        self._metadata("gateway_identity_ref", "spki_initial")
        self._metadata("pairing_generation", "1")
        self._metadata("deployment_id", f"deploy_{hashlib.sha256(str(paths.root.parent.parent).encode('utf-8')).hexdigest()[:16]}")
        self._metadata("tls_spki_sha256", "sha256:" + "0" * 64)

    def _metadata(self, key: str, value: str) -> None:
        self.database.execute(
            "INSERT OR IGNORE INTO account_metadata(key, value) VALUES (?, ?)",
            (key, value),
        )

    def require_aead(self) -> Any:
        if self.aead is None:
            raise GatewayError("MASTER_KEY_UNAVAILABLE")
        return self.aead

    def seal_bytes(self, plaintext: bytes, purpose: str) -> str:
        provider = self.require_aead()
        try:
            ciphertext = provider.encrypt(bytes(plaintext), purpose.encode("utf-8"))
        except Exception as exc:
            raise GatewayError("ENCRYPTION_FAILED") from exc
        if not isinstance(ciphertext, (bytes, bytearray, memoryview)):
            raise GatewayError("ENCRYPTION_FAILED")
        encoded = base64.urlsafe_b64encode(bytes(ciphertext)).decode("ascii")
        return "aead-v1:" + encoded

    def open_bytes(self, sealed: str, purpose: str) -> bytes:
        provider = self.require_aead()
        if not isinstance(sealed, str) or not sealed.startswith("aead-v1:"):
            raise GatewayError("DECRYPTION_FAILED")
        try:
            ciphertext = base64.b64decode(sealed[8:], altchars=b"-_", validate=True)
            plaintext = provider.decrypt(ciphertext, purpose.encode("utf-8"))
        except Exception as exc:
            raise GatewayError("DECRYPTION_FAILED") from exc
        if not isinstance(plaintext, (bytes, bytearray, memoryview)):
            raise GatewayError("DECRYPTION_FAILED")
        return bytes(plaintext)

    def seal_json(self, value: Any, purpose: str) -> str:
        return self.seal_bytes(_json(value).encode("utf-8"), purpose)

    def open_json(self, sealed: str, purpose: str) -> Any:
        try:
            return json.loads(self.open_bytes(sealed, purpose).decode("utf-8"))
        except GatewayError:
            raise
        except (UnicodeDecodeError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise GatewayError("DECRYPTION_FAILED") from exc

    @contextmanager
    def transaction(self, unknown_marker: Mapping[str, Any] | None = None) -> Iterator[sqlite3.Connection]:
        nested = self._transaction_depth > 0
        if not nested:
            self.database.execute("BEGIN IMMEDIATE")
        self._transaction_depth += 1
        try:
            yield self.database
        except BaseException as exc:
            if not nested:
                try:
                    self.database.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                self._transaction_depth = 0
                # Rolled back: these events never happened, so nothing may be
                # delivered for them.
                self._pending_events.clear()
                if isinstance(exc, TransactionOutcomeUnknown):
                    self._reopen_after_unknown()
                    self._persist_uncertain_marker(unknown_marker)
            else:
                self._transaction_depth -= 1
            raise
        if nested:
            self._transaction_depth -= 1
            return
        try:
            if self.fail_next_commit:
                self.fail_next_commit = False
                raise sqlite3.OperationalError("injected commit failure")
            if self.commit_hook is not None:
                self.commit_hook()
            self.database.execute("COMMIT")
        except BaseException as exc:
            self._transaction_depth = 0
            # The commit outcome is unknown, so whether these events are durable
            # is unknown too: claiming delivery would tell the phone about a fact
            # it might not be able to read back.
            self._pending_events.clear()
            self._reopen_after_unknown()
            self._persist_uncertain_marker(unknown_marker)
            raise TransactionOutcomeUnknown({"reason": "transaction commit outcome unknown"}) from exc
        self._transaction_depth = 0
        self._flush_events()

    def record_event(self, event: Mapping[str, Any]) -> None:
        """Hand one appended event to the stream that owns this store.

        Inside a transaction the delivery waits for `COMMIT`: an event must never
        reach a live subscriber before it is durable, or a reconnect would replay
        a fact the phone was already told and could not re-read. Outside one the
        write is already committed, so there is nothing to wait for.
        """
        if self._transaction_depth == 0:
            self._deliver_events([dict(event)])
            return
        self._pending_events.append(dict(event))

    recordEvent = record_event

    def _flush_events(self) -> None:
        pending = self._pending_events
        self._pending_events = []
        self._deliver_events(pending)

    def _deliver_events(self, events: list[dict[str, Any]]) -> None:
        """Deliver committed events; a stream failure never fails the write."""
        sink = self.event_sink
        if sink is None or not events:
            return
        for event in events:
            try:
                sink(self.account_id, event)
            except Exception as exc:  # noqa: BLE001 - delivery is best effort
                logger.warning("[open_android] Event delivery failed: %s", exc)

    def close(self) -> None:
        self.database.close()

    def _reopen_after_unknown(self) -> None:
        try:
            self.database.rollback()
        except sqlite3.Error:
            pass
        try:
            self.database.close()
        except sqlite3.Error:
            pass
        replacement = AccountStore(
            self.paths, self.master_key_ref, self.commit_hook, self.aead,
            account_id=self.account_id, event_sink=self.event_sink,
        )
        self.database = replacement.database
        self._transaction_depth = 0

    def _persist_uncertain_marker(self, marker: Mapping[str, Any] | None) -> None:
        if marker is None:
            return
        self.database.execute(
            "INSERT OR REPLACE INTO uncertain_outcomes(device_id, request_id, input_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            (
                marker["deviceId"], marker["requestId"], marker["inputHash"],
                marker["createdAt"], marker["expiresAt"],
            ),
        )


class CredentialStore:
    """The account password digest, stored beside the account it protects.

    Only a digest is kept: the Gateway can never read a password back, and an
    account without a recorded digest cannot be logged into at all.
    """

    PASSWORD_CREDENTIAL_ID = "password"

    def __init__(self, store: AccountStore):
        self.store = store

    def set_password(self, password: str, now: datetime | str | None = None) -> None:
        digest = hash_password(password)
        self.store.database.execute(
            "INSERT INTO account_credentials(credential_id, password_hash, updated_at) VALUES (?, ?, ?) "
            "ON CONFLICT(credential_id) DO UPDATE SET "
            "password_hash = excluded.password_hash, updated_at = excluded.updated_at",
            (self.PASSWORD_CREDENTIAL_ID, digest, iso_millis(now)),
        )
        self.store.database.commit()

    def has_password(self) -> bool:
        row = self.store.database.execute(
            "SELECT 1 FROM account_credentials WHERE credential_id = ?",
            (self.PASSWORD_CREDENTIAL_ID,),
        ).fetchone()
        return row is not None

    def verify_password(self, password: str) -> bool:
        row = self.store.database.execute(
            "SELECT password_hash FROM account_credentials WHERE credential_id = ?",
            (self.PASSWORD_CREDENTIAL_ID,),
        ).fetchone()
        if row is None:
            return False
        return verify_password(password, str(row[0]))

    setPassword = set_password
    hasPassword = has_password
    verifyPassword = verify_password


class EventStore:
    def __init__(self, store: AccountStore, retention_seconds: int = 86_400):
        self.store = store
        self.retention_seconds = retention_seconds

    def append(
        self,
        event_type: str,
        correlation_id: str,
        payload: Mapping[str, Any],
        now: datetime | str | None = None,
    ) -> dict[str, Any]:
        current = _now(now)
        event = {
            "eventId": f"evt_{uuid.uuid4()}",
            "eventType": event_type,
            "correlationId": correlation_id,
            "occurredAt": iso_millis(current),
            "payload": dict(payload),
            "expiresAt": iso_millis(current + timedelta(seconds=self.retention_seconds)),
        }
        self.store.database.execute(
            "INSERT INTO events(event_id, event_type, correlation_id, occurred_at, payload_json, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
            (
                event["eventId"], event_type, correlation_id, event["occurredAt"],
                self.store.seal_json(payload, f"event:{event['eventId']}:payload"), event["expiresAt"],
            ),
        )
        # Live delivery is decided by the store, not by whichever caller appended
        # the event: a durable event that only reaches the phone on reconnect is
        # how a command result the Agent already produced stays invisible.
        self.store.record_event(event)
        return event

    def read_after(self, cursor: str | None, now: datetime | str | None = None) -> list[dict[str, Any]]:
        current = _now(now)
        current_iso = iso_millis(current)
        if cursor is not None:
            row = self.store.database.execute("SELECT expires_at FROM events WHERE event_id = ?", (cursor,)).fetchone()
            if row is None or _now(row["expires_at"]) <= current:
                raise GatewayError("CURSOR_EXPIRED")
            rows = self.store.database.execute(
                """
                SELECT * FROM events
                WHERE expires_at > ? AND (occurred_at, event_id) >
                  (SELECT occurred_at, event_id FROM events WHERE event_id = ?)
                ORDER BY occurred_at, event_id
                """,
                (current_iso, cursor),
            ).fetchall()
        else:
            rows = self.store.database.execute(
                "SELECT * FROM events WHERE expires_at > ? ORDER BY occurred_at, event_id", (current_iso,)
            ).fetchall()
        return [self._map(row) for row in rows]

    def _map(self, row: sqlite3.Row) -> dict[str, Any]:
        return {
            "eventId": row["event_id"],
            "eventType": row["event_type"],
            "correlationId": row["correlation_id"],
            "occurredAt": row["occurred_at"],
            "payload": self.store.open_json(row["payload_json"], f"event:{row['event_id']}:payload"),
            "expiresAt": row["expires_at"],
        }

    readAfter = read_after


class AgentSessionBindings:
    """Which Agent session owns one conversation's memory (ADR 0043).

    The Agent host holds the long-lived conversation, so the binding between a
    Gateway conversation and the host's own session is a fact about this account
    rather than a per-connection guess. Recording it is what makes "switch the
    conversation" mean "switch the memory": a conversation created by `/new` owns
    a session of its own, and a conversation someone opens again lands back on the
    session it already had.

    `agent_session_id` stays NULL until the host reports one. A placeholder would
    be an invented fact, and the phone would then be routed to a session that does
    not exist.
    """

    # Where a binding row came from. The command entry is the only path that also
    # creates the Agent session up front; the others reuse or create lazily.
    CREATED_VIA_NEW_COMMAND = "new-command"
    CREATED_VIA_CONVERSATION_READ = "conversation-read"
    CREATED_VIA_INBOUND_MESSAGE = "inbound-message"

    def __init__(self, store: AccountStore):
        self.store = store

    def record(
        self, conversation_id: str, created_via: str,
        request_id: str | None = None, now: datetime | str | None = None,
    ) -> dict[str, Any]:
        """Record that this conversation exists and owes the host a session.

        Idempotent by primary key: re-recording the same conversation never
        replaces the session it already has.
        """
        current = iso_millis(_now(now))
        self.store.database.execute(
            """
            INSERT INTO conversation_agent_sessions(
              conversation_id, agent_session_id, session_key, created_at, created_via, request_id
            ) VALUES (?, NULL, NULL, ?, ?, ?)
            ON CONFLICT(conversation_id) DO NOTHING
            """,
            (conversation_id, current, created_via, request_id),
        )
        binding = self.lookup(conversation_id)
        return binding if binding is not None else {
            "conversationId": conversation_id, "agentSessionId": None,
            "sessionKey": None, "createdAt": current, "createdVia": created_via,
            "requestId": request_id,
        }

    record_binding = record

    def attach(
        self, conversation_id: str, agent_session_id: str, session_key: str | None = None,
    ) -> bool:
        """Fill in the host's session id for a conversation that already has a row.

        The first session a conversation is bound to is kept: contract §7.1 makes
        the binding a fact about the account, so a later, slower ensure (a message
        or a read that raced the command entry) must not re-point a conversation
        to a second Agent session. A host that reports a *different* session for an
        already-bound conversation is recorded as the divergence it is, rather
        than silently rewriting the row.
        """
        existing = self.lookup(conversation_id)
        if existing is None:
            return False
        bound = existing["agentSessionId"]
        if isinstance(bound, str) and bound:
            if bound != agent_session_id:
                logger.warning(
                    "[open_android] Conversation %s is bound to Agent session %s but the host reported %s",
                    conversation_id, bound, agent_session_id,
                )
            return False
        cursor = self.store.database.execute(
            """
            UPDATE conversation_agent_sessions SET agent_session_id = ?, session_key = ?
            WHERE conversation_id = ? AND agent_session_id IS NULL
            """,
            (agent_session_id, session_key, conversation_id),
        )
        return cursor.rowcount > 0

    def lookup(self, conversation_id: str) -> dict[str, Any] | None:
        row = self.store.database.execute(
            "SELECT * FROM conversation_agent_sessions WHERE conversation_id = ?", (conversation_id,)
        ).fetchone()
        return None if row is None else self._map(row)

    def list(self) -> list[dict[str, Any]]:
        rows = self.store.database.execute(
            "SELECT * FROM conversation_agent_sessions ORDER BY created_at, conversation_id"
        ).fetchall()
        return [self._map(row) for row in rows]

    def _map(self, row: sqlite3.Row) -> dict[str, Any]:
        return {
            "conversationId": row["conversation_id"],
            "agentSessionId": row["agent_session_id"],
            "sessionKey": row["session_key"],
            "createdAt": row["created_at"],
            "createdVia": row["created_via"],
            "requestId": row["request_id"],
        }


class AttachmentStore:
    def __init__(
        self, account_id: str, paths: AccountPaths, store: AccountStore,
        audit: AuditStore, policy: AttachmentPolicy | None = None,
        events: EventStore | None = None,
    ):
        self.account_id = account_id
        self.paths = paths
        self.store = store
        self.audit = audit
        self.policy = policy or DEFAULT_ATTACHMENT_POLICY
        self.events = events
        self.cas_dir = paths.attachments / "cas"
        self.cas_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            self.cas_dir.chmod(0o700)
        except OSError:
            pass
        self._reconcile_staged_files()

    def create(self, **input: Any) -> dict[str, Any]:
        self.store.require_aead()
        current = _now(input.get("now"))
        attachment_id = f"att_{uuid.uuid4()}"
        raw_media_type = input["media_type"] if "media_type" in input else input["mediaType"]
        media_type = str(raw_media_type).split(";")[0].strip().lower()
        size_bytes = int(input["size_bytes"] if "size_bytes" in input else input["sizeBytes"])
        if (
            size_bytes < 0
            or size_bytes > self.policy.max_single_attachment_bytes
            or (
                media_type not in self.policy.allowed_media_types
                and not media_type.startswith(("image/", "audio/", "video/", "text/"))
            )
        ):
            raise GatewayError("ATTACHMENT_LIMIT_EXCEEDED")
        requested_expiry = input.get("expires_at") or input.get("expiresAt")
        if requested_expiry is None:
            expires_at = iso_millis(current + timedelta(seconds=self.policy.attachment_ttl_seconds))
        else:
            try:
                expiry = _now(requested_expiry)
            except (TypeError, ValueError, OverflowError) as exc:
                raise GatewayError("SCHEMA_INVALID") from exc
            maximum_expiry = current + timedelta(seconds=self.policy.attachment_ttl_seconds)
            if expiry <= current or expiry > maximum_expiry:
                raise GatewayError("SCHEMA_INVALID")
            expires_at = iso_millis(expiry)
        self.store.database.execute(
            """
            INSERT INTO attachments(attachment_id, client_attachment_id, filename, media_type, size_bytes,
              sha256, state, content_path, cas_path, created_at, expires_at, delivered_at, acknowledged_at)
            VALUES (?, ?, ?, ?, ?, ?, 'created', NULL, NULL, ?, ?, NULL, NULL)
            """,
            (
                attachment_id,
                input["client_attachment_id"] if "client_attachment_id" in input else input["clientAttachmentId"],
                input["filename"], media_type, size_bytes,
                input["sha256"], iso_millis(current), expires_at,
            ),
        )
        self.audit.append(
            "attachment.created", {"accountId": self.account_id},
            {"attachmentId": attachment_id, "mediaType": media_type, "sizeBytes": size_bytes},
            input["correlation_id"] if "correlation_id" in input else input["correlationId"], current,
        )
        return self.get(attachment_id, current)

    def get(self, attachment_id: str, now: datetime | str | None = None) -> dict[str, Any]:
        row, _ = self._expire_if_due(attachment_id, _now(now))
        path = row["content_path"]
        return {
            "attachmentId": row["attachment_id"], "state": row["state"],
            "filename": row["filename"], "mediaType": row["media_type"],
            "sizeBytes": int(row["size_bytes"]), "sha256": row["sha256"],
            "hasStagedBytes": bool(path and Path(path).is_file()), "expiresAt": row["expires_at"],
        }

    def require_verified_for_message(self, attachment_id: str, now: datetime | str | None = None) -> None:
        if self.get(attachment_id, now)["state"] != "verified":
            raise GatewayError("ATTACHMENT_EXPIRED")

    def upload_content(
        self, attachment_id: str, content: bytes, now: datetime | str | None = None,
    ) -> dict[str, Any]:
        stage_path = self.paths.attachments / f"{attachment_id}.stage"
        row, expired = self._expire_if_due(attachment_id, _now(now))
        if expired or row["state"] == "expired":
            raise GatewayError("ATTACHMENT_EXPIRED")
        try:
            with self.store.transaction():
                row = self._row(attachment_id)
                if int(row["size_bytes"]) != len(content):
                    raise GatewayError("ATTACHMENT_DIGEST_MISMATCH")
                next_state = next_attachment_state(row["state"], "begin_upload")
                encrypted = self.store.seal_bytes(bytes(content), self._attachment_aad(row["sha256"]))
                stage_path.write_bytes(encrypted.encode("ascii"))
                try:
                    stage_path.chmod(0o600)
                except OSError:
                    pass
                self.store.database.execute(
                    "UPDATE attachments SET state = ?, content_path = ? WHERE attachment_id = ?",
                    (next_state, str(stage_path), attachment_id),
                )
        except BaseException:
            # A filesystem write may have preceded a rolled-back database row;
            # retain and reconcile that unique stage rather than discarding it.
            self._reconcile_staged_files()
            raise
        return self.get(attachment_id, now)

    def commit(self, attachment_id: str, now: datetime | str | None = None) -> dict[str, Any]:
        mismatch = False
        row, expired = self._expire_if_due(attachment_id, _now(now))
        if expired or row["state"] == "expired":
            raise GatewayError("ATTACHMENT_EXPIRED")
        with self.store.transaction():
            row = self._row(attachment_id)
            stage_path = self._require_content_path(row)
            try:
                sealed = stage_path.read_bytes().decode("ascii")
                content = self.store.open_bytes(sealed, self._attachment_aad(row["sha256"]))
            except GatewayError:
                raise
            except (OSError, UnicodeDecodeError) as exc:
                raise GatewayError("DECRYPTION_FAILED") from exc
            digest = hashlib.sha256(content).hexdigest()
            if digest != row["sha256"] or len(content) != int(row["size_bytes"]):
                self.store.database.execute(
                    "UPDATE attachments SET state = ?, content_path = ? WHERE attachment_id = ?",
                    (next_attachment_state(row["state"], "fail"), str(stage_path), attachment_id),
                )
                mismatch = True
            else:
                cas_path = self.cas_dir / row["sha256"]
                if not cas_path.exists():
                    shutil.copyfile(stage_path, cas_path)
                    try:
                        cas_path.chmod(0o600)
                    except OSError:
                        pass
                self.store.database.execute(
                    "UPDATE attachments SET state = ?, cas_path = ? WHERE attachment_id = ?",
                    (next_attachment_state(row["state"], "verify"), str(cas_path), attachment_id),
                )
        if mismatch:
            raise GatewayError("ATTACHMENT_DIGEST_MISMATCH")
        return self.get(attachment_id, now)

    def mark_delivered(self, attachment_id: str, now: datetime | str | None = None) -> dict[str, Any]:
        current = _now(now)
        row, expired = self._expire_if_due(attachment_id, current)
        if expired or row["state"] == "expired":
            raise GatewayError("ATTACHMENT_EXPIRED")
        with self.store.transaction():
            row = self._row(attachment_id)
            state = next_attachment_state(row["state"], "deliver")
            self.store.database.execute(
                "UPDATE attachments SET state = ?, delivered_at = ? WHERE attachment_id = ?",
                (state, iso_millis(current), attachment_id),
            )
        return self.get(attachment_id, now)

    def acknowledge(self, attachment_id: str, correlation_id: str, now: datetime | str | None = None) -> dict[str, Any]:
        current = _now(now)
        row, expired = self._expire_if_due(attachment_id, current)
        if expired or row["state"] == "expired":
            raise GatewayError("ATTACHMENT_EXPIRED")
        paths: list[Path] = []
        with self.store.transaction():
            row = self._row(attachment_id)
            for key in ("content_path", "cas_path"):
                if row[key]:
                    paths.append(Path(row[key]))
            state = next_attachment_state(row["state"], "acknowledge")
            self.store.database.execute(
                "UPDATE attachments SET state = ?, content_path = NULL, cas_path = NULL, acknowledged_at = ? WHERE attachment_id = ?",
                (state, iso_millis(current), attachment_id),
            )
            self.audit.append(
                "attachment.acknowledged", {"accountId": self.account_id},
                {"attachmentId": attachment_id}, correlation_id, current,
            )
            if self.events is None:
                raise GatewayError("INTERNAL_ERROR")
            self.events.append(
                "attachment.acknowledged", correlation_id,
                {"attachmentId": attachment_id}, current,
            )
        cas_path = next((path for path in paths if path.parent == self.cas_dir), None)
        if cas_path is not None:
            remaining = self.store.database.execute(
                "SELECT 1 FROM attachments WHERE cas_path = ? LIMIT 1", (str(cas_path),)
            ).fetchone()
            if remaining is not None:
                paths = [path for path in paths if path != cas_path]
        for path in paths:
            self._move_to_trash(path)
        return self.get(attachment_id, now)

    def expire_due(self, now: datetime | str | None = None) -> int:
        current = _now(now)
        self._reconcile_staged_files()
        rows = self.store.database.execute(
            "SELECT attachment_id FROM attachments WHERE expires_at <= ? AND state IN ('created', 'uploading', 'verified', 'delivered')",
            (iso_millis(current),),
        ).fetchall()
        expired = 0
        for row_ref in rows:
            paths: list[Path] = []
            with self.store.transaction():
                row = self._row(row_ref["attachment_id"])
                if _now(row["expires_at"]) > current or row["state"] not in {"created", "uploading", "verified", "delivered"}:
                    continue
                for key in ("content_path", "cas_path"):
                    if row[key]:
                        paths.append(Path(row[key]))
                state = next_attachment_state(row["state"], "expire")
                self.store.database.execute(
                    "UPDATE attachments SET state = ?, content_path = NULL, cas_path = NULL WHERE attachment_id = ?",
                    (state, row["attachment_id"]),
                )
                expired += 1
            for path in paths:
                self._move_unreferenced_path(path)
        return expired

    def cleanup(self, now: datetime | str | None = None) -> int:
        self.expire_due(now)
        protected = self._reconcile_staged_files()
        paths: list[Path] = []
        with self.store.transaction():
            rows = self.store.database.execute(
                "SELECT attachment_id, content_path, cas_path, state FROM attachments WHERE state IN ('acknowledged', 'failed', 'expired')"
            ).fetchall()
            for row in rows:
                for key in ("content_path", "cas_path"):
                    if row[key]:
                        paths.append(Path(row[key]))
                self.store.database.execute(
                    "UPDATE attachments SET state = ?, content_path = NULL, cas_path = NULL WHERE attachment_id = ?",
                    (next_attachment_state(row["state"], "cleanup"), row["attachment_id"]),
                )
            referenced = {
                Path(item[0]) for item in self.store.database.execute(
                    "SELECT content_path FROM attachments WHERE content_path IS NOT NULL"
                ).fetchall()
            }
            referenced.update(
                Path(item[0]) for item in self.store.database.execute(
                    "SELECT cas_path FROM attachments WHERE cas_path IS NOT NULL"
                ).fetchall()
            )
            for staged in self.paths.attachments.glob("*.stage"):
                if staged not in referenced and staged not in protected:
                    paths.append(staged)
            for cas in self.cas_dir.glob("*"):
                if cas.is_file() and cas not in referenced:
                    paths.append(cas)
        deleted = 0
        for path in set(paths):
            if self._move_to_trash(path):
                deleted += 1
        return deleted

    def _expire_if_due(self, attachment_id: str, current: datetime) -> tuple[sqlite3.Row, bool]:
        paths: list[Path] = []
        expired = False
        with self.store.transaction():
            row = self._row(attachment_id)
            if (
                _now(row["expires_at"]) <= current
                and row["state"] in {"created", "uploading", "verified", "delivered"}
            ):
                for key in ("content_path", "cas_path"):
                    if row[key]:
                        paths.append(Path(row[key]))
                self.store.database.execute(
                    "UPDATE attachments SET state = 'expired', content_path = NULL, cas_path = NULL WHERE attachment_id = ?",
                    (attachment_id,),
                )
                row = self._row(attachment_id)
                expired = True
        for path in paths:
            self._move_unreferenced_path(path)
        return row, expired

    def _row(self, attachment_id: str) -> sqlite3.Row:
        row = self.store.database.execute("SELECT * FROM attachments WHERE attachment_id = ?", (attachment_id,)).fetchone()
        if row is None:
            raise GatewayError("ATTACHMENT_EXPIRED")
        return row

    def _attachment_aad(self, sha256: str) -> str:
        return f"open-android-intelligence:attachment:v1:{self.account_id}:{sha256}"

    def _require_content_path(self, row: sqlite3.Row) -> Path:
        path = Path(row["content_path"]) if row["content_path"] else None
        if path is None or not path.is_file():
            raise GatewayError("ATTACHMENT_EXPIRED")
        return path

    @staticmethod
    def _trash_root() -> Path:
        root = Path("/tmp/Open Android Intelligence-trash")
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        return root

    @classmethod
    def _move_to_trash(cls, path: Path) -> bool:
        if not path.exists():
            return False
        destination = cls._trash_root() / f"{uuid.uuid4()}-{path.name}"
        try:
            shutil.move(str(path), str(destination))
            return True
        except OSError:
            return False

    def _move_unreferenced_path(self, path: Path) -> bool:
        if path.parent == self.cas_dir:
            referenced = self.store.database.execute(
                "SELECT 1 FROM attachments WHERE cas_path = ? LIMIT 1", (str(path),)
            ).fetchone()
            if referenced is not None:
                return False
        return self._move_to_trash(path)

    def _reconcile_staged_files(self) -> set[Path]:
        protected: set[Path] = set()
        for path in self.paths.attachments.glob("*.stage"):
            attachment_id = path.name[:-len(".stage")]
            row = self.store.database.execute("SELECT state, content_path FROM attachments WHERE attachment_id = ?", (attachment_id,)).fetchone()
            if row is None or row["state"] not in {"created", "uploading", "verified", "delivered", "failed"}:
                continue
            if row["content_path"] != str(path) or row["state"] == "created":
                try:
                    self.store.database.execute(
                        "UPDATE attachments SET state = ?, content_path = ? WHERE attachment_id = ?",
                        ("uploading" if row["state"] == "created" else row["state"], str(path), attachment_id),
                    )
                except sqlite3.Error:
                    protected.add(path)
        return protected

    uploadContent = upload_content
    markDelivered = mark_delivered
    expireDue = expire_due
    requireVerifiedForMessage = require_verified_for_message


class DeviceRequestStore:
    def __init__(self, account_id: str, store: AccountStore, audit: AuditStore, events: EventStore, contracts: ContractRegistry | None = None):
        self.account_id = account_id
        self.store = store
        self.audit = audit
        self.events = events
        self.contracts = contracts or ContractRegistry()

    def enqueue(
        self, request_id: str | None = None, device_id: str | None = None,
        pairing_generation: int | None = None, grant_revision: int | None = None,
        risk: str | None = None, capability: Mapping[str, Any] | None = None,
        provider: Mapping[str, Any] | None = None, parameters: Mapping[str, Any] | None = None,
        correlation_id: str | None = None, now: datetime | str | None = None,
        requires_foreground_confirmation: bool | None = None, **aliases: Any,
    ) -> dict[str, Any]:
        request_id = request_id if request_id is not None else aliases.pop("requestId")
        device_id = device_id if device_id is not None else aliases.pop("deviceId")
        pairing_generation = pairing_generation if pairing_generation is not None else aliases.pop("pairingGeneration")
        grant_revision = grant_revision if grant_revision is not None else aliases.pop("grantRevision")
        risk = risk if risk is not None else aliases.pop("risk")
        capability = capability if capability is not None else aliases.pop("capability")
        provider = provider if provider is not None else aliases.pop("provider")
        parameters = parameters if parameters is not None else aliases.pop("parameters")
        correlation_id = correlation_id if correlation_id is not None else aliases.pop("correlationId")
        requires_foreground_confirmation = (
            requires_foreground_confirmation
            if requires_foreground_confirmation is not None
            else aliases.pop("requiresForegroundConfirmation", False)
        )
        current = _now(now)
        ttl = maximum_device_request_queue_seconds(risk)
        state = "expired" if ttl == 0 else "pending"
        expires_at = iso_millis(current + timedelta(seconds=ttl))
        record = {
            "requestId": request_id,
            "capability": dict(capability),
            "provider": dict(provider),
            "parameters": dict(parameters),
            "risk": risk,
            "grantRevision": int(grant_revision),
            "createdAt": iso_millis(current),
            "expiresAt": expires_at,
            "requiresForegroundConfirmation": bool(requires_foreground_confirmation),
        }
        if not self.contracts.validate_dispatched(
            self.contracts.dispatched_registry_id, {"kind": "device.request"}, record
        ):
            raise GatewayError("SCHEMA_INVALID")
        parameters_json = self.store.seal_json(
            parameters, f"device-request:{self.account_id}:{request_id}:parameters"
        )
        with self.store.transaction():
            self.store.database.execute(
                """
                INSERT INTO device_requests(request_id, device_id, pairing_generation, grant_revision, risk, state,
                  capability_json, provider_json, parameters_json, created_at, expires_at, requires_foreground_confirmation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (request_id, device_id, pairing_generation, grant_revision, risk, state,
                 _json(capability), _json(provider), parameters_json, record["createdAt"], expires_at,
                 int(record["requiresForegroundConfirmation"])),
            )
            if state == "pending":
                self.events.append("device.requested", correlation_id, record, current)
            self.audit.append(
                "device.request.enqueued", {"accountId": self.account_id, "deviceId": device_id},
                {"requestId": request_id, "risk": risk, "grantRevision": grant_revision, "state": state},
                correlation_id, current,
            )
        return self.get(request_id)

    def claim(
        self, request_id: str, device_id: str, pairing_generation: int,
        grant_revision: int, correlation_id: str, now: datetime | str | None = None,
    ) -> dict[str, Any]:
        current = _now(now)
        expired = False
        with self.store.transaction():
            row = self._row(request_id)
            self._assert_binding(row, device_id, pairing_generation, grant_revision)
            if self._expire_if_due(row, current):
                expired = True
            else:
                receipt = self.store.database.execute("SELECT * FROM claim_receipts WHERE request_id = ?", (request_id,)).fetchone()
                if receipt is not None:
                    return self._receipt(receipt)
                if row["state"] != "pending":
                    raise GatewayError("OUTCOME_UNKNOWN")
                changed = self.store.database.execute(
                    "UPDATE device_requests SET state = 'claimed' WHERE request_id = ? AND state = 'pending' AND device_id = ? AND pairing_generation = ? AND grant_revision = ?",
                    (request_id, device_id, pairing_generation, grant_revision),
                ).rowcount
                if changed != 1:
                    raise GatewayError("OUTCOME_UNKNOWN")
                claim_id = f"claim_{uuid.uuid4()}"
                self.store.database.execute(
                    "INSERT INTO claim_receipts(claim_id, request_id, device_id, pairing_generation, grant_revision, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    (claim_id, request_id, device_id, pairing_generation, grant_revision, iso_millis(current)),
                )
                self.audit.append(
                    "device.request.claimed", {"accountId": self.account_id, "deviceId": device_id},
                    {"requestId": request_id, "claimId": claim_id, "grantRevision": grant_revision},
                    correlation_id, current,
                )
                return {
                    "claimId": claim_id, "requestId": request_id, "accountId": self.account_id,
                    "deviceId": device_id, "pairingGeneration": pairing_generation,
                    "grantRevision": grant_revision,
                }
        if expired:
            raise GatewayError("OUTCOME_UNKNOWN")
        raise GatewayError("OUTCOME_UNKNOWN")

    def submit_result(
        self, request_id: str, device_id: str, pairing_generation: int,
        grant_revision: int, claim_id: str, result: Mapping[str, Any],
        correlation_id: str, now: datetime | str | None = None,
    ) -> dict[str, Any]:
        current = _now(now)
        expired = False
        record: dict[str, Any] | None = None
        with self.store.transaction():
            row = self._row(request_id)
            self._assert_binding(row, device_id, pairing_generation, grant_revision)
            if self._expire_if_due(row, current):
                expired = True
            else:
                receipt = self.store.database.execute(
                    "SELECT * FROM claim_receipts WHERE request_id = ? AND claim_id = ?", (request_id, claim_id)
                ).fetchone()
                if receipt is None:
                    raise GatewayError("OUTCOME_UNKNOWN")
                self._assert_binding(receipt, device_id, pairing_generation, grant_revision)
                if row["state"] not in {"claimed", "cancel_requested"}:
                    raise GatewayError("OUTCOME_UNKNOWN")
                outcome = result.get("outcome")
                event = "result_outcome_unknown" if outcome == "outcome_unknown" else f"result_{outcome}"
                next_state = next_device_request_state(row["state"], event)
                self.store.database.execute("UPDATE device_requests SET state = ? WHERE request_id = ?", (next_state, request_id))
                self.audit.append(
                    "device.request.result", {"accountId": self.account_id, "deviceId": device_id},
                    {"requestId": request_id, "claimId": claim_id, "outcome": outcome},
                    correlation_id, current,
                )
                record = self.get(request_id)
        if expired:
            raise GatewayError("OUTCOME_UNKNOWN")
        if record is None:
            raise GatewayError("OUTCOME_UNKNOWN")
        return record

    def validate_claim_replay(
        self, request_id: str, device_id: str, pairing_generation: int,
        grant_revision: int, now: datetime | str | None = None,
    ) -> str | None:
        current = _now(now)
        with self.store.transaction():
            row = self._row(request_id)
            self._assert_binding(row, device_id, pairing_generation, grant_revision)
            if self._expire_if_due(row, current):
                return "OUTCOME_UNKNOWN"
            receipt = self.store.database.execute("SELECT * FROM claim_receipts WHERE request_id = ?", (request_id,)).fetchone()
            if receipt is None:
                raise GatewayError("OUTCOME_UNKNOWN")
            self._assert_binding(receipt, device_id, pairing_generation, grant_revision)
            return None

    def validate_result_replay(
        self, request_id: str, device_id: str, pairing_generation: int,
        grant_revision: int, claim_id: str, now: datetime | str | None = None,
    ) -> str | None:
        current = _now(now)
        with self.store.transaction():
            row = self._row(request_id)
            self._assert_binding(row, device_id, pairing_generation, grant_revision)
            if self._expire_if_due(row, current):
                return "OUTCOME_UNKNOWN"
            receipt = self.store.database.execute(
                "SELECT * FROM claim_receipts WHERE request_id = ? AND claim_id = ?", (request_id, claim_id)
            ).fetchone()
            if receipt is None:
                raise GatewayError("OUTCOME_UNKNOWN")
            self._assert_binding(receipt, device_id, pairing_generation, grant_revision)
            return None

    def recover_expired(self, now: datetime | str | None = None) -> int:
        current = _now(now)
        rows = self.store.database.execute(
            "SELECT request_id, state FROM device_requests WHERE expires_at <= ? AND state IN ('pending', 'claimed', 'cancel_requested')",
            (iso_millis(current),),
        ).fetchall()
        recovered = 0
        for row in rows:
            with self.store.transaction():
                event = "expire" if row["state"] == "pending" else "recover_outcome_unknown"
                self.store.database.execute(
                    "UPDATE device_requests SET state = ? WHERE request_id = ?",
                    (next_device_request_state(row["state"], event), row["request_id"]),
                )
                recovered += 1
        return recovered

    def cancel(self, request_id: str, device_id: str, pairing_generation: int, grant_revision: int, correlation_id: str, now: datetime | str | None = None) -> dict[str, Any]:
        current = _now(now)
        expired = False
        record: dict[str, Any] | None = None
        with self.store.transaction():
            row = self._row(request_id)
            self._assert_binding(row, device_id, pairing_generation, grant_revision)
            if self._expire_if_due(row, current):
                expired = True
            else:
                next_state = next_device_request_state(row["state"], "cancel")
                self.store.database.execute("UPDATE device_requests SET state = ? WHERE request_id = ?", (next_state, request_id))
                self.events.append("device.request.cancel.requested", correlation_id, {"requestId": request_id}, current)
                record = self.get(request_id)
        if expired:
            raise GatewayError("OUTCOME_UNKNOWN")
        if record is None:
            raise GatewayError("OUTCOME_UNKNOWN")
        return record

    def get(self, request_id: str) -> dict[str, Any]:
        row = self._row(request_id)
        return self._map(row)

    def list(self) -> list[dict[str, Any]]:
        rows = self.store.database.execute("SELECT * FROM device_requests ORDER BY request_id").fetchall()
        return [self._map(row) for row in rows]

    def _map(self, row: sqlite3.Row) -> dict[str, Any]:
        return {
            "requestId": row["request_id"], "deviceId": row["device_id"],
            "pairingGeneration": int(row["pairing_generation"]), "grantRevision": int(row["grant_revision"]),
            "risk": row["risk"], "state": row["state"],
            "capability": json.loads(row["capability_json"]),
            "provider": json.loads(row["provider_json"]),
            "parameters": self.store.open_json(
                row["parameters_json"],
                f"device-request:{self.account_id}:{row['request_id']}:parameters",
            ),
            "createdAt": row["created_at"], "expiresAt": row["expires_at"],
            "requiresForegroundConfirmation": bool(row["requires_foreground_confirmation"]),
        }

    def _row(self, request_id: str) -> sqlite3.Row:
        row = self.store.database.execute("SELECT * FROM device_requests WHERE request_id = ?", (request_id,)).fetchone()
        if row is None:
            raise GatewayError("OUTCOME_UNKNOWN")
        return row

    @staticmethod
    def _assert_binding(row: sqlite3.Row, device_id: str, pairing_generation: int, grant_revision: int) -> None:
        if row["device_id"] != device_id or int(row["pairing_generation"]) != pairing_generation:
            raise GatewayError("PAIRING_GENERATION_STALE")
        if int(row["grant_revision"]) != grant_revision:
            raise GatewayError("GRANT_STALE")

    def _expire_if_due(self, row: sqlite3.Row, current: datetime) -> bool:
        if _now(row["expires_at"]) > current or row["state"] not in {"pending", "claimed", "cancel_requested"}:
            return False
        event = "expire" if row["state"] == "pending" else "recover_outcome_unknown"
        self.store.database.execute(
            "UPDATE device_requests SET state = ? WHERE request_id = ?",
            (next_device_request_state(row["state"], event), row["request_id"]),
        )
        return True

    def _receipt(self, row: sqlite3.Row) -> dict[str, Any]:
        return {
            "claimId": row["claim_id"], "requestId": row["request_id"], "accountId": self.account_id,
            "deviceId": row["device_id"], "pairingGeneration": int(row["pairing_generation"]),
            "grantRevision": int(row["grant_revision"]),
        }

    submitResult = submit_result
    validateClaimReplay = validate_claim_replay
    validateResultReplay = validate_result_replay
    recoverExpired = recover_expired


class ConversationPort:
    def __init__(
        self, account_id: str, store: AccountStore, attachments: AttachmentStore,
        audit: AuditStore, policy: AttachmentPolicy | None = None,
    ):
        self.account_id = account_id
        self.store = store
        self.attachments = attachments
        self.audit = audit
        self.policy = policy or DEFAULT_ATTACHMENT_POLICY

    def create(self, client_conversation_id: str, title: str | None, correlation_id: str, now: datetime | str | None = None) -> dict[str, Any]:
        current = _now(now)
        conversation_id = f"conv_{uuid.uuid4()}"
        with self.store.transaction():
            self.store.database.execute(
                "INSERT INTO conversations(conversation_id, client_conversation_id, title, created_at) VALUES (?, ?, ?, ?)",
                (conversation_id, client_conversation_id, title, iso_millis(current)),
            )
            self.audit.append(
                "conversation.created", {"accountId": self.account_id},
                {"conversationId": conversation_id, "clientConversationId": client_conversation_id},
                correlation_id, current,
            )
        return {"conversationId": conversation_id, "clientConversationId": client_conversation_id, "title": title}

    def accept_message(
        self, conversation_id: str, client_message_id: str, text: str,
        attachment_ids: list[str], device_id: str, request_id: str,
        correlation_id: str, now: datetime | str | None = None,
    ) -> dict[str, Any]:
        current = _now(now)
        total_attachment_bytes = 0
        for attachment_id in attachment_ids:
            attachment = self.attachments.get(attachment_id, current)
            if attachment["state"] != "verified":
                raise GatewayError("ATTACHMENT_EXPIRED")
            total_attachment_bytes += int(attachment["sizeBytes"])
        if total_attachment_bytes > self.policy.max_message_attachment_bytes:
            raise GatewayError("ATTACHMENT_LIMIT_EXCEEDED")
        with self.store.transaction():
            row = self.store.database.execute("SELECT conversation_id FROM conversations WHERE conversation_id = ?", (conversation_id,)).fetchone()
            if row is None:
                raise GatewayError("SCHEMA_INVALID")
            message_id = f"msg_{uuid.uuid4()}"
            self.store.database.execute(
                """
                INSERT INTO messages(
                    message_id, conversation_id, client_message_id, sender, text,
                    created_at, attachment_ids_json, state
                ) VALUES (?, ?, ?, 'user', ?, ?, ?, 'CONFIRMED')
                """,
                (message_id, conversation_id, client_message_id, text, iso_millis(current), _json(attachment_ids)),
            )
            self.audit.append(
                "conversation.message.accepted", {"accountId": self.account_id, "deviceId": device_id},
                {"conversationId": conversation_id, "messageId": message_id, "attachmentCount": len(attachment_ids)},
                correlation_id, current,
            )
            return {"status": "accepted", "messageId": message_id, "conversationId": conversation_id}

    def record_assistant_message(
        self, conversation_id: str, message_id: str, text: str,
        now: datetime | str | None = None,
    ) -> dict[str, Any]:
        current = _now(now)
        with self.store.transaction():
            self.store.database.execute(
                """
                INSERT INTO messages(
                    message_id, conversation_id, client_message_id, sender, text,
                    created_at, attachment_ids_json, state
                ) VALUES (?, ?, ?, 'assistant', ?, ?, '[]', 'CONFIRMED')
                ON CONFLICT(message_id) DO UPDATE SET text = excluded.text
                """,
                (message_id, conversation_id, message_id, text, iso_millis(current)),
            )
            return {"status": "recorded", "messageId": message_id, "conversationId": conversation_id}

    def list_messages(
        self, conversation_id: str, client_message_id: str | None = None,
        cursor: str | None = None, limit: int = 50,
    ) -> dict[str, Any]:
        row = self.store.database.execute(
            "SELECT conversation_id FROM conversations WHERE conversation_id = ?",
            (conversation_id,),
        ).fetchone()
        if row is None:
            raise GatewayError("SCHEMA_INVALID")

        query = (
            "SELECT message_id, conversation_id, client_message_id, sender, text, "
            "created_at, attachment_ids_json, state FROM messages WHERE conversation_id = ?"
        )
        params: list[Any] = [conversation_id]
        if client_message_id:
            query += " AND client_message_id = ?"
            params.append(client_message_id)
        query += " ORDER BY created_at ASC LIMIT ?"
        params.append(max(1, min(int(limit), 100)))

        rows = self.store.database.execute(query, params).fetchall()
        result_messages = []
        for r in rows:
            att_ids = []
            try:
                raw_json = r["attachment_ids_json"] if hasattr(r, "keys") and "attachment_ids_json" in r.keys() else r[6]
                att_ids = json.loads(raw_json) if raw_json else []
            except Exception:
                pass

            mid = r["message_id"] if hasattr(r, "keys") and "message_id" in r.keys() else r[0]
            cid = r["conversation_id"] if hasattr(r, "keys") and "conversation_id" in r.keys() else r[1]
            cmid = r["client_message_id"] if hasattr(r, "keys") and "client_message_id" in r.keys() else r[2]
            sender = r["sender"] if hasattr(r, "keys") and "sender" in r.keys() else r[3]
            txt = r["text"] if hasattr(r, "keys") and "text" in r.keys() else r[4]
            created = r["created_at"] if hasattr(r, "keys") and "created_at" in r.keys() else r[5]
            st = r["state"] if hasattr(r, "keys") and "state" in r.keys() else r[7]

            parts = []
            if txt:
                parts.append({"type": "text", "text": txt})
            for aid in att_ids:
                part_dict: dict[str, Any] = {"type": "attachment", "attachmentId": aid}
                try:
                    att_obj = self.attachments.get(aid)
                    if att_obj:
                        part_dict["filename"] = att_obj.get("filename") or ""
                        part_dict["mediaType"] = att_obj.get("mediaType") or ""
                        part_dict["sizeBytes"] = att_obj.get("sizeBytes") or 0
                except Exception:
                    pass
                parts.append(part_dict)

            ts = 0
            try:
                ts = _epoch_millis(created)
            except Exception:
                pass
            if ts <= 0:
                ts = _epoch_millis(_now())

            result_messages.append({
                "messageId": mid,
                "clientMessageId": cmid,
                "conversationId": cid,
                "sender": sender or "assistant",
                "text": txt or "",
                "parts": parts,
                "timestamp": ts,
                "state": st or "CONFIRMED",
                "createdAt": created,
            })
        return {"messages": result_messages, "nextCursor": None, "snapshotRevision": 1}

    def list(self) -> list[dict[str, Any]]:
        rows = self.store.database.execute("SELECT conversation_id, client_conversation_id, title FROM conversations ORDER BY conversation_id").fetchall()
        return [{"conversationId": r[0], "clientConversationId": r[1], "title": r[2]} for r in rows]

    def get(self, conversation_id: str) -> dict[str, Any]:
        row = self.store.database.execute(
            "SELECT conversation_id, client_conversation_id, title FROM conversations WHERE conversation_id = ?",
            (conversation_id,),
        ).fetchone()
        if row is None:
            raise GatewayError("SCHEMA_INVALID")
        return {"conversationId": row[0], "clientConversationId": row[1], "title": row[2]}

    def update_title(
        self, conversation_id: str, title: str, correlation_id: str,
        now: datetime | str | None = None,
    ) -> dict[str, Any]:
        current = _now(now)
        with self.store.transaction():
            row = self.store.database.execute(
                "SELECT conversation_id, client_conversation_id, title FROM conversations WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()
            if row is None:
                raise GatewayError("SCHEMA_INVALID")
            self.store.database.execute(
                "UPDATE conversations SET title = ? WHERE conversation_id = ?",
                (title, conversation_id),
            )
            self.audit.append(
                "conversation.title.updated",
                {"accountId": self.account_id},
                {"conversationId": conversation_id, "title": title},
                correlation_id,
                current,
            )
        return {"conversationId": conversation_id, "clientConversationId": row[1], "title": title}

    acceptMessage = accept_message


class GatewayAccount:
    def __init__(
        self, account_id: str, paths: AccountPaths, store: AccountStore,
        contracts: ContractRegistry | None = None,
        attachment_policy: AttachmentPolicy | None = None,
        credential_verifier: Any = None,
    ):
        self.account_id = account_id
        self.accountId = account_id
        self.paths = paths
        self.store = store
        self.master_key_ref = str(store.database.execute("SELECT value FROM account_metadata WHERE key = 'master_key_ref'").fetchone()[0])
        self.masterKeyRef = self.master_key_ref
        self.audit = AuditStore(store, account_id)
        self.events = EventStore(store)
        self.attachment_policy = attachment_policy or DEFAULT_ATTACHMENT_POLICY
        self.attachments = AttachmentStore(
            account_id, paths, store, self.audit, self.attachment_policy, self.events
        )
        self.device_requests = DeviceRequestStore(account_id, store, self.audit, self.events, contracts)
        self.conversations = ConversationPort(account_id, store, self.attachments, self.audit, self.attachment_policy)
        self.agent_sessions = AgentSessionBindings(store)
        self.credentials = CredentialStore(store)
        self.sessions = SessionService(account_id, store, self.audit, credential_verifier)
        self.deviceRequests = self.device_requests
        self.agentSessions = self.agent_sessions
        self.masterKeyRef = self.master_key_ref
        self._closed = False

    def close(self) -> None:
        if not self._closed:
            self.store.close()
            self._closed = True


class SessionService:
    def __init__(self, account_id: str, store: AccountStore, audit: AuditStore, credential_verifier: Any = None):
        self.account_id = account_id
        self.store = store
        self.audit = audit
        self.credential_verifier = credential_verifier

    @staticmethod
    def _digest(value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    @staticmethod
    def _secret(prefix: str) -> str:
        return f"{prefix}_{secrets.token_urlsafe(32)}"

    def create_password_session(
        self, username: str, password: str, installation: Mapping[str, Any],
        correlation_id: str, now: datetime | str | None = None,
    ) -> dict[str, Any]:
        installation_id = installation.get("installationId") if isinstance(installation, Mapping) else None
        device_public_key = installation.get("devicePublicKey") if isinstance(installation, Mapping) else None
        if (
            not isinstance(username, str) or not username
            or not isinstance(password, str) or not password
            or not isinstance(installation_id, str) or not installation_id
            or not isinstance(device_public_key, str) or not device_public_key
            or self.credential_verifier is None
        ):
            raise GatewayError("AUTHENTICATION_FAILED")
        verifier = getattr(self.credential_verifier, "verify", self.credential_verifier)
        try:
            verified = verifier(self.account_id, username, password, installation)
        except Exception as exc:
            raise GatewayError("AUTHENTICATION_FAILED") from exc
        if verified is not True:
            raise GatewayError("AUTHENTICATION_FAILED")
        current = _now(now)
        with self.store.transaction():
            bundle = self._issue(installation_id, f"dev_{uuid.uuid4()}", current)
            self._register_device_key(bundle["deviceId"], installation_id, device_public_key, current)
            self.audit.append(
                "session.password.created",
                {"accountId": self.account_id, "deviceId": bundle["deviceId"], "installationId": installation_id},
                {"method": "password", "displayName": installation.get("displayName")}, correlation_id, current,
            )
            return bundle

    def refresh(
        self, refresh_credential: str, installation_id: str, device_id: str,
        correlation_id: str, now: datetime | str | None = None,
    ) -> dict[str, Any]:
        current = _now(now)
        reused = False
        bundle: dict[str, Any] | None = None
        with self.store.transaction():
            digest = self._digest(refresh_credential)
            row = self.store.database.execute(
                "SELECT * FROM refresh_credentials WHERE credential_hash = ?", (digest,)
            ).fetchone()
            if row is None or row["installation_id"] != installation_id or row["device_id"] != device_id:
                raise GatewayError("AUTHENTICATION_FAILED")
            if row["status"] != "active":
                self.store.database.execute(
                    "UPDATE refresh_credentials SET status = 'revoked' WHERE installation_id = ? AND device_id = ?",
                    (installation_id, device_id),
                )
                self.audit.append(
                    "session.refresh.reused",
                    {"accountId": self.account_id, "deviceId": device_id, "installationId": installation_id},
                    {"reused": True}, correlation_id, current,
                )
                reused = True
            else:
                bundle = self._issue(installation_id, device_id, current)
                self.store.database.execute(
                    "UPDATE refresh_credentials SET status = 'used', replaced_by_hash = ? WHERE credential_hash = ?",
                    (self._digest(bundle["refreshCredential"]), digest),
                )
                self.audit.append(
                    "session.refresh.rotated",
                    {"accountId": self.account_id, "deviceId": device_id, "installationId": installation_id},
                    {"rotated": True}, correlation_id, current,
                )
        if reused:
            raise GatewayError("REFRESH_REUSED")
        if bundle is None:
            raise GatewayError("AUTHENTICATION_FAILED")
        return bundle

    def register_device_key(
        self, device_id: str, installation_id: str, public_key: str,
        now: datetime | str | None = None,
    ) -> None:
        self._register_device_key(device_id, installation_id, public_key, _now(now))

    def _register_device_key(self, device_id: str, installation_id: str, public_key: str, current: datetime) -> None:
        """Binds the Ed25519 public key a device proved at login.

        The key is the only thing that makes a later request signature
        checkable, so it is written in the same transaction that issues the
        session: a session whose key was never recorded cannot be used.
        """
        self.store.database.execute(
            "INSERT INTO device_keys(device_id, installation_id, public_key, pairing_generation, grant_revision, registered_at) "
            "VALUES (?, ?, ?, 1, 1, ?) "
            "ON CONFLICT(device_id) DO UPDATE SET "
            "installation_id = excluded.installation_id, public_key = excluded.public_key, registered_at = excluded.registered_at",
            (device_id, installation_id, public_key, iso_millis(current)),
        )

    def resolve_session(
        self, access_token: str, session_id: str, device_id: str,
        now: datetime | str | None = None,
    ) -> dict[str, Any] | None:
        """The verified-request facts behind one live access session.

        Returns None instead of raising: the caller is an authentication seam
        that must fail closed without distinguishing "no such session" from
        "expired" to the requester.
        """
        row = self.store.database.execute(
            "SELECT s.session_id AS session_id, s.installation_id AS installation_id, s.status AS status, "
            "s.expires_at AS expires_at, k.public_key AS public_key, "
            "k.pairing_generation AS pairing_generation, k.grant_revision AS grant_revision "
            "FROM access_sessions s LEFT JOIN device_keys k ON k.device_id = s.device_id "
            "WHERE s.session_id = ? AND s.device_id = ? AND s.access_token_hash = ?",
            (session_id, device_id, self._digest(access_token)),
        ).fetchone()
        if row is None or row["status"] != "active" or _now(row["expires_at"]) <= _now(now):
            return None
        public_key = row["public_key"]
        if not isinstance(public_key, str) or not public_key:
            return None
        return {
            "sessionId": str(row["session_id"]),
            "installationId": str(row["installation_id"]),
            "devicePublicKey": public_key,
            "pairingGeneration": int(row["pairing_generation"] or 1),
            "grantRevision": int(row["grant_revision"] or 1),
        }

    def revoke_refresh_credentials(
        self, device_id: str, correlation_id: str, now: datetime | str | None = None,
    ) -> None:
        """Ends the pairing: no refresh credential, and no key left to sign with."""
        current = _now(now)
        with self.store.transaction():
            self.store.database.execute(
                "UPDATE refresh_credentials SET status = 'revoked' WHERE device_id = ? AND status = 'active'",
                (device_id,),
            )
            self.store.database.execute("DELETE FROM device_keys WHERE device_id = ?", (device_id,))
            self.audit.append(
                "session.refresh.revoked",
                {"accountId": self.account_id, "deviceId": device_id}, {}, correlation_id, current,
            )

    def active_refresh_credential_count(self, device_id: str) -> int:
        row = self.store.database.execute(
            "SELECT COUNT(*) FROM refresh_credentials WHERE device_id = ? AND status = 'active'", (device_id,)
        ).fetchone()
        return int(row[0])

    def verify_access_token(self, access_token: str, session_id: str, device_id: str, now: datetime | str | None = None) -> bool:
        row = self.store.database.execute(
            "SELECT access_token_hash, device_id, status, expires_at FROM access_sessions WHERE session_id = ?",
            (session_id,),
        ).fetchone()
        return bool(
            row is not None and row["access_token_hash"] == self._digest(access_token)
            and row["device_id"] == device_id and row["status"] == "active"
            and _now(row["expires_at"]) > _now(now)
        )

    def revoke_session(self, session_id: str, correlation_id: str, now: datetime | str | None = None) -> None:
        current = _now(now)
        with self.store.transaction():
            self.store.database.execute("UPDATE access_sessions SET status = 'revoked' WHERE session_id = ?", (session_id,))
            self.audit.append(
                "session.revoked", {"accountId": self.account_id}, {"sessionId": session_id}, correlation_id, current,
            )

    def _issue(self, installation_id: str, device_id: str, current: datetime) -> dict[str, Any]:
        session_id = f"sess_{uuid.uuid4()}"
        access_token = self._secret("access")
        refresh_credential = self._secret("refresh")
        expires_at = iso_millis(current + timedelta(minutes=15))
        self.store.database.execute(
            "INSERT INTO access_sessions(session_id, installation_id, device_id, access_token_hash, status, created_at, expires_at) VALUES (?, ?, ?, ?, 'active', ?, ?)",
            (session_id, installation_id, device_id, self._digest(access_token), iso_millis(current), expires_at),
        )
        self.store.database.execute(
            "INSERT INTO refresh_credentials(credential_hash, installation_id, device_id, session_id, status, created_at, replaced_by_hash) VALUES (?, ?, ?, ?, 'active', ?, NULL)",
            (self._digest(refresh_credential), installation_id, device_id, session_id, iso_millis(current)),
        )
        return {
            "sessionId": session_id, "deviceId": device_id,
            "accessToken": access_token, "refreshCredential": refresh_credential,
            "expiresAt": expires_at,
        }

    createPasswordSession = create_password_session
    activeRefreshCredentialCount = active_refresh_credential_count


_ATTACHMENT_TRANSITIONS: dict[str, dict[str, str]] = {
    "created": {"begin_upload": "uploading", "fail": "failed", "expire": "expired"},
    "uploading": {"verify": "verified", "fail": "failed", "expire": "expired"},
    "verified": {"deliver": "delivered", "fail": "failed", "expire": "expired"},
    "delivered": {"acknowledge": "acknowledged", "expire": "expired"},
    "acknowledged": {"cleanup": "deleted"},
    "failed": {"cleanup": "deleted"},
    "expired": {"cleanup": "deleted"},
    "deleted": {},
}

_DEVICE_TRANSITIONS: dict[str, dict[str, str]] = {
    "pending": {"claim": "claimed", "cancel": "cancelled", "expire": "expired"},
    "claimed": {
        "cancel": "cancel_requested", "expire": "outcome_unknown",
        "result_succeeded": "succeeded", "result_failed": "failed",
        "result_denied": "denied", "result_cancelled": "cancelled",
        "result_outcome_unknown": "outcome_unknown", "recover_outcome_unknown": "outcome_unknown",
    },
    "cancel_requested": {
        "expire": "outcome_unknown", "result_succeeded": "succeeded",
        "result_failed": "failed", "result_denied": "denied",
        "result_cancelled": "cancelled", "result_outcome_unknown": "outcome_unknown",
        "recover_outcome_unknown": "outcome_unknown",
    },
    "succeeded": {}, "failed": {}, "denied": {}, "cancelled": {},
    "expired": {}, "outcome_unknown": {},
}


def next_attachment_state(current: str, event: str) -> str:
    try:
        return _ATTACHMENT_TRANSITIONS[current][event]
    except KeyError as exc:
        raise GatewayError("INVALID_STATE_TRANSITION") from exc


def next_device_request_state(current: str, event: str) -> str:
    try:
        return _DEVICE_TRANSITIONS[current][event]
    except KeyError as exc:
        raise GatewayError("INVALID_STATE_TRANSITION") from exc


def maximum_device_request_queue_seconds(risk: str) -> int:
    if risk in {"read", "sync"}:
        return 86_400
    if risk == "write":
        return 900
    if risk == "high-privilege-ephemeral":
        return 0
    raise GatewayError("SCHEMA_INVALID")


_PERSISTABLE_ERRORS = {
    "SCHEMA_INVALID", "IDENTITY_OVERRIDE_REJECTED", "PAIRING_GENERATION_STALE",
    "GRANT_STALE", "IDEMPOTENCY_CONFLICT", "OUTCOME_UNKNOWN", "ATTACHMENT_DIGEST_MISMATCH",
    "ATTACHMENT_LIMIT_EXCEEDED",
    "ATTACHMENT_EXPIRED", "MASTER_KEY_UNAVAILABLE", "MASTER_KEY_REFERENCE_MISMATCH",
    "ENCRYPTION_FAILED", "DECRYPTION_FAILED", "CURSOR_CONFLICT", "CURSOR_EXPIRED",
}


COMMAND_CATALOG_FORMAT = "agent-command-catalog-1.0"

DEFAULT_COMMAND_CATALOG: tuple[dict[str, Any], ...] = (
    {
        "id": "new",
        "invocation": "/new",
        "title": "新建对话",
        "description": "结束当前会话并创建全新对话线程",
        "acceptsArguments": False,
        "availability": "available",
    },
    {
        "id": "models",
        "invocation": "/models",
        "title": "[provider]",
        "description": "切换或查看活动大模型与参数",
        "acceptsArguments": True,
        "availability": "available",
    },
    {
        "id": "status",
        "invocation": "/status",
        "title": "网关状态",
        "description": "查看当前网关连通性、时延与配对状态",
        "acceptsArguments": False,
        "availability": "available",
    },
    {
        "id": "review",
        "invocation": "/review",
        "title": "[diff]",
        "description": "审查代码变更、规范或当前文档",
        "acceptsArguments": True,
        "availability": "available",
    },
    {
        "id": "gateway",
        "invocation": "/gateway",
        "title": "<url>",
        "description": "切换或添加连接的 Agent Gateway",
        "acceptsArguments": True,
        "availability": "available",
    },
    {
        "id": "clear",
        "invocation": "/clear",
        "title": "清空时间线",
        "description": "清空当前时间线临时渲染状态",
        "acceptsArguments": False,
        "availability": "available",
    },
    {
        "id": "help",
        "invocation": "/help",
        "title": "[command]",
        "description": "查看所有可用指令与使用指南",
        "acceptsArguments": True,
        "availability": "available",
    },
)


def _contains_identity_override(value: Any) -> bool:
    if isinstance(value, Mapping):
        if any(key in {"accountId", "account_id", "deviceId", "device_id", "principalId", "principal_id", "pairingGeneration", "pairing_generation"} for key in value):
            return True
        return any(_contains_identity_override(child) for child in value.values())
    if isinstance(value, list):
        return any(_contains_identity_override(child) for child in value)
    return False


class GatewayCore:
    def __init__(
        self, storage_root: str | Path | None = None, secret_store: Any = None,
        contract_root: str | Path | None = None, commit_hook: Any = None,
        attachment_policy: AttachmentPolicy | None = None,
        credential_verifier: Any = None,
        command_catalog: Any = None,
        event_sink: EventSink | None = None,
    ):
        self.storage_root = Path(storage_root or default_hermes_gateway_root()).resolve()
        self.secret_store = secret_store
        self.contract_root = Path(contract_root).resolve() if contract_root is not None else None
        self._contracts: ContractRegistry | None = None
        self._pending_negotiations: dict[str, dict[str, Any]] = {}
        self.commit_hook = commit_hook
        self.attachment_policy = attachment_policy or DEFAULT_ATTACHMENT_POLICY
        self.credential_verifier = credential_verifier
        self.command_catalog = tuple(command_catalog) if command_catalog is not None else DEFAULT_COMMAND_CATALOG
        self._event_sinks: list[EventSink] = [event_sink] if event_sink is not None else []

    @property
    def contracts(self) -> ContractRegistry:
        if self._contracts is None:
            self._contracts = ContractRegistry(self.contract_root)
        return self._contracts

    def register_event_sink(self, sink: EventSink) -> None:
        """Register one transport that wants committed events as they happen.

        Registration is separate from construction because the transport starts
        after the accounts exist: an account opened before the first stream is
        still served, because delivery reads this list at call time.
        """
        if sink not in self._event_sinks:
            self._event_sinks.append(sink)

    registerEventSink = register_event_sink

    def unregister_event_sink(self, sink: EventSink) -> None:
        self._event_sinks = [item for item in self._event_sinks if item is not sink]

    unregisterEventSink = unregister_event_sink

    def deliver_event(self, account_id: str, event: Mapping[str, Any]) -> None:
        """Hand one committed event to every registered transport.

        A transport that is gone or failing must not fail the write that produced
        the event: the event is durable either way and the phone's cursor recovers
        it on the next subscription.
        """
        for sink in list(self._event_sinks):
            try:
                sink(account_id, event)
            except Exception as exc:  # noqa: BLE001 - delivery is best effort
                logger.warning("[open_android] Event sink failed: %s", exc)

    deliverEvent = deliver_event

    def account_exists(self, account_id: str) -> bool:
        """Whether this account was registered on this host.

        Login must never be the act that creates an account: an unknown
        username is an authentication failure, not a signup.
        """
        try:
            paths = account_paths(self.storage_root, account_id)
        except (ValueError, TypeError):
            return False
        return paths.database.is_file()

    has_gateway_account = account_exists

    def command_catalog_response(self, language_code: str) -> dict[str, Any]:
        """The shared command catalog in one response.

        Each entry carries both spellings the two consumers read: the
        discovery fields (`invocation`, `title`, `acceptsArguments`) the phone
        renders, and the contract fields (`command`, `description`,
        `argumentHint`) the shared schema validates. Deriving one from the
        other here keeps a single source of truth.
        """
        catalog = tuple(self.command_catalog)
        fingerprint = hashlib.sha256(
            _json({"format": COMMAND_CATALOG_FORMAT, "commands": catalog}).encode("utf-8"),
        ).hexdigest()[:16]
        commands = []
        for entry in catalog:
            item = dict(entry)
            item.setdefault("command", item.get("invocation", ""))
            item.setdefault("argumentHint", item.get("title", "") if item.get("acceptsArguments") else "")
            commands.append(item)
        return {
            "format": COMMAND_CATALOG_FORMAT,
            "catalogVersion": f"cmdcat_{fingerprint}",
            "languageCode": language_code,
            "commands": commands,
        }

    @staticmethod
    def _coerce_aead(candidate: Any) -> tuple[str, Any] | None:
        provider = candidate
        reference: Any = None
        if isinstance(candidate, Mapping):
            provider = candidate.get("aead", candidate.get("cipher", candidate.get("provider")))
            reference = candidate.get("reference", candidate.get("keyReference"))
        if provider is None:
            return None
        if reference is None:
            reference = getattr(provider, "reference", getattr(provider, "key_reference", None))
        algorithm = getattr(provider, "algorithm", None)
        authenticated = getattr(provider, "authenticated", None)
        if (
            not isinstance(reference, str) or not reference
            or not isinstance(algorithm, str) or not algorithm
            or authenticated is not True
            or not callable(getattr(provider, "encrypt", None))
            or not callable(getattr(provider, "decrypt", None))
        ):
            return None
        return reference, provider

    def _resolve_key_binding(self, account_id: str) -> tuple[str, Any | None]:
        secret_store = self.secret_store
        if secret_store is None:
            return "", None
        name = f"open-android-intelligence-gateway/{account_id}"
        for method_name in ("get_or_create_aead", "get_aead", "get_or_create_secure_key"):
            method = getattr(secret_store, method_name, None)
            if callable(method):
                try:
                    binding = self._coerce_aead(method(name))
                except Exception:
                    return "", None
                return binding if binding is not None else ("", None)
        binding = self._coerce_aead(secret_store)
        if binding is not None:
            return binding
        for method_name in ("get_or_create", "get_or_create_secret", "reference_for"):
            method = getattr(secret_store, method_name, None)
            if not callable(method):
                continue
            try:
                candidate = method(name)
            except TypeError:
                try:
                    candidate = method(account_id)
                except Exception:
                    return "", None
            except Exception:
                return "", None
            binding = self._coerce_aead(candidate)
            return binding if binding is not None else ("", None)
        return "", None

    def _master_key_ref(self, paths: AccountPaths, account_id: str) -> str:
        del paths
        reference, _ = self._resolve_key_binding(account_id)
        return reference

    def open_gateway_account(self, account_id: str) -> GatewayAccount:
        # Resolve and validate the opaque account ID before constructing SQLite.
        paths = account_paths(self.storage_root, account_id)
        master_key_ref, aead = self._resolve_key_binding(account_id)
        store = AccountStore(
            paths, master_key_ref, self.commit_hook, aead,
            account_id=account_id, event_sink=self.deliver_event,
        )
        return GatewayAccount(
            account_id, paths, store, self.contracts, self.attachment_policy,
            self.credential_verifier,
        )

    def delete_gateway_account(self, account_id: str) -> bool:
        """Removes every artifact of one logical Gateway (contract section 13).

        The account directory *is* the logical Gateway: database, staged and
        confirmed attachment bytes, credentials and the account audit trail.
        Deletion is a resource-level transaction, so it never pretends to be
        per-attachment reducer events. The path comes from `account_paths`,
        the only place allowed to turn an opaque ID into a filesystem location.
        """
        paths = account_paths(self.storage_root, account_id)
        if not paths.root.is_dir():
            return False
        shutil.rmtree(paths.root)
        return True

    deleteGatewayAccount = delete_gateway_account

    def _negotiate(self, account: GatewayAccount, context: Mapping[str, Any], body: Any, now: datetime) -> Mapping[str, Any]:
        response = self._build_negotiation_response(body, account)
        if not self.contracts.validate("negotiate.response", response):
            raise GatewayError("INTERNAL_ERROR")
        account.store.database.execute(
            "INSERT OR REPLACE INTO negotiations(negotiation_id, response_json, created_at, expires_at) VALUES (?, ?, ?, ?)",
            (body["negotiationId"], _json(response), iso_millis(now), iso_millis(now + timedelta(hours=24))),
        )
        return response

    def _build_negotiation_response(self, body: Any, account: GatewayAccount | None = None) -> Mapping[str, Any]:
        if not isinstance(body, Mapping) or not self.contracts.validate("negotiate.request", body):
            raise GatewayError("SCHEMA_INVALID")
        if body["schemaHashes"]["core"] != self.contracts.core_schema_hash:
            raise GatewayError("PROTOCOL_INCOMPATIBLE")
        requested = body["features"]
        # Only what this Gateway implements is ever advertised: an
        # account-invitation or device-key flow this host cannot serve would be a
        # capability claim the phone would then rely on.
        supported_auth = {"password", "refresh"}
        auth = [item for item in requested["auth"] if item in supported_auth]
        # Only capabilities this adapter really serves. `agent-command-new-v1`
        # means the `/new` command entry exists here: it creates the new
        # conversation and answers with the authoritative id. Advertising it
        # without that entry would make the agreement a claim, not a fact.
        supported_conversation_ui = {"agent-command-catalog-v1", "agent-command-new-v1"}
        conversation_ui = [
            item for item in requested.get("conversationUi", [])
            if item in supported_conversation_ui
        ]
        required = {
            "messages": "chat-v1", "attachments": "staged-sha256-v1",
            "events": "sse-cursor-v1", "deviceRequests": "risk-queue-v1",
        }
        if any(required[key] not in requested[key] for key in required):
            raise GatewayError("PROTOCOL_INCOMPATIBLE")
        if account is None:
            deployment_id = "deploy_" + hashlib.sha256(str(self.storage_root).encode("utf-8")).hexdigest()[:16]
            tls_identity = "sha256:" + "0" * 64
        else:
            metadata = {
                row["key"]: row["value"] for row in account.store.database.execute(
                    "SELECT key, value FROM account_metadata WHERE key IN ('deployment_id', 'tls_spki_sha256')"
                ).fetchall()
            }
            deployment_id = metadata.get("deployment_id", "deploy_hermes")
            tls_identity = metadata.get("tls_spki_sha256", "sha256:" + "0" * 64)
        features: dict[str, Any] = {"auth": auth, **required}
        if conversation_ui:
            features["conversationUi"] = conversation_ui
        return {
            "protocol": {"major": 2, "minor": 0},
            "features": features,
            "limits": {
                "maxSingleAttachmentBytes": self.attachment_policy.max_single_attachment_bytes,
                "maxMessageAttachmentBytes": self.attachment_policy.max_message_attachment_bytes,
                "allowedMediaTypes": list(self.attachment_policy.allowed_media_types),
                "attachmentTtlSeconds": self.attachment_policy.attachment_ttl_seconds,
                "eventRetentionSeconds": 86_400, "maxClockSkewSeconds": 120,
            },
            "gatewayIdentity": {"deploymentId": deployment_id, "tlsSpkiSha256": tls_identity},
        }

    def _pre_auth_context(self, request: Any, body: Mapping[str, Any]) -> dict[str, str]:
        return {
            "requestId": str(_value(request, "requestId", "request_id", default=body.get("negotiationId", "open-android-intelligence-negotiate"))),
            "correlationId": str(_value(request, "correlationId", "correlation_id", default=body.get("negotiationId", "open-android-intelligence-negotiate"))),
        }

    def _handle_pre_auth(self, request: Any) -> GatewayResponse:
        body = _request_body(request)
        context = self._pre_auth_context(request, body if isinstance(body, Mapping) else {})
        try:
            response = self._build_negotiation_response(body)
            negotiation_id = str(body["negotiationId"])
            now = _request_now(request)
            input_hash = _hash_input("POST", "/open-android-intelligence/v2/negotiate", body)
            existing = self._pending_negotiations.get(negotiation_id)
            if existing is not None:
                if existing["inputHash"] != input_hash:
                    raise GatewayError("PROTOCOL_INCOMPATIBLE")
            else:
                self._pending_negotiations[negotiation_id] = {
                    "inputHash": input_hash,
                    "installationId": body["client"]["installationId"],
                    "response": dict(response),
                    "expiresAt": _now(now) + timedelta(minutes=5),
                    "accountId": None,
                }
            return _success(context, response)
        except GatewayError as exc:
            code = "SCHEMA_INVALID" if exc.code == "INVALID_STATE_TRANSITION" else exc.code
            return _failure(context, code, exc.details)

    def _handle_session_refresh(self, request: Any) -> GatewayResponse:
        """Rotates an access session from a refresh credential (pre-auth).

        The refresh credential is the only proof presented, so every field the
        schema makes mandatory is required here too: a request that names an
        account, device or installation that does not match the stored
        credential is rejected before anything is rotated.
        """
        body = _request_body(request)
        body_map = body if isinstance(body, Mapping) else {}
        context = self._pre_auth_context(request, body_map)
        now = _request_now(request)
        try:
            if not isinstance(body, Mapping) or not self.contracts.validate("session.refresh", body):
                raise GatewayError("SCHEMA_INVALID")
            account_id = str(body["accountId"])
            if not self.account_exists(account_id):
                raise GatewayError("AUTHENTICATION_FAILED")
            self.bind_negotiation(str(body["negotiationId"]), account_id, str(body["installationId"]), now)
            account = self.open_gateway_account(account_id)
            try:
                bundle = account.sessions.refresh(
                    refresh_credential=str(body["refreshCredential"]),
                    installation_id=str(body["installationId"]),
                    device_id=str(body["deviceId"]),
                    correlation_id=context["correlationId"],
                    now=now,
                )
            finally:
                account.close()
            response = dict(bundle)
            response["accountId"] = account_id
            return _success(context, response)
        except GatewayError as exc:
            code = "SCHEMA_INVALID" if exc.code == "INVALID_STATE_TRANSITION" else exc.code
            return _failure(context, code, exc.details)

    def _handle_session_logout(self, request: Any) -> GatewayResponse:
        """Ends the access session named by the request's own identity headers."""
        context = self._pre_auth_context(request, {})
        now = _request_now(request)
        try:
            headers = _request_headers(request)
            account_id = headers.get("x-open-android-intelligence-account")
            device_id = headers.get("x-open-android-intelligence-device")
            session_id = headers.get("x-open-android-intelligence-session")
            access_token = _bearer_token(headers)
            if not account_id or not device_id or not session_id or not access_token:
                raise GatewayError("AUTHENTICATION_REQUIRED")
            if not self.account_exists(account_id):
                raise GatewayError("AUTHENTICATION_FAILED")
            revoke_refresh = _query_flag(_value(request, "target"), "revokeRefresh")
            account = self.open_gateway_account(account_id)
            try:
                if not account.sessions.verify_access_token(access_token, session_id, device_id, now):
                    raise GatewayError("AUTHENTICATION_FAILED")
                account.sessions.revoke_session(session_id, context["correlationId"], now)
                if revoke_refresh:
                    account.sessions.revoke_refresh_credentials(device_id, context["correlationId"], now)
            finally:
                account.close()
            return _success(context, {"sessionId": session_id, "refreshRevoked": revoke_refresh})
        except GatewayError as exc:
            code = "SCHEMA_INVALID" if exc.code == "INVALID_STATE_TRANSITION" else exc.code
            return _failure(context, code, exc.details)

    def bind_negotiation(
        self, negotiation_id: str, account_id: str, installation_id: str,
        now: datetime | str | None = None,
    ) -> None:
        current = _now(now)
        pending = self._pending_negotiations.get(negotiation_id)
        if (
            pending is None
            or pending["expiresAt"] <= current
            or pending["installationId"] != installation_id
            or (pending["accountId"] is not None and pending["accountId"] != account_id)
        ):
            raise GatewayError("PROTOCOL_INCOMPATIBLE")
        account = self.open_gateway_account(account_id)
        try:
            with account.store.transaction():
                account.store.database.execute(
                    "INSERT OR REPLACE INTO negotiation_bindings(negotiation_id, account_id, installation_id, bound_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                    (negotiation_id, account_id, installation_id, iso_millis(current), iso_millis(pending["expiresAt"])),
                )
            pending["accountId"] = account_id
        finally:
            account.close()

    def _assert_negotiation_bound(self, account: GatewayAccount, context: Mapping[str, Any], now: datetime) -> None:
        negotiation_id = context.get("negotiationId")
        if negotiation_id is None:
            return
        installation_id = context.get("installationId")
        if installation_id is None:
            raise GatewayError("PROTOCOL_INCOMPATIBLE")
        row = account.store.database.execute(
            "SELECT account_id, installation_id, expires_at FROM negotiation_bindings WHERE negotiation_id = ?",
            (negotiation_id,),
        ).fetchone()
        if row is None or row["account_id"] != context["accountId"] or row["installation_id"] != installation_id or _now(row["expires_at"]) <= now:
            raise GatewayError("PROTOCOL_INCOMPATIBLE")

    def _run_idempotent(self, account: GatewayAccount, request: Any, context: Mapping[str, Any], work: Any, replay_check: Any = None) -> dict[str, Any]:
        method = _value(request, "method")
        if method == "GET":
            return work()
        request_id = context["requestId"]
        idempotency_key = _value(request, "idempotencyKey", "idempotency_key")
        if idempotency_key != request_id:
            return _failure(context, "IDEMPOTENCY_CONFLICT")
        now = _request_now(request)
        input_hash = _hash_input(method, _value(request, "target"), _request_body(request))
        with account.store.transaction(unknown_marker={
            "deviceId": context["deviceId"],
            "requestId": request_id,
            "inputHash": input_hash,
            "createdAt": iso_millis(now),
            "expiresAt": iso_millis(now + timedelta(days=30)),
        }):
            uncertain = account.store.database.execute(
                "SELECT 1 FROM uncertain_outcomes WHERE device_id = ? AND request_id = ?",
                (context["deviceId"], request_id),
            ).fetchone()
            if uncertain is not None:
                return _failure(context, "OUTCOME_UNKNOWN")
            existing = account.store.database.execute(
                "SELECT input_hash, outcome_json, expires_at FROM idempotency_ledger WHERE device_id = ? AND request_id = ?",
                (context["deviceId"], request_id),
            ).fetchone()
            if existing is not None:
                if existing["input_hash"] != input_hash:
                    return _failure(context, "IDEMPOTENCY_CONFLICT")
                if _now(existing["expires_at"]) <= now:
                    return _failure(context, "OUTCOME_UNKNOWN")
                if replay_check is not None:
                    replay_error = replay_check()
                    if replay_error is not None:
                        return _failure(context, replay_error)
                return GatewayResponse(account.store.open_json(
                    existing["outcome_json"],
                    f"idempotency:{context['deviceId']}:{request_id}",
                ))
            try:
                response = work()
            except GatewayError as exc:
                if exc.code not in _PERSISTABLE_ERRORS:
                    raise
                response = _failure(context, exc.code, exc.details)
            try:
                account.store.database.execute(
                    "INSERT INTO idempotency_ledger(device_id, request_id, input_hash, outcome_json, expires_at) VALUES (?, ?, ?, ?, ?)",
                    (
                        context["deviceId"], request_id, input_hash,
                        account.store.seal_json(response, f"idempotency:{context['deviceId']}:{request_id}"),
                        iso_millis(now + timedelta(days=30)),
                    ),
                )
            except sqlite3.Error as exc:
                raise TransactionOutcomeUnknown({"reason": "idempotency outcome persistence unknown"}) from exc
            return response

    def _handle_new_conversation(
        self,
        account: Any,
        source_conversation_id: str,
        client_message_id: str,
        correlation_id: str,
        device_id: str,
        request_id: str,
        now: datetime | str | None = None,
    ) -> dict[str, Any]:
        """
        The `/new` command entry (contract §7.1, ADR 0043).

        The phone never mints a conversation id of its own: it sends `/new`
        into the thread it is leaving, and this entry answers with the id the
        Agent host really created. Message acceptance, conversation creation,
        the event and the audit are one transaction, so an interruption cannot
        leave a conversation that exists without the event that names it, or
        an event pointing at a conversation nobody stored.

        The command itself stays in the source thread — the phone keeps showing
        it there — and the new thread starts empty.
        """
        current = _now(now)
        with account.store.transaction():
            accepted = account.conversations.accept_message(
                source_conversation_id, client_message_id, NEW_CONVERSATION_COMMAND,
                [], device_id, request_id, correlation_id, current,
            )
            created = account.conversations.create(
                # Derived from the id the phone chose for the command itself, so
                # the same request cannot end up with two new threads: a replay
                # of this request is answered from the idempotency ledger before
                # this code runs again.
                client_conversation_id=f"{client_message_id}-new",
                title=None,
                correlation_id=correlation_id,
                now=current,
            )
            account.events.append(
                "conversation.command.result",
                correlation_id,
                {
                    "command": "new",
                    "commandId": "new",
                    "outcome": "created-conversation",
                    "sourceConversationId": source_conversation_id,
                    "sourceMessageId": accepted["messageId"],
                    "conversationId": created["conversationId"],
                },
                current,
            )
            # The binding the command entry owns: this request asked for a new
            # thread and this thread is what it got. `agentSessionId` stays NULL
            # here — only the host runtime can name the session it created, and
            # it fills that column in through this same row rather than through an
            # id invented by the protocol layer. The row exists from this
            # transaction on, so the conversation can never be routed to a session
            # nobody recorded.
            account.agent_sessions.record(
                created["conversationId"],
                AgentSessionBindings.CREATED_VIA_NEW_COMMAND,
                request_id,
                current,
            )
            account.audit.append(
                "conversation.command.new",
                {"accountId": account.account_id, "deviceId": device_id},
                {
                    "command": "new",
                    "requestId": request_id,
                    "sourceConversationId": source_conversation_id,
                    "sourceMessageId": accepted["messageId"],
                    "conversationId": created["conversationId"],
                },
                correlation_id,
                current,
            )
        return {"message": accepted}

    def handle(self, request: VerifiedGatewayRequest) -> GatewayResponse:
        try:
            method = _value(request, "method")
            target = _value(request, "target")
            if _value(request, "context") is None:
                if method == "POST" and target == "/open-android-intelligence/v2/negotiate":
                    return self._handle_pre_auth(request)
                if method == "POST" and target == "/open-android-intelligence/v2/sessions/refresh":
                    return self._handle_session_refresh(request)
                if method == "DELETE" and isinstance(target, str) and (
                    target == "/open-android-intelligence/v2/sessions/current"
                    or target.startswith("/open-android-intelligence/v2/sessions/current?")
                ):
                    return self._handle_session_logout(request)
            if not isinstance(request, VerifiedGatewayRequest):
                raise GatewayError("AUTHENTICATION_REQUIRED")
            context = _request_context(request)
            account = self.open_gateway_account(context["accountId"])
            try:
                account.store.require_aead()
                self._assert_negotiation_bound(account, context, _request_now(request))
                body = _request_body(request)
                if _contains_identity_override(body):
                    return _failure(context, "IDENTITY_OVERRIDE_REJECTED")
                if method == "GET" and isinstance(target, str) and target.startswith("/open-android-intelligence/v2/events"):
                    query = urlsplit(target).query
                    cursor = None
                    for part in query.split("&") if query else []:
                        if part.startswith("cursor="):
                            cursor = part[7:]
                    last_event_id = _value(request, "lastEventId", "last_event_id")
                    if last_event_id is not None and last_event_id != cursor:
                        return _failure(context, "CURSOR_CONFLICT")
                    try:
                        return _success(context, {"events": account.events.read_after(cursor, _request_now(request))})
                    except GatewayError as exc:
                        if exc.code == "CURSOR_EXPIRED":
                            return _failure(context, "CURSOR_EXPIRED", {"recoverableResources": ["conversations", "attachments", "device-requests"]})
                        raise

                parsed_target = urlsplit(str(target))
                target_path = parsed_target.path
                query_params: dict[str, str] = {}
                if parsed_target.query:
                    for item in parsed_target.query.split("&"):
                        if "=" in item:
                            k, v = item.split("=", 1)
                            query_params[k] = v

                def work() -> dict[str, Any]:
                    if method == "GET" and target_path == "/open-android-intelligence/v2/commands":
                        language_code = query_params.get("languageCode") or "en"
                        return _success(context, self.command_catalog_response(language_code))
                    if method == "POST" and target_path == "/open-android-intelligence/v2/negotiate":
                        return _success(context, self._negotiate(account, context, body, _request_now(request)))
                    if method == "POST" and target_path == "/open-android-intelligence/v2/conversations":
                        body_map = body if isinstance(body, Mapping) else None
                        if body_map is None or not self.contracts.validate("conversation.create", body_map):
                            raise GatewayError("SCHEMA_INVALID")
                        return _success(context, {"conversation": account.conversations.create(
                            str(body_map["clientConversationId"]),
                            body_map.get("title"), context["correlationId"], _request_now(request),
                        )})
                    if method == "GET" and target_path == "/open-android-intelligence/v2/conversations":
                        return _success(context, {"conversations": account.conversations.list()})
                    message_match = re.fullmatch(r"/open-android-intelligence/v2/conversations/([^/]+)/messages", target_path)
                    if method == "GET" and message_match:
                        conv_id = message_match.group(1)
                        client_msg_id = query_params.get("clientMessageId")
                        cursor = query_params.get("cursor")
                        limit = int(query_params.get("limit", 50))
                        return _success(context, account.conversations.list_messages(
                            conv_id, client_message_id=client_msg_id, cursor=cursor, limit=limit,
                        ))
                    if method == "POST" and message_match:
                        body_map = body if isinstance(body, Mapping) else None
                        if body_map is None or not self.contracts.validate("message.create", body_map):
                            raise GatewayError("SCHEMA_INVALID")
                        attachments = body_map.get("attachments", [])
                        attachment_ids = [str(item["attachmentId"]) for item in attachments] if isinstance(attachments, list) else []
                        source_conversation_id = message_match.group(1)
                        client_message_id = str(body_map["clientMessageId"])
                        text_value = str(body_map["text"])
                        if text_value.strip() == NEW_CONVERSATION_COMMAND:
                            return _success(context, self._handle_new_conversation(
                                account,
                                source_conversation_id=source_conversation_id,
                                client_message_id=client_message_id,
                                correlation_id=context["correlationId"],
                                device_id=context["deviceId"],
                                request_id=context["requestId"],
                                now=_request_now(request),
                            ))
                        return _success(context, {"message": account.conversations.accept_message(
                            source_conversation_id, client_message_id, text_value,
                            attachment_ids, context["deviceId"], context["requestId"], context["correlationId"], _request_now(request),
                        )})
                    conversation_get = re.fullmatch(r"/open-android-intelligence/v2/conversations/([^/]+)", target_path)
                    if method == "GET" and conversation_get:
                        return _success(context, {"conversation": account.conversations.get(conversation_get.group(1))})
                    if method == "PATCH" and conversation_get:
                        body_map = None
                        if isinstance(body, (bytes, bytearray)):
                            try:
                                body_map = json.loads(body.decode("utf-8"))
                            except Exception:
                                pass
                        elif isinstance(body, str):
                            try:
                                body_map = json.loads(body)
                            except Exception:
                                pass
                        elif isinstance(body, Mapping):
                            body_map = body
                        if body_map is None:
                            raise GatewayError("SCHEMA_INVALID")
                        title = str(body_map.get("title", ""))
                        conv_id = conversation_get.group(1)
                        now = _request_now(request)
                        updated = account.conversations.update_title(
                            conv_id, title, context["correlationId"], now,
                        )
                        account.events.append(
                            "conversation.title.updated",
                            context["correlationId"],
                            {"conversationId": conv_id, "title": title, "newTitle": title},
                            now,
                        )
                        return _success(context, {"conversation": updated})
                    if method == "POST" and target_path == "/open-android-intelligence/v2/attachments":
                        body_map = body if isinstance(body, Mapping) else None
                        if body_map is None or not self.contracts.validate("attachment.create", body_map):
                            raise GatewayError("SCHEMA_INVALID")
                        return _success(context, {"attachment": account.attachments.create(
                            clientAttachmentId=str(body_map["clientAttachmentId"]),
                            filename=str(body_map["filename"]), mediaType=str(body_map["mediaType"]),
                            sizeBytes=int(body_map["sizeBytes"]), sha256=str(body_map["sha256"]),
                            correlationId=context["correlationId"], now=_request_now(request),
                        )})
                    attachment_content = re.fullmatch(r"/open-android-intelligence/v2/attachments/([^/]+)/content", target_path)
                    if method == "PUT" and attachment_content:
                        if not isinstance(body, (bytes, bytearray, memoryview)):
                            raise GatewayError("SCHEMA_INVALID")
                        return _success(context, {"attachment": account.attachments.upload_content(
                            attachment_content.group(1), bytes(body), _request_now(request),
                        )})
                    attachment_commit = re.fullmatch(r"/open-android-intelligence/v2/attachments/([^/]+)/commit", target_path)
                    if method == "POST" and attachment_commit:
                        return _success(context, {"attachment": account.attachments.commit(
                            attachment_commit.group(1), _request_now(request),
                        )})
                    attachment_get = re.fullmatch(r"/open-android-intelligence/v2/attachments/([^/]+)", target_path)
                    if method == "GET" and attachment_get:
                        return _success(context, {"attachment": account.attachments.get(
                            attachment_get.group(1), _request_now(request),
                        )})
                    claim_match = re.fullmatch(r"/open-android-intelligence/v2/device-requests/([^/]+)/claim", target_path)
                    if method == "POST" and claim_match:
                        return _success(context, {"receipt": account.device_requests.claim(
                            request_id=claim_match.group(1), device_id=context["deviceId"],
                            pairing_generation=int(context["pairingGeneration"]), grant_revision=int(context["grantRevision"]),
                            correlation_id=context["correlationId"], now=_request_now(request),
                        )})
                    result_match = re.fullmatch(r"/open-android-intelligence/v2/device-requests/([^/]+)/result", target_path)
                    if method == "POST" and result_match:
                        body_map = body if isinstance(body, Mapping) else None
                        if body_map is None or int(body_map.get("grantRevision", -1)) != int(context["grantRevision"]):
                            raise GatewayError("GRANT_STALE")
                        result_value = body_map.get("result")
                        if not isinstance(result_value, Mapping) or result_value.get("outcome") not in {"succeeded", "failed", "denied", "cancelled", "outcome_unknown"}:
                            raise GatewayError("SCHEMA_INVALID")
                        return _success(context, {"deviceRequest": account.device_requests.submit_result(
                            request_id=result_match.group(1), device_id=context["deviceId"],
                            pairing_generation=int(context["pairingGeneration"]), grant_revision=int(context["grantRevision"]),
                            claim_id=str(body_map.get("claimId")), result=result_value,
                            correlation_id=context["correlationId"], now=_request_now(request),
                        )})
                    device_get = re.fullmatch(r"/open-android-intelligence/v2/device-requests/([^/]+)", target_path)
                    if method == "GET" and device_get:
                        return _success(context, {"deviceRequest": account.device_requests.get(device_get.group(1))})
                    return _failure(context, "SCHEMA_INVALID")

                replay_check = None
                claim_match = re.fullmatch(r"/open-android-intelligence/v2/device-requests/([^/]+)/claim", target_path)
                if method == "POST" and claim_match:
                    replay_check = lambda: account.device_requests.validate_claim_replay(
                        claim_match.group(1), context["deviceId"], int(context["pairingGeneration"]),
                        int(context["grantRevision"]), _request_now(request),
                    )
                result_match = re.fullmatch(r"/open-android-intelligence/v2/device-requests/([^/]+)/result", target_path)
                if method == "POST" and result_match and isinstance(body, Mapping):
                    replay_check = lambda: account.device_requests.validate_result_replay(
                        result_match.group(1), context["deviceId"], int(context["pairingGeneration"]),
                        int(context["grantRevision"]), str(body.get("claimId")), _request_now(request),
                    )
                return self._run_idempotent(account, request, context, work, replay_check)
            finally:
                account.close()
        except GatewayError as exc:
            try:
                context = _request_context(request)
            except GatewayError:
                context = {"requestId": "open-android-intelligence-route", "correlationId": "open-android-intelligence-route"}
            code = "SCHEMA_INVALID" if exc.code == "INVALID_STATE_TRANSITION" else exc.code
            return _failure(context, code, exc.details)
        except Exception:
            try:
                context = _request_context(request)
            except GatewayError:
                context = {"requestId": "open-android-intelligence-route", "correlationId": "open-android-intelligence-route"}
            return _failure(context, "INTERNAL_ERROR")

    def run_shared_vectors(self, contract_root: str | Path | None = None) -> list[dict[str, Any]]:
        registry = ContractRegistry(contract_root or self.contract_root)
        vector_files = SHARED_VECTOR_FILE_NAMES
        results: list[dict[str, Any]] = []
        for file_name in vector_files:
            document = json.loads((registry.root / "vectors" / file_name).read_text(encoding="utf-8"))
            for case in document["cases"]:
                operation = case["operation"]
                input_value = case["input"]
                try:
                    if operation == "request.target":
                        actual_value = {"canonicalTarget": canonicalize_target(input_value["target"])}
                    elif operation == "request.signature":
                        actual_value = {"preimageHex": request_signature_preimage(input_value).hex()}
                    elif operation == "schema.validate":
                        if not registry.validate(input_value["schemaName"], input_value["value"]):
                            raise GatewayError("SCHEMA_INVALID")
                        actual_value = {"valid": True}
                    elif operation == "schema.validate_dispatched":
                        if not registry.validate_dispatched(input_value["fixtureBindingSetId"], input_value["dispatch"], input_value["value"]):
                            raise GatewayError("SCHEMA_INVALID")
                        actual_value = {"valid": True}
                    elif operation == "attachment.transition":
                        actual_value = {"nextState": next_attachment_state(input_value["current"], input_value["event"])}
                    elif operation == "device.transition":
                        actual_value = {"nextState": next_device_request_state(input_value["current"], input_value["event"])}
                    elif operation == "device.maximum_queue_seconds":
                        actual_value = {"seconds": maximum_device_request_queue_seconds(input_value["risk"])}
                    else:
                        raise GatewayError("SCHEMA_INVALID")
                    actual = {
                        "vectorId": case["id"], "operation": operation,
                        "outcome": "value", "value": actual_value,
                    }
                except GatewayError as exc:
                    actual = {
                        "vectorId": case["id"], "operation": operation,
                        "outcome": "error", "code": exc.code,
                    }
                expected = case["expected"]
                expected_projection = ({"outcome": "value", "value": expected["value"]}
                                       if expected["outcome"] == "value"
                                       else {"outcome": "error", "code": expected["code"]})
                actual_projection = {key: actual[key] for key in ("outcome", "value", "code") if key in actual}
                result = {
                    "vectorId": case["id"],
                    "operation": operation,
                    "implementation": "hermes-python",
                    "status": "pass" if actual_projection == expected_projection else "fail",
                    "resultHash": "sha256:" + hashlib.sha256(_jcs(actual).encode("utf-8")).hexdigest(),
                }
                results.append(result)
        return results

    openGatewayAccount = open_gateway_account


def create_gateway_core(storage_root: str | Path | None = None, **options: Any) -> GatewayCore:
    return GatewayCore(storage_root=storage_root, **options)


def open_gateway_account(account_id: str, storage_root: str | Path | None = None) -> GatewayAccount:
    return create_gateway_core(storage_root).open_gateway_account(account_id)


createGatewayCore = create_gateway_core
