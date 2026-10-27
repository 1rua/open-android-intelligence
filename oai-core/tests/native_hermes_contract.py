"""Executed in an isolated process by test_hermes_native.py."""
import asyncio
import hashlib
import io
import zipfile
from contextlib import closing
import os
from pathlib import Path
import socket
import sys

sys.path.insert(0, str(Path(os.environ["HERMES_SDK_ROOT"]).resolve()))

from aiohttp import ClientSession
from gateway.config import GatewayConfig, PlatformConfig
from gateway.platforms.base import ExecApprovalPrompt
from gateway.platform_registry import platform_registry
from gateway.session import SessionStore
from hermes_cli.plugins import PluginContext, PluginManager, parse_manifest_file
from oai_hermes_adapter.runtime import NATIVE_HERMES_AVAILABLE
from oai_hermes_adapter.platform_identity import GATEWAY_PLATFORM_IDS

from oai_hermes_adapter.sessions import HermesSessionAdapter
from oai_core.auth import Principal
from oai_hermes_adapter.bridge import register
from oai_core.server import create_app, create_core
from oai_core.sessions import AdapterError

repository, temporary = Path(sys.argv[1]), Path(sys.argv[2])
assert NATIVE_HERMES_AVAILABLE
settings = {"storageRoot": str(temporary / "accounts"), "masterKeyFile": str(temporary / "master-key"),
            "contractRoot": str(repository / "gateway-contract")}
core = create_core(settings)

# The Core listener is independent of Hermes' platform registry even when the
# native SDK happens to be installed in the same Python environment.
async def standalone_core():
    app = create_app(settings)
    app.freeze()
    await app.startup()
    await app.shutdown()
    await app.cleanup()

asyncio.run(standalone_core())

for owner in ("alice", "bob"):
    with closing(core.open_gateway_account(owner)) as account:
        account.credentials.create_password("synthetic-native-test-password")
os.environ.update({"OAI_HERMES_GATEWAY_ROOT": settings["storageRoot"],
                   "OAI_HERMES_ACCOUNTS": '["alice","bob"]',
                   "OAI_HERMES_SESSION_TOKEN": "synthetic-native-session-bridge-token-1234567890",
                   "OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE": settings["masterKeyFile"]})
with socket.socket() as probe:
    probe.bind(("127.0.0.1", 0))
    os.environ["OAI_HERMES_SESSION_PORT"] = str(probe.getsockname()[1])

plugin = repository / "integrations" / "hermes-session-adapter"
manifest = parse_manifest_file(plugin / "plugin.yaml", plugin, "project", "")
assert manifest is not None and manifest.provides_tools == []
manager = PluginManager()
register(PluginContext(manifest, manager))
assert set(manager._plugin_platform_names) == set(GATEWAY_PLATFORM_IDS)
assert not manager._plugin_tool_names
entries = [platform_registry.get(name) for name in GATEWAY_PLATFORM_IDS]
assert all(entry is not None and entry.check_fn() for entry in entries)
runtime = entries[0].adapter_factory(PlatformConfig(enabled=True, extra={}))
assert all(entry.adapter_factory(PlatformConfig(enabled=True, extra={})) is runtime for entry in entries)
store = SessionStore(Path(os.environ["HERMES_HOME"]) / "sessions", GatewayConfig())
runtime.set_session_store(store)

# Persist a legacy platform binding before the new adapter acquires the old
# account directory. No account database or native session ID is replaced.
with closing(core.open_gateway_account("alice")) as account:
    legacy = account.conversations.create("legacy-before-migration", "legacy title", "legacy-before-migration")
    legacy_id = legacy["conversationId"]
    entry = store.get_or_create_session(runtime._agent_source(legacy_id, "alice", "open_android"), False)
    account.agent_sessions.record(legacy_id, "conversation-create")
    account.agent_sessions.attach(legacy_id, entry.session_id, entry.session_key)
legacy_database = store._db_for_key(entry.session_key)
legacy_database.append_message(entry.session_id, "assistant", "persisted before optional adapter", timestamp=1)
legacy_session_id = entry.session_id


