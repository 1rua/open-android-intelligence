"""Authenticated HTTP client shared by the command line and MCP surfaces.

This module never schedules a device operation. Submission, cancellation,
expiry and result acknowledgement all go through the running Core.
"""

from __future__ import annotations

import ipaddress
import json
import os
from pathlib import Path
import stat
import tempfile
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import uuid


API_PREFIX = "/open-android-intelligence/core/v1"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024


class CoreClientError(RuntimeError):
    def __init__(self, code: str, status: int | None = None):
        super().__init__(code)
        self.code = code
        self.status = status


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


def save_client_config(path: Path, value: dict[str, Any]) -> None:
    """Atomically write credentials without a group/world-readable interval."""
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".client-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def load_client_config(path: Path) -> dict[str, Any]:
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        with os.fdopen(descriptor, "r", encoding="utf-8") as stream:
            info = os.fstat(stream.fileno())
            if (not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077
                    or info.st_uid != os.getuid()):
                raise CoreClientError("CLIENT_CONFIG_PERMISSIONS_INVALID")
            value = json.load(stream)
    except FileNotFoundError as error:
        raise CoreClientError("LOGIN_REQUIRED") from error
    except (OSError, ValueError) as error:
        raise CoreClientError("CLIENT_CONFIG_INVALID") from error
    if (not isinstance(value, dict) or not isinstance(value.get("url"), str)
            or not isinstance(value.get("token"), str) or not value["token"]):
        raise CoreClientError("CLIENT_CONFIG_INVALID")
    return value


class CoreClient:
    def __init__(self, url: str, token: str | None = None, *,
                 allow_http: bool = False, timeout: float = 30):
        self.url = validate_url(url, allow_http)
        self.token = token
        self.timeout = timeout
        self._opener = build_opener(_NoRedirect())

    @classmethod
    def from_config(cls, path: Path) -> CoreClient:
        value = load_client_config(path)
        return cls(value["url"], value["token"], allow_http=value.get("allowHttp") is True)

    def _request(self, method: str, path: str, body: dict[str, Any] | None = None,
                 *, idempotency_key: str | None = None) -> dict[str, Any]:
        headers = {"Accept": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
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
            try:
                result = json.loads(payload)
                failure = result.get("error", {})
                code = failure.get("code") if isinstance(failure, dict) else None
                code = code or result.get("code") or result.get("errorCode")
            except (ValueError, AttributeError):
                code = None
            raise CoreClientError(str(code or "CORE_HTTP_ERROR"), error.code) from error
        except (URLError, TimeoutError, OSError) as error:
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
            raise CoreClientError(str(error.get("code", "CORE_ERROR")) if isinstance(error, dict) else "CORE_ERROR")
        return result.get("data", result)

    def login(self, username: str, password: str, device_id: str) -> dict[str, Any]:
        return self._request("POST", "/tokens", {
            "username": username, "password": password, "deviceId": device_id,
        })

    def devices(self) -> dict[str, Any]:
        return self._request("GET", "/devices")

    def logout(self) -> dict[str, Any]:
        return self._request("DELETE", "/tokens/current")

    def capabilities(self) -> dict[str, Any]:
        return self._request("GET", "/capabilities")

    def invoke(self, capability_id: str, capability_version: str,
               parameters: dict[str, Any], idempotency_key: str | None = None) -> dict[str, Any]:
        if not isinstance(parameters, dict):
            raise CoreClientError("PARAMETERS_INVALID")
        return self._request("POST", "/device-requests", {
            "capabilityId": capability_id, "capabilityVersion": capability_version,
            "parameters": parameters,
        }, idempotency_key=idempotency_key or str(uuid.uuid4()))

    @staticmethod
    def _request_path(request_id: str) -> str:
        if not isinstance(request_id, str) or not request_id:
            raise CoreClientError("REQUEST_ID_REQUIRED")
        return "/device-requests/" + quote(request_id, safe="")

    def status(self, request_id: str) -> dict[str, Any]:
        return self._request("GET", self._request_path(request_id))

    def cancel(self, request_id: str) -> dict[str, Any]:
        return self._request("POST", self._request_path(request_id) + "/cancel", {})

    def acknowledge(self, request_id: str) -> dict[str, Any]:
        return self._request("POST", self._request_path(request_id) + "/ack", {})
