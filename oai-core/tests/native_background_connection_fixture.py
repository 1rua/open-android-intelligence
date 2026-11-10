"""复用真实 Hermes 桥、事件库和 ACK 的 Android 原生会话半开验收夹具。"""
from __future__ import annotations

import argparse
import asyncio
from contextlib import closing
import json
import os
from pathlib import Path
import signal
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "integrations/hermes-session-adapter/src"))

from aiohttp import web
from background_connection_fixture import BackgroundConnectionFixture, HalfOpenProxy, ACCOUNT, FIXTURE
from communication_process_fixture import TOKEN, ControlledExecution, HermesSessionAdapter, SessionBackend
from oai_core.adapters.hermes import HermesRemoteSessionAdapter
from oai_core.server import create_app, create_core
from oai_core.sessions import AgentRegistry
from oai_hermes_adapter.bridge import create_hermes_bridge_app

OWNER = "native-connection-e2e"
NATIVE = "/open-android-intelligence/v2/agents/hermes/sessions/"


class NativeFixture(BackgroundConnectionFixture):
    async def prepare_host(self, contract):
        settings = {"storageRoot": os.path.relpath(self.directory / "host-data"),
                    "masterKeyFile": os.path.relpath(self.directory / "host-master"),
                    "contractRoot": os.path.relpath(contract.resolve())}
        self.host_core = create_core(settings)
        with closing(self.host_core.open_gateway_account(OWNER)) as account:
            self.conversation = account.conversations.create("验收会话", OWNER, "seed")["conversationId"]
            account.agent_sessions.record(self.conversation, "conversation-create")
            account.agent_sessions.attach(self.conversation, "native-test-session", "native-test-key")
        self.backend = SessionBackend(self.host_core.storage_root, [OWNER], secret_store=self.host_core.secret_store)
        self.backend.acquire_accounts()
        self.backend.history_reader = lambda owner, conversation: []
        self.runtime = ControlledExecution(self.backend, {OWNER: self.conversation})
        self.bridge = web.AppRunner(create_hermes_bridge_app(HermesSessionAdapter(self.backend, self.runtime), TOKEN, [OWNER]), access_log=None)
        await self.bridge.setup()
        site = web.TCPSite(self.bridge, "127.0.0.1", 0)
        await site.start()
        self.host_url = "http://127.0.0.1:" + str(site._server.sockets[0].getsockname()[1])
        self.native_reads = 0
        self.native_acks = 0
        self.prompt_posts = 0
        self.platform_opens = 0
        self.half_open = False
        self.message_ids = []

    async def start_native_core(self):
        remote = HermesRemoteSessionAdapter(self.host_url, TOKEN, {ACCOUNT: OWNER})
        registry = AgentRegistry([remote])
        assert (await registry.discover())[0]["online"], "真实Hermes桥未就绪，不能伪造在线"

        @web.middleware
        async def observe(request, handler):
            native_connection = None
            if request.path.startswith(NATIVE):
                if request.path.endswith("/events"):
                    self.native_reads += 1
                    native_connection = self.proxy.connections.get(request.transport.get_extra_info("peername")[1])
                    if native_connection is not None:
                        native_connection.event_stream = True
                elif request.path.endswith("/events/ack"):
                    self.native_acks += 1
                elif request.path.endswith("/messages") and request.method == "POST":
                    self.prompt_posts += 1
            elif request.path in {"/open-android-intelligence/v2/events", "/open-android-intelligence/v2/events/ws"}:
                self.platform_opens += 1
            try:
                return await handler(request)
            finally:
                if native_connection is not None:
                    native_connection.event_stream = False

        app = create_app(self.settings, registry=registry, core=self.core)
        app.middlewares.insert(0, observe)
        self.runner = web.AppRunner(app, access_log=None, shutdown_timeout=1)
        await self.runner.setup()
        site = web.TCPSite(self.runner, "127.0.0.1", 0)
        await site.start()
        self.port = site._server.sockets[0].getsockname()[1]

    def append_native(self, identity):
        with closing(self.backend.open_gateway_account(OWNER)) as account:
            account.events.append("conversation.message.completed", identity, {
                "conversationId": self.conversation, "messageId": identity, "sender": "assistant",
                "text": identity, "parts": [{"type": "text", "text": identity}],
                "timestamp": (len(self.message_ids) + 1) * 1000, "revision": 1})
        self.message_ids.append(identity)

    def native_status(self):
        return {"fixture": FIXTURE, "nativeReads": self.native_reads, "nativeAcks": self.native_acks,
                "promptPosts": self.prompt_posts, "platformOpens": self.platform_opens,
                "messageCount": len(self.message_ids), **self.proxy.status()}

    async def native_control(self, request):
        operation = request.match_info["operation"]
        if operation == "status":
            return web.json_response(self.native_status())
        if request.method != "POST":
            raise web.HTTPMethodNotAllowed(request.method, ["POST"])
        if operation == "prepare" and not self.message_ids:
            self.append_native("native_before")
        elif operation == "half_open" and self.message_ids == ["native_before"]:
            frozen = self.proxy.freeze_existing_events()
            self.half_open = True
            self.append_native("native_during")
            return web.json_response({**self.native_status(), **frozen})
        elif operation == "finish" and self.half_open:
            self.append_native("native_after")
        elif operation == "stop":
            asyncio.get_running_loop().call_later(0.1, self.stopped.set)
        else:
            raise web.HTTPConflict(text="原生测试操作顺序无效")
        return web.json_response(self.native_status())

    async def close(self):
        await super().close()
        await self.runtime.close()
        await self.bridge.cleanup()
        self.backend.close()


async def serve(directory, contract):
    fixture = NativeFixture(directory, contract)
    await fixture.prepare_host(contract)
    fixture.proxy = HalfOpenProxy(0)
    await fixture.start_native_core()
    fixture.proxy.upstream_port = fixture.port
    await fixture.proxy.start()
    control = web.Application()
    control.router.add_route("*", "/{operation}", fixture.native_control)
    runner = web.AppRunner(control, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    control_port = site._server.sockets[0].getsockname()[1]
    ready = {"fixture": FIXTURE, "corePort": fixture.proxy.port, "controlPort": control_port}
    (fixture.directory / "ready.json").write_text(json.dumps(ready))
    print(json.dumps(ready), flush=True)
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(signum, fixture.stopped.set)
    try:
        await fixture.stopped.wait()
    finally:
        await fixture.close()
        await runner.cleanup()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, required=True)
    args = parser.parse_args()
    asyncio.run(serve(args.state_dir, Path(__file__).resolve().parents[2] / "gateway-contract"))
