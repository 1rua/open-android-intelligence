#!/usr/bin/env python3
"""Check that the Hermes plugin pins this repository's contract.

The Gateway plugin ships in its own repository and fetches the contract at a
pinned commit, so nothing in either repository would fail if that pin drifted
away from the schemas shipped here. The phone would simply be told
``PROTOCOL_INCOMPATIBLE`` at negotiation time.

This gate compares the plugin's pinned revision with the commit that carries
this contract and fails when the plugin is behind, so the bump is made here and
in the plugin together rather than discovered on a device.

Set ``HERMES_PLUGIN_ROOT`` to a checkout of the plugin repository. Without it the
gate is skipped with a stated reason rather than silently passing.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
CONTRACT_ROOT = TOOLS_DIR.parent
REPO_ROOT = CONTRACT_ROOT.parent
PLUGIN_ENV = "HERMES_PLUGIN_ROOT"


def head_revision() -> str:
    return subprocess.run(
        ["git", "-C", str(REPO_ROOT), "rev-parse", "HEAD"],
        check=True, capture_output=True, text=True,
    ).stdout.strip()


def normalise_repository(url: str) -> str:
    """``https://host/owner/repo.git`` and ``git@host:owner/repo`` → ``host/owner/repo``."""
    identity = url.strip().removesuffix(".git")
    if identity.startswith("git@"):
        identity = identity.split(":", 1)[-1]
    for scheme in ("https://", "http://", "ssh://"):
        if identity.startswith(scheme):
            identity = identity[len(scheme):]
    return identity.lower().strip("/")


def this_repository() -> str:
    url = subprocess.run(
        ["git", "-C", str(REPO_ROOT), "remote", "get-url", "origin"],
        check=True, capture_output=True, text=True,
    ).stdout.strip()
    return normalise_repository(url)


def contract_matches(revision: str):
    """Whether the pinned commit carries the same contract as this checkout.

    None means the pinned commit is not present locally, so the question cannot
    be answered here. Comparing the contract tree rather than the commit is what
    makes this useful: an application commit that touches no schema leaves the
    pin correct, and only a real contract change has to bump it.
    """
    present = subprocess.run(
        ["git", "-C", str(REPO_ROOT), "cat-file", "-e", f"{revision}^{{commit}}"],
        capture_output=True,
    )
    if present.returncode != 0:
        return None
    # Only the contract proper: the runners under gateway-contract/tools are
    # repository tooling, not part of what the phone negotiates against.
    unchanged = subprocess.run(
        [
            "git", "-C", str(REPO_ROOT), "diff", "--quiet", revision, "--",
            "gateway-contract/schemas", "gateway-contract/vectors",
        ],
        capture_output=True,
    )
    return unchanged.returncode == 0


def main() -> int:
    plugin_root = os.environ.get(PLUGIN_ENV, "").strip()
    if not plugin_root:
        print(
            f"跳过：未设置 {PLUGIN_ENV}，无法校验插件仓锁定的契约提交。"
            f"CI 插件门禁会设置该变量。"
        )
        return 0

    pin_path = Path(plugin_root).expanduser() / "contract-pin.json"
    if not pin_path.is_file():
        print(f"❌ 插件仓缺少 contract-pin.json: {pin_path}")
        return 1

    pin = json.loads(pin_path.read_text(encoding="utf-8"))
    revision = str(pin.get("revision") or "")
    if len(revision) != 40:
        print(f"❌ contract-pin.json 的 revision 必须是 40 位提交，当前为 {revision or '(空)'}")
        return 1

    expected = this_repository()
    repository = normalise_repository(str(pin.get("repository") or ""))
    if repository != expected:
        print(
            f"❌ contract-pin.json 指向的仓库 {pin.get('repository')} 不是本仓库（应为 {expected}）"
        )
        return 1

    current = head_revision()
    matches = contract_matches(revision)
    if matches is None:
        print(
            f"跳过：本地没有插件仓锁定的提交 {revision}，无法比对契约内容。\n"
            f"   请在完整（浅克隆请加 --depth 0）检出中运行，或先 fetch 该提交。"
        )
        return 0
    if matches:
        print(
            f"✅ 插件仓锁定提交 {revision[:12]} 的契约与本仓 HEAD {current[:12]} 一致"
        )
        return 0

    print(
        f"❌ 插件仓锁定的契约与本仓库不一致。\n"
        f"   插件仓 pin: {revision}\n"
        f"   本仓 HEAD:  {current}\n"
        f"   本仓的 gateway-contract/ 相对该提交已发生变化，"
        f"必须把插件仓的 contract-pin.json 升级并发布，"
        f"否则手机端将在协商阶段收到 PROTOCOL_INCOMPATIBLE。"
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
