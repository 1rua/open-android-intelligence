"""Session adapter delivery-status reporting without a live host."""
import os
import sys
from pathlib import Path

import pytest

_SDK_ROOT = os.environ.get("HERMES_SDK_ROOT")
# 适配器 import 时会导入宿主的 gateway 包；CI 只把 SDK 检出到 HERMES_SDK_ROOT，
# 并没有装进 site-packages，所以这里显式补上搜索路径，避免 ModuleNotFoundError。
if _SDK_ROOT:
    sys.path.insert(0, str(Path(_SDK_ROOT).resolve()))

pytestmark = pytest.mark.skipif(
    not _SDK_ROOT,
    reason="requires an explicit pinned Hermes SDK checkout",
)


class _Conversations:
    def __init__(self, status="queued", failure=None):
        self.status = status
        self.failure = failure
        self.calls = []

    def update_dispatch_status(self, message_id, status, error_code, correlation_id, *, claim_token=None):
        self.calls.append((message_id, status, error_code, correlation_id, claim_token))
        if self.failure is not None:
            raise self.failure
        return {"status": status}


class _Account:
    def __init__(self, status="queued", failure=None):
        self.conversations = _Conversations(status, failure)


def _record(status="queued", message_id="msg_1"):
    return {"messageId": message_id, "status": status}


@pytest.mark.asyncio
async def test_queued_record_is_reported_as_delivered():
    from oai_hermes_adapter.runtime import report_dispatch_delivered

    account = _Account()
    assert await report_dispatch_delivered(account, _record(), "claim_1") is True
    assert account.conversations.calls == [("msg_1", "delivered", None, "agent-started-msg_1", "claim_1")]


@pytest.mark.asyncio
async def test_already_delivered_record_is_a_no_op():
    from oai_hermes_adapter.runtime import report_dispatch_delivered

    account = _Account(status="delivered")
    assert await report_dispatch_delivered(account, _record(status="delivered"), "claim_1") is False
    assert account.conversations.calls == []


@pytest.mark.asyncio
async def test_invalid_transition_is_swallowed():
    from oai_core.gateway.core import GatewayError
    from oai_hermes_adapter.runtime import report_dispatch_delivered

    account = _Account(failure=GatewayError("INVALID_STATE_TRANSITION"))
    assert await report_dispatch_delivered(account, _record(), "claim_1") is False
    assert len(account.conversations.calls) == 1


@pytest.mark.asyncio
async def test_unexpected_failure_is_swallowed():
    from oai_hermes_adapter.runtime import report_dispatch_delivered

    account = _Account(failure=RuntimeError("disk busy"))
    assert await report_dispatch_delivered(account, _record(), None) is False


@pytest.mark.asyncio
async def test_missing_record_is_a_no_op():
    from oai_hermes_adapter.runtime import report_dispatch_delivered

    assert await report_dispatch_delivered(_Account(), None, "claim_1") is False


@pytest.mark.asyncio
async def test_completion_retires_all_materialized_attachment_files(tmp_path):
    """A successful host callback must finish even when inbound media exists."""
    from types import SimpleNamespace
    from oai_hermes_adapter.runtime import SessionOnlyAdapter

    closed = []
    account = SimpleNamespace(
        conversations=SimpleNamespace(dispatch_message=lambda _identity: None),
        close=lambda: closed.append(True),
    )
    paths = [tmp_path / "first.png", tmp_path / "second.pdf"]
    for path in paths:
        path.write_bytes(b"synthetic attachment")
    runtime = SessionOnlyAdapter.__new__(SessionOnlyAdapter)
    runtime.services = SimpleNamespace(core=SimpleNamespace(open_gateway_account=lambda _owner: account))
    runtime._processing_media_files = {("alice", "client-attachment"): paths}
    event = SimpleNamespace(source=SimpleNamespace(user_id="alice"), message_id="client-attachment")

    await runtime.on_processing_complete(event, "completed")

    assert closed == [True]
    assert runtime._processing_media_files == {}
    assert all(not path.exists() for path in paths)


@pytest.mark.asyncio
async def test_identical_replies_with_distinct_native_ids_remain_distinct():
    """Content equality cannot replace the host's explicit message identity."""
    from types import SimpleNamespace
    from oai_hermes_adapter.runtime import SessionOnlyAdapter

    runtime = SessionOnlyAdapter.__new__(SessionOnlyAdapter)
    runtime._turn_replies = {}
    runtime._conv_to_account = {"chat": "alice"}
    runtime._account_id = None
    writes = []
    async def complete(chat, identity, content, **_kwargs):
        writes.append((chat, identity, content))
    runtime.complete_message = complete

    first = await runtime.send("chat", "same synthetic reply", metadata={"messageId": "native-one"})
    second = await runtime.send("chat", "same synthetic reply", metadata={"messageId": "native-two"})
    finalized = await runtime.edit_message("chat", "native-two", "same synthetic reply", finalize=True)

    assert [first.message_id, second.message_id, finalized.message_id] == ["native-one", "native-two", "native-two"]
    assert [identity for _, identity, _ in writes] == ["native-one", "native-two", "native-two"]


@pytest.mark.asyncio
async def test_tool_observer_failure_cannot_block_authoritative_completion_or_media_cleanup(tmp_path, caplog):
    from types import SimpleNamespace
    from oai_hermes_adapter.runtime import SessionOnlyAdapter

    from contextlib import nullcontext

    settled, dispatch_updates, cleaned, closed = [], [], [], []
    generation = {"generationId": "gen-one", "state": "running", "revision": 1}
    def settle(message_id, status):
        settled.append((message_id, status))
        generation["state"] = status
    record = {"messageId": "msg-one", "conversationId": "chat", "status": "delivered"}
    account = SimpleNamespace(conversations=SimpleNamespace(
        dispatch_message=lambda identity: record,
        workflow=SimpleNamespace(for_message=lambda identity: generation, settle=settle),
        update_dispatch_status=lambda *args: dispatch_updates.append(args)),
        store=SimpleNamespace(transaction=nullcontext), close=lambda: closed.append(True))
    runtime = SessionOnlyAdapter.__new__(SessionOnlyAdapter)
    runtime.services = SimpleNamespace(core=SimpleNamespace(open_gateway_account=lambda owner: account))
    runtime._processing_media_files = {("alice", "client-one"): []}
    async def completed_cleanup(*args):
        cleaned.append(args)
    runtime.withdraw_generation_approvals = completed_cleanup
    runtime._finish_streaming_replies = completed_cleanup
    def observer_failure(*args):
        raise RuntimeError("private synthetic observer payload")
    runtime._tool_observer = SimpleNamespace(close_generation=observer_failure)
    event = SimpleNamespace(source=SimpleNamespace(user_id="alice"), message_id="client-one")

    await runtime.on_processing_complete(event, "completed")

    assert settled == [("msg-one", "completed")]
    assert dispatch_updates[0][1] == "completed"
    assert len(cleaned) == 2 and closed == [True, True]
    assert runtime._processing_media_files == {}
    assert "private synthetic observer payload" not in caplog.text
