import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import call, patch

import app_release_metadata as release


COMMIT = "1234567890abcdef1234567890abcdef12345678"
DIGEST = "ab" * 32


class AppReleaseMetadataTest(unittest.TestCase):
    def metadata(self, channel="development", count=397, run_number=None, version="2.1.1", minimum_version_code=None):
        return release.release_metadata(version, COMMIT, count, channel, run_number, minimum_version_code)

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
        self.assertEqual(preview["versionName"], "2.1.1-dev")
        self.assertEqual(preview["channel"], "preview")
        self.assertEqual(preview["versionCode"], 3971)
        self.assertEqual(stable["versionName"], "2.1.1")
        self.assertEqual(stable["channel"], "stable")
        self.assertEqual(stable["versionCode"], 3972)

    def test_package_version_changes_are_reflected_without_reading_a_tag(self):
        self.assertEqual(self.metadata(channel="stable", version="2.2.0")["versionName"], "2.2.0")

    @patch.object(release, "extract_package_version", return_value="2.1.1")
    def test_build_metadata_uses_the_android_package_version_source(self, extract_version):
        self.assertEqual(release.read_base_version(), "2.1.1")
        extract_version.assert_called_once_with(release.REPOSITORY_ROOT, platform="android")

    def test_version_code_orders_channels_and_later_commits(self):
        codes = [
            self.metadata(channel="development")["versionCode"],
            self.metadata(channel="preview", run_number=1)["versionCode"],
            self.metadata(channel="stable")["versionCode"],
            self.metadata(channel="development", count=398)["versionCode"],
        ]
        self.assertEqual(codes, sorted(set(codes)))

    def test_preview_version_code_must_exceed_latest_stable_high_water(self):
        stable_high_water = self.metadata(channel="stable", count=457)["versionCode"]
        with self.assertRaisesRegex(ValueError, "exceed the latest stable versionCode"):
            self.metadata(channel="preview", count=457, run_number=43, minimum_version_code=stable_high_water)
        self.assertEqual(
            self.metadata(channel="preview", count=458, run_number=44, minimum_version_code=stable_high_water)["versionCode"],
            4581,
        )

    def test_prepare_uses_the_greatest_main_or_dev_history_for_stable_version_code(self):
        dev_sha = "abcdef0123456789abcdef0123456789abcdef02"
        for target_count, dev_count, expected_count in (
            (456, 457, 457),
            (458, 457, 458),
            (456, 459, 459),
        ):
            git_output = ("false", dev_sha, str(target_count), str(dev_count), COMMIT)
            expected_calls = [
                call("rev-parse", "--is-shallow-repository"),
                call("rev-parse", "--verify", f"{dev_sha}^{{commit}}"),
                call("rev-list", "--count", "HEAD"),
                call("rev-list", "--count", dev_sha),
                call("rev-parse", "HEAD"),
            ]
            metadata_path = Path(tempfile.mkdtemp(prefix="open-android-intelligence-release-test-")) / "metadata.json"
            with patch.object(release, "read_base_version", return_value="2.1.1"), patch.object(
                release, "git_value", side_effect=git_output,
            ) as git_value:
                release.prepare(argparse.Namespace(
                    channel="stable",
                    run_number=None,
                    history_sha=[dev_sha],
                    metadata=metadata_path,
                    github_env=None,
                ))

            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            self.assertEqual(metadata["versionCode"], expected_count * release.VERSION_CODE_CHANNEL_SCALE + release.CHANNEL_PHASE["stable"])
            self.assertEqual(git_value.call_args_list, expected_calls)

    def test_prepare_uses_head_history_for_preview_version_code(self):
        metadata_path = Path(tempfile.mkdtemp(prefix="open-android-intelligence-release-test-")) / "metadata.json"
        with patch.object(release, "read_base_version", return_value="2.1.1"), patch.object(
            release, "git_value", side_effect=("false", "457", COMMIT),
        ) as git_value:
            release.prepare(argparse.Namespace(
                channel="preview",
                run_number=42,
                history_sha=[],
                metadata=metadata_path,
                github_env=None,
            ))
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        self.assertEqual(metadata["versionCode"], 4571)
        self.assertEqual(git_value.call_args_list, [
            call("rev-parse", "--is-shallow-repository"),
            call("rev-list", "--count", "HEAD"),
            call("rev-parse", "HEAD"),
        ])

    def test_prepare_rejects_invalid_version_code_history_sha(self):
        with patch.object(release, "git_value", return_value="false"), patch.object(
            release, "read_base_version", return_value="2.1.1",
        ):
            with self.assertRaisesRegex(ValueError, "full Git SHAs"):
                release.prepare(argparse.Namespace(
                    channel="stable", run_number=None, history_sha=["short"],
                    metadata=Path("unused.json"), github_env=None,
                ))

    def test_invalid_versions_channels_and_run_numbers_are_rejected(self):
        for version in ("2.1", "02.1.1", "2.1.1-beta.1", "2.1.1+build", "2.1.1\n"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                self.metadata(channel="stable", version=version)
        for channel in ("unknown",):
            with self.subTest(channel=channel), self.assertRaises(ValueError):
                self.metadata(channel=channel)

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
        self.assertEqual(metadata["versionName"], "2.2.1-dev")

    @patch.object(release, "git_value", return_value="true")
    def test_prepare_rejects_shallow_checkout_before_writing_outputs(self, _git):
        with self.assertRaisesRegex(ValueError, "complete Git history"):
            release.prepare(argparse.Namespace())

    def test_workflows_are_branch_scoped_and_use_package_versions(self):
        dev_workflow = (release.REPOSITORY_ROOT / ".github/workflows/dev-release.yml").read_text(encoding="utf-8")
        self.assertIn("branches: [dev]", dev_workflow)
        self.assertIn("extract_package_version.py --platform android", dev_workflow)
        self.assertIn("git merge-base --is-ancestor origin/main HEAD", dev_workflow)
        self.assertIn("Resolve stable version-code high-water", dev_workflow)
        self.assertIn("--minimum-version-code", dev_workflow)
        self.assertIn("group: dev-release", dev_workflow)
        self.assertIn("cancel-in-progress: false", dev_workflow)
        self.assertIn("queue: max", dev_workflow)
        release_contract = (release.REPOSITORY_ROOT / "docs/contracts/app-update-release-v1.md").read_text(encoding="utf-8")
        self.assertIn("最多保留 100 个等待运行", release_contract)
        self.assertIn("contents: write", dev_workflow)
        # 发布说明需要读取关联 PR 正文，因此保留 PR 读取权限。
        self.assertIn("pull-requests: read", dev_workflow)
        # dev 预览版只由 push 触发。
        #
        # 不再使用 pull_request_target：它执行的是默认分支 main 上的工作流定义，
        # 却 checkout dev 的合并提交。main 与 dev 的脚本命名一旦不同步，
        # 这条 run 必然失败，且失败与被合并的代码无关。
        #
        # 断言只检查「on:」块内的实际触发器，不扫全文：
        # 说明性注释里出现触发器名是合法且必要的。
        dev_triggers = dev_workflow.split("on:", 1)[1].split("permissions:", 1)[0]
        self.assertNotIn("pull_request_target", dev_triggers)
        self.assertIn("push:", dev_triggers)
        self.assertIn("workflow_dispatch", dev_triggers)
        # 同理，PR 检出豁免与「跳过已合并 PR 的 push」逻辑都已随该触发器移除。
        self.assertNotIn("allow-unsafe-pr-checkout:", dev_workflow)
        self.assertNotIn("should-release", dev_workflow)
        self.assertIn("sign-plugin-packages:", dev_workflow)
        self.assertIn("actions/download-artifact@v8", dev_workflow)
        self.assertIn("signed-plugins-$GITHUB_RUN_ATTEMPT", dev_workflow)
        self.assertIn("preview-release-assets-$GITHUB_RUN_ATTEMPT", dev_workflow)
        self.assertIn("needs.sign-plugin-packages.outputs.signed-artifact-name", dev_workflow)
        build_job = dev_workflow.split("  build-preview:", 1)[1].split("  publish-preview:", 1)[0]
        self.assertIn("permissions:\n      contents: read", build_job)
        self.assertNotIn("pull-requests: read", build_job)
        publish_job = dev_workflow.split("  publish-preview:", 1)[1]
        self.assertIn("permissions:\n      contents: write", publish_job)
        self.assertNotIn("pull-requests: read", publish_job)
        self.assertIn("v${{ needs.build-preview.outputs.package-version }}-dev", dev_workflow)
        self.assertIn("Move dev tag to published commit", dev_workflow)
        self.assertIn("prerelease: true", dev_workflow)
        self.assertIn('make_latest: "false"', dev_workflow)
        self.assertIn("body_path: ${{ runner.temp }}/release-assets/release-notes.md", dev_workflow)
        self.assertNotIn("--tag", dev_workflow)

        release_workflow = (release.REPOSITORY_ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch", release_workflow)
        self.assertNotIn("pull_request_target", release_workflow)
        self.assertNotIn("allow-unsafe-pr-checkout: true", release_workflow)
        self.assertIn("refs/heads/main", release_workflow)
        self.assertIn("group: dev-release", release_workflow)
        self.assertIn("extract_package_version.py --platform android", release_workflow)
        self.assertIn("cancel-in-progress: false", release_workflow)
        self.assertIn("queue: max", release_workflow)
        self.assertIn("contents: write", release_workflow)
        self.assertIn("pull-requests: read", release_workflow)
        self.assertIn("sign-plugin-packages:", release_workflow)
        self.assertIn("signed-plugins-$GITHUB_RUN_ATTEMPT", release_workflow)
        self.assertIn("stable-apk-inputs-$GITHUB_RUN_ATTEMPT", release_workflow)
        self.assertIn("stable-release-assets-$GITHUB_RUN_ATTEMPT", release_workflow)
        stable_build_job = release_workflow.split("  build-stable-apk:", 1)[1].split("  verify-stable-apk:", 1)[0]
        self.assertIn("permissions:\n      contents: read", stable_build_job)
        self.assertNotIn("pull-requests: read", stable_build_job)
        self.assertIn("Resolve dev version-code history", stable_build_job)
        self.assertIn('+refs/heads/dev:refs/remotes/origin/dev', stable_build_job)
        self.assertIn("max(commit_counts)", (release.REPOSITORY_ROOT / "apps/android/tools/app_release_metadata.py").read_text(encoding="utf-8"))
        self.assertIn("--history-sha", stable_build_job)
        stable_verify_job = release_workflow.split("  verify-stable-apk:", 1)[1].split("  publish-stable:", 1)[0]
        self.assertIn("permissions:\n      contents: read\n      pull-requests: read", stable_verify_job)
        stable_publish_job = release_workflow.split("  publish-stable:", 1)[1]
        self.assertIn("permissions:\n      contents: write", stable_publish_job)
        self.assertNotIn("pull-requests: read", stable_publish_job)
        self.assertLess(release_workflow.index("build-stable-apk:"), release_workflow.index("verify-stable-apk:"))
        self.assertLess(release_workflow.index("verify-stable-apk:"), release_workflow.index("publish-stable:"))
        self.assertIn("workflow_dispatch", release_workflow)
        self.assertIn("v${{ needs.build-stable-apk.outputs.package-version }}", release_workflow)
        self.assertIn("git/refs/tags/", release_workflow)
        self.assertIn("-F force=true", release_workflow)
        self.assertIn("prerelease: false", release_workflow)
        self.assertIn('make_latest: "true"', release_workflow)
        self.assertIn("overwrite_files: true", release_workflow)
        self.assertIn("body_path: ${{ runner.temp }}/release-assets/release-notes.md", release_workflow)
        self.assertLess(
            release_workflow.index("Publish or replace the stable release"),
            release_workflow.index("Move stable tag to published commit"),
        )
        self.assertIn("CURRENT_MAIN_SHA", release_workflow.split("Move stable tag to published commit", 1)[1])
        self.assertNotIn("--tag", release_workflow)


class ReleaseNotesForPushEventTest(unittest.TestCase):
    """dev 预览版由 push 触发，发布说明要优先取关联 PR 正文。

    背景：合并 PR 会产生 push 事件，该 push 是唯一发布入口。
    原先另挂 pull_request_target 来读 PR 正文，但它执行默认分支 main 上的
    工作流定义、却检出 dev 的合并提交，两边脚本命名不同步时必然失败。
    现在改由 push 路径直接查询关联 PR，查不到再回退提交说明。
    """

    def _write_event(self, payload, directory):
        path = Path(directory) / "event.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def test_push_event_prefers_the_merged_pull_request_body(self):
        import prepare_release_notes as notes

        with tempfile.TemporaryDirectory() as directory:
            event_path = self._write_event({"pull_request": None}, directory)
            with patch.object(notes, "_dispatch_pull_request_body", return_value="## PR 正文") as lookup:
                body = notes.prepare_release_notes(
                    "dev", "push", event_path, "owner/repo", COMMIT,
                )
            lookup.assert_called_once_with("owner/repo", COMMIT, "dev")
            self.assertEqual(body, "## PR 正文")

    def test_push_event_falls_back_to_commit_message_without_a_pull_request(self):
        import prepare_release_notes as notes

        with tempfile.TemporaryDirectory() as directory:
            event_path = self._write_event({"pull_request": None}, directory)
            with patch.object(notes, "_dispatch_pull_request_body", return_value=""):
                with patch.object(notes.subprocess, "run") as run:
                    run.return_value = subprocess.CompletedProcess(
                        args=[], returncode=0, stdout="直接推送的提交说明",
                    )
                    body = notes.prepare_release_notes(
                        "dev", "push", event_path, "owner/repo", COMMIT,
                    )
            self.assertEqual(body, "直接推送的提交说明")

    def test_push_event_survives_a_failed_pull_request_lookup(self):
        """查不到 PR（例如维护者直接 push）不能让发布失败。"""
        import prepare_release_notes as notes

        with tempfile.TemporaryDirectory() as directory:
            event_path = self._write_event({"pull_request": None}, directory)
            failure = subprocess.CalledProcessError(1, ["gh", "api"])
            with patch.object(notes, "_dispatch_pull_request_body", side_effect=failure):
                with patch.object(notes.subprocess, "run") as run:
                    run.return_value = subprocess.CompletedProcess(
                        args=[], returncode=0, stdout="回退的提交说明",
                    )
                    body = notes.prepare_release_notes(
                        "dev", "push", event_path, "owner/repo", COMMIT,
                    )
            self.assertEqual(body, "回退的提交说明")


if __name__ == "__main__":
    unittest.main()
