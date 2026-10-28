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
