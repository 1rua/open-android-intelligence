#!/usr/bin/env python3
"""为开发预览、编号测试版和正式版生成同一套 App 更新元数据。"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
CHANNEL_PHASE = {"development": 0, "preview": 1, "stable": 2}
VERSION_CODE_CHANNEL_SCALE = 10
MAX_VERSION_CODE = 2_100_000_000
SEMVER_PATTERN = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-((?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


def git_value(*arguments: str, repository: Path = REPOSITORY_ROOT) -> str:
    return subprocess.check_output(
        ["git", "-C", str(repository), *arguments], text=True
    ).strip()


def read_base_version() -> str:
    properties = REPOSITORY_ROOT / "apps/android/app/version.properties"
    for line in properties.read_text(encoding="utf-8").splitlines():
        if line.startswith("versionName="):
            value = line.partition("=")[2].strip()
            if SEMVER_PATTERN.fullmatch(value) and "-" not in value and "+" not in value:
                return value
    raise ValueError("version.properties 缺少有效的正式基线版本号")


def release_metadata(base_version: str, commit: str, commit_count: int, tag: str) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("发布提交必须为完整的 Git SHA")
    if commit_count < 1:
        raise ValueError("发布构建必须具有完整的 Git 历史提交数")
    if tag == "nightly":
        channel = "development"
        version_name = f"{base_version}-dev.{commit_count}+{commit[:12]}"
    elif tag.startswith("v"):
        version_name = tag[1:]
        parsed = SEMVER_PATTERN.fullmatch(version_name)
        if not parsed:
            raise ValueError("版本标签必须为 v 开头的完整 SemVer，例如 v2.2.0-beta.1")
        channel = "preview" if parsed.group(4) else "stable"
    else:
        raise ValueError("只支持 nightly 或 v 开头的版本标签")
    version_code = commit_count * VERSION_CODE_CHANNEL_SCALE + CHANNEL_PHASE[channel]
    if version_code > MAX_VERSION_CODE:
        raise ValueError("versionCode 超出 Android 发行范围")
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
        raise ValueError("发布构建需要完整 Git 历史，请在 checkout 中设置 fetch-depth: 0")
    metadata = release_metadata(
        read_base_version(), git_value("rev-parse", "HEAD"),
        int(git_value("rev-list", "--count", "HEAD")), args.tag,
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
    print(f"发布版本：{metadata['versionName']}；安装版本代码：{metadata['versionCode']}；渠道：{metadata['channel']}")


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
    raise ValueError(f"找不到 Android SDK 工具 {name}，无法验证 APK")


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
        raise ValueError("无法从 APK 的真实 Android Manifest 读取包名及版本")
    return {"applicationId": package.group(1), "versionCode": int(package.group(2) or "0"), "versionName": package.group(3)}


def verify_apk_identity(metadata: dict, actual: dict) -> None:
    expected = {key: metadata[key] for key in ("applicationId", "versionCode", "versionName")}
    if actual != expected:
        raise ValueError("APK 中的真实包名或版本与构建前信息不一致，拒绝发布")


def signing_digest(apk: Path) -> str:
    result = subprocess.run(
        [str(find_android_tool("apksigner")), "verify", "--print-certs", str(apk.resolve())],
        check=True, capture_output=True, text=True,
    )
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", result.stdout)
    if len(digests) != 1:
        raise ValueError("发布 APK 必须具有唯一且可验证的当前签名证书")
    return digests[0].lower()


def verified_manifest(metadata: dict, output_metadata: dict, apk: Path, digest: str) -> dict:
    elements = output_metadata.get("elements", [])
    if len(elements) != 1:
        raise ValueError("更新发布必须使用唯一的完整 APK，不能发布拆分安装包")
    element = elements[0]
    if (
        output_metadata.get("applicationId") != metadata["applicationId"]
        or element.get("versionName") != metadata["versionName"]
        or element.get("versionCode") != metadata["versionCode"]
        or element.get("outputFile") != apk.name
    ):
        raise ValueError("实际 APK 输出元数据与构建前版本信息不一致，拒绝发布")
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("APK 签名证书摘要必须为小写 SHA-256")
    return {**metadata, "assetName": apk.name, "signingCertificateSha256": digest}


def release_notes(metadata: dict, repository: str) -> str:
    channel_name = {"development": "开发预览测试版", "preview": "版本测试版", "stable": "正式版"}[metadata["channel"]]
    build_time = dt.datetime.now(dt.timezone.utc).astimezone(dt.timezone(dt.timedelta(hours=8)))
    signing_note = (
        "此包使用仓库固定的公开调试签名，可覆盖使用同一调试签名的开发预览安装。"
        if metadata["channel"] == "development"
        else "此包不可调试并使用私有发行签名；与开发预览版的公开调试签名不同，Android 无法直接覆盖安装。"
    )
    preview_note = "\n该版本供测试使用，可能包含尚未充分验证的改动。\n" if metadata["channel"] != "stable" else ""
    return (
        f"### Open Android Intelligence · {channel_name}\n\n"
        f"- **版本**：`{metadata['versionName']}`\n"
        f"- **安装版本代码**：`{metadata['versionCode']}`\n"
        f"- **构建时间**：{build_time:%Y-%m-%d %H:%M:%S}（北京时间）\n"
        f"- **源码提交**：[{metadata['commit'][:12]}](https://github.com/{repository}/commit/{metadata['commit']})\n"
        f"- **安装文件**：`{metadata['assetName']}`（完整分发版本）\n"
        "- **更新元数据**：`oai-update.json`，与实际 APK 的版本、分发类型及签名身份相对应。\n\n"
        f"{signing_note}\n{preview_note}"
        "\n更新检测分为正式版和预览版；预览版会检查开发预览测试版，以及后续带版本号发布的测试版。\n"
    )


def manifest(args: argparse.Namespace) -> None:
    if not args.apk.is_file():
        raise ValueError("找不到待发布的 APK")
    metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
    actual = json.loads((args.apk.parent / "output-metadata.json").read_text(encoding="utf-8"))
    verify_apk_identity(metadata, apk_identity(args.apk))
    result = verified_manifest(metadata, actual, args.apk, signing_digest(args.apk))
    write_json(args.manifest, result)
    if args.notes:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository or ""):
            raise ValueError("发布说明需要有效的 GitHub 仓库名称")
        args.notes.write_text(release_notes(result, args.repository), encoding="utf-8")
    print(f"已验证 APK 输出信息及签名，更新元数据：{args.manifest}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare", help="构建前生成版本信息并注入 GitHub Actions 环境")
    prepare_parser.add_argument("--tag", required=True)
    prepare_parser.add_argument("--metadata", type=Path, required=True)
    prepare_parser.add_argument("--github-env", type=Path)
    prepare_parser.set_defaults(run=prepare)
    manifest_parser = commands.add_parser("manifest", help="核实 APK 输出和签名后生成发布资源")
    manifest_parser.add_argument("--metadata", type=Path, required=True)
    manifest_parser.add_argument("--apk", type=Path, required=True)
    manifest_parser.add_argument("--manifest", type=Path, required=True)
    manifest_parser.add_argument("--notes", type=Path)
    manifest_parser.add_argument("--repository")
    manifest_parser.set_defaults(run=manifest)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"发布元数据校验失败：{error}", file=sys.stderr)
        sys.exit(1)
