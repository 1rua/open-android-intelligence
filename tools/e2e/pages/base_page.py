"""E2E 页面对象基类与语义定位强制校验。"""

from __future__ import annotations

import time
from collections.abc import Sequence
from typing import Any

from tools.e2e.core import (
    BySemantics,
    E2ERuntimeContext,
    SemanticNode,
    SmartWaitTimeoutError,
)


class CoordinateForbiddenError(ValueError):
    """当页面对象层传入绝对坐标或非语义定位器时抛出此异常。"""


class BasePage:
    """所有 Android E2E 页面对象的基类，强制通过语义树交互并自动留痕。"""

    def __init__(self, ctx: E2ERuntimeContext) -> None:
        self.ctx = ctx

    @staticmethod
    def _ensure_semantics_locator(locator: Any) -> BySemantics:
        """强制校验定位器必须为 BySemantics，杜绝绝对坐标、元组或裸整数输入。"""
        if not isinstance(locator, BySemantics):
            raise CoordinateForbiddenError(
                f"禁止在页面对象层使用坐标或非语义定位器（收到 {type(locator).__name__}: {locator!r}），"
                "请改用 BySemantics。"
            )
        return locator

    @staticmethod
    def _node_tap_point(node: SemanticNode) -> tuple[int, int]:
        """从语义节点动态计算安全点击中心点，不依赖任何屏幕硬编码坐标。"""
        center = getattr(node, "center", None)
        if isinstance(center, (tuple, list)) and len(center) == 2:
            return int(center[0]), int(center[1])
        bounds = getattr(node, "bounds", None)
        if isinstance(bounds, (tuple, list)) and len(bounds) == 4:
            left, top, right, bottom = (int(v) for v in bounds)
            return (left + right) // 2, (top + bottom) // 2
        raise RuntimeError(f"语义节点缺少有效中心点或边界信息，无法点击: {node!r}")

    def _settle_after_input(self, step_name: str, timeout: float = 4.0) -> None:
        """通过一次语义树轮询等待 UIAutomator 空闲，替代固定延时等待。"""
        self.ctx.wait.wait_for_predicate(
            f"{step_name} 后界面响应空闲",
            lambda _roots: True,
            timeout=timeout,
        )

    def wait_visible(
        self,
        locator: BySemantics,
        timeout: float | None = None,
    ) -> SemanticNode:
        """等待目标语义节点出现并收敛稳定。"""
        checked = self._ensure_semantics_locator(locator)
        return self.ctx.wait.wait_for(checked, timeout=timeout, require_stable=True)

    def wait_any(
        self,
        locators: Sequence[BySemantics],
        timeout: float | None = None,
        *,
        require_stable: bool = True,
    ) -> tuple[BySemantics, SemanticNode]:
        """并行等待多个候选语义定位器之一出现，返回实际命中的定位器与节点。"""
        checked = [self._ensure_semantics_locator(loc) for loc in locators]
        if not checked:
            raise ValueError("wait_any 至少需要提供一个 BySemantics 定位器。")
        return self.ctx.wait.wait_for_any(
            checked,
            timeout=timeout,
            require_stable=require_stable,
        )

    def is_visible(self, locator: BySemantics, timeout: float = 1.2) -> bool:
        """在短超时内语义探测目标是否可见，超时返回 False 而不抛出异常。"""
        checked = self._ensure_semantics_locator(locator)
        try:
            self.ctx.wait.wait_for(checked, timeout=timeout, require_stable=False)
            return True
        except SmartWaitTimeoutError:
            return False

    def click(
        self,
        locator: BySemantics,
        step_name: str,
        *,
        timeout: float | None = None,
    ) -> SemanticNode:
        """等待目标语义稳定后执行点击，并记录操作前后视觉留痕。"""
        checked = self._ensure_semantics_locator(locator)
        started = time.monotonic()
        node = self.ctx.wait.wait_for(checked, timeout=timeout, require_stable=True)
        tap_point = self._node_tap_point(node)
        before_png = self.ctx.driver.capture_screenshot_bytes()
        self.ctx.driver.tap_point(tap_point[0], tap_point[1])
        self._settle_after_input(step_name)
        after_png = self.ctx.driver.capture_screenshot_bytes()
        duration_ms = (time.monotonic() - started) * 1000.0
        self.ctx.evidence.record_action(
            step_name,
            before_png=before_png,
            after_png=after_png,
            target=node,
            tap_point=tap_point,
            target_desc=checked.describe(),
            duration_ms=duration_ms,
        )
        return node

    def fill(
        self,
        locator: BySemantics,
        value: str,
        step_name: str,
        *,
        secret: bool = False,
        timeout: float | None = None,
    ) -> SemanticNode:
        """语义点击聚焦输入框，清空既有内容后输入文本并等待软键盘与布局稳定。"""
        checked = self._ensure_semantics_locator(locator)
        started = time.monotonic()
        node = self.ctx.wait.wait_for(checked, timeout=timeout, require_stable=True)
        tap_point = self._node_tap_point(node)
        before_png = self.ctx.driver.capture_screenshot_bytes()

        # 先点击输入框聚焦并等待软键盘弹出后的布局位移收敛
        self.ctx.driver.tap_point(tap_point[0], tap_point[1])
        self.ctx.wait.wait_for(checked, timeout=timeout, require_stable=True)

        # 清空已有预填文本后再输入目标内容，避免字符串追加拼接
        self.ctx.driver.input_text(value, clear_first=True)
        updated_node = self.ctx.wait.wait_for(checked, timeout=timeout, require_stable=True)
        after_png = self.ctx.driver.capture_screenshot_bytes()

        duration_ms = (time.monotonic() - started) * 1000.0
        self.ctx.evidence.record_action(
            step_name,
            before_png=before_png,
            after_png=after_png,
            target=node,
            tap_point=tap_point,
            target_desc=checked.describe(),
            input_value=value,
            secret=secret,
            duration_ms=duration_ms,
        )
        return updated_node

    def assert_visible(
        self,
        locator: BySemantics,
        step_name: str,
        timeout: float | None = None,
    ) -> SemanticNode:
        """断言目标语义节点在超时时间内稳定可见，并记录断言截图。"""
        checked = self._ensure_semantics_locator(locator)
        started = time.monotonic()
        node = self.ctx.wait.wait_for(checked, timeout=timeout, require_stable=True)
        screenshot_png = self.ctx.driver.capture_screenshot_bytes()
        duration_ms = (time.monotonic() - started) * 1000.0
        self.ctx.evidence.record_assertion(
            step_name,
            passed=True,
            screenshot_png=screenshot_png,
            target=node,
            target_desc=checked.describe(),
            duration_ms=duration_ms,
        )
        return node

    def press_back(self, step_name: str) -> None:
        """发送系统返回键并等待界面空闲，记录操作前后视觉留痕。"""
        started = time.monotonic()
        before_png = self.ctx.driver.capture_screenshot_bytes()
        self.ctx.driver.press_back()
        self._settle_after_input(step_name, timeout=4.0)
        after_png = self.ctx.driver.capture_screenshot_bytes()
        duration_ms = (time.monotonic() - started) * 1000.0
        self.ctx.evidence.record_action(
            step_name,
            before_png=before_png,
            after_png=after_png,
            target_desc="KEYCODE_BACK",
            duration_ms=duration_ms,
        )
