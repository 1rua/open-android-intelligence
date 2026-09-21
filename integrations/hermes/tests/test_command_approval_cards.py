"""Command-execution approval is a structured card, not an `/approve` text.

Contract §7.2: the Gateway publishes one approval as an event, the phone answers
it through its own endpoint, and the decision is what releases the Agent thread
blocked on the command. Everything below drives the real seams — the shared
dispatched fixture registry, the idempotency ledger and the event stream — so a
host that paints a card it cannot settle, or settles one nobody answered, fails
here instead of disagreeing with the phone in production.
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from open_android_intelligence_gateway.adapter import OpenAndroidPlatformAdapter  # noqa: E402
from open_android_intelligence_gateway.admin import (  # noqa: E402
    HostApiCompatibility,
    create_admin_service,
)
from open_android_intelligence_gateway.core import create_gateway_core  # noqa: E402
from open_android_intelligence_gateway.http import create_gateway_exposure  # noqa: E402
from open_android_intelligence_gateway.plugin import GatewayServices  # noqa: E402
from test_support import make_secret_store, trust_core  # noqa: E402

TEST_HOST_API = HostApiCompatibility("1.0.0", "1.0.0", "0123456789abcdef0123456789abcdef01234567")
ACCOUNT_ID = "acct_approval"
APPROVALS = "/open-android-intelligence/v2/approvals"
BINDING_SET_ID = "gateway-core-fixtures-v1"

_real_create_gateway_core = create_gateway_core


def create_gateway_core(storage_root=None, **options):
    options.setdefault("secret_store", make_secret_store())
    return trust_core(_real_create_gateway_core(storage_root=storage_root, **options))


class _Config:
    def __init__(self, account_id: str = ACCOUNT_ID):
        self.extra = {"host": "127.0.0.1", "port": 0, "account_id": account_id}


class _Prompt:
    """The host's exec-approval prompt, reduced to what this surface reads."""

    def __init__(
        self, chat_id: str, command: str, description: str,
        actions, session_key: str = "agent:main:open_android:dm:conv_1",
        metadata=None, smart_denied: bool = False,
    ):
        self.chat_id = chat_id
        self.session_key = session_key
        self.text = command
        self.actions = actions
        self.command = command
        self.description = description
        self.smart_denied = smart_denied
        self.metadata = metadata or {}


def _adapter(core):
    exposure = create_gateway_exposure(
        "host-route", core=core, host_version="1.0.0", host_api=TEST_HOST_API,
    )
    admin = create_admin_service(core=core, host_version="1.0.0", host_api=TEST_HOST_API)
    return OpenAndroidPlatformAdapter(_Config(), GatewayServices(core, admin, exposure))


def _seed_conversation(core, client_id: str = "cconv_approval") -> str:
    account = core.open_gateway_account(ACCOUNT_ID)
    try:
        return account.conversations.create(client_id, "审批会话", f"cor_{client_id}")["conversationId"]
    finally:
        account.close()


def _events(core, event_type: str) -> list[dict]:
    account = core.open_gateway_account(ACCOUNT_ID)
    try:
        events = account.events.read_after(None)
    finally:
        account.close()
    return [event for event in events if event["eventType"] == event_type]


def _decide(core, approval_id: str, decision: str, request_id: str = "req_decision") -> dict:
    return core.handle({
        "method": "POST",
        "target": f"{APPROVALS}/{approval_id}/decisions",
        "context": {
            "accountId": ACCOUNT_ID, "deviceId": "dev_approval", "sessionId": "sess_approval",
            "requestId": request_id, "correlationId": f"cor_{request_id}",
            "pairingGeneration": 1, "grantRevision": 1,
        },
        "body": {"decision": decision},
        "idempotencyKey": request_id,
    })


def _publish_card(core, conversation_id: str, actions=None, metadata=None, timeout: int = 300) -> str:
    adapter = _adapter(core)
    prompt = _Prompt(
        chat_id=conversation_id,
        command="python3 -c \"print(1)\"",
        description="内联解释器执行",
        actions=actions if actions is not None else [
            ("允许一次", "once", "primary"),
            ("本次会话允许", "session", ""),
            ("始终允许", "always", ""),
            ("拒绝", "deny", "danger"),
        ],
        metadata=metadata or {"request_id": "host_req_1"},
    )
    result = asyncio.run(adapter._send_exec_approval_prompt(prompt))
    assert result.success, result.error
    return str(result.message_id)


