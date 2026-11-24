"""E2E 页面对象模块导出入口。"""

from __future__ import annotations

from .base_page import BasePage, CoordinateForbiddenError
from .chat_page import ChatScreen
from .home_page import HomeScreen
from .onboarding_page import OnboardingScreen

__all__ = [
    "BasePage",
    "CoordinateForbiddenError",
    "OnboardingScreen",
    "HomeScreen",
    "ChatScreen",
]
