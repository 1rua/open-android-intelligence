#!/usr/bin/env python3
"""核验冻结历史 Gateway 的来源和锁定契约，再实际执行其历史共享向量。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_SPEC = REPO_ROOT / "gateway-contract/historical-hermes-gateway.json"
CONTRACT_PATHS = (
    "gateway-contract/schemas", "gateway-contract/vectors",
    "gateway-contract/core-dispatched-schemas.json", "gateway-contract/src",
)
PROBES = ("schemas/envelope.schema.json", "vectors/vector-set-1.0.0.schema.json", "core-dispatched-schemas.json")
VECTOR_FILES = ("request-signatures.json", "protocol-negotiation.json", "auth-sessions.json",
                "attachments.json", "sse-events.json", "device-requests.json")


class GateError(RuntimeError):
    """门禁错误只含固定诊断，不输出运行环境或凭据。"""


def git(root: Path, *args: str, binary: bool = False):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, check=False)
    if result.returncode:
        raise GateError("无法读取已固定的 Git 来源")
    return result.stdout if binary else result.stdout.decode().strip()


def repository_identity(value: str) -> str:
    identity = value.strip().removesuffix(".git")
    if identity.startswith("git@"):
        return identity[4:].replace(":", "/", 1).lower()
    return re.sub(r"^(?:https?|ssh)://", "", identity).strip("/").lower()


def load_spec(path: Path) -> dict:
    value = json.loads(path.read_text())
    if not isinstance(value, dict) or any(not re.fullmatch(r"[0-9a-f]{40}", str(value.get(key, "")))
                                          for key in ("revision", "contractRevision")):
        raise GateError("历史来源必须锁定完整提交")
    if any(not isinstance(value.get(key), str) or not value[key] for key in ("repository", "contractRepository")):
        raise GateError("历史来源缺少仓库身份")
    aliases = value.get("repositoryAliases", [])
    if not isinstance(aliases, list) or any(not isinstance(alias, str) or not alias for alias in aliases):
        raise GateError("历史来源仓库别名不合法")
    return value


def verify_plugin(plugin: Path, spec: dict) -> None:
    if git(plugin, "rev-parse", "HEAD") != spec["revision"]:
        raise GateError("历史插件提交与固定基线不一致")
    known_repositories = {repository_identity(value) for value in [spec["repository"], *spec.get("repositoryAliases", [])]}
    if repository_identity(git(plugin, "remote", "get-url", "origin")) not in known_repositories:
        raise GateError("历史插件仓库与固定基线不一致")
    managed = ("contract-pin.json", "open_android_intelligence_gateway", "tests")
    dirty = git(plugin, "status", "--porcelain", "--untracked-files=normal", "--", *managed)
    if dirty:
        raise GateError("历史插件受管源码有未提交变化")
    pin = json.loads((plugin / "contract-pin.json").read_text())
    if (pin.get("revision") != spec["contractRevision"]
            or repository_identity(str(pin.get("repository", ""))) != repository_identity(spec["contractRepository"])):
        raise GateError("历史插件锁定契约与固定基线不一致")


def export_relative_path(raw: str) -> Path:
    path = PurePosixPath(raw)
    if "\\" in raw or path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != "gateway-contract":
        raise GateError("契约导出路径不合法")
    if len(path.parts) < 2:
        raise GateError("契约导出路径缺少文件")
    return Path(*path.parts[1:])


def contract_files(repo: Path, revision: str) -> dict[str, bytes]:
    listing = git(repo, "ls-tree", "-r", "-z", revision, "--", *CONTRACT_PATHS, binary=True)
    files = {}
    for entry in listing.split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        mode, kind, object_id = metadata.decode().split()
        if mode not in ("100644", "100755") or kind != "blob":
            raise GateError("契约导出只接受普通受管文件")
        relative = str(export_relative_path(raw_path.decode()))
        files[relative] = git(repo, "cat-file", "blob", object_id, binary=True)
    if not all(probe in files for probe in PROBES):
        raise GateError("锁定契约缺少必要输入")
    return files


def export_contract(repo: Path, revision: str, output: Path) -> dict[str, str]:
    files = contract_files(repo, revision)
    output.mkdir(parents=True, exist_ok=False)
    hashes = {}
    for relative, content in files.items():
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        hashes[relative] = hashlib.sha256(content).hexdigest()
    return hashes


def verify_export(repo: Path, revision: str, output: Path, hashes: dict[str, str]) -> None:
    expected = contract_files(repo, revision)
    actual = {str(path.relative_to(output)): path for path in output.rglob("*") if path.is_file() or path.is_symlink()}
    if actual.keys() != expected.keys() or hashes.keys() != expected.keys():
        raise GateError("契约导出内容缺失或包含额外文件")
    for relative, content in expected.items():
        target = actual[relative]
        if (target.is_symlink() or target.read_bytes() != content
                or hashlib.sha256(content).hexdigest() != hashes[relative]):
            raise GateError("契约导出内容与固定 Git 对象不一致")


def validate_results(results: list[dict], expected_ids: set[str] | None = None) -> int:
    if (not isinstance(results, list) or not results
            or any(not isinstance(row, dict) or row.get("status") != "pass"
                   or not isinstance(row.get("vectorId"), str) for row in results)):
        raise GateError("历史共享 vectors 执行失败或没有执行")
    ids = [row["vectorId"] for row in results]
    if len(ids) != len(set(ids)) or expected_ids is not None and set(ids) != expected_ids:
        raise GateError("历史共享 vectors 未完整执行或出现重复")
    return len(results)


def worktree_contract_snapshot() -> dict[str, str]:
    files = []
    for relative in CONTRACT_PATHS:
        path = REPO_ROOT / relative
        files.extend(path.rglob("*") if path.is_dir() else [path])
    return {str(path.relative_to(REPO_ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in files if path.is_file()}


RUNTIME_PROBE = r'''
import json, sys
from pathlib import Path
plugin, contract, storage = map(Path, sys.argv[1:])
sys.path.insert(0, str(plugin))
import open_android_intelligence_gateway.core as module
assert Path(module.__file__).resolve().is_relative_to(plugin.resolve()), "HISTORICAL_PACKAGE_SOURCE_CHANGED"
core = module.create_gateway_core(storage_root=storage, contract_root=contract)
print(json.dumps(core.run_shared_vectors(contract), ensure_ascii=False))
'''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plugin-root-env", default="HERMES_PLUGIN_ROOT")
    parser.add_argument("--output", required=True, type=Path, help="新的隔离输出目录；不会永久删除")
    args = parser.parse_args()
    try:
        spec = load_spec(FIXTURE_SPEC)
        plugin_value = os.environ.get(args.plugin_root_env, "").strip()
        if not plugin_value:
            raise GateError("必须提供历史插件检出目录")
        plugin = Path(plugin_value).resolve()
        verify_plugin(plugin, spec)
        if repository_identity(git(REPO_ROOT, "remote", "get-url", "origin")) != repository_identity(spec["contractRepository"]):
            raise GateError("契约 Git 来源不是固定仓库")
        present = subprocess.run(["git", "-C", str(REPO_ROOT), "cat-file", "-e", spec["contractRevision"] + "^{commit}"], capture_output=True)
        if present.returncode:
            git(REPO_ROOT, "fetch", "--no-tags", "--quiet", "origin", spec["contractRevision"])
        output = args.output.resolve()
        if output.is_relative_to(REPO_ROOT / "gateway-contract") or output.is_relative_to(plugin):
            raise GateError("历史隔离输出不得写入当前契约或插件源码")
        current_before = worktree_contract_snapshot()
        args.output.mkdir(parents=True, exist_ok=False)
        contract = output / "gateway-contract"
        hashes = export_contract(REPO_ROOT, spec["contractRevision"], contract)
        verify_export(REPO_ROOT, spec["contractRevision"], contract, hashes)
        environment = {key: value for key, value in os.environ.items()
                       if key not in ("PYTHONPATH", "OPEN_ANDROID_GATEWAY_CONTRACT_ROOT", "HERMES_SDK_ROOT")}
        # 独立解释器仅导入固定历史检出，不让当前 Core 或 SDK 改写历史测试宿主。
        run = subprocess.run([sys.executable, "-c", RUNTIME_PROBE, str(plugin), str(contract),
                              str(args.output.resolve() / "runtime")], env=environment,
                             capture_output=True, text=True, timeout=120)
        if run.returncode:
            raise GateError("历史共享 vectors 运行进程失败")
        results = json.loads(run.stdout)
        expected_ids = {case["id"] for name in VECTOR_FILES
                        for case in json.loads((contract / "vectors" / name).read_text())["cases"]}
        count = validate_results(results, expected_ids)
        verify_export(REPO_ROOT, spec["contractRevision"], contract, hashes)
        if worktree_contract_snapshot() != current_before:
            raise GateError("当前契约在历史验证期间发生变化")
        report = {"historicalPluginRevision": spec["revision"], "contractRevision": spec["contractRevision"],
                  "fileSha256": hashes, "vectors": results, "passed": count, "failed": 0,
                  "currentContractUnmodified": True}
        (args.output / "historical-contract-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(f"历史 Gateway 来源、锁定契约完整性及真实共享 vectors：{count}/{count} 通过；当前契约兼容性仍由后续独立步骤验证。")
        return 0
    except (GateError, ValueError, OSError, subprocess.TimeoutExpired) as error:
        detail = str(error) if isinstance(error, GateError) else type(error).__name__
        print("历史契约门禁失败：" + detail, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
