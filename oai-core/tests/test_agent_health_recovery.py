"""Agent health is isolated, bounded and survives transient failures."""
import asyncio
import json
import ssl
import unittest
from unittest.mock import patch

from oai_core.sessions import AdapterError, AgentRegistry
from oai_core.agent_health import AgentHealth


class Host:
    def __init__(self, name, online=True, verified=True):
        self.name = name
        self.online = online
        self._capabilities_verified = verified
        self.health_failure = None
        self.calls = 0
        self.gate = None
        self.closed = False
        self.error = None

    def descriptor(self):
        return {"id": self.name, "sessionScope": "deployment", "capabilities": {"send": self._capabilities_verified}}

    async def status(self):
        self.calls += 1
        if self.gate:
            await self.gate.wait()
        if self.error:
            raise self.error
        return self.online

    async def close(self):
        self.closed = True


class HealthRecoveryTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.a, self.b = Host("a"), Host("b")
        self.registry = AgentRegistry([self.a, self.b])
        self.addAsyncCleanup(self.registry.close)

    async def test_transient_threshold_reset_and_isolation(self):
        with patch("oai_core.agent_health.PROBE_INTERVAL", 0):
            self.assertTrue((await self.registry.discover())[0]["online"])
            self.a.online = False
            self.a.health_failure = {"kind": "transient", "code": "network", "source": "status"}
            for _ in range(2):
                result = await self.registry.discover()
                self.assertTrue(result[0]["online"])
                self.assertTrue(result[1]["online"])
                self.assertTrue(result[0]["capabilities"]["send"])
            self.a.online = True
            self.assertTrue((await self.registry.discover())[0]["online"])
            self.a.online = False
            for _ in range(2):
                self.assertTrue((await self.registry.discover())[0]["online"])
            self.assertFalse((await self.registry.discover())[0]["online"])
            self.a.online = True
            self.assertTrue((await self.registry.discover())[0]["online"])

    async def test_initial_failure_and_permanent_rejection(self):
        self.a.online = False
        with patch("oai_core.agent_health.PROBE_INTERVAL", 0):
            self.assertFalse((await self.registry.discover())[0]["online"])
            self.a.online = True
            self.assertTrue((await self.registry.discover())[0]["online"])
            for kind in ("authentication", "security", "protocol"):
                self.a.online = False
                self.a.health_failure = {"kind": kind, "code": "rejected", "source": "status"}
                result = await self.registry.discover()
                self.assertFalse(result[0]["online"])
                self.assertTrue(result[1]["online"])
                self.a.online = True
                await self.registry.discover()

    async def test_initial_failure_is_offline_and_first_success_is_not_recovery(self):
        self.a.online = False
        self.a.health_failure = {"kind": "transient", "code": "network", "source": "status"}
        with patch("oai_core.agent_health.PROBE_INTERVAL", 0):
            with self.assertLogs("oai_core.agent_health", level="INFO") as logs:
                self.assertFalse((await self.registry.discover())[0]["online"])
                state = self.registry._health._states["a"]
                self.assertFalse(state.succeeded)
                self.assertEqual("offline", state.phase)
                self.a.online = True
                self.assertTrue((await self.registry.discover())[0]["online"])
                self.assertEqual("healthy", state.phase)
        text = " ".join(logs.output)
        self.assertIn("agent=a state=offline", text)
        self.assertIn("agent=a state=healthy", text)
        self.assertNotIn("agent=a state=recovered", text)

    async def test_singleflight_cancellation_and_parallel_agents(self):
        self.a.gate = asyncio.Event()
        first = asyncio.create_task(self.registry.discover())
        await asyncio.sleep(0.02)
        second = asyncio.create_task(self.registry.discover())
        await asyncio.sleep(0.02)
        self.assertEqual(1, self.a.calls)
        self.assertEqual(1, self.b.calls)
        first.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await first
        self.a.gate.set()
        self.assertTrue((await second)[0]["online"])
        self.assertEqual(1, self.a.calls)

    async def test_periodic_probe_and_close_cancels_probe(self):
        with patch("oai_core.agent_health.PROBE_INTERVAL", 0.03):
            await self.registry.discover()
            self.a.gate = asyncio.Event()
            await asyncio.sleep(0.08)
            self.assertGreaterEqual(self.a.calls, 2)
            await self.registry.close()
            self.assertTrue(self.a.closed)
            calls = self.a.calls
            await asyncio.sleep(0.05)
            self.assertEqual(calls, self.a.calls)

    async def test_probe_timeout_is_bounded(self):
        self.a.gate = asyncio.Event()
        with patch("oai_core.agent_health.PROBE_TIMEOUT", 0.03):
            result = await asyncio.wait_for(self.registry.discover(), 0.3)
        self.assertFalse(result[0]["online"])
        self.assertTrue(result[1]["online"])

    async def test_cached_results_and_exception_does_not_use_stale_failure(self):
        await self.registry.discover()
        await self.registry.discover()
        self.assertEqual((1, 1), (self.a.calls, self.b.calls))
        self.a.health_failure = {"kind": "authentication", "code": "secret", "source": "secret"}
        self.a.error = RuntimeError("secret")
        with patch("oai_core.agent_health.PROBE_INTERVAL", 0):
            with self.assertLogs("oai_core.agent_health", level="INFO") as logs:
                self.assertTrue((await self.registry.discover())[0]["online"])
                self.assertTrue((await self.registry.discover())[0]["online"])
                self.assertFalse((await self.registry.discover())[0]["online"])
            self.assertNotIn("secret", " ".join(logs.output))

    async def test_replaced_adapter_cancels_old_probe_and_starts_fresh(self):
        health = AgentHealth()
        self.addAsyncCleanup(health.close)
        old, new = Host("same"), Host("same", online=False)
        old.gate = asyncio.Event()
        old_call = asyncio.create_task(health.online("same", old))
        await asyncio.sleep(0.02)
        self.assertEqual(old.calls, 1)
        self.assertFalse(await health.online("same", new))
        self.assertTrue(old_call.done())
        self.assertIs(health._states["same"].adapter, new)
        self.assertFalse(health._states["same"].succeeded)
        old.gate.set()
        await asyncio.gather(old_call, return_exceptions=True)
        self.assertFalse(health._states["same"].online)
        self.assertEqual(new.calls, 1)

    async def test_replacement_waits_for_cancelled_old_probe_and_shares_new_one(self):
        health = AgentHealth()
        self.addAsyncCleanup(health.close)
        cancelled, release = asyncio.Event(), asyncio.Event()

        class SlowCancellationHost(Host):
            async def status(self):
                self.calls += 1
                try:
                    await asyncio.Event().wait()
                except asyncio.CancelledError:
                    cancelled.set()
                    await release.wait()
                    return True

        old, new = SlowCancellationHost("same"), Host("same", online=False)
        old_call = asyncio.create_task(health.online("same", old))
        await asyncio.sleep(0.02)
        replacement = asyncio.create_task(health.online("same", new))
        await asyncio.wait_for(cancelled.wait(), 0.3)
        second = asyncio.create_task(health.online("same", new))
        await asyncio.sleep(0.02)
        self.assertFalse(replacement.done())
        self.assertEqual(0, new.calls)
        release.set()
        self.assertEqual([False, False], await asyncio.gather(replacement, second))
        await asyncio.gather(old_call, return_exceptions=True)
        self.assertIs(health._states["same"].adapter, new)
        self.assertEqual(1, new.calls)
        self.assertFalse(health._states["same"].succeeded)

    async def test_degraded_recovered_offline_diagnostics_and_safe_fields(self):
        with patch("oai_core.agent_health.PROBE_INTERVAL", 0):
            with self.assertLogs("oai_core.agent_health", level="INFO") as logs:
                self.a.online = False
                self.a.health_failure = {"kind": "transient", "code": "private-token", "source": "private-source"}
                await self.registry.discover()
                self.a.online = True
                await self.registry.discover()
                state = self.registry._health._states["a"]
                first_success = state.last_success
                self.assertIsNotNone(first_success)
                self.a.online = False
                await self.registry.discover()
                self.assertEqual(first_success, state.last_success)
                await self.registry.discover()
                await self.registry.discover()
                self.a.online = True
                await self.registry.discover()
                self.assertGreaterEqual(state.last_success, first_success)
                self.assertEqual(0, state.failures)
            text = " ".join(logs.output)
            for term in ("degraded", "recovered", "offline", "duration", "failures"):
                self.assertIn(term, text)
            self.assertNotIn("private-token", text)
            self.assertNotIn("private-source", text)

    async def test_periodic_respects_recent_discovery_probe(self):
        health = AgentHealth()
        self.addAsyncCleanup(health.close)
        host = Host("same")
        with patch("oai_core.agent_health.PROBE_INTERVAL", 0.18):
            await health.online("same", host)
            await asyncio.sleep(0.10)
            health._states["same"].last_probe -= 0.18
            await health.online("same", host)
            self.assertEqual(2, host.calls)
            await asyncio.sleep(0.11)
            self.assertEqual(2, host.calls)
            await asyncio.sleep(0.11)
            self.assertEqual(3, host.calls)

    async def test_explicit_permanent_exceptions_immediately_offline(self):
        examples = [
            AdapterError("AGENT_UNAVAILABLE", 502, details={"reason": "authentication_failed"}),
            AdapterError("AGENT_UNAVAILABLE", 502, details={"reason": "tls_rejected"}),
            AdapterError("HOST_INCOMPATIBLE", 502),
            ssl.SSLError("private exception text"),
            json.JSONDecodeError("private", "private", 0),
        ]
        with patch("oai_core.agent_health.PROBE_INTERVAL", 0):
            for error in examples:
                self.a.error = None
                self.assertTrue((await self.registry.discover())[0]["online"])
                self.a.error = error
                self.a.health_failure = {"kind": "transient", "code": "private"}
                with self.assertLogs("oai_core.agent_health", level="INFO") as logs:
                    result = await self.registry.discover()
                self.assertFalse(result[0]["online"])
                self.assertTrue(result[1]["online"])
                self.assertNotIn("private", " ".join(logs.output))

    async def test_unknown_and_known_capabilities(self):
        self.a._capabilities_verified = False
        with self.assertRaises(AdapterError) as caught:
            self.registry.get("a", "send")
        self.assertEqual(("AGENT_UNAVAILABLE", 503), (caught.exception.code, caught.exception.status))
        self.a._capabilities_verified = True
        with self.assertRaises(AdapterError) as caught:
            self.registry.get("a", "missing")
        self.assertEqual(("UNSUPPORTED_CAPABILITY", 405), (caught.exception.code, caught.exception.status))
