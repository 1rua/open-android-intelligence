#!/usr/bin/env python3
"""Focused checks for fail-closed device target selection."""

import importlib.util
from pathlib import Path
import unittest


path = Path(__file__).with_name("collect-first-draw.py")
spec = importlib.util.spec_from_file_location("draw_collector", path)
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class DrawCollectorTest(unittest.TestCase):
    def test_center_uses_visible_node_bounds(self):
        self.assertEqual(collector.center({"bounds": "[10,20][30,60]"}), ("20", "40"))

    def test_center_rejects_missing_or_empty_bounds(self):
        for bounds in ("", "[10,20][10,60]", "[30,20][10,60]", "invalid"):
            with self.subTest(bounds=bounds), self.assertRaises(RuntimeError):
                collector.center({"bounds": bounds})

    def test_target_rejects_missing_or_duplicate_visible_items(self):
        with self.assertRaises(RuntimeError):
            collector.target([], text="synthetic")
        with self.assertRaises(RuntimeError):
            collector.target([{"text": "synthetic"}, {"text": "synthetic"}], text="synthetic")

    def test_target_ignores_offscreen_match(self):
        visible = {"text": "synthetic", "bounds": "[0,0][1,1]"}
        self.assertIs(collector.target([{"text": "synthetic", "off-screen": True}, visible],
                                       text="synthetic"), visible)

    def test_session_target_does_not_confuse_search_field_with_result(self):
        visible = {"class": "android.widget.TextView", "text": "Synthetic second"}
        nodes = [{"class": "android.widget.EditText", "text": "Synthetic second"}, visible,
                 {"content-desc": "管理会话 Synthetic second"}]
        self.assertIs(collector.session_target(nodes, "Synthetic second"), visible)


if __name__ == "__main__":
    unittest.main()
