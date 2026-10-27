"""Native conversation interactions cross the public Core routes intact."""
import json
import uuid

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.auth import Principal
from oai_core.sessions import AdapterError, AgentRegistry, create_agent_routes
from oai_core.server import REGISTRY_KEY
from test_core_transport import CONTRACT, PREFIX, gateway
from test_sessions import NativeHost


COMMAND = {"id": "plugin-task", "invocation": "/plugin-task", "title": "Plugin task",
           "description": "Run a registered host command", "acceptsArguments": True,
           "argumentHint": "<task>", "availability": "available"}
APPROVAL = {"approvalId": "apr_native", "conversationId": "chat", "command": "echo test",
            "reason": "Host confirmation", "severity": "elevated",
            "options": [{"choice": "once"}, {"choice": "deny"}],
            "timeoutSeconds": 30, "requestedAt": 1}


class InteractiveHost(NativeHost):
    def __init__(self):
        super().__init__("interactive", "account")

    def descriptor(self):
        result = super().descriptor()
        result["capabilities"].update(commands=True, approvals=True, steer=True)
        return result

    async def commands(self, language_code="en", *, principal=None):
        assert principal is not None, "commands must preserve authenticated account scope"
        self.calls.append(("commands", principal.account_id, language_code))
        return [COMMAND]

    async def send(self, principal, session_id, text):
        yield {"type": "generation", "generationId": "gen_native"}
        yield {"type": "approval_requested", "approval": APPROVAL}
        yield {"type": "approval_resolved", "approval": {
            "approvalId": "apr_native", "conversationId": "chat", "decision": "once", "decidedAt": 2}}
        yield {"type": "delta", "text": "accepted"}
        yield {"type": "done", "text": "accepted", "outcome": "completed", "generationId": "gen_native"}

    async def resolve_approval(self, principal, session_id, approval_id, decision):
        self.calls.append(("approval", principal.account_id, session_id, approval_id, decision))
        return {"approval": {"approvalId": approval_id, "conversationId": session_id,
                             "decision": decision, "decidedAt": 2}}

    async def steer(self, principal, session_id, text, generation_id, client_message_id=None):
        self.calls.append(("steer", principal.account_id, session_id, generation_id, text, client_message_id))
        return {"generationId": generation_id, "outcome": "accepted"}

    async def current_generation(self, principal, session_id):
        self.calls.append(("generation", principal.account_id, session_id))
        return {"generationId": "gen_native", "conversationId": session_id, "state": "running"}


@pytest.fixture
async def native_api():
    host = InteractiveHost()
    registry = AgentRegistry([host])

    async def authenticate(request):
        return Principal(request.headers.get("X-Test-User", "alice"))

    app = web.Application()
    app.add_routes(create_agent_routes(registry, authenticate, lambda _: True))
    async with TestClient(TestServer(app)) as client:
        yield client, host
    await registry.close()


def write_headers(key=None):
    return {"Idempotency-Key": key or str(uuid.uuid4())}


@pytest.mark.asyncio
async def test_native_negotiation_advertises_only_available_requested_interactions(gateway):
    client, core, app = gateway
    host = InteractiveHost()
    app[REGISTRY_KEY].register(host)
    negotiation = json.loads((CONTRACT / "vectors/protocol-negotiation.json").read_text())["cases"][0]["input"]["value"]
    negotiation["schemaHashes"]["core"] = core.contracts.core_schema_hash
    requested = ["agent-command-catalog-v1", "agent-approval-cards-v1", "generation-cancel-v1",
                 "message-batches-v1", "agent-command-new-v1"]
    negotiation["features"]["conversationUi"] = requested
    response = await client.post(PREFIX + "/negotiate", json=negotiation)
    assert response.status == 200, await response.text()
    assert (await response.json())["data"]["features"]["conversationUi"] == requested[:3]
    negotiation["features"]["conversationUi"] = ["agent-command-catalog-v1"]
    negotiation["negotiationId"] = "neg_subset"
    response = await client.post(PREFIX + "/negotiate", json=negotiation)
    assert (await response.json())["data"]["features"]["conversationUi"] == ["agent-command-catalog-v1"]
    host.online = False
    negotiation["negotiationId"] = "neg_offline"
    response = await client.post(PREFIX + "/negotiate", json=negotiation)
    assert (await response.json())["data"]["features"].get("conversationUi", []) == []


@pytest.mark.asyncio
async def test_selected_agent_catalog_preserves_principal_language_and_stable_version(native_api):
    client, host = native_api
    path = PREFIX + "/agents/interactive/commands?languageCode=zh-CN"
    first = await client.get(path)
    assert first.status == 200, await first.text()
    catalog = await first.json()
    assert catalog["commands"] == [COMMAND]
    assert catalog["format"] == "agent-command-catalog-1.0"
    assert catalog["languageCode"] == "zh-CN"
    second = await (await client.get(path)).json()
    assert second["catalogVersion"] == catalog["catalogVersion"]
    assert host.calls == [("commands", "alice", "zh-CN")] * 2


