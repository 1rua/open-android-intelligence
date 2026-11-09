# open-android-intelligence 模块化插件架构迁移交接文档（Task 11 及后续）

生成时间：2026-08-30
当前基线：`main` at `8057d9a`（`迁移: 提供 v1 安全引导导出并冻结旧 Bridge`）
工作区状态：包含 Task 11 正在开发中的未暂存及未跟踪改动（`platform-kernel`、`plugin-runtime-wasm`、`plugin-ui`、`plugins/`）

---

## 1. 项目定位与核心原则

open-android-intelligence 让 Android 设备能够连接用户自建/托管的 Agent Gateway。
- **宪法原则**：**手机端始终是本机数据与设备操作的最终授权者**，任何改动不得削弱手机端控制权。
- **架构组成**：极简 Android 宿主 + 可安装设备插件（`.alp` 格式）+ Agent 宿主内的 Gateway 适配器（OpenClaw/TypeScript 与 Hermes/Python 两套）。

---

## 2. 权威文档与规范参考

遇到设计冲突或不明确时，依此顺序裁决：

1. 架构总规格：`docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md`
2. 通信协议唯一权威：`docs/contracts/gateway-protocol-v2.md`
3. 插件包唯一权威：`docs/contracts/device-plugin-package-v1.md`
4. 架构决策记录：`docs/adr/`（40+ 篇，accepted）
5. 领域术语表：`CONTEXT.md`
6. 16 步实施计划：`docs/superpowers/plans/2026-08-24-modular-plugin-architecture-migration.md`

---

## 3. 当前任务进度盘点

| 任务 | 内容与作用 | 当前状态 | 对应提交 / 证据 |
|:---|:---|:---:|:---|
| **Task 1–5** | Gateway v2 协议契约、签名向量、OpenClaw(TS) 与 Hermes(Py) 双宿主核心 | **已完成** | `2262597` 及之前提交 |
| **Task 6** | 双宿主跨语言一致性门禁（TS 与 Python 哈希比对） | **已完成** | `5d34a09` |
| **Task 7** | Android 模块架构边界与网络所有权门禁（全仓禁 VPN/代理） | **已完成** | `f51aed4` |
| **Task 8** | Android 多账号隔离、凭据与会话生命周期 | **已完成** | `a7134ec` |
| **Task 9** | Android Gateway v2 HTTPS/SSE 客户端与附件传输 | **已完成** | `fe47f13` |
| **Task 10** | `.alp` 插件包构建器、验签、防 ZIP 炸弹与事务安装（含真机测试） | **已完成** | `b5d6d3c`、`10cc191` |
| **Task 11** | 平台内核（权限交集/沙箱）、Rust SDK、WASM 运行时与声明式 UI | **进行中（开发阶段）** | 本地代码编写中 |
| **Task 12** | Companion 身份校验与单用途加密字节通道 | 待开始 | — |
| **Task 13** | 将通知/短信/通话记录迁为参考插件 | 待开始 | — |
| **Task 14** | 将 tsnet (Tailscale) 迁为可选 Companion | 待开始 | — |
| **Task 15** | Android App 切换到极简核心（无插件亦可独立使用） | 待开始 | — |
| **Task 16** | 提供 v1 安全配置导出并冻结旧 Bridge | **基础已完成** | `8057d9a`（全仓大门禁留待最后验收） |

---

## 4. 环境与命令注意点（必读避坑指南）

### 4.1 Node / TypeScript 测试
- 系统全局 Node 版本为 `v26.7.0`（超出 `engines` 约束），直接运行会导致 SQLite 相关测试误报失败。
- **必须使用固定工具链包装器**：
  ```bash
  ./tools/run-node24 npx vitest run
  ./tools/run-node24 npm run typecheck
  ./tools/run-node24 npm run gateway:v2:conformance
  ```

### 4.2 Gradle / Android 构建
- 仓库路径包含中文字符（`数据/项目`），系统 `LANG=C` 会导致 JVM 报 `Could not find or load main class org.gradle.wrapper.GradleWrapperMain` 假死。
- 系统默认的 `ANDROID_HOME` 包含旧路径残留，每次执行 Gradle 前**必须设置环境变量**：
  ```bash
  export LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8
  export ANDROID_HOME=/mnt/数据/项目/open-android-intelligence/.toolchains/android-sdk
  export GRADLE_USER_HOME=/mnt/数据/项目/open-android-intelligence/.toolchains/gradle-home
  export ANDROID_USER_HOME=/mnt/数据/项目/open-android-intelligence/.toolchains/android-user-home
  ```

