"""对话工作台与侧边抽屉页面对象模型。"""

from __future__ import annotations

from tools.e2e.core import BySemantics, SemanticNode
from tools.e2e.pages.base_page import BasePage


class ChatScreen(BasePage):
    """封装对话工作台就绪校验、抽屉/侧栏路由验证及消息发送断言。"""

    WORKBENCH_ROOT = BySemantics(tag="chat-workbench-root")
    OPEN_DRAWER_BUTTON = BySemantics(tag="chat-open-drawer")
    DRAWER_SHEET = BySemantics(tag="workbench-drawer")
    DRAWER_CLOSE_BUTTON = BySemantics(desc="关闭抽屉")
    CONVERSATION_BROWSER = BySemantics(tag="workspace-conversation-browser")
    NEW_CONVERSATION_BUTTON = BySemantics(tag="chat-new-conversation")
    TIMELINE = BySemantics(tag="chat-timeline")
    WELCOME_EMPTY = BySemantics(tag="chat-welcome-empty")
    COMPOSER_INPUT = BySemantics(tag="chat-composer-input")
    COMPOSER_SEND_ENABLED = BySemantics(tag="chat-composer-send", enabled=True)

    def wait_ready(self, timeout: float = 15.0) -> None:
        """等待工作台根容器、时间线/空欢迎态及输入坞全部就绪。"""
        self.assert_visible(self.WORKBENCH_ROOT, "验证对话工作台根容器可见", timeout=timeout)
        self.wait_any((self.TIMELINE, self.WELCOME_EMPTY), timeout=timeout)
        self.wait_visible(self.COMPOSER_INPUT, timeout=timeout)

    def verify_drawer_route(self) -> None:
        """验证窄屏侧滑抽屉开合流转，或在平板宽屏下验证常驻会话浏览器可见。"""
        if self.is_visible(self.OPEN_DRAWER_BUTTON, timeout=1.5):
            self.click(self.OPEN_DRAWER_BUTTON, "点击打开会话侧边抽屉")
            self.ctx.wait.wait_for_predicate(
                "等待会话抽屉展开到屏幕可视区域",
                self._drawer_opened,
                timeout=10.0,
            )
            matched_drawer, _ = self.wait_any((self.DRAWER_SHEET, self.CONVERSATION_BROWSER))
            self.assert_visible(matched_drawer, "断言会话抽屉已展开可见")

            if self._has_onscreen_close_button():
                self.click(self.DRAWER_CLOSE_BUTTON, "点击关闭按钮收起会话抽屉")
            else:
                self.press_back("按返回键收起会话抽屉")

            self.ctx.wait.wait_for_predicate(
                "等待会话抽屉退出可视区域",
                self._drawer_closed,
                timeout=10.0,
            )
            self.assert_visible(self.OPEN_DRAWER_BUTTON, "确认抽屉已关闭并返回工作台主视图")
            return

        # 宽屏展开形态下抽屉按钮隐藏，左侧常驻渲染会话浏览器
        self.assert_visible(self.CONVERSATION_BROWSER, "断言宽屏常驻会话浏览器可见")

    @staticmethod
    def _is_onscreen(node: SemanticNode) -> bool:
        """判断语义节点是否处于屏幕正向可视区域，避免命中滑出屏幕外的抽屉残影。"""
        bounds = getattr(node, "bounds", None)
        if not (isinstance(bounds, (tuple, list)) and len(bounds) == 4):
            return False
        left, top, right, bottom = (int(v) for v in bounds)
        return right > 0 and bottom > 0 and right > left and bottom > top

    @classmethod
    def _find_onscreen(cls, roots: list[SemanticNode], locator: BySemantics) -> SemanticNode | None:
        """在语义树中查找处于可视区域内的首个匹配节点。"""
        for root in roots:
            for node in root.flatten():
                if locator.matches(node) and cls._is_onscreen(node):
                    return node
        return None

    @classmethod
    def _drawer_opened(cls, roots: list[SemanticNode]) -> bool:
        """判断抽屉容器或会话浏览器是否已实际滑入可视区域。"""
        return (
            cls._find_onscreen(roots, cls.DRAWER_SHEET) is not None
            or cls._find_onscreen(roots, cls.CONVERSATION_BROWSER) is not None
        )

    @classmethod
    def _drawer_closed(cls, roots: list[SemanticNode]) -> bool:
        """判断抽屉已退出可视区域（或从树中移除）且打开抽屉按钮恢复可见。"""
        drawer_visible = cls._find_onscreen(roots, cls.DRAWER_SHEET) is not None
        open_btn_visible = cls._find_onscreen(roots, cls.OPEN_DRAWER_BUTTON) is not None
        return (not drawer_visible) and open_btn_visible

    def _has_onscreen_close_button(self) -> bool:
        """确认抽屉关闭按钮在可视区域内可见。"""
        roots = self.ctx.driver.dump_semantics()
        return self._find_onscreen(roots, self.DRAWER_CLOSE_BUTTON) is not None

    def send_message(self, text: str, *, timeout: float = 15.0) -> None:
        """向对话输入框填入消息并点击发送，等待草稿清空与时间线更新。"""
        self.fill(self.COMPOSER_INPUT, text, f"输入测试消息: {text}", timeout=timeout)
        self.wait_visible(self.COMPOSER_SEND_ENABLED, timeout=timeout)
        self.click(self.COMPOSER_SEND_ENABLED, "点击发送消息按钮", timeout=timeout)

        # 等待时间线容器呈现，并确认输入框草稿已提交清空（避免后续文本断言误匹配输入草稿）
        self.wait_visible(self.TIMELINE, timeout=timeout)
        self.ctx.wait.wait_for_predicate(
            "等待消息提交并清空输入框草稿",
            lambda roots: self._composer_draft_cleared(roots, text),
            timeout=timeout,
        )

    @classmethod
    def _composer_draft_cleared(cls, roots: list[SemanticNode], sent_text: str) -> bool:
        """检查输入坞中不再保留刚刚发送的完整草稿文本。"""
        for root in roots:
            for node in root.flatten():
                if cls.COMPOSER_INPUT.matches(node):
                    return node.text != sent_text
        return True

    def assert_message_visible(self, substring: str, timeout: float = 15.0) -> SemanticNode:
        """断言对话时间线中出现包含指定子串的消息气泡或内容。"""
        return self.assert_visible(
            BySemantics(text_contains=substring),
            f"断言对话消息可见: {substring}",
            timeout=timeout,
        )
