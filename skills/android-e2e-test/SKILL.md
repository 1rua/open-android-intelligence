---
name: android-e2e-test
description: 当需要运行 Android UI 端到端冒烟/回归验证（如 `chat_smoke`、`failure_drill`）、排查 `report.html` / `report.json` 视觉图文报告、扩展 `Page Object` 页面模型、补齐 Compose `testTag` 语义标签或新增端到端测试场景时使用本 Skill。强制通过单条 `python3 tools/e2e/run.py` 命令驱动测试，严禁在对话中逐步执行底层 `adb` 截图/点击命令。
---

# Android UI 自动化端到端测试规范（`tools/e2e`）

本 Skill 用于指导 Agent 高效、低成本、安全地执行、排查并扩展 Open Android Intelligence（OAI）Android 客户端端到端（E2E）测试。

> 当需要扩展新页面模型（`Page Object`）、补齐 Compose 语义或编写新 `test_*.py` 场景模板时，按需查阅 [页面与场景扩展及进阶排障指南](references/extending-and-troubleshooting.md)。

---

## 1. 核心纪律：单指令执行与 Token 节流

执行 Android UI 验证时，必须遵循 **单指令驱动 + 最小输出解析**：

1. **必须单指令一键运行**：始终使用 `python3 tools/e2e/run.py --scenario <name>` 一次跑完整个端到端场景（内置初始化、等待、点击、断言与收尾清理）。
2. **解析单行摘要**：正常通过时，`run.py` 在标准输出**严格只输出 1 行**摘要（见下文）。读取该行即可确认通过，**不要**在通过后顺手读取几 MB 的 `report.html`。
3. **仅按需排查视觉证据**：只有在测试失败，或需要抽检交互动画与点击落点时，才读取 `build/reports/e2e/report.json` 或抽取 `report.html` 中的关键步骤截图做核查（切勿将含 Base64 图片的整份 `report.html` 原文直接读入对话上下文）。
4. **严禁手工步进操控（Token 爆炸护栏）**：严禁在对话循环中一步步调用 `adb shell uiautomator dump`、`adb shell input tap`、`adb exec-out screencap` 或 `android layout` 来手工模拟用户操作——原始布局树单帧即可达数万字符，会迅速耗尽上下文预算并因动画漂移脱靶。

---

## 2. 快速运行指南与 CI 边界

### 2.1 常用运行命令

所有命令均在仓库根目录（或当前 Git Worktree 根目录）以相对路径执行：

```bash
# 1. 自动免密运行主链路冒烟（引导页 -> 隔离接入 -> 抽屉路由 -> 发送消息并校验回复）
python3 tools/e2e/run.py --scenario chat_smoke

# 2. 运行失败演练（故意触发语义缺失超时，验证数秒内优雅失败并产出置顶标红报告）
python3 tools/e2e/run.py --scenario failure_drill --report-dir build/reports/e2e-failure-drill

# 3. 指定本地仿真模式或已登录黄金快照
python3 tools/e2e/run.py --scenario chat_smoke --auth-mode local-sim
python3 tools/e2e/run.py --scenario chat_smoke --auth-mode snapshot --snapshot oai_authenticated
```

