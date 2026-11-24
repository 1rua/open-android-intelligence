"""E2E 运行时共享上下文定义模块。"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .auth_env import IsolatedAuthEndpoint
from .device_driver import (
    DEFAULT_APP_ACTIVITY,
    DEFAULT_APP_PACKAGE,
    AdbAndroidDriver,
    resolve_repo_root,
)
from .evidence import EvidenceRecorder
from .smart_wait import SmartWait


@dataclass
class E2ERuntimeContext:
    """贯穿 Page Object 与 Scenario 执行全周期的运行时上下文。"""

    driver: AdbAndroidDriver
    wait: SmartWait
    evidence: EvidenceRecorder
    auth: IsolatedAuthEndpoint
    repo_root: Path = field(default_factory=resolve_repo_root)
    report_dir: Path = field(
        default_factory=lambda: resolve_repo_root() / "build/reports/e2e"
    )
    expect_reply: str = "E2E_PONG"
    clear_app_state: bool = False
    package_name: str = DEFAULT_APP_PACKAGE
    activity_name: str = DEFAULT_APP_ACTIVITY
    verbose: bool = False
