"""A slow native stream reads durable events in bounded commit-order batches."""
import pytest

from oai_core.gateway.core import create_gateway_core
from .support import make_secret_store


def test_event_replay_pages_preserve_every_identity_and_account_boundary(tmp_path):
    core = create_gateway_core(storage_root=tmp_path, secret_store=make_secret_store())
    alice, bob = core.open_gateway_account("alice"), core.open_gateway_account("bob")
    try:
        expected = [alice.events.append("gateway.notice", f"cor_{i}", {"noticeCode": "replay"})["eventId"]
                    for i in range(7)]
        bob.events.append("gateway.notice", "cor_other", {"noticeCode": "other"})
        cursor, seen = None, []
        while page := alice.events.read_after(cursor, limit=2):
            assert len(page) <= 2
            seen.extend(event["eventId"] for event in page)
            cursor = page[-1]["eventId"]
        assert seen == expected
        assert [event["eventId"] for event in alice.events.read_after(None)] == expected
        assert len(bob.events.read_after(None, limit=2)) == 1
    finally:
        alice.close()
        bob.close()


@pytest.mark.parametrize("limit", [0, -1, True, 1.5, "2"])
def test_event_replay_rejects_invalid_batch_sizes(tmp_path, limit):
    core = create_gateway_core(storage_root=tmp_path, secret_store=make_secret_store())
    account = core.open_gateway_account("alice")
    try:
        with pytest.raises(ValueError, match="positive integer"):
            account.events.read_after(None, limit=limit)
    finally:
        account.close()