常用 CLI 参数说明：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--scenario` | 必填 | 场景名称，如 `chat_smoke`、`failure_drill` |
| `--auth-mode` | `auto` | 免密模式：`auto`（自动选择）、`snapshot`（只读黄金快照）、`local-sim`（本地仿真主机） |
| `--device` | 自动探测 | 指定 ADB 设备序列号（多设备同时在线时建议显式指定，如 `emulator-5554`） |
| `--avd` | 自动探测 | 指定要检查或关联的模拟器 AVD 名称（如 `medium_phone`） |
| `--snapshot` | `oai_authenticated` | `--auth-mode snapshot` 模式下使用的只读黄金快照名称 |
| `--report-dir` | `build/reports/e2e` | 报告输出目录（生成 `report.html` 与 `report.json`） |
| `--expect-reply` | `E2E_PONG` | `chat_smoke` 中断言期望收到的回复关键字 |
| `--clear-app-state` | 按模式自适应 | 是否冷启动前清空 App 数据（`local-sim` 默认清理以保洁净；`snapshot` 默认保留登录态） |
| `--verbose` | 关闭 | 打印详细执行日志；关闭时标准输出严格保持单行 |

标准单行输出契约（非 `--verbose`）：

```text
PASS | scenario=chat_smoke | steps=12 | duration=18.4s | report_size=1.82MB | report=build/reports/e2e/report.html
FAIL | scenario=failure_drill | steps=3 | duration=4.2s | report_size=0.45MB | report=build/reports/e2e-failure-drill/report.html | error=SmartWaitTimeoutError: ...
```

### 2.2 与仓库三级测试体系的边界

| 验证层级 | 命令 | 覆盖范围与设备要求 |
| --- | --- | --- |
| **Level 1（Fast）** | `npm run test:fast` | 秒级静态安全门禁、上游锁校验、硬编码中文扫描（无需模拟器） |
| **Level 2（PR）** | `npm run test:pr` | 包含 `pytest tools/e2e/test_e2e_framework.py` 等**纯离线单测**及受影响 Android 模块回归；`tools/e2e/scenarios/` 因未设置 `OAI_E2E_LIVE=1` 会自动优雅跳过，**不阻塞日常开发与云端 PR CI** |
| **Level 3（Full）** | `npm run test:full` | 全量模块与 `:app` 双变体单元测试回归（无需模拟器） |
| **按需实机 E2E** | `python3 tools/e2e/run.py --scenario <name>` | 连接真机或 KVM Android 模拟器运行真实 APK，仅在本地按需执行或通过 CI `workflow_dispatch` 手动触发 |

---

## 3. 免密与凭据安全隔离方案

本框架绝不在仓库代码、测试脚本或测试报告中硬编码或泄露任何真实账号密码，提供两条免密隔离路径：

### 3.1 路径 A：AVD 黄金快照模式（`--auth-mode snapshot`）

适合在已有预置登录态的本地工作站快速回归工作台交互：

1. **首次建快照（一次性隔离准备）**：使用相对工具链启动模拟器（如将 `ANDROID_AVD_HOME` 指向 `.toolchains/avd-home/.android/avd`，运行 `.toolchains/android-sdk/emulator/emulator @<avd> -no-window -no-audio -gpu swiftshader_indirect`），在隔离环境中完成一次测试登录并进入工作台后，执行 `adb emu avd snapshot save oai_authenticated` 保存快照。
2. **后续只读免密回放**：以只读参数启动模拟器：
   ```bash
   .toolchains/android-sdk/emulator/emulator @<avd> -snapshot oai_authenticated -no-snapshot-save
   ```
   `-no-snapshot-save` 保证本次测试产生的消息、缓存或状态变更在退出后**不会写回快照**。`chat_smoke` 在 `snapshot` 模式下会保留既有应用数据冷启动，识别到 `chat-workbench-root` 即免登录直通工作台。

### 3.2 路径 B：本地隔离仿真服务模式（`--auth-mode local-sim`）

适合全新无状态环境、未预置快照的工作站或需要完整验证“引导 ➔ 登录 ➔ 加密握手 ➔ 对话”全链路的场景：

1. **本地回环信令与虚拟主机**：`AuthEnvironmentManager` 自动拉起本地隔离服务 `tools/remote-server/fixed-server.mjs`（仅使用固定本地测试凭据 `fixture@example.test` / `fixture-local-password`）及仿真主机 `tools/e2e/core/sim_remote_host.mjs`。
2. **端口安全映射**：自动通过 `adb reverse tcp:<port> tcp:<port>` 将手机/模拟器内部的 `127.0.0.1:<port>` 回环映射至宿主机临时仿真端口（App 清单已允许本地明文回环）。
3. **完整加密协议闭环**：`sim_remote_host.mjs` 以 `"E2E Sim Host"` 注册在线，与 App 完成 Noise IK 端到端加密握手（无需人工扫码或 OAuth），响应 `harness.transport.describe`、`session/create`、`session/prompt` 等标准方法，并在收到测试消息后自动回传流式帧 `"E2E_PONG: <原文>"`。
4. **报告自动脱敏**：在页面层填写密码/令牌时启用 `secret=True`，所有命令行日志、`report.json` 与 `report.html` 中的敏感文本都会自动替换为 `******`。

---

## 4. 分层架构与扩展规范

### 4.1 三层职责划分

```text
tools/e2e/
├── core/                       # 基础设施底座（不直接耦合业务页面）
│   ├── device_driver.py        # AdbAndroidDriver：带超时的 ADB 执行、dumpsys/layout 采集、相对工具链推导
│   ├── layout_pruner.py        # SemanticNode & BySemantics：剥离包名前缀，裁剪无意义装饰容器
│   ├── smart_wait.py           # SmartWait：连续两次采样中心位移 <= 2px 方可操作，消除动画漂移
│   ├── evidence.py             # EvidenceRecorder：绘制 Before 红框/红点与 After 圆环、WebP 缩放、****** 脱敏
│   ├── reporter.py             # write_html_report：输出自包含 report.html & report.json，强制 < 15MB 守卫
│   ├── auth_env.py             # AuthEnvironmentManager：管理 snapshot / local-sim / auto 隔离与安全清理
│   ├── sim_remote_host.mjs     # 本地隔离仿真主机节点（只读复用 fixed-server 夹具）
│   └── context.py              # E2ERuntimeContext：串联驱动、等待、留痕与环境的运行上下文
├── pages/                      # Page Object 页面抽象层
│   ├── base_page.py            # BasePage：强校验 BySemantics 定位，阻断坐标入参（CoordinateForbiddenError）
│   ├── onboarding_page.py      # OnboardingScreen：识别并逐页完成首次启动引导
│   ├── home_page.py            # HomeScreen：兼容已登录直通与自建服务登录、Host/Agent 选中
│   └── chat_page.py            # ChatScreen：工作台就绪、抽屉/宽屏侧栏切换、发送消息与断言回复
├── scenarios/                  # 业务场景编排层（仅组合 Page 动作与业务断言）
│   ├── test_chat_smoke.py      # 主链路端到端冒烟（chat_smoke）
│   └── test_failure_drill.py   # 失败报告与优雅退出自检（failure_drill）
└── run.py                      # 单指令 CLI 入口与单行摘要输出
```

### 4.2 Page / Scenario 层两条硬禁令与 Pytest 保护

编写或修改 `tools/e2e/pages/` 与 `tools/e2e/scenarios/` 时，必须遵守以下三条规则：

1. **定位方式强制使用 `BySemantics`，严禁硬编码屏幕绝对坐标**：所有交互均传入 `BySemantics(tag=...)`、`BySemantics(text_contains=...)` 等语义定位器；向 `BasePage` 传入坐标元组或整数会直接抛出 `CoordinateForbiddenError`。
2. **等待同步强制使用 `SmartWait`，严禁使用固定 `time.sleep`**：任何界面切换、抽屉收起、列表加载或 AI 回复等待，均使用 `SmartWait.wait_for` / `wait_for_any` / `wait_until_gone` / `wait_for_predicate`，依赖连续两次采样的位置收敛（`<= 2px`）防抖，既稳又快。
3. **新增场景必须加 `OAI_E2E_LIVE` 守卫**：所有 `tools/e2e/scenarios/test_*.py` 中的 pytest 测试函数，必须添加以下装饰器以保护日常离线 `pytest` 与 `npm run test:pr`：
   ```python
   @pytest.mark.skipif(
       os.environ.get("OAI_E2E_LIVE") != "1",
       reason="按需 Android E2E 场景，请使用 python3 tools/e2e/run.py --scenario <name> 执行",
   )
   ```

### 4.3 Compose 界面语义契约与核心标签速查表

Android 端已在根宿主 `RemoteWorkbenchRoot.kt` 为 `AppSharedTransitionHost` 挂载：

```kotlin
Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }
```

该配置会自动把整棵 Compose 树中的 `Modifier.testTag("<tag>")` 暴露到无障碍 `resource-id`；`layout_pruner.py` 读取后会自动剥离 `<package>:id/` 前缀，使脚本直接用短标签匹配。为新控件补齐语义时：**只追加无侵入 `Modifier.testTag("<区域>-<元素>")`，不修改既有文案、逻辑或布局尺寸**（需通过 `check_hardcoded_chinese.py` 与 `test_string_resources_integrity.py`）。

核心 `testTag` 速查表：

| 所属阶段 / 界面 | 核心 `testTag`（`BySemantics(tag=...)`） | 说明 |
| --- | --- | --- |
| **首次引导流** | `onboarding-welcome`、`onboarding-welcome-start`、`onboarding-appearance`、`onboarding-appearance-next`、`onboarding-permissions`、`onboarding-permissions-skip`、`onboarding-permissions-next`、`onboarding-deployment`、`onboarding-deploy-done`、`onboarding-guide`、`onboarding-guide-done` | 引导阶段识别与推进按钮 |
| **登录与接入** | `remote-login-root`、`remote-login-card`、`remote-official-login`、`remote-self-hosted-login`、`remote-login-toggle-mode`、`remote-login-origin-input`、`remote-login-account-input`、`remote-login-password-input`、`remote-login-submit`、`remote-login-open-settings` | 登录容器、模式切换、三段表单输入框与提交按钮 |
| **主机与 Agent** | `remote-host-item:<deviceId>`、`remote-refresh-hosts`、`remote-agent-item:<backendId>`（仿真主路径为 `remote-agent-item:harness`） | 主机列表项（支持 `tag_prefix="remote-host-item:"`）、刷新按钮与 Agent 端点 |
| **对话工作台** | `chat-workbench-root`、`chat-open-drawer`、`workbench-drawer`、`workspace-conversation-browser`、`chat-new-conversation`、`chat-more-actions`、`chat-timeline`、`chat-welcome-empty` | 工作台根视图、手机抽屉/平板侧栏、新建与更多菜单、时间线与空态页 |
| **底部输入栏** | `chat-composer-attach`、`chat-composer-input`、`chat-composer-voice`、`chat-composer-send` | 附件入口、文本输入区、语音按钮与发送/停止按钮 |

---

## 5. 图文报告自查与排障规范

### 5.1 视觉证据核查要点

查看 `build/reports/e2e/report.html`（或在 Agent 内检查 `report.json` 与抽取的截图图片）时，重点核对以下四项：

1. **Before 动作前靶心对齐**：检查目标控件是否套有半透明红色高亮框，实心红点（`#FF1744`）是否精准落在控件可点击区域中心；若红点偏到边缘或落在相邻控件上，通常是转场未静止或语义节点给在了错误的外层容器上。
2. **动画残影检查**：若 `Before` 截图处于淡入/滑动半透明过渡期，说明目标控件的稳定判定阈值或层级判断需要收紧。
3. **After 动作后对比**：参考圆环位置及之后一帧画面应能体现真实结果——例如焦点框高亮、键盘弹出、抽屉滑出、页面跳转或消息气泡上屏。
4. **脱敏验证**：凡涉及密码、令牌的步骤，截图之外的文字描述、日志与 `report.json` 字段中必须仅显示 `******`。

