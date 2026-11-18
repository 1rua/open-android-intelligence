# Open Android Intelligence (OAI) 全局项目架构说明

Open Android Intelligence（OAI）将 Android 手机与电脑或服务器上的多种 AI Agent 安全连接起来：用户在手机端随时接续与各类 Agent 的原生会话，电脑端 Agent 亦可在手机本机显式授权后读取手机消息或操控屏幕，同时无需 Root 手机，也不在手机本地运行大模型。

---

## 1. DSH Remote 三端架构

当前仓库采用 **DSH Remote** 架构（规格见 [DSH Remote 架构重构规格](docs/specs/2026-11-13-dsh-remote-architecture-refactor.md)，上游固定参考 `liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`），核心由三端协作组成：

```text
+-------------------------------------------------------------------+
|  1. Android 宿主（OAI Mobile App）                                 |
|  - Remote Client：发现电脑 Host / Backend / Workspace 并发起聊天   |
|  - OAI Virtual Host（oai.android）：暴露 harness/phone-capabilities |
|  - 平台内核（platform-kernel）与本机授权：唯一能力裁决权威            |
+-------------------------------------------------------------------+
                ^                                  ^
                |  Noise_IK_25519_ChaChaPoly_SHA256 |
                |  选路：LAN -> P2P -> TURN -> Relay |
                v                                  v
+-------------------------------------------------------------------+
|  2. DSH Remote 服务层（官方服务或兼容自托管地址）                   |
|  - 账号认证、同账号设备目录、在线状态、WebRTC 信令、TURN 与密文 Relay |
|  - 不授予手机设备能力，不保留 OAI 旧独立中心服务                      |
+-------------------------------------------------------------------+
                ^                                  ^
                |                                  |
                v                                  v
+-------------------------------------------------------------------+
|  3. 电脑端 Agent 后端（Host）                                      |
|  - 原生 DSH：通过内置 remote_* 工具直连 phone-capabilities 工作区   |
|  - 外部 Agent（Claude Code / Codex / OpenCode / Hermes ACP）：     |
|    经 DSH 进程内桥 + oai-android-mcp Stdio MCP 调用 android_* 工具 |
+-------------------------------------------------------------------+
```

### 三端职责划分
1. **Android 宿主客户端与虚拟主机门面**
   - **主动客户端（Remote Client）**：管理单一活动 Remote 账号下的桌面端发现、原生会话、流式输出、附件上传与离线加密历史缓存；应用退居后台且无待处理推送任务时可按系统条件休眠。
   - **被控虚拟主机（OAI Virtual Host）**：以被控身份注册至同账号设备目录，固定提供 `backend: "harness"`、`workspaceId: "phone-capabilities"`、常驻总线会话 `control-bus` 以及按任务创建的独立 `taskSessionId`。手机端不运行本地 LLM，仅解析确定性控制信封或等价 CLI 简写，并维护持久化 `request_id` 防重回执账本。
2. **DSH Remote 服务层**
   - 提供账号成员校验、设备目录发现、WebRTC 信令、TURN 凭据与加密流量中继。
   - 所有跨端业务载荷均运行在 `Noise_IK_25519_ChaChaPoly_SHA256` 端到端加密通道之上；服务端与同账号关系均不构成对手机端设备能力的自动授权。
3. **电脑端 Agent 后端**
   - 宿主拥有会话正文、历史、模型与工具执行的唯一权威。
   - **原生 DSH** 直接通过内置 `remote_*` 工具与手机的 `phone-capabilities` 工作区通信。
   - **外部 Agent** 通过电脑侧 DSH 进程内桥与 [手机 OAI MCP](integrations/oai-android-mcp/README.md) 暴露的 `android_*` Stdio 工具操控手机；外部 Agent 不复制 Remote 账号凭据或私钥，也不支持跨工作区委派。

---

## 2. 仓库顶层目录与技术栈职责

