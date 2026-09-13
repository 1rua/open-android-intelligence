import hashlib
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from open_android_intelligence_gateway.core import GatewayError, create_gateway_core
from open_android_intelligence_gateway.http import create_gateway_exposure
from test_support import core_schema_hash, make_secret_store, make_verified_request, trust_core


_real_create_gateway_core = create_gateway_core


def create_gateway_core(storage_root=None, **options):
    options.setdefault("secret_store", make_secret_store())
    return trust_core(_real_create_gateway_core(storage_root=storage_root, **options))


NEGOTIATION_BODY = {
    "negotiationId": "neg_attachment_policy",
    "protocol": {"major": 2, "minor": 0},
    "client": {
        "installationId": "install_attachment_policy",
        "appVersion": "2.0.0",
        "platform": "android",
        "platformApi": 35,
    },
    "features": {
        "auth": ["password", "refresh"],
        "messages": ["chat-v1"],
        "attachments": ["staged-sha256-v1"],
        "events": ["sse-cursor-v1"],
        "deviceRequests": ["risk-queue-v1"],
    },
    "schemaHashes": {"core": core_schema_hash()},
}


def _negotiate(core):
    response = core.handle(
        {
            "requestId": "req_attachment_negotiate",
            "correlationId": "cor_attachment_negotiate",
            "method": "POST",
            "target": "/open-android-intelligence/v2/negotiate",
            "body": NEGOTIATION_BODY,
        }
    )
    assert "data" in response
    return response["data"]["limits"]


def _create_input(size_bytes=4, media_type="text/plain", client_id="att_client"):
    return {
        "clientAttachmentId": client_id,
        "filename": "note.txt",
        "mediaType": media_type,
        "sizeBytes": size_bytes,
        "sha256": hashlib.sha256(b"body").hexdigest(),
        "correlationId": f"cor_{client_id}",
    }


def _create(account, body=b"body", *, now=None, client_id="att_client", media_type="text/plain"):
    values = _create_input(len(body), media_type, client_id)
    values["sha256"] = hashlib.sha256(body).hexdigest()
    if now is not None:
        values["now"] = now
    return account.attachments.create(**values)


def _expire_row(account, attachment_id):
    account.store.database.execute(
        "UPDATE attachments SET expires_at = ? WHERE attachment_id = ?",
        ("2020-01-01T00:00:00.000Z", attachment_id),
    )


