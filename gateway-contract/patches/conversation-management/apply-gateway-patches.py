#!/usr/bin/env python3
"""Check/apply conversation-management patches and bind both plugins to this contract.

This helper invokes Git with argument arrays (never a shell), does not fetch or
modify the checkouts in default mode, and refuses dirty or incorrectly pinned
repositories before checking either patch.
"""

from __future__ import annotations

import argparse
import json
import re
import hashlib
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


CONTRACT_INPUTS = ("schemas", "vectors", "src", "core-dispatched-schemas.json")
CORE_SCHEMAS = ("attachment.schema.json", "conversation.schema.json", "device-request.schema.json",
                "envelope.schema.json", "event.schema.json", "negotiate.schema.json", "session.schema.json")


@dataclass(frozen=True)
class Target:
    name: str
    root: Path
    patch: Path
    revision: str


class PatchError(RuntimeError):
    pass


def _git(root: Path, *args: str) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(root), *args],
            check=False,
            capture_output=True,
            text=True,
            shell=False,
        )
    except OSError as cause:
        raise PatchError(f"无法执行 Git：{cause}") from cause
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise PatchError(f"git {' '.join(args)} 失败：{detail}")
    return result.stdout.strip()


def pinned_revisions(patch_dir: Path) -> tuple[str, str]:
    """Load the immutable upstream revisions to which these archived patches apply."""
    pins_path = patch_dir / "base-pins.json"
    try:
        pins = json.loads(pins_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError) as cause:
        raise PatchError(f"无法读取归档基线 pin {pins_path}：{cause}") from cause
    except json.JSONDecodeError as cause:
        raise PatchError(f"归档基线 pin {pins_path} 不是有效 JSON：{cause}") from cause
    if not isinstance(pins, dict):
        raise PatchError("base-pins.json 必须是 JSON 对象")
    revisions = (pins.get("openclaw"), pins.get("hermes"))
    for name, revision in zip(("OpenClaw", "Hermes"), revisions):
        if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40}", revision):
            raise PatchError(f"base-pins.json 中的 {name} revision 必须是 40 位小写十六进制 SHA")
    return revisions


def _validate_checkout(root: Path, expected_revision: str, name: str) -> None:
    root = root.expanduser().resolve(strict=True)
    if not root.is_dir():
        raise PatchError(f"{name} root 不是目录：{root}")
    top = Path(_git(root, "rev-parse", "--show-toplevel")).resolve(strict=True)
    if top != root:
        raise PatchError(f"{name} root 必须是 Git worktree 根目录：{root}（实际为 {top}）")
    actual_revision = _git(root, "rev-parse", "HEAD")
    if actual_revision != expected_revision:
        raise PatchError(
            f"{name} HEAD 不匹配：期望 {expected_revision}，实际 {actual_revision}"
        )
    dirty = _git(root, "status", "--porcelain", "--untracked-files=all")
    if dirty:
        raise PatchError(f"{name} checkout 有未提交或未跟踪修改；拒绝应用 patch：\n{dirty}")


def check_targets(targets: Sequence[Target]) -> None:
    """Validate both repositories and dry-run both patches without mutation."""
    for target in targets:
        _validate_checkout(target.root, target.revision, target.name)
        if not target.patch.is_file() or target.patch.is_symlink():
            raise PatchError(f"{target.name} patch 缺失或不是普通文件：{target.patch}")
    for target in targets:
        _git(target.root, "apply", "--check", "--whitespace=error", str(target.patch.resolve()))


def validate_contract_source(project_root: Path, allow_working_tree: bool = False) -> str:
    """Production pins describe HEAD bytes; dirty contract use requires an explicit test flag."""
    if Path(_git(project_root, "rev-parse", "--show-toplevel")).resolve() != project_root.resolve():
        raise PatchError("project root must be the main repository's Git worktree root")
    revision = _git(project_root, "rev-parse", "HEAD")
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise PatchError("main HEAD must be a full immutable revision")
    source = project_root / "gateway-contract"
    for relative in CONTRACT_INPUTS:
        path = source / relative
        if not path.exists() or path.is_symlink():
            raise PatchError(f"missing/unsafe contract input: {path}")
        if path.is_dir() and any(child.is_symlink() for child in path.rglob("*")):
            raise PatchError(f"contract symlinks are not supported: {path}")
    dirty = _git(project_root, "status", "--porcelain", "--untracked-files=all", "--",
                 *(f"gateway-contract/{name}" for name in CONTRACT_INPUTS))
    if dirty and not allow_working_tree:
        raise PatchError("main contract differs from HEAD; commit it first, or use --allow-working-tree-contract for local tests")
    return revision