```text
open-android-intelligence/
├── apps/android/              # Android 客户端与虚拟主机多模块工程（Kotlin / Compose / Gradle）
├── plugins/                   # 6 个官方 Rust WASM 手机能力插件、Rust SDK 与 JSON Schema
├── plugin-tooling/            # .alp 插件包构建与 Ed25519 数字签名工具（TypeScript）
├── integrations/              # 外部工具接入：oai-android-mcp 与 oai-phone-control Skill
├── tools/                     # 智能测试调度、CI 影响分析、固定上游完整性与 i18n 门禁
├── assets/readme/             # README 可视化图片与可编辑 SVG 源文件
├── docs/                      # 架构规格、ADR、设计文档、指南与验收台账（索引见 docs/README.md）
├── legacy/                    # 已冻结历史归档模块（不参与现行 CI 与发布）
├── Makefile / .env.example    # 本地统一构建入口与环境变量模板
├── CONTEXT.md                 # 项目核心领域词汇表
├── AGENTS.md / CLAUDE.md      # 工程规范、三级测试约定与 Git 提交规则
├── PROJECT.md                 # 本文件：全局项目架构说明
└── TEST_INFRA.md              # 全局测试与质量门禁体系说明
```

### 2.1 `apps/android/` 多模块拓扑（25 个 Gradle 模块）

构建配置基于 Gradle 8.12、AGP 8.9.2、Kotlin 2.1.20 与 Jetpack Compose（JDK 21 运行构建与 Robolectric 测试，产物目标字节码 Java 17，`compileSdk 36` / `targetSdk 35` / `minSdk 31`）。按 [settings.gradle.kts](apps/android/settings.gradle.kts) 划分如下：

| 模块分类 | 模块列表 | 核心职责 |
| --- | --- | --- |
| 应用入口壳 | `:app`、`:assistant-holder` | `:app` 承载主界面、设置中心、会话工作台与 `full`/`play` 双构建变体（`full` 支持运行时插件，`play` 禁用）；`:assistant-holder` 承载系统默认数字助理与圈选截图入口 |
| 通信与协议门面 | `:gateway-client` | 承载 DSH Remote 客户端、Noise 通道、传输路由与 OAI Virtual Host 门面；唯一允许持有通用出站网络能力的业务模块 |
| 平台内核与安全基础设施 | `:platform-kernel`、`:plugin-package`、`:plugin-runtime-wasm`、`:plugin-ui`、`:policy-engine`、`:encrypted-store`、`:core-model` | `:platform-kernel` 负责插件生命周期、权限裁决与审计日志；`:plugin-package` 负责 `.alp` 解包与 Ed25519 验签；`:plugin-runtime-wasm` 运行受保护插件 WASM 沙箱；`:policy-engine` 评估风险与授权策略；`:encrypted-store` 提供端上加密存储 |
| 会话领域与展示层 | `:conversation-domain`、`:conversation-data`、`:conversation-ui`、`:ui-resources` | `:conversation-domain` 与 `:conversation-data` 管理 `AgentEndpoint`、会话状态、附件与加密历史镜像（零 UI 资源依赖）；`:conversation-ui` 提供时间线、思考卡片、斜杠命令等组件；`:ui-resources`（出度为 0，包名 `com.openandroidintelligence.ui.resources`）托管全量国际化 XML 字符串资源 |
| 能力端口与设备采集/控制 | `:artifact-ports`、`:capability-ports`、`:capability-sync-runtime`、`:control-ports`、`:device-primitives`、`:screen-control`、`:notification-control`、`:notification-host`、`:notification-collector`、`:sms-collector`、`:call-log-collector` | 封闭的能力接口契约与底层执行模块。其中 `:screen-control` 与 `:device-primitives` 负责原生 Kotlin 无障碍语义树读取、SoM 截图与无焦点悬浮确认/急停控制；其余采集模块分别负责通知、短信与通话记录的受控读取与主动推送 |

### 2.2 `plugins/` 手机能力插件（Rust → WASM）

位于 `plugins/`，基于 Rust（CI 采用 `1.90.0` + `wasm32-unknown-unknown` 目标）与 `plugins/sdk-rust/` 开发，并由 `plugins/schemas/` 约束输入输出契约。包含 6 个官方能力插件：
- `plugins/notifications`：通知查询、应用范围过滤与主动推送
- `plugins/sms`：短信查询、发送控制与定时/主动推送
- `plugins/call-log`：通话记录只读查询
- `plugins/screen-reader`：屏幕文字与 UI 结构读取封装
- `plugins/screen-capture`：屏幕截图封装
- `plugins/screen-control`：屏幕点击、滑动、输入与导航控制封装

