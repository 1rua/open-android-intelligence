#!/usr/bin/env python3
"""Host-side tests for the active Android MVP architecture boundary.

These tests intentionally do not require an Android SDK, Gradle, or a JVM.  They
check the source-level contract that must remain true even when the full Android
build is unavailable in CI.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = (
    ROOT / "core-model" / "src" / "main",
    ROOT / "capability-ports" / "src" / "main",
    ROOT / "control-ports" / "src" / "main",
    ROOT / "artifact-ports" / "src" / "main",
)


class TransportBoundaryTest(unittest.TestCase):
    def test_expected_modules_and_sources_exist(self):
        expected = (
            ROOT / "settings.gradle.kts",
            ROOT / "build.gradle.kts",
            ROOT / "app" / "build.gradle.kts",
            ROOT / "assistant-holder" / "build.gradle.kts",
            ROOT / "gradle" / "mvp-forbidden-surfaces.gradle.kts",
            ROOT / "artifact-ports" / "build.gradle.kts",
            ROOT / "artifact-ports" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "artifact" / "ArtifactSelectionPorts.kt",
            ROOT / "core-model" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "core" / "model" / "TransportContracts.kt",
        )
        missing = [str(path.relative_to(ROOT)) for path in expected if not path.is_file()]
        self.assertEqual([], missing)

    def test_gradle_wrapper_uses_pinned_distribution(self):
        properties = (ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(
            encoding="utf-8"
        )
        self.assertIn("gradle-8.12-bin.zip", properties)
        self.assertTrue((ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar").is_file())

    def test_closed_contract_modules_are_registered_and_scanned(self):
        """A source-only module must still be compiled and covered by no-VPN CI.

        Leaving a contract directory out of settings makes its Kotlin code
        invisible to Gradle, while omitting it from the root gate lets a later
        adapter add a forbidden network surface without being rejected.
        """
        settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        for module in ("artifact-ports", "capability-ports", "control-ports"):
            self.assertIn(f'":{module}"', settings, module)

    def test_no_vpn_gate_covers_production_sources(self):
        forbidden = re.compile(
            r"VpnService|BIND_VPN_SERVICE|TunInterface|\bTUN\b|addRoute|setHttpProxy|ProxyInfo|LocalAPI|\bListen\s*\(|\bDial\s*\(",
            re.IGNORECASE,
        )
        violations = []
        for root in PRODUCTION:
            for path in root.rglob("*"):
                if path.is_file() and path.suffix in {".kt", ".java", ".xml"}:
                    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                        if forbidden.search(line):
                            violations.append(f"{path.relative_to(ROOT)}:{line_no}: {line.strip()}")
        self.assertEqual([], violations)

    def test_no_vpn_gate_rejects_common_socket_and_http_surfaces(self):
        gate = (ROOT / "gradle" / "mvp-forbidden-surfaces.gradle.kts").read_text(encoding="utf-8")
        for token in ("Socket", "ServerSocket", "DatagramSocket", "URLConnection", "WebSocket", "HttpClient"):
            self.assertIn(token, gate)

    def test_transport_interfaces_do_not_accept_endpoints(self):
        source = (ROOT / "core-model" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "core" / "model" / "TransportContracts.kt").read_text(encoding="utf-8")
        interface_block = re.search(r"interface PairedBridgeTransport.*?^}\s*$", source, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(interface_block)
        block = interface_block.group(0)
        self.assertIn("open(binding: VerifiedPairingTransportBinding)", block)
        self.assertNotRegex(block, r"\b(host|port|url|socket|route|dns|endpoint)\b")

    def test_package_namespaces_are_distinct(self):
        app_build = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        holder_build = (ROOT / "assistant-holder" / "build.gradle.kts").read_text(encoding="utf-8")
        app_namespaces = re.findall(r'namespace\s*=\s*"([^"]+)"', app_build)
        holder_namespaces = re.findall(r'namespace\s*=\s*"([^"]+)"', holder_build)
        app_ids = re.findall(r'applicationId\s*=\s*"([^"]+)"', app_build)
        self.assertEqual(["com.openandroidintelligence.mobile"], app_namespaces)
        self.assertEqual(["com.openandroidintelligence.assistant"], holder_namespaces)
        self.assertEqual(["com.openandroidintelligence.mobile"], app_ids)
        self.assertNotEqual(app_namespaces[0], holder_namespaces[0])

    def test_assistant_holder_exposes_only_the_default_assistant_entry_seam(self):
        holder_source = "\n".join(path.read_text(encoding="utf-8") for path in (ROOT / "assistant-holder" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "assistant").glob("*.kt"))
        self.assertIn("AssistantVoiceService", holder_source)
        self.assertIn("AssistantSessionService", holder_source)

    def test_no_active_modules_depend_on_legacy_tailnet(self):
        for module in ("app", "assistant-holder", "core-model", "policy-engine", "notification-collector", "encrypted-store"):
            source = (ROOT / module / "build.gradle.kts").read_text(encoding="utf-8")
            production_dependencies = re.findall(r"^\s*implementation\((.+)\)", source, re.MULTILINE)
            self.assertNotIn('project(":tailnet-core")', production_dependencies, module)
            self.assertNotIn('project(":transport")', production_dependencies, module)


class ProductionBoundaryTest(unittest.TestCase):
    def test_legacy_tailnet_modules_are_not_in_active_android_tree(self):
        self.assertFalse((ROOT / "tailnet-core").exists())
        self.assertFalse((ROOT / "transport").exists())


if __name__ == "__main__":
    unittest.main()
