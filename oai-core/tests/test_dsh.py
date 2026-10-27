"""Transport preserves the explicitly connected, shared DSH host corpus."""
import unittest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from oai_core.adapters.dsh import DshSessionAdapter
from oai_core.auth import Principal
from oai_core.sessions import AdapterError, SESSION_REQUEST_MAX_BYTES, session_request_json


class TestDshAdapter(unittest.IsolatedAsyncioTestCase):
    async def test_dsh_shared_corpus_is_fresh_and_no_core_device_credential_is_forwarded(self):
        sessions = [{"id": "terminal-session", "title": "Terminal"}]
        requests = []
        terminal = True

        async def host(request):
            self.assertEqual(request.headers["Authorization"], "Bearer test-only-dsh-session-token-1234567890")
            self.assertFalse(any(key.lower().startswith("x-oai") for key in request.headers))
            requests.append((request.method, request.path, await request.json() if request.can_read_body else None))
            if request.path.endswith("/status"):
                return web.json_response({"online": True, "capabilities": {"list": True, "history": True, "send": True, "stream": True}})
            if request.path.endswith("/messages"):
                response = web.StreamResponse(headers={"Content-Type": "application/x-ndjson"})
                await response.prepare(request)
                await response.write(b'{"type":"delta","text":"hello"}\n')
                if terminal:
                    await response.write(b'{"type":"done","text":"hello"}\n')
                return response
            return web.json_response({"sessions": list(sessions)})

        app = web.Application()
        app.router.add_route("*", "/{tail:.*}", host)
        async with TestClient(TestServer(app)) as client:
            adapter = DshSessionAdapter(str(client.make_url("/")).rstrip("/"), "test-only-dsh-session-token-1234567890")
            self.assertTrue(await adapter.status())
            self.assertEqual(adapter.descriptor()["sessionScope"], "deployment")
            self.assertTrue(adapter.descriptor()["capabilities"]["send"])
            self.assertEqual(len(await adapter.list_sessions(Principal("alice"))), 1)
            sessions.append({"id": "later-cli-session", "title": "CLI"})
            self.assertEqual(len(await adapter.list_sessions(Principal("bob"))), 2)
            self.assertEqual([item["type"] async for item in adapter.send(Principal("alice"), "terminal-session", "hi", client_message_id="android-id")], ["delta", "done"])
            self.assertEqual(requests[-1][2], {"text": "hi"})
            terminal = False
            with self.assertRaises(AdapterError) as error:
                _ = [event async for event in adapter.send(Principal("alice"), "terminal-session", "hi")]
            self.assertEqual(error.exception.code, "STREAM_INTERRUPTED")

    def test_dsh_requires_an_explicit_secure_origin_and_token(self):
        for url in ("http://remote.invalid", "http://user:pass@localhost", "http://localhost/other", "file:///tmp"):
            with self.assertRaises(ValueError):
                DshSessionAdapter(url, "test-token-with-at-least-thirty-two-characters")
        with self.assertRaises(ValueError):
            DshSessionAdapter("http://localhost", "short")

    async def test_http_bridge_uses_compact_utf8_for_all_outgoing_json_requests(self):
        received = []

        async def host(request):
            raw = await request.read()
            received.append(raw)
            if request.path.endswith("/messages"):
                return web.Response(body=b'{"type":"done"}\n', content_type="application/x-ndjson")
            return web.json_response({"session": {"id": "native"}})

        app = web.Application(client_max_size=SESSION_REQUEST_MAX_BYTES)
        app.router.add_route("POST", "/{tail:.*}", host)
        async with TestClient(TestServer(app)) as client:
            adapter = DshSessionAdapter(str(client.make_url("/")).rstrip("/"), "test-only-dsh-session-token-1234567890")
            for text in ("a" * 70000, "文" * 24000, "😀" * 100000):
                # The final case would exceed 1 MiB with Python's default ASCII escapes.
                events = [item async for item in adapter.send(Principal("alice"), "native", text)]
                self.assertEqual(events, [{"type": "done"}])
                self.assertEqual(received[-1], session_request_json({"text": text}).encode())
                self.assertTrue(65536 < len(received[-1]) < SESSION_REQUEST_MAX_BYTES)
            await adapter.create(Principal("alice"), "中文 title")
            self.assertEqual(received[-1], b'{"title":"\xe4\xb8\xad\xe6\x96\x87 title"}')
            await adapter.manage(Principal("alice"), "native", "rename", "中文 rename")
            self.assertEqual(received[-1], session_request_json({"value": "中文 rename"}).encode())


if __name__ == "__main__":
    unittest.main()
