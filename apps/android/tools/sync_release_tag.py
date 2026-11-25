#!/usr/bin/env python3
"""可靠同步 GitHub Release 对应的远端 Git Tag 到目标提交。

设计原则：
1. 禁止使用 `>/dev/null` 静默吞错：所有 API/Git 响应状态与正文都会显式输出（自动脱敏凭据）。
2. 明确区分三类结果：
   - 标签已指向目标提交（幂等跳过）；
   - 瞬态传输/网关/JSON 截断故障（自动重试并在重试耗尽后降级至带鉴权的 `git push --force`）；
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

SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
REPO_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
HTTP_STATUS_PATTERN = re.compile(r"^HTTP/\S+\s+(\d{3})\b", re.IGNORECASE)

TRANSIENT_HTTP_STATUSES = {408, 429, 500, 502, 503, 504}
POLICY_HTTP_STATUSES = {401, 403, 422}
TRANSIENT_ERROR_MARKERS = (
    "unexpected end of json input",
    "unexpected eof",
    "eof",
    "connection reset",
    "connection refused",
    "timeout",
    "timed out",
    "temporary failure",
    "tls handshake",
    "bad gateway",
    "service unavailable",
    "gateway timeout",
)
POLICY_GIT_MARKERS = (
    "remote rejected",
    "protected",
    "hook declined",
    "permission",
    "denied",
    "403",
    "401",
    "rule",
)


class TagSyncError(RuntimeError):
    """标签同步失败基类。"""


class PolicyRejectionError(TagSyncError):
    """远端规则集、Tag 保护或令牌权限明确拒绝更新。"""


class TransientApiError(TagSyncError):
    """GitHub API 瞬态网络故障、5xx 或 JSON 响应截断。"""


@dataclass(frozen=True)
class ApiResponse:
    status: int | None
    headers: str
    body: str
    stderr: str
    returncode: int

    def summary(self) -> str:
        parts = [f"returncode={self.returncode}", f"http_status={self.status if self.status is not None else 'unknown'}"]
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


def parse_http_response(raw_stdout: str) -> tuple[int | None, str, str]:
    """解析 `gh api -i` 输出中的最后一个 HTTP 状态行、响应头与正文。"""
    normalized = raw_stdout.replace("\r\n", "\n")
    if not normalized.strip():
        return None, "", ""

    remaining = normalized
    last_status: int | None = None
    last_headers = ""

    while True:
        first_line = remaining.split("\n", 1)[0]
        match = HTTP_STATUS_PATTERN.match(first_line.strip())
        if not match:
            break
        last_status = int(match.group(1))
        if "\n\n" in remaining:
            header_block, body_block = remaining.split("\n\n", 1)
            last_headers = header_block
            remaining = body_block
            # 处理 100 Continue 或重定向链中的后续 HTTP 响应头。
            if HTTP_STATUS_PATTERN.match(remaining.split("\n", 1)[0].strip()):
                continue
            return last_status, last_headers, remaining
        return last_status, remaining, ""

    return last_status, last_headers, remaining


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
    status, headers, body = parse_http_response(proc.stdout)
    return ApiResponse(
        status=status,
        headers=mask_secret(headers, token),
        body=mask_secret(body, token),
        stderr=mask_secret(proc.stderr, token),
        returncode=proc.returncode,
    )


def is_transient_response(response: ApiResponse) -> bool:
    if response.status in TRANSIENT_HTTP_STATUSES:
        return True
    combined = f"{response.stderr}\n{response.body}".lower()
    if any(marker in combined for marker in TRANSIENT_ERROR_MARKERS):
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


def invoke_with_retry(
    action_name: str,
    action,
    *,
    max_attempts: int,
    retry_delay: float,
    sleep_fn=time.sleep,
):
    last_error: TransientApiError | None = None
    for attempt in range(1, max_attempts + 1):
        try:
            return action()
        except TransientApiError as exc:
            last_error = exc
            print(f"[sync-release-tag] {action_name} 第 {attempt}/{max_attempts} 次遇到瞬态异常：{exc}")
            if attempt < max_attempts and retry_delay > 0:
                sleep_fn(retry_delay * attempt)
    assert last_error is not None
    raise last_error


def get_remote_tag_state(
    repo: str,
    release_tag: str,
    *,
    token: str,
    max_attempts: int,
    retry_delay: float,
    sleep_fn=time.sleep,
) -> tuple[bool, str | None, str | None]:
    """查询远端标签现状，返回 (exists, target_commit_sha, object_type)。"""
    endpoint = f"repos/{repo}/git/ref/tags/{release_tag}"

    def _fetch() -> tuple[bool, str | None, str | None]:
        response = call_gh_api(endpoint, method="GET", token=token)
        print(f"[sync-release-tag] GET {endpoint} -> {response.summary()}")
        if response.status == 404:
            return False, None, None
        if response.status in POLICY_HTTP_STATUSES:
            raise PolicyRejectionError(f"读取远端标签 {release_tag} 被拒绝：{response.summary()}")
        if response.returncode != 0 or response.status != 200:
            if is_transient_response(response):
                raise TransientApiError(f"读取远端标签 {release_tag} 失败：{response.summary()}")
            raise TagSyncError(f"读取远端标签 {release_tag} 异常：{response.summary()}")

        payload = parse_json_body(response, f"GET {endpoint}")
        object_sha, object_type = extract_ref_object(payload, f"GET {endpoint}")
        if object_type == "commit":
            return True, object_sha, object_type

        if object_type == "tag":
            tag_endpoint = f"repos/{repo}/git/tags/{object_sha}"
            tag_response = call_gh_api(tag_endpoint, method="GET", token=token)
            print(f"[sync-release-tag] GET {tag_endpoint} -> {tag_response.summary()}")
            if tag_response.status in POLICY_HTTP_STATUSES:
                raise PolicyRejectionError(f"读取附注标签对象 {object_sha} 被拒绝：{tag_response.summary()}")
            if tag_response.returncode != 0 or tag_response.status != 200:
                if is_transient_response(tag_response):
                    raise TransientApiError(f"读取附注标签对象 {object_sha} 失败：{tag_response.summary()}")
                raise TagSyncError(f"读取附注标签对象 {object_sha} 异常：{tag_response.summary()}")
            tag_payload = parse_json_body(tag_response, f"GET {tag_endpoint}")
            peeled_sha, _ = extract_ref_object(tag_payload, f"GET {tag_endpoint}")
            return True, peeled_sha, object_type

        return True, object_sha, object_type

    return invoke_with_retry(
        f"GET refs/tags/{release_tag}",
        _fetch,
        max_attempts=max_attempts,
        retry_delay=retry_delay,
        sleep_fn=sleep_fn,
    )


def patch_remote_tag(
    repo: str,
    release_tag: str,
    target_sha: str,
    *,
    token: str,
    max_attempts: int,
    retry_delay: float,
    sleep_fn=time.sleep,
) -> None:
    endpoint = f"repos/{repo}/git/refs/tags/{release_tag}"

    def _patch() -> None:
        response = call_gh_api(
            endpoint,
            method="PATCH",
            fields=[("sha", target_sha, False), ("force", "true", True)],
            token=token,
        )
        print(f"[sync-release-tag] PATCH {endpoint} -> {response.summary()}")
        if response.status in POLICY_HTTP_STATUSES:
            raise PolicyRejectionError(f"更新远端标签 {release_tag} 被策略或权限拒绝：{response.summary()}")
        if response.returncode != 0 or response.status not in {200, 201}:
            if is_transient_response(response):
                raise TransientApiError(f"PATCH {endpoint} 瞬态失败：{response.summary()}")
            raise TagSyncError(f"PATCH {endpoint} 失败：{response.summary()}")
        payload = parse_json_body(response, f"PATCH {endpoint}")
        updated_sha, _ = extract_ref_object(payload, f"PATCH {endpoint}")
        if updated_sha != target_sha:
            raise TagSyncError(f"PATCH {endpoint} 返回 SHA 不匹配：期望 {target_sha}，实际 {updated_sha}")

    invoke_with_retry(
        f"PATCH refs/tags/{release_tag}",
        _patch,
        max_attempts=max_attempts,
        retry_delay=retry_delay,
        sleep_fn=sleep_fn,
    )


def create_remote_tag(
    repo: str,
    release_tag: str,
    target_sha: str,
    *,
    token: str,
    max_attempts: int,
    retry_delay: float,
    sleep_fn=time.sleep,
) -> bool:
    """创建远端标签；若遇到并发已创建（Reference already exists）则返回 False 提示改走 PATCH。"""
    endpoint = f"repos/{repo}/git/refs"

    def _post() -> bool:
        response = call_gh_api(
            endpoint,
            method="POST",
            fields=[("ref", f"refs/tags/{release_tag}", False), ("sha", target_sha, False)],
            token=token,
        )
        print(f"[sync-release-tag] POST {endpoint} -> {response.summary()}")
        if response.status == 422 and "reference already exists" in response.body.lower():
            print(f"[sync-release-tag] 标签 {release_tag} 已被并发创建，切换为 PATCH 强制更新。")
            return False
        if response.status in POLICY_HTTP_STATUSES:
            raise PolicyRejectionError(f"创建远端标签 {release_tag} 被策略或权限拒绝：{response.summary()}")
        if response.returncode != 0 or response.status not in {200, 201}:
            if is_transient_response(response):
                raise TransientApiError(f"POST {endpoint} 瞬态失败：{response.summary()}")
            raise TagSyncError(f"POST {endpoint} 失败：{response.summary()}")
        payload = parse_json_body(response, f"POST {endpoint}")
        created_sha, _ = extract_ref_object(payload, f"POST {endpoint}")
        if created_sha != target_sha:
            raise TagSyncError(f"POST {endpoint} 返回 SHA 不匹配：期望 {target_sha}，实际 {created_sha}")
        return True

    return invoke_with_retry(
        f"POST refs/tags/{release_tag}",
        _post,
        max_attempts=max_attempts,
        retry_delay=retry_delay,
        sleep_fn=sleep_fn,
    )


def _git_auth_header(token: str) -> str:
    encoded = base64.b64encode(f"x-access-token:{token}".encode("utf-8")).decode("ascii")
    return f"AUTHORIZATION: basic {encoded}"


def push_tag_via_git_fallback(
    repo: str,
    release_tag: str,
    target_sha: str,
    *,
    token: str,
) -> None:
    """当 GitHub REST API 持续返回瞬态传输/JSON 截断故障时，使用 Git Smart-HTTP 原子强制推送兜底。"""
    remote_url = f"https://github.com/{repo}.git"
    auth_header = _git_auth_header(token)
    print(f"[sync-release-tag] 切换至 Git Smart-HTTP 兜底更新 refs/tags/{release_tag} -> {target_sha}")
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
    push_out = mask_secret(push_proc.stdout.strip(), token)
    push_err = mask_secret(push_proc.stderr.strip(), token)
    print(f"[sync-release-tag] git push returncode={push_proc.returncode}")
    if push_out:
        print(f"[sync-release-tag] git push stdout: {push_out}")
    if push_err:
        print(f"[sync-release-tag] git push stderr: {push_err}")
    if push_proc.returncode != 0:
        combined = f"{push_out}\n{push_err}".lower()
        if any(marker in combined for marker in POLICY_GIT_MARKERS):
            raise PolicyRejectionError(
                f"Git 推送 refs/tags/{release_tag} 被远端策略或权限拒绝：{push_err or push_out}"
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
    verify_out = mask_secret(verify_proc.stdout.strip(), token)
    verify_err = mask_secret(verify_proc.stderr.strip(), token)
    print(f"[sync-release-tag] git ls-remote returncode={verify_proc.returncode} output={verify_out}")
    if verify_proc.returncode != 0:
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


def sync_release_tag(
    repo: str,
    release_tag: str,
    target_sha: str,
    *,
    token: str,
    max_attempts: int = 3,
    retry_delay: float = 2.0,
    sleep_fn=time.sleep,
) -> None:
    if not REPO_PATTERN.fullmatch(repo):
        raise ValueError(f"无效的仓库标识：{repo!r}")
    if not release_tag or any(ch.isspace() for ch in release_tag) or ".." in release_tag or release_tag.startswith("/"):
        raise ValueError(f"无效的发布标签名：{release_tag!r}")
    if not SHA_PATTERN.fullmatch(target_sha):
        raise ValueError(f"目标提交必须是 40 位小写十六进制 SHA：{target_sha!r}")
    if not token.strip():
        raise ValueError("缺少 GH_TOKEN 环境变量，无法更新远端标签。")

    print(f"[sync-release-tag] 开始同步 {repo} 的 refs/tags/{release_tag} -> {target_sha}")
    try:
        exists, current_sha, object_type = get_remote_tag_state(
            repo,
            release_tag,
            token=token,
            max_attempts=max_attempts,
            retry_delay=retry_delay,
            sleep_fn=sleep_fn,
        )
        if exists and current_sha == target_sha and object_type == "commit":
            print(f"[sync-release-tag] refs/tags/{release_tag} 已指向目标提交 {target_sha}，无需重复更新。")
            return
        if exists:
            print(
                f"[sync-release-tag] 远端 refs/tags/{release_tag} 当前指向 {current_sha}（type={object_type}），"
                f"准备更新为 {target_sha}。"
            )
            patch_remote_tag(
                repo,
                release_tag,
                target_sha,
                token=token,
                max_attempts=max_attempts,
                retry_delay=retry_delay,
                sleep_fn=sleep_fn,
            )
        else:
            print(f"[sync-release-tag] 远端不存在 refs/tags/{release_tag}，准备创建并指向 {target_sha}。")
            created = create_remote_tag(
                repo,
                release_tag,
                target_sha,
                token=token,
                max_attempts=max_attempts,
                retry_delay=retry_delay,
                sleep_fn=sleep_fn,
            )
            if not created:
                patch_remote_tag(
                    repo,
                    release_tag,
                    target_sha,
                    token=token,
                    max_attempts=max_attempts,
                    retry_delay=retry_delay,
                    sleep_fn=sleep_fn,
                )
        print(f"[sync-release-tag] 已通过 GitHub API 成功同步 refs/tags/{release_tag} -> {target_sha}")
    except TransientApiError as api_error:
        print(f"::warning::GitHub REST API 同步标签遇到瞬态错误（{api_error}），尝试使用 Git Smart-HTTP 强制推送兜底。")
        push_tag_via_git_fallback(repo, release_tag, target_sha, token=token)
        print(f"[sync-release-tag] 已通过 Git 兜底成功同步 refs/tags/{release_tag} -> {target_sha}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="可靠更新 GitHub Release 对应的远端 Git Tag。")
    parser.add_argument("--repo", required=True, help="GitHub 仓库名（owner/name）")
    parser.add_argument("--release-tag", required=True, help="待同步的发布标签名（例如 v2.2.1-dev）")
    parser.add_argument("--sha", required=True, help="目标提交的 40 位小写 SHA")
    parser.add_argument("--max-attempts", type=int, default=3, help="REST API 瞬态失败时的最大重试次数")
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
            max_attempts=max(1, args.max_attempts),
            retry_delay=max(0.0, args.retry_delay),
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
