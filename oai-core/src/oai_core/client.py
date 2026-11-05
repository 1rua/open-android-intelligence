"""Authenticated HTTP client shared by the command line and MCP surfaces.

Device operations are scheduled, cancelled and acknowledged by Core.  A
client configuration is also the cross-process journal for rotating sessions
and for safely retrying an invocation whose result was not received.
"""

from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path
import stat
import tempfile
from typing import Any, Iterator
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import uuid


API_PREFIX = "/open-android-intelligence/core/v1"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
_SECRET_FIELDS = {"password", "otp", "recoverycode", "recoverycodes", "refreshcredential", "accesstoken",
                  "clientproof", "token", "secret", "authorization", "provisioninguri"}


def _safe_details(value: Any) -> Any:
    """Do not relay credential fields from an untrusted error response."""
    if isinstance(value, dict):
        return {k: _safe_details(v) for k, v in value.items()
                if str(k).replace("_", "").replace("-", "").lower() not in _SECRET_FIELDS}
    if isinstance(value, list):
        return [_safe_details(item) for item in value]
    return value


class CoreClientError(RuntimeError):
    def __init__(self, code: str, status: int | None = None, *,
                 details: dict[str, Any] | None = None, retryable: bool = False):
        self.code = code
        self.status = status
        self.details = _safe_details(details or {})
        self.retryable = retryable
        # MCP retains this structured diagnostic in its tool error text.
        super().__init__(json.dumps(self.as_error(), ensure_ascii=False) if self.details else code)

    def as_error(self) -> dict[str, Any]:
        return {"code": self.code, **({"details": self.details} if self.details else {}),
                **({"retryable": True} if self.retryable else {})}


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # A redirect must never forward the deployment's scoped credential.
        return None


def validate_url(value: str, allow_http: bool = False) -> str:
    parsed = urlsplit(value)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.path not in {"", "/"} or parsed.query or parsed.fragment):
        raise CoreClientError("CORE_URL_INVALID")
    try:
        parsed.port
    except ValueError as error:
        raise CoreClientError("CORE_URL_INVALID") from error
    try:
        loopback = ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError:
        loopback = parsed.hostname.lower() == "localhost"
    if parsed.scheme == "http" and not (loopback or allow_http):
        raise CoreClientError("CORE_TLS_REQUIRED")
    return value.rstrip("/")


def default_client_config() -> Path:
    return Path.home() / ".config" / "oai" / "client.json"


def _check_private_file(info: os.stat_result) -> None:
    if (not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077
            or info.st_uid != os.getuid()):
        raise CoreClientError("CLIENT_CONFIG_PERMISSIONS_INVALID")


@contextmanager
def client_config_lock(path: Path) -> Iterator[None]:
    """Serialize readers that may refresh, invoke, log in or log out."""
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    lock_path = path.with_name(path.name + ".lock")
    try:
        descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
        try:
            _check_private_file(os.fstat(descriptor))
            fcntl.flock(descriptor, fcntl.LOCK_EX)
            yield
        finally:
            os.close(descriptor)
    except OSError as error:
        raise CoreClientError("CLIENT_CONFIG_INVALID") from error


def save_client_config(path: Path, value: dict[str, Any]) -> None:
    """Atomically write credentials without a group/world-readable interval.

    On a failed write the private temporary file is retained for recovery rather
    than deleting it. Callers that modify shared state must hold the lock.
    """
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        try:
            existing = os.lstat(path)
        except FileNotFoundError:
            pass
        else:
            _check_private_file(existing)
        descriptor, temporary = tempfile.mkstemp(prefix=".client-", dir=path.parent)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    except OSError as error:
        raise CoreClientError("CLIENT_CONFIG_INVALID") from error


def load_client_config(path: Path) -> dict[str, Any]:
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(descriptor, "r", encoding="utf-8") as stream:
            _check_private_file(os.fstat(stream.fileno()))
            value = json.load(stream)
    except FileNotFoundError as error:
        raise CoreClientError("LOGIN_REQUIRED") from error
    except (OSError, ValueError) as error:
        raise CoreClientError("CLIENT_CONFIG_INVALID") from error
    if not isinstance(value, dict) or not isinstance(value.get("url"), str):
        raise CoreClientError("CLIENT_CONFIG_INVALID")
    logged_out = value.get("loggedOut") is True
    if (not logged_out or value.get("remoteRevocationPending") is True) and (
            not isinstance(value.get("token"), str) or not value["token"]):
        raise CoreClientError("CLIENT_CONFIG_INVALID")
    return value


