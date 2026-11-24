"""Android E2E 测试框架核心底座统一导出包。"""

from __future__ import annotations

from .auth_env import (
    AuthEnvironmentManager,
    E2EAuthEnvironmentError,
    IsolatedAuthEndpoint,
)
from .context import E2ERuntimeContext
from .device_driver import (
    DEFAULT_APK_REL_PATH,
    DEFAULT_APP_ACTIVITY,
    DEFAULT_APP_PACKAGE,
    AdbAndroidDriver,
    E2EDeviceError,
    resolve_main_repo_root,
    resolve_repo_root,
    resolve_toolchains_root,
)
from .evidence import EvidenceRecorder, StepEvidence
from .layout_pruner import (
    BySemantics,
    SemanticNode,
    find_nodes,
    format_pruned_summary,
    prune_android_cli_json,
    prune_layout_xml,
    strip_resource_id_prefix,
)
from .reporter import MAX_REPORT_BYTES, write_html_report
from .smart_wait import SmartWait, SmartWaitTimeoutError

__all__ = [
    "AdbAndroidDriver",
    "AuthEnvironmentManager",
    "BySemantics",
    "DEFAULT_APK_REL_PATH",
    "DEFAULT_APP_ACTIVITY",
    "DEFAULT_APP_PACKAGE",
    "E2EAuthEnvironmentError",
    "E2EDeviceError",
    "E2ERuntimeContext",
    "EvidenceRecorder",
    "IsolatedAuthEndpoint",
    "MAX_REPORT_BYTES",
    "SemanticNode",
    "SmartWait",
    "SmartWaitTimeoutError",
    "StepEvidence",
    "find_nodes",
    "format_pruned_summary",
    "prune_android_cli_json",
    "prune_layout_xml",
    "resolve_main_repo_root",
    "resolve_repo_root",
    "resolve_toolchains_root",
    "strip_resource_id_prefix",
    "write_html_report",
]
