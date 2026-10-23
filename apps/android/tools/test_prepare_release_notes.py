import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

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
    def test_manual_stable_release_fails_when_no_merged_main_pr_is_associated(self, run):
        run.return_value.stdout = "[]"
        with self.assertRaisesRegex(ValueError, "merged pull request"):
            notes.prepare_release_notes("main", "workflow_dispatch", event_file({}), "owner/repo", "abc")


if __name__ == "__main__":
    unittest.main()