def test_negotiated_single_attachment_limit_rejects_oversize_create(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    limits = _negotiate(core)
    account = core.open_gateway_account("acct_attachment_policy")

    with pytest.raises(GatewayError) as error:
        account.attachments.create(
            **_create_input(
                limits["maxSingleAttachmentBytes"] + 1,
                client_id="att_too_large",
            )
        )

    assert error.value.code == "ATTACHMENT_LIMIT_EXCEEDED"


def test_negotiated_media_types_reject_unsupported_create(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    limits = _negotiate(core)
    account = core.open_gateway_account("acct_attachment_media")

    with pytest.raises(GatewayError) as error:
        account.attachments.create(
            **_create_input(
                media_type="application/x-not-negotiated",
                client_id="att_bad_media",
            )
        )

    assert "application/x-not-negotiated" not in limits["allowedMediaTypes"]
    assert error.value.code == "ATTACHMENT_LIMIT_EXCEEDED"


def test_server_owns_attachment_ttl_and_rejects_caller_expiration_override(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    limits = _negotiate(core)
    account = core.open_gateway_account("acct_attachment_ttl")

    with pytest.raises(GatewayError) as error:
        account.attachments.create(
            **_create_input(client_id="att_ttl_override"),
            now="2026-08-28T00:00:00.000Z",
            expiresAt="2099-01-01T00:00:00.000Z",
        )

    assert error.value.code == "SCHEMA_INVALID"
    assert limits["attachmentTtlSeconds"] == 3600


def test_message_attachment_total_uses_negotiated_message_limit(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    limits = _negotiate(core)
    account = core.open_gateway_account("acct_message_attachment_limit")
    size = limits["maxMessageAttachmentBytes"] // 3 + 1
    attachment_ids = []

    for index in range(3):
        attachment = _create(account, body=b"x", client_id=f"att_message_{index}")
        account.store.database.execute(
            "UPDATE attachments SET size_bytes = ?, state = 'verified' WHERE attachment_id = ?",
            (size, attachment["attachmentId"]),
        )
        attachment_ids.append(attachment["attachmentId"])

    conversation = account.conversations.create(
        client_conversation_id="conv_message_limit",
        title=None,
        correlation_id="cor_message_limit",
    )

    with pytest.raises(GatewayError) as error:
        account.conversations.accept_message(
            conversation_id=conversation["conversationId"],
            client_message_id="msg_message_limit",
            text="too many bytes",
            attachment_ids=attachment_ids,
            device_id="dev_1",
            request_id="req_message_limit",
            correlation_id="cor_message_limit_send",
        )

    assert error.value.code == "ATTACHMENT_LIMIT_EXCEEDED"


def test_overdue_attachment_rejects_upload_and_stabilizes_expired_state(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_expired_upload")
    attachment = _create(
        account,
        now="2020-01-01T00:00:00.000Z",
        client_id="att_expired_upload",
    )

    with pytest.raises(GatewayError) as error:
        account.attachments.upload_content(attachment["attachmentId"], b"body")

    assert error.value.code == "ATTACHMENT_EXPIRED"
    assert account.attachments.get(attachment["attachmentId"])["state"] == "expired"


def test_expired_commit_cannot_verify_or_keep_staged_bytes(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_expired_commit")
    attachment = _create(account, client_id="att_expired_commit")
    account.attachments.upload_content(attachment["attachmentId"], b"body")
    _expire_row(account, attachment["attachmentId"])

    with pytest.raises(GatewayError) as error:
        account.attachments.commit(attachment["attachmentId"])

    assert error.value.code == "ATTACHMENT_EXPIRED"
    record = account.attachments.get(attachment["attachmentId"])
    assert record["state"] == "expired"
    assert record["hasStagedBytes"] is False


def test_get_expired_attachment_does_not_expose_uploading_state(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_expired_get")
    attachment = _create(account, client_id="att_expired_get")
    account.attachments.upload_content(attachment["attachmentId"], b"body")
    _expire_row(account, attachment["attachmentId"])

    record = account.attachments.get(attachment["attachmentId"])

    assert record["state"] == "expired"
    assert record["hasStagedBytes"] is False


def test_expired_attachment_cannot_be_referenced_by_a_message(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_expired_reference")
    attachment = _create(account, client_id="att_expired_reference")
    account.attachments.upload_content(attachment["attachmentId"], b"body")
    account.attachments.commit(attachment["attachmentId"])
    _expire_row(account, attachment["attachmentId"])
    conversation = account.conversations.create(
        client_conversation_id="conv_expired_reference",
        title=None,
        correlation_id="cor_expired_reference",
    )

    with pytest.raises(GatewayError) as error:
        account.conversations.accept_message(
            conversation_id=conversation["conversationId"],
            client_message_id="msg_expired_reference",
            text="must not reference expired attachment",
            attachment_ids=[attachment["attachmentId"]],
            device_id="dev_1",
            request_id="req_expired_reference",
            correlation_id="cor_expired_reference_send",
            now="2030-01-01T00:00:00.000Z",
        )

    assert error.value.code == "ATTACHMENT_EXPIRED"
    assert account.attachments.get(attachment["attachmentId"])["state"] == "expired"


def test_cleanup_expires_overdue_attachment_before_deleting_references(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_expired_cleanup")
    attachment = _create(account, client_id="att_expired_cleanup")
    account.attachments.upload_content(attachment["attachmentId"], b"body")
    _expire_row(account, attachment["attachmentId"])

    account.attachments.cleanup()

    row = account.store.database.execute(
        "SELECT state, content_path, cas_path FROM attachments WHERE attachment_id = ?",
        (attachment["attachmentId"],),
    ).fetchone()
    assert row["state"] == "deleted"
    assert row["content_path"] is None
    assert row["cas_path"] is None


def test_expiring_one_attachment_does_not_remove_a_shared_cas_reference(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_shared_cas_expiry")
    first = _create(account, client_id="att_shared_expiry_first")
    second = _create(account, client_id="att_shared_expiry_second")
    for attachment in (first, second):
        account.attachments.upload_content(attachment["attachmentId"], b"body")
        account.attachments.commit(attachment["attachmentId"])

    _expire_row(account, first["attachmentId"])
    assert account.attachments.get(first["attachmentId"])["state"] == "expired"

    second_record = account.attachments.get(second["attachmentId"])
    assert second_record["state"] == "verified"
    second_row = account.store.database.execute(
        "SELECT cas_path FROM attachments WHERE attachment_id = ?",
        (second["attachmentId"],),
    ).fetchone()
    assert Path(second_row["cas_path"]).is_file()


def test_attachment_storage_without_an_explicit_aead_key_fails_closed(tmp_path):
    core = _real_create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_no_aead")

    with pytest.raises(GatewayError) as error:
        account.attachments.create(**_create_input(client_id="att_no_aead"))

    assert error.value.code == "MASTER_KEY_UNAVAILABLE"
    assert account.master_key_ref == ""
    stored_ref = account.store.database.execute(
        "SELECT value FROM account_metadata WHERE key = 'master_key_ref'"
    ).fetchone()[0]
    assert stored_ref == ""
    blocked = core.handle(make_verified_request({
            "context": {
                "accountId": "acct_no_aead", "deviceId": "dev_1", "sessionId": "sess_1",
                "requestId": "req_no_aead", "correlationId": "cor_no_aead",
                "pairingGeneration": 1, "grantRevision": 1,
            },
            "method": "GET", "target": "/open-android-intelligence/v2/conversations",
        }))
    assert blocked["error"]["code"] == "MASTER_KEY_UNAVAILABLE"


class _ReferenceOnlySecretStore:
    def get_or_create(self, name):
        return f"operator-ref://{name}"


def test_reference_only_secret_store_does_not_enable_plaintext_fallback(tmp_path):
    core = _real_create_gateway_core(
        storage_root=tmp_path, secret_store=_ReferenceOnlySecretStore()
    )
    account = core.open_gateway_account("acct_reference_only")

    with pytest.raises(GatewayError) as error:
        account.attachments.create(**_create_input(client_id="att_reference_only"))

    assert error.value.code == "MASTER_KEY_UNAVAILABLE"
    assert account.master_key_ref == ""


def test_attachment_stage_and_cas_are_sealed_by_the_host_aead_provider(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_sealed_attachment")
    body = b"attachment body must not be stored in plaintext"
    attachment = _create(account, body=body, client_id="att_sealed")

    uploaded = account.attachments.upload_content(attachment["attachmentId"], body)
    stage_path = Path(
        account.store.database.execute(
            "SELECT content_path FROM attachments WHERE attachment_id = ?",
            (attachment["attachmentId"],),
        ).fetchone()[0]
    )
    stage_bytes = stage_path.read_bytes()
    assert uploaded["state"] == "uploading"
    assert body not in stage_bytes
    assert stage_bytes.startswith(b"aead-v1:")

    verified = account.attachments.commit(attachment["attachmentId"])
    cas_path = Path(
        account.store.database.execute(
            "SELECT cas_path FROM attachments WHERE attachment_id = ?",
            (attachment["attachmentId"],),
        ).fetchone()[0]
    )
    assert verified["state"] == "verified"
    assert body not in cas_path.read_bytes()
    assert account.attachments.get(attachment["attachmentId"])["state"] == "verified"


def test_device_parameters_and_event_payload_are_sealed_at_rest(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_sealed_device")
    parameters = {"query": "from:alice", "senders": ["alice"]}
    request = account.device_requests.enqueue(
        **{
            "request_id": "device_req_sealed",
            "device_id": "dev_1",
            "pairing_generation": 4,
            "grant_revision": 7,
            "risk": "read",
            "capability": {"id": "org.openandroidintelligence.sms.query", "version": "1.0.0"},
            "provider": {"pluginId": "org.openandroidintelligence.sms", "authorKeyId": "sha256:" + "a" * 64},
            "parameters": parameters,
            "correlation_id": "cor_sealed_device",
        }
    )
    row = account.store.database.execute(
        "SELECT parameters_json FROM device_requests WHERE request_id = 'device_req_sealed'"
    ).fetchone()
    event_row = account.store.database.execute(
        "SELECT payload_json FROM events WHERE event_type = 'device.requested'"
    ).fetchone()

    assert request["parameters"] == parameters
    assert "from:alice" not in row["parameters_json"]
    assert "from:alice" not in event_row["payload_json"]
    assert account.device_requests.get("device_req_sealed")["parameters"] == parameters
    assert account.events.read_after(None)[0]["payload"]["parameters"] == parameters


def test_idempotency_outcome_is_sealed_at_rest_and_replays_through_the_provider(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    request = {
        "context": {
            "accountId": "acct_sealed_idempotency", "deviceId": "dev_1",
            "sessionId": "sess_1", "requestId": "req_sealed_idempotency",
            "correlationId": "cor_sealed_idempotency", "pairingGeneration": 1,
            "grantRevision": 1,
        },
        "method": "POST", "target": "/open-android-intelligence/v2/conversations",
        "idempotencyKey": "req_sealed_idempotency",
        "body": {"clientConversationId": "conv_sealed_idempotency", "title": "private title"},
    }

    first = core.handle(request)
    account = core.open_gateway_account("acct_sealed_idempotency")
    row = account.store.database.execute(
        "SELECT outcome_json FROM idempotency_ledger WHERE request_id = 'req_sealed_idempotency'"
    ).fetchone()

    assert first["data"]["conversation"]["title"] == "private title"
    assert row["outcome_json"].startswith("aead-v1:")
    assert "private title" not in row["outcome_json"]
    assert core.handle(request) == first


def test_tampered_attachment_ciphertext_fails_closed(tmp_path):
    core = create_gateway_core(storage_root=tmp_path)
    account = core.open_gateway_account("acct_tampered_attachment")
    body = b"tamper me"
    attachment = _create(account, body=body, client_id="att_tampered")
    account.attachments.upload_content(attachment["attachmentId"], body)
    stage_path = Path(
        account.store.database.execute(
            "SELECT content_path FROM attachments WHERE attachment_id = ?",
            (attachment["attachmentId"],),
        ).fetchone()[0]
    )
    stored = bytearray(stage_path.read_bytes())
    stored[-1] ^= 1
    stage_path.write_bytes(bytes(stored))

    with pytest.raises(GatewayError) as error:
        account.attachments.commit(attachment["attachmentId"])

    assert error.value.code == "DECRYPTION_FAILED"


class _RawRequest:
    def __init__(self, body, url):
        self.method = "PUT"
        self.url = url
        self.headers = {"content-type": "application/octet-stream"}
        self.rawHeaders = ("content-type", "application/octet-stream")
        self.body = body


class _RawResponse:
    def __init__(self):
        self.status_code = 0
        self.headers = {}
        self.body = ""

    def set_header(self, name, value):
        self.headers[name.lower()] = value

    def end(self, body):
        self.body = body


class _RawCore:
    def __init__(self):
        self.requests = []

    def handle(self, request):
        self.requests.append(request)
        return {
            "requestId": "request-raw-attachment",
            "correlationId": "correlation-raw-attachment",
            "protocol": "2.0",
            "data": {"accepted": True},
        }


def test_raw_attachment_body_limit_accepts_the_negotiated_single_attachment_size():
    core = _RawCore()
    seen = []

    def verify(request):
        seen.append(request)
        return make_verified_request({
            "context": {
                "accountId": "acct_raw_attachment",
                "deviceId": "dev_raw_attachment",
                "sessionId": "sess_raw_attachment",
                "requestId": "request-raw-attachment",
                "correlationId": "correlation-raw-attachment",
                "pairingGeneration": 1,
                "grantRevision": 1,
            },
            "method": request["method"],
            "target": request["target"],
            "body": request["body"],
            "idempotencyKey": "request-raw-attachment",
        })

    exposure = create_gateway_exposure(
        "direct-tls",
        core=core,
        host_version="1.0.0",
        host_api={
            "minVersion": "1.0.0",
            "maxVersion": "1.0.0",
            "verifiedCommit": "0123456789abcdef0123456789abcdef01234567",
        },
        verify_request=verify,
    )
    route = next(item for item in exposure.routes if item.path == "/open-android-intelligence/v2/attachments/")
    body = b"x" * 26_214_400
    response = _RawResponse()

    route.handler(
        _RawRequest(body, "/open-android-intelligence/v2/attachments/att_raw/content"),
        response,
    )

    assert response.status_code == 200
    assert len(seen) == 1
    assert seen[0]["body"] == body
    assert len(core.requests) == 1