def _copy_inputs(source: Path, target: Path, inputs: Sequence[str]) -> None:
    target.mkdir(parents=True, exist_ok=True)
    for relative in inputs:
        src, dst = source / relative, target / relative
        if dst.is_symlink():
            raise PatchError(f"refusing contract destination symlink: {dst}")
        if src.is_dir():
            if dst.exists():
                shutil.rmtree(dst)
            shutil.copytree(src, dst)
        else:
            shutil.copy2(src, dst)


def contract_hash(source: Path) -> str:
    lines = ["open-android-intelligence/v2/core-schema-hash"]
    lines.extend(f"{name}\tsha256:{hashlib.sha256((source / 'schemas' / name).read_bytes()).hexdigest()}" for name in CORE_SCHEMAS)
    return "sha256:" + hashlib.sha256(("\n".join(lines) + "\n").encode()).hexdigest()


def sync_contract(project_root: Path, targets: Sequence[Target], revision: str) -> None:
    source = project_root / "gateway-contract"
    for target in targets:
        pin_path = target.root / "contract-pin.json"
        pin = json.loads(pin_path.read_text(encoding="utf-8"))
        pin["revision"] = revision
        pin_path.write_text(json.dumps(pin, indent=2) + "\n", encoding="utf-8")
        _copy_inputs(source, target.root / "gateway-contract", CONTRACT_INPUTS)
        if target.name == "Hermes":
            (target.root / "gateway-contract" / ".pinned-revision").write_text(revision + "\n", encoding="utf-8")
        if target.name == "OpenClaw":
            # Compiled runtime/src is supplied by the reviewed patch. Raw JSON
            # schemas must retain exactly the same bytes as the phone contract.
            _copy_inputs(source, target.root / "runtime" / "gateway-contract",
                         ("schemas", "vectors", "core-dispatched-schemas.json"))
            manifest_path = target.root / "plugin-manifest.json"
            text = manifest_path.read_text(encoding="utf-8")
            text, count = re.subn(r'("capabilitySchemaHash"\s*:\s*")[^"]+',
                                  lambda match: match[1] + contract_hash(source), text, count=1)
            if count != 1:
                raise PatchError("OpenClaw static manifest is missing capabilitySchemaHash")
            manifest_path.write_text(text, encoding="utf-8")


def apply_targets(targets: Sequence[Target]) -> None:
    """Apply only after every checkout and patch passed the non-mutating check."""
    check_targets(targets)
    for target in targets:
        _git(target.root, "apply", "--whitespace=error", str(target.patch.resolve()))


def build_targets(
    project_root: Path,
    openclaw_root: Path,
    hermes_root: Path,
    patch_dir: Path,
) -> list[Target]:
    openclaw_revision, hermes_revision = pinned_revisions(patch_dir)
    return [
        Target("OpenClaw", openclaw_root.expanduser().resolve(), patch_dir / "openclaw.patch", openclaw_revision),
        Target("Hermes", hermes_root.expanduser().resolve(), patch_dir / "hermes.patch", hermes_revision),
    ]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--openclaw-root", required=True, type=Path, help="OpenClaw plugin Git worktree root")
    parser.add_argument("--hermes-root", required=True, type=Path, help="Hermes plugin Git worktree root")
    parser.add_argument(
        "--apply",
        action="store_true",
        help="apply both patches after all pin, clean-tree, and git apply --check validations pass",
    )
    parser.add_argument("--allow-working-tree-contract", action="store_true",
                        help="local tests only: use uncommitted contract bytes while still recording actual main HEAD")
    args = parser.parse_args(argv)
    script_dir = Path(__file__).resolve().parent
    project_root = script_dir.parents[2]
    try:
        targets = build_targets(project_root, args.openclaw_root, args.hermes_root, script_dir)
        revision = validate_contract_source(project_root, args.allow_working_tree_contract)
        if args.apply:
            apply_targets(targets)
            sync_contract(project_root, targets, revision)
            print(f"Applied both reviewed patches; copied the main contract and set both plugin pins to {revision}.")
            if args.allow_working_tree_contract:
                print("LOCAL TEST MODE: contract bytes may differ from HEAD; production pin gates must be rerun after committing.")
        else:
            check_targets(targets)
            print("两个 patch 均通过 pinned HEAD、clean tree 和 git apply --check；未修改文件。")
        return 0
    except (PatchError, OSError) as cause:
        print(f"conversation-management patch check failed: {cause}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
