#!/usr/bin/env python3
"""sync_release_tag.py 单元测试：覆盖幂等跳过、PATCH/POST、瞬态重试、Git 兜底与策略拒绝诊断。"""

from __future__ import annotations

import base64
import io
import json
import subprocess
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

TOOLS_DIR = Path(__file__).resolve().parent
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

import sync_release_tag as sync_tag  # noqa: E402

OLD_SHA = "0" * 40
TARGET_SHA = "c57640109448149daa4319e02b8cf15bc47e61aa"
TAG_OBJECT_SHA = "a" * 40
TEST_TOKEN = "ghs_super_secret_token_value_123456"


def http_proc(status: int, body: dict | str, *, returncode: int = 0, stderr: str = "") -> subprocess.CompletedProcess[str]:
    payload = json.dumps(body) if isinstance(body, dict) else body
    stdout = f"HTTP/2.0 {status} Status\r\nContent-Type: application/json\r\n\r\n{payload}"
    return subprocess.CompletedProcess(args=["gh"], returncode=returncode, stdout=stdout, stderr=stderr)


class SyncReleaseTagTest(unittest.TestCase):
    def test_noop_when_tag_already_points_to_target_commit(self):
        calls: list[list[str]] = []

        def fake_run(args, *, env=None):
            calls.append(args)
            return http_proc(200, {"ref": "refs/tags/v2.2.1-dev", "object": {"sha": TARGET_SHA, "type": "commit"}})

        buf = io.StringIO()
        with patch.object(sync_tag, "run_command", side_effect=fake_run), redirect_stdout(buf):
            sync_tag.sync_release_tag("1rua/open-android-intelligence", "v2.2.1-dev", TARGET_SHA, token=TEST_TOKEN)

        self.assertEqual(len(calls), 1)
        self.assertIn("repos/1rua/open-android-intelligence/git/ref/tags/v2.2.1-dev", calls[0])
        self.assertIn("无需重复更新", buf.getvalue())

    def test_patches_existing_tag_and_prints_response(self):
        calls: list[list[str]] = []
        responses = [
            http_proc(200, {"ref": "refs/tags/v2.2.1-dev", "object": {"sha": OLD_SHA, "type": "commit"}}),
            http_proc(200, {"ref": "refs/tags/v2.2.1-dev", "object": {"sha": TARGET_SHA, "type": "commit"}}),
        ]

        def fake_run(args, *, env=None):
            calls.append(args)
            return responses.pop(0)

        buf = io.StringIO()
        with patch.object(sync_tag, "run_command", side_effect=fake_run), redirect_stdout(buf):
            sync_tag.sync_release_tag("1rua/open-android-intelligence", "v2.2.1-dev", TARGET_SHA, token=TEST_TOKEN)

        self.assertEqual(len(calls), 2)
        self.assertIn("PATCH", calls[1])
        self.assertIn("repos/1rua/open-android-intelligence/git/refs/tags/v2.2.1-dev", calls[1])
        self.assertIn("force=true", calls[1])
        output = buf.getvalue()
        self.assertIn("PATCH repos/1rua/open-android-intelligence/git/refs/tags/v2.2.1-dev", output)
        self.assertIn(TARGET_SHA, output)

    def test_creates_missing_tag_when_get_returns_404(self):
        calls: list[list[str]] = []
        responses = [
            http_proc(404, {"message": "Not Found"}, returncode=1, stderr="gh: Not Found (HTTP 404)"),
            http_proc(201, {"ref": "refs/tags/v2.2.1-dev", "object": {"sha": TARGET_SHA, "type": "commit"}}),
        ]

        def fake_run(args, *, env=None):
            calls.append(args)
            return responses.pop(0)

        with patch.object(sync_tag, "run_command", side_effect=fake_run), redirect_stdout(io.StringIO()):
            sync_tag.sync_release_tag("1rua/open-android-intelligence", "v2.2.1-dev", TARGET_SHA, token=TEST_TOKEN)

        self.assertEqual(len(calls), 2)
        self.assertIn("POST", calls[1])
        self.assertIn("repos/1rua/open-android-intelligence/git/refs", calls[1])
        self.assertIn("ref=refs/tags/v2.2.1-dev", calls[1])

    def test_handles_concurrent_creation_by_falling_through_to_patch(self):
        calls: list[list[str]] = []
        responses = [
            http_proc(404, {"message": "Not Found"}, returncode=1, stderr="gh: Not Found (HTTP 404)"),
            http_proc(422, {"message": "Reference already exists"}, returncode=1, stderr="gh: Reference already exists (HTTP 422)"),
            http_proc(200, {"ref": "refs/tags/v2.2.1-dev", "object": {"sha": TARGET_SHA, "type": "commit"}}),
        ]

        def fake_run(args, *, env=None):
            calls.append(args)
            return responses.pop(0)

        with patch.object(sync_tag, "run_command", side_effect=fake_run), redirect_stdout(io.StringIO()):
            sync_tag.sync_release_tag("1rua/open-android-intelligence", "v2.2.1-dev", TARGET_SHA, token=TEST_TOKEN)

        self.assertEqual(len(calls), 3)
        self.assertIn("POST", calls[1])
        self.assertIn("PATCH", calls[2])

    def test_retries_transient_truncated_json_and_succeeds(self):
        slept: list[float] = []
        responses = [
            subprocess.CompletedProcess(["gh"], 1, stdout="", stderr="unexpected end of JSON input"),
            http_proc(200, {"ref": "refs/tags/v2.2.1-dev", "object": {"sha": OLD_SHA, "type": "commit"}}),
            subprocess.CompletedProcess(["gh"], 1, stdout="", stderr="unexpected end of JSON input"),
            http_proc(200, {"ref": "refs/tags/v2.2.1-dev", "object": {"sha": TARGET_SHA, "type": "commit"}}),
        ]

        with patch.object(sync_tag, "run_command", side_effect=lambda args, env=None: responses.pop(0)), redirect_stdout(io.StringIO()):
            sync_tag.sync_release_tag(
                "1rua/open-android-intelligence",
                "v2.2.1-dev",
                TARGET_SHA,
                token=TEST_TOKEN,
                max_attempts=3,
                retry_delay=1.5,
                sleep_fn=slept.append,
            )

        self.assertEqual(slept, [1.5, 1.5])
        self.assertEqual(responses, [])

    def test_falls_back_to_authenticated_git_push_when_api_exhausts_transient_errors(self):
        calls: list[list[str]] = []
        encoded_auth = base64.b64encode(f"x-access-token:{TEST_TOKEN}".encode("utf-8")).decode("ascii")

        def fake_run(args, *, env=None):
            calls.append(args)
            if args[0] == "gh":
                return subprocess.CompletedProcess(args, 1, stdout="", stderr="unexpected end of JSON input")
            if args[0] == "git" and "push" in args:
                return subprocess.CompletedProcess(
                    args, 0, stdout="", stderr=f"To https://github.com/1rua/open-android-intelligence.git\n + {OLD_SHA[:7]}...{TARGET_SHA[:7]} {TARGET_SHA} -> v2.2.1-dev (forced update)\n{TEST_TOKEN}"
                )
            if args[0] == "git" and "ls-remote" in args:
                return subprocess.CompletedProcess(args, 0, stdout=f"{TARGET_SHA}\trefs/tags/v2.2.1-dev\n", stderr="")
            raise AssertionError(f"unexpected command: {args}")

        buf = io.StringIO()
        with patch.object(sync_tag, "run_command", side_effect=fake_run), redirect_stdout(buf):
            sync_tag.sync_release_tag(
                "1rua/open-android-intelligence",
                "v2.2.1-dev",
                TARGET_SHA,
                token=TEST_TOKEN,
                max_attempts=2,
                retry_delay=0.0,
            )

        self.assertEqual([cmd[0] for cmd in calls], ["gh", "gh", "git", "git"])
        self.assertIn(f"{TARGET_SHA}:refs/tags/v2.2.1-dev", calls[2])
        self.assertIn("--force", calls[2])
        output = buf.getvalue()
        self.assertNotIn(TEST_TOKEN, output)
        self.assertNotIn(encoded_auth, output)
        self.assertIn("Git 兜底成功同步", output)

    def test_policy_rejection_emits_explicit_diagnostic_without_fallback(self):
        calls: list[list[str]] = []
        responses = [
            http_proc(200, {"ref": "refs/tags/v2.2.1-dev", "object": {"sha": OLD_SHA, "type": "commit"}}),
            http_proc(
                422,
                {"message": "Repository rule violations found: Cannot force-update protected tag."},
                returncode=1,
                stderr="gh: Repository rule violations found (HTTP 422)",
            ),
        ]

        def fake_run(args, *, env=None):
            calls.append(args)
            return responses.pop(0)

        out = io.StringIO()
        err = io.StringIO()
        with patch.dict("os.environ", {"GH_TOKEN": TEST_TOKEN}, clear=False), \
                patch.object(sync_tag, "run_command", side_effect=fake_run), \
                redirect_stdout(out), redirect_stderr(err):
            code = sync_tag.main(
                ["--repo", "1rua/open-android-intelligence", "--release-tag", "v2.2.1-dev", "--sha", TARGET_SHA]
            )

        self.assertEqual(code, 1)
        self.assertEqual(len(calls), 2, "策略拒绝不应盲目重试或走 Git 兜底")
        self.assertIn("::error::远端策略或权限拒绝更新发布标签", err.getvalue())
        self.assertIn("Cannot force-update protected tag", err.getvalue())

    def test_annotated_tag_is_peeled_and_replaced_with_target_commit(self):
        calls: list[list[str]] = []
        responses = [
            http_proc(200, {"ref": "refs/tags/v2.2.1-dev", "object": {"sha": TAG_OBJECT_SHA, "type": "tag"}}),
            http_proc(200, {"sha": TAG_OBJECT_SHA, "object": {"sha": TARGET_SHA, "type": "commit"}}),
            http_proc(200, {"ref": "refs/tags/v2.2.1-dev", "object": {"sha": TARGET_SHA, "type": "commit"}}),
        ]

        def fake_run(args, *, env=None):
            calls.append(args)
            return responses.pop(0)

        with patch.object(sync_tag, "run_command", side_effect=fake_run), redirect_stdout(io.StringIO()):
            sync_tag.sync_release_tag("1rua/open-android-intelligence", "v2.2.1-dev", TARGET_SHA, token=TEST_TOKEN)

        self.assertEqual(len(calls), 3)
        self.assertIn(f"repos/1rua/open-android-intelligence/git/tags/{TAG_OBJECT_SHA}", calls[1])
        self.assertIn("PATCH", calls[2])

    def test_rejects_invalid_sha_or_missing_token(self):
        with self.assertRaisesRegex(ValueError, "40 位"):
            sync_tag.sync_release_tag("1rua/open-android-intelligence", "v2.2.1-dev", "not-a-sha", token=TEST_TOKEN)
        with self.assertRaisesRegex(ValueError, "GH_TOKEN"):
            sync_tag.sync_release_tag("1rua/open-android-intelligence", "v2.2.1-dev", TARGET_SHA, token="")


if __name__ == "__main__":
    unittest.main()
