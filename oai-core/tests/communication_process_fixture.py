"""Isolated OS processes for the real Core and controlled Hermes HTTP bridge.

The pipe is a test-only lifecycle channel. All product traffic uses real signed
HTTP over 127.0.0.1; account data and the observation journal live in tmp_path.
There is no model, shell executor, production config, or device connection.
"""
from __future__ import annotations

import asyncio
from contextlib import closing, suppress
import json
import multiprocessing
import os
from pathlib import Path
import socket
import traceback
from urllib.parse import urlsplit

from aiohttp import web

from oai_core.adapters.hermes import HermesRemoteSessionAdapter
from oai_core.server import create_app, create_core
from oai_core.sessions import AgentRegistry
from oai_hermes_adapter.bridge import create_hermes_bridge_app
from test_interaction_pipeline import (
    CONTRACT, TOKEN, ControlledExecution, HermesSessionAdapter, SessionBackend,
)


OWNER = "native-alice"
PROCESS_TIMEOUT = 15
BRIDGE_PREFIX = "/oai/session-adapter/v1"


def _record(path, event, **fields):
    with path.open("a", encoding="utf-8") as journal:
        journal.write(json.dumps({"event": event, **fields}, ensure_ascii=False) + "\n")


async def _host(root, conversation):
    settings = {"storageRoot": "host-data", "masterKeyFile": "host-master",
                "contractRoot": os.path.relpath(CONTRACT, root)}
    seed = create_core(settings)
    if conversation is None:
        with closing(seed.open_gateway_account(OWNER)) as account:
            conversation = account.conversations.create("existing", OWNER, "seed")["conversationId"]
            account.agent_sessions.record(conversation, "conversation-create")
            account.agent_sessions.attach(conversation, "native-session", "native-key-test-only")
    backend = SessionBackend(seed.storage_root, [OWNER], secret_store=seed.secret_store)
    backend.acquire_accounts()
    backend.history_reader = lambda owner, session: []
    journal = root / "observations.jsonl"

    class ObservedExecution(ControlledExecution):
        async def _notify_agent_inbound(self, path, body, response, owner):
            accepted = response["data"]
            identity = {"generationId": accepted["generationId"],
                        "clientMessageId": json.loads(body)["clientMessageId"]}
            _record(journal, "task.started", **identity)
            try:
                await super()._notify_agent_inbound(path, body, response, owner)
            except asyncio.CancelledError:
                _record(journal, "task.cancelled", **identity)
                raise
            else:
                _record(journal, "task.finished", **identity)

    runtime = ObservedExecution(backend, {OWNER: conversation})
    detached = asyncio.Event()
    unregister = backend.unregister_event_sink
    loop = asyncio.get_running_loop()

    def observe_detach(sink):
        unregister(sink)
        loop.call_soon_threadsafe(detached.set)

    backend.unregister_event_sink = observe_detach

    @web.middleware
    async def observe_post(request, handler):
        if request.method == "POST" and request.path == f"{BRIDGE_PREFIX}/sessions/{conversation}/messages":
            body = await request.json()
            _record(journal, "http.message.post", clientMessageId=body.get("clientMessageId"))
        return await handler(request)

    app = create_hermes_bridge_app(
        HermesSessionAdapter(backend, runtime), TOKEN, [OWNER])
    app.middlewares.insert(0, observe_post)

    async def control(command):
        operation = command["operation"]
        if operation == "emit":
            generation = command["generationId"]
            task = runtime.turns[(OWNER, conversation, generation)]
            assert not task.done(), "accepted host task stopped before its explicit release"
            runtime.emit_text(OWNER, conversation, generation, command["text"])
        elif operation == "detached":
            await asyncio.wait_for(detached.wait(), 5)
        elif operation == "release":
            runtime.observer_release.set()
            await asyncio.wait_for(runtime.host_finished.wait(), 5)
        elif operation != "status":
            raise AssertionError(f"unknown host control operation: {operation}")
        with closing(backend.open_gateway_account(OWNER)) as account:
            users = account.store.database.execute(
                "SELECT COUNT(*) FROM messages WHERE conversation_id=? AND sender='user'", (conversation,)
            ).fetchone()[0]
        return {"userCount": users, "turnCount": len(runtime.turns),
                "liveTurns": sum(not task.done() for task in runtime.turns.values()),
                "interrupted": len(runtime.interrupted), "finished": runtime.host_finished.is_set()}

    async def cleanup():
        await runtime.close()
        backend.close()

    return app, {"conversationId": conversation}, control, cleanup


