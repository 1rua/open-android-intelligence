import unittest
from pathlib import Path
from oai_core.auth import Principal
from adapter import SampleCustomAdapter, create_sample_adapter


class TestSampleAdapter(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.adapter = SampleCustomAdapter()

    async def test_lifecycle(self):
        desc = self.adapter.descriptor()
        self.assertEqual("sample-custom", desc["id"])
        self.assertTrue(await self.adapter.status())

        p = Principal("alice")
        sessions = await self.adapter.list_sessions(p)
        self.assertEqual(1, len(sessions))

        new_s = await self.adapter.create(p, "测试会话")
        self.assertEqual("测试会话", new_s["title"])

        messages = await self.adapter.history(p, new_s["id"])
        self.assertEqual(2, len(messages))

        events = [e async for e in self.adapter.send(p, new_s["id"], "你好")]
        self.assertGreater(len(events), 1)
        self.assertEqual("done", events[-1]["type"])


if __name__ == "__main__":
    unittest.main()
