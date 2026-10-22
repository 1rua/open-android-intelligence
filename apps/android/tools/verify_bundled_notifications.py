"""Verify the delivered APK contains exactly the .alp produced by the signed build."""
import argparse
from pathlib import Path
import zipfile

ASSET = "assets/bundled-plugins/notifications.alp"


def verify(apk: Path, package: Path) -> None:
    expected = package.read_bytes()
    if not expected:
        raise ValueError("BUNDLED_NOTIFICATIONS_INVALID: empty package")
    with zipfile.ZipFile(apk) as archive:
        if archive.namelist().count(ASSET) != 1 or archive.read(ASSET) != expected:
            raise ValueError("BUNDLED_NOTIFICATIONS_MISMATCH: APK must contain the signed notification package")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--package", required=True, type=Path)
    args = parser.parse_args()
    verify(args.apk, args.package)
