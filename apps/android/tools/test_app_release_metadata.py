"""验证更新发布的渠道、安装顺序与实际 APK 信息校验。"""

import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import app_release_metadata as release


COMMIT = "1234567890abcdef1234567890abcdef12345678"
DIGEST = "ab" * 32


class AppReleaseMetadataTest(unittest.TestCase):
    def metadata(self, tag="nightly", count=397):
        return release.release_metadata("2.1.1", COMMIT, count, tag)

    def output_metadata(self, metadata):
        return {
            "applicationId": metadata["applicationId"],
            "elements": [{
                "versionName": metadata["versionName"],
                "versionCode": metadata["versionCode"],
                "outputFile": "app-full-debug.apk",
            }],
        }

    def test_development_rebuild_does_not_invent_an_update(self):
        first = self.metadata()
        self.assertEqual(first, self.metadata())
        self.assertEqual(first["versionName"], "2.1.1-dev.397+1234567890ab")
        self.assertEqual(first["versionCode"], 3970)
        self.assertEqual(first["channel"], "development")

    def test_preview_and_stable_tags_keep_the_complete_version_name(self):
        preview = self.metadata("v2.2.0-beta.1+build.7")
        stable = self.metadata("v2.2.0")
        self.assertEqual(preview["versionName"], "2.2.0-beta.1+build.7")
        self.assertEqual(preview["channel"], "preview")
        self.assertEqual(stable["versionName"], "2.2.0")
        self.assertEqual(stable["channel"], "stable")

    def test_channel_promotion_and_next_commit_never_downgrade_install_code(self):
        codes = [self.metadata(tag)["versionCode"] for tag in ("nightly", "v2.1.1-rc.1", "v2.1.1")]
        codes.append(self.metadata(count=398)["versionCode"])
        self.assertEqual(codes, sorted(set(codes)))

    def test_invalid_or_ambiguous_version_tags_are_rejected(self):
        for tag in ("v2.2", "v02.2.0", "v2.2.0-beta.01", "v2.2.0-", "v2.2.0+", "release-2.2.0", "v2.2.0\n"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.metadata(tag)

    def test_missing_git_history_and_invalid_commit_are_rejected(self):
        for count, commit in ((0, COMMIT), (397, "1234567"), (210_000_001, COMMIT)):
            with self.subTest(count=count), self.assertRaises(ValueError):
                release.release_metadata("2.1.1", commit, count, "nightly")

    def test_manifest_binds_install_code_name_and_certificate(self):
        metadata = self.metadata()
        manifest = release.verified_manifest(metadata, self.output_metadata(metadata), Path("app-full-debug.apk"), DIGEST)
        self.assertEqual(manifest["assetName"], "app-full-debug.apk")
        self.assertEqual(manifest["signingCertificateSha256"], DIGEST)
        self.assertEqual(manifest["distribution"], "full")
        self.assertEqual(manifest["schemaVersion"], 1)
        self.assertEqual(manifest["commit"], COMMIT)

    def test_manifest_rejects_wrong_apk_identity_or_version(self):
        metadata = self.metadata()
        for key, value in (("versionCode", 1), ("versionName", "2.1.1"), ("outputFile", "another.apk")):
            actual = self.output_metadata(metadata)
            actual["elements"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                release.verified_manifest(metadata, actual, Path("app-full-debug.apk"), DIGEST)
        actual = self.output_metadata(metadata)
        actual["applicationId"] = "unrelated.application"
        with self.assertRaises(ValueError):
            release.verified_manifest(metadata, actual, Path("app-full-debug.apk"), DIGEST)

    def test_manifest_rejects_splits_and_malformed_certificate(self):
        metadata = self.metadata()
        for elements in ([], self.output_metadata(metadata)["elements"] * 2):
            with self.subTest(elements=elements), self.assertRaises(ValueError):
                release.verified_manifest(metadata, {"elements": elements}, Path("app-full-debug.apk"), DIGEST)
        with self.assertRaises(ValueError):
            release.verified_manifest(metadata, self.output_metadata(metadata), Path("app-full-debug.apk"), DIGEST.upper())

    @patch.object(release, "find_android_tool", return_value=Path("test-apksigner"))
    @patch.object(release.subprocess, "run")
    def test_certificate_requires_successful_apk_verification(self, run, _signer):
        run.side_effect = subprocess.CalledProcessError(1, "apksigner")
        with self.assertRaises(subprocess.CalledProcessError):
            release.signing_digest(Path("app-full-debug.apk"))
        self.assertTrue(run.call_args.kwargs["check"])

    @patch.object(release, "find_android_tool", return_value=Path("test-apksigner"))
    @patch.object(release.subprocess, "run")
    def test_certificate_is_normalized_and_multiple_signers_are_rejected(self, run, _signer):
        run.return_value.stdout = f"Signer #1 certificate SHA-256 digest: {DIGEST.upper()}\n"
        self.assertEqual(release.signing_digest(Path("app-full-debug.apk")), DIGEST)
        run.return_value.stdout += f"Signer #2 certificate SHA-256 digest: {DIGEST}\n"
        with self.assertRaises(ValueError):
            release.signing_digest(Path("app-full-debug.apk"))

    @patch.object(release, "find_android_tool", return_value=Path("test-aapt2"))
    @patch.object(release.subprocess, "run")
    def test_actual_apk_identity_is_read_from_binary_manifest(self, run, _tool):
        run.return_value.stdout = "package: name='com.openandroidintelligence.mobile' versionCode='3970' versionName='2.1.1-dev.397+1234567890ab' platformBuildVersionName='16'\n"
        metadata = self.metadata()
        actual = release.apk_identity(Path("app-full-debug.apk"))
        release.verify_apk_identity(metadata, actual)
        self.assertEqual(actual["versionCode"], 3970)
        self.assertTrue(run.call_args.kwargs["check"])
        run.return_value.stdout = "package: name='com.openandroidintelligence.mobile' versionCode='' versionName='2.1.1'\n"
        self.assertEqual(release.apk_identity(Path("app-full-debug.apk"))["versionCode"], 0)
        run.return_value.stdout = "未能读取包信息"
        with self.assertRaises(ValueError):
            release.apk_identity(Path("app-full-debug.apk"))

    def test_stale_apk_cannot_be_published_with_new_output_metadata(self):
        metadata = self.metadata()
        stale_identity = {"applicationId": metadata["applicationId"], "versionCode": 1, "versionName": "2.1.1"}
        with self.assertRaisesRegex(ValueError, "真实包名或版本"):
            release.verify_apk_identity(metadata, stale_identity)

    def test_rolling_release_waits_for_apk_upload_before_publishing_manifest(self):
        # 官方 action 的 preserve_order=true 分支逐个等待上传；APK 失败必须阻止新清单发布。
        workflow = (release.REPOSITORY_ROOT / ".github/workflows/dev-release.yml").read_text(encoding="utf-8")
        action = "uses: softprops/action-gh-release@v2"
        self.assertEqual(workflow.count(action), 1)
        release_step = re.split(r"\n      - ", workflow[workflow.index(action):], maxsplit=1)[0]
        self.assertRegex(release_step, r"(?m)^          preserve_order: true\s*$")
        files = re.search(r"(?m)^          files: \|\n((?:            [^\n]+\n?)+)", release_step)
        self.assertIsNotNone(files)
        self.assertEqual(
            [Path(line.strip()).name for line in files.group(1).splitlines()],
            ["app-full-debug.apk", "oai-update.json"],
        )

    def test_prepare_cli_records_one_consistent_build_environment(self):
        workspace = Path(tempfile.mkdtemp(prefix="open-android-intelligence-release-test-"))
        metadata_path = workspace / "metadata.json"
        environment_path = workspace / "github-env"
        result = subprocess.run([
            "python3", str(Path(release.__file__)), "prepare", "--tag", "v2.2.0-beta.1",
            "--metadata", str(metadata_path), "--github-env", str(environment_path),
        ], capture_output=True, text=True, check=True)
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        environment = dict(line.split("=", 1) for line in environment_path.read_text(encoding="utf-8").splitlines())
        self.assertEqual(environment["OAI_VERSION_NAME"], metadata["versionName"])
        self.assertEqual(environment["OAI_VERSION_CODE"], str(metadata["versionCode"]))
        self.assertEqual(environment["OAI_GIT_COMMIT"], metadata["commit"])
        self.assertEqual(environment["OAI_UPDATE_CHANNEL"], "preview")
        self.assertIn("发布版本", result.stdout)

    @patch.object(release, "git_value", return_value="true")
    def test_prepare_rejects_shallow_checkout_before_writing_outputs(self, _git):
        with self.assertRaisesRegex(ValueError, "完整 Git 历史"):
            release.prepare(argparse.Namespace())

    def test_preview_notes_explain_both_sources_and_signing_boundary(self):
        metadata = {**self.metadata(), "assetName": "app-full-debug.apk"}
        notes = release.release_notes(metadata, "1rua/open-android-intelligence")
        self.assertIn("开发预览测试版", notes)
        self.assertIn("带版本号发布的测试版", notes)
        self.assertIn("公开调试签名", notes)
        private_notes = release.release_notes({**metadata, "channel": "preview"}, "1rua/open-android-intelligence")
        self.assertIn("无法直接覆盖安装", private_notes)


if __name__ == "__main__":
    unittest.main()
