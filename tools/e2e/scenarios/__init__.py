"""E2E 测试场景注册与统一索引。"""

from __future__ import annotations

import sys
from pathlib import Path

# 保证通过独立 `pytest` 命令收集场景时同样能定位仓库根目录下的 `tools.e2e.*` 包
_REPO_ROOT = Path(__file__).resolve().parents[3]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from . import test_chat_smoke, test_failure_drill

SCENARIOS = {
    test_chat_smoke.SCENARIO_NAME: test_chat_smoke,
    test_failure_drill.SCENARIO_NAME: test_failure_drill,
}

__all__ = [
    "SCENARIOS",
    "test_chat_smoke",
    "test_failure_drill",
]