> 说明：在架构演进中，屏幕高频感知与控制路径在 Android 侧优先复用原生 Kotlin `:screen-control` 与 `:device-primitives` 执行（见 [ADR-0065](docs/adr/0065-separate-native-screen-control-from-wasm-data-plugins.md)），离散数据能力运行于 WASM 沙箱；6 个官方能力在 `full` 变体构建时可作为已签名内置 `.alp` 资产打入 APK 并经同一套 `AlpVerifier` 校验加载。

### 2.3 `plugin-tooling/` 插件打包与签名

基于 TypeScript（Node.js `>=24.18.0 <25`）与 Vitest，负责将编译后的 WASM 与插件清单（`manifest.json`）打包为确定性的 `.alp` 容器并完成 Ed25519 数字签名（区分开发测试作者密钥与正式发布签名密钥）。

### 2.4 `integrations/` 外部接入与 Agent 技能

- `integrations/oai-android-mcp/`：提供 DSH Cordis 进程内桥插件与 Stdio MCP Server，向外部 Agent 仅暴露 `android_*` 手机工具，不提供跨工作区委派，不泄露 Remote Token 或私钥。
- `integrations/skills/oai-phone-control/`：面向 AI Agent 的标准手机控制 Skill（`SKILL.md` 与 `references/protocol-and-tools.md`），覆盖原生 DSH `remote_*` 与外部 `android_*` 两条控制路径的规范工作流。

### 2.5 `tools/` 工程门禁、CI 规划与固定来源观察

- **分级测试与 CI 规划**：`tools/smart-test.py`、`tools/ci/impact.py`、`tools/ci/android_tasks.py`、`tools/ci/android_app_performance.py`。
- **固定上游完整性门禁**：`tools/check-remote-sources.mjs`、`tools/remote-source-integrity.ts`。
- **Android 国际化双门禁**：`tools/check_hardcoded_chinese.py`、`tools/check_string_resources_integrity.py`（兼容入口 `tools/test_string_resources_integrity.py`）。
- **固定来源回归与互通探针**：`tools/remote/`、`tools/remote-server/`、`tools/remote-interop/`（含只读固定上游源码夹具、版本协商服务测试、ACP 投影 Scope 与媒体原文测试及原生 Kotlin 互通探针）。

### 2.6 `legacy/` 与已退役组件

- `legacy/`（说明见 [legacy/README.md](legacy/README.md)）：包含早期 `android-tailnet`、`artifact-contract`、`bridge-contract`、`bridge-runtime`、`mvp-contract`、`protocol` 归档，处于只读冻结状态，不参与现行构建、CI 或发布。
- 早期独立中心服务 `oai-core` 及旧 Gateway 适配层已在 DSH Remote 架构下退役并从主工程移除，不再作为现行三端运行时依赖。

---

## 3. 本机安全边界原则

1. **Android 安装实例是本机授权唯一权威**：Remote 登录认证、同账号设备发现或端到端加密通道的建立，均不自动赋予任何电脑或 Agent 访问手机数据的权限。
2. **按能力独立开关与三级模式**：每项设备能力均拥有独立开关与模式控制（默认「每次手动」，可选「允许只读」或「自动允许」）；切换账号、Remote 服务或后端不重置本机授权策略。
3. **敏感动作与悬浮急停保护**：发送短信、屏幕交互等敏感动作强制手机本机弹窗或通过无障碍无焦点悬浮窗（`TYPE_ACCESSIBILITY_OVERLAY`）确认，并全程保留紧急停止入口；所有穿透确认浮窗的手势请求均被拒绝。
4. **锁屏状态保护**：设备锁屏期间仅允许已明确授予后台权限的数据请求，严禁读屏、截图及任何屏幕操控行为，且不从锁屏拉起界面或自动解锁。
5. **插件信任模式隔离与网络收口**：受保护模式（WASM 沙箱）严格限制内存、CPU 与私有存储，无法绕过内核权限；开发者信任模式仅在用户显式启用且仅在 `full` 变体中可用。全仓由 `noVpnSurfaceCheck` 门禁强制拦截 VPN/TUN/路由/代理表面及未登记出站网络调用，确保除 `:gateway-client` 与显式登记的公开版本检查之外不存在隐蔽网络通道。