@pytest.mark.asyncio
async def test_public_native_stream_preserves_interaction_events(native_api):
    client, _ = native_api
    path = PREFIX + "/agents/interactive/sessions/chat/messages"
    response = await client.post(path, json={"text": "confirm"}, headers=write_headers())
    events = [json.loads(line[6:]) for line in (await response.text()).splitlines() if line.startswith("data: ")]
    assert [event["type"] for event in events] == ["generation", "approval_requested", "approval_resolved", "delta", "done"]
    assert events[1]["approval"] == APPROVAL
    assert events[-1]["generationId"] == "gen_native"


@pytest.mark.asyncio
async def test_approval_decision_is_validated_and_replayable_without_releasing_host_twice(native_api):
    client, host = native_api
    path = PREFIX + "/agents/interactive/sessions/chat/approvals/apr_native/decisions"
    headers = write_headers("one-decision")
    response = await client.post(path, json={"decision": "once"}, headers=headers)
    assert response.status == 200, await response.text()
    body = await response.json()
    again = await client.post(path, json={"decision": "once"}, headers=headers)
    assert again.status == 200, await again.text()
    assert await again.json() == body
    assert host.calls == [("approval", "alice", "chat", "apr_native", "once")]
    for invalid in ({"decision": "allow"}, {"decision": "once", "accountId": "bob"}, {"decision": "once", "sessionKey": "other"}):
        response = await client.post(path, json=invalid, headers=write_headers())
        assert response.status == 400
    assert len(host.calls) == 1


@pytest.mark.asyncio
async def test_cancel_forwards_exact_generation_and_rejects_identity_override(native_api):
    client, host = native_api
    path = PREFIX + "/agents/interactive/sessions/chat/cancel"
    response = await client.post(path, json={"generationId": "gen_native"}, headers=write_headers())
    assert response.status == 200, await response.text()
    assert host.calls == [("cancel", "alice", "chat", {"generationId": "gen_native"})]
    for invalid in ({"generationId": ""}, {"generationId": 7}, {"generationId": "gen", "accountId": "bob"}):
        assert (await client.post(path, json=invalid, headers=write_headers())).status == 400
    assert len(host.calls) == 1


@pytest.mark.asyncio
async def test_steering_targets_the_exact_generation_without_creating_another_turn(native_api):
    client, host = native_api
    path = PREFIX + "/agents/interactive/sessions/chat/steer"
    headers = write_headers("steer-once")
    response = await client.post(
        path,
        json={"text": "Focus on the parser tests", "generationId": "gen_native", "clientMessageId": "cm_steer"},
        headers=headers,
    )
    assert response.status == 200, await response.text()
    body = await response.json()
    assert body == {"steering": {"generationId": "gen_native", "outcome": "accepted"}}
    replay = await client.post(
        path,
        json={"text": "Focus on the parser tests", "generationId": "gen_native", "clientMessageId": "cm_steer"},
        headers=headers,
    )
    assert replay.status == 200, await replay.text()
    assert await replay.json() == body
    assert host.calls == [
        ("steer", "alice", "chat", "gen_native", "Focus on the parser tests", "cm_steer"),
    ]
    for invalid in (
        {"text": "", "generationId": "gen_native"},
        {"text": "x", "generationId": ""},
        {"text": "x", "generationId": "gen_native", "accountId": "bob"},
    ):
        rejected = await client.post(path, json=invalid, headers=write_headers())
        assert rejected.status == 400

    host.descriptor = lambda: {
        **InteractiveHost().descriptor(),
        "capabilities": {
            **InteractiveHost().descriptor()["capabilities"],
            "steer": False,
        },
    }
    unsupported = await client.post(
        PREFIX + "/agents/interactive/sessions/chat/steer",
        json={"text": "x", "generationId": "gen_native"},
        headers=write_headers(),
    )
    assert unsupported.status == 405


@pytest.mark.asyncio
async def test_current_generation_is_exposed_for_safe_queue_reconnect(native_api):
    client, host = native_api
    response = await client.get(PREFIX + "/agents/interactive/sessions/chat/generation")
    assert response.status == 200, await response.text()
    assert await response.json() == {
        "generation": {
            "generationId": "gen_native",
            "conversationId": "chat",
            "state": "running",
        },
    }
    assert host.calls == [("generation", "alice", "chat")]


@pytest.mark.asyncio
@pytest.mark.parametrize("override", [
    {"approvalId": "unrelated-approval"}, {"conversationId": "unrelated-chat"},
    {"decision": {"sessionKey": "synthetic-internal-secret"}},
])
async def test_malformed_approval_error_is_rejected_before_public_receipt(native_api, override):
    client, host = native_api
    async def malformed(*_):
        details = {"approvalId": "apr_native", "conversationId": "chat", "decision": "once", "decidedAt": 2}
        details.update(override)
        raise AdapterError("APPROVAL_ALREADY_RESOLVED", 409, details=details)
    host.resolve_approval = malformed
    path = PREFIX + "/agents/interactive/sessions/chat/approvals/apr_native/decisions"
    response = await client.post(path, json={"decision": "deny"}, headers=write_headers())
    assert response.status == 502, await response.text()
    assert "synthetic-internal-secret" not in await response.text()
