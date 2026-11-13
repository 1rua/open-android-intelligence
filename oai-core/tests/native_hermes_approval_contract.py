"""Executed with an isolated HERMES_HOME by test_hermes_approval_prompts.py."""
import asyncio
from contextlib import closing
from pathlib import Path
import sys
from types import SimpleNamespace

from gateway.config import Platform, PlatformConfig
from gateway.platforms.base import BasePlatformAdapter, SendResult
from gateway.run import _redact_approval_command
from gateway.run_turn_runner import TurnRunner
from tools import approval as host_approval
from tools.approval_gateway_wait import _ApprovalEntry

from oai_core.server import create_core
from oai_hermes_adapter.approvals import NativeApprovalMixin

repository, temporary = Path(sys.argv[1]), Path(sys.argv[2])
scenario, decision = sys.argv[3:5]


class ApprovalSurface(NativeApprovalMixin, BasePlatformAdapter):
    """Control only message egress, leaving prompt/card/host state real."""

    def __init__(self, core, conversation):
        super().__init__(PlatformConfig(), Platform.TELEGRAM)
        self.services = SimpleNamespace(core=core)
        self._conv_to_account = {conversation: "alice"}
        self.tasks = set()
        self.texts = []
        self.prompts = []
        self.sent_results = []

    async def connect(self, *, is_reconnect=False):
        return True

    async def disconnect(self):
        for task in tuple(self.tasks):
            task.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)

    async def send(self, chat_id, content, **kwargs):
        self.texts.append(content)
        return SendResult(success=True, message_id="text-fallback")

    async def get_chat_info(self, chat_id):
        return {"name": "synthetic approval chat", "type": "dm", "chat_id": chat_id}

    def _track_background_task(self, task, label):
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    async def _send_exec_approval_prompt(self, prompt):
        self.prompts.append(prompt)
        result = await super()._send_exec_approval_prompt(prompt)
        self.sent_results.append(result)
        return result


async def run():
    core = create_core({
        "storageRoot": str(temporary / "accounts"), "masterKeyFile": str(temporary / "master"),
        "contractRoot": str(repository / "gateway-contract"),
    })
    with closing(core.open_gateway_account("alice")) as account:
        account.credentials.create_password("synthetic-approval-test-password")
        conversation = account.conversations.create("approval-test", "approval-test", "approval-test")["conversationId"]
        account.agent_sessions.record(conversation, "conversation-create")
        account.agent_sessions.attach(conversation, "native_synthetic", "synthetic_session")
        account.conversations.accept_message(conversation, "client_1", "synthetic turn", [],
            "phone", "request_1", "approval-test")
        account.conversations.claim_dispatch("client_1")

    surface = ApprovalSurface(core, conversation)
    loop = asyncio.get_running_loop()
    context = SimpleNamespace(_status_adapter=surface, _status_chat_id=conversation,
        _status_thread_metadata={}, session_key="synthetic_session", _loop_for_step=loop,
        stream_consumer_holder=[], _run_still_current=lambda: True)
    runner = TurnRunner(SimpleNamespace(), context)
    # Commands are strings passed through approval display only; none are run.
    raw = "systemctl --user restart hermes-gateway" if scenario == "plain_parallel" \
        else "export OPENAI_API_KEY=sk-" + "a" * 48
    display = _redact_approval_command(raw)
    assert (display == raw) == (scenario == "plain_parallel"), "the fixture must exercise the real SDK redactor"

    def enqueue(request_id, command):
        entry = _ApprovalEntry({"request_id": request_id, "command": command,
            "description": "Hermes 返回的审批原因", "allow_permanent": True, "allow_session": True})
        with host_approval._lock:
            host_approval._gateway_queues.setdefault("synthetic_session", []).append(entry)
        return entry

    selected = enqueue("selected_request", raw)
    if scenario in {"redacted_ambiguous", "redacted_literal_collision"}:
        # Both raw commands render identically. Never choose an arbitrary wait.
        other = enqueue("other_request", display if scenario == "redacted_literal_collision"
            else raw.replace("a" * 48, "b" * 48))
        assert _redact_approval_command(other.data["command"]) == display
    elif scenario == "text_fallback":
        # A live wait exists, but its session is not bound to this conversation.
        context.session_key = "not_the_bound_session"
        other = enqueue("other_request", "echo unrelated")
    else:
        other = enqueue("other_request", "echo unrelated")

    try:
        if scenario == "host_options":
            # Actual SDK flags restrict smart-denied prompts to once/deny.
            selected.data["smart_denied"] = True
        elif scenario == "host_without_always":
            selected.data["allow_permanent"] = False
        elif scenario == "host_without_session":
            # The SDK does not offer permanent permission without session
            # permission, even when allow_permanent itself remains True.
            selected.data["allow_session"] = False
        elif scenario == "host_once_deny":
            selected.data.update(allow_permanent=False, allow_session=False)
        await asyncio.to_thread(runner._approval_notify_sync, dict(selected.data))
        assert selected.result is None and other.result is None, "publishing cannot approve or deny a host wait"
        assert len(surface.prompts) == 1
        prompt = surface.prompts[0]
        assert prompt.command == display, "preserve the actual host callback command, including its own redaction"

        if scenario in {"redacted_ambiguous", "redacted_literal_collision", "text_fallback"}:
            assert len(surface.sent_results) == 1 and not surface.sent_results[0].success
            assert len(surface.texts) == 1 and display in surface.texts[0]
            assert "/approve" in surface.texts[0] and "/deny" in surface.texts[0]
            with closing(core.open_gateway_account("alice")) as account:
                assert account.store.database.execute("SELECT COUNT(*) FROM approvals").fetchone()[0] == 0
            return

        assert len(surface.sent_results) == 1 and surface.sent_results[0].success, (
            "the real Hermes callback fell back to text instead of publishing its approval card")
        assert surface.texts == [], "a successful structured prompt must not be duplicated as ordinary text"
        with closing(core.open_gateway_account("alice")) as account:
            card = account.approvals.lookup(surface.sent_results[0].message_id)
            assert card["command"] == prompt.command and card["reason"] == prompt.description
            expected_options = [
                {"choice": choice, "label": label, **({"style": style} if style else {})}
                for label, choice, style in prompt.actions
            ]
            assert card["options"] == expected_options, "preserve host action labels, styles and order without filling missing choices"
            choices = [item["choice"] for item in card["options"]]
            expected_choices = {
                "host_options": ["once", "deny"],
                "host_without_always": ["once", "session", "deny"],
                "host_without_session": ["once", "deny"],
                "host_once_deny": ["once", "deny"],
            }.get(scenario, ["once", "session", "always", "deny"])
            assert choices == expected_choices
        receipt = await surface.decide_native_approval("alice", conversation, card["approvalId"], decision, "phone")
        assert receipt["approval"]["decision"] == decision
        assert selected.result == decision and other.result is None, "a decision must wake only the selected host wait"
        replay = await surface.decide_native_approval("alice", conversation, card["approvalId"], decision, "phone")
        assert replay == receipt
        assert len(host_approval.list_gateway_approvals("synthetic_session")) == 1
    finally:
        await surface.disconnect()
        host_approval.unregister_gateway_notify("synthetic_session")


asyncio.run(run())
print("native approval prompt contracts passed")
