"""Route contracts and security boundaries independent of host model execution."""
import unittest
import uuid
from pathlib import Path
import tempfile

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer
from open_android_intelligence_gateway.core import GatewayError

from oai_core.sessions import AgentRegistry, Principal, create_agent_routes
from oai_core.operations import NativeOperations, OperationConflict

BASE = "/open-android-intelligence/v2/agents"


class NativeHost:
    def __init__(self, agent_id="codex", scope="deployment", readonly=False):
        self.agent_id, self.scope, self.readonly = agent_id, scope, readonly
        self.native = [{"id": "terminal-1", "title": "Created in terminal"}]
        self.calls = []
        self.online = True
        self.fail_stream = False

    def descriptor(self):
        return {"id": self.agent_id, "name": self.agent_id, "kind": self.agent_id,
                "sessionScope": self.scope,
                "capabilities": {k: not self.readonly for k in ("create", "send", "rename", "archive", "delete", "cancel", "stream")}
                | {"list": True, "history": True}}

    async def status(self):
        return self.online

    async def list_sessions(self, principal):
        self.calls.append(("list", principal.account_id))
        return list(self.native) if self.scope == "deployment" else [s for s in self.native if s.get("owner") == principal.account_id]

    async def history(self, principal, session_id):
        return [{"id": "m1", "role": "assistant", "text": "Native history", "createdAt": "2026-10-25T00:00:00Z"}]

    async def create(self, principal, title):
        self.calls.append(("create", principal.account_id))
        session = {"id": "native-created", "title": title}
        self.native.append(session)
        return session

    async def manage(self, principal, session_id, action, value=None):
        self.calls.append((action, principal.account_id, session_id, value))
        return {"id": session_id}

    async def send(self, principal, session_id, text):
        self.calls.append(("send", principal.account_id, session_id, text))
        yield {"type": "delta", "text": "原生\n回复"}
        if not self.fail_stream:
            yield {"type": "done"}


class SessionRoutesTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.host = NativeHost()
        self.hermes = NativeHost("hermes", "account")
        self.hermes.native = [{"id": "alice-chat", "owner": "alice"}, {"id": "bob-chat", "owner": "bob"}]
        self.registry = AgentRegistry([self.host, self.hermes, NativeHost("readonly", readonly=True)])
        self.active = True
        self.auth_calls = 0

        async def authenticate(request):
            self.auth_calls += 1
            user = request.headers.get("X-Test-Authenticated-User")
            if user not in {"alice", "bob"}:
                raise GatewayError("AUTHENTICATION_REQUIRED")
            return Principal(user)

        app = web.Application()
        app.add_routes(create_agent_routes(self.registry, authenticate, lambda p: self.active))
        self.client = TestClient(TestServer(app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        await self.registry.close()

    def user(self, user="alice"):
        return {"X-Test-Authenticated-User": user, "Idempotency-Key": str(uuid.uuid4())}

    async def test_authentication_precedes_agent_lookup_and_read(self):
        for path in (BASE, BASE + "/missing/sessions", BASE + "/codex/sessions/terminal-1/history"):
            response = await self.client.get(path)
            self.assertEqual(401, response.status)
        self.assertEqual([], self.host.calls)

    async def test_shared_native_sessions_are_fresh_and_not_filtered_by_account(self):
        alice = await (await self.client.get(BASE + "/codex/sessions", headers=self.user())).json()
        self.host.native.append({"id": "new-cli-session", "title": "Later native session"})
        bob = await (await self.client.get(BASE + "/codex/sessions", headers=self.user("bob"))).json()
        self.assertEqual("terminal-1", alice["sessions"][0]["id"])
        self.assertEqual(["terminal-1", "new-cli-session"], [s["id"] for s in bob["sessions"]])

    async def test_hermes_principal_is_preserved(self):
        for user in ("alice", "bob"):
            result = await (await self.client.get(BASE + "/hermes/sessions", headers=self.user(user))).json()
            self.assertEqual([user + "-chat"], [s["id"] for s in result["sessions"]])

    async def test_capability_and_identity_override_rejected_server_side(self):
        response = await self.client.post(BASE + "/readonly/sessions", json={"title": "test"}, headers=self.user())
        self.assertEqual(405, response.status)
        response = await self.client.post(BASE + "/codex/sessions", json={"title": "test", "accountId": "bob"}, headers=self.user())
        self.assertEqual(400, response.status)
        self.assertEqual([], self.host.calls)

    async def test_create_and_rename_use_real_adapter_and_only_one_authentication(self):
        response = await self.client.post(BASE + "/codex/sessions", json={"title": "new"}, headers=self.user())
        self.assertEqual("native-created", (await response.json())["session"]["id"])
        before = self.auth_calls
        response = await self.client.patch(BASE + "/codex/sessions/terminal-1", json={"action": "rename", "value": "title"}, headers=self.user("bob"))
        self.assertEqual(200, response.status)
        self.assertEqual(before + 1, self.auth_calls, "signed nonce must only be verified once")
        self.assertIn(("rename", "bob", "terminal-1", "title"), self.host.calls)

    async def test_actual_online_status_and_closed_capabilities(self):
        self.host.online = False
        result = await (await self.client.get(BASE, headers=self.user())).json()
        self.assertFalse(result["agents"][0]["online"])
        self.assertEqual("deployment", result["agents"][0]["sessionScope"])
        self.assertFalse(result["agents"][2]["capabilities"]["send"])

    async def test_stream_is_native_and_missing_completion_is_error(self):
        path = BASE + "/codex/sessions/terminal-1/messages"
        response = await self.client.post(path, json={"text": "hello"}, headers=self.user())
        result = await response.text()
        self.assertIn('"type": "delta"', result)
        self.assertIn('"type": "done"', result)
        self.assertIn("原生\\n回复", result)
        self.host.fail_stream = True
        response = await self.client.post(path, json={"text": "again"}, headers=self.user())
        result = await response.text()
        self.assertIn("AGENT_STREAM_INTERRUPTED", result)
        self.assertNotIn('"type": "done"', result)

    async def test_revocation_denies_native_calls(self):
        self.active = False
        response = await self.client.get(BASE + "/codex/sessions", headers=self.user())
        self.assertEqual(401, response.status)
        self.assertEqual([], self.host.calls)

    async def test_native_create_and_send_receipts_prevent_duplicate_side_effects(self):
        headers = self.user()
        path = BASE + "/codex/sessions"
        first = await self.client.post(path, json={"title": "once"}, headers=headers)
        replay = await self.client.post(path, json={"title": "once"}, headers=headers)
        self.assertEqual(await first.json(), await replay.json())
        self.assertEqual(1, len([c for c in self.host.calls if c[0] == "create"]))
        conflict = await self.client.post(path, json={"title": "different"}, headers=headers)
        self.assertEqual(409, conflict.status)
        headers = self.user()
        path += "/terminal-1/messages"
        response = await self.client.post(path, json={"text": "once"}, headers=headers)
        await response.read()
        replay = await self.client.post(path, json={"text": "once"}, headers=headers)
        self.assertEqual(409, replay.status)
        self.assertEqual("REQUEST_ALREADY_EXECUTED", (await replay.json())["error"]["code"])
        self.assertEqual(1, len([c for c in self.host.calls if c[0] == "send"]))

    async def test_interrupted_native_turn_is_unknown_and_never_resent(self):
        self.host.fail_stream = True
        headers = self.user()
        path = BASE + "/codex/sessions/terminal-1/messages"
        response = await self.client.post(path, json={"text": "once"}, headers=headers)
        await response.read()
        replay = await self.client.post(path, json={"text": "once"}, headers=headers)
        self.assertEqual(409, replay.status)
        self.assertEqual("OUTCOME_UNKNOWN", (await replay.json())["error"]["code"])
        self.assertEqual(1, len([c for c in self.host.calls if c[0] == "send"]))

    async def test_archive_defaults_to_true_and_can_be_explicitly_reversed(self):
        path = BASE + "/codex/sessions/terminal-1"
        response = await self.client.patch(path, json={"action": "archive"}, headers=self.user())
        self.assertEqual(200, response.status)
        self.assertEqual(("archive", "alice", "terminal-1", True), self.host.calls[-1])
        response = await self.client.patch(path, json={"action": "archive", "value": False}, headers=self.user())
        self.assertEqual(200, response.status)
        self.assertEqual(("archive", "alice", "terminal-1", False), self.host.calls[-1])
        response = await self.client.patch(path, json={"action": "archive", "value": "false"}, headers=self.user())
        self.assertEqual(400, response.status)

    def test_registration_cannot_weaken_hermes_isolation_or_duplicate_ids(self):
        with self.assertRaises(ValueError):
            AgentRegistry([NativeHost("hermes", "deployment")])
        with self.assertRaises(ValueError):
            AgentRegistry([NativeHost(), NativeHost()])

    def test_native_receipts_survive_restart_and_unknown_outcomes_are_not_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "operations.sqlite"
            store = NativeOperations(path)
            self.assertIsNone(store.begin("alice", "done", "POST", "/sessions", {"title": "new"}))
            store.complete("alice", "done", {"session": {"id": "native"}})
            self.assertIsNone(store.begin("alice", "unknown", "POST", "/send", {"text": "hello"}))
            store.close()
            store = NativeOperations(path)
            try:
                self.assertEqual({"session": {"id": "native"}}, store.begin("alice", "done", "POST", "/sessions", {"title": "new"}))
                with self.assertRaises(OperationConflict) as error:
                    store.begin("alice", "unknown", "POST", "/send", {"text": "hello"})
                self.assertEqual("OUTCOME_UNKNOWN", error.exception.code)
                self.assertIsNone(store.begin("bob", "done", "POST", "/sessions", {"title": "new"}))
            finally:
                store.close()


if __name__ == "__main__":
    unittest.main()
