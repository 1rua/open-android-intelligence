import tempfile
from pathlib import Path
import unittest
import zipfile

from verify_bundled_plugins import BUNDLED_PLUGINS, verify


class BundledPluginsDeliveryTest(unittest.TestCase):
    """六个内置插件资产必须逐项、逐字节与签出的包一致。"""

    def build_apk(self, root: Path, entries: list[tuple[str, bytes]]) -> Path:
        apk = root / "app.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            for name, content in entries:
                archive.writestr(name, content)
        return apk

    def test_all_assets_present_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            packages = {name: root / f"{name}.alp" for name in BUNDLED_PLUGINS}
            for name, path in packages.items():
                path.write_bytes(f"signed-{name}-package".encode())
            apk = self.build_apk(root, [
                (asset, packages[name].read_bytes()) for name, asset in BUNDLED_PLUGINS.items()
            ])
            verify(apk, packages)

    def test_a_missing_asset_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            packages = {name: root / f"{name}.alp" for name in BUNDLED_PLUGINS}
            for name, path in packages.items():
                path.write_bytes(f"signed-{name}-package".encode())
            # 只放 sms 与 call-log，缺 notifications。
            entries = [(BUNDLED_PLUGINS["sms"], packages["sms"].read_bytes()),
                       (BUNDLED_PLUGINS["call-log"], packages["call-log"].read_bytes())]
            apk = self.build_apk(root, entries)
            with self.assertRaises(ValueError):
                verify(apk, packages)

    def test_a_stale_asset_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            packages = {name: root / f"{name}.alp" for name in BUNDLED_PLUGINS}
            for name, path in packages.items():
                path.write_bytes(f"signed-{name}-package".encode())
            # APK 里残留的是旧签名内容，而期望的是重新签出的 sms 包。
            entries = [(asset, packages[name].read_bytes()) for name, asset in BUNDLED_PLUGINS.items()]
            packages["sms"].write_bytes(b"freshly-signed-sms-package")
            apk = self.build_apk(root, entries)
            with self.assertRaises(ValueError):
                verify(apk, packages)

    def test_a_wrongly_placed_asset_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            packages = {name: root / f"{name}.alp" for name in BUNDLED_PLUGINS}
            for name, path in packages.items():
                path.write_bytes(f"signed-{name}-package".encode())
            # notifications 被放错目录（少了 bundled-plugins 层级）。
            entries = [("assets/notifications.alp", packages["notifications"].read_bytes())]
            entries += [(asset, packages[name].read_bytes()) for name, asset in BUNDLED_PLUGINS.items()
                        if name != "notifications"]
            apk = self.build_apk(root, entries)
            with self.assertRaises(ValueError):
                verify(apk, packages)

    def test_an_unknown_plugin_name_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            package = root / "geo.alp"
            package.write_bytes(b"signed-geo-package")
            apk = self.build_apk(root, [])
            with self.assertRaises(ValueError):
                verify(apk, {"geo-fence": package})

    def test_a_duplicated_asset_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            packages = {name: root / f"{name}.alp" for name in BUNDLED_PLUGINS}
            for name, path in packages.items():
                path.write_bytes(f"signed-{name}-package".encode())
            entries = [(asset, packages[name].read_bytes()) for name, asset in BUNDLED_PLUGINS.items()]
            entries.append((BUNDLED_PLUGINS["call-log"], packages["call-log"].read_bytes()))
            apk = self.build_apk(root, entries)
            with self.assertRaises(ValueError):
                verify(apk, packages)

    def test_an_empty_signed_package_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            packages = {name: root / f"{name}.alp" for name in BUNDLED_PLUGINS}
            for name, path in packages.items():
                path.write_bytes(f"signed-{name}-package".encode())
            packages["sms"].write_bytes(b"")
            entries = [(asset, packages[name].read_bytes()) for name, asset in BUNDLED_PLUGINS.items()]
            apk = self.build_apk(root, entries)
            with self.assertRaises(ValueError):
                verify(apk, packages)


if __name__ == "__main__":
    unittest.main()
