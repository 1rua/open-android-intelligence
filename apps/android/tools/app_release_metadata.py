#!/usr/bin/env python3
"""Generate and verify App update metadata for preview and stable releases."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from extract_package_version import extract_package_version

REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
CHANNEL_PHASE = {"development": 0, "preview": 1, "stable": 2}
VERSION_CODE_CHANNEL_SCALE = 10
MAX_VERSION_CODE = 2_100_000_000
def git_value(*arguments: str, repository: Path = REPOSITORY_ROOT) -> str:
    return subprocess.check_output(
        ["git", "-C", str(repository), *arguments], text=True
    ).strip()


def read_base_version() -> str:
    return extract_package_version(REPOSITORY_ROOT)


def release_metadata(
    base_version: str,
    commit: str,
    commit_count: int,
    channel: str,
    run_number: int | None = None,
) -> dict:
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", base_version):
        raise ValueError("Package version must be X.Y.Z")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Release commit must be a full Git SHA")
    if commit_count < 1:
        raise ValueError("Release builds require complete Git history")
    if channel == "development":
        version_name = f"{base_version}-dev.{commit_count}+{commit[:12]}"
    elif channel == "preview":
        if run_number is None or run_number < 1:
            raise ValueError("Preview releases require a positive workflow run number")
        version_name = f"{base_version}-dev.{run_number}"
    elif channel == "stable":
        version_name = base_version
    else:
        raise ValueError("Release channel must be development, preview, or stable")
    version_code = commit_count * VERSION_CODE_CHANNEL_SCALE + CHANNEL_PHASE[channel]
    if version_code > MAX_VERSION_CODE:
        raise ValueError("Android versionCode is out of range")
    return {
        "schemaVersion": 1,
        "versionName": version_name,
        "versionCode": version_code,
        "commit": commit,
        "channel": channel,
        "applicationId": "com.openandroidintelligence.mobile",
        "distribution": "full",
    }


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def prepare(args: argparse.Namespace) -> None:
    if git_value("rev-parse", "--is-shallow-repository") != "false":
        raise ValueError("Release builds require a complete Git history")
    metadata = release_metadata(
        read_base_version(), git_value("rev-parse", "HEAD"),
        int(git_value("rev-list", "--count", "HEAD")), args.channel, args.run_number,
    )
    write_json(args.metadata, metadata)
    if args.github_env:
        with args.github_env.open("a", encoding="utf-8") as output:
            for key, value in (
                ("OAI_VERSION_NAME", metadata["versionName"]),
                ("OAI_VERSION_CODE", metadata["versionCode"]),
                ("OAI_UPDATE_CHANNEL", metadata["channel"]),
                ("OAI_GIT_COMMIT", metadata["commit"]),
            ):
                output.write(f"{key}={value}\n")
    print(f"Version: {metadata['versionName']}; versionCode: {metadata['versionCode']}; channel: {metadata['channel']}")


def find_android_tool(name: str) -> Path:
    configured = os.environ.get(name.upper()) or shutil.which(name)
    if configured:
        candidate = Path(configured)
        if candidate.is_file():
            return candidate.resolve()
    for sdk_variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk_root = os.environ.get(sdk_variable)
        if sdk_root:
            candidates = sorted(Path(sdk_root).glob(f"build-tools/*/{name}"), reverse=True)
            if candidates:
                return candidates[0].resolve()
    raise ValueError(f"Android SDK tool not found: {name}")


def apk_identity(apk: Path) -> dict:
    result = subprocess.run(
        [str(find_android_tool("aapt2")), "dump", "badging", str(apk.resolve())],
        check=True, capture_output=True, text=True,
    )
    package = re.search(
        r"^package: name='([^']+)' versionCode='([0-9]*)' versionName='([^']+)'",
        result.stdout, flags=re.MULTILINE,
    )
    if not package:
        raise ValueError("Could not read package name and version from the APK manifest")
    return {"applicationId": package.group(1), "versionCode": int(package.group(2) or "0"), "versionName": package.group(3)}


def verify_apk_identity(metadata: dict, actual: dict) -> None:
    expected = {key: metadata[key] for key in ("applicationId", "versionCode", "versionName")}
    if actual != expected:
        raise ValueError("APK package identity or version does not match build metadata")


# apksigner 的两种输出格式都要认：
# - 旧版（直到 SDK 36）：`Signer #1 certificate SHA-256 digest: <hex>`
# - SDK 37 起：签名方案前缀 `V1/V2/V3.0 Signer: certificate SHA-256 digest: <hex>`，
#   多签名者写成 `V2 Signer #2: ...`。
# 只统计**当前 APK 签名证书**那一行：`public key` 摘要与 `Source Stamp Signer`
# （来源印章，不是 APK 的签名身份）都不算，末尾锚定防止截断的 65 位十六进制误命中。
_LEGACY_SIGNER_DIGEST = re.compile(
    r"^Signer #(\d+) certificate SHA-256 digest: ([0-9a-fA-F]{64})$", re.MULTILINE
)
_SCHEME_SIGNER_DIGEST = re.compile(
    r"^(V\d+(?:\.\d+)?) Signer: certificate SHA-256 digest: ([0-9a-fA-F]{64})$", re.MULTILINE
)
_SCHEME_INDEXED_SIGNER_DIGEST = re.compile(
    r"^(V\d+(?:\.\d+)?) Signer #(\d+): certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
    re.MULTILINE,
)


def signing_digest(apk: Path) -> str:
    result = subprocess.run(
        [str(find_android_tool("apksigner")), "verify", "--print-certs", str(apk.resolve())],
        check=True, capture_output=True, text=True,
    )
    legacy = _LEGACY_SIGNER_DIGEST.findall(result.stdout)
    plain = _SCHEME_SIGNER_DIGEST.findall(result.stdout)
    indexed = _SCHEME_INDEXED_SIGNER_DIGEST.findall(result.stdout)

    # 多签名者一律拒绝——即使两个签名者用同一张证书，也说明构建产物身份不唯一。
    if len(legacy) > 1 or any(int(number) != 1 for _, number, _ in indexed):
        raise ValueError("Release APK must have exactly one verifiable current signing certificate")
    if any(int(number) != 1 for number, _ in legacy):
        raise ValueError("Release APK must have exactly one verifiable current signing certificate")

    digests = {digest.lower() for _, digest in legacy}
    digests |= {digest.lower() for _, digest in plain}
    digests |= {digest.lower() for _, _, digest in indexed}
    if len(digests) != 1:
        raise ValueError("Release APK must have exactly one verifiable current signing certificate")
    return digests.pop()


def verified_manifest(metadata: dict, output_metadata: dict, apk: Path, digest: str) -> dict:
    elements = output_metadata.get("elements", [])
    if len(elements) != 1:
        raise ValueError("Update releases require one complete APK, not split APKs")
    element = elements[0]
    if (
        output_metadata.get("applicationId") != metadata["applicationId"]
        or element.get("versionName") != metadata["versionName"]
        or element.get("versionCode") != metadata["versionCode"]
        or element.get("outputFile") != apk.name
    ):
        raise ValueError("APK output metadata does not match build metadata")
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("APK signing certificate digest must be lowercase SHA-256")
    return {**metadata, "assetName": apk.name, "signingCertificateSha256": digest}


def manifest(args: argparse.Namespace) -> None:
    if not args.apk.is_file():
        raise ValueError("Release APK not found")
    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    actual = json.loads((args.apk.parent / "output-metadata.json").read_text(encoding="utf-8"))
    verify_apk_identity(metadata, apk_identity(args.apk))
    result = verified_manifest(metadata, actual, args.apk, signing_digest(args.apk))
    write_json(args.manifest, result)
    print(f"Verified APK identity and signature; update metadata: {args.manifest}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare", help="Create build metadata from the application package version")
    prepare_parser.add_argument("--channel", choices=CHANNEL_PHASE, required=True)
    prepare_parser.add_argument("--run-number", type=int)
    prepare_parser.add_argument("--metadata", type=Path, required=True)
    prepare_parser.add_argument("--github-env", type=Path)
    prepare_parser.set_defaults(run=prepare)
    manifest_parser = commands.add_parser("manifest", help="Verify the APK and create update metadata")
    manifest_parser.add_argument("--metadata", type=Path, required=True)
    manifest_parser.add_argument("--apk", type=Path, required=True)
    manifest_parser.add_argument("--manifest", type=Path, required=True)
    manifest_parser.set_defaults(run=manifest)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Release metadata validation failed: {error}", file=sys.stderr)
        sys.exit(1)
