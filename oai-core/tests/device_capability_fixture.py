"""Test-only standalone Core HTTP peer for Android capability integration.

The first stdout line is the loopback base URL. Subsequent JSON lines are a
local test-control protocol, never a public admin route. Android must perform
real login, signed capability publication, Claim and Result. The Agent side
obtains a real device-bound Core token and uses the public request/read/ACK API.
Credentials and server diagnostics are never written to the control channel.
"""
from __future__ import annotations

import argparse
import asyncio
from contextlib import suppress
import json
import logging
from pathlib import Path
import secrets
import shutil
import signal
import sys
import tempfile
from typing import Any

# Always exercise this checkout's independent Core, never an installed legacy
# Hermes Gateway or a package selected by HERMES_PLUGIN_ROOT.
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

import aiohttp
from aiohttp import web

from oai_core.gateway.core import _jcs
from oai_core.server import DEVICE_API, create_app, create_core
from oai_core.sessions import AgentRegistry


ACCOUNT = "alice"
PASSWORD = "device-fixture-only"
TERMINAL = {"succeeded", "failed", "denied", "cancelled", "expired", "outcome_unknown"}


class FixtureFailure(Exception):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class DeviceFixture:
    def __init__(self, core: Any, client: aiohttp.ClientSession, base_url: str):
        self.core, self.client, self.base_url = core, client, base_url
        self.request_id: str | None = None
        self.task: asyncio.Task[dict[str, Any]] | None = None

    async def request(self, method: str, path: str, *, headers=None, body=None) -> dict[str, Any]:
        async with self.client.request(method, self.base_url + path, headers=headers, json=body) as response:
            value = await response.json()
            if response.status != 200:
                raise FixtureFailure(value.get("error", {}).get("code", "FIXTURE_HTTP_FAILURE"))
            if not isinstance(value, dict):
                raise FixtureFailure("FIXTURE_RESPONSE_INVALID")
            return value

    async def start(self, command: dict[str, Any]) -> dict[str, Any]:
        if self.task is not None and not self.task.done():
            raise FixtureFailure("FIXTURE_REQUEST_ACTIVE")
        capability_id = command.get("capabilityId")
        parameters = command.get("parameters", {})
        if not isinstance(capability_id, str) or not isinstance(parameters, dict):
            raise FixtureFailure("FIXTURE_COMMAND_INVALID")
        account = self.core.open_gateway_account(ACCOUNT)
        try:
            devices = account.store.database.execute("SELECT device_id FROM device_keys").fetchall()
            if len(devices) != 1:
                raise FixtureFailure("PAIRING_REQUIRED")
            device_id = devices[0][0]
        finally:
            account.close()
        # Refresh per invocation because Android state publication advances the
        # revision and correctly invalidates the previous scoped credential.
        credential = await self.request("POST", DEVICE_API + "/tokens", body={
            "username": ACCOUNT, "password": PASSWORD, "deviceId": device_id,
        })
        headers = {"Authorization": "Bearer " + credential["token"]}
        catalog = await self.request("GET", DEVICE_API + "/capabilities", headers=headers)
        capability = next((item for item in catalog["capabilities"]
                           if item["capabilityId"] == capability_id and item["status"] == "READY"), None)
        if capability is None:
            raise FixtureFailure("CAPABILITY_DENIED")
        receipt = await self.request("POST", DEVICE_API + "/device-requests", headers={
            **headers, "Idempotency-Key": "fixture_" + secrets.token_hex(12),
        }, body={"capabilityId": capability_id, "capabilityVersion": capability["capabilityVersion"],
                 "parameters": parameters})
        self.request_id = receipt["requestId"]
        self.task = asyncio.create_task(self.wait_for_result(self.request_id, headers))
        return {"started": True}

    async def wait_for_result(self, request_id: str, headers: dict[str, str]) -> dict[str, Any]:
        async with asyncio.timeout(10):
            while True:
                record = await self.request("GET", DEVICE_API + "/device-requests/" + request_id, headers=headers)
                if record["state"] in TERMINAL:
                    result = record.get("result")
                    if not isinstance(result, dict):
                        raise FixtureFailure("DEVICE_RESULT_UNAVAILABLE")
                    acknowledged = await self.request("POST", DEVICE_API + f"/device-requests/{request_id}/ack", headers=headers)
                    if acknowledged.get("acknowledged") is not True:
                        raise FixtureFailure("DEVICE_RESULT_ACK_FAILED")
                    return result
                await asyncio.sleep(0.02)

    async def control(self, command: dict[str, Any]) -> dict[str, Any]:
        operation = command.get("operation")
        if operation == "legacy_policy":
            capability_id, enabled = command.get("capabilityId"), command.get("enabled")
            if not isinstance(capability_id, str) or type(enabled) is not bool:
                raise FixtureFailure("FIXTURE_COMMAND_INVALID")
            # Explicit fixture input representing existing historical metadata.
            # Do not re-enable a retired policy writer to create this state.
            account = self.core.open_gateway_account(ACCOUNT)
            try:
                with account.store.transaction():
                    account.store.database.execute(
                        "INSERT OR REPLACE INTO account_metadata(key,value) VALUES (?,?)",
                        ("device-capability-policy:v1", _jcs({"revision": 1, "enabled": {capability_id: enabled}})),
                    )
            finally:
                account.close()
            return {"ok": True}
        if operation == "start":
            return await self.start(command)
        if operation == "pending":
            return {"requestId": self.request_id}
        if operation == "result":
            if self.task is None:
                raise FixtureFailure("FIXTURE_REQUEST_REQUIRED")
            return await asyncio.wait_for(asyncio.shield(self.task), timeout=11)
        if operation == "stop":
            return {"stopped": True}
        raise FixtureFailure("FIXTURE_COMMAND_INVALID")

    async def close(self) -> None:
        if self.task is not None:
            self.task.cancel()
            with suppress(asyncio.CancelledError, FixtureFailure, TimeoutError):
                await self.task


