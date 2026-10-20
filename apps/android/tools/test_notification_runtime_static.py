#!/usr/bin/env python3
"""SDK-free checks for the notification listener/runtime composition seam."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
COLLECTOR = ROOT / "notification-collector/src/main/kotlin/com/openandroidintelligence/notifications"
RUNTIME = COLLECTOR / "NotificationRuntime.kt"
SERVICE = COLLECTOR / "AndroidNotificationCollector.kt"
APPLICATION = ROOT / "app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
KEY_PROVIDER = ROOT / "encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/AndroidKeystoreOutboxKeyProvider.kt"
HOST = ROOT / "notification-host/src/main/kotlin/com/openandroidintelligence/notification/host/NotificationHostInstaller.kt"
BOOTSTRAP = ROOT / "notification-host/src/main/kotlin/com/openandroidintelligence/notification/host/NotificationControlBootstrapProvider.kt"


class NotificationRuntimeStaticTest(unittest.TestCase):
    def read(self, path: Path) -> str:
        self.assertTrue(path.is_file(), path)
        return path.read_text(encoding="utf-8")

    def test_runtime_has_typed_outbox_injection_and_lifecycle(self):
        source = self.read(RUNTIME) + "\n" + self.read(SERVICE)
        self.assertIn("class NotificationRuntime", source)
        self.assertIn("NotificationOutbox", source)
        self.assertIn("observeAutoSend", source)
        self.assertIn("enqueueAccepted", source)
        self.assertIn("fun start", source)
        self.assertIn("fun stop", source)
        self.assertIn("CoroutineScope", source)
        self.assertIn("SupervisorJob", source)
        self.assertIn("import kotlinx.coroutines.CoroutineScope", source)
        self.assertIn("import kotlinx.coroutines.Dispatchers", source)
        self.assertIn("import kotlinx.coroutines.SupervisorJob", source)
        self.assertIn("import kotlinx.coroutines.cancel", source)
        self.assertIn("NotificationAuthorization", source)
        self.assertIn("AuthorizationDecision.deny", source)
        self.assertIn("NotificationRuntimeFactory", source)
        self.assertIn("NotificationRuntimeFactoryRegistry", source)

    def test_listener_lifecycle_creates_and_stops_runtime(self):
        source = self.read(SERVICE)
        self.assertIn("override fun onCreate()", source)
        self.assertIn("NotificationRuntimeFactoryRegistry", source)
        self.assertIn("runtime", source)
        self.assertIn("runtime?.start", source)
        self.assertIn("override fun onDestroy()", source)
        self.assertIn("runtime?.stop", source)
        self.assertIn("installCollector", source)

    def test_runtime_module_installs_no_backup_keystore_and_paired_bridge_composition(self):
        application = self.read(APPLICATION)
        host = self.read(HOST)
        manifest = self.read(MANIFEST)
        key_provider = self.read(KEY_PROVIDER)
        self.assertIn('android:name=".OpenAndroidIntelligenceApplication"', manifest)
        self.assertIn('android:allowBackup="false"', manifest)
        # The modular App deliberately cannot import collectors. Their
        # ContentProvider composes the host before Application.onCreate.
        self.assertIn('runtimeOnly(project(":notification-host"))', self.read(ROOT / "app/build.gradle.kts"))
        self.assertNotIn("import com.openandroidintelligence.notifications.", application)
        self.assertIn("NotificationHostInstaller.install", self.read(BOOTSTRAP))
        self.assertIn("noBackupFilesDir", host)
        self.assertIn("FileNotificationPolicyPersistence", host)
        self.assertIn("PersistentNotificationPolicyAuthority", host)
        self.assertIn("FileEncryptedOutboxPersistence", host)
        self.assertIn("AndroidKeyStoreOutboxKeySource", host)
        self.assertIn("NotificationOutboxStore", host)
        self.assertIn("NotificationBridgeDispatcher", host)
        self.assertIn("PairedBridgeTransport", host)
        self.assertIn("NotificationRuntimeFactoryRegistry.install", host)
        self.assertIn('KeyStore.getInstance("AndroidKeyStore")', key_provider)
        self.assertIn('KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")', key_provider)

    def test_application_composition_has_no_public_or_generic_network_fallback(self):
        source = self.read(APPLICATION) + "\n" + self.read(KEY_PROVIDER) + "\n" + self.read(HOST)
        forbidden = re.compile(
            r"VpnService|BIND_VPN_SERVICE|Socket|ServerSocket|DatagramSocket|"
            r"URLConnection|WebSocket|HttpClient|OkHttp|\bhost\s*:|\bport\s*:|"
            r"\bendpoint\s*:|URL\s*\(|ProcessBuilder|Runtime\.getRuntime",
            re.IGNORECASE,
        )
        self.assertEqual([], [line for line in source.splitlines() if forbidden.search(line)])

    def test_runtime_has_no_network_or_generic_execution_surface(self):
        source = self.read(RUNTIME) + "\n" + self.read(SERVICE)
        forbidden = re.compile(
            r"VpnService|BIND_VPN_SERVICE|Socket|ServerSocket|DatagramSocket|"
            r"URLConnection|WebSocket|HttpClient|OkHttp|ProcessBuilder|"
            r"Runtime\.getRuntime|sendBroadcast|startService|bindService",
            re.IGNORECASE,
        )
        self.assertEqual([], [line for line in source.splitlines() if forbidden.search(line)])


if __name__ == "__main__":
    unittest.main()
