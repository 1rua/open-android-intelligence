"""The Android-owned authorization model is an explicit wire agreement."""
from __future__ import annotations

import json
import secrets
from pathlib import Path

import pytest
from aiohttp.test_utils import TestClient, TestServer

from oai_core.server import create_app, create_core
from oai_core.sessions import AgentRegistry


HEADER = "X-Open-Android-Intelligence-Device-Authorization"
MODEL = "android-v1"
CONTRACT = Path(__file__).resolve().parents[2] / "gateway-contract"


@pytest.fixture
async def negotiation_server(tmp_path):
    config = {"storageRoot": str(tmp_path / "accounts"),
              "masterKeyFile": str(tmp_path / "secrets" / "master"), "contractRoot": str(CONTRACT)}
    core = create_core(config)
    async with TestClient(TestServer(create_app(config, registry=AgentRegistry(), core=core))) as client:
        yield client, core


async def negotiate(fixture, headers):
    client, core = fixture
    body = json.loads((CONTRACT / "vectors" / "protocol-negotiation.json").read_text())["cases"][0]["input"]["value"]
    body["negotiationId"] = "neg_" + secrets.token_hex(8)
    body["schemaHashes"]["core"] = core.contracts.core_schema_hash
    return await client.post("/open-android-intelligence/v2/negotiate", json=body, headers=headers)


@pytest.mark.asyncio
async def test_android_authority_is_acknowledged_only_when_requested(negotiation_server):
    response = await negotiate(negotiation_server, [(HEADER, MODEL)])
    assert response.status == 200, await response.text()
    assert response.headers.getall(HEADER, []) == [MODEL]
    # The optional extension does not alter the closed JSON contract or its hash.
    assert set((await response.json())["data"]) == {"protocol", "features", "limits", "gatewayIdentity"}

    legacy = await negotiate(negotiation_server, [])
    assert legacy.status == 200, await legacy.text()
    assert HEADER not in legacy.headers


@pytest.mark.asyncio
@pytest.mark.parametrize("values", [[MODEL, MODEL], ["android-v2"], [MODEL + ", " + MODEL]])
async def test_ambiguous_or_unknown_authorization_model_is_refused(negotiation_server, values):
    response = await negotiate(negotiation_server, [(HEADER, value) for value in values])
    assert response.status == 400, await response.text()
    assert (await response.json())["error"]["code"] == "SCHEMA_INVALID"
    assert HEADER not in response.headers
