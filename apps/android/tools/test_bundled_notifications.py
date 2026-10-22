import tempfile
from pathlib import Path
import unittest
import zipfile

from verify_bundled_notifications import ASSET, verify


class BundledNotificationDeliveryTest(unittest.TestCase):
    def test_apk_must_contain_the_exact_signed_package_once(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            package = root / "notifications.alp"
            package.write_bytes(b"signed-notification-package")
            for entries, accepted in (([(ASSET, package.read_bytes())], True),
                                      ([], False),
                                      ([(ASSET, b"stale-package")], False),
                                      ([("assets/notifications.alp", package.read_bytes())], False)):
                with self.subTest(entries=entries):
                    apk = root / "app.apk"
                    with zipfile.ZipFile(apk, "w") as archive:
                        for name, content in entries:
                            archive.writestr(name, content)
                    if accepted:
                        verify(apk, package)
                    else:
                        with self.assertRaises(ValueError):
                            verify(apk, package)
