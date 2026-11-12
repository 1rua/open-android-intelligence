"""原生观察必须保留到期、撤销和认证存储故障的真实分类。"""
from __future__ import annotations

import asyncio
from contextlib import contextmanager
import json
import sqlite3

import pytest

from oai_core.gateway.core import GatewayError
from oai_core.server import REGISTRY_KEY
from test_core_transport import PREFIX, gateway, phone_login, signed
from test_native_transport_lifecycle import Host


CASES = (
    ("expired", "SESSION_EXPIRED", 401),
    ("revoked", "SESSION_REVOKED", 401),
    ("storage-busy", "AUTHENTICATION_STORAGE_UNAVAILABLE", 503),
    ("storage-error", "AUTHENTICATION_STORAGE_FAILED", 500),
)


@contextmanager
def authentication_failure(core, bundle, mode, monkeypatch):
    if mode in {"expired", "revoked"}:
        account = core.open_gateway_account(bundle["accountId"])
        try:
            if mode == "expired":
                account.store.database.execute(
                    "UPDATE access_sessions SET expires_at='2000-01-01T00:00:00.000Z' WHERE session_id=?",
                    (bundle["sessionId"],),
                )
            else:
                account.sessions.revoke_session(bundle["sessionId"], "synthetic-revocation")
        finally:
            account.close()
        yield
        return

    connect = sqlite3.connect
    failure = sqlite3.OperationalError("private-authentication-storage-message")
    failure.sqlite_errorcode = sqlite3.SQLITE_BUSY if mode == "storage-busy" else sqlite3.SQLITE_ERROR

    def unavailable_readonly_database(database, *args, **kwargs):
        if "?mode=ro" in str(database):
            raise failure
        return connect(database, *args, **kwargs)

    with monkeypatch.context() as patch:
        patch.setattr(sqlite3, "connect", unavailable_readonly_database)
        yield


class CapturingHost(Host):
    async def send(self, principal, session_id, text, *, client_message_id=None):
        self.principal = principal
        async for event in super().send(principal, session_id, text, client_message_id=client_message_id):
            yield event


@pytest.mark.asyncio
@pytest.mark.parametrize("mode,expected,status", CASES)
async def test_prepared_native_stream_keeps_its_authentication_failure_code(
        gateway, monkeypatch, caplog, mode, expected, status):
    client, core, app = gateway
    bundle, key = await phone_login(client, core)
    host = CapturingHost()
    app[REGISTRY_KEY].register(host)
    path = PREFIX + "/agents/probe/sessions/chat/messages"
    body = {"text": "合成输入", "clientMessageId": "synthetic-first"}
    raw, headers = signed(bundle, key, "POST", path, body)
    response = await client.post(path, data=raw, headers=headers)
    assert response.status == 200
    first = await asyncio.wait_for(response.content.readuntil(b"\n\n"), 2)
    assert b'"type": "generation"' in first

    with authentication_failure(core, bundle, mode, monkeypatch):
        if mode in {"expired", "revoked"}:
            assert core.event_session_failure(host.principal.context) == expected
        else:
            with pytest.raises(GatewayError, match=expected):
                core.event_session_failure(host.principal.context)
        host.release.set()
        remainder = await asyncio.wait_for(response.text(), 2)

    events = [json.loads(line[6:]) for line in remainder.splitlines() if line.startswith("data: ")]
    assert events == [{"type": "error", "code": expected}]
    assert host.calls == 1
    assert host.completed.is_set(), "观察认证失效不能伪造宿主停止"
    assert "private-authentication-storage-message" not in remainder
    assert "private-authentication-storage-message" not in caplog.text
    for header in ("Authorization", "X-Open-Android-Intelligence-Nonce", "X-Open-Android-Intelligence-Signature"):
        assert headers[header] not in caplog.text
    if mode.startswith("storage-"):
        _, restored_headers = signed(bundle, key, "GET", PREFIX + "/agents")
        restored = await client.get(PREFIX + "/agents", headers=restored_headers)
        assert restored.status == 200, await restored.text()
        assert host.calls == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("mode,expected,status", CASES)
async def test_authentication_failure_during_admission_records_accurate_refusal_without_host_call(
        gateway, monkeypatch, mode, expected, status):
    client, core, app = gateway
    host = CapturingHost()
    registry = app[REGISTRY_KEY]
    registry.register(host)
    bundle, key = await phone_login(client, core)
    entered, release = asyncio.Event(), asyncio.Event()
    begin = registry.operations.begin

    async def blocked_begin(*args, **kwargs):
        entered.set()
        await release.wait()
        return await begin(*args, **kwargs)

    monkeypatch.setattr(registry.operations, "begin", blocked_begin)
    path = PREFIX + "/agents/probe/sessions/chat/messages"
    raw, headers = signed(bundle, key, "POST", path,
        {"text": "合成输入", "clientMessageId": "synthetic-refused"})
    pending = asyncio.create_task(client.post(path, data=raw, headers=headers))
    try:
        await asyncio.wait_for(entered.wait(), 2)
        with authentication_failure(core, bundle, mode, monkeypatch):
            release.set()
            response = await asyncio.wait_for(pending, 2)
            assert response.status == status, await response.text()
            assert (await response.json())["error"]["code"] == expected
        await asyncio.gather(*tuple(registry._operation_tasks))
        receipt = await registry.operations.lookup(bundle["accountId"],
            headers["X-Open-Android-Intelligence-Request-Id"], "probe")
        assert receipt == {"state": "resolved", "receipt": {"error": {"code": expected}}}
        assert host.calls == 0
    finally:
        release.set()
        await asyncio.gather(pending, return_exceptions=True)
