"""Observable target selection and immutable request retry behaviour."""
from contextlib import contextmanager
from types import SimpleNamespace

import pytest

from oai_core.auth import Principal
from oai_core.device_service import DeviceService
from oai_core.gateway.core import GatewayCore, GatewayError
from oai_core.routing import DeviceRouter
from gateway_runtime.support import make_secret_store


@pytest.fixture
def account(tmp_path):
    core = GatewayCore(storage_root=tmp_path / "accounts", secret_store=make_secret_store())
    store = core.open_gateway_account("alice")
    core.online = set()
    core.is_device_online = lambda owner, device, generation: owner == "alice" and device in core.online
    for device in ("old", "phone", "tablet"):
        store.store.database.execute("INSERT INTO device_keys VALUES (?,?,?,?,?,?)",
            (device, "install-" + device, "test-key", 1, 1, "2026-01-01T00:00:00.000Z"))
    try:
        yield core, store
    finally:
        store.close()


def test_only_online_device_ignores_historical_offline_devices(account):
    core, store = account
    core.online = {"phone"}
    assert DeviceRouter(core).resolve(store, Principal("alice", client_id="client")).device_id == "phone"


def test_ambiguous_online_devices_return_only_own_candidates(account):
    core, store = account
    core.online = {"phone", "tablet"}
    with pytest.raises(GatewayError) as failure:
        DeviceRouter(core).resolve(store, Principal("alice"))
    assert failure.value.code == "DEVICE_SELECTION_REQUIRED"
    assert {r["deviceId"] for r in failure.value.details["devices"]} == {"phone", "tablet"}


def test_explicit_source_and_default_keep_offline_target(account):
    core, store = account
    core.online = {"phone"}
    router = DeviceRouter(core)
    router.set_default(store, "old")
    principal = Principal("alice", client_id="client")
    assert router.resolve(store, principal).device_id == "old"
    assert router.resolve(store, principal, device_id="tablet").device_id == "tablet"
    ctx = SimpleNamespace(device_id="tablet", pairing_generation=1)
    assert router.resolve(store, Principal("alice", context=ctx)).device_id == "tablet"


def test_revoked_default_falls_back_but_invalid_explicit_fails(account):
    core, store = account
    core.online = {"phone"}
    router = DeviceRouter(core)
    router.set_default(store, "old")
    store.store.database.execute("DELETE FROM device_keys WHERE device_id='old'")
    assert router.resolve(store, Principal("alice")).device_id == "phone"
    with pytest.raises(GatewayError, match="DEVICE_ACCESS_DENIED"):
        router.resolve(store, Principal("alice"), device_id="foreign-device")


def test_legacy_credential_cannot_expand_to_another_device(account):
    core, store = account
    principal = Principal("alice", device_id="old", pairing_generation=1, token_id="legacy")
    assert DeviceRouter(core).resolve(store, principal).device_id == "old"
    with pytest.raises(GatewayError, match="DEVICE_ACCESS_DENIED"):
        DeviceRouter(core).resolve(store, principal, device_id="phone")


def test_unverified_origin_is_not_ignored(account):
    core, store = account
    core.online = {"phone"}
    with pytest.raises(GatewayError, match="ORIGIN_CONTEXT_INVALID"):
        DeviceRouter(core).resolve(store, Principal("alice"), origin_context="forged")


def test_default_must_be_owned(account):
    core, store = account
    with pytest.raises(GatewayError, match="DEVICE_ACCESS_DENIED"):
        DeviceRouter(core).set_default(store, "not-owned")
    assert DeviceRouter(core).default_device(store) is None
