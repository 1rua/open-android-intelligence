"""HTTP regressions for attachment upload failure source classification."""
from __future__ import annotations

import hashlib
from contextlib import nullcontext

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.adapters.remote import RemoteSessionAdapter
from oai_core.auth import Principal
from oai_core.sessions import AdapterError, NativeAttachment


PREFIX = "/oai/session-adapter/v1"
TOKEN = "synthetic-attachment-token-12345678901234567890"


class _Database:
    def __init__(self):
        self.values: dict[str, object] = {}

    def execute(self, _sql, parameters):
        self.values[parameters[0]] = parameters[1]


class _Store:
    def __init__(self):
        self.database = _Database()

    def transaction(self):
        return nullcontext()

    @staticmethod
    def seal_json(value, _key):
        return value


class _Account:
    def __init__(self, store):
        self.store = store

    def close(self):
        pass


class _Core:
    def __init__(self):
        self.store = _Store()
        self.accounts: list[_Account] = []

    def open_gateway_account(self, _account_id):
        account = _Account(self.store)
        self.accounts.append(account)
        return account


class _Attachment:
    def __init__(self, content: bytes = b"good"):
        self.core = _Core()
        self.principal = Principal("alice")
        self.metadata = {
            "attachmentId": "core-attachment",
            "filename": "image.png",
            "mediaType": "image/png",
            "sizeBytes": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
        }
        self._content = content

    def chunks(self):
        yield self._content


def _adapter(client: TestClient) -> RemoteSessionAdapter:
    return RemoteSessionAdapter(
        str(client.make_url("/")).rstrip("/"),
        TOKEN,
        agent_id="probe",
        name="probe",
        kind="remote",
        capabilities={"attachments": True},
    )


async def _create_attachment(_request):
    return web.json_response({"attachmentId": "remote-attachment", "state": "pending"})


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [401, 403])
@pytest.mark.parametrize("body_kind", ["empty", "html"])
async def test_attachment_bridge_auth_failure_is_classified_before_body_parsing(status, body_kind):
    calls = []

    async def create(_request):
        calls.append(("POST", b""))
        return web.json_response({"attachmentId": "remote-attachment", "state": "pending"})

    async def upload(request):
        calls.append(("PUT", await request.read()))
        if body_kind == "empty":
            return web.Response(status=status)
        return web.Response(text="<html>bridge rejected credentials</html>",
                            content_type="text/html", status=status)

    app = web.Application()
    app.router.add_post(PREFIX + "/attachments", create)
    app.router.add_put(PREFIX + "/attachments/{attachment_id}/content", upload)
    async with TestClient(TestServer(app)) as client:
        with pytest.raises(AdapterError) as failure:
            await _adapter(client)._upload_attachment(_Attachment(), {}, "client-message")

    assert failure.value.code == "AGENT_UNAVAILABLE"
    assert failure.value.status == 502
    assert failure.value.details == {
        "source": "agent_bridge",
        "reason": "authentication_failed" if status == 401 else "permission_denied",
        "upstreamStatus": status,
    }
    assert calls == [("POST", b""), ("PUT", b"good")]


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [401, 403])
async def test_attachment_bridge_json_auth_code_is_not_a_native_authorization_error(status):
    async def upload(_request):
        return web.json_response({"code": "AUTHENTICATION_REQUIRED"}, status=status)

    app = web.Application()
    app.router.add_post(PREFIX + "/attachments", _create_attachment)
    app.router.add_put(PREFIX + "/attachments/{attachment_id}/content", upload)
    async with TestClient(TestServer(app)) as client:
        with pytest.raises(AdapterError) as failure:
            await _adapter(client)._upload_attachment(_Attachment(), {}, "client-message")

    assert failure.value.code == "AGENT_UNAVAILABLE"
    assert failure.value.status == 502
    assert failure.value.details["source"] == "agent_bridge"
    assert failure.value.details["upstreamStatus"] == status


@pytest.mark.asyncio
async def test_remote_attachment_authorization_failure_is_bridge_unavailable():
    async def upload(_request):
        return web.json_response({"code": "ATTACHMENT_ACCESS_DENIED"}, status=403)

    app = web.Application()
    app.router.add_post(PREFIX + "/attachments", _create_attachment)
    app.router.add_put(PREFIX + "/attachments/{attachment_id}/content", upload)
    async with TestClient(TestServer(app)) as client:
        with pytest.raises(AdapterError) as failure:
            await _adapter(client)._upload_attachment(_Attachment(), {}, "client-message")

    assert failure.value.code == "AGENT_UNAVAILABLE"
    assert failure.value.status == 502
    assert failure.value.details == {
        "source": "agent_bridge",
        "reason": "permission_denied",
        "upstreamStatus": 403,
    }


@pytest.mark.asyncio
@pytest.mark.parametrize("mode,code,status", [
    ("inactive", "SESSION_REVOKED", 401),
    ("denied", "ATTACHMENT_ACCESS_DENIED", 403),
])
async def test_local_attachment_active_check_preserves_error_and_skips_put(mode, code, status):
    put_calls = []

    async def is_active():
        if mode == "inactive":
            return False
        raise AdapterError("ATTACHMENT_ACCESS_DENIED", 403)

    async def upload(request):
        put_calls.append(request.method)
        return web.json_response({"status": "uploaded"})

    app = web.Application()
    app.router.add_post(PREFIX + "/attachments", _create_attachment)
    app.router.add_put(PREFIX + "/attachments/{attachment_id}/content", upload)
    async with TestClient(TestServer(app)) as client:
        source = _Attachment()
        attachment = NativeAttachment(
            source.core, source.principal, source.metadata, is_active)
        with pytest.raises(AdapterError) as failure:
            _ = [event async for event in _adapter(client).send(
                source.principal, "session", "", attachments=[attachment],
                client_message_id="client-message")]

    assert failure.value.code == code
    assert failure.value.status == status
    assert put_calls == []


@pytest.mark.asyncio
async def test_valid_attachment_upload_preserves_digest_identity_and_single_put():
    attachment = _Attachment()
    calls = []
    expected_idempotency_key = hashlib.sha256(
        b"core-attachment:client-message"
    ).hexdigest()

    async def create(request):
        value = await request.json()
        calls.append(("POST", value, request.headers.get("X-OAI-Content-SHA256")))
        return web.json_response({"attachmentId": "remote-attachment", "state": "pending"})

    async def upload(request):
        calls.append((
            "PUT",
            await request.read(),
            request.headers.get("X-OAI-Content-SHA256"),
        ))
        return web.json_response({"status": "uploaded"})

    app = web.Application()
    app.router.add_post(PREFIX + "/attachments", create)
    app.router.add_put(PREFIX + "/attachments/{attachment_id}/content", upload)
    async with TestClient(TestServer(app)) as client:
        result = await _adapter(client)._upload_attachment(
            attachment, {}, "client-message")

    assert result == "remote-attachment"
    assert [call[0] for call in calls] == ["POST", "PUT"]
    assert calls[0][1]["clientAttachmentId"] == expected_idempotency_key
    assert calls[1][1] == b"good"
    assert calls[1][2] == attachment.metadata["sha256"]
    assert len(attachment.core.store.database.values) == 1
