#!/usr/bin/env python3
"""
PR 来源分支与仓库身份校验模块。
用于确保 main 分支仅接受本仓库 dev 分支发起的 Pull Request。
"""
import os
import sys
from typing import Optional, Tuple


def validate_pr_source(
    base_ref: Optional[str],
    head_ref: Optional[str],
    base_repo: Optional[str],
    head_repo: Optional[str],
    base_repo_id: Optional[str] = None,
    head_repo_id: Optional[str] = None,
    is_fork: bool = False,
) -> Tuple[bool, str]:
    """
    校验 PR 来源分支和仓库身份。

    规则约束：
    1. 目标分支 (base_ref) 必须为 'main'。
    2. 来源分支 (head_ref) 必须为 'dev'。
    3. 目标仓库 (base_repo) 与来源仓库 (head_repo) 必须一致且非空。
    4. 严禁 Fork 仓库发起的 PR (is_fork 必须为 False)。
    5. 若传入仓库 ID，则两端 ID 必须完全一致。
    """
    base_ref_clean = (base_ref or "").strip()
    head_ref_clean = (head_ref or "").strip()
    base_repo_clean = (base_repo or "").strip()
    head_repo_clean = (head_repo or "").strip()

    if not base_ref_clean or not head_ref_clean:
        return False, "缺少必要分支信息 (base_ref 或 head_ref 为空)"

    if base_ref_clean != "main":
        return False, f"非法目标分支: 仅允许以 'main' 为目标分支，当前为 '{base_ref_clean}'"

    if head_ref_clean != "dev":
        return False, (
            f"非法来源分支: main 分支仅接受来自 'dev' 分支的合并请求，"
            f"当前来源为 '{head_ref_clean}'。请先将变更合并至 'dev' 分支！"
        )

    if not base_repo_clean or not head_repo_clean:
        return False, "缺少必要仓库信息 (base_repo 或 head_repo 为空)"

    if base_repo_clean.lower() != head_repo_clean.lower():
        return False, (
            f"非法来源仓库: 来源仓库 '{head_repo_clean}' 与目标仓库 '{base_repo_clean}' 不一致，"
            f"拒绝跨仓库合并！"
        )

    if is_fork:
        return False, "非法来源仓库: 拒绝来自 Fork 仓库的合并请求！"

    if base_repo_id is not None and head_repo_id is not None:
        base_id_str = str(base_repo_id).strip()
        head_id_str = str(head_repo_id).strip()
        if base_id_str and head_id_str and base_id_str != head_id_str:
            return False, (
                f"非法来源仓库: 仓库 ID 不匹配 (base: {base_id_str}, head: {head_id_str})！"
            )

    return True, (
        f"校验通过: 允许合法的本仓库 dev -> main 合并请求 "
        f"({head_repo_clean}:{head_ref_clean} -> {base_repo_clean}:{base_ref_clean})"
    )


def main() -> None:
    base_ref = os.environ.get("BASE_REF")
    head_ref = os.environ.get("HEAD_REF")
    base_repo = os.environ.get("BASE_REPO")
    head_repo = os.environ.get("HEAD_REPO")
    base_repo_id = os.environ.get("BASE_REPO_ID")
    head_repo_id = os.environ.get("HEAD_REPO_ID")
    is_fork_raw = os.environ.get("IS_FORK", "false").strip().lower()
    is_fork = is_fork_raw in ("true", "1", "yes")

    print("=== PR 来源分支与仓库身份校验 ===")
    print(f"目标分支 (Base Ref): {base_ref}")
    print(f"来源分支 (Head Ref): {head_ref}")
    print(f"目标仓库 (Base Repo): {base_repo}")
    print(f"来源仓库 (Head Repo): {head_repo}")
    print(f"是否 Fork: {is_fork}")

    ok, msg = validate_pr_source(
        base_ref=base_ref,
        head_ref=head_ref,
        base_repo=base_repo,
        head_repo=head_repo,
        base_repo_id=base_repo_id,
        head_repo_id=head_repo_id,
        is_fork=is_fork,
    )

    if not ok:
        print(f"::error::{msg}", file=sys.stderr)
        print(f"❌ 校验失败: {msg}", file=sys.stderr)
        sys.exit(1)

    print(f"✅ {msg}")
    sys.exit(0)


if __name__ == "__main__":
    main()