async def scenario():
    block = asyncio.Event()
    entered = asyncio.Event()
    approval_answered = asyncio.Event()
    approvals = []
    approval_continuations = []
    received_media = []
    import oai_hermes_adapter.approvals as gateway_core_module
    original_resolver = gateway_core_module.resolve_host_approval
    loop = asyncio.get_running_loop()
    def resolve_approval(session, choice, request_id=None):
        approvals.append((session, choice, request_id))
        loop.call_soon_threadsafe(approval_answered.set)
        return 1
    gateway_core_module.resolve_host_approval = resolve_approval
    original_hook = runtime._run_processing_hook
    async def isolated_hook(name, *args, **kwargs):
        # Lifecycle hooks can be supervised in a child Task by the host. The
        # adapter must still capture the native processing Task, not this hook.
        if name == "on_processing_start":
            return await asyncio.create_task(original_hook(name, *args, **kwargs))
        return await original_hook(name, *args, **kwargs)
    runtime._run_processing_hook = isolated_hook

    class Handler:
        async def receive(self, event):
            if event.media_urls:
                received_media.append((event.message_type.value, list(event.media_types),
                    [Path(path).read_bytes() for path in event.media_urls]))
            if event.text == "block until cancelled":
                entered.set()
                await block.wait()
            if event.text in {"interactive approval", "approval until cancelled"}:
                approval_answered.clear()
                request_id = "synthetic-approval" if event.text == "interactive approval" else "synthetic-cancel-approval"
                result = await runtime._send_exec_approval_prompt(ExecApprovalPrompt(
                    chat_id=event.source.chat_id, session_key=runtime._event_session_key(event),
                    text="Synthetic approval", actions=[("Once", "once", "primary"), ("Deny", "deny", "danger")],
                    command="synthetic-sensitive-action", description="test", smart_denied=False,
                    metadata={"account_id": event.source.user_id, "request_id": request_id}))
                assert result.success
                await approval_answered.wait()
                approval_continuations.append(event.text)
            return "native synthetic reply"

        async def _interrupt_and_clear_session(self, session_key, source, **kwargs):
            await runtime.cancel_session_processing(session_key)

    runtime.set_message_handler(Handler().receive)
    assert await runtime.connect()
    adapter = HermesSessionAdapter(runtime.services.core, runtime)
    from hermes_cli import plugins as host_plugins
    host_plugins._ensure_plugins_discovered = lambda: manager
    PluginContext(manifest, manager).register_command(
        "mobile-test-command", lambda text: text, "Native plugin command", args_hint="<value>")
    native_catalog = {item["invocation"]: item for item in adapter.commands("zh-CN")}
    assert {"/help", "/model", "/mobile-test-command"} <= set(native_catalog)
    assert native_catalog["/mobile-test-command"]["argumentHint"] == "<value>"
    assert native_catalog["/mobile-test-command"]["acceptsArguments"] is True
    alice = Principal("alice", device_id="core-phone", pairing_generation=7, grant_revision=9)
    bob = Principal("bob", device_id="other-phone", pairing_generation=3, grant_revision=2)
    assert await adapter.status()
    assert not hasattr(runtime.services.core, "device_requests")
    with closing(runtime.services.core.open_gateway_account("alice")) as session_account:
        assert not hasattr(session_account, "device_requests")
        assert not hasattr(session_account, "sessions")
    before = await adapter.list_sessions(alice)
    assert before[0]["id"] == legacy_id
    assert any(row["text"] == "persisted before optional adapter" for row in await adapter.history(alice, legacy_id))
    with closing(runtime.services.core.open_gateway_account("alice")) as account:
        assert account.agent_sessions.lookup(legacy_id)["agentSessionId"] == legacy_session_id
    try:
        await adapter.history(bob, legacy_id)
    except AdapterError as error:
        assert error.status == 404
    else:
        raise AssertionError("another account read legacy native history")
    # Finish the migrated legacy thread without changing its original identity.
    legacy_events = [event async for event in adapter.send(alice, legacy_id, "native legacy prompt")]
    assert legacy_events[-1]["type"] == "done", legacy_events
    await asyncio.gather(*tuple(runtime._background_tasks))
    await adapter.manage(alice, legacy_id, "delete")
    assert legacy_database.get_session(legacy_session_id) is None
    try:
        async with ClientSession() as client:
            url = "http://127.0.0.1:" + os.environ["OAI_HERMES_SESSION_PORT"]
            async with client.post(url + "/open-android-intelligence/v2/negotiate", json={},
                                   headers={"Authorization": "Bearer " + os.environ["OAI_HERMES_SESSION_TOKEN"], "X-OAI-Account": "alice"}) as response:
                assert response.status == 404, "session host must not expose a second device Gateway"
        a = await adapter.create(alice, "native Alice title")
        b = await adapter.create(bob, "native Bob title")
        with closing(runtime.services.core.open_gateway_account("alice")) as account:
            binding = account.agent_sessions.lookup(a["id"])
        database = store._db_for_key(binding["sessionKey"])
        assert database.get_session(binding["agentSessionId"]) is not None
        assert database.get_session_title(binding["agentSessionId"]) == "native Alice title"
        database.append_message(binding["agentSessionId"], "assistant", "native persisted history", timestamp=1)
        assert any(row["text"] == "native persisted history" for row in await adapter.history(alice, a["id"]))
        try:
            await adapter.history(bob, a["id"])
        except AdapterError as error:
            assert error.status == 404
        else:
            raise AssertionError("another account read private native history")
        await adapter.manage(alice, a["id"], "rename", "renamed in native DB")
        assert database.get_session_title(binding["agentSessionId"]) == "renamed in native DB"
        database.set_session_title(binding["agentSessionId"], "renamed in terminal")
        assert (await adapter.list_sessions(alice))[0]["title"] == "renamed in terminal"
        events = [event async for event in adapter.send(alice, a["id"], "synthetic prompt")]
        assert events[-1]["type"] == "done", events
        assert "native synthetic reply" == "".join(event.get("text", "") for event in events if event["type"] == "delta"), events
        # The real Hermes dispatch receives private verified files with their
        # original MIME type, including a pure image and a text/document turn.
        document = io.BytesIO()
        with zipfile.ZipFile(document, "w") as archive:
            archive.writestr("word/document.xml", "<document><p>native document</p></document>")
        fixtures = [("", "photo.png", "image/png", b"\x89PNG\r\n\x1a\nsynthetic-native-image", "photo"),
                    ("阅读这份文档", "report.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                     document.getvalue(), "document")]
        for index, (text, filename, media_type, content, message_type) in enumerate(fixtures):
            client_id = "android-attachment-" + str(index)
            with closing(runtime.services.core.open_gateway_account("alice")) as account:
                created = account.attachments.create(clientAttachmentId=client_id, filename=filename, mediaType=media_type,
                    sizeBytes=len(content), sha256=hashlib.sha256(content).hexdigest(), deviceId=alice.device_id,
                    pairingGeneration=alice.pairing_generation, correlationId=client_id)
                account.attachments.upload_content_stream(created["attachmentId"], (content,))
                account.attachments.commit(created["attachmentId"])
            media_events = [event async for event in adapter.send(alice, a["id"], text,
                attachment_ids=[created["attachmentId"]], client_message_id=client_id)]
            assert media_events[-1]["type"] == "done", media_events
            assert media_events[0]["clientMessageId"] == client_id
            assert received_media[-1] == (message_type, [media_type], [content])
            history = await adapter.history(alice, a["id"])
            user = next(row for row in history if row.get("clientMessageId") == client_id and row["role"] == "user")
            reply = next(row for row in history if row["id"] == media_events[-1]["messageId"])
            assert user["id"] == media_events[0]["userMessageId"]
            assert user["text"] == text
            assert user["attachments"][0]["filename"] == filename
            assert reply["clientMessageId"] == client_id
            assert reply["text"] == "native synthetic reply"
        interactive = adapter.send(alice, a["id"], "interactive approval")
        assert (await anext(interactive))["type"] == "generation"
        while True:
            approval_event = await asyncio.wait_for(anext(interactive), 5)
            if approval_event["type"] == "approval_requested":
                break
        assert approvals == [], "the native wait must remain pending until a phone decision"
        await adapter.decide_approval(alice, a["id"], approval_event["approval"]["approvalId"], "once")
        completed = [event async for event in interactive]
        assert completed[-1]["type"] == "done", completed
        assert any(event["type"] == "approval_resolved" for event in completed), completed
        assert approvals[-1][1:] == ("once", "synthetic-approval")
        await asyncio.gather(*tuple(runtime._background_tasks))
        await adapter.manage(alice, a["id"], "archive", True)
        assert database.get_session(binding["agentSessionId"])["archived"]
        await adapter.manage(alice, a["id"], "archive", False)
        assert not database.get_session(binding["agentSessionId"])["archived"]
        # Re-pairing in Core need not modify the independent legacy account DB.
        pending = adapter.send(alice, a["id"], "block until cancelled")
        generation = await anext(pending)
        assert generation["type"] == "generation"
        first = asyncio.create_task(anext(pending))
        await asyncio.wait_for(entered.wait(), 5)
        cancelled = await adapter.manage(alice, a["id"], "cancel", {"generationId": generation["generationId"]})
        assert cancelled["outcome"] == "cancelled", cancelled
        assert (await asyncio.wait_for(first, 5))["outcome"] == "cancelled"
        await pending.aclose()
        # Cancelling a waiting approval must invalidate the native task before
        # denying the wait, so it cannot resume another model/tool step.
        pending_approval = adapter.send(alice, a["id"], "approval until cancelled")
        approval_generation = await anext(pending_approval)
        while True:
            card_event = await asyncio.wait_for(anext(pending_approval), 5)
            if card_event["type"] == "approval_requested":
                break
        cancelled = await adapter.manage(alice, a["id"], "cancel", {"generationId": approval_generation["generationId"]})
        assert cancelled["outcome"] == "cancelled", cancelled
        assert "approval until cancelled" not in approval_continuations
        approval_tail = [event async for event in pending_approval]
        assert any(event["type"] == "approval_resolved" and event["approval"]["decision"] == "withdrawn"
                   for event in approval_tail), approval_tail
        assert approval_tail[-1]["outcome"] == "cancelled", approval_tail
        # Native compression publishes a continuation without rewriting the
        # independent Gateway account binding. Titles belong to its live tip.
        compressed_tip = "native-compression-tip"
        database.publish_compression_child(
            parent_session_id=binding["agentSessionId"], child_session_id=compressed_tip,
            source=database.get_session(binding["agentSessionId"])["source"],
            messages=[{"role": "user", "content": "synthetic compressed context"}],
            require_compression_lease=False)
        with closing(runtime.services.core.open_gateway_account("alice")) as account:
            assert account.agent_sessions.lookup(a["id"])["agentSessionId"] == binding["agentSessionId"]
        assert database.get_compression_chain(binding["agentSessionId"])[-1] == compressed_tip
        database.set_session_title(compressed_tip, "renamed on compressed native tip")
        assert (await adapter.list_sessions(alice))[0]["title"] == "renamed on compressed native tip"
        await adapter.manage(alice, a["id"], "rename", "renamed compressed tip from App")
        assert database.get_session_title(compressed_tip) == "renamed compressed tip from App"
        assert (await adapter.list_sessions(alice))[0]["title"] == "renamed compressed tip from App"
        await adapter.manage(alice, a["id"], "delete")
        assert database.get_session(binding["agentSessionId"]) is None
        assert database.get_session(compressed_tip) is None
        try:
            await adapter.history(alice, a["id"])
        except AdapterError as error:
            assert error.status == 404
        else:
            raise AssertionError("deleted native session remained readable")
        assert await adapter.list_sessions(alice) == []
        assert (await adapter.list_sessions(bob))[0]["id"] == b["id"]
    finally:
        block.set()
        await runtime.disconnect()
        gateway_core_module.resolve_host_approval = original_resolver


asyncio.run(scenario())
print("native Hermes contracts passed")