### 5.2 失败场景快速定位三步法

当 `run.py` 返回退出码 `1` 并输出 `FAIL | ...` 时，打开失败对应的 `report.html` 按顺序定位：

1. **置顶标红失败卡片**：失败报告会自动将出错步骤置顶并用醒目红色标出，无需在数十步正常记录中翻找。
2. **失败现场截图 + 精简堆栈**：先看截图确认当时手机画面卡在哪个界面（如意外停在引导页、报错横幅、系统权限弹窗或空白加载），再对照去除了冗余调用栈的精简错误信息。
3. **展开折叠的 Pruned Layout 节点**：若报错为 `SmartWaitTimeoutError`，展开失败卡片底部默认折叠的 `Pruned Layout` 节点摘要，直接对照当前屏幕实际暴露出来的 `tag`、`text`、`clickable` 与 `bounds`，区分是“前置按钮没点到”“服务未响应”还是“UI 标签变更”。

### 5.3 报告体积守卫与安全清理

- **体积上限（`< 15MB`）**：`reporter.py` 内置 `MAX_REPORT_BYTES = 15 * 1024 * 1024`。生成报告后会自动检查字节数；超限时自动降低截图分辨率与 WebP 质量并重新编码（极端情况下优先保住失败现场截图与关键步骤），确保自包含单文件始终严格低于 `15MB`。
- **安全清理（严禁 `rm`/`unlink`）**：复核完毕后清理临时报告目录（如失败演练产出的 `build/reports/e2e-failure-drill`）时，**一律安全移动到仓库 `.worktrees/trash/`**，绝不使用 `rm`、`rm -rf` 或 `unlink`：
  ```bash
  mkdir -p .worktrees/trash
  mv build/reports/e2e-failure-drill .worktrees/trash/e2e-failure-drill-$(date +%Y%m%d-%H%M%S)
  ```
