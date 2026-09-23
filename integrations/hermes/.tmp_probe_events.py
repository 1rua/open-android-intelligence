import sys
import tempfile
from pathlib import Path

sys.path.insert(0, ".")
sys.path.insert(0, "tests")

from open_android_intelligence_gateway.core import create_gateway_core, GatewayError  # noqa: E402
from android_gateway_fixture import make_secret_store  # noqa: E402

root = Path(tempfile.mkdtemp())
core = create_gateway_core(storage_root=root, secret_store=make_secret_store())
account = core.open_gateway_account("probe")

probes = [
    ("gateway.notice", {"noticeCode": "maintenance"}),
    ("conversation.message.delta", {
        "conversationId": "conv_1", "messageId": "msg_1", "sender": "assistant",
        "parts": [{"type": "text", "text": "hi"}], "text": "hi",
        "timestamp": 1780000000000, "revision": 0,
    }),
    ("conversation.message.completed", {
        "conversationId": "conv_1", "messageId": "msg_1", "sender": "assistant",
        "parts": [], "text": "", "timestamp": 1780000000000, "revision": 0,
    }),
    ("conversation.title.updated", {"conversationId": "conv_1", "title": "t", "newTitle": "t"}),
    ("device.request.cancel.requested", {"requestId": "device_request_1"}),
    ("pairing.grant.changed", {"grantRevision": 2}),
    ("session.revoked", {"sessionId": "sess_1", "deviceId": "dev_1"}),
    ("attachment.acknowledged", {"attachmentId": "att_1"}),
    ("device.requested", {
        "requestId": "device_request_1",
        "capability": {"id": "org.openandroidintelligence.sms.query", "version": "1.0.0"},
        "provider": {
            "pluginId": "org.openandroidintelligence.sms",
            "authorKeyId": "sha256:" + "a" * 64,
        },
        "parameters": {"query": "from:alice"},
        "risk": "read",
        "grantRevision": 7,
        "createdAt": "2026-08-27T00:00:00.000Z",
        "expiresAt": "2026-08-28T00:00:00.000Z",
        "requiresForegroundConfirmation": False,
    }),
]

for event_type, payload in probes:
    try:
        account.events.append(event_type, "cor_probe", payload)
        print("PASS", event_type)
    except GatewayError as error:
        print("FAIL", event_type, "->", error)
    except Exception as error:  # noqa: BLE001
        print("ERROR", event_type, "->", type(error).__name__, error)
