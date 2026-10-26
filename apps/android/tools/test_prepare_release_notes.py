import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import prepare_release_notes as notes


def event_file(payload: dict) -> Path:
    descriptor, filename = tempfile.mkstemp(prefix="oai-release-event-", suffix=".json")
    os.close(descriptor)
    path = Path(filename)
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


class PrepareReleaseNotesTest(unittest.TestCase):
    def test_merged_pull_request_body_is_used_verbatim(self):
        body = "## Changes\n\n- Keep this wording\n"
        path = event_file({
            "pull_request": {
                "merged": True,
                "base": {"ref": "dev"},
                "body": body,
            },
        })
        self.assertEqual(notes.prepare_release_notes("dev", "pull_request_target", path, "owner/repo", "abc"), body)

    def test_pull_request_for_the_wrong_branch_or_unmerged_pr_is_rejected(self):
        for payload in (
            {"pull_request": {"merged": False, "base": {"ref": "dev"}, "body": "text"}},
            {"pull_request": {"merged": True, "base": {"ref": "main"}, "body": "text"}},
        ):
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                notes.prepare_release_notes("dev", "pull_request_target", event_file(payload), "owner/repo", "abc")
        with self.assertRaises(ValueError):
            notes.prepare_release_notes(
                "main",
                "pull_request_target",
                event_file({"pull_request": {"merged": True, "base": {"ref": "main"}, "body": "text"}}),
                "owner/repo",
                "abc",
            )

    @patch.object(notes.subprocess, "run")
    def test_dev_push_uses_the_latest_commit_message(self, run):
        run.return_value.stdout = "Direct push summary\n\nLonger details\n"
        body = notes.prepare_release_notes("dev", "push", event_file({}), "owner/repo", "abc")
        self.assertEqual(body, run.return_value.stdout)
        self.assertEqual(run.call_args.args[0], ["git", "log", "-1", "--format=%B"])

    @patch.object(notes.subprocess, "run")
    def test_manual_stable_release_uses_the_matching_merged_main_pr(self, run):
        run.return_value.stdout = json.dumps([
            {"base": {"ref": "dev"}, "merged_at": "2026-01-01T00:00:00Z", "body": "ignore"},
            {"base": {"ref": "main"}, "merged_at": "2026-02-01T00:00:00Z", "body": "older"},
            {"base": {"ref": "main"}, "merged_at": "2026-03-01T00:00:00Z", "body": "latest"},
        ])
        body = notes.prepare_release_notes("main", "workflow_dispatch", event_file({}), "owner/repo", "abc")
        self.assertEqual(body, "latest")
        self.assertEqual(run.call_args.args[0], ["gh", "api", "repos/owner/repo/commits/abc/pulls"])

    @patch.object(notes.subprocess, "run")
    def test_manual_stable_release_falls_back_to_the_selected_main_commit_message(self, run):
        run.side_effect = [
            Mock(stdout="[]"),
            Mock(stdout="Direct main release summary\n\nDetails\n"),
        ]
        body = notes.prepare_release_notes("main", "workflow_dispatch", event_file({}), "owner/repo", "abc")
        self.assertEqual(body, "Direct main release summary\n\nDetails\n")
        self.assertEqual(run.call_args_list[0].args[0], ["gh", "api", "repos/owner/repo/commits/abc/pulls"])
        self.assertEqual(run.call_args_list[1].args[0], ["git", "show", "-s", "--format=%B", "abc"])

    @patch.object(notes.subprocess, "check_output")
    def test_append_build_metadata_formats_header_and_body(self, check_output):
        check_output.side_effect = ["402\n", "6b28baf4\n", "2026-10-23 23:00:00 +0800\n"]
        result = notes.append_build_metadata("Initial release notes\n")
        self.assertIn("> **构建信息**", result)
        self.assertIn("第 402 次提交 (`6b28baf4`)", result)
        self.assertIn("2026-10-23 23:00:00 +0800", result)
        self.assertIn("### 变更说明\n\nInitial release notes", result)

    @patch.object(notes.subprocess, "check_output", side_effect=Exception("Git error"))
    def test_append_build_metadata_falls_back_on_error(self, _check_output):
        result = notes.append_build_metadata("Plain notes")
        self.assertEqual(result, "Plain notes")


if __name__ == "__main__":
    unittest.main()
