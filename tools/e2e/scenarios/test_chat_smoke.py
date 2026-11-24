"""对话主链路冒烟 E2E 场景：引导 -> 登录/快照 -> 抽屉路由 -> 发送消息与接收回传。"""

from __future__ import annotations

import os
from typing import TYPE_CHECKING

import pytest

if TYPE_CHECKING:
    from tools.e2e.core import E2ERuntimeContext

SCENARIO_NAME = "chat_smoke"


def run(ctx: E2ERuntimeContext) -> None:
    """执行端到端对话冒烟场景。"""
    from tools.e2e.pages import ChatScreen, HomeScreen, OnboardingScreen

    # 清理策略完全遵循上下文中的 clear_app_state 标志（由 run.py 默认策略或 --no-clear-app-state 决定）
    clear_data = bool(ctx.clear_app_state)
    ctx.driver.start_app(
        ctx.package_name,
        ctx.activity_name,
        cold=True,
        clear_data=clear_data,
    )

    OnboardingScreen(ctx).complete_if_present()
    HomeScreen(ctx).connect_with_isolated_endpoint(ctx.auth)

    chat = ChatScreen(ctx)
    chat.wait_ready()
    chat.verify_drawer_route()
    chat.send_message("Hello OAI E2E")
    chat.assert_message_visible("Hello OAI E2E")
    chat.assert_message_visible(ctx.expect_reply)


@pytest.mark.skipif(
    os.environ.get("OAI_E2E_LIVE") != "1",
    reason="按需 Android E2E 场景，请使用 python3 tools/e2e/run.py --scenario chat_smoke 执行",
)
def test_chat_smoke() -> None:
    """按需真机/模拟器冒烟测试入口（仅在 OAI_E2E_LIVE=1 时执行）。"""
    from tools.e2e.run import main as e2e_main

    exit_code = e2e_main(["--scenario", SCENARIO_NAME])
    assert exit_code == 0
