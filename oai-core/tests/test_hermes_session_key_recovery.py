"""Regression tests for Hermes session key disambiguation, orphaned ciphertext self-healing, and error status mapping."""
from __future__ import annotations

from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from oai_core.auth import Principal
from oai_core.gateway import local_keys
from oai_core.gateway.account_paths import account_paths
from oai_core.gateway.core import GatewayError, create_gateway_core, iso_millis
from oai_core.gateway.local_keys import LocalMasterKeyStore, create_master_key_file
from oai_core.sessions import AdapterError
from oai_hermes_adapter.backend import SessionBackend
from oai_hermes_adapter.sessions import HermesSessionAdapter, gateway_error_status


def _seed_account_with_key(storage_root: Path, key_path: Path, account_id: str = "djbd") -> tuple[str, str]:
    core = create_gateway_core(storage_root=storage_root, secret_store=LocalMasterKeyStore(key_path))
    with closing(core.open_gateway_account(account_id)) as account:
        created = account.conversations.create("client_old", "Old Conversation", "req_old")
        conv_id = created["conversationId"]
        account.agent_sessions.record(conv_id, "conversation-create")
        account.agent_sessions.attach(conv_id, "native_sess_old", "hermes:key:old")
        account.conversations.management.commit(conv_id, "archive", False, "corr_old")
        past = datetime.now(timezone.utc) - timedelta(days=2)
        evt = account.events.append(
            "conversation.updated",
            "corr_old",
            {"conversationId": conv_id, "archived": False},
            now=past,
        )
        account.store.database.execute(
            "UPDATE events SET expires_at = ? WHERE event_id = ?",
            ("2000-01-01T00:00:00.000Z", evt["eventId"]),
        )
        return conv_id, evt["eventId"]


@pytest.mark.asyncio
async def test_session_backend_prefers_existing_gateway_key_that_decrypts_account_db(tmp_path, monkeypatch):
    home_dir = tmp_path / "home"
    key_dir = home_dir / ".open-android-intelligence"
    key_dir.mkdir(parents=True, mode=0o700)
    gw_key = create_master_key_file(key_dir / "gateway-master-key")
    create_master_key_file(key_dir / "session-master-key")

    accounts_root = tmp_path / "accounts"
    accounts_root.mkdir(mode=0o700)
    conv_id, _ = _seed_account_with_key(accounts_root, gw_key, "djbd")

    monkeypatch.setattr(local_keys.Path, "home", staticmethod(lambda: home_dir))
    monkeypatch.delenv(local_keys.MASTER_KEY_FILE_ENV, raising=False)
    monkeypatch.delenv(local_keys.MASTER_KEY_FILE_ENV_ALIAS, raising=False)

    backend = SessionBackend(accounts_root, ["djbd"])
    try:
        assert isinstance(backend.secret_store, LocalMasterKeyStore)
        assert backend.secret_store.path == gw_key
        backend.acquire_accounts()
        adapter = HermesSessionAdapter(backend)
        sessions = await adapter.list_sessions(Principal("djbd", device_id="dev1"))
        assert [row["id"] for row in sessions] == [conv_id]
    finally:
        backend.close()


@pytest.mark.asyncio
async def test_session_backend_self_heals_orphaned_ciphertexts_from_lost_key(tmp_path):
    accounts_root = tmp_path / "accounts"
    accounts_root.mkdir(mode=0o700)
    lost_key = create_master_key_file(tmp_path / "lost-master-key")
    active_key = create_master_key_file(tmp_path / "active-session-key")

    old_conv, _ = _seed_account_with_key(accounts_root, lost_key, "djbd")

    # Simulate a newer conversation written under the active session key while
    # legacy conversation-management and expired events remain encrypted with lost_key.
    backend = SessionBackend(accounts_root, ["djbd"], secret_store=LocalMasterKeyStore(active_key))
    try:
        backend.acquire_accounts()
        adapter = HermesSessionAdapter(backend)
        principal = Principal("djbd", device_id="dev1")
        consumer = "a" * 64

        # 1. Listing sessions succeeds and preserves the historical native binding!
        sessions = await adapter.list_sessions(principal)
        assert [row["id"] for row in sessions] == [old_conv]

        # 2. Snapshot event sync succeeds (previously failed with 409 DECRYPTION_FAILED!)
        snap = await adapter.event_page(principal, old_conv, consumer_id=consumer, snapshot=True)
        assert snap["events"] == []
        assert isinstance(snap["cursor"], str) and snap["cursor"]

        # 3. Appending a new reply event succeeds without purge_expired crashing!
        with closing(backend.open_gateway_account("djbd")) as account:
            reply_evt = account.events.append(
                "conversation.message.completed",
                "msg_reply_1",
                {
                    "conversationId": old_conv,
                    "messageId": "msg_reply_1",
                    "sender": "assistant",
                    "text": "hello",
                    "parts": [{"type": "text", "text": "hello"}],
                    "timestamp": int(datetime.now(timezone.utc).timestamp() * 1000),
                    "revision": 1,
                },
            )

        page = await adapter.event_page(principal, old_conv, consumer_id=consumer, cursor=snap["cursor"])
        assert [e["eventId"] for e in page["events"]] == [reply_evt["eventId"]]
    finally:
        backend.close()


def test_event_store_purge_expired_tolerates_undecryptable_expired_rows(tmp_path):
    accounts_root = tmp_path / "accounts"
    accounts_root.mkdir(mode=0o700)
    key_a = create_master_key_file(tmp_path / "key-a")
    key_b = create_master_key_file(tmp_path / "key-b")

    conv_id, _ = _seed_account_with_key(accounts_root, key_a, "djbd")

    core_b = create_gateway_core(storage_root=accounts_root, secret_store=LocalMasterKeyStore(key_b))
    with closing(core_b.open_gateway_account("djbd")) as account:
        purged = account.events.purge_expired(iso_millis())
        assert purged >= 1
        # Appending a fresh event after purging undecryptable expired rows works cleanly.
        evt = account.events.append(
            "conversation.updated",
            "corr_new",
            {"conversationId": conv_id, "archived": True},
        )
        assert evt["eventId"].startswith("evt_")


@pytest.mark.asyncio
async def test_gateway_error_status_maps_decryption_and_storage_errors_to_503_not_409(tmp_path):
    assert gateway_error_status("DECRYPTION_FAILED") == 503
    assert gateway_error_status("ENCRYPTION_FAILED") == 503
    assert gateway_error_status("MASTER_KEY_UNAVAILABLE") == 503
    assert gateway_error_status("HOST_INCOMPATIBLE") == 503
    assert gateway_error_status("IDEMPOTENCY_CONFLICT") == 409
    assert gateway_error_status("ATTACHMENT_DIGEST_MISMATCH") == 409
    assert gateway_error_status("CONVERSATION_NOT_FOUND") == 404
    assert gateway_error_status("AUTHENTICATION_REQUIRED") == 401

    accounts_root = tmp_path / "accounts"
    accounts_root.mkdir(mode=0o700)
    key = create_master_key_file(tmp_path / "key")
    _seed_account_with_key(accounts_root, key, "djbd")

    backend = SessionBackend(accounts_root, ["djbd"], secret_store=LocalMasterKeyStore(key))
    try:
        backend.acquire_accounts()
        adapter = HermesSessionAdapter(backend)
        with pytest.raises(AdapterError) as err:
            with adapter._account(Principal("djbd", device_id="dev1")):
                raise GatewayError("DECRYPTION_FAILED")
        assert err.value.code == "DECRYPTION_FAILED"
        assert err.value.status == 503
    finally:
        backend.close()