async def _core(root, host_url):
    parsed = urlsplit(host_url)
    assert parsed.scheme == "http" and parsed.hostname == "127.0.0.1" and parsed.port
    settings = {"storageRoot": "core-data", "masterKeyFile": "core-master",
                "contractRoot": os.path.relpath(CONTRACT, root)}
    core = create_core(settings)
    if not core.account_exists("alice"):
        with closing(core.open_gateway_account("alice")) as account:
            account.credentials.create_password("test-only-password")
    remote = HermesRemoteSessionAdapter(host_url, TOKEN, {"alice": OWNER})
    registry = AgentRegistry([remote], operations_path=Path(settings["storageRoot"]) / "native-operations.sqlite")
    assert (await registry.discover())[0]["online"], "fixture Adapter was not ready"
    return create_app(settings, registry=registry, core=core), {
        "schemaHash": core.contracts.core_schema_hash,
    }, None, None


async def _serve(role, directory, connection, host_url, port, conversation):
    root = Path(directory).resolve()
    os.chdir(root)
    # The controlled host does not import the Hermes model runtime. These paths
    # additionally isolate any optional Hermes helper imported by the bridge.
    os.environ["HERMES_HOME"] = str(root / "hermes-home")
    os.environ["HERMES_TEST_ISOLATION"] = str(root / "hermes-home")
    app, metadata, control, cleanup = (await _host(root, conversation) if role == "adapter"
                                       else await _core(root, host_url))
    runner = web.AppRunner(app, access_log=None, shutdown_timeout=1)
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", port))
        await runner.setup()
        await web.SockSite(runner, listener).start()
        connection.send({"kind": "ready", "pid": os.getpid(),
                         "url": f"http://127.0.0.1:{listener.getsockname()[1]}", **metadata})
        while True:
            command = await asyncio.to_thread(connection.recv)
            if command["operation"] == "stop":
                connection.send({"kind": "stopped"})
                break
            assert control is not None, "Core has no synthetic execution control API"
            connection.send({"kind": "result", **await control(command)})
    finally:
        # End the local host task before closing its account connection.
        if cleanup is not None:
            await cleanup()
        await runner.cleanup()
        listener.close()


def _entry(role, directory, connection, host_url, port, conversation):
    try:
        asyncio.run(_serve(role, directory, connection, host_url, port, conversation))
    except BaseException:
        with suppress(BrokenPipeError, EOFError, OSError):
            connection.send({"kind": "error", "traceback": traceback.format_exc()})
        raise
    finally:
        connection.close()


class CommunicationProcess:
    """One spawned interpreter, a bounded readiness handshake, and owned cleanup."""

    def __init__(self, role, directory, *, host_url=None, port=0, conversation=None):
        assert role in {"adapter", "core"}
        self.directory = directory.resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        context = multiprocessing.get_context("spawn")
        self.connection, child = context.Pipe()
        self._child_connection = child
        self.process = context.Process(target=_entry,
            args=(role, str(self.directory), child, host_url, port, conversation),
            name=f"communication-test-{role}", daemon=True)
        self.ready = None
        self._closed = False

    async def _receive(self, timeout=PROCESS_TIMEOUT):
        def read():
            if not self.connection.poll(timeout):
                raise AssertionError(f"{self.process.name} timed out; exitcode={self.process.exitcode}")
            try:
                message = self.connection.recv()
            except EOFError as exc:
                raise AssertionError(f"{self.process.name} exited before replying; exitcode={self.process.exitcode}") from exc
            assert message["kind"] != "error", message.get("traceback")
            return message
        return await asyncio.to_thread(read)

    async def start(self):
        self.process.start()
        self._child_connection.close()
        try:
            self.ready = await self._receive()
            assert self.ready["kind"] == "ready"
            assert self.ready["pid"] == self.process.pid != os.getpid()
            assert urlsplit(self.ready["url"]).hostname == "127.0.0.1"
        except BaseException:
            await self.close(crash=True)
            raise
        return self

    @property
    def url(self):
        return self.ready["url"]

    @property
    def port(self):
        return urlsplit(self.url).port

    async def command(self, operation, **fields):
        assert self.process.is_alive(), f"{self.process.name} unexpectedly exited: {self.process.exitcode}"
        self.connection.send({"operation": operation, **fields})
        result = await self._receive()
        assert result.pop("kind") == "result"
        return result

    def observations(self):
        path = self.directory / "observations.jsonl"
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()] if path.exists() else []

    async def close(self, *, crash=False):
        if self._closed:
            return
        if self.process.pid is None:
            self._closed = True
            self.connection.close()
            self._child_connection.close()
            return
        try:
            if self.process.is_alive():
                if crash:
                    self.process.kill()
                else:
                    self.connection.send({"operation": "stop"})
                    assert (await self._receive())["kind"] == "stopped"
            await asyncio.to_thread(self.process.join, 5)
            if self.process.is_alive():
                self.process.kill()
                await asyncio.to_thread(self.process.join, 5)
                raise AssertionError(f"{self.process.name} did not shut down within its deadline")
            if not crash:
                assert self.process.exitcode == 0, f"{self.process.name} exitcode={self.process.exitcode}"
        finally:
            if self.process.is_alive():
                self.process.kill()
                await asyncio.to_thread(self.process.join, 5)
            self.connection.close()
            self._closed = True
