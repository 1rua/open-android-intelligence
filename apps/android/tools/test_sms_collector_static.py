#!/usr/bin/env python3
"""Host checks for the narrowly scoped Android SMS collector boundary.

These checks validate registration and prohibited source surfaces only. Kotlin
and TypeScript suites remain the behavioral evidence; device and AAR evidence
must be collected separately.
"""

from pathlib import Path
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ROOT.parents[1]
SMS_ROOT = ROOT / "sms-collector"
SMS_SOURCE_ROOT = SMS_ROOT / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "sms"
INBOX_READER_SOURCE = SMS_SOURCE_ROOT / "AndroidSmsInboxReader.kt"
CAPABILITY_PROVIDER_SOURCE = SMS_SOURCE_ROOT / "AndroidSmsCapabilityProvider.kt"
SETTINGS_SOURCE = SMS_SOURCE_ROOT / "SmsSettingsAuthority.kt"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
SETTINGS_GRADLE = ROOT / "settings.gradle.kts"
APP_BUILD_GRADLE = ROOT / "app" / "build.gradle.kts"
READINESS = REPO_ROOT / "docs" / "mvp" / "sms-read-readiness.md"
VERTICAL_SLICE_CONTRACT = REPO_ROOT / "docs" / "mvp" / "mvp-vertical-slice-contract.md"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class SmsCollectorStaticTest(unittest.TestCase):
    def sms_sources(self) -> dict[Path, str]:
        sources = {path: path.read_text(encoding="utf-8") for path in SMS_SOURCE_ROOT.rglob("*.kt")}
        self.assertNotEqual({}, sources)
        return sources

    def test_sms_source_walk_includes_nested_kotlin_files(self):
        with tempfile.TemporaryDirectory(dir=SMS_SOURCE_ROOT) as directory:
            nested = Path(directory) / "nested" / "NestedSmsBoundary.kt"
            nested.parent.mkdir()
            nested.write_text("package com.openandroidintelligence.sms\n", encoding="utf-8")

            self.assertIn(nested, self.sms_sources())

    def test_sms_collector_is_registered_as_an_android_module(self):
        self.assertIn('":sms-collector"', SETTINGS_GRADLE.read_text(encoding="utf-8"))
        self.assertIn('runtimeOnly(project(":device-primitives"))', APP_BUILD_GRADLE.read_text(encoding="utf-8"))
        self.assertIn('implementation(project(":sms-collector"))', (ROOT / "device-primitives" / "build.gradle.kts").read_text(encoding="utf-8"))
        build = (SMS_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('namespace = "com.openandroidintelligence.sms"', build)

    def test_reader_uses_the_inbox_uri_and_no_other_sms_or_mms_surface(self):
        source = INBOX_READER_SOURCE.read_text(encoding="utf-8")
        self.assertIn("Telephony.Sms.Inbox.CONTENT_URI", source)
        for forbidden_uri in (
            "Telephony.Sms.Sent.CONTENT_URI",
            "Telephony.Sms.Draft.CONTENT_URI",
            "Telephony.Sms.Outbox.CONTENT_URI",
            "Telephony.Mms",
        ):
            self.assertNotIn(forbidden_uri, source)

    def test_manifest_declares_sms_read_and_send_without_exporting_a_receiver(self):
        root = ET.parse(ROOT / "device-primitives" / "src" / "main" / "AndroidManifest.xml").getroot()
        declared_permissions = [
            permission.get(f"{ANDROID_NS}name")
            for permission in root.findall("uses-permission")
        ]
        self.assertIn("android.permission.READ_SMS", declared_permissions)
        self.assertIn("android.permission.SEND_SMS", declared_permissions)
        application = root.find("application")
        self.assertIsNotNone(application)
        self.assertEqual([], application.findall("receiver"))
        # 旧独立 SmsSyncJobService 已不在生产清单中；真实定时入口继续受系统绑定权限保护。
        app = ET.parse(MANIFEST).getroot().find("application")
        for name in (".DevicePushJobService", ".plugins.PluginTimerService"):
            service = next(service for service in app.findall("service") if service.get(f"{ANDROID_NS}name") == name)
            self.assertEqual("false", service.get(f"{ANDROID_NS}exported"))
            self.assertEqual("android.permission.BIND_JOB_SERVICE", service.get(f"{ANDROID_NS}permission"))

    def test_sms_source_has_no_forbidden_platform_or_network_escape_surface(self):
        forbidden = re.compile(
            r"VpnService|\b(?:ServerSocket|DatagramSocket|Socket)\b|"
            r"BroadcastReceiver|ContentObserver|NotificationListenerService|"
            r"AccessibilityService|\b(?:URL|HttpUrl|OkHttpClient)\b|"
            r"https?://|ProcessBuilder|Runtime\.getRuntime|/system/bin/|\bexec\s*\(",
            re.IGNORECASE,
        )
        violations = [
            f"{path.relative_to(ROOT)}:{line}"
            for path, source in self.sms_sources().items()
            for line in source.splitlines()
            if forbidden.search(line) and not (
                path.name == "AndroidSmsSendProvider.kt" and "BroadcastReceiver" in line
                or line.lstrip().startswith(("//", "*", "/**")) and "ContentObserver" in line
            )
        ]
        self.assertEqual([], violations)

    def test_provider_preserves_the_full_body_through_the_authorized_content_normalizer(self):
        source = CAPABILITY_PROVIDER_SOURCE.read_text(encoding="utf-8")
        self.assertEqual(2, source.count('content = normalizeContent(body ?: "", scope)'))

    def test_readiness_packet_records_the_remaining_evidence_boundary(self):
        self.assertTrue(READINESS.is_file(), READINESS)
        self.assertTrue(VERTICAL_SLICE_CONTRACT.is_file(), VERTICAL_SLICE_CONTRACT)

        readiness = " ".join(READINESS.read_text(encoding="utf-8").casefold().split())
        for required_statement in (
            "complete SMS body text",
            "local user authorization",
            "history start and maximum-record settings are local",
            "best-effort",
            "encrypted outbox",
            "mobile.sms.query",
            "hermes",
            "openclaw",
            "android sdk",
            "aar",
            "receive_boot_completed",
        ):
            self.assertIn(required_statement.casefold(), readiness)

        for document in (readiness, " ".join(VERTICAL_SLICE_CONTRACT.read_text(encoding="utf-8").casefold().split())):
            for required_statement in (
                "agentmayrequest",
                "remote agent may request sms reads or subscriptions",
                "does not authorize local periodic auto-send",
                "never mutate local grant, history, maximum records, interval, or modes",
            ):
                self.assertIn(required_statement, document)


if __name__ == "__main__":
    unittest.main()