def _expired(value: Any) -> bool:
    if value is None:
        return False  # Legacy configurations may not record an expiry.
    try:
        expires = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if expires.tzinfo is None:
            raise ValueError("missing timezone")
        return datetime.now(timezone.utc) >= expires
    except (AttributeError, TypeError, ValueError) as error:
        raise CoreClientError("CLIENT_CONFIG_INVALID") from error


def _credential_bundle(result: dict[str, Any], previous: dict[str, Any] | None = None) -> dict[str, Any]:
    token = result.get("accessToken") or result.get("token")
    if (not isinstance(token, str) or not token
            or result.get("token") is not None and result["token"] != token):
        raise CoreClientError("CORE_RESPONSE_INVALID")
    bundle: dict[str, Any] = {"token": token, "expiresAt": result.get("expiresAt")}
    refresh = result.get("refreshCredential")
    if refresh is not None:
        if (not isinstance(refresh, str) or not refresh
                or not all(isinstance(result.get(k), str) and result[k]
                           for k in ("clientId", "loginSessionId"))
                or not isinstance(result.get("expiresAt"), str)):
            raise CoreClientError("CORE_RESPONSE_INVALID")
        # Parse the time before committing the new rotating credential.
        _expired(result["expiresAt"])
        if previous and any(previous.get(k) and previous[k] != result[k]
                            for k in ("clientId", "loginSessionId")):
            raise CoreClientError("CORE_RESPONSE_INVALID")
        bundle.update({k: result[k] for k in ("refreshCredential", "clientId", "loginSessionId")})
        proof = result.get("clientProof")
        if proof is not None:
            if not isinstance(proof, str) or not proof:
                raise CoreClientError("CORE_RESPONSE_INVALID")
            if previous and previous.get("clientProof") and previous["clientProof"] != proof:
                raise CoreClientError("CORE_RESPONSE_INVALID")
            bundle["clientProof"] = proof
    elif previous and previous.get("refreshCredential"):
        raise CoreClientError("CORE_RESPONSE_INVALID")
    if isinstance(result.get("accountId"), str) and result["accountId"]:
        if previous and previous.get("accountId") and previous["accountId"] != result["accountId"]:
            raise CoreClientError("CORE_RESPONSE_INVALID")
        bundle["accountId"] = result["accountId"]
    return bundle


