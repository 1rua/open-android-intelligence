"""Stable, sanitized errors for Agent bridge handshakes."""
from pathlib import Path
from types import SimpleNamespace

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.agent_registration import AgentHandshakeError, AgentRegistration


TEST_TOKEN = "synthetic-agent-token-that-is-long-enough-0123456789"
SERVER_DETAILS = "synthetic private server response"


def _registration() -> AgentRegistration:
    identity = SimpleNamespace(database_path=Path("unused.sqlite"), require_management=lambda *_: None)
    return AgentRegistration("core-test", identity, lambda _: True, lambda _: True)


def _manifest(url: str) -> dict[str, str]:
    return {
        "id": "legacy-dsh",
        "type": "dsh",
        "instanceId": "legacy-dsh-instance",
        "sessionScope": "deployment",
        "url": url.rstrip("/"),
        "tokenEnv": "OAI_TEST_AGENT_TOKEN",
    }


def _assert_sanitized(error: AgentHandshakeError, code: str, url: str) -> None:
    assert isinstance(error, ValueError)
    assert error.code == code
    assert str(error) == error.message
    assert TEST_TOKEN not in str(error)
    assert url not in str(error)
    assert SERVER_DETAILS not in str(error)


@pytest.mark.asyncio
async def test_status_success_does_not_replace_missing_handshake_and_error_is_sanitized(monkeypatch):
    monkeypatch.setenv("OAI_TEST_AGENT_TOKEN", TEST_TOKEN)

    async def status(_request):
        return web.json_response({"online": True})

    async def handshake(request):
        assert request.headers.get("Authorization") == f"Bearer {TEST_TOKEN}"
        return web.Response(status=404, text=f"{SERVER_DETAILS}; {TEST_TOKEN}; {request.url}")

    app = web.Application()
    app.router.add_get("/oai/session-adapter/v1/status", status)
    app.router.add_post("/oai/session-adapter/v1/handshake", handshake)

    async with TestClient(TestServer(app)) as client:
        status_response = await client.get(
            "/oai/session-adapter/v1/status",
            headers={"Authorization": f"Bearer {TEST_TOKEN}"},
        )
        assert status_response.status == 200
        url = str(client.make_url("/")).rstrip("/")

        with pytest.raises(AgentHandshakeError) as raised:
            await _registration()._handshake(_manifest(url), Path("."))

    error = raised.value
    _assert_sanitized(error, "AGENT_BRIDGE_UPGRADE_REQUIRED", url)
    assert "升级" in str(error)


@pytest.mark.parametrize(
    ("status", "code"),
    [
        (401, "AGENT_BRIDGE_AUTHENTICATION_FAILED"),
        (403, "AGENT_BRIDGE_AUTHENTICATION_FAILED"),
        (500, "AGENT_BRIDGE_UNAVAILABLE"),
        (503, "AGENT_BRIDGE_UNAVAILABLE"),
        (400, "AGENT_HANDSHAKE_FAILED"),
    ],
)
@pytest.mark.asyncio
async def test_http_failures_have_stable_codes_and_sanitized_messages(monkeypatch, status, code):
    monkeypatch.setenv("OAI_TEST_AGENT_TOKEN", TEST_TOKEN)

    async def handshake(request):
        return web.Response(status=status, text=f"{SERVER_DETAILS}; {TEST_TOKEN}; {request.url}")

    app = web.Application()
    app.router.add_post("/oai/session-adapter/v1/handshake", handshake)

    async with TestClient(TestServer(app)) as client:
        url = str(client.make_url("/")).rstrip("/")
        with pytest.raises(AgentHandshakeError) as raised:
            await _registration()._handshake(_manifest(url), Path("."))

    _assert_sanitized(raised.value, code, url)


@pytest.mark.parametrize("response_kind", ["bad-json", "deep-json", "non-object", "oversized"])
@pytest.mark.asyncio
async def test_invalid_handshake_responses_fail_without_exposing_response_data(monkeypatch, response_kind):
    monkeypatch.setenv("OAI_TEST_AGENT_TOKEN", TEST_TOKEN)

    async def handshake(request):
        if response_kind == "bad-json":
            return web.Response(text=f"{SERVER_DETAILS}; {TEST_TOKEN}; {request.url}")
        if response_kind == "deep-json":
            return web.Response(text="[" * 1100 + "]" * 1100)
        if response_kind == "non-object":
            return web.json_response([TEST_TOKEN, SERVER_DETAILS, str(request.url)])
        return web.Response(body=b"x" * 65537)

    app = web.Application()
    app.router.add_post("/oai/session-adapter/v1/handshake", handshake)

    async with TestClient(TestServer(app)) as client:
        url = str(client.make_url("/")).rstrip("/")
        with pytest.raises(AgentHandshakeError) as raised:
            await _registration()._handshake(_manifest(url), Path("."))

    _assert_sanitized(raised.value, "AGENT_HANDSHAKE_FAILED", url)


@pytest.mark.asyncio
async def test_network_failure_is_reported_as_bridge_unavailable(monkeypatch):
    monkeypatch.setenv("OAI_TEST_AGENT_TOKEN", TEST_TOKEN)
    server = TestServer(web.Application())
    await server.start_server()
    url = str(server.make_url("/")).rstrip("/")
    await server.close()

    with pytest.raises(AgentHandshakeError) as raised:
        await _registration()._handshake(_manifest(url), Path("."))

    _assert_sanitized(raised.value, "AGENT_BRIDGE_UNAVAILABLE", url)


@pytest.mark.asyncio
async def test_handshake_timeout_is_reported_as_bridge_unavailable(monkeypatch):
    import asyncio

    monkeypatch.setenv("OAI_TEST_AGENT_TOKEN", TEST_TOKEN)
    request_received = asyncio.Event()

    async def handshake(_request):
        request_received.set()
        await asyncio.sleep(1)
        return web.json_response({})

    app = web.Application()
    app.router.add_post("/oai/session-adapter/v1/handshake", handshake)

    async with TestClient(TestServer(app)) as client:
        url = str(client.make_url("/")).rstrip("/")
        with pytest.raises(AgentHandshakeError) as raised:
            await _registration()._handshake(_manifest(url), Path("."), timeout_seconds=0.05)
        assert request_received.is_set()

    _assert_sanitized(raised.value, "AGENT_BRIDGE_UNAVAILABLE", url)
