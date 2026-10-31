"""Multiple Agent aliases cannot weaken one native host's account isolation."""
import pytest

from oai_core.adapters.hermes import HermesRemoteSessionAdapter
from oai_core.sessions import AgentRegistry


def adapter(name, accounts, url="http://127.0.0.1:8792", token="a" * 32):
    return HermesRemoteSessionAdapter(url, token, accounts, agent_id=name)


@pytest.mark.parametrize("first,second", [
    ("http://127.0.0.1:8792", "http://localhost:8792/"),
    ("http://localhost:8792", "http://[::1]:8792"),
    ("https://example.com", "https://EXAMPLE.COM:443/"),
    ("https://example.com", "https://example.com."),
    ("https://bücher.example", "https://xn--bcher-kva.example:443"),
    ("https://faß.example", "https://xn--fa-hia.example"),
    ("https://[::1]:8792", "https://[0:0:0:0:0:0:0:1]:8792"),
])
def test_aliases_cannot_map_different_core_accounts_to_one_native_account(first, second):
    registry = AgentRegistry([adapter("first", {"alice": "native"}, first)])
    try:
        with pytest.raises(ValueError, match="one-to-one across"):
            registry.register(adapter("second", {"bob": "native"}, second, token="b" * 32))
    finally:
        registry.operations.close()


def test_aliases_cannot_map_one_core_account_to_different_native_accounts():
    registry = AgentRegistry([adapter("first", {"alice": "native-a"})])
    try:
        with pytest.raises(ValueError, match="one-to-one across"):
            registry.register(adapter("second", {"alice": "native-b"}))
    finally:
        registry.operations.close()


@pytest.mark.asyncio
async def test_same_owner_alias_and_distinct_host_accounts_remain_available():
    registry = AgentRegistry([adapter("first", {"alice": "native-a"}),
                              adapter("alias", {"alice": "native-a", "bob": "native-b"}),
                              adapter("another_host", {"charlie": "native-a"}, "http://127.0.0.1:8793")])
    assert len(registry._adapters) == 3
    await registry.close()
