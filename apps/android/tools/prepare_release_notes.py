#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


def _merged_pull_request_body(event: dict, expected_branch: str) -> str:
    pull_request = event.get("pull_request")
    if not isinstance(pull_request, dict):
        raise ValueError("The event does not contain a pull request")
    base = pull_request.get("base", {})
    if pull_request.get("merged") is not True or base.get("ref") != expected_branch:
        raise ValueError("The pull request was not merged into the expected branch")
    return pull_request.get("body") or ""


def _dispatch_pull_request_body(repository: str, commit: str, expected_branch: str) -> str:
    if not repository or not commit:
        raise ValueError("Manual release requires a repository and commit SHA")
    result = subprocess.run(
        ["gh", "api", f"repos/{repository}/commits/{commit}/pulls"],
        check=True,
        capture_output=True,
        text=True,
        env=os.environ.copy(),
    )
    pull_requests = json.loads(result.stdout)
    candidates = [
        pull_request for pull_request in pull_requests
        if pull_request.get("base", {}).get("ref") == expected_branch
        and pull_request.get("merged_at")
    ]
    if candidates:
        candidates.sort(key=lambda item: item.get("merged_at") or "")
        return candidates[-1].get("body") or ""
    result = subprocess.run(
        ["git", "show", "-s", "--format=%B", commit],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def prepare_release_notes(
    expected_branch: str,
    event_name: str,
    event_path: Path,
    repository: str,
    commit: str,
) -> str:
    event = json.loads(event_path.read_text(encoding="utf-8"))
    if expected_branch == "dev" and event_name in {"pull_request", "pull_request_target"}:
        return _merged_pull_request_body(event, expected_branch)
    if expected_branch == "dev" and event_name == "push":
        # push 是 dev 预览版的唯一发布入口。
        #
        # 合并 PR 产生的 push 提交本身关联着 PR，因此优先用 PR 正文当发布说明
        # （正文比 squash 后的单行提交说明信息量大得多）。查不到关联 PR 的
        # 直接 push（例如维护者手推）则回退到提交说明。
        #
        # 这样就不再需要 pull_request_target 触发：它取的是默认分支 main 上的
        # 工作流定义，而 checkout 的是 dev 的合并提交。一旦 main 与 dev 的脚本
        # 命名不同步，这条 run 必然失败，且失败原因与被合并的代码无关。
        try:
            body = _dispatch_pull_request_body(repository, commit, expected_branch)
        except (subprocess.CalledProcessError, ValueError, json.JSONDecodeError):
            body = ""
        if body.strip():
            return body
        result = subprocess.run(
            ["git", "log", "-1", "--format=%B"],
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout
    if expected_branch == "main" and event_name == "workflow_dispatch":
        return _dispatch_pull_request_body(repository, commit, expected_branch)
    raise ValueError("Unsupported release event")


def append_build_metadata(body: str, repository: Path | None = None) -> str:
    repo = repository or Path.cwd()
    try:
        count = subprocess.check_output(
            ["git", "-C", str(repo), "rev-list", "--count", "HEAD"],
            text=True,
        ).strip()
        sha = subprocess.check_output(
            ["git", "-C", str(repo), "rev-parse", "--short", "HEAD"],
            text=True,
        ).strip()
        date = subprocess.check_output(
            ["git", "-C", str(repo), "log", "-1", "--format=%ci", "HEAD"],
            text=True,
        ).strip()
    except Exception:
        return body

    metadata_header = (
        f"> **构建信息**\n"
        f"> - **提交编号**: 第 {count} 次提交 (`{sha}`)\n"
        f"> - **提交时间**: {date}\n\n"
    )
    trimmed_body = body.strip()
    if trimmed_body:
        return f"{metadata_header}### 变更说明\n\n{trimmed_body}\n"
    return metadata_header


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare release notes from the triggering pull request")
    parser.add_argument("--branch", choices=("dev", "main"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    event_path = Path(os.environ["GITHUB_EVENT_PATH"])
    notes = prepare_release_notes(
        args.branch,
        os.environ["GITHUB_EVENT_NAME"],
        event_path,
        os.environ.get("GITHUB_REPOSITORY", ""),
        os.environ.get("GITHUB_SHA", ""),
    )
    full_notes = append_build_metadata(notes)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(full_notes, encoding="utf-8")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, OSError, ValueError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
