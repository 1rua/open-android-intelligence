# 页面对象、Compose 语义与场景扩展参考

当你需要为新功能增加 E2E 覆盖、扩展 `Page Object` 方法或深度排查失败报告时，按需查阅本参考文档。日常执行既定场景只需阅读 [SKILL.md](../SKILL.md)。

---

## 1. 为 Compose 页面补齐无侵入语义

当现有 `testTag` 无法唯一识别新控件时，按以下步骤扩展 Kotlin 代码（必须遵守仓库门禁：`check_hardcoded_chinese.py` 与 `test_string_resources_integrity.py`）：

1. **确认根宿主已开启资源 ID 映射**：`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteWorkbenchRoot.kt` 已经在根部 `AppSharedTransitionHost` 挂载 `semantics { testTagsAsResourceId = true }`，子页面无需重复配置。
2. **使用小写短横线命名**：优先遵循 `<功能域>-<区域>-<元素>`（例如 `settings-diagnostics-retry`）；对于动态列表项，使用 `<前缀>:<稳定ID>`（例如 `remote-host-item:${host.deviceId}`），**不要**把会随语言或状态变化的文案拼进 `testTag`。
3. **零业务侵入原则**：仅在目标交互组件或顶层容器追加 `.testTag(...)`，保持原有点击回调、文本资源、间距和动画不变。如果封装组件暂未透传 `Modifier`，可增加默认为空的可选参数（如 `fieldTestTag: String? = null`）。

---

## 2. 扩展 Page Object（`tools/e2e/pages/`）

Page Object 负责把页面上的 `BySemantics` 定位器与交互动作封装为语义化方法，供场景编排层调用。

编写新页面类的核心检查项：

- 继承 `BasePage`（接收 `ctx: E2ERuntimeContext`）。
- 把全部定位器集中定义为类常量或只读属性，全部使用 `BySemantics(...)`。
- 点击、输入与断言分别委托给 `self.click(...)`、`self.fill(..., secret=...)`、`self.assert_visible(...)`，确保自动完成 `SmartWait` 位移防抖与 `EvidenceRecorder` 前后截图留痕。
- 状态跳转等待使用 `self.wait_visible(...)` 或 `self.ctx.wait.wait_until_gone(...)`，不使用 `time.sleep`。

最小模板示例（相对路径置于 `tools/e2e/pages/`）：

```python
from __future__ import annotations

from tools.e2e.core import BySemantics
from tools.e2e.pages.base_page import BasePage


class DiagnosticsScreen(BasePage):
    """连接诊断页面对象示例。"""

    ROOT = BySemantics(tag="diagnostics-screen-root")
    RUN_CHECK_BUTTON = BySemantics(tag="diagnostics-run-check")
    STATUS_BADGE = BySemantics(tag="diagnostics-status-badge")

    def wait_ready(self) -> None:
        self.assert_visible(self.ROOT, "确认进入连接诊断页")

    def run_diagnostics(self) -> None:
        self.click(self.RUN_CHECK_BUTTON, "点击运行诊断检查")
        self.assert_visible(self.STATUS_BADGE, "断言诊断状态徽章出现")
```

---

## 3. 新增业务场景（`tools/e2e/scenarios/`）

新场景脚本以 `test_<场景名>.py` 命名，需同时满足 **CLI 注册调用** 与 **日常 `pytest` 隔离保护** 两个入口：

1. 暴露模块常量 `SCENARIO_NAME` 与入口函数 `run(ctx: E2ERuntimeContext) -> None`。
2. 如需支持通过 `pytest` 单独运行，测试函数必须携带 `OAI_E2E_LIVE` 跳过装饰器：

```python
from __future__ import annotations

import os
import pytest

from tools.e2e.core import E2ERuntimeContext
from tools.e2e.pages import ChatScreen, HomeScreen, OnboardingScreen

SCENARIO_NAME = "example_flow"


def run(ctx: E2ERuntimeContext) -> None:
    clear_data = ctx.auth.mode != "snapshot"
    ctx.driver.start_app(
        "com.openandroidintelligence.mobile",
        ".MainActivity",
        cold=True,
        clear_data=clear_data,
    )
    OnboardingScreen(ctx).complete_if_present()
    HomeScreen(ctx).connect_with_isolated_endpoint(ctx.auth)
    chat = ChatScreen(ctx)
    chat.wait_ready()
    chat.send_message("Hello OAI E2E")
    chat.assert_message_visible("E2E_PONG")


@pytest.mark.skipif(
    os.environ.get("OAI_E2E_LIVE") != "1",
    reason="按需 Android E2E 场景，请使用 python3 tools/e2e/run.py --scenario example_flow 执行",
)
def test_example_flow() -> None:
    ...
```

3. 将新场景接入 `tools/e2e/run.py` 的场景路由表后，先执行离线校验，再在模拟器上跑通实测：

```bash
python3 -m py_compile tools/e2e/pages/*.py tools/e2e/scenarios/*.py tools/e2e/run.py
pytest --collect-only tools/e2e/scenarios/
pytest tools/e2e/test_e2e_framework.py
python3 tools/e2e/run.py --scenario example_flow
```

---

## 4. 进阶排查与报告核对技巧

### 4.1 Agent 安全排查 `report.json` 与截图证据

为避免大体积 Base64 HTML 占用上下文，Agent 可先通过轻量命令检索 `build/reports/e2e/report.json` 中的结果摘要与失败记录，再在需要视觉确认时仅提取指定步骤图像查看：

```bash
python3 -m json.tool build/reports/e2e/report.json | head -n 80
```

### 4.2 常见故障速查

| 现象 | 常见根因 | 排查动作 |
| --- | --- | --- |
| `CoordinateForbiddenError` | 在 `pages/` 或 `scenarios/` 中传入了 `(x, y)` 坐标而非 `BySemantics` | 改用 `BySemantics(tag=...)` 或补齐 Compose `Modifier.testTag` |
| `SmartWaitTimeoutError` 且布局摘要无目标 `tag` | ① 未在 `RemoteWorkbenchRoot` 下或新页面尚未挂 `testTag`；② 前置页面阻塞（如弹窗、登录错误横幅） | 查看失败报告截图 + 展开折叠 `Pruned Layout` 核对当前真实树节点 |
| `SmartWaitTimeoutError` 但截图控件可见 | 控件持续处于位置变化/滚动动画中，两次采样中心位移 `> 2px` | 核对是否有持续刷新动画，或在 `SmartWait` 等待前置动效结束后再操作 |
| `local-sim` 登录超时或连不上 `127.0.0.1` | `adb reverse` 端口转发失效、设备离线或 `sim_remote_host.mjs` 未输出 `READY` | 用 `--verbose` 重跑一次检查 `adb devices` 与仿真进程日志，确认 Node `>= 24.18.0` 及 `npm ci` 依赖完整 |
| `snapshot` 模式仍跳回引导或登录页 | 目标 AVD 中缺少 `oai_authenticated` 快照，或运行参数误清除了 App 状态 | 检查模拟器快照列表并按主文档 3.1 节重建只读黄金快照 |