class CoreClient:
    def __init__(self, url: str, token: str | None = None, *,
                 allow_http: bool = False, timeout: float = 30):
        self.url = validate_url(url, allow_http)
        self.token = token
        self.timeout = timeout
        self._opener = build_opener(_NoRedirect())
        self._config_path: Path | None = None
        self._session_id: str | None = None
        self._account_id: str | None = None
        self._legacy_token: str | None = None

    @classmethod
    def from_config(cls, path: Path, *, allow_logged_out: bool = False) -> CoreClient:
        with client_config_lock(path):
            value = load_client_config(path)
        if value.get("loggedOut") is True and not allow_logged_out:
            raise CoreClientError("LOCAL_LOGGED_OUT", details={
                "remoteRevocationPending": value.get("remoteRevocationPending") is True,
                "action": "Run oai logout to retry pending revocation or oai login after it completes.",
            })
        client = cls(value["url"], value.get("token"), allow_http=value.get("allowHttp") is True)
        client._config_path = path
        client._session_id = value.get("loginSessionId")
        client._account_id = value.get("accountId")
        client._legacy_token = None if client._session_id else value.get("token")
        return client

    def _send(self, method: str, path: str, body: dict[str, Any] | None = None,
              *, idempotency_key: str | None = None, token: str | None = None) -> dict[str, Any]:
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        raw = None
        if body is not None:
            raw = json.dumps(body, ensure_ascii=False, allow_nan=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = Request(self.url + API_PREFIX + path, data=raw, headers=headers, method=method)
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                payload = response.read(MAX_RESPONSE_BYTES + 1)
        except HTTPError as error:
            payload = error.read(MAX_RESPONSE_BYTES + 1)
            details, retryable = {}, False
            try:
                result = json.loads(payload)
                failure = result.get("error", {})
                code = failure.get("code") if isinstance(failure, dict) else None
                code = code or result.get("code") or result.get("errorCode")
                if isinstance(failure, dict):
                    details = failure.get("details") if isinstance(failure.get("details"), dict) else {}
                    retryable = failure.get("retryable") is True
            except (ValueError, AttributeError):
                code = None
            raise CoreClientError(str(code or "CORE_HTTP_ERROR"), error.code, details=details, retryable=retryable) from error
        except (URLError, TimeoutError, OSError, http.client.HTTPException) as error:
            # A server can commit an operation and then close before replying.
            # Keep its journal so a later call reuses the same operation key.
            raise CoreClientError("CORE_UNREACHABLE") from error
        if len(payload) > MAX_RESPONSE_BYTES:
            raise CoreClientError("CORE_RESPONSE_TOO_LARGE")
        try:
            result = json.loads(payload)
        except ValueError as error:
            raise CoreClientError("CORE_RESPONSE_INVALID") from error
        if not isinstance(result, dict):
            raise CoreClientError("CORE_RESPONSE_INVALID")
        if "error" in result:
            error = result["error"]
            raise CoreClientError(str(error.get("code", "CORE_ERROR")) if isinstance(error, dict) else "CORE_ERROR",
                details=error.get("details") if isinstance(error, dict) and isinstance(error.get("details"), dict) else None,
                retryable=isinstance(error, dict) and error.get("retryable") is True)
        data = result.get("data", result)
        if not isinstance(data, dict):
            raise CoreClientError("CORE_RESPONSE_INVALID")
        return data

    def _current(self) -> dict[str, Any]:
        assert self._config_path is not None
        value = load_client_config(self._config_path)
        if value.get("loggedOut") is True:
            raise CoreClientError("LOCAL_LOGGED_OUT", details={
                "remoteRevocationPending": value.get("remoteRevocationPending") is True,
                "action": "Run oai logout to retry pending revocation or oai login after it completes.",
            })
        if (validate_url(value["url"], value.get("allowHttp") is True) != self.url
                or self._session_id and value.get("loginSessionId") != self._session_id
                or self._account_id and value.get("accountId") != self._account_id
                or self._legacy_token and value.get("token") != self._legacy_token):
            raise CoreClientError("SESSION_CHANGED", details={"action": "Restart the client and authenticate again."})
        return value

    def _refresh(self, value: dict[str, Any]) -> None:
        assert self._config_path is not None
        refresh, client_id = value.get("refreshCredential"), value.get("clientId")
        if not isinstance(refresh, str) or not refresh or not isinstance(client_id, str) or not client_id:
            raise CoreClientError("REAUTHENTICATION_REQUIRED", details={"action": "Run oai login in a terminal."})
        operation_id = value.get("refreshOperationId")
        if operation_id is None:
            operation_id = str(uuid.uuid4())
            value["refreshOperationId"] = operation_id
            save_client_config(self._config_path, value)
        if not isinstance(operation_id, str) or not operation_id:
            raise CoreClientError("CLIENT_CONFIG_INVALID")
        result = self._send("POST", "/tokens/refresh", {
            "refreshCredential": refresh, "clientId": client_id, "operationId": operation_id,
        })
        bundle = _credential_bundle(result, value)
        value.update(bundle)
        value.pop("refreshOperationId", None)
        save_client_config(self._config_path, value)
        self.token = value["token"]

    def _request(self, method: str, path: str, body: dict[str, Any] | None = None,
                 *, idempotency_key: str | None = None, auto_idempotency: bool = False) -> dict[str, Any]:
        if self._config_path is None:
            key = idempotency_key or (str(uuid.uuid4()) if auto_idempotency else None)
            try:
                return self._send(method, path, body, idempotency_key=key, token=self.token)
            except CoreClientError as error:
                if key and auto_idempotency:
                    raise CoreClientError(error.code, error.status, details={**error.details, "idempotencyKey": key},
                                          retryable=error.retryable) from error
                raise
        with client_config_lock(self._config_path):
            value = self._current()
            journal_key = None
            if auto_idempotency:
                canonical = json.dumps({"path": path, "body": body}, sort_keys=True, ensure_ascii=False,
                                       separators=(",", ":"), allow_nan=False).encode("utf-8")
                journal_key = hashlib.sha256(canonical).hexdigest()
                pending = value.setdefault("pendingInvocations", {})
                if not isinstance(pending, dict) or any(not isinstance(k, str) or not isinstance(v, str) or not v
                                                        for k, v in pending.items()):
                    raise CoreClientError("CLIENT_CONFIG_INVALID")
                idempotency_key = pending.get(journal_key)
                if idempotency_key is None:
                    idempotency_key = str(uuid.uuid4())
                    pending[journal_key] = idempotency_key
                    save_client_config(self._config_path, value)
            try:
                if value.get("refreshOperationId") or value.get("refreshCredential") and _expired(value.get("expiresAt")):
                    self._refresh(value)
                try:
                    result = self._send(method, path, body, idempotency_key=idempotency_key, token=value["token"])
                except CoreClientError as error:
                    # AUTHENTICATION_REQUIRED/403/revocation can mean security policy,
                    # not expiry. Only an explicit expiry is safe to refresh and retry.
                    if error.code != "SESSION_EXPIRED" or not value.get("refreshCredential"):
                        raise
                    self._refresh(value)
                    result = self._send(method, path, body, idempotency_key=idempotency_key, token=value["token"])
            except CoreClientError as error:
                if journal_key and idempotency_key:
                    raise CoreClientError(error.code, error.status,
                                          details={**error.details, "idempotencyKey": idempotency_key},
                                          retryable=error.retryable) from error
                raise
            if journal_key:
                value["pendingInvocations"].pop(journal_key, None)
                save_client_config(self._config_path, value)
            self.token = value["token"]
            return result

    def login(self, username: str, password: str, device_id: str | None = None, *,
              client_id: str | None = None, client_proof: str | None = None,
              challenge_id: str | None = None, otp: str | None = None,
              recovery_code: str | None = None) -> dict[str, Any]:
        body = {"username": username, "password": password}
        for key, value in (("deviceId", device_id), ("clientId", client_id), ("clientProof", client_proof),
                           ("challengeId", challenge_id), ("otp", otp), ("recoveryCode", recovery_code)):
            if value is not None:
                body[key] = value
        return self._send("POST", "/tokens", body)

    def begin_mfa_enrollment(self, challenge_id: str) -> dict[str, Any]:
        return self._send("POST", "/mfa/enroll", {"challengeId": challenge_id})

    def confirm_mfa_enrollment(self, challenge_id: str, enrollment_id: str, otp: str) -> dict[str, Any]:
        return self._send("POST", "/mfa/confirm", {
            "challengeId": challenge_id, "enrollmentId": enrollment_id, "otp": otp,
        })

    def complete_login(self, challenge_id: str, client_id: str, *, client_proof: str | None = None,
                       otp: str | None = None, recovery_code: str | None = None) -> dict[str, Any]:
        if otp is not None and recovery_code is not None:
            raise CoreClientError("MFA_METHOD_INVALID")
        body = {"challengeId": challenge_id, "clientId": client_id}
        if client_proof is not None:
            body["clientProof"] = client_proof
        if otp is not None:
            body["otp"] = otp
        if recovery_code is not None:
            body["recoveryCode"] = recovery_code
        return self._send("POST", "/tokens/complete", body)

    def devices(self) -> dict[str, Any]:
        return self._request("GET", "/devices")

    def set_default_device(self, device_id: str | None) -> dict[str, Any]:
        return self._request("PUT", "/devices/default", {"deviceId": device_id})

    def logout(self) -> dict[str, Any]:
        if self._config_path is None:
            return self._send("DELETE", "/tokens/current", token=self.token)
        with client_config_lock(self._config_path):
            value = load_client_config(self._config_path)
            if value.get("loggedOut") is True and value.get("remoteRevocationPending") is not True:
                return {"authenticated": False, "remoteRevocationPending": False}
            if (validate_url(value["url"], value.get("allowHttp") is True) != self.url
                    or self._session_id and value.get("loginSessionId") != self._session_id
                    or self._account_id and value.get("accountId") != self._account_id
                    or self._legacy_token and value.get("token") != self._legacy_token):
                raise CoreClientError("SESSION_CHANGED", details={"action": "Restart the client and authenticate again."})
            # The barrier is durable before any attempt to contact Core.
            value["loggedOut"] = True
            value["remoteRevocationPending"] = True
            save_client_config(self._config_path, value)
            self.token = None
            try:
                # The revoke endpoint accepts a consumed refresh credential from
                # this family, so logout must not issue a new access token even
                # if a prior refresh response was lost.
                refresh = value.get("refreshCredential")
                client_id = value.get("clientId")
                if refresh:
                    if not isinstance(client_id, str) or not client_id:
                        raise CoreClientError("CLIENT_CONFIG_INVALID")
                    self._send("POST", "/tokens/revoke", {
                        "refreshCredential": refresh, "clientId": client_id,
                    })
                else:
                    self._send("DELETE", "/tokens/current", token=value["token"])
            except CoreClientError as error:
                return {"authenticated": False, "remoteRevocationPending": True, "reason": error.code}
            value["remoteRevocationPending"] = False
            for key in ("token", "refreshCredential", "refreshOperationId", "expiresAt", "loginSessionId"):
                value.pop(key, None)
            save_client_config(self._config_path, value)
            self.token = None
            return {"authenticated": False, "remoteRevocationPending": False}

    def capabilities(self, device_id: str | None = None) -> dict[str, Any]:
        path = "/capabilities"
        if device_id is not None:
            path += "?deviceId=" + quote(device_id, safe="")
        return self._request("GET", path)

    def invoke(self, capability_id: str, capability_version: str,
               parameters: dict[str, Any], idempotency_key: str | None = None, *,
               agent_name: str | None = None, purpose: str | None = None,
               device_id: str | None = None, origin_context: dict[str, Any] | None = None) -> dict[str, Any]:
        if not isinstance(parameters, dict):
            raise CoreClientError("PARAMETERS_INVALID")
        if not isinstance(agent_name, str) or not agent_name.strip() or not isinstance(purpose, str) or not purpose.strip():
            raise CoreClientError("CALLER_DECLARATION_REQUIRED")
        body: dict[str, Any] = {
            "capabilityId": capability_id, "capabilityVersion": capability_version,
            "parameters": parameters, "caller": {"agentName": agent_name, "purpose": purpose},
        }
        if device_id is not None:
            body["deviceId"] = device_id
        if origin_context is not None:
            body["originContext"] = origin_context
        return self._request("POST", "/device-requests", body, idempotency_key=idempotency_key,
                             auto_idempotency=not bool(idempotency_key))

    @staticmethod
    def _request_path(request_id: str) -> str:
        if not isinstance(request_id, str) or not request_id:
            raise CoreClientError("REQUEST_ID_REQUIRED")
        return "/device-requests/" + quote(request_id, safe="")

    def status(self, request_id: str) -> dict[str, Any]:
        return self._request("GET", self._request_path(request_id))

    def cancel(self, request_id: str) -> dict[str, Any]:
        return self._request("POST", self._request_path(request_id) + "/cancel", {})

    def acknowledge(self, request_id: str, result_digest: str | None = None) -> dict[str, Any]:
        return self._request("POST", self._request_path(request_id) + "/ack",
                             {"resultDigest": result_digest} if result_digest is not None else {})

    def register_endpoint(self, registration_key: str, label: str, ttl_seconds: int = 3600) -> dict[str, Any]:
        return self._request("POST", "/endpoints", {"registrationKey": registration_key, "label": label, "ttlSeconds": ttl_seconds})

    def endpoints(self) -> dict[str, Any]:
        return self._request("GET", "/endpoints")

    @staticmethod
    def _endpoint_path(endpoint_id: str) -> str:
        if not isinstance(endpoint_id, str) or not endpoint_id:
            raise CoreClientError("ENDPOINT_ID_REQUIRED")
        return "/endpoints/" + quote(endpoint_id, safe="")

    def unregister_endpoint(self, endpoint_id: str) -> dict[str, Any]:
        return self._request("DELETE", self._endpoint_path(endpoint_id))

    def receive_deliveries(self, endpoint_id: str) -> dict[str, Any]:
        return self._request("GET", self._endpoint_path(endpoint_id) + "/deliveries")

    def acknowledge_delivery(self, endpoint_id: str, delivery_id: str, event_id: str) -> dict[str, Any]:
        return self._request("POST", self._endpoint_path(endpoint_id) + "/deliveries/" + quote(delivery_id, safe="") + "/ack", {"eventId": event_id})
