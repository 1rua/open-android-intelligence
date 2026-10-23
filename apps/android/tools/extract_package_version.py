#!/usr/bin/env python3
from __future__ import annotations

import json
import plistlib
import re
import sys
import tomllib
from pathlib import Path


VERSION_PATTERN = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
IGNORED_PARTS = {".git", ".gradle", "build", "dist", "node_modules", "target", "third_party"}


def validate_version(value: object, source: Path) -> str:
    if not isinstance(value, str) or not VERSION_PATTERN.fullmatch(value.strip()):
        raise ValueError(f"{source} must contain an X.Y.Z version")
    return value.strip()


def _json_version(path: Path) -> str | None:
    payload = json.loads(path.read_text(encoding="utf-8"))
    value = payload.get("version") if isinstance(payload, dict) else None
    return validate_version(value, path) if value is not None else None


def _yaml_version(path: Path) -> str | None:
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^version\s*:\s*['\"]?([^\s'\"#]+)", line)
        if match:
            value = match.group(1)
            flutter = re.fullmatch(
                r"((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))"
                r"(?:\+[0-9A-Za-z.-]+)?",
                value,
            )
            return validate_version(flutter.group(1) if flutter else value, path)
    return None


def _properties_version(path: Path) -> str | None:
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")) or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        if key.strip() in {"versionName", "version"}:
            return validate_version(value.strip(), path)
    return None


def _gradle_version(path: Path) -> str | None:
    source = path.read_text(encoding="utf-8")
    for line in source.splitlines():
        active_line = line.split("//", 1)[0]
        direct = re.search(r"\bversionName(?:\s*=\s*|\s+)['\"]([^'\"]+)['\"]", active_line)
        if direct:
            return validate_version(direct.group(1), path)

    # 兼容以 Gradle Properties 文件提供 versionName 的现有 Android 工程。
    for match in re.finditer(r"file\(\s*['\"]([^'\"]+\.properties)['\"]\s*\)", source):
        properties_path = (path.parent / match.group(1)).resolve()
        if properties_path.is_file():
            value = _properties_version(properties_path)
            if value is not None:
                return value
    return None


def _plist_version(path: Path) -> str | None:
    with path.open("rb") as source:
        payload = plistlib.load(source)
    value = payload.get("CFBundleShortVersionString") if isinstance(payload, dict) else None
    return validate_version(value, path) if value is not None else None


def _cargo_version(path: Path) -> str | None:
    payload = tomllib.loads(path.read_text(encoding="utf-8"))
    package = payload.get("package", {})
    value = package.get("version") if isinstance(package, dict) else None
    if isinstance(value, dict) and value.get("workspace") is True:
        for parent in path.parents:
            workspace_manifest = parent / "Cargo.toml"
            if workspace_manifest == path or not workspace_manifest.is_file():
                continue
            workspace_data = tomllib.loads(workspace_manifest.read_text(encoding="utf-8"))
            workspace = workspace_data.get("workspace", {})
            workspace_package = workspace.get("package", {}) if isinstance(workspace, dict) else {}
            inherited_version = workspace_package.get("version") if isinstance(workspace_package, dict) else None
            if isinstance(inherited_version, str):
                return validate_version(inherited_version, workspace_manifest)
    if value is None:
        workspace = payload.get("workspace", {})
        workspace_package = workspace.get("package", {}) if isinstance(workspace, dict) else {}
        value = workspace_package.get("version") if isinstance(workspace_package, dict) else None
    return validate_version(value, path) if isinstance(value, str) else None


def _usable_files(root: Path, pattern: str) -> list[Path]:
    return sorted(
        path for path in root.rglob(pattern)
        if not any(part in IGNORED_PARTS for part in path.relative_to(root).parts)
        and not any(part.startswith(".") for part in path.relative_to(root).parts)
    )


def _first_value(paths: list[Path], reader) -> str | None:
    for path in paths:
        if path.is_file():
            value = reader(path)
            if value is not None:
                return value
    return None


def extract_package_version(root: Path) -> str:
    root = root.resolve()

    # 先检查根目录的应用清单，避免仓库子模块版本覆盖应用版本。
    root_version = _first_value([root / "package.json"], _json_version)
    if root_version is not None:
        return root_version
    root_version = _first_value([root / "pubspec.yaml"], _yaml_version)
    if root_version is not None:
        return root_version

    android_paths = [
        root / "apps/android/app/build.gradle.kts",
        root / "apps/android/app/build.gradle",
        root / "android/app/build.gradle.kts",
        root / "android/app/build.gradle",
        root / "app/build.gradle.kts",
        root / "app/build.gradle",
        root / "build.gradle.kts",
        root / "build.gradle",
    ]
    android_version = _first_value(android_paths, _gradle_version)
    if android_version is not None:
        return android_version

    plist_paths = [
        root / "Info.plist",
        root / "ios/Info.plist",
        root / "apps/ios/Info.plist",
    ]
    if (root / "ios").is_dir():
        plist_paths.extend(_usable_files(root / "ios", "Info.plist"))
    if (root / "apps/ios").is_dir():
        plist_paths.extend(_usable_files(root / "apps/ios", "Info.plist"))
    plist_version = _first_value(plist_paths, _plist_version)
    if plist_version is not None:
        return plist_version

    cargo_paths = [
        root / "src-tauri/Cargo.toml",
        root / "tauri/Cargo.toml",
        root / "app/src-tauri/Cargo.toml",
        root / "Cargo.toml",
    ]
    cargo_version = _first_value(cargo_paths, _cargo_version)
    if cargo_version is not None:
        return cargo_version

    # 支持把单个应用清单放在 apps/<name> 或包根目录的轻量仓库。
    readers = (
        ("package.json", _json_version),
        ("pubspec.yaml", _yaml_version),
        ("Info.plist", _plist_version),
        ("Cargo.toml", _cargo_version),
    )
    for pattern, reader in readers:
        matches = []
        for path in _usable_files(root, pattern):
            value = reader(path)
            if value is not None:
                matches.append((path, value))
        if len(matches) == 1:
            return matches[0][1]
        if len(matches) > 1:
            locations = ", ".join(str(path.relative_to(root)) for path, _ in matches)
            raise ValueError(f"Multiple application version files found: {locations}")

    raise ValueError("No supported application version file found")


def main() -> None:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    if len(sys.argv) > 2:
        raise SystemExit("Usage: extract_package_version.py [project-root]")
    print(extract_package_version(root))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError, tomllib.TOMLDecodeError, plistlib.InvalidFileException) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
