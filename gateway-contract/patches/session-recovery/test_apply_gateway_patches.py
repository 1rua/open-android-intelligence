"""SDK-free safety tests for apply-gateway-patches.py."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("apply-gateway-patches.py")
SPEC = importlib.util.spec_from_file_location("apply_gateway_patches", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
PATCHER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = PATCHER
SPEC.loader.exec_module(PATCHER)


class ApplyGatewayPatchesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.repos: list[Path] = []

    def tearDown(self) -> None:
        self.temp.cleanup()

    def repo(self, name: str = "gateway") -> tuple[Path, str]:
        root = self.base / name
        root.mkdir()
        self.git(root, "init", "-q")
        self.git(root, "config", "user.email", "patch-test@example.invalid")
        self.git(root, "config", "user.name", "Patch Test")
        (root / "file.txt").write_text("before\n", encoding="utf-8")
        self.git(root, "add", "file.txt")
        self.git(root, "commit", "-qm", "base")
        self.repos.append(root)
        return root, self.git(root, "rev-parse", "HEAD")

    @staticmethod
    def git(root: Path, *args: str) -> str:
        result = subprocess.run(
            ["git", "-C", str(root), *args],
            check=False,
            capture_output=True,
            text=True,
            shell=False,
        )
        if result.returncode:
            raise AssertionError(result.stderr)
        return result.stdout.strip()

    def target(self, root: Path, revision: str, patch_text: str, name: str = "Gateway"):
        patch = self.base / f"{name}.patch"
        patch.write_text(patch_text, encoding="utf-8")
        return PATCHER.Target(name, root, patch, revision)

    def test_check_mode_validates_patch_without_modifying_checkout(self) -> None:
        root, revision = self.repo()
        patch = self.target(
            root,
            revision,
            "diff --git a/file.txt b/file.txt\n"
            "--- a/file.txt\n+++ b/file.txt\n"
            "@@ -1 +1 @@\n-before\n+after\n",
        )

        PATCHER.check_targets([patch])

        self.assertEqual("before\n", (root / "file.txt").read_text(encoding="utf-8"))
        self.assertEqual("", self.git(root, "status", "--porcelain"))

    def test_already_applied_patch_is_rejected_by_check(self) -> None:
        root, revision = self.repo()
        patch = self.target(
            root,
            revision,
            "diff --git a/file.txt b/file.txt\n"
            "--- a/file.txt\n+++ b/file.txt\n"
            "@@ -1 +1 @@\n-before\n+after\n",
        )
        PATCHER.apply_targets([patch])

        with self.assertRaises(PATCHER.PatchError):
            PATCHER.check_targets([patch])

    def test_dirty_checkout_is_rejected_before_apply(self) -> None:
        root, revision = self.repo()
        (root / "file.txt").write_text("local edit\n", encoding="utf-8")
        patch = self.target(
            root,
            revision,
            "diff --git a/file.txt b/file.txt\n"
            "--- a/file.txt\n+++ b/file.txt\n"
            "@@ -1 +1 @@\n-before\n+after\n",
        )

        with self.assertRaisesRegex(PATCHER.PatchError, "有未提交或未跟踪修改"):
            PATCHER.apply_targets([patch])
        self.assertEqual("local edit\n", (root / "file.txt").read_text(encoding="utf-8"))

    def test_wrong_pinned_head_is_rejected(self) -> None:
        root, _revision = self.repo()
        patch = self.target(root, "0" * 40, "not a patch\n")

        with self.assertRaisesRegex(PATCHER.PatchError, "HEAD 不匹配"):
            PATCHER.check_targets([patch])

    def test_archived_pins_are_independent_of_active_consumer_pins(self) -> None:
        project = self.base / "project"
        project.mkdir()
        (project / "openclaw-plugin-pin.json").write_text(
            '{"revision":"' + "c" * 40 + '"}\n', encoding="utf-8"
        )
        active_hermes_revision = "d" * 40
        (project / "README.md").write_text(
            "hermes plugins install 1rua/hermes-gateway-plugin --ref "
            + active_hermes_revision
            + " --enable\n"
            "hermes plugins install 1rua/hermes-gateway-plugin --force --ref "
            + active_hermes_revision
            + " --enable\n",
            encoding="utf-8",
        )

        archive = self.base / "archive"
        archive.mkdir()
        openclaw_revision = "a" * 40
        hermes_revision = "b" * 40
        (archive / "base-pins.json").write_text(
            json.dumps({"openclaw": openclaw_revision, "hermes": hermes_revision}),
            encoding="utf-8",
        )
        self.assertEqual((openclaw_revision, hermes_revision), PATCHER.pinned_revisions(archive))

    def test_missing_or_invalid_archived_pins_are_rejected(self) -> None:
        archive = self.base / "archive"
        archive.mkdir()
        with self.assertRaisesRegex(PATCHER.PatchError, "无法读取归档基线 pin"):
            PATCHER.pinned_revisions(archive)
        (archive / "base-pins.json").write_text("{invalid", encoding="utf-8")
        with self.assertRaisesRegex(PATCHER.PatchError, "不是有效 JSON"):
            PATCHER.pinned_revisions(archive)
        (archive / "base-pins.json").write_text("[]", encoding="utf-8")
        with self.assertRaisesRegex(PATCHER.PatchError, "必须是 JSON 对象"):
            PATCHER.pinned_revisions(archive)

        for pins in (
            {"openclaw": "a" * 40},
            {"openclaw": "A" * 40, "hermes": "b" * 40},
            {"openclaw": "a" * 39, "hermes": "b" * 40},
            {"openclaw": "a" * 40, "hermes": "g" * 40},
        ):
            with self.subTest(pins=pins):
                (archive / "base-pins.json").write_text(json.dumps(pins), encoding="utf-8")
                with self.assertRaisesRegex(PATCHER.PatchError, "40 位小写十六进制 SHA"):
                    PATCHER.pinned_revisions(archive)


if __name__ == "__main__":
    unittest.main()