async def serve(contract_root: Path) -> None:
    directory = Path(tempfile.mkdtemp(prefix="oai-core-device-e2e-"))
    runner = None
    stdin_transport = None
    fixture = None
    try:
        settings = {"storageRoot": str(directory / "accounts"),
                    "masterKeyFile": str(directory / "secrets" / "master"),
                    "contractRoot": str(contract_root.resolve())}
        core = create_core(settings)
        account = core.open_gateway_account(ACCOUNT)
        try:
            account.credentials.create_password(PASSWORD)
        finally:
            account.close()
        runner = web.AppRunner(create_app(settings, registry=AgentRegistry(), core=core), access_log=None)
        await runner.setup()
        site = web.TCPSite(runner, "127.0.0.1", 0)
        await site.start()
        port = site._server.sockets[0].getsockname()[1]
        base_url = f"http://127.0.0.1:{port}"
        loop = asyncio.get_running_loop()
        current = asyncio.current_task()
        with suppress(NotImplementedError):
            loop.add_signal_handler(signal.SIGTERM, current.cancel)
        reader = asyncio.StreamReader(limit=131072)
        stdin_transport, _ = await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=5)) as client:
            fixture = DeviceFixture(core, client, base_url)
            print(base_url, flush=True)
            while line := await reader.readline():
                command = None
                try:
                    command = json.loads(line)
                    if not isinstance(command, dict):
                        raise FixtureFailure("FIXTURE_COMMAND_INVALID")
                    result = await fixture.control(command)
                except FixtureFailure as error:
                    result = {"ok": False, "error": {"code": error.code}}
                except TimeoutError:
                    result = {"ok": False, "error": {"code": "DEVICE_RESULT_TIMEOUT"}}
                except (ValueError, TypeError, aiohttp.ClientError):
                    result = {"ok": False, "error": {"code": "FIXTURE_CONTROL_FAILED"}}
                print(json.dumps(result, separators=(",", ":")), flush=True)
                if isinstance(command, dict) and command.get("operation") == "stop":
                    break
            await fixture.close()
            fixture = None
    finally:
        if fixture is not None:
            await fixture.close()
        if stdin_transport is not None:
            stdin_transport.close()
        if runner is not None:
            await runner.cleanup()
        # Preserve test artifacts for inspection instead of permanently deleting
        # the temporary account/key files during teardown.
        trash = Path(tempfile.gettempdir()) / "open-android-intelligence-trash"
        trash.mkdir(mode=0o700, parents=True, exist_ok=True)
        shutil.move(str(directory), str(trash / directory.name))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract-root", type=Path, required=True)
    args = parser.parse_args()
    logging.basicConfig(stream=sys.stderr, level=logging.WARNING)
    with suppress(KeyboardInterrupt, asyncio.CancelledError):
        asyncio.run(serve(args.contract_root))


if __name__ == "__main__":
    main()
