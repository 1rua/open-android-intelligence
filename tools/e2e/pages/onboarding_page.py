"""首次启动引导流程页面对象模型。"""

from __future__ import annotations

from tools.e2e.core import BySemantics, SmartWaitTimeoutError
from tools.e2e.pages.base_page import BasePage


class OnboardingScreen(BasePage):
    """封装首次启动引导流（欢迎 -> 外观 -> 权限 -> 部署/指引）的语义识别与自动流转。"""

    # 已离开引导流的宿主目标节点
    EXIT_LOCATORS = (
        BySemantics(tag="chat-workbench-root"),
        BySemantics(tag="remote-login-root"),
        BySemantics(tag="remote-login-card"),
    )

    WELCOME_SCREEN = BySemantics(tag="onboarding-welcome")
    WELCOME_START = BySemantics(tag="onboarding-welcome-start")

    APPEARANCE_SCREEN = BySemantics(tag="onboarding-appearance")
    APPEARANCE_NEXT = BySemantics(tag="onboarding-appearance-next")

    PERMISSIONS_SCREEN = BySemantics(tag="onboarding-permissions")
    PERMISSIONS_SKIP = BySemantics(tag="onboarding-permissions-skip")
    PERMISSIONS_NEXT = BySemantics(tag="onboarding-permissions-next")

    DEPLOYMENT_SCREEN = BySemantics(tag="onboarding-deployment")
    DEPLOY_DONE = BySemantics(tag="onboarding-deploy-done")

    GUIDE_SCREEN = BySemantics(tag="onboarding-guide")
    GUIDE_DONE = BySemantics(tag="onboarding-guide-done")

    def complete_if_present(self, timeout: float | None = None) -> bool:
        """若当前处于任意 onboarding-* 引导页，则按序点击完成直至进入登录页或工作台。

        返回 True 表示实际执行了引导流程，返回 False 表示已处于登录页或工作台无需引导。
        """
        probe_locators = (
            *self.EXIT_LOCATORS,
            self.WELCOME_START,
            self.APPEARANCE_NEXT,
            self.PERMISSIONS_SKIP,
            self.PERMISSIONS_NEXT,
            self.DEPLOY_DONE,
            self.GUIDE_DONE,
        )

        matched_loc, _ = self.wait_any(probe_locators, timeout=timeout)
        if matched_loc in self.EXIT_LOCATORS:
            return False

        # 按引导状态机依次推进，并在每步等待旧阶段退出后再识别下一步，避免转场期重复点击
        for _ in range(8):
            if matched_loc == self.WELCOME_START:
                self.click(self.WELCOME_START, "引导欢迎页点击开始")
                self._wait_stage_leave(self.WELCOME_SCREEN)
            elif matched_loc == self.APPEARANCE_NEXT:
                self.click(self.APPEARANCE_NEXT, "引导外观页点击下一步")
                self._wait_stage_leave(self.APPEARANCE_SCREEN)
            elif matched_loc in (self.PERMISSIONS_SKIP, self.PERMISSIONS_NEXT):
                if self.is_visible(self.PERMISSIONS_SKIP, timeout=1.0):
                    self.click(self.PERMISSIONS_SKIP, "引导权限页点击跳过可选权限")
                else:
                    self.click(self.PERMISSIONS_NEXT, "引导权限页点击下一步")
                self._wait_stage_leave(self.PERMISSIONS_SCREEN)
            elif matched_loc == self.DEPLOY_DONE:
                # 部署页展开转场约 780ms，必须等待离开引导流再结束
                self.click(self.DEPLOY_DONE, "引导部署页点击进入登录")
                self._wait_exit_onboarding(timeout=timeout)
                return True
            elif matched_loc == self.GUIDE_DONE:
                self.click(self.GUIDE_DONE, "引导指引页点击了解完毕进入登录")
                self._wait_exit_onboarding(timeout=timeout)
                return True

            matched_loc, _ = self.wait_any(probe_locators, timeout=timeout)
            if matched_loc in self.EXIT_LOCATORS:
                return True

        raise RuntimeError("引导流程步数超出预期上限，未能离开 onboarding-* 页面。")

    def _wait_stage_leave(self, stage_locator: BySemantics) -> None:
        """等待当前引导阶段根节点退出，防止跨阶段淡入淡出期间重复触发按钮。"""
        try:
            self.ctx.wait.wait_until_gone(stage_locator, timeout=5.0)
        except SmartWaitTimeoutError:
            # 若极端情况下节点已切换或保留在后台，继续交由下一步 wait_any 兜底判定
            pass

    def _wait_exit_onboarding(self, timeout: float | None = None) -> None:
        """等待按钮展开转场动画完全结束并渲染登录页或工作台。"""
        self.wait_any(self.EXIT_LOCATORS, timeout=timeout)
