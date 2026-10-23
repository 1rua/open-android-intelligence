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
    if not candidates:
        raise ValueError("Manual release requires a merged pull request for the selected commit")
    candidates.sort(key=lambda item: item.get("merged_at") or "")
    return candidates[-1].get("body") or ""


def prepare_release_notes(
    expected_branch: str,
    event_name: str,
    event_path: Path,
    repository: str,
    commit: str,
) -> str:
    event = json.loads(event_path.read_text(encoding="utf-8"))
    if event_name in {"pull_request", "pull_request_target"}:
        return _merged_pull_request_body(event, expected_branch)
    if expected_branch == "dev" and event_name == "push":
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
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(notes, encoding="utf-8")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, OSError, ValueError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
