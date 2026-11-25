#!/usr/bin/env python3
"""可靠同步 GitHub Release 对应的远端 Git Tag 到目标提交。

设计原则：
1. 禁止使用 `>/dev/null` 静默吞错：所有 API/Git 响应状态与正文都会显式输出（自动脱敏凭据）。
2. 明确区分三类结果：
   - 标签已指向目标提交（幂等跳过）；
   - 瞬态传输/网关/JSON 截断故障（自动重试并在重试耗尽后降级至带鉴权且可重试的 `git push --force`）；
   - 远端策略/权限拒绝（HTTP 401/403/422 或 Git remote rejected，输出 `::error::` 诊断并立即失败）。
3. 更新后核对远端标签最终指向的提交 SHA，确保与目标提交逐字符一致。
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Callable

SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
REPO_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
HTTP_STATUS_PATTERN = re.compile(r"^HTTP/\S+\s+(\d{3})\b", re.IGNORECASE)

TRANSIENT_HTTP_STATUSES = {408, 429, 500, 502, 503, 504}
POLICY_HTTP_STATUSES = {401, 403, 422}

# 使用精确短语或词边界正则，避免 40 位提交 SHA（例如包含 401/403/eof）误触发错误分类。
TRANSIENT_ERROR_PATTERNS = (
    re.compile(r"unexpected end of json input", re.IGNORECASE),
    re.compile(r"unexpected eof", re.IGNORECASE),
    re.compile(r"(?:^|[\s:(\[])eof(?:$|[\s:)\],.])", re.IGNORECASE),
    re.compile(r"connection reset", re.IGNORECASE),
    re.compile(r"connection refused", re.IGNORECASE),
    re.compile(r"timed?\s*out", re.IGNORECASE),
    re.compile(r"temporary failure", re.IGNORECASE),
    re.compile(r"tls handshake", re.IGNORECASE),
    re.compile(r"bad gateway", re.IGNORECASE),
    re.compile(r"service unavailable", re.IGNORECASE),
    re.compile(r"gateway timeout", re.IGNORECASE),
    re.compile(r"the requested url returned error:\s*(?:429|5\d\d)\b", re.IGNORECASE),
    re.compile(r"rpc failed;\s*http\s*(?:429|5\d\d)\b", re.IGNORECASE),
)
POLICY_GIT_PATTERNS = (
    re.compile(r"\[remote rejected\]", re.IGNORECASE),
    re.compile(r"pre-receive hook declined", re.IGNORECASE),
    re.compile(r"protected (?:tag|branch|ref)", re.IGNORECASE),
    re.compile(r"repository rule violations?", re.IGNORECASE),
    re.compile(r"\bgh(?:006|013):", re.IGNORECASE),
    re.compile(r"permission(?:\s+to\s+\S+)?\s+denied", re.IGNORECASE),
    re.compile(r"write access to repository not granted", re.IGNORECASE),
    re.compile(r"the requested url returned error:\s*(?:401|403)\b", re.IGNORECASE),
    re.compile(r"authentication failed", re.IGNORECASE),
)


class TagSyncError(RuntimeError):
    """标签同步失败基类。"""


class PolicyRejectionError(TagSyncError):
    """远端规则集、Tag 保护或令牌权限明确拒绝更新。"""


class TransientApiError(TagSyncError):
    """GitHub API 或 Git 传输层瞬态故障、5xx 或 JSON 响应截断。"""


@dataclass(frozen=True)
class SyncOptions:
    """封装标签同步共用的鉴权与重试策略，避免在函数间重复透传零散参数。"""

    token: str
    max_attempts: int = 3
    retry_delay: float = 2.0
    sleep_fn: Callable[[float], None] = time.sleep

    def validate(self) -> None:
        if not self.token.strip():
            raise ValueError("缺少 GH_TOKEN 环境变量，无法更新远端标签。")
        if self.max_attempts < 1:
            raise ValueError(f"max_attempts 必须大于等于 1，实际为 {self.max_attempts}")
        if self.retry_delay < 0:
            raise ValueError(f"retry_delay 不能为负数，实际为 {self.retry_delay}")


@dataclass(frozen=True)
class ApiResponse:
    status: int | None
    body: str
    stderr: str
    returncode: int

    def summary(self) -> str:
        parts = [
            f"returncode={self.returncode}",
            f"http_status={self.status if self.status is not None else 'unknown'}",
        ]
        body_text = self.body.strip()
        stderr_text = self.stderr.strip()
        if body_text:
            parts.append(f"body={body_text}")
        if stderr_text:
            parts.append(f"stderr={stderr_text}")
        return " | ".join(parts)


def mask_secret(text: str, secret: str) -> str:
    """脱敏输出中的令牌及 Base64 鉴权头，防止误打印到 CI 日志。"""
    if not text or not secret:
        return text
    masked = text.replace(secret, "***")
    encoded = base64.b64encode(f"x-access-token:{secret}".encode("utf-8")).decode("ascii")
    return masked.replace(encoded, "***")


def parse_http_response(raw_stdout: str) -> tuple[int | None, str]:
    """解析 `gh api -i` 输出中的最后一个 HTTP 状态码与正文。"""
    normalized = raw_stdout.replace("\r\n", "\n")
    if not normalized.strip():
        return None, ""

    remaining = normalized
    last_status: int | None = None

    while True:
        first_line = remaining.split("\n", 1)[0]
        match = HTTP_STATUS_PATTERN.match(first_line.strip())
        if not match:
            break
        last_status = int(match.group(1))
        if "\n\n" in remaining:
            _, body_block = remaining.split("\n\n", 1)
            remaining = body_block
            # 处理 100 Continue 或重定向链中的后续 HTTP 响应头。
            if HTTP_STATUS_PATTERN.match(remaining.split("\n", 1)[0].strip()):
                continue
            return last_status, remaining
        return last_status, ""

    return last_status, remaining


def run_command(args: list[str], *, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, check=False, env=env)


def call_gh_api(
    endpoint: str,
    *,
    method: str = "GET",
    fields: list[tuple[str, str, bool]] | None = None,
    token: str,
) -> ApiResponse:
    """调用 `gh api -i` 并显式保留完整状态码、响应正文与错误输出。"""
    command = ["gh", "api", "-i"]
    if method != "GET":
        command.extend(["--method", method])
    command.append(endpoint)
    for key, value, typed in fields or []:
        command.extend(["-F" if typed else "-f", f"{key}={value}"])

    env = os.environ.copy()
    env["GH_TOKEN"] = token
    proc = run_command(command, env=env)
    status, body = parse_http_response(proc.stdout)
    return ApiResponse(
        status=status,
        body=mask_secret(body, token),
        stderr=mask_secret(proc.stderr, token),
        returncode=proc.returncode,
    )


def matches_any_pattern(text: str, patterns: tuple[re.Pattern[str], ...]) -> bool:
    return any(pattern.search(text) for pattern in patterns)


def is_transient_response(response: ApiResponse) -> bool:
    if response.status in TRANSIENT_HTTP_STATUSES:
        return True
    combined = f"{response.stderr}\n{response.body}"
    if matches_any_pattern(combined, TRANSIENT_ERROR_PATTERNS):
        return True
    return response.returncode != 0 and response.status is None


def parse_json_body(response: ApiResponse, context: str) -> dict[str, object]:
    raw = response.body.strip()
    if not raw:
        raise TransientApiError(f"{context} 返回空响应体（{response.summary()}）")
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise TransientApiError(f"{context} 返回了非完整 JSON（{exc}；{response.summary()}）") from exc
    if not isinstance(parsed, dict):
        raise TransientApiError(f"{context} 返回了非对象 JSON（{response.summary()}）")
    return parsed


def extract_ref_object(payload: dict[str, object], context: str) -> tuple[str, str]:
    obj = payload.get("object")
    if not isinstance(obj, dict):
        raise TransientApiError(f"{context} 缺少 object 字段：{payload!r}")
    sha = obj.get("sha")
    obj_type = obj.get("type")
    if not isinstance(sha, str) or not SHA_PATTERN.fullmatch(sha):
        raise TransientApiError(f"{context} 返回的 object.sha 无效：{sha!r}")
    if not isinstance(obj_type, str) or not obj_type:
        raise TransientApiError(f"{context} 返回的 object.type 无效：{obj_type!r}")
    return sha, obj_type


def ensure_api_success(
    response: ApiResponse,
    *,
    action_desc: str,
    endpoint: str,
    ok_statuses: set[int],
) -> dict[str, object]:
    """统一校验 GitHub API 响应分类（策略拒绝 / 瞬态重试 / 硬失败）并解析 JSON 正文。"""
    if response.status in POLICY_HTTP_STATUSES:
        raise PolicyRejectionError(f"{action_desc} 被策略或权限拒绝：{response.summary()}")
    if response.returncode != 0 or response.status not in ok_statuses:
        if is_transient_response(response):
            raise TransientApiError(f"{action_desc} 遇到瞬态错误：{response.summary()}")
        raise TagSyncError(f"{action_desc} 失败：{response.summary()}")
    return parse_json_body(response, endpoint)


def verify_response_ref_sha(payload: dict[str, object], endpoint: str, target_sha: str) -> None:
    actual_sha, _ = extract_ref_object(payload, endpoint)
    if actual_sha != target_sha:
        raise TagSyncError(f"{endpoint} 返回 SHA 不匹配：期望 {target_sha}，实际 {actual_sha}")


def invoke_with_retry(action_name: str, action: Callable[[], object], options: SyncOptions):
    options.validate()
    last_error: TransientApiError | None = None
    for attempt in range(1, options.max_attempts + 1):
        try:
            return action()
        except TransientApiError as exc:
            last_error = exc
            print(f"[sync-release-tag] {action_name} 第 {attempt}/{options.max_attempts} 次遇到瞬态异常：{exc}")
            if attempt < options.max_attempts and options.retry_delay > 0:
                options.sleep_fn(options.retry_delay * attempt)
    assert last_error is not None
    raise last_error


def get_remote_tag_state(
    repo: str,
    release_tag: str,
    options: SyncOptions,
) -> tuple[bool, str | None, str | None]:
    """查询远端标签现状，返回 (exists, target_commit_sha, object_type)。"""
    endpoint = f"repos/{repo}/git/ref/tags/{release_tag}"

    def _fetch() -> tuple[bool, str | None, str | None]:
        response = call_gh_api(endpoint, method="GET", token=options.token)
        print(f"[sync-release-tag] GET {endpoint} -> {response.summary()}")
        if response.status == 404:
            return False, None, None
        payload = ensure_api_success(
            response,
            action_desc=f"读取远端标签 {release_tag}",
            endpoint=f"GET {endpoint}",
            ok_statuses={200},
        )
        object_sha, object_type = extract_ref_object(payload, f"GET {endpoint}")
        if object_type == "tag":
            tag_endpoint = f"repos/{repo}/git/tags/{object_sha}"
            tag_response = call_gh_api(tag_endpoint, method="GET", token=options.token)
            print(f"[sync-release-tag] GET {tag_endpoint} -> {tag_response.summary()}")
            tag_payload = ensure_api_success(
                tag_response,
                action_desc=f"读取附注标签对象 {object_sha}",
                endpoint=f"GET {tag_endpoint}",
                ok_statuses={200},
            )
            peeled_sha, _ = extract_ref_object(tag_payload, f"GET {tag_endpoint}")
            return True, peeled_sha, object_type
        return True, object_sha, object_type

    return invoke_with_retry(f"GET refs/tags/{release_tag}", _fetch, options)


def patch_remote_tag(repo: str, release_tag: str, target_sha: str, options: SyncOptions) -> None:
    endpoint = f"repos/{repo}/git/refs/tags/{release_tag}"

    def _patch() -> None:
        response = call_gh_api(
            endpoint,
            method="PATCH",
            fields=[("sha", target_sha, False), ("force", "true", True)],
            token=options.token,
        )
        print(f"[sync-release-tag] PATCH {endpoint} -> {response.summary()}")
        payload = ensure_api_success(
            response,
            action_desc=f"更新远端标签 {release_tag}",
            endpoint=f"PATCH {endpoint}",
            ok_statuses={200, 201},
        )
        verify_response_ref_sha(payload, f"PATCH {endpoint}", target_sha)

    invoke_with_retry(f"PATCH refs/tags/{release_tag}", _patch, options)


def create_remote_tag(repo: str, release_tag: str, target_sha: str, options: SyncOptions) -> bool:
    """创建远端标签；若遇到并发已创建（Reference already exists）则返回 False 提示改走 PATCH。"""
    endpoint = f"repos/{repo}/git/refs"

    def _post() -> bool:
        response = call_gh_api(
            endpoint,
            method="POST",
            fields=[("ref", f"refs/tags/{release_tag}", False), ("sha", target_sha, False)],
            token=options.token,
        )
        print(f"[sync-release-tag] POST {endpoint} -> {response.summary()}")
        if response.status == 422 and "reference already exists" in response.body.lower():
            print(f"[sync-release-tag] 标签 {release_tag} 已被并发创建，切换为 PATCH 强制更新。")
            return False
        payload = ensure_api_success(
            response,
            action_desc=f"创建远端标签 {release_tag}",
            endpoint=f"POST {endpoint}",
            ok_statuses={200, 201},
        )
        verify_response_ref_sha(payload, f"POST {endpoint}", target_sha)
        return True

    return invoke_with_retry(f"POST refs/tags/{release_tag}", _post, options)


def _git_auth_header(token: str) -> str:
    encoded = base64.b64encode(f"x-access-token:{token}".encode("utf-8")).decode("ascii")
    return f"AUTHORIZATION: basic {encoded}"


def push_tag_via_git_fallback(
    repo: str,
    release_tag: str,
    target_sha: str,
    options: SyncOptions,
) -> None:
    """当 GitHub REST API 持续返回瞬态故障时，使用 Git Smart-HTTP 原子强制推送兜底（同样支持瞬态重试）。"""
    remote_url = f"https://github.com/{repo}.git"
    auth_header = _git_auth_header(options.token)
    print(f"[sync-release-tag] 切换至 Git Smart-HTTP 兜底更新 refs/tags/{release_tag} -> {target_sha}")

    def _git_push_and_verify() -> None:
        push_proc = run_command(
            [
                "git",
                "-c",
                f"http.extraheader={auth_header}",
                "push",
                "--force",
                remote_url,
                f"{target_sha}:refs/tags/{release_tag}",
            ]
        )
        push_out = mask_secret(push_proc.stdout.strip(), options.token)
        push_err = mask_secret(push_proc.stderr.strip(), options.token)
        print(f"[sync-release-tag] git push returncode={push_proc.returncode}")
        if push_out:
            print(f"[sync-release-tag] git push stdout: {push_out}")
        if push_err:
            print(f"[sync-release-tag] git push stderr: {push_err}")
        if push_proc.returncode != 0:
            combined = f"{push_out}\n{push_err}"
            if matches_any_pattern(combined, POLICY_GIT_PATTERNS):
                raise PolicyRejectionError(
                    f"Git 推送 refs/tags/{release_tag} 被远端策略或权限拒绝：{push_err or push_out}"
                )
            if matches_any_pattern(combined, TRANSIENT_ERROR_PATTERNS):
                raise TransientApiError(
                    f"Git 兜底推送 refs/tags/{release_tag} 遇到瞬态网络错误：{push_err or push_out}"
                )
            raise TagSyncError(f"Git 兜底推送 refs/tags/{release_tag} 失败：{push_err or push_out}")

        verify_proc = run_command(
            [
                "git",
                "-c",
                f"http.extraheader={auth_header}",
                "ls-remote",
                remote_url,
                f"refs/tags/{release_tag}",
            ]
        )
        verify_out = mask_secret(verify_proc.stdout.strip(), options.token)
        verify_err = mask_secret(verify_proc.stderr.strip(), options.token)
        print(f"[sync-release-tag] git ls-remote returncode={verify_proc.returncode} output={verify_out}")
        if verify_proc.returncode != 0:
            combined = f"{verify_out}\n{verify_err}"
            if matches_any_pattern(combined, TRANSIENT_ERROR_PATTERNS):
                raise TransientApiError(f"校验远端标签 {release_tag} 遇到瞬态网络错误：{verify_err or verify_out}")
            raise TagSyncError(f"校验远端标签 {release_tag} 失败：{verify_err or verify_out}")

        matched_sha = None
        expected_ref = f"refs/tags/{release_tag}"
        for line in verify_out.splitlines():
            fields = line.split()
            if len(fields) >= 2 and fields[1] == expected_ref:
                matched_sha = fields[0]
                break
        if matched_sha != target_sha:
            raise TagSyncError(
                f"远端标签校验不一致：refs/tags/{release_tag} 期望 {target_sha}，实际 {matched_sha or '缺失'}"
            )

    invoke_with_retry(f"git push refs/tags/{release_tag}", _git_push_and_verify, options)


def sync_release_tag(
    repo: str,
    release_tag: str,
    target_sha: str,
    *,
    token: str,
    max_attempts: int = 3,
    retry_delay: float = 2.0,
    sleep_fn: Callable[[float], None] = time.sleep,
) -> None:
    if not REPO_PATTERN.fullmatch(repo):
        raise ValueError(f"无效的仓库标识：{repo!r}")
    if not release_tag or any(ch.isspace() for ch in release_tag) or ".." in release_tag or release_tag.startswith("/"):
        raise ValueError(f"无效的发布标签名：{release_tag!r}")
    if not SHA_PATTERN.fullmatch(target_sha):
        raise ValueError(f"目标提交必须是 40 位小写十六进制 SHA：{target_sha!r}")

    options = SyncOptions(
        token=token,
        max_attempts=max_attempts,
        retry_delay=retry_delay,
        sleep_fn=sleep_fn,
    )
    options.validate()

    print(f"[sync-release-tag] 开始同步 {repo} 的 refs/tags/{release_tag} -> {target_sha}")
    try:
        exists, current_sha, object_type = get_remote_tag_state(repo, release_tag, options)
        if exists and current_sha == target_sha and object_type == "commit":
            print(f"[sync-release-tag] refs/tags/{release_tag} 已指向目标提交 {target_sha}，无需重复更新。")
            return
        if exists:
            print(
                f"[sync-release-tag] 远端 refs/tags/{release_tag} 当前指向 {current_sha}（type={object_type}），"
                f"准备更新为 {target_sha}。"
            )
            patch_remote_tag(repo, release_tag, target_sha, options)
        else:
            print(f"[sync-release-tag] 远端不存在 refs/tags/{release_tag}，准备创建并指向 {target_sha}。")
            if not create_remote_tag(repo, release_tag, target_sha, options):
                patch_remote_tag(repo, release_tag, target_sha, options)
        print(f"[sync-release-tag] 已通过 GitHub API 成功同步 refs/tags/{release_tag} -> {target_sha}")
    except TransientApiError as api_error:
        print(f"::warning::GitHub REST API 同步标签遇到瞬态错误（{api_error}），尝试使用 Git Smart-HTTP 强制推送兜底。")
        push_tag_via_git_fallback(repo, release_tag, target_sha, options)
        print(f"[sync-release-tag] 已通过 Git 兜底成功同步 refs/tags/{release_tag} -> {target_sha}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="可靠更新 GitHub Release 对应的远端 Git Tag。")
    parser.add_argument("--repo", required=True, help="GitHub 仓库名（owner/name）")
    parser.add_argument("--release-tag", required=True, help="待同步的发布标签名（例如 v2.2.1-dev）")
    parser.add_argument("--sha", required=True, help="目标提交的 40 位小写 SHA")
    parser.add_argument("--max-attempts", type=int, default=3, help="REST API 与 Git 兜底瞬态失败时的最大重试次数")
    parser.add_argument("--retry-delay", type=float, default=2.0, help="重试退避基础秒数")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""
    try:
        sync_release_tag(
            args.repo,
            args.release_tag,
            args.sha,
            token=token,
            max_attempts=args.max_attempts,
            retry_delay=args.retry_delay,
        )
    except PolicyRejectionError as exc:
        print(
            "::error::远端策略或权限拒绝更新发布标签。"
            f"请检查仓库 Tag 保护规则集（Rulesets）与工作流 `contents: write` 权限。详情：{exc}",
            file=sys.stderr,
        )
        return 1
    except (TagSyncError, ValueError) as exc:
        print(f"::error::同步发布标签失败：{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
