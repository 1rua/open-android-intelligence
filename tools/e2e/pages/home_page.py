"""登录页与远程 Host/Agent 选择页面对象模型。"""

from __future__ import annotations

import time
from collections.abc import Sequence

from tools.e2e.core import (
    BySemantics,
    IsolatedAuthEndpoint,
    SemanticNode,
    SmartWaitTimeoutError,
)
from tools.e2e.pages.base_page import BasePage


class HomeScreen(BasePage):
    """封装登录入口、自建服务端凭证填写以及 Host/Agent 选择连接流程。"""

    WORKBENCH_ROOT = BySemantics(tag="chat-workbench-root")
    LOGIN_ROOT = BySemantics(tag="remote-login-root")
    LOGIN_CARD = BySemantics(tag="remote-login-card")
    OFFICIAL_LOGIN = BySemantics(tag="remote-official-login")
    SELF_HOSTED_LOGIN = BySemantics(tag="remote-self-hosted-login")
    TOGGLE_MODE = BySemantics(tag="remote-login-toggle-mode")
    ORIGIN_INPUT = BySemantics(tag="remote-login-origin-input")
    ACCOUNT_INPUT = BySemantics(tag="remote-login-account-input")
    PASSWORD_INPUT = BySemantics(tag="remote-login-password-input")
    SUBMIT_BUTTON = BySemantics(tag="remote-login-submit")
    SUBMIT_ENABLED = BySemantics(tag="remote-login-submit", enabled=True)

    AGENT_HARNESS = BySemantics(tag="remote-agent-item:harness")
    AGENT_ANY = BySemantics(tag_prefix="remote-agent-item:")
    HOST_ANY = BySemantics(tag_prefix="remote-host-item:")
    HOST_ANY_CLICKABLE = BySemantics(tag_prefix="remote-host-item:", clickable=True)

    # Android 13+ 登录后可能触发的系统通知/运行时权限授权弹窗按钮
    PERMISSION_ALLOW = BySemantics(tag="permission_allow_button")
    PERMISSION_ALLOW_ALT = BySemantics(tag="permission_allow_foreground_only_button")

    def connect_with_isolated_endpoint(
        self,
        auth: IsolatedAuthEndpoint,
        *,
        timeout: float = 30.0,
    ) -> None:
        """按当前认证隔离模式直通工作台或完成自建服务端登录与 Agent 路由。"""
        landing_locators = (
            self.WORKBENCH_ROOT,
            self.LOGIN_CARD,
            self.AGENT_HARNESS,
            self.AGENT_ANY,
            self.HOST_ANY,
        )
        matched_loc, _ = self._wait_any_dismissing_permissions(
            landing_locators,
            timeout=timeout,
        )

        if matched_loc == self.WORKBENCH_ROOT:
            self.assert_visible(
                self.WORKBENCH_ROOT,
                "校验已登录态直接进入工作台",
                timeout=max(timeout, 30.0),
            )
            return

        if matched_loc == self.LOGIN_CARD:
            self._submit_self_hosted_login(auth, timeout=timeout)

        self._select_host_and_agent(auth, timeout=timeout)

    def _wait_any_dismissing_permissions(
        self,
        locators: Sequence[BySemantics],
        *,
        timeout: float,
    ) -> tuple[BySemantics, SemanticNode]:
        """在等待目标语义节点期间自动允许并关闭系统权限弹窗，防止遮挡底层界面。"""
        deadline = time.monotonic() + max(timeout, 1.0)
        probe_locators = (*locators, self.PERMISSION_ALLOW, self.PERMISSION_ALLOW_ALT)
        while True:
            remaining = max(0.5, deadline - time.monotonic())
            matched_loc, node = self.wait_any(probe_locators, timeout=remaining)
            if matched_loc in (self.PERMISSION_ALLOW, self.PERMISSION_ALLOW_ALT):
                self.click(matched_loc, "允许系统通知权限弹窗", timeout=5.0)
                try:
                    self.ctx.wait.wait_until_gone(matched_loc, timeout=5.0)
                except SmartWaitTimeoutError:
                    pass
                if time.monotonic() >= deadline:
                    # 弹窗刚刚处理完但总超时已耗尽时，再给目标节点一次完整稳定判定窗口
                    deadline = time.monotonic() + min(timeout, 15.0)
                continue
            return matched_loc, node

    def _submit_self_hosted_login(
        self,
        auth: IsolatedAuthEndpoint,
        *,
        timeout: float,
    ) -> None:
        """切换到自建服务模式并提交隔离测试凭据。"""
        if not self.is_visible(self.SELF_HOSTED_LOGIN, timeout=1.0) or self.is_visible(
            self.OFFICIAL_LOGIN, timeout=0.6
        ):
            self.click(self.TOGGLE_MODE, "切换为自建 Remote 服务登录模式")
            self.wait_visible(self.SELF_HOSTED_LOGIN, timeout=timeout)

        self.fill(self.ORIGIN_INPUT, auth.origin, "填写隔离测试服务端地址")
        self.fill(self.ACCOUNT_INPUT, auth.fixture_account, "填写隔离测试账号")
        self.fill(
            self.PASSWORD_INPUT,
            auth.fixture_token,
            "填写隔离测试令牌",
            secret=True,
        )

        # 若软键盘遮挡了登录卡片底部的提交按钮，先收起键盘后再等待按钮可用
        if not self.is_visible(self.SUBMIT_ENABLED, timeout=1.0):
            if self.is_visible(self.LOGIN_CARD, timeout=0.5) and not self.is_visible(
                self.SUBMIT_BUTTON, timeout=0.5
            ):
                self.ctx.driver.hide_keyboard()

        # 等待表单校验通过使提交按钮可点击后再触发登录
        self.wait_visible(self.SUBMIT_ENABLED, timeout=timeout)
        self.click(self.SUBMIT_ENABLED, "提交自建服务端登录", timeout=timeout)

    def _select_host_and_agent(
        self,
        auth: IsolatedAuthEndpoint,
        *,
        timeout: float,
    ) -> None:
        """在 Host/Agent 选择页等待发现结果并路由进入对话工作台。"""
        post_login_locators = (
            self.WORKBENCH_ROOT,
            self.AGENT_HARNESS,
            self.AGENT_ANY,
            BySemantics(text_contains=auth.host_name),
            self.HOST_ANY,
        )
        matched_loc, _ = self._wait_any_dismissing_permissions(
            post_login_locators,
            timeout=timeout,
        )
        if matched_loc == self.WORKBENCH_ROOT:
            self.assert_visible(self.WORKBENCH_ROOT, "已自动路由进入对话工作台", timeout=max(timeout, 30.0))
            return

        # 单台在线 Host 时客户端会自动发现 Agent，留出足够双次语义采样窗口避免在 busy 禁用态重复点击 Host
        if matched_loc not in (self.AGENT_HARNESS, self.AGENT_ANY):
            try:
                matched_loc, _ = self._wait_any_dismissing_permissions(
                    (self.WORKBENCH_ROOT, self.AGENT_HARNESS, self.AGENT_ANY),
                    timeout=6.0,
                )
            except SmartWaitTimeoutError:
                host_by_name = BySemantics(text_contains=auth.host_name, clickable=True)
                target_loc, _ = self._wait_any_dismissing_permissions(
                    (
                        self.WORKBENCH_ROOT,
                        self.AGENT_HARNESS,
                        self.AGENT_ANY,
                        host_by_name,
                        self.HOST_ANY_CLICKABLE,
                    ),
                    timeout=timeout,
                )
                if target_loc in (host_by_name, self.HOST_ANY_CLICKABLE):
                    desc = (
                        f"选择目标远程主机: {auth.host_name}"
                        if target_loc == host_by_name
                        else "选择可用远程主机触发发现"
                    )
                    self.click(target_loc, desc, timeout=timeout)
                    matched_loc, _ = self._wait_any_dismissing_permissions(
                        (self.WORKBENCH_ROOT, self.AGENT_HARNESS, self.AGENT_ANY),
                        timeout=timeout,
                    )
                else:
                    matched_loc = target_loc

        if matched_loc == self.WORKBENCH_ROOT:
            self.assert_visible(self.WORKBENCH_ROOT, "已进入对话工作台", timeout=max(timeout, 30.0))
            return

        if matched_loc == self.AGENT_HARNESS or self.is_visible(self.AGENT_HARNESS, timeout=2.0):
            self.click(self.AGENT_HARNESS, "选择 Harness Agent 进入工作台")
        else:
            self.click(self.AGENT_ANY, "选择首个可用 Agent 进入工作台")

        # 路由切换后仍可能延迟弹出系统通知权限弹窗，先清障再断言工作台根容器稳定可见
        self._wait_any_dismissing_permissions(
            (self.WORKBENCH_ROOT,),
            timeout=max(timeout, 30.0),
        )
        self.assert_visible(self.WORKBENCH_ROOT, "验证路由进入对话工作台", timeout=max(timeout, 30.0))