### 4.3 Python / Hermes 测试
- 虚拟环境已建立在 `/tmp/open-android-intelligence-venv`：
  ```bash
  /tmp/open-android-intelligence-venv/bin/python -m pytest integrations/hermes/tests -q
  ```

### 4.4 Rust / WASM 工具链
- 规范要求 **Rust 1.88.0 + `wasm32-unknown-unknown`**。
- `plugins/target/wasm32-unknown-unknown/release/echo.wasm` 产物已编译在本地；如需重新编译，需先通过 `rustup default 1.88.0` 与 `rustup target add wasm32-unknown-unknown` 准备好工具链。

### 4.5 Android 真机测试
- 跑真机测试前先执行 `adb devices` 确认设备已连接。若列表为空，不要强行跑 `connectedDebugAndroidTest`。

---

## 5. 当前 Task 11 状态与待办

### 当前代码现状：
1. **已建立的源码与测试**：
   - `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/`：包含 `PluginKernel`、`CapabilityGrant`、`PluginStateMachine`、`CapabilityProviderSelector`、`PluginPrivateStore`、`MediatedNetworkProxy`、`AndroidAuditStore`、`DeveloperTrustMode`、`NativePluginLoader`。
   - `apps/android/plugin-runtime-wasm/src/main/kotlin/com/openandroidintelligence/plugin/wasm/ChicoryPluginRuntime.kt`：纯 Java 解释器运行时（基于 Chicory 1.7.5，无 JNI/`.so`）。
   - `apps/android/plugin-ui/src/main/kotlin/com/openandroidintelligence/plugin/ui/DeclarativeUiSchema.kt`：声明式 UI 白名单解析。
   - `plugins/sdk-rust/` & `plugins/fixtures/echo/`：Rust SDK 与测试夹具。

2. **当前单测状态**：
   - `CapabilityGrant.kt` 中的 `EffectiveCapabilities.compute` 之前保留了红灯探针 `if (false && ...)`，需调整恢复为正确的六项交集判断：
     ```kotlin
     if (!inputs.pluginEnabled || grant == null) {
         return EffectiveCapabilitySet(emptySet(), backgroundAllowed = false)
     }
     ```
   - 修复后运行单元测试验证通过：
     ```bash
     cd apps/android && ./gradlew :platform-kernel:testDebugUnitTest :plugin-ui:testDebugUnitTest
     ```

### 下一步接手顺序：
1. 跑通 Task 11 的所有单元测试并复核代码覆盖。
2. 当真机连接后，运行 `connectedDebugAndroidTest` 验证 WASM 运行时和开发者信任模式真机测试。
3. 勾选计划文档中 Task 11 的复选框并提交：
   `git commit -m "新增: 实现受保护 WASM 插件平台内核"`。
4. 依次推进 Task 12（Companion 隧道）→ Task 13（参考插件）→ Task 14（Tailscale 解耦）→ Task 15（极简 App 切换）→ Task 16（最终门禁与全仓冻结）。

---

## 6. 核心架构决策与操作禁令

- **严禁使用 `rm`、`rm -rf` 等破坏性删除**：任何废弃文件必须移动至 `/tmp/open-android-intelligence-trash/`。
- **所有提交信息必须使用中文**，格式如 `<类型>: <简要描述>`。
- **代码中引用文件一律使用相对路径**，禁止硬编码绝对路径。
- **不可打破的六项权限交集公式**：
  $$\text{有效能力} = \text{宿主包络} \cap \text{手机级限制} \cap \text{插件声明} \cap \text{插件启用状态} \cap \text{当前配对授权} \cap \text{当前会话约束}$$
- **保留用户本地文件**：如 `.codebuddy/memory/` 等未跟踪的记忆文件不得误删或强行 stage。

---

## 7. 建议技能 (Suggested Skills)

- `superpowers:subagent-driven-development`：用于任务上下文拆解与子代理驱动（注意子代理选择性价比模型，如 `gpt-5.6-luna` / `flash`）。
- `tdd`：严格遵守编写失败测试（RED）→ 最小实现（GREEN）→ 重构流程。
- `superpowers:executing-plans`：按实施计划逐 Task 跟踪执行状态与检查点。
- `diagnosing-bugs`：排查 Android JVM 编码、WASM 边界或协议签名时间戳等反直觉问题。
- `code-review`：提交前进行严格的安全边界审查（防路径穿越、权限越界、签名伪造）。
