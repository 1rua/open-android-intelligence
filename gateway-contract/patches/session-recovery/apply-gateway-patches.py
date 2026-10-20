#!/usr/bin/env python3
"""Check or apply the reviewed session-recovery patches to pinned Gateway checkouts.

This helper invokes Git with argument arrays (never a shell), does not fetch or
modify the checkouts in default mode, and refuses dirty or incorrectly pinned
repositories before checking either patch.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence


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
    args = parser.parse_args(argv)
    script_dir = Path(__file__).resolve().parent
    project_root = script_dir.parents[2]
    try:
        targets = build_targets(project_root, args.openclaw_root, args.hermes_root, script_dir)
        if args.apply:
            apply_targets(targets)
            print("已将两个 session-recovery patch 应用到固定 revision 的干净 checkout。")
        else:
            check_targets(targets)
            print("两个 patch 均通过 pinned HEAD、clean tree 和 git apply --check；未修改文件。")
        return 0
    except (PatchError, OSError) as cause:
        print(f"session-recovery patch 检查失败：{cause}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
