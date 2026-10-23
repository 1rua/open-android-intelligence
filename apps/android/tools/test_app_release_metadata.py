import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import app_release_metadata as release


COMMIT = "1234567890abcdef1234567890abcdef12345678"
DIGEST = "ab" * 32


class AppReleaseMetadataTest(unittest.TestCase):
    def metadata(self, channel="development", count=397, run_number=None, version="2.1.1"):
        return release.release_metadata(version, COMMIT, count, channel, run_number)

    def output_metadata(self, metadata):
        return {
            "applicationId": metadata["applicationId"],
            "elements": [{
                "versionName": metadata["versionName"],
                "versionCode": metadata["versionCode"],
                "outputFile": "app-full-debug.apk",
            }],
        }

    def test_development_metadata_uses_the_package_version_and_commit_identity(self):
        first = self.metadata()
        self.assertEqual(first, self.metadata())
        self.assertEqual(first["versionName"], "2.1.1-dev.397+1234567890ab")
        self.assertEqual(first["versionCode"], 3970)
        self.assertEqual(first["channel"], "development")

    def test_preview_and_stable_versions_are_derived_from_package_version(self):
        preview = self.metadata(channel="preview", run_number=42)
        stable = self.metadata(channel="stable")
        self.assertEqual(preview["versionName"], "2.1.1-dev.42")
        self.assertEqual(preview["channel"], "preview")
        self.assertEqual(preview["versionCode"], 3971)
        self.assertEqual(stable["versionName"], "2.1.1")
        self.assertEqual(stable["channel"], "stable")
        self.assertEqual(stable["versionCode"], 3972)

    def test_package_version_changes_are_reflected_without_reading_a_tag(self):
        self.assertEqual(self.metadata(channel="stable", version="2.2.0")["versionName"], "2.2.0")

    def test_version_code_orders_channels_and_later_commits(self):
        codes = [
            self.metadata(channel="development")["versionCode"],
            self.metadata(channel="preview", run_number=1)["versionCode"],
            self.metadata(channel="stable")["versionCode"],
            self.metadata(channel="development", count=398)["versionCode"],
        ]
        self.assertEqual(codes, sorted(set(codes)))

    def test_invalid_versions_channels_and_run_numbers_are_rejected(self):
        for version in ("2.1", "02.1.1", "2.1.1-beta.1", "2.1.1+build", "2.1.1\n"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                self.metadata(channel="stable", version=version)
        for channel, run_number in (("unknown", None), ("preview", None), ("preview", 0)):
            with self.subTest(channel=channel, run_number=run_number), self.assertRaises(ValueError):
                self.metadata(channel=channel, run_number=run_number)

    def test_invalid_history_and_commit_are_rejected(self):
        for count, commit in ((0, COMMIT), (397, "1234567"), (210_000_001, COMMIT)):
            with self.subTest(count=count, commit=commit), self.assertRaises(ValueError):
                release.release_metadata("2.1.1", commit, count, "stable")

    def test_manifest_binds_install_code_name_and_certificate(self):
        metadata = self.metadata()
        manifest = release.verified_manifest(metadata, self.output_metadata(metadata), Path("app-full-debug.apk"), DIGEST)
        self.assertEqual(manifest["assetName"], "app-full-debug.apk")
        self.assertEqual(manifest["signingCertificateSha256"], DIGEST)
        self.assertEqual(manifest["distribution"], "full")
        self.assertEqual(manifest["schemaVersion"], 1)
        self.assertEqual(manifest["commit"], COMMIT)

    def test_manifest_rejects_wrong_apk_identity_version_splits_and_digest(self):
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
        for elements in ([], self.output_metadata(metadata)["elements"] * 2):
            with self.subTest(elements=elements), self.assertRaises(ValueError):
                release.verified_manifest(metadata, {"elements": elements}, Path("app-full-debug.apk"), DIGEST)
        with self.assertRaises(ValueError):
            release.verified_manifest(metadata, self.output_metadata(metadata), Path("app-full-debug.apk"), DIGEST.upper())

    @patch.object(release, "find_android_tool", return_value=Path("test-apksigner"))
    @patch.object(release.subprocess, "run")
    def test_certificate_digest_is_normalized_and_multiple_signers_are_rejected(self, run, _signer):
        run.return_value.stdout = f"Signer #1 certificate SHA-256 digest: {DIGEST.upper()}\n"
        self.assertEqual(release.signing_digest(Path("app-full-debug.apk")), DIGEST)
        run.return_value.stdout += f"Signer #2 certificate SHA-256 digest: {DIGEST}\n"
        with self.assertRaises(ValueError):
            release.signing_digest(Path("app-full-debug.apk"))

    @patch.object(release, "find_android_tool", return_value=Path("test-apksigner"))
    @patch.object(release.subprocess, "run")
    def test_certificate_accepts_sdk37_signer_format_and_ignores_other_digests(self, run, _signer):
        run.return_value.stdout = (
            f"V3.0 Signer: certificate SHA-256 digest: {DIGEST.upper()}\n"
            f"V3.0 Signer: public key SHA-256 digest: {'cd' * 32}\n"
            f"Source Stamp Signer: certificate SHA-256 digest: {'ef' * 32}\n"
        )
        self.assertEqual(release.signing_digest(Path("app-full-debug.apk")), DIGEST)
        run.return_value.stdout = f"V3.0 Signer #1: certificate SHA-256 digest: {DIGEST}\nV3.0 Signer #2: certificate SHA-256 digest: {DIGEST}\n"
        with self.assertRaises(ValueError):
            release.signing_digest(Path("app-full-debug.apk"))

    @patch.object(release, "find_android_tool", return_value=Path("test-aapt2"))
    @patch.object(release.subprocess, "run")
    def test_apk_identity_is_read_from_the_binary_manifest(self, run, _tool):
        metadata = self.metadata()
        run.return_value.stdout = "package: name='com.openandroidintelligence.mobile' versionCode='3970' versionName='2.1.1-dev.397+1234567890ab' platformBuildVersionName='16'\n"
        actual = release.apk_identity(Path("app-full-debug.apk"))
        release.verify_apk_identity(metadata, actual)
        self.assertEqual(actual["versionCode"], 3970)
        self.assertTrue(run.call_args.kwargs["check"])
        run.return_value.stdout = "package: name='com.openandroidintelligence.mobile' versionCode='' versionName='2.1.1'\n"
        self.assertEqual(release.apk_identity(Path("app-full-debug.apk"))["versionCode"], 0)
        run.return_value.stdout = "No package information"
        with self.assertRaises(ValueError):
            release.apk_identity(Path("app-full-debug.apk"))

    def test_prepare_cli_records_one_consistent_build_environment(self):
        workspace = Path(tempfile.mkdtemp(prefix="open-android-intelligence-release-test-"))
        metadata_path = workspace / "metadata.json"
        environment_path = workspace / "github-env"
        subprocess.run([
            "python3", str(Path(release.__file__)), "prepare", "--channel", "preview", "--run-number", "42",
            "--metadata", str(metadata_path), "--github-env", str(environment_path),
        ], capture_output=True, text=True, check=True)
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        environment = dict(line.split("=", 1) for line in environment_path.read_text(encoding="utf-8").splitlines())
        self.assertEqual(environment["OAI_VERSION_NAME"], metadata["versionName"])
        self.assertEqual(environment["OAI_VERSION_CODE"], str(metadata["versionCode"]))
        self.assertEqual(environment["OAI_GIT_COMMIT"], metadata["commit"])
        self.assertEqual(environment["OAI_UPDATE_CHANNEL"], "preview")
        self.assertEqual(metadata["versionName"], "2.1.1-dev.42")

    @patch.object(release, "git_value", return_value="true")
    def test_prepare_rejects_shallow_checkout_before_writing_outputs(self, _git):
        with self.assertRaisesRegex(ValueError, "complete Git history"):
            release.prepare(argparse.Namespace())

    def test_workflows_are_branch_scoped_and_use_package_versions(self):
        dev_workflow = (release.REPOSITORY_ROOT / ".github/workflows/dev-release.yml").read_text(encoding="utf-8")
        self.assertIn("branches: [dev]", dev_workflow)
        self.assertIn("group: dev-release", dev_workflow)
        self.assertIn("cancel-in-progress: false", dev_workflow)
        self.assertIn("contents: write", dev_workflow)
        self.assertIn("pull-requests: read", dev_workflow)
        self.assertIn("allow-unsafe-pr-checkout: true", dev_workflow)
        self.assertIn("commits/$GITHUB_SHA/pulls", dev_workflow)
        self.assertIn("should-release=false", dev_workflow)
        self.assertIn("sign-notification-package:", dev_workflow)
        self.assertIn("actions/download-artifact@v8", dev_workflow)
        self.assertIn("signed-notifications-$GITHUB_RUN_ATTEMPT", dev_workflow)
        self.assertIn("preview-release-assets-$GITHUB_RUN_ATTEMPT", dev_workflow)
        self.assertIn("needs.sign-notification-package.outputs.signed-artifact-name", dev_workflow)
        self.assertIn("v${{ needs.build-preview.outputs.package-version }}-dev.${{ github.run_number }}", dev_workflow)
        self.assertIn("prerelease: true", dev_workflow)
        self.assertIn('make_latest: "false"', dev_workflow)
        self.assertIn("body_path: ${{ runner.temp }}/release-assets/release-notes.md", dev_workflow)
        self.assertNotIn("--tag", dev_workflow)

        release_workflow = (release.REPOSITORY_ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("branches: [main]", release_workflow)
        self.assertIn("contents: write", release_workflow)
        self.assertIn("pull-requests: read", release_workflow)
        self.assertIn("allow-unsafe-pr-checkout: true", release_workflow)
        self.assertIn("sign-notification-package:", release_workflow)
        self.assertIn("signed-notifications-$GITHUB_RUN_ATTEMPT", release_workflow)
        self.assertIn("stable-apk-inputs-$GITHUB_RUN_ATTEMPT", release_workflow)
        self.assertIn("stable-release-assets-$GITHUB_RUN_ATTEMPT", release_workflow)
        self.assertLess(release_workflow.index("build-stable-apk:"), release_workflow.index("verify-stable-apk:"))
        self.assertLess(release_workflow.index("verify-stable-apk:"), release_workflow.index("publish-stable:"))
        self.assertIn("workflow_dispatch", release_workflow)
        self.assertIn("v${{ needs.verify-stable-apk.outputs.package-version }}", release_workflow)
        self.assertIn("git/refs/tags/", release_workflow)
        self.assertIn("-F force=true", release_workflow)
        self.assertIn("prerelease: false", release_workflow)
        self.assertIn('make_latest: "true"', release_workflow)
        self.assertIn("overwrite_files: true", release_workflow)
        self.assertIn("body_path: ${{ runner.temp }}/release-assets/release-notes.md", release_workflow)
        self.assertNotIn("--tag", release_workflow)


if __name__ == "__main__":
    unittest.main()
