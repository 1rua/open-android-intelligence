import json
from pathlib import Path
import plistlib
import tempfile
import unittest

import extract_package_version as extractor


def workspace() -> Path:
    return Path(tempfile.mkdtemp(prefix="oai-package-version-test-"))


class ExtractPackageVersionTest(unittest.TestCase):
    def test_reads_node_react_native_and_electron_package_json(self):
        root = workspace()
        (root / "package.json").write_text(json.dumps({"name": "example", "version": "1.2.3"}), encoding="utf-8")
        self.assertEqual(extractor.extract_package_version(root), "1.2.3")

    def test_reads_flutter_pubspec_version(self):
        root = workspace()
        (root / "pubspec.yaml").write_text("name: example\nversion: 1.2.3+4\n", encoding="utf-8")
        self.assertEqual(extractor.extract_package_version(root), "1.2.3")

    def test_flutter_version_must_be_top_level(self):
        root = workspace()
        (root / "pubspec.yaml").write_text(
            "dependencies:\n  versioned_package:\n    version: 9.8.7\nversion: 1.2.3+4\n",
            encoding="utf-8",
        )
        self.assertEqual(extractor.extract_package_version(root), "1.2.3")

        nested_only = workspace()
        (nested_only / "pubspec.yaml").write_text(
            "dependencies:\n  versioned_package:\n    version: 9.8.7\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(ValueError, "No supported application version file"):
            extractor.extract_package_version(nested_only)

    def test_reads_android_gradle_version_name(self):
        root = workspace()
        app = root / "app"
        app.mkdir()
        (app / "build.gradle.kts").write_text('android { defaultConfig { versionName = "1.2.3" } }\n', encoding="utf-8")
        self.assertEqual(extractor.extract_package_version(root), "1.2.3")

    def test_reads_android_version_properties_referenced_by_gradle(self):
        root = workspace()
        app = root / "apps/android/app"
        app.mkdir(parents=True)
        (app / "build.gradle.kts").write_text(
            'val packageVersion = Properties().apply { file("version.properties").inputStream().use(::load) }\n'
            'defaultConfig { versionName = packageVersion.getProperty("versionName") }\n',
            encoding="utf-8",
        )
        (app / "version.properties").write_text("versionName=1.2.3\n", encoding="utf-8")
        self.assertEqual(extractor.extract_package_version(root), "1.2.3")

    def test_android_app_version_takes_precedence_over_root_protocol_package(self):
        root = workspace()
        (root / "package.json").write_text(json.dumps({"name": "protocol", "version": "9.8.7"}), encoding="utf-8")
        app = root / "apps/android/app"
        app.mkdir(parents=True)
        (app / "build.gradle.kts").write_text(
            'android { defaultConfig { versionName = "1.2.3" } }\n', encoding="utf-8"
        )
        self.assertEqual(extractor.extract_package_version(root), "9.8.7")
        self.assertEqual(extractor.extract_package_version(root, platform="android"), "1.2.3")

    def test_reads_ios_info_plist(self):
        root = workspace()
        app = root / "ios/Example"
        app.mkdir(parents=True)
        with (app / "Info.plist").open("wb") as output:
            plistlib.dump({"CFBundleShortVersionString": "1.2.3"}, output)
        self.assertEqual(extractor.extract_package_version(root), "1.2.3")

    def test_reads_tauri_cargo_package_version(self):
        root = workspace()
        app = root / "src-tauri"
        app.mkdir()
        (app / "Cargo.toml").write_text('[package]\nname = "example"\nversion = "1.2.3"\n', encoding="utf-8")
        self.assertEqual(extractor.extract_package_version(root), "1.2.3")

    def test_reads_tauri_version_inherited_from_workspace_package(self):
        root = workspace()
        app = root / "src-tauri"
        app.mkdir()
        (root / "Cargo.toml").write_text('[workspace.package]\nversion = "1.2.3"\n', encoding="utf-8")
        (app / "Cargo.toml").write_text('[package]\nname = "example"\nversion.workspace = true\n', encoding="utf-8")
        self.assertEqual(extractor.extract_package_version(root), "1.2.3")

    def test_rejects_non_x_y_z_versions(self):
        root = workspace()
        (root / "package.json").write_text(json.dumps({"version": "1.2.3-beta.1"}), encoding="utf-8")
        with self.assertRaises(ValueError):
            extractor.extract_package_version(root)

    def test_rejects_ambiguous_fallback_manifests(self):
        root = workspace()
        for name in ("one", "two"):
            package = root / name
            package.mkdir()
            (package / "package.json").write_text(json.dumps({"version": "1.2.3"}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Multiple application version files"):
            extractor.extract_package_version(root)

    def test_current_android_project_uses_the_gradle_package_version(self):
        root = Path(extractor.__file__).resolve().parents[3]
        self.assertEqual(extractor.extract_package_version(root, platform="android"), "2.2.1")


if __name__ == "__main__":
    unittest.main()
