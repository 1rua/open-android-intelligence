#!/usr/bin/env python3
"""Host-side tests for the archived Android Tailnet transport boundary.

These tests preserve the historical checks for the Tailnet userspace core and
transport modules that were decoupled and moved to legacy/android-tailnet/.
"""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = (
    ROOT / "tailnet-core" / "src" / "main",
    ROOT / "transport" / "src" / "main",
)


class ArchivedTailnetBoundaryTest(unittest.TestCase):
    def test_archived_tailnet_sources_exist(self):
        expected = (
            ROOT / "tailnet-core" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "tailnet" / "core" / "VerifiedPairingTransportBinding.kt",
            ROOT / "tailnet-core" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "tailnet" / "core" / "TailscaleUserspaceCore.kt",
            ROOT / "tailnet-core" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "tailnet" / "core" / "PairingReconnectState.kt",
            ROOT / "transport" / "src" / "testFixtures" / "kotlin" / "com" / "openandroidintelligence" / "transport" / "FakeUserspaceTransport.kt",
            ROOT / "transport" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "transport" / "TsnetPairedBridgeTransport.kt",
            ROOT / "transport" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "transport" / "PairedBridgeSessionCoordinator.kt",
        )
        missing = [str(path.relative_to(ROOT)) for path in expected if not path.is_file()]
        self.assertEqual([], missing)

    def test_fake_and_real_adapter_share_pairing_binding_and_generation(self):
        fake = (ROOT / "transport" / "src" / "testFixtures" / "kotlin" / "com" / "openandroidintelligence" / "transport" / "FakeUserspaceTransport.kt").read_text(encoding="utf-8")
        real = (ROOT / "transport" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "transport" / "TsnetPairedBridgeTransport.kt").read_text(encoding="utf-8")
        for source in (fake, real):
            self.assertIn("PairedBridgeTransport", source)
            self.assertIn("VerifiedPairingTransportBinding", source)
            self.assertIn("connectionGeneration", source)

    def test_transport_depends_directly_on_tailnet_core(self):
        transport = (ROOT / "transport" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertRegex(transport, r'(?:implementation|api)\(project\(":tailnet-core"\)\)')


class ArchivedProductionBoundaryTest(unittest.TestCase):
    def test_fake_is_never_in_production_sources(self):
        main_fake = ROOT / "transport" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "transport" / "FakeUserspaceTransport.kt"
        self.assertFalse(main_fake.exists())
        fixture = ROOT / "transport" / "src" / "testFixtures" / "kotlin" / "com" / "openandroidintelligence" / "transport" / "FakeUserspaceTransport.kt"
        self.assertTrue(fixture.is_file())

    def test_sealed_production_factory_exists(self):
        factory = ROOT / "transport" / "src" / "main" / "kotlin" / "com" / "openandroidintelligence" / "transport" / "ProductionTailnetTransportFactory.kt"
        self.assertTrue(factory.is_file())
        text = factory.read_text(encoding="utf-8")
        self.assertIn("sealed interface ProductionPairedBridgeTransport", text)
        self.assertIn("ProductionTailnetTransportFactory", text)
        self.assertNotIn("FakeUserspaceTransport", text)

    def test_native_adapter_is_the_only_generated_import(self):
        generated = []
        for path in (ROOT / "tailnet-core" / "src" / "main").rglob("*.kt"):
            text = path.read_text(encoding="utf-8")
            if "import tsnetbridge." in text:
                generated.append(path.relative_to(ROOT).as_posix())
        self.assertEqual(
            ["tailnet-core/src/main/kotlin/com/openandroidintelligence/tailnet/core/AndroidTsnetBinding.kt"],
            generated,
        )


if __name__ == "__main__":
    unittest.main()
