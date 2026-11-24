"""基于语义树连续采样与布局位移收敛防抖的智能等待模块。"""

from __future__ import annotations

import time
from typing import Any, Callable, Sequence, TypeVar

from .layout_pruner import (
    BySemantics,
    SemanticNode,
    find_nodes,
    format_pruned_summary,
)

T = TypeVar("T")


class SmartWaitTimeoutError(AssertionError):
    """智能等待超时异常，携带目标定位描述、超时时间及末尾精简布局摘要。"""

    def __init__(
        self,
        locator_desc: str,
        timeout: float,
        last_layout_summary: str,
        message: str | None = None,
    ) -> None:
        self.locator_desc = locator_desc
        self.timeout = float(timeout)
        self.last_layout_summary = last_layout_summary
        detail = message or (
            f"等待语义条件超时 ({self.timeout:.2f}s): {self.locator_desc}\n"
            f"--- 末次采样精简语义树 ---\n{self.last_layout_summary}"
        )
        super().__init__(detail)


class SmartWait:
    """语义轮询器，强制连续采样位移收敛后再返回目标控件，消除动画漂移脱靶。"""

    def __init__(
        self,
        driver: Any,
        default_timeout: float = 12.0,
        poll_interval: float = 0.25,
        stability_px: int = 2,
    ) -> None:
        self.driver = driver
        self.default_timeout = float(default_timeout)
        self.poll_interval = float(poll_interval)
        self.stability_px = int(stability_px)

    def _is_stable(
        self, prev_center: tuple[int, int] | None, current: SemanticNode
    ) -> bool:
        """检查连续两次采样的中心坐标位移是否均落在阈值内。"""
        if prev_center is None:
            return False
        dx = abs(current.center[0] - prev_center[0])
        dy = abs(current.center[1] - prev_center[1])
        return dx <= self.stability_px and dy <= self.stability_px

    def _sleep_step(self, deadline: float) -> None:
        """在不超过截止时间的前提下按轮询间隔休眠。"""
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return
        time.sleep(min(self.poll_interval, remaining))

    def _poll_until(
        self,
        description: str,
        probe_fn: Callable[[list[SemanticNode]], tuple[bool, Any]],
        timeout: float | None = None,
    ) -> Any:
        """统一语义轮询循环：周期采样语义树直到 probe_fn 就绪，超时抛出诊断异常。"""
        effective_timeout = self.default_timeout if timeout is None else float(timeout)
        deadline = time.monotonic() + max(0.0, effective_timeout)
        last_roots: list[SemanticNode] = []

        while True:
            last_roots = list(self.driver.dump_semantics())
            ready, value = probe_fn(last_roots)
            if ready:
                return value
            if time.monotonic() >= deadline:
                raise SmartWaitTimeoutError(
                    locator_desc=description,
                    timeout=effective_timeout,
                    last_layout_summary=format_pruned_summary(last_roots),
                )
            self._sleep_step(deadline)

    def wait_for(
        self,
        locator: BySemantics,
        timeout: float | None = None,
        require_stable: bool = True,
    ) -> SemanticNode:
        """等待指定语义节点出现；启用防抖时需连续两次中心位移 <= stability_px 才返回。"""
        prev_center: tuple[int, int] | None = None

        def _probe(roots: list[SemanticNode]) -> tuple[bool, SemanticNode | None]:
            nonlocal prev_center
            matches = find_nodes(roots, locator)
            if matches:
                node = matches[0]
                if not require_stable or self._is_stable(prev_center, node):
                    return True, node
                prev_center = node.center
            else:
                prev_center = None
            return False, None

        return self._poll_until(locator.describe(), _probe, timeout)

    def wait_for_any(
        self,
        locators: Sequence[BySemantics],
        timeout: float | None = None,
        require_stable: bool = True,
    ) -> tuple[BySemantics, SemanticNode]:
        """并行等待多个候选定位器中的任意一个就绪，返回 `(命中定位器, 语义节点)`。"""
        if not locators:
            raise ValueError("locators 不能为空")

        prev_centers: dict[int, tuple[int, int]] = {}
        desc = " | ".join(loc.describe() for loc in locators)

        def _probe(
            roots: list[SemanticNode],
        ) -> tuple[bool, tuple[BySemantics, SemanticNode] | None]:
            for idx, locator in enumerate(locators):
                matches = find_nodes(roots, locator)
                if matches:
                    node = matches[0]
                    if not require_stable or self._is_stable(
                        prev_centers.get(idx), node
                    ):
                        return True, (locator, node)
                    prev_centers[idx] = node.center
                else:
                    prev_centers.pop(idx, None)
            return False, None

        return self._poll_until(desc, _probe, timeout)

    def wait_until_gone(
        self, locator: BySemantics, timeout: float | None = None
    ) -> None:
        """等待指定语义节点从当前界面树完全消失。"""

        def _probe(roots: list[SemanticNode]) -> tuple[bool, None]:
            return (not find_nodes(roots, locator)), None

        self._poll_until(f"消失: {locator.describe()}", _probe, timeout)

    def wait_for_predicate(
        self,
        description: str,
        predicate: Callable[[list[SemanticNode]], T | None],
        timeout: float | None = None,
    ) -> T:
        """等待自定义谓词基于最新语义树返回真值/非空结果。"""

        def _probe(roots: list[SemanticNode]) -> tuple[bool, T | None]:
            result = predicate(roots)
            if result is not None and result is not False:
                return True, result
            return False, None

        return self._poll_until(description, _probe, timeout)
