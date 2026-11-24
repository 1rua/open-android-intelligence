"""失败演练 E2E 场景：故意断言不存在的语义标签以验证优雅中断与标红报告。"""

from __future__ import annotations

import os
from typing import TYPE_CHECKING

import pytest

if TYPE_CHECKING:
    from tools.e2e.core import E2ERuntimeContext

SCENARIO_NAME = "failure_drill"


def run(ctx: E2ERuntimeContext) -> None:
    """执行失败留痕与优雅退出自检场景。"""
    from tools.e2e.core import BySemantics
    from tools.e2e.pages import BasePage

    clear_data = bool(ctx.clear_app_state) or ctx.auth.mode != "snapshot"
    ctx.driver.start_app(
        ctx.package_name,
        ctx.activity_name,
        cold=True,
        clear_data=clear_data,
    )

    page = BasePage(ctx)
    first_screen_locators = (
        BySemantics(tag_prefix="onboarding-"),
        BySemantics(tag="remote-login-root"),
        BySemantics(tag="chat-workbench-root"),
    )
    matched_loc, _ = page.wait_any(first_screen_locators)
    page.assert_visible(matched_loc, "确认首屏已加载并进入稳定态")

    # 故意断言缺失标签以触发 SmartWaitTimeoutError，验证现场截图与报告标红
    page.assert_visible(
        BySemantics(tag="intentional-missing-tag-drill"),
        "故意触发缺失语义断言",
        timeout=2.0,
    )


@pytest.mark.skipif(
    os.environ.get("OAI_E2E_LIVE") != "1",
    reason="按需 Android E2E 场景，请使用 python3 tools/e2e/run.py --scenario failure_drill 执行",
)
def test_failure_drill() -> None:
    """按需失败演练入口（仅在 OAI_E2E_LIVE=1 时执行，预期非零退出码）。"""
    from tools.e2e.run import main as e2e_main

    exit_code = e2e_main(["--scenario", SCENARIO_NAME])
    assert exit_code == 1