def test_a_command_approval_is_published_as_a_card_not_as_a_message(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    conversation_id = _seed_conversation(core)

    approval_id = _publish_card(core, conversation_id)

    assert approval_id.startswith("apr_")
    events = _events(core, "conversation.approval.requested")
    assert len(events) == 1
    payload = events[0]["payload"]
    assert payload["approvalId"] == approval_id
    assert payload["conversationId"] == conversation_id
    assert payload["command"] == 'python3 -c "print(1)"'
    assert payload["reason"] == "内联解释器执行"
    assert payload["timeoutSeconds"] == 300
    assert payload["expiresAt"] > payload["requestedAt"]
    # The tiers are the host's own vocabulary, never a set the phone invented.
    assert [option["choice"] for option in payload["options"]] == ["once", "session", "always", "deny"]
    assert [option["style"] for option in payload["options"]] == ["primary", "secondary", "secondary", "danger"]
    # No host-internal identifier ever leaves the Gateway.
    assert "session_key" not in payload and "sessionKey" not in payload


def test_the_published_payload_validates_against_the_shared_fixture(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    conversation_id = _seed_conversation(core)
    _publish_card(core, conversation_id)

    event = _events(core, "conversation.approval.requested")[0]
    envelope = {
        "correlationId": event["correlationId"],
        "occurredAt": event["occurredAt"],
        "payload": event["payload"],
    }
    assert core.contracts.validate_dispatched(
        BINDING_SET_ID, {"kind": "event", "eventType": "conversation.approval.requested"}, envelope,
    )


def test_a_smart_deny_offers_only_the_tiers_the_host_allows(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    conversation_id = _seed_conversation(core)

    _publish_card(core, conversation_id, actions=[("Allow Once", "once", "primary"), ("Deny", "deny", "danger")])

    payload = _events(core, "conversation.approval.requested")[0]["payload"]
    assert [option["choice"] for option in payload["options"]] == ["once", "deny"]


def test_a_prompt_without_a_known_tier_is_refused_instead_of_painting_an_empty_card(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    conversation_id = _seed_conversation(core)
    adapter = _adapter(core)
    prompt = _Prompt(conversation_id, "rm -rf /tmp/example", "递归删除", [("Allow Once", "allow-once", "primary")])

    result = asyncio.run(adapter._send_exec_approval_prompt(prompt))

    assert result.success is False
    assert _events(core, "conversation.approval.requested") == []


def test_a_decision_releases_the_agent_and_is_published_to_every_device(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    conversation_id = _seed_conversation(core)
    released: list[tuple[str, str, str | None]] = []
    core.approval_resolver = lambda session_key, choice, request_id: (
        released.append((session_key, choice, request_id)) or 1
    )
    approval_id = _publish_card(core, conversation_id)

    response = _decide(core, approval_id, "always")

    assert response["data"]["approval"]["decision"] == "always"
    # The decision reaches the host under the identifiers the Gateway persisted,
    # never ones the phone supplied.
    assert released == [("agent:main:open_android:dm:conv_1", "always", "host_req_1")]
    resolved = _events(core, "conversation.approval.resolved")
    assert len(resolved) == 1
    assert resolved[0]["payload"]["approvalId"] == approval_id
    assert resolved[0]["payload"]["decision"] == "always"
    assert resolved[0]["payload"]["conversationId"] == conversation_id


def test_the_same_decision_replayed_is_the_retry_it_looks_like(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    conversation_id = _seed_conversation(core)
    calls = []
    core.approval_resolver = lambda session_key, choice, request_id: (calls.append(choice) or 1)
    approval_id = _publish_card(core, conversation_id)

    first = _decide(core, approval_id, "once")
    replay = _decide(core, approval_id, "once", request_id="req_decision")

    assert first["data"] == replay["data"]
    assert calls == ["once"], "重放不得二次解析宿主侧等待中的请求"


def test_a_second_different_decision_is_a_conflict_not_an_update(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    conversation_id = _seed_conversation(core)
    core.approval_resolver = lambda session_key, choice, request_id: 1
    approval_id = _publish_card(core, conversation_id)

    _decide(core, approval_id, "once")
    second = _decide(core, approval_id, "deny", request_id="req_second")

    assert second["error"]["code"] == "APPROVAL_ALREADY_RESOLVED"
    assert second["error"]["details"]["decision"] == "once"
    decisions = [event["payload"]["decision"] for event in _events(core, "conversation.approval.resolved")]
    assert decisions == ["once"], "已落定的审批不得被第二个意见改写"


def test_a_decision_outside_the_closed_set_is_refused(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    conversation_id = _seed_conversation(core)
    core.approval_resolver = lambda session_key, choice, request_id: 1
    approval_id = _publish_card(core, conversation_id)

    unknown = _decide(core, approval_id, "approved")
    missing = _decide(core, "apr_missing", "once", request_id="req_missing")

    assert unknown["error"]["code"] == "APPROVAL_DECISION_INVALID"
    assert missing["error"]["code"] == "APPROVAL_NOT_FOUND"
    assert _events(core, "conversation.approval.resolved") == []


def test_an_approval_nobody_answered_settles_as_timeout(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    conversation_id = _seed_conversation(core)
    core.approval_resolver = lambda session_key, choice, request_id: 1
    approval_id = _publish_card(core, conversation_id, timeout=1)
    account = core.open_gateway_account(ACCOUNT_ID)
    try:
        account.store.database.execute(
            "UPDATE approvals SET expires_at = ? WHERE approval_id = ?",
            ("2020-01-01T00:00:00.000Z", approval_id),
        )
    finally:
        account.close()

    response = _decide(core, approval_id, "once")

    assert response["error"]["code"] == "APPROVAL_EXPIRED"
    resolved = _events(core, "conversation.approval.resolved")
    assert [event["payload"]["decision"] for event in resolved] == ["timeout"]


def test_a_timeout_is_settled_without_waiting_for_a_device_to_ask(tmp_path):
    """The Gateway's clock closes the window, not the phone's countdown."""
    core = create_gateway_core(storage_root=tmp_path)
    conversation_id = _seed_conversation(core)
    core.approval_resolver = lambda session_key, choice, request_id: 1
    approval_id = _publish_card(core, conversation_id, timeout=1)
    account = core.open_gateway_account(ACCOUNT_ID)
    try:
        account.store.database.execute(
            "UPDATE approvals SET expires_at = ? WHERE approval_id = ?",
            ("2020-01-01T00:00:00.000Z", approval_id),
        )
    finally:
        account.close()

    core.handle({
        "method": "GET",
        "target": "/open-android-intelligence/v2/events",
        "context": {
            "accountId": ACCOUNT_ID, "deviceId": "dev_approval", "sessionId": "sess_approval",
            "requestId": "req_events", "correlationId": "cor_events",
            "pairingGeneration": 1, "grantRevision": 1,
        },
    })

    resolved = _events(core, "conversation.approval.resolved")
    assert [event["payload"]["decision"] for event in resolved] == ["timeout"]


def test_a_decision_the_host_is_no_longer_waiting_for_is_not_claimed_as_allowed(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    conversation_id = _seed_conversation(core)
    core.approval_resolver = lambda session_key, choice, request_id: 0
    approval_id = _publish_card(core, conversation_id)

    response = _decide(core, approval_id, "once")

    assert response["error"]["code"] == "APPROVAL_EXPIRED"
    resolved = _events(core, "conversation.approval.resolved")
    assert [event["payload"]["decision"] for event in resolved] == ["withdrawn"], (
        "宿主已不再等待时不得把按钮按下的档位记成已允许"
    )


def test_approval_cards_are_only_advertised_when_a_decision_can_reach_the_agent(tmp_path):
    def negotiate(core) -> list[str]:
        response = core.handle({
            "method": "POST",
            "target": "/open-android-intelligence/v2/negotiate",
            "body": {
                "negotiationId": "neg_approval",
                "protocol": {"major": 2, "minor": 0},
                "client": {
                    "installationId": "install_1", "appVersion": "2.0.0",
                    "platform": "android", "platformApi": 35,
                },
                "features": {
                    "auth": ["password"], "messages": ["chat-v1"],
                    "attachments": ["staged-sha256-v1"], "events": ["sse-cursor-v1"],
                    "deviceRequests": ["risk-queue-v1"],
                    "conversationUi": [
                        "agent-command-catalog-v1", "agent-command-new-v1", "agent-approval-cards-v1",
                    ],
                },
                "schemaHashes": {"core": core.contracts.core_schema_hash},
            },
        })
        return response["data"]["features"].get("conversationUi", [])

    without_resolver = create_gateway_core(storage_root=tmp_path)
    assert "agent-approval-cards-v1" not in negotiate(without_resolver)

    with_resolver = create_gateway_core(storage_root=tmp_path)
    with_resolver.approval_resolver = lambda session_key, choice, request_id: 1
    assert "agent-approval-cards-v1" in negotiate(with_resolver)
