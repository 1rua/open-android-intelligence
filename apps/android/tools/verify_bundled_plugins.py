"""Verify the delivered APK contains exactly the signed .alp packages built by the signed build.

内置数据读取与屏幕插件逐项校验：APK 内必须恰好
出现一次对应资产，且与签出的包逐字节一致；任何缺失、重复或内容偏差
都视为内置链路断裂，直接失败。
"""
import argparse
from pathlib import Path
import zipfile

BUNDLED_PLUGINS: dict[str, str] = {
    "notifications": "assets/bundled-plugins/notifications.alp",
    "sms": "assets/bundled-plugins/sms.alp",
    "call-log": "assets/bundled-plugins/call-log.alp",
    "screen-reader": "assets/bundled-plugins/screen-reader.alp",
    "screen-capture": "assets/bundled-plugins/screen-capture.alp",
    "screen-control": "assets/bundled-plugins/screen-control.alp",
}


def verify(apk: Path, packages: dict[str, Path]) -> None:
    unknown = sorted(name for name in packages if name not in BUNDLED_PLUGINS)
    if unknown:
        raise ValueError(f"BUNDLED_PLUGINS_UNKNOWN:{','.join(unknown)}")
    expected = {name: Path(path).read_bytes() for name, path in packages.items()}
    for name, content in expected.items():
        if not content:
            raise ValueError(f"BUNDLED_PLUGINS_INVALID:{name}: empty package")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        for name, content in expected.items():
            asset = BUNDLED_PLUGINS[name]
            if names.count(asset) != 1 or archive.read(asset) != content:
                raise ValueError(f"BUNDLED_PLUGINS_MISMATCH:{name}: APK must contain the exact signed package at {asset}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--package", required=True, action="append", metavar="NAME=PATH",
                        help="插件名与签出包路径，可重复：notifications=/tmp/notifications.alp")
    args = parser.parse_args()
    packages: dict[str, Path] = {}
    for item in args.package:
        name, separator, path = item.partition("=")
        if not separator or not name or not path:
            raise SystemExit(f" Invalid --package (expected NAME=PATH): {item}")
        packages[name] = Path(path)
    verify(args.apk, packages)
