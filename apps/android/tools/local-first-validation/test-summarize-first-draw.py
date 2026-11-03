#!/usr/bin/env python3
"""Focused checks for measurement correlation and percentile reporting."""

import importlib.util
import json
from pathlib import Path
import unittest


path = Path(__file__).with_name("summarize-first-draw.py")
spec = importlib.util.spec_from_file_location("first_draw", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def events(epoch, source="local", elapsed_ms=10, found=True):
    common = {"bootId": "synthetic-boot", "conversationId": "synthetic-conv", "epoch": epoch}
    opened = {**common, "type": "timeline.opened", "clickedAtNs": 1_000_000,
              "source": "memory" if source == "memory" else "pending"}
    read = {**common, "type": "timeline.local_read", "found": found}
    draw = {**common, "type": "timeline.first_draw", "source": source,
            "elapsedNs": elapsed_ms * 1_000_000,
            "monotonicNs": 1_000_000 + elapsed_ms * 1_000_000 + 100}
    return [opened, read, draw]


class FirstDrawSummaryTest(unittest.TestCase):
    def run_summary(self, records, history_ids=None):
        return module.summarize([json.dumps(row) for row in records], history_ids=history_ids)

    def test_nearest_rank_and_median_use_only_matching_epochs(self):
        records = [row for index in range(1, 21) for row in events(index, elapsed_ms=index)]
        summary = self.run_summary(records)["sources"]["local"]
        self.assertEqual(summary["count"], 20)
        self.assertEqual(summary["medianMs"], 10.5)
        self.assertEqual(summary["p95Ms"], 19)

    def test_low_sample_count_reports_percentiles_with_warning(self):
        summary = self.run_summary(events(1))["sources"]["local"]
        self.assertEqual(summary["count"], 1)
        self.assertEqual(summary["medianMs"], 10)
        self.assertEqual(summary["p95Ms"], 10)
        self.assertTrue(summary["lowSampleWarning"])

    def test_duplicate_open_or_draw_and_missing_open_are_rejected(self):
        first = events(1)
        second = events(2)
        third = events(3)
        result = self.run_summary(first + [first[0]] + second + [second[2]] + third[1:])
        self.assertEqual(result["sources"]["local"]["count"], 0)
        self.assertEqual(result["rejected"]["ambiguous_open_or_draw"], 3)

    def test_remote_requires_a_confirmed_local_miss(self):
        second = events(2, "remote", found=False)
        second.append({"bootId": "synthetic-boot", "conversationId": "synthetic-conv", "epoch": 2,
                       "type": "timeline.remote_synced", "messageCount": 1, "success": True})
        result = self.run_summary(events(1, "remote", found=True) + second, {"synthetic-conv"})
        self.assertEqual(result["sources"]["remote"]["count"], 1)
        self.assertEqual(result["rejected"]["remote_provenance_uncertain"], 1)

    def test_remote_requires_nonempty_sync_and_adapter_history_evidence(self):
        records = events(1, "remote", found=False)
        result = self.run_summary(records)
        self.assertEqual(result["rejected"]["remote_history_unverified"], 1)

    def test_local_requires_a_confirmed_local_snapshot(self):
        result = self.run_summary(events(1, "local", found=False) + events(2, "local", found=True))
        self.assertEqual(result["sources"]["local"]["count"], 1)
        self.assertEqual(result["rejected"]["local_provenance_uncertain"], 1)

    def test_memory_requires_memory_open_and_elapsed_cannot_exceed_logged_time(self):
        first = events(1, "memory")
        first[0]["source"] = "pending"
        second = events(2)
        second[2]["monotonicNs"] = 1
        result = self.run_summary(first + second)
        self.assertEqual(result["rejected"]["memory_source_mismatch"], 1)
        self.assertEqual(result["rejected"]["invalid_timing_or_source"], 1)


if __name__ == "__main__":
    unittest.main()
