# Open Android Intelligence Android 对话与助理前端完全重构 Implementation Plan

> **2026-09-23 附件策略更新：** 本计划 Task 3 中的 `ArtifactMediaType` 闭集、25/50 MiB 单文件/消息上限与协商限制已由 ADR-0051 和 [当前流式附件修复计划](./2026-09-23-agent-owned-streaming-attachments.md) 取代。Pending Submission 的快照、一次性提交和 integrity gate 仍保留；附件格式与处理大小交给 Agent。


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从干净的 `main@732eb88` 重新建立真实、可测试、无假状态的 Android 对话与默认助理前端，视觉、共享对话领域、Gateway v2.1 能力、加密镜像、附件门控、系统 Assist、可中断物理动效、插件界面与无障碍验收。

**Architecture:** 不合并被审查为 BLOCK 的 `feat/conversation-assistant-ui`；该分支只作为视觉取证。新实现以 `conversation-domain` 深模块集中状态机、草稿、批次、附件与生成规则，以 `conversation-ui` 深模块承载主 App 和助理会话共用的 Compose 设计系统与组件，再由现有 `gateway-client`、`encrypted-store`、`platform-kernel` 和同 APK 私有进程适配器分别实现网络、持久化、安全与系统入口。协议/Schema/双宿主一致性必须先于依赖这些能力的 UI。

**Tech Stack:** Kotlin 2.1.20、Java 17、Jetpack Compose BOM 2024.12.01、Material 3、Navigation Compose 2.8.5、Kotlin Coroutines 1.9.0、Gateway Protocol v2.1 HTTPS/SSE、Android Keystore + SQLiteOpenHelper + AES-GCM、VoiceInteractionService/VoiceInteractionSessionService、JUnit 4、Compose UI Test、Macrobenchmark。

**Spec:** `docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md`；上位权威为 `docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md`、`CONTEXT.md`、`docs/contracts/gateway-protocol-v2.md`、`docs/contracts/device-plugin-package-v1.md` 和 ADR-0041–0046。

**Plan baseline (2026-09-01):**

- 权威起点：`main = 732eb88e87bb3bb30c823bcf750ea7dc54eefe27`。
- 被审查实现：`feat/conversation-assistant-ui = a496b0d922262085b19353fbf20ffddaa3713b60`，相对固定点为 `git diff 732eb88...a496b0d`，4 个提交、24 个文件、约 5025 行新增。
- feature worktree 的 5 个未提交文件必须原样保留；二进制 patch SHA-256 为 `4f419f7ef66f50c5fe35472edb81871d8e1f689ff9c4d2de3c3ab35077ba4715`。
- 当前 `main` 也有用户的 Hermes、legacy test、`.open-android-intelligence-hermes/` 和 `docs/superpowers/reviews/` 改动；任何 Task 都不得 stage、stash、reset、clean、覆盖或混入这些文件。
- 当前 `adb devices -l` 无设备；因此真机门禁当前是 **BLOCKED**，不能用编译、JVM 测试、模拟器或截图替代。
- 设计 `22.1` 原本建议一次只为一个切片写计划；用户本轮明确要求完整重构计划，因此本文覆盖完整 program，但执行门仍严格一次只做一个 Task，任何 Task 失败都阻止后续 Task。

## Global Constraints

- Android 可见核心只包含账号/Gateway、对话、用户明确选择的附件与设置中的平台管理；短信、通知、通话记录和 Tailscale 不得重新进入 App 本体。
- 平台内核继续拥有身份、权限、插件生命周期、隔离、审计和紧急停用；UI、Gateway、Agent 和插件都不能扩大本机授权。
- Android 最低/目标/编译版本保持 `minSdk 34`、`targetSdk 35`、`compileSdk 35`；本计划不夹带 AGP 9、Gradle 9 或 compileSdk 37 迁移。
- Compose 保持仓库现有稳定工具链。新增依赖只允许：`androidx.navigation:navigation-compose:2.8.5`、`androidx.compose.material3:material3-window-size-class`、`org.commonmark:commonmark:0.30.0`、`io.noties:prism4j:2.0.0`、Compose 测试依赖和 Macrobenchmark 1.4.1；任何额外依赖必须先修改本计划。
- V1 不使用系统动态取色。浅/深主题严格使用“设计系统”语义令牌；Signal Stitch 是唯一主要视觉签名。
- 不允许空函数、未使用输入、立即复位的假异步、伪造 Gateway、伪造消息、伪造附件、随机标题、默认成功状态或“在线同步中”硬编码。
- 所有 wire ID 使用不透明领域类型；Android 不接收、生成或保存 `agentSessionId`，账号身份与设备证明始终分离。
- 主 App 与助理会话只有一个草稿、附件列表、命令目录、批次、时间线和 generation 状态源；不能再出现平行 `StateFlow`。
- 任何需要新增 Gateway 行为的 UI 都必须先修改权威协议、严格 Schema、黄金向量和 Hermes/OpenClaw conformance；缺少协商能力时 UI 明确隐藏、禁用或显示“不支持”，不得猜测。
- 普通附件必须经过选择授权、字节读取、MIME/数量/大小校验、SHA-256、三步上传和 `verified`；屏幕选区只使用当前 Assist screenshot，完整截图不得持久化或上传。
- 附件提交必须冻结文字、有序附件快照、revision、`submitIntentId`、`clientMessageId` 和每个 HTTP request ID；全部 verified 后恰好提交一次。
- 关闭助理界面不等于取消 generation；只有结构化 `CANCELLED` 终态可显示“已停止”，`OUTCOME_UNKNOWN` 必须暂停同线程后续批次并恢复真实结果。
- 所有触控目标至少 48×48dp；动态字号、TalkBack、RTL、深浅主题、减少动态、手机/平板/横屏共享同一业务状态。
- 手势释放必须继承瞬时速度；栏↔球和圈选↔附件保持空间连续性；减少动态时使用短 Crossfade，不改变业务语义。
- 每个 Task 都执行 RED → 验证正确失败原因 → 最小 GREEN → focused test → 回归 → 独立 review → 中文提交。不得跨 Task 混改。
- 每个 Task 的报告必须区分 PASS、BLOCKED、SKIPPED；静态检查、APK 编译、模拟器截图不等于默认助理真机、跨 App 圈选或物理 Android↔Gateway E2E。
- 代码、测试、文档中的项目内文件引用全部使用相对路径。
- 严禁使用 `rm`、`rm -rf`、`unlink`。废弃文件使用 `trash-put`，或先 `mkdir -p /tmp/Open Android Intelligence-trash/android-conversation-ui/` 再 `mv`。
- 所有提交说明使用中文，格式 `<类型>: <简要描述>`。

## 执行隔离

执行 Task 1 前，使用 `superpowers:using-git-worktrees` 从固定点建立新 worktree；不要进入已有 feature worktree开发：

```bash
git worktree add .worktrees/android-conversation-ui-refactor \
  -b codex/android-conversation-ui-refactor \
  732eb88e87bb3bb30c823bcf750ea7dc54eefe27
```

每个 Android 命令使用同一环境前缀：

```bash
OPEN_ANDROID_INTELLIGENCE_ROOT="$(git rev-parse --show-toplevel)"
cd "$OPEN_ANDROID_INTELLIGENCE_ROOT/apps/android"
LANG=zh_CN.UTF-8 \
LC_ALL=zh_CN.UTF-8 \
ANDROID_HOME="$OPEN_ANDROID_INTELLIGENCE_ROOT/.toolchains/android-sdk" \
GRADLE_USER_HOME="$OPEN_ANDROID_INTELLIGENCE_ROOT/.toolchains/gradle-home" \
ANDROID_USER_HOME="$OPEN_ANDROID_INTELLIGENCE_ROOT/.toolchains/android-user-home" \
./gradlew --no-daemon --console=plain check
```

### Preflight：接受书面设计（只提交文档）

进入 Task 1 前，将 `docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md` front matter 的 `status: proposed` 改为 `status: accepted`，并在 front matter 后增加：

```markdown
approval:
  date: 2026-09-01
  basis: 用户明确要求依据本设计与 2026-08-30 审查报告制定并执行完整重构计划
```

随后执行：

```bash
git add docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md
git commit -m "文档: 接受 Android 对话与助理界面设计"
```

该提交不得包含计划或业务代码。

## Standards

固定 diff `732eb88...a496b0d` 的 Standards 轴为 **FAIL，11 项**（10 项明确违规、1 项 judgement call）。最严重事实：

- `PlatformSettingsScreen.kt`/`ConversationViewModel.kt` 绕过 Kernel 与 flavor policy 修改 Developer Trust，并在未 TLS/协商/认证时伪造 online。
- `ConversationViewModel.kt` 本地解释 `/new`、生成伪 conversation ID、立即结束假 generation、跨 Gateway 共用 timeline。
- picker/圈选直接标 VERIFIED/ACCEPTED，违反三步上传、附件门控与 exactly-once。
- 主 Activity 直接打开 Overlay，释放速度未进入 Spring，存在空 stop/route 回调。
- judgement call：主 App/助理各自读取 ContentResolver 是 Duplicated Code，应收敛到一个 selection adapter。

## Spec

同一固定 diff 的 Spec 轴为 **FAIL，6 项**。最严重事实：

- `7/`15/`16 的共享 repository、单状态源、加密镜像和事件流不存在。
- `10/`12 的真实 screenshot、PNG/digest 与待提交意图被假数据替代。
- `8/`13/`14 的批次上限、FIFO、真实取消、动态命令和 Agent 解释 `/new` 未实现。
- `3.2/`9.0 的同 APK 私有进程系统 Assist 未建立；实现一次跨越六切片且未先更新 contract。

两轴不能互相抵消：Standards 最坏问题是绕过安全权威/伪造状态，Spec 最坏问题是核心领域与真实系统/网络链路缺失。

## Motion review findings

| Before | After | Why |
|---|---|---|
| `AssistantOverlay.kt:58-112` 两个互斥 `AnimatedVisibility` 分别挂载栏/球 | 单一常驻 `AssistantMorphContainer` 从当前 presentation bounds retarget | 必须是 Shared element transition + Morph；重建会闪烁且不可快速反向 |
| `DockedBall.kt:146-159` 释放直接赋 `offsetX/offsetY` | X/Y 独立 `Animatable.animateTo`，传入 release velocity | Drag 到 Spring 的速度交接不能瞬移 |
| `ConversationTimeline.kt:70-88` 每个 token `animateScrollToItem` | 用户在底部才合帧 `scrollToItem`；上滑立即停跟随 | 当前实现抢滚动并反复取消/重启动画 |
| `DockedBall.kt:127-131` 下边界 `maxY+100f` 硬停 | 四边统一 Rubber-banding | 边缘需连续渐增阻力 |
| 固定 1080×2400、3x density | layout size + LocalDensity + safe drawing/IME insets | 旋转、分屏、折叠和不同密度必须保持安全位置 |
| Send/Stop 使用 0.8 scale | 高频切换使用 0.95–1.0 + 150–180ms，或 reduced Crossfade | 安静产品不需要夸张 Pop in |
| Signal Stitch/完成态无限 Rotate/Pulse | thinking 状态克制且有文字；完成只 one-shot pulse；error 静止 | Purpose/frequency 与“安静”设计不允许持续抢注意力 |
| `reducedMotionSpec` 定义但未消费 | 全局 MotionPolicy；栏/球/圈选使用 150ms Crossfade | reduced motion 必须是可验证业务分支 |
| 圈选闭合写 `72f` | `24.dp.toPx()` + locked PointerId | 物理阈值不能依赖假定密度，多指不能扭曲路径 |
| 防抖每 100ms 重启 FastOutSlowIn tween | deadline + frame clock 的 Linear progress | 倒计时是连续进度，不应每步重新缓入缓出 |

**Motion verdict：BLOCK。** Feel-breaking：栏/球对象身份断裂、释放瞬移、时间线抢滚动。Performance 项 `expandVertically` 必须先 trace，不能把 Web/CSS 规则机械套到 Compose。Accessibility：reduced motion 当前无效。没有真机/逐帧证据，因此不宣称运行时结论。

## 审查校准：哪些报告项进入计划

| 结论 | 报告项 | 本计划处理 |
|---|---|---|
| 确认阻断 | 01–15、17–20、23–24、27–49、51–61、63–64、66–72 | 由 Task 1–18 逐项关闭；不能只改视觉。 |
| 需要重述 | 16 | 当前 `dragAmount` 累加并不会天然把球心吸到手指；Task 15 以“按下无跳变、拖动 1:1、释放继承速度”的行为测试代替错误的实现猜测。 |
| 需要实测再判 | 21 | Compose `expandVertically` 是平台布局动画，不等同于 Web 的 CSS layout thrashing；Task 12 先做 trace/帧证据，再决定保留或改为 `animateContentSize`/`graphicsLayer`。 |
| 不是强制旋转 | 22 | 工具执行中需要明确状态，但不强制 Pulse/Rotate；Task 12 使用标准进度语义，减少动态时静态显示。 |
| 平台方式修正 | 26 | Manifest 显式 opt-in + Navigation Compose/`PredictiveBackHandler`；不写自制返回栈。 |
| 驳回误报 | 50 | 不因“主题切换会重组”替换 `staticCompositionLocalOf`；不可变主题令牌极少变化，根重组本身不是缺陷。 |
| 部分接受 | 62 | 只约束间距为 4dp 网格；1/2.5dp 描边、48dp 触控、56dp 球、680/760dp 内容上限和 300dp 导航宽属于记录过的尺寸例外。 |
| 驳回误报 | 65 | `Long > 0` 后 `log10` 不会产生 NaN；旧 `formatBytes` 会随旧 Presenter/ViewModel 一并删除，不单独制造无意义修复。 |

## 视觉命题锁定

- **具体产品：** 连接用户自有 Agent Gateway 的 Android 本机对话工作台。
- **目标用户：** 重视设备控制权、账号隔离和可验证状态的 Android 用户。
- **单一工作：** 清楚地完成“选择 Gateway → 对话/附件 → 观察真实状态 → 必要时从系统助理继续”的闭环。
- **色彩：** Canvas `#F1F4F1/#101613`、Surface `#E3ECE7/#19231F`、Surface high `#D5E2DC/#26332E`、Primary `#2F645C/#88BAAE`、Accent `#B8874A/#D3A86F`、Text `#1C2724/#E8EFEB`、Muted `#63706B/#9CABA5`、Error `#A95E50/#E1998C`。
- **字体：** 系统无衬线/Noto Sans SC 回退用于 UI 与正文；Roboto Mono 只用于时间、状态、大小和代码元数据。
- **布局：** compact 为抽屉+单对话；medium 为线程+对话；expanded 为 Gateway+线程+对话，正文列不超过 680dp。
- **唯一签名：** Signal Stitch 只在助手正文、助理容器、停靠球和屏幕选区表达同一物体关系；不增加 AI 星光、渐变光晕、机器人头像或第二套装饰语言。
- **自我批评结果：** “设计系统”不依赖常见紫蓝 AI 渐变、酸性色或报纸式网格；真正的审美风险只花在 Signal Stitch 的跨表面连续身份，其余界面保持克制。

## 统一动效词汇

| 术语 | 在本项目中的精确定义 |
|---|---|
| **Shared element transition** | An element travels and transforms from one position into another, like a thumbnail expanding into a card. 用于圈选预览进入附件 chip。 |
| **Morph** | One shape smoothly turns into another shape, e.g. Dynamic Island. 用于同一助理容器由栏变为球。 |
| **Continuity transition** | A change that keeps the user oriented by visually connecting before and after. 用于保持栏、球、Signal Stitch 的对象恒常性。 |
| **Direction-aware transition** | Content slides one way going forward and the opposite way going back, so navigation has a sense of direction. 用于主 App 层级导航。 |
| **Drag** | Moving an element by grabbing it, often with momentum when released. 用于停靠球直接操纵。 |
| **Momentum** | Motion that carries velocity, especially after a drag or interruption. 用于释放后的落点投影。 |
| **Rubber-banding** | Resistance and snap-back when you drag past a boundary (the iOS overscroll feel). 用于安全边缘渐增阻力。 |
| **Spring** | Motion driven by physics (tension, mass, damping) rather than a set duration. 用于 Morph 与吸附。 |
| **Interruptible animation** | An animation that can be smoothly redirected mid-flight instead of finishing first. 所有手势与栏↔球转换必须满足。 |
| **Crossfade** | One element fades out as another fades in, in the same spot. 减少动态时替代位移/弹簧。 |

## 目标文件结构与深模块

| Module | Interface / seam | Implementation responsibility |
|---|---|---|
| `gateway-contract` | 严格 Schema、向量与 normalized result | v2.1 功能协商、命令目录、批次、取消、快照、附件查询和事件闭集。 |
| Hermes/OpenClaw Gateway | 现有 `GatewayCore.handle()` / `ConversationPort` | 两个独立宿主实现，同一语义与 conformance，不共享运行二进制。 |
| `gateway-client` | typed request/event clients | Android HTTPS/SSE 适配器；不含 UI 状态与业务猜测。 |
| 新 `conversation-domain` | `ConversationController` + 四个 ports | 一个深模块隐藏四轴状态机、草稿、批次、FIFO、标题、取消和附件门控。 |
| `encrypted-store` | `ConversationMirrorStore` / `LocalMediaCacheStore` adapters | 按账号×Gateway×安装实例隔离的 SQLite 元数据与 AES-GCM payload。 |
| 新 `conversation-ui` | stateless Compose functions + `UiAction` | 主 App 与助理共用主题、时间线、编辑器、附件、插件渲染和动效令牌。 |
| `app` | Navigation Compose + `AppGraph` | 主进程组合根、自适应 Shell、平台设置和真实 adapter 注入。 |
| `assistant-holder`（改为 library） | 版本化 Messenger IPC | 同 APK 的 keeper/session 私有进程、Assist screenshot 和助理专用表面。 |

依赖顺序：

```text
Task 1 protocol
   └─> Task 2 dual hosts ─> Task 3 Android client
                               └─> Task 4 domain model ─> Task 5 controller
                                      ├─> Task 6 commands/batches/cancel
                                      ├─> Task 7 attachment gate
                                      └─> Task 8 mirror/cache
Task 4 ─> Task 10 design/UI module
Task 5–10 ─> Task 11 shell ─> Task 12 timeline ─> Task 13 composer
Task 9–13 ─> Task 14 system assistant ─> Task 15 morph/dock ─> Task 16 selection
Task 10–14 ─> Task 17 plugin UI
all ─> Task 18 release evidence
```

---

### Task 1: 固定 Gateway Protocol v2.1 对话界面扩展

**Files:**
- Modify: `docs/contracts/gateway-protocol-v2.md`
- Modify: `gateway-contract/schemas/negotiate.schema.json`
- Modify: `gateway-contract/schemas/conversation.schema.json`
- Modify: `gateway-contract/schemas/attachment.schema.json`
- Modify: `gateway-contract/schemas/event.schema.json`
- Create: `gateway-contract/schemas/command-catalog.schema.json`
- Create: `gateway-contract/schemas/conversation-snapshot.schema.json`
- Modify: `gateway-contract/src/schema-registry.ts`
- Modify: `gateway-contract/src/state-machines.ts`
- Modify: `gateway-contract/vectors/vector-set-1.0.0.schema.json`
- Create: `gateway-contract/vectors/conversation-ui.json`
- Modify: `gateway-contract/test/schema-registry.test.ts`
- Modify: `gateway-contract/test/golden-vectors.test.ts`
- Modify: `gateway-contract/test/state-machines.test.ts`

**Interfaces:**
- Consumes: v2.0 envelope、签名、幂等、附件与 SSE 规则。
- Produces: protocol minor `2.1`；协商 feature `agent-command-catalog-v1`、`message-batches-v1`、`newline-v1`、`generation-cancel-v1`、`conversation-mirror-v1`、`attachment-status-v1`；limits `maxBatchMembers` 和 `maxBatchBytes`；新增闭集 wire IDs `clientBatchId`、`generationId`、`catalogVersion`。

- [ ] **Step 1: 写 v2.1 协商与未知字段 RED**

```ts
it("accepts the complete v2.1 conversation feature set", () => {
  expect(validateGatewayValue("negotiate.response", {
    protocol: { major: 2, minor: 1 },
    features: {
      auth: ["password"],
      messages: "chat-v1",
      attachments: "staged-sha256-v1",
      events: "sse-cursor-v1",
      deviceRequests: "risk-queue-v1",
      conversationUi: [
        "agent-command-catalog-v1",
        "message-batches-v1",
        "newline-v1",
        "generation-cancel-v1",
        "conversation-mirror-v1",
        "attachment-status-v1",
      ],
    },
    limits: { maxBatchMembers: 20, maxBatchBytes: 65536 },
  })).toEqual({ ok: true });
});
```

- [ ] **Step 2: 运行 RED 并确认是 Schema 尚未声明能力**

Run: `./tools/run-node24 npm --prefix gateway-contract test -- schema-registry.test.ts`  
Expected: FAIL；错误指向 `conversationUi`/`maxBatchMembers` 未知字段，而不是工具链或 JSON 语法。

- [ ] **Step 3: 写批次 newline-v1 与 generation reducer RED**

```ts
it("joins members with one U+000A and no trailing LF", () => {
  expect(joinMessageBatch([
    { clientMessageId: "msg_a", text: "甲\n" },
    { clientMessageId: "msg_b", text: "\n乙" },
  ])).toEqual(new TextEncoder().encode("甲\n\n\n乙"));
});

it("does not overwrite outcome_unknown with completed", () => {
  expect(() => nextGenerationState("outcome_unknown", "complete"))
    .toThrow("INVALID_STATE_TRANSITION");
});
```

- [ ] **Step 4: 扩展权威契约与严格 Schema**

契约必须精确增加：

```text
GET  /commands?languageCode=<bcp47>
POST /conversations/{conversationId}/message-batches
POST /conversations/{conversationId}/generations/{generationId}/cancel
GET  /attachments/{attachmentId}
DELETE /attachments/{attachmentId}
GET  /conversations/{conversationId}/messages?clientMessageId=<id>
GET  /conversations
GET  /conversations/{conversationId}
GET  /conversations/{conversationId}/attachments/{attachmentId}/metadata
POST /conversations/{conversationId}/attachments/{attachmentId}/cache-grant
GET  /conversations/{conversationId}/attachments/{attachmentId}/content
```

新增 SSE 闭集必须包含 `conversation.message.accepted`、`conversation.generation.cancelled`、`conversation.command.result`、`conversation.title.updated`、`conversation.timeline.upsert`、`conversation.timeline.tombstoned`、`conversation.snapshot.invalidated`。对象继续 `additionalProperties:false`，消息流 sequence 连续、终态唯一、终态后增量失败关闭。

- [ ] **Step 5: 扩展共享向量**

`conversation-ui.json` 顶层使用 `vectorSet:"conversation-ui"`，只允许：

```ts
type ConversationUiVectorOperation =
  | "schema.validate"
  | "message.batch.join"
  | "generation.transition";
```

至少覆盖：空/含换行/Unicode 成员、20/21 成员、字节上限、重复 `clientMessageId`、cancel requested 后各真实终态、终态后 delta、命令结果不得含 `agentSessionId`、tombstone 不含旧正文。

- [ ] **Step 6: 实现最小 pure functions 与 registry**

```ts
export function joinMessageBatch(
  members: readonly Readonly<{ clientMessageId: string; text: string }>[],
): Uint8Array {
  if (members.length === 0 || members.length > 20) throw new Error("SCHEMA_INVALID");
  if (new Set(members.map((member) => member.clientMessageId)).size !== members.length) {
    throw new Error("SCHEMA_INVALID");
  }
  return new TextEncoder().encode(members.map((member) => member.text).join("\n"));
}
```

- [ ] **Step 7: 运行 GREEN 与协议回归**

Run:

```bash
./tools/run-node24 npm --prefix gateway-contract test
./tools/run-node24 npm --prefix gateway-contract run typecheck
./tools/run-node24 npm --prefix gateway-contract run typecheck:tools
./tools/run-node24 npx vitest run --exclude '.worktrees/**'
```

Expected: 新旧向量、Schema、类型和根回归全部 PASS，`git diff --check` 为 0。

- [ ] **Step 8: 独立审查并提交**

审查必须确认：协议 minor/feature 交集、wire ID 闭集、幂等绑定、终态、Schema digest 和向量 runner 没有自报 Schema 或 silent fallback。

```bash
git add docs/contracts/gateway-protocol-v2.md gateway-contract
git commit -m "契约: 扩展 Gateway v2.1 对话界面能力"
```

### Task 2: 实现 Hermes/OpenClaw 双宿主 v2.1 行为与一致性

**Files:**
- Modify: `integrations/openclaw/src/core/conversation-port.ts`
- Modify: `integrations/openclaw/src/core/account-store.ts`
- Modify: `integrations/openclaw/src/core/event-store.ts`
- Modify: `integrations/openclaw/src/core/gateway-core.ts`
- Modify: `integrations/openclaw/src/http/routes.ts`
- Create: `integrations/openclaw/test/conversation-ui-v21.test.ts`
- Create: `integrations/openclaw/test/conversation-ui-test-support.ts`
- Modify: `integrations/hermes/open_android_intelligence_gateway/core.py`
- Modify: `integrations/hermes/open_android_intelligence_gateway/http.py`
- Create: `integrations/hermes/tests/test_conversation_ui_v21.py`
- Modify: `gateway-contract/tools/run-openclaw-conformance.ts`
- Modify: `gateway-contract/tools/run-hermes-conformance.py`
- Modify: `gateway-contract/test/cross-host-conformance.test.ts`

**Interfaces:**
- Consumes: Task 1 v2.1 Schema、状态机与 `conversation-ui.json`。
- Produces: 两个独立宿主的命令目录、批次、generation cancel、快照/tombstone、附件状态/删除/历史媒体 grant；normalized result hash 相同。

- [ ] **Step 1: 写跨宿主 RED**

OpenClaw：

```ts
it("accepts one batch, preserves members, and starts one generation", async () => {
  const response = await core.handle(verifiedRequest({
    method: "POST",
    target: "/open-android-intelligence/v2/conversations/conv_1/message-batches",
    requestId: "req_batch_1",
    body: {
      clientBatchId: "batch_1",
      joinMode: "newline-v1",
      members: [
        { clientMessageId: "msg_1", text: "甲" },
        { clientMessageId: "msg_2", text: "乙" },
      ],
    },
  }));
  expect(response.data?.batch.memberMappings).toHaveLength(2);
  expect(response.data?.batch.generationId).toMatch(/^[A-Za-z0-9._~-]+$/);
});
```

Hermes 写同一输入/输出断言，不共享生产代码。

`conversation-ui-test-support.ts` 在本 Task 定义调用形状，测试不依赖隐式 global：

```ts
type VerifiedRequestOverrides = Partial<Omit<VerifiedGatewayRequest, "context">> &
  Readonly<{ requestId?: string }>;

export const verifiedRequest = (
  overrides: VerifiedRequestOverrides,
): VerifiedGatewayRequest => {
  const { requestId = "req_test", ...request } = overrides;
  return Object.freeze({
    context: Object.freeze({
      accountId: "acct_test",
      deviceId: "dev_test",
      sessionId: "sess_test",
      requestId,
      correlationId: "cor_test",
      pairingGeneration: 1,
      grantRevision: 1,
    }),
    method: "GET",
    target: "/open-android-intelligence/v2/conversations",
    now: new Date("2026-09-01T00:00:00.000Z"),
    ...request,
  });
};
```

- [ ] **Step 2: 运行 RED 并确认路由未实现**

Run:

```bash
./tools/run-node24 npm --prefix integrations/openclaw test -- conversation-ui-v21.test.ts
PYTHONPATH=integrations/hermes python3 -m pytest \
  integrations/hermes/tests/test_conversation_ui_v21.py -q
```

Expected: 两端都以未知 route/Schema 失败，不得因 fixture 或 import 失败。

- [ ] **Step 3: 深化 ConversationPort，不把 Agent 内部 session 暴露给 Android**

```ts
export interface AgentConversationBackend {
  commandCatalog(languageCode: string): Promise<AgentCommandCatalog>;
  acceptInput(input: Readonly<{
    conversationId: string;
    aggregateText: string;
    attachmentIds: readonly string[];
  }>): Promise<Readonly<{ generationId: string }>>;
  cancelGeneration(input: Readonly<{
    conversationId: string;
    generationId: string;
  }>): Promise<"cancelled" | "already-completed" | "unsupported" | "outcome-unknown">;
}
```

宿主内部 session 绑定只保存在账号隔离数据库，不进入响应、事件、日志或向量。

- [ ] **Step 4: 建立事务与幂等语义**

每个批次事务保存 `clientBatchId`、成员映射、request ID、body hash、generation ID 和终态。相同 request/body replay 返回原结果；相同 request/不同 body 返回 `IDEMPOTENCY_CONFLICT`；host 调用返回前崩溃记录 `OUTCOME_UNKNOWN`，不创建第二个 generation。

- [ ] **Step 5: 实现真实取消和事件顺序**

`CANCEL_REQUESTED` 不是 `CANCELLED`。只有 backend 或恢复查询返回真实 outcome 后写一个终态事件；终态后任何 delta 以内部非法转移失败，并在 HTTP 边界映射为 `SCHEMA_INVALID`。

- [ ] **Step 6: 实现快照、tombstone 与附件查询**

tombstone payload 只能含 ID、revision、删除时间和类型，不能含旧正文、缩略图、工具输出或附件下载位置。cache grant 短期、单用途、对话/附件/账号绑定；任意 URL、provider URI 或路径拒绝。

- [ ] **Step 7: 扩展 conformance runner**

两个 runner 对 Task 1 的每个 vector 输出：

```ts
type NormalizedConversationUiResult = Readonly<{
  vectorId: string;
  operation: "schema.validate" | "message.batch.join" | "generation.transition";
  outcome: "value" | "error";
  value?: unknown;
  code?: "SCHEMA_INVALID" | "INVALID_STATE_TRANSITION";
}>;
```

hash 只覆盖上述 JCS，不包含宿主、时间、路径、堆栈或 vendor diagnostics。

- [ ] **Step 8: 运行 GREEN、负向探针和回归**

```bash
./tools/run-node24 npm --prefix integrations/openclaw test
./tools/run-node24 npm --prefix integrations/openclaw run typecheck
PYTHONPATH=integrations/hermes python3 -m pytest integrations/hermes/tests -q
./tools/run-node24 npm run gateway:v2:conformance
./tools/run-node24 npx vitest run --exclude '.worktrees/**'
```

负向探针：篡改一个 newline result、终态后追加 delta、重复 member ID、跨账号 attachment ID；门禁必须非零退出，再恢复探针。

- [ ] **Step 9: 独立双轴审查并提交**

```bash
git add integrations/openclaw integrations/hermes gateway-contract
git commit -m "新增: 实现双宿主对话界面协议"
```

### Task 3: 扩展 Android Gateway typed clients

**Files:**
- Modify: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/conversations/ConversationClient.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/conversations/ConversationWireModels.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/commands/AgentCommandCatalogClient.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/generation/GenerationClient.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/attachments/AttachmentStatusClient.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/events/ConversationEvent.kt`
- Create: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/negotiation/GatewayFeatureSet.kt`
- Create: `apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/conversations/ConversationClientV21Test.kt`
- Create: `apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/events/ConversationEventTest.kt`
- Create: `apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/ConversationUiVectorTest.kt`

**Interfaces:**
- Consumes: Task 1–2 v2.1 wire contract。
- Produces: typed Android transport methods；未经 strict Schema dispatch 的 payload 不能进入领域模块。

- [ ] **Step 1: 写 request identity 与能力降级 RED**

```kotlin
@Test
fun batchRetryReusesRequestAndLogicalIds() = runTest {
    val transport = RecordingConversationTransport()
    val client = ConversationClientV21(transport)
    val batch = OutgoingMessageBatch(
        clientBatchId = ClientBatchId("batch_1"),
        members = listOf(OutgoingBatchMember(ClientMessageId("msg_1"), "甲")),
    )

    client.submitBatch(ConversationId("conv_1"), RequestId("req_1"), batch)
    client.submitBatch(ConversationId("conv_1"), RequestId("req_1"), batch)

    assertEquals(listOf("req_1", "req_1"), transport.requests.map { it.requestId.value })
    assertEquals(listOf("batch_1", "batch_1"), transport.requests.map { it.batch.clientBatchId.value })
}
```

- [ ] **Step 2: 运行 RED**

Run: `:gateway-client:testDebugUnitTest --tests '*ConversationClientV21Test*'`  
Expected: FAIL，缺少 typed client/model，不得是 SDK 或编码失败。

- [ ] **Step 3: 定义不透明 wire 类型与 feature set**

```kotlin
@JvmInline value class RequestId(val value: String)
@JvmInline value class ConversationId(val value: String)
@JvmInline value class ClientMessageId(val value: String)
@JvmInline value class ClientBatchId(val value: String)
@JvmInline value class GenerationId(val value: String)
@JvmInline value class CatalogVersion(val value: String)

data class GatewayFeatureSet(
    val commandCatalog: Boolean,
    val messageBatches: Boolean,
    val newlineJoin: Boolean,
    val generationCancel: Boolean,
    val conversationMirror: Boolean,
    val attachmentStatus: Boolean,
    val maxBatchMembers: Int?,
    val maxBatchBytes: Long?,
)
```

所有 value class 初始化都调用从 `RequestSigner` 抽出的 internal `requireWireId(value, field)`；禁止解析前缀。`ConversationClientV21Test.kt` 在同一文件定义 `RecordingConversationTransport`，其 `submitBatch` 把 `RecordedBatchRequest(requestId, batch)` 加入 mutable list 并返回固定 `GenerationId("gen_test")`；其他 interface 方法返回显式空 page/result，禁止抛未实现异常。

- [ ] **Step 4: 实现 typed clients**

```kotlin
interface ConversationTransport {
    suspend fun list(page: PageRequest): ConversationPage
    suspend fun timeline(conversationId: ConversationId, page: PageRequest): TimelinePage
    suspend fun submitMessage(requestId: RequestId, message: OutgoingMessage): MessageAcceptance
    suspend fun submitBatch(requestId: RequestId, batch: OutgoingMessageBatch): BatchAcceptance
    suspend fun queryByClientMessageId(
        conversationId: ConversationId,
        clientMessageId: ClientMessageId,
    ): MessageLookupResult
}
```

`AgentCommandCatalogClient`、`GenerationClient` 和 `AttachmentStatusClient` 只返回经过严格 Schema 验证的冻结模型。

- [ ] **Step 5: 实现 event parser 投影**

```kotlin
sealed interface VerifiedConversationEvent {
    val eventId: String
    val occurredAt: Instant

    data class MessageDelta(/* ids, sequence, delta */) : VerifiedConversationEvent
    data class MessageCompleted(/* ids, terminal sequence */) : VerifiedConversationEvent
    data class GenerationCancelled(/* generationId */) : VerifiedConversationEvent
    data class CommandResult(/* source ids, outcome, optional conversationId */) : VerifiedConversationEvent
    data class TimelineUpsert(/* revision, item */) : VerifiedConversationEvent
    data class TimelineTombstoned(/* id, revision; no old content */) : VerifiedConversationEvent
    data class SnapshotInvalidated(/* snapshotRevision */) : VerifiedConversationEvent
}
```

- [ ] **Step 6: 消费共享向量**

`ConversationUiVectorTest` 必须读取仓库相对路径 `../../gateway-contract/vectors/conversation-ui.json`，逐 case 调用生产 join/reducer/Schema adapter；不复制 fixture。

- [ ] **Step 7: 运行 GREEN 与 Android client 回归**

Run:

```bash
:gateway-client:testDebugUnitTest
:gateway-client:connectedDebugAndroidTest
```

Expected: JVM tests PASS；当前无设备时 connected test 明确记录 BLOCKED，不能写 PASS。

- [ ] **Step 8: 审查并提交**

```bash
git add apps/android/gateway-client
git commit -m "新增: 扩展 Android Gateway 对话客户端"
```

### Task 4: 建立 conversation-domain 模块、类型与四轴 reducer

**Files:**
- Modify: `apps/android/settings.gradle.kts`
- Create: `apps/android/conversation-domain/build.gradle.kts`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/model/Identifiers.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/model/MessagePart.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/model/ConversationState.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/model/ConversationIntent.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/reducer/ConversationReducer.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/ports/ConversationPorts.kt`
- Create: `apps/android/conversation-domain/src/test/kotlin/com/openandroidintelligence/conversation/reducer/ConversationReducerTest.kt`
- Create: `apps/android/conversation-domain/src/test/kotlin/com/openandroidintelligence/conversation/model/IdentifierBoundaryTest.kt`

**Interfaces:**
- Consumes: Task 3 typed wire models only through ports。
- Produces: one shared domain vocabulary；UI 不再拥有网络/文件/URI/凭据。

- [ ] **Step 1: 注册新模块并写非法状态 RED**

```kotlin
@Test
fun acceptedComposerCannotReturnToWaitingAttachments() {
    val state = ConversationSessionState(
        surface = SurfaceState.HIDDEN,
        generation = GenerationState.IDLE,
        composer = ComposerState.ACCEPTED,
        attachments = emptyList(),
    )

    assertFailsWith<InvalidConversationTransition> {
        ConversationReducer.reduce(state, ConversationEvent.AttachmentsPending)
    }
}
```

- [ ] **Step 2: 运行 RED**

Run: `:conversation-domain:testDebugUnitTest`  
Expected: FAIL，模块或 reducer 尚不存在。

- [ ] **Step 3: 定义 MessagePart 与 opaque IDs**

```kotlin
sealed interface MessagePart {
    data class Text(val value: String) : MessagePart
    data class Attachment(val draftId: AttachmentDraftId) : MessagePart
    data class Command(val rawText: String, val catalogVersion: CatalogVersion?) : MessagePart
}

@JvmInline value class GatewayId(val value: String)
@JvmInline value class ConversationId(val value: String)
@JvmInline value class ClientMessageId(val value: String)
@JvmInline value class AttachmentDraftId(val value: String)
@JvmInline value class SubmitIntentId(val value: String)
```

所有构造器拒绝空白和非 wire-ID 字符；显示名、URL、MIME 和正文不复用 ID 类型。

- [ ] **Step 4: 定义四轴与同步状态**

```kotlin
enum class SurfaceState { HIDDEN, EXPANDED, SELECTING_SCREEN, CROP_PREVIEW, DOCKED, TERMINATED }
enum class GenerationState {
    IDLE, QUEUED, RUNNING, CANCEL_REQUESTED, CANCELLED,
    COMPLETED, FAILED, UNSUPPORTED, OUTCOME_UNKNOWN,
}
enum class ComposerState {
    EDITING, DEBOUNCE_COLLECTING, SEALED, WAITING_NETWORK,
    WAITING_ATTACHMENTS, SUBMITTING, ACCEPTED, FAILED,
}
enum class AttachmentState {
    LOCAL_PREPARING, CREATE_PENDING, UPLOADING, VERIFYING, VERIFIED,
    RETRYABLE_FAILURE, TERMINAL_FAILURE, OUTCOME_UNKNOWN, CANCELLED,
}
enum class MirrorSyncState {
    SYNCED, CATCHING_UP, OFFLINE_MIRROR, STALE_MIRROR, RESYNC_REQUIRED,
    ACCOUNT_LOCKED, LOCAL_DATA_REMOVED, PAIRING_REVOKED,
}
```

- [ ] **Step 5: 定义深模块 ports**

```kotlin
interface ConversationRepository {
    suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage
    suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation
    suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage
    suspend fun submitBatch(batch: MessageBatch): BatchAcceptance
    suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance
    fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent>
    suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult
}

interface AgentCommandCatalogRepository {
    suspend fun get(gatewayId: String, languageCode: String): AgentCommandCatalog
}

interface AttachmentDraftCoordinator {
    suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft
    suspend fun armSubmission(draftId: String, revision: Long): PendingSubmissionIntent
    suspend fun cancelSubmission(intentId: String): CancelSubmissionResult
    fun observe(draftId: String): Flow<AttachmentDraftState>
}

interface ConversationMirrorStore {
    suspend fun open(scope: MirrorScope): MirrorSession
    suspend fun lock(scope: MirrorScope)
    suspend fun wipeForUnpairing(scope: MirrorScope)
    suspend fun wipeForLocalAccountRemoval(scope: MirrorScope)
}
```

- [ ] **Step 6: 实现封闭 reducer**

每个事件只改变相关轴；非法组合抛 `InvalidConversationTransition`。`OUTCOME_UNKNOWN` 不接受普通完成覆盖；关闭 surface 不改变 generation；编辑/附件变化使旧 `PendingSubmissionIntent` 失效。

- [ ] **Step 7: 运行 GREEN 与负向矩阵**

Run: `:conversation-domain:testDebugUnitTest`  
Expected: 所有合法矩阵和 `state × event` 负向组合 PASS；无 Android/Compose 依赖。

- [ ] **Step 8: 审查并提交**

```bash
git add apps/android/settings.gradle.kts apps/android/conversation-domain
git commit -m "新增: 建立共享对话领域状态机"
```

### Task 5: 实现单一 ConversationController 与共享状态源

**Files:**
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/ConversationController.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/DefaultConversationController.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/internal/EventProjector.kt`
- Create: `apps/android/conversation-domain/src/test/kotlin/com/openandroidintelligence/conversation/DefaultConversationControllerTest.kt`
- Create: `apps/android/conversation-domain/src/test/kotlin/com/openandroidintelligence/conversation/fakes/InMemoryConversationAdapters.kt`

**Interfaces:**
- Consumes: Task 4 reducer/ports。
- Produces: main App 与 assistant IPC 共用的唯一 `StateFlow<ConversationSessionState>` 和单入口 `dispatch`。

- [ ] **Step 1: 写单草稿与 scope 隔离 RED**

```kotlin
@Test
fun mainAndAssistantObserveTheSameDraftButDifferentGatewaysNeverShareTimeline() = runTest {
    val controller = controllerWithInMemoryAdapters()
    controller.dispatch(ConversationIntent.ActivateScope(scopeA))
    controller.dispatch(ConversationIntent.EditDraft("同一草稿"))
    assertEquals("同一草稿", controller.state.value.composerDraft.text)

    controller.dispatch(ConversationIntent.ActivateScope(scopeB))
    assertEquals("", controller.state.value.composerDraft.text)
    assertTrue(controller.state.value.timeline.none { it.scope == scopeA })

    controller.dispatch(ConversationIntent.ActivateScope(scopeA))
    assertEquals("同一草稿", controller.state.value.composerDraft.text)
}
```

- [ ] **Step 2: 运行 RED**

Run: `:conversation-domain:testDebugUnitTest --tests '*DefaultConversationControllerTest*'`  
Expected: FAIL，controller 尚不存在。

- [ ] **Step 3: 定义小 interface**

```kotlin
interface ConversationController {
    val state: StateFlow<ConversationSessionState>
    suspend fun dispatch(intent: ConversationIntent): ConversationIntentResult
}
```

调用方只学习 `state + dispatch`；网络、镜像、批次计时、附件重试和 SSE projector 都隐藏在 implementation。

`InMemoryConversationAdapters.kt` 同时定义本测试使用的 fixtures：

```kotlin
internal val scopeA = ConversationScope("profile_a", "gateway_a", "account_a", "install_a")
internal val scopeB = ConversationScope("profile_b", "gateway_b", "account_b", "install_a")

internal fun controllerWithInMemoryAdapters(): DefaultConversationController =
    DefaultConversationController(
        conversations = InMemoryConversationRepository(),
        commands = InMemoryCommandCatalogRepository(),
        attachments = InMemoryAttachmentDraftCoordinator(),
        mirrors = InMemoryConversationMirrorStore(),
        clock = TestConversationClock(),
    )
```

- [ ] **Step 4: 实现 scope 激活与事件恢复**

激活顺序固定：锁定旧 scope → 打开新 mirror → emit 本地快照 → 拉远端 snapshot → 以 revision/cursor 合并 → 启动事件流。后台其他 scope 事件只更新对应 mirror，不抢当前滚动或草稿。

- [ ] **Step 5: 实现真实状态投影**

`EventProjector` 必须检查 message sequence 连续、终态唯一、revision 单调和 tombstone 清除正文；错误转为 `RESYNC_REQUIRED`，不能丢弃后继续。

- [ ] **Step 6: 实现失败/空状态，不伪造在线**

未配置 Gateway：`NoGatewayConfigured`；未认证：`AccountLocked`；连接中断且有镜像：`OFFLINE_MIRROR`；无镜像：`ConnectionUnavailable`。任何状态都有下一步动作，不使用“发生未知错误”。

- [ ] **Step 7: 运行 GREEN 与并发回归**

测试至少覆盖：scope 快速切换、旧 SSE 晚到、重复事件、cursor 过期、tombstone、进程恢复、同一 controller 两个观察者。使用 `StandardTestDispatcher` 和虚拟时钟，禁止真实 delay。

Run: `:conversation-domain:testDebugUnitTest`

- [ ] **Step 8: 审查并提交**

```bash
git add apps/android/conversation-domain
git commit -m "新增: 实现共享对话控制器"
```

### Task 6: 实现命令目录、防抖批次、标题、FIFO 与真实取消

**Files:**
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/commands/CommandCoordinator.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/batch/DebounceBatcher.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/title/ConversationTitlePolicy.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/generation/GenerationQueue.kt`
- Modify: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/DefaultConversationController.kt`
- Create: `apps/android/conversation-domain/src/test/kotlin/com/openandroidintelligence/conversation/commands/CommandCoordinatorTest.kt`
- Create: `apps/android/conversation-domain/src/test/kotlin/com/openandroidintelligence/conversation/batch/DebounceBatcherTest.kt`
- Create: `apps/android/conversation-domain/src/test/kotlin/com/openandroidintelligence/conversation/generation/GenerationQueueTest.kt`
- Create: `apps/android/conversation-domain/src/test/kotlin/com/openandroidintelligence/conversation/title/ConversationTitlePolicyTest.kt`

**Interfaces:**
- Consumes: Task 3 clients、Task 5 controller。
- Produces: spec `8`、`13`、`14` 的可复用领域行为，不在 Compose 中解释。

- [ ] **Step 1: 写命令透传与未知命令 RED**

```kotlin
@Test
fun unknownSlashCommandIsSentVerbatimAndNewWaitsForStructuredResult() = runTest {
    val controller = controllerWithCatalog(commands = emptyList())
    controller.dispatch(ConversationIntent.EditDraft("/unknown x"))
    controller.dispatch(ConversationIntent.Send)
    assertEquals("/unknown x", repository.lastSubmittedText)

    controller.dispatch(ConversationIntent.EditDraft("/new"))
    controller.dispatch(ConversationIntent.Send)
    assertNull(controller.state.value.activeConversationId)
    repository.emit(commandCreatedConversation("conv_real"))
    assertEquals(ConversationId("conv_real"), controller.state.value.activeConversationId)
}
```

`CommandCoordinatorTest.kt` 在 class 内显式持有 fixture：

```kotlin
private lateinit var repository: InMemoryConversationRepository
private fun controllerWithCatalog(commands: List<AgentCommand>): ConversationController {
    repository = InMemoryConversationRepository()
    return DefaultConversationController(
        conversations = repository,
        commands = InMemoryCommandCatalogRepository(commands),
        attachments = InMemoryAttachmentDraftCoordinator(),
        mirrors = InMemoryConversationMirrorStore(),
        clock = TestConversationClock(),
    )
}
private fun commandCreatedConversation(id: String) =
    VerifiedConversationEvent.CommandResult.createdConversation(id)
```

- [ ] **Step 2: 写批次边界 RED**

```kotlin
@Test
fun sealsAtTwentyMembersAndNeverCrossesScopeOrAttachments() = runTest {
    val batcher = DebounceBatcher(policy = DebouncePolicy(delay = 1.5.seconds))
    repeat(20) { batcher.offer(textAtom("m$it", scopeA)) }
    assertEquals(20, batcher.sealed.single().members.size)
    assertTrue(batcher.active.isEmpty())
    batcher.offer(attachmentAtom(scopeA))
    batcher.offer(textAtom("next", scopeB))
    assertEquals(2, batcher.immediateOrNewBatchCount)
}
```

`textAtom` 与 `attachmentAtom` 在 `DebounceBatcherTest.kt` 分别构造 `LocalSendAtom.Text`/`LocalSendAtom.Attachment`，使用递增的固定 `ClientMessageId`；不得调用系统时钟或 UUID。

- [ ] **Step 3: 运行 RED**

Run: `:conversation-domain:testDebugUnitTest --tests '*CommandCoordinatorTest*' --tests '*DebounceBatcherTest*'`  
Expected: FAIL，缺少 coordinator/batcher。

- [ ] **Step 4: 实现 DebouncePolicy**

```kotlin
data class DebouncePolicy(
    val delay: Duration = 1.5.seconds,
    val extendOnNewMessage: Boolean = true,
    val maximumWait: Duration = 30.seconds,
    val maximumMembers: Int = 20,
    val maximumBytes: Long,
) {
    init {
        require(delay in Duration.ZERO..10.seconds)
        require(maximumWait == 30.seconds)
        require(maximumMembers == 20)
        require(maximumBytes > 0)
    }
}
```

计时器使用注入的 `TimeSource`/test scheduler；设置变化只影响新批次。命令、附件、音频、屏幕选区、scope 变化都是硬边界。

- [ ] **Step 5: 实现 GenerationQueue 与 cancel**

每个 conversation 同时最多一个 RUNNING；后续 batch FIFO。`CancelGeneration` 先变 `CANCEL_REQUESTED`，调用原 request ID 重试；`CANCELLED`/`ALREADY_COMPLETED`/`UNSUPPORTED`/`OUTCOME_UNKNOWN` 分别投影，不在本地拼“已停止”消息。

- [ ] **Step 6: 实现标题策略**

`ConversationTitlePolicy` 按第一发送单元第一段非空文本、48 Unicode grapheme clusters、附件文件名、`语音消息`、`屏幕选区` 生成临时标题；`/new` 不作标题。使用 `BreakIterator.getCharacterInstance(locale)`，用户重命名后拒绝 Agent 覆盖。

- [ ] **Step 7: 运行 GREEN 与完整领域回归**

Run: `:conversation-domain:testDebugUnitTest`  
Expected: virtual-time 测试无需等待真实 1.5/30 秒；newline bytes 与 Task 1 vector 相同。

- [ ] **Step 8: 审查并提交**

```bash
git add apps/android/conversation-domain
git commit -m "新增: 实现命令批次与生成取消"
```

### Task 7: 实现附件准备、门控与恰好一次提交

**Files:**
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/attachment/SubmissionSnapshot.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/attachment/DefaultAttachmentDraftCoordinator.kt`
- Create: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/attachment/AttachmentSubmissionGate.kt`
- Modify: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/DefaultConversationController.kt`
- Create: `apps/android/conversation-domain/src/test/kotlin/com/openandroidintelligence/conversation/attachment/AttachmentSubmissionGateTest.kt`
- Create: `apps/android/conversation-domain/src/test/kotlin/com/openandroidintelligence/conversation/attachment/AttachmentLimitsTest.kt`
- Modify: `apps/android/artifact-ports/src/main/kotlin/com/openandroidintelligence/artifact/ArtifactSelectionPorts.kt`
- Modify: `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/attachments/AttachmentUploader.kt`
- Modify: `apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/attachments/AttachmentUploaderTest.kt`

**Interfaces:**
- Consumes: `ArtifactSelectionPort`、`ArtifactDigestPort`、Task 3 `AttachmentStatusClient`、Task 4 `AttachmentDraftCoordinator`。
- Produces: `PendingSubmissionIntent`、稳定请求身份、冻结附件快照、重试/取消和 exactly-once message acceptance。

- [ ] **Step 1: 写并发完成只发送一次 RED**

```kotlin
@Test
fun simultaneousVerificationCallbacksSubmitExactlyOnce() = runTest {
    val sender = RecordingMessageSender()
    val gate = AttachmentSubmissionGate(sender, idSource)
    val intent = gate.arm(
        text = "",
        draftRevision = 7,
        attachments = listOf(snapshot("a"), snapshot("b")),
    )

    coroutineScope {
        launch { gate.onAttachmentState(intent.id, "a", AttachmentState.VERIFIED) }
        launch { gate.onAttachmentState(intent.id, "b", AttachmentState.VERIFIED) }
    }

    assertEquals(1, sender.submissions.size)
    assertEquals(intent.clientMessageId, sender.submissions.single().clientMessageId)
}
```

- [ ] **Step 2: 写编辑使旧意图失效 RED**

```kotlin
@Test
fun editingDraftInvalidatesArmedIntentWithoutPartialSend() = runTest {
    val sender = RecordingMessageSender()
    val gate = AttachmentSubmissionGate(sender, idSource)
    val intent = gate.arm("正文", 3, listOf(snapshot("a")))
    gate.invalidate(intent.id, InvalidationReason.DRAFT_EDITED)
    gate.onAttachmentState(intent.id, "a", AttachmentState.VERIFIED)
    assertEquals(PendingIntentState.INVALIDATED, gate.state(intent.id))
    assertTrue(sender.submissions.isEmpty())
}
```

- [ ] **Step 3: 运行 RED**

Run: `:conversation-domain:testDebugUnitTest --tests '*AttachmentSubmissionGateTest*'`  
Expected: FAIL，缺少 gate/snapshot。

- [ ] **Step 4: 定义冻结模型**

```kotlin
data class AttachmentSnapshot(
    val draftId: AttachmentDraftId,
    val clientAttachmentId: String,
    val filename: String,
    val mediaType: ArtifactMediaType,
    val sizeBytes: Long,
    val sha256: String,
    val remoteAttachmentId: String?,
    val state: AttachmentState,
)

data class PendingSubmissionIntent(
    val id: SubmitIntentId,
    val clientMessageId: ClientMessageId,
    val draftRevision: Long,
    val text: String,
    val attachments: List<AttachmentSnapshot>,
    val requestIds: SubmissionRequestIds,
    val state: PendingIntentState,
)

enum class PendingIntentState { ARMED, SUBMITTING, ACCEPTED, INVALIDATED, FAILED }
enum class InvalidationReason { DRAFT_EDITED, ATTACHMENT_ADDED, ATTACHMENT_REMOVED, CANCELLED }
```

`SubmissionRequestIds` 分别固定 create/upload/commit/query/message request ID；重试不重新生成逻辑 ID。

`AttachmentSubmissionGateTest.kt` 在同一文件定义：

```kotlin
private val idSource = FixedSubmissionIdSource()
private fun snapshot(id: String) = AttachmentSnapshot(
    draftId = AttachmentDraftId("draft_$id"),
    clientAttachmentId = "client_$id",
    filename = "$id.txt",
    mediaType = ArtifactMediaType.TEXT_PLAIN,
    sizeBytes = 1,
    sha256 = "00".repeat(32),
    remoteAttachmentId = "att_$id",
    state = AttachmentState.UPLOADING,
)
private class RecordingMessageSender : ArmedMessageSender {
    val submissions = mutableListOf<PendingSubmissionIntent>()
    override suspend fun submit(intent: PendingSubmissionIntent) {
        submissions += intent
    }
}
```

- [ ] **Step 5: 强制本机与协商限制交集**

选择器只接受 `ArtifactMediaType` 闭集；数量 `<=4`、单文件 `<= min(25 MiB, negotiated max)`、消息总量 `<= min(50 MiB, negotiated max)`；AAC/M4A `<=10 MiB` 且 `<=120000ms`。读取 exact bytes 后计算摘要，不信任 provider 文件名、MIME 或 size 元数据。

- [ ] **Step 6: 实现三步上传与 outcome unknown 恢复**

状态顺序固定：

```text
LOCAL_PREPARING -> CREATE_PENDING -> UPLOADING -> VERIFYING -> VERIFIED
                                \-> RETRYABLE_FAILURE | TERMINAL_FAILURE | OUTCOME_UNKNOWN
```

网络响应丢失时使用原 request ID 查询附件/消息状态；明确 failed/expired 的手动重试创建新远端尝试；不能把本地重试显示为已提交。

- [ ] **Step 7: 实现 gate 原子切换**

只有当前 intent、revision 未变且全部快照 VERIFIED 时，互斥区内从 `ARMED` 原子切换 `SUBMITTING`；只有赢得切换的协程调用 repository。空正文+至少一个 verified 附件允许；空正文+空附件拒绝。

- [ ] **Step 8: 运行 GREEN 与回归**

Run:

```bash
:artifact-ports:testDebugUnitTest \
:gateway-client:testDebugUnitTest \
:conversation-domain:testDebugUnitTest
```

Expected: 重复点击、同时完成、失败后重试、SSE replay、进程恢复和 intent invalidation 都不产生第二次 submit。

- [ ] **Step 9: 审查并提交**

```bash
git add apps/android/artifact-ports apps/android/gateway-client apps/android/conversation-domain
git commit -m "新增: 实现附件门控恰好一次提交"
```

### Task 8: 实现加密对话镜像与独立媒体缓存

**Files:**
- Create: `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/conversation/ConversationMirrorDatabase.kt`
- Create: `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/conversation/AndroidConversationMirrorStore.kt`
- Create: `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/conversation/MirrorPayloadCipher.kt`
- Create: `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/conversation/MirrorScopePaths.kt`
- Create: `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/media/LocalMediaCacheStore.kt`
- Create: `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/media/AndroidLocalMediaCacheStore.kt`
- Create: `apps/android/encrypted-store/src/test/kotlin/com/openandroidintelligence/encrypted/store/conversation/MirrorScopePathsTest.kt`
- Create: `apps/android/encrypted-store/src/androidTest/kotlin/com/openandroidintelligence/encrypted/store/conversation/AndroidConversationMirrorStoreInstrumentedTest.kt`
- Create: `apps/android/encrypted-store/src/androidTest/kotlin/com/openandroidintelligence/encrypted/store/media/AndroidLocalMediaCacheStoreInstrumentedTest.kt`
- Modify: `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/ports/ConversationPorts.kt`

**Interfaces:**
- Consumes: Task 4 `ConversationMirrorStore`。
- Produces: 按 local profile × Gateway account × installation 隔离的完整加密 mirror；媒体 cache 与 attachment staging 明确分离。

- [ ] **Step 1: 写跨 scope 与 tombstone RED**

```kotlin
@Test
fun oneScopeCannotOpenAnotherScopeAndTombstoneErasesPlaintext() = runTest {
    val fixture = MirrorStoreFixture.create(instrumentationContext)
    val store = fixture.store
    val a = store.open(scopeA)
    a.upsert(message("msg_1", "秘密正文", revision = 1))
    a.tombstone("msg_1", revision = 2)

    assertNull(store.open(scopeB).findMessage("msg_1"))
    val tombstone = a.findMessage("msg_1")!!
    assertTrue(tombstone.isTombstone)
    assertNull(tombstone.text)
    assertFalse(fixture.databaseBytes(scopeA).decodeToString().contains("秘密正文"))
}
```

`AndroidConversationMirrorStoreInstrumentedTest.kt` 的 `MirrorStoreFixture` 接收 instrumentation 专属 root 和 deterministic test key provider，仅在 test source set 暴露 `databaseBytes`；`scopeA`/`scopeB` 是不同 account/gateway 的固定 `MirrorScope`，`message` 构造 revisioned test record。生产 `ConversationMirrorStore` 不暴露路径或密文字节。

- [ ] **Step 2: 运行 RED**

Run: `:encrypted-store:connectedDebugAndroidTest`  
Expected: 当前无设备记录 BLOCKED；设备到位后先得到“store 不存在”的 RED，不以 BLOCKED 冒充 RED。

- [ ] **Step 3: 定义隔离路径与 key alias**

`MirrorScopePaths` 只接受经 SHA-256 的 scope key：

```kotlin
data class MirrorScopeKey(val sha256: String) {
    init { require(sha256.matches(Regex("^[0-9a-f]{64}$"))) }
}
```

数据库路径为 `noBackupFilesDir/conversation-mirror/<scopeHash>/mirror-v1.db`，Keystore alias 为 `open_android_intelligence_mirror_v1_<scopeHash>`；interface 不暴露真实路径。

- [ ] **Step 4: 用平台 SQLite + AES-GCM 实现**

SQLite 只保存 scope 内随机 row ID、resource type、SHA-256 lookup key、revision、sort key、tombstone flag 和密文 payload。AAD 精确为：

```text
open-android-intelligence:mirror:v1:<scopeHash>:<resourceType>:<resourceIdHash>:<revision>
```

密文认证失败、未知 format、重复 revision 回退、trailing bytes 全部 fail closed 为 `MirrorCorrupted`。

- [ ] **Step 5: 实现原子 snapshot/cursor 更新**

snapshot revision、SSE cursor 和所有 upsert/tombstone 必须在同一 SQLite transaction 提交；崩溃后要么看到旧完整 snapshot，要么看到新完整 snapshot，不出现 cursor 超前。

- [ ] **Step 6: 实现 logout/remove/unpair 语义**

- logout：锁定 mirror，保留密文。
- local account removal：只清当前手机该 scope 的 mirror、staging、media cache 和 key。
- unpair：清目标 pairing scope，并让 UI 进入 `PAIRING_REVOKED`。
- Gateway account deletion：由服务端确认后清全部本地关联 scope。

实现代码只能操作专属目录，不能遍历/删除 `context.cacheDir`。

- [ ] **Step 7: 实现 LocalMediaCacheStore**

```kotlin
interface LocalMediaCacheStore {
    suspend fun usage(scope: MirrorScope): MediaCacheUsage
    suspend fun cache(grant: MediaCacheGrant, source: ByteSource): CachedMedia
    suspend fun clear(scope: MirrorScope): MediaCacheClearResult
}
```

只有用户点击“保留离线副本”后的短期单用途 grant 可写入；达配额失败并提示“只清媒体”，不自动淘汰，不读取 Gateway 暂存区长期内容。

- [ ] **Step 8: 运行 GREEN**

Run:

```bash
:encrypted-store:testDebugUnitTest \
:encrypted-store:connectedDebugAndroidTest \
:conversation-domain:testDebugUnitTest
```

Expected: 单测 PASS；真机无设备则保持 BLOCKED。额外检查 `run-as com.openandroidintelligence.mobile grep` 找不到明文只在 debug 测试 APK/隔离 fixture 范围执行。

- [ ] **Step 9: 审查并提交**

```bash
git add apps/android/encrypted-store apps/android/conversation-domain
git commit -m "新增: 实现加密对话镜像与媒体缓存"
```

### Task 9: 建立进程感知 AppGraph 与真实 adapter 注入

**Files:**
- Modify: `apps/android/app/build.gradle.kts`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/process/AppProcessRole.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/di/AppGraph.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/di/MainProcessGraph.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/di/AssistantProcessGraph.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModelFactory.kt`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt`
- Create: `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/process/AppProcessRoleTest.kt`
- Create: `apps/android/app/src/androidTest/kotlin/com/openandroidintelligence/mobile/ProcessAwareApplicationInstrumentedTest.kt`

**Interfaces:**
- Consumes: Tasks 3–8。
- Produces: MAIN 进程真实 controller/kernel/network/mirror graph；keeper/session 进程只有窄依赖；ViewModel 仅将 controller StateFlow 暴露给 Compose。

- [ ] **Step 1: 写进程隔离 RED**

```kotlin
@Test
fun assistantProcessesNeverCreateKernelOrGatewayClient() {
    assertEquals(AppProcessRole.MAIN, classify("com.openandroidintelligence.mobile"))
    assertEquals(AppProcessRole.ASSISTANT_KEEPER, classify("com.openandroidintelligence.mobile:assistant-keeper"))
    assertEquals(AppProcessRole.ASSISTANT_SESSION, classify("com.openandroidintelligence.mobile:assistant-session"))
}
```

仪表化 test graph 用 counters 断言 keeper/session 的 `kernelCreated == 0`、`gatewayClientCreated == 0`。

- [ ] **Step 2: 运行 RED**

Run: `:app:testFullDebugUnitTest --tests '*AppProcessRoleTest*'`  
Expected: FAIL，类型不存在。

- [ ] **Step 3: 实现 fail-closed classifier**

```kotlin
enum class AppProcessRole { MAIN, ASSISTANT_KEEPER, ASSISTANT_SESSION, UNKNOWN }

fun classifyProcess(packageName: String, processName: String): AppProcessRole = when (processName) {
    packageName -> AppProcessRole.MAIN
    "$packageName:assistant-keeper" -> AppProcessRole.ASSISTANT_KEEPER
    "$packageName:assistant-session" -> AppProcessRole.ASSISTANT_SESSION
    else -> AppProcessRole.UNKNOWN
}
```

UNKNOWN 不初始化 Kernel、凭据或 Gateway。

- [ ] **Step 4: 构造 MainProcessGraph**

Main graph 注入 `AccountProfileStore`、`GatewaySessionManager`、`GatewayHttpClient` factory、四个 conversation ports、`PluginKernel`、mirror/media adapters 和 `DefaultConversationController`。构造顺序中任何安全依赖失败都阻止 UI 写操作，只允许明确恢复界面。

- [ ] **Step 5: 保持 ViewModel 浅且无第二状态源**

```kotlin
class ConversationViewModel(
    private val controller: ConversationController,
) : ViewModel() {
    val state: StateFlow<ConversationSessionState> = controller.state
    fun dispatch(intent: ConversationIntent) {
        viewModelScope.launch { controller.dispatch(intent) }
    }
}
```

禁止在 ViewModel 生成 ID、读文件、解析命令、计时、模拟 generation 或持有 assistant 副本。

- [ ] **Step 6: 修改 Application**

`onCreate` 根据 `Application.getProcessName()` 只创建对应 graph。`assistant-keeper` 只保留角色服务需要的轻量对象；`assistant-session` 只创建 IPC client/UI dependencies；Kernel 和审计数据库只在 MAIN。

- [ ] **Step 7: 运行 GREEN**

Run:

```bash
:app:testFullDebugUnitTest \
:app:testPlayDebugUnitTest \
:app:connectedFullDebugAndroidTest
```

Expected: 两 flavor JVM PASS；真机进程测试无设备则 BLOCKED。

- [ ] **Step 8: 审查并提交**

```bash
git add apps/android/app
git commit -m "重构: 建立进程感知应用组合根"
```

### Task 10: 建立 conversation-ui、设计系统令牌与全局 MotionPolicy

**Files:**
- Modify: `apps/android/settings.gradle.kts`
- Create: `apps/android/conversation-ui/build.gradle.kts`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/ColorTokens.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/TypeTokens.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/ShapeTokens.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/SpacingTokens.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/MotionPolicy.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/MotionTokens.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/PressFeedback.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/SignalStitch.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/OpenAndroidIntelligenceTheme.kt`
- Create: `apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/ui/design/DesignTokenTest.kt`
- Create: `apps/android/conversation-ui/src/androidTest/kotlin/com/openandroidintelligence/ui/design/ThemeSemanticsTest.kt`

**Interfaces:**
- Consumes: Task 4 immutable domain models。
- Produces: stateless Compose design module；App 与 assistant-holder 都依赖它，不互相依赖。

- [ ] **Step 1: 写 token RED**

```kotlin
@Test
fun brandTokensMatchAcceptedSpecAndSpacingExceptionsAreClosed() {
    assertEquals(0xFFF1F4F1, LightTokens.canvas.value)
    assertEquals(0xFF101613, DarkTokens.canvas.value)
    assertEquals(48.dp, Dimensions.minimumTouchTarget)
    assertEquals(56.dp, Dimensions.dockedBall)
    assertEquals(setOf(1.dp, 2.5.dp), Dimensions.nonGridStrokeExceptions)
}
```

- [ ] **Step 2: 运行 RED**

Run: `:conversation-ui:testDebugUnitTest`  
Expected: FAIL，模块/令牌不存在。

- [ ] **Step 3: 实现完整 ColorScheme 映射**

映射 primary/onPrimary/primaryContainer/onPrimaryContainer、secondary、error、background、surface、surfaceVariant、outline 和 inverse slots；所有 on-color 必须经浅/深主题对比测试。继续使用不可变 `staticCompositionLocalOf`，不为误报引入额外读取跟踪。

- [ ] **Step 4: 实现排版和网格**

正文 16sp/24sp line height；标题 18–20sp；大标题 <=24sp；metadata 12sp Roboto Mono。display tracking 使用相对 `(-0.02).em`，不使用固定高度裁切。间距只从 `Space4/8/12/16/20/24` 取值，尺寸例外集中在 `Dimensions` 并写理由。

- [ ] **Step 5: 定义 MotionPolicy**

```kotlin
data class MotionPolicy(
    val reduceMotion: Boolean,
    val durationScale: Float,
)

interface MotionPreferenceSource {
    val policy: StateFlow<MotionPolicy>
}
```

Android adapter 读取 `ValueAnimator.areAnimatorsEnabled()` 并在 lifecycle resume 刷新；测试注入 policy。Compose 自带 `MotionDurationScale` 继续生效，但业务组件仍显式选择 normal/reduced 分支。

- [ ] **Step 6: 定义 exact motion tokens**

- press：scale 0.98，90ms，pointer-down 第一帧，不消费 click/scroll。
- default Morph：critical damping 1.0，response 0.34s。
- dock snap：damping 0.82，response 0.32s，传入 initial velocity。
- message enter：180ms，4dp + opacity，强 ease-out。
- reduced：150ms Crossfade，无位移、过冲、循环。

Compose spring 用测试校准的 stiffness/damping 映射；禁止把 response 秒数误写成固定 duration。

- [ ] **Step 7: 实现 PressFeedback 与 Signal Stitch**

Press observer 使用不消费事件的 pointer input/Modifier.Node；保留 Material semantics 和 click target。Signal Stitch 默认静止；thinking 只改变可访问状态/有限进度，completed 只播放一次克制 pulse，error 变 error 色并静止；reduced 分支无循环。

- [ ] **Step 8: 运行 GREEN 与 UI 测试**

Run:

```bash
:conversation-ui:testDebugUnitTest \
:conversation-ui:connectedDebugAndroidTest
```

UI test 切换浅/深、fontScale 1.0/1.3/2.0、reduceMotion true/false；无设备时 instrumentation 保持 BLOCKED。

- [ ] **Step 9: 审查并提交**

```bash
git add apps/android/settings.gradle.kts apps/android/conversation-ui
git commit -m "新增: 建立设计系统设计与动效系统"
```

### Task 11: 原子替换旧 Presenter UI，建立官方导航与自适应 Shell

**Files:**
- Modify: `apps/android/build.gradle.kts`
- Modify: `apps/android/app/build.gradle.kts`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/navigation/AppDestination.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/navigation/OpenAndroidIntelligenceNavHost.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/shell/OpenAndroidIntelligenceApp.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/shell/AdaptiveConversationShell.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/shell/GatewayPane.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/shell/ConversationListPane.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/gateway/GatewayPicker.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/media/MediaCacheScreen.kt`
- Create: `apps/android/app/src/main/res/values/themes.xml`
- Create: `apps/android/app/src/main/res/values-night/themes.xml`
- Create: `apps/android/app/src/androidTest/kotlin/com/openandroidintelligence/mobile/navigation/NavigationAndAdaptiveLayoutTest.kt`
- Delete safely: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayScreen.kt`
- Delete safely: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationScreen.kt`
- Delete safely: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/AttachmentPicker.kt`
- Delete safely: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/PlatformSettingsScreen.kt`
- Replace tests: `apps/android/app/src/androidTest/kotlin/com/openandroidintelligence/mobile/CoreWithoutPluginsInstrumentedTest.kt`
- Replace tests: `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ArchitectureBoundaryTest.kt`

**Interfaces:**
- Consumes: Task 9 graph、Task 10 theme/UI。
- Produces: Navigation Compose back stack、compact/medium/expanded Shell、真实 Gateway/附件/设置 routes；旧双架构同一提交消失。

- [ ] **Step 1: 写导航与 adaptive RED**

```kotlin
@Test
fun widthsSelectTheAcceptedPaneCount() {
    assertEquals(PaneMode.COMPACT, paneModeFor(599.dp))
    assertEquals(PaneMode.MEDIUM, paneModeFor(600.dp))
    assertEquals(PaneMode.EXPANDED, paneModeFor(840.dp))
}
```

Compose test 从 Settings 返回必须回到原 conversation，不退出 Activity；3 个 Gateway 全部可选择；“附件与媒体缓存”必须进入真实 route。

- [ ] **Step 2: 运行 RED**

Run: `:app:testFullDebugUnitTest :app:connectedFullDebugAndroidTest`  
Expected: pane function/navigation nodes 不存在；无设备时 instrumentation 单列 BLOCKED。

- [ ] **Step 3: 引入官方 typed Navigation Compose**

根插件增加 Kotlin serialization `2.1.20`，依赖固定 `navigation-compose:2.8.5`。routes：

```kotlin
@Serializable data object ConversationHome
@Serializable data class ConversationThread(val conversationId: String)
@Serializable data object GatewayManagement
@Serializable data object AttachmentAndMedia
@Serializable data object PlatformSettings
@Serializable data object PluginManagement
```

不再以裸 String 保存 current screen。forward/pop 使用 `Direction-aware transition`，返回镜像路径；Manifest 显式 `android:enableOnBackInvokedCallback="true"`。

- [ ] **Step 4: 实现 edge-to-edge 与启动主题**

`MainActivity : ComponentActivity` 在 `super.onCreate` 前后按 Activity API 调用 `enableEdgeToEdge()`；Scaffold 消费 safe drawing/IME insets。Manifest application/activity 使用 `Theme.OpenAndroidIntelligence`，window background 与 Canvas token 匹配，禁止冷启动闪白。

- [ ] **Step 5: 实现 adaptive Shell**

- compact <600dp：ModalNavigationDrawer + 单 conversation。
- medium 600–839dp：conversation list + current conversation。
- expanded >=840dp：Gateway rail + conversation list + current conversation。
- 正文 `widthIn(max=680.dp)`；assistant 另行处理，不复制三栏。

窗口分类来自 `material3-window-size-class`，集中在 root；子 Composable 只接收 `PaneMode`。

- [ ] **Step 6: 实现真实 Gateway picker 与空状态**

Gateway picker 显示所有 profile，切换调用 controller scope；无 Gateway 显示“添加或登录 Gateway”，不显示“离线镜像”。连接状态来自 session/sync state，未认证不能显示 online。

- [ ] **Step 7: 原子移出旧文件**

```bash
mkdir -p /tmp/Open Android Intelligence-trash/task-11-legacy-presenters
mv apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayScreen.kt \
   apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationScreen.kt \
   apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/AttachmentPicker.kt \
   apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/PlatformSettingsScreen.kt \
   /tmp/Open Android Intelligence-trash/task-11-legacy-presenters/
```

同时替换所有调用与旧 Presenter 测试；不得留下 compatibility wrapper。

- [ ] **Step 8: 运行 GREEN 与边界门禁**

Run:

```bash
:app:testFullDebugUnitTest \
:app:testPlayDebugUnitTest \
:app:assembleFullDebug \
:app:assemblePlayDebug \
check
```

Expected: 两 flavor PASS；`rg 'GatewayPresenter|ConversationPresenter|AttachmentPresenter|currentScreen = "' apps/android/app/src` 无结果。

- [ ] **Step 9: 独立 review 并提交**

```bash
git add apps/android/build.gradle.kts apps/android/app
git commit -m "重构: 建立自适应主应用导航"
```

### Task 12: 实现真实时间线、Markdown、工具结果、tombstone 与滚动所有权

**Files:**
- Modify: `apps/android/conversation-ui/build.gradle.kts`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/timeline/ConversationTimeline.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/timeline/TimelineItem.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/timeline/TimelineFollowPolicy.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/message/AssistantMessage.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/message/UserMessage.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/message/MarkdownDocument.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/message/CodeBlock.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/tool/ToolResultCard.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/tombstone/DeletedTombstone.kt`
- Create: `apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/ui/timeline/TimelineFollowPolicyTest.kt`
- Create: `apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/ui/message/MarkdownDocumentTest.kt`
- Create: `apps/android/conversation-ui/src/androidTest/kotlin/com/openandroidintelligence/ui/timeline/ConversationTimelineTest.kt`

**Interfaces:**
- Consumes: one immutable `ConversationSessionState`。
- Produces: stateless timeline；滚动位置属于 UI state，但 remote events 不得强夺用户控制。

- [ ] **Step 1: 写滚动策略 RED**

```kotlin
@Test
fun userScrollDisablesFollowUntilExplicitReturnToBottom() {
    var policy = TimelineFollowPolicy.initiallyFollowing()
    policy = policy.onUserScroll(isAtBottom = false)
    assertFalse(policy.shouldFollowStreamingDelta)
    policy = policy.onNewDelta(unreadIncrement = 3)
    assertEquals(3, policy.unreadCount)
    policy = policy.onReturnToBottom()
    assertTrue(policy.shouldFollowStreamingDelta)
}
```

- [ ] **Step 2: 写 Markdown/tombstone RED**

```kotlin
@Test
fun parsesCodeAndNeverRendersDeletedPlaintext() {
    val document = MarkdownDocument.parse("正文\n\n```kotlin\nval x = 1\n```")
    assertTrue(document.blocks.any { it is MarkdownBlock.FencedCode })
    assertNull(TimelineItem.Tombstone("msg_1", revision = 2).plaintext)
}
```

- [ ] **Step 3: 运行 RED**

Run: `:conversation-ui:testDebugUnitTest --tests '*TimelineFollowPolicyTest*' --tests '*MarkdownDocumentTest*'`

- [ ] **Step 4: 实现安全 Markdown**

使用 `org.commonmark:commonmark:0.30.0` 解析 AST；只渲染 paragraph、heading、list、quote、emphasis、strong、link、inline code、fenced code 和 thematic break。原始 HTML 不执行、不交给 WebView；未知/过深节点降级为纯文本。外链点击先显示目标并走系统安全 Intent。

`CodeBlock` 使用 Roboto Mono、language label、横向滚动和复制按钮；`prism4j:2.0.0` 只为明确支持的 Kotlin/Java/JSON/Python/Bash/JavaScript/TypeScript/Rust grammar 产生 `AnnotatedString` spans，未知 language 保持等宽纯文本。

- [ ] **Step 5: 实现消息与工具模型**

`ToolExecutionStatus` 使用 enum `QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/OUTCOME_UNKNOWN`；duration 为空时不显示，不再默认 `SUCCESS/240ms`。RUNNING 使用标准进度语义；reduced motion 静态图标+文字。

- [ ] **Step 6: 实现滚动与入场**

只有用户实际位于底部时，流式 delta 使用合并后的 `scrollToItem`，不对每 token 启动 `animateScrollToItem`。用户上滑立即停止；“回到底部”显示未读数。新 timeline item 使用 180ms、4dp+opacity；流式文本增长不重复入场；reduced 只 fade。

- [ ] **Step 7: 性能裁定 ToolResult 展开**

先用 Compose tracing 比较平台 `AnimatedVisibility(expandVertically)`、`animateContentSize` 和裁剪 layer；只有存在可复现 dropped frame/双测量问题才换实现。计划禁止把 Web “GPU only”规则当作无证据结论。选定实现写在 Task 12 review 中。

- [ ] **Step 8: 实现复制/失败/tombstone**

复制状态 2 秒后复位并可被新点击重启；发送失败显示具体 code 与“检查发送状态/继续发送”；tombstone 只显示删除占位；`OUTCOME_UNKNOWN` 不提供“重新发送新消息”，只提供原 ID 查询。

- [ ] **Step 9: 运行 GREEN**

Run:

```bash
:conversation-ui:testDebugUnitTest \
:conversation-ui:connectedDebugAndroidTest \
:app:testFullDebugUnitTest
```

Expected: JVM PASS；无设备时 UI test BLOCKED。长 Markdown、1000 条 timeline、streaming 负载留到 Task 18 帧门禁。

- [ ] **Step 10: 审查并提交**

```bash
git add apps/android/conversation-ui apps/android/app
git commit -m "新增: 实现真实对话时间线"
```

### Task 13: 实现主 App/助理共用消息编辑器、命令、批次与附件状态

**Files:**
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/composer/SharedComposer.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/composer/ComposerUiState.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/composer/CommandCatalogMenu.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/composer/DebounceBatchIndicator.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/attachment/AttachmentDraftBar.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/attachment/AttachmentDraftChip.kt`
- Create: `apps/android/conversation-ui/src/androidTest/kotlin/com/openandroidintelligence/ui/composer/SharedComposerTest.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/attachment/AndroidArtifactSelectionAdapter.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/attachment/AndroidArtifactDigestAdapter.kt`
- Create: `apps/android/app/src/androidTest/kotlin/com/openandroidintelligence/mobile/attachment/AndroidArtifactSelectionAdapterTest.kt`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/shell/AdaptiveConversationShell.kt`

**Interfaces:**
- Consumes: Task 5–7 controller state/intents、Task 10 stateless design。
- Produces: 同一个 `SharedComposer`；主 App 和 assistant 只能通过 `ComposerUiAction` 与 controller 交互。

- [ ] **Step 1: 写 Compose semantics RED**

```kotlin
@Test
fun attachmentStillUploadingAllowsArmButDoesNotShowAccepted() {
    composeRule.setContent {
        SharedComposer(
            state = composerState(
                text = "说明",
                attachments = listOf(uploadingAttachment(progress = 0.42f)),
            ),
            onAction = actions::add,
        )
    }
    composeRule.onNodeWithContentDescription("提交消息").performClick()
    assertEquals(ComposerUiAction.Submit, actions.single())
    composeRule.onNodeWithText("等待附件验证").assertIsDisplayed()
    composeRule.onNodeWithText("已发送").assertDoesNotExist()
}
```

- [ ] **Step 2: 写 IME 与命令 RED**

```kotlin
@Test
fun imeSendUsesTheSameSubmitActionAndCatalogSelectionOnlyFillsDraft() {
    composeRule.setContent {
        SharedComposer(
            state = composerState(
                text = "hello",
                commands = listOf(command("/status")),
            ),
            onAction = actions::add,
        )
    }
    composeRule.onNodeWithTag("composer-input").performImeAction()
    assertEquals(ComposerUiAction.Submit, actions.last())
    composeRule.onNodeWithText("/status").performClick()
    assertEquals(ComposerUiAction.FillCommand("/status"), actions.last())
}
```

`SharedComposerTest.kt` 在 test class 内定义 `actions = mutableListOf<ComposerUiAction>()`，并在 `@Before` 清空。`composerState` 返回显式 `ComposerUiState`，默认 EDITING、online、空附件/命令；`uploadingAttachment` 返回 `AttachmentDraftUiModel` 的 UPLOADING 状态；`command` 返回固定 id/title/description 的 catalog item。测试之间不共享 composition 或 action state。

- [ ] **Step 3: 运行 RED**

Run: `:conversation-ui:connectedDebugAndroidTest`  
Expected: 无设备先记录 BLOCKED；设备存在时缺少 composable 的 compile RED。

- [ ] **Step 4: 实现 stateless composer**

```kotlin
sealed interface ComposerUiAction {
    data class EditText(val value: String) : ComposerUiAction
    data object Submit : ComposerUiAction
    data object StopGeneration : ComposerUiAction
    data object AddAttachment : ComposerUiAction
    data object StartScreenSelection : ComposerUiAction
    data class RemoveAttachment(val draftId: AttachmentDraftId) : ComposerUiAction
    data class RetryAttachment(val draftId: AttachmentDraftId) : ComposerUiAction
    data class FillCommand(val invocation: String) : ComposerUiAction
    data object CreateConversationCommand : ComposerUiAction
}
```

`SharedComposer` 不接 Context、URI、repository 或 ViewModel；所有主按钮 48dp，图标 20–24dp。Send/Stop 高频切换只做小于 180ms 的 Crossfade/0.95→1 scale，不使用 0.8 scale。

- [ ] **Step 5: 实现 Android picker adapter**

主 App 使用 Photo Picker 处理图片，SAF `OpenMultipleDocuments` 处理 PDF/text/audio；adapter 将 provider URI 留在私有 grant registry，只向 domain 返回 `GrantedArtifactSelection`。选择数量、MIME、size、duration、digest 任一失败时保留草稿并显示具体动作。

- [ ] **Step 6: 实现真实附件进度**

`AttachmentDraftChip` 直接显示 controller 的 `uploadProgress in 0f..1f`，VERIFYING 使用确定状态文案，VERIFIED 使用茶金点，失败提供 retry/remove。文件名使用 `weight(1f) + widthIn(max=...)`，不固定 90dp；remove hit target 48dp。

- [ ] **Step 7: 实现命令目录和离线行为**

目录按 active Gateway + language 读取；离线目录显示“可能已过期”，普通 command 点击只填入；顶部“新建对话”发原样 `/new`。未列命令仍可输入/发送。离线时发送不可用，不排队自动发送。

- [ ] **Step 8: 实现连续批次进度**

domain 暴露 firstAt/lastAt/deadline；UI 以单一 frame clock 线性插值剩余比例，不每 100ms 重启 tween。成员保持独立身份/顺序；progress 只装饰，不能阻止用户交互。

- [ ] **Step 9: 运行 GREEN 与回归**

Run:

```bash
:artifact-ports:testDebugUnitTest \
:conversation-domain:testDebugUnitTest \
:conversation-ui:testDebugUnitTest \
:conversation-ui:connectedDebugAndroidTest \
:app:testFullDebugUnitTest \
:app:connectedFullDebugAndroidTest
```

Expected: JVM PASS；instrumentation 无设备则 BLOCKED。

- [ ] **Step 10: 审查并提交**

```bash
git add apps/android/conversation-ui apps/android/app
git commit -m "新增: 实现共享消息编辑器"
```

### Task 14: 将默认助理迁入同 APK 私有进程并建立窄 IPC

**Files:**
- Modify: `apps/android/assistant-holder/build.gradle.kts`
- Modify: `apps/android/assistant-holder/src/main/AndroidManifest.xml`
- Modify: `apps/android/assistant-holder/src/main/res/xml/voice_interaction_service.xml`
- Modify: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantVoiceService.kt`
- Modify: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionService.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSession.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/bridge/AssistantBridgeClient.kt`
- Create: `apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/AssistantBridgeProtocol.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/assistant/AssistantConversationBridgeService.kt`
- Modify: `apps/android/app/build.gradle.kts`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Create: `apps/android/assistant-holder/src/test/kotlin/com/openandroidintelligence/assistant/bridge/AssistantBridgeProtocolTest.kt`
- Create: `apps/android/assistant-holder/src/androidTest/kotlin/com/openandroidintelligence/assistant/AssistantSessionLifecycleInstrumentedTest.kt`
- Create: `apps/android/app/src/androidTest/kotlin/com/openandroidintelligence/mobile/assistant/AssistantBridgeIsolationInstrumentedTest.kt`

**Interfaces:**
- Consumes: Task 9 graph、Task 10/13 UI。
- Produces: 同 `applicationId=com.openandroidintelligence.mobile`、同 UID、`:assistant-keeper` 和 `:assistant-session`；MAIN 进程 controller 仍是唯一状态权威。

- [ ] **Step 1: 写 manifest/process RED**

```kotlin
@Test
fun servicesAreInTheMainPackageButSeparatePrivateProcesses() {
    val manifest = mergedManifest("fullDebug")
    assertService(
        manifest,
        "com.openandroidintelligence.assistant.AssistantVoiceService",
        process = ":assistant-keeper",
        exported = true,
        permission = "android.permission.BIND_VOICE_INTERACTION",
    )
    assertService(
        manifest,
        "com.openandroidintelligence.assistant.AssistantSessionService",
        process = ":assistant-session",
        exported = false,
        permission = "android.permission.BIND_VOICE_INTERACTION",
    )
}
```

- [ ] **Step 2: 运行 RED**

Run: `:app:testFullDebugUnitTest --tests '*Assistant*'`  
Expected: 当前 assistant-holder 是独立 application，merged manifest 不满足。

- [ ] **Step 3: 将 assistant-holder 改为 Android library**

使用 `com.android.library`，删除独立 `applicationId` 和 launcher/ASSIST Activity；`app` 增加 `implementation(project(":assistant-holder"))`。最终只有一个 APK/UID；library manifest 贡献两个 service。

- [ ] **Step 4: 定义版本化 Messenger protocol**

```kotlin
object AssistantBridgeProtocol {
    const val VERSION = 1
    const val MSG_SNAPSHOT = 1
    const val MSG_DISPATCH = 2
    const val MSG_SUBSCRIBE = 3
    const val MAX_PAYLOAD_BYTES = 256 * 1024
}
```

每个 Bundle 必须只含白名单 key、`version=1`、opaque Gateway/conversation IDs 和 UI 所需投影；禁止凭据、provider URI、文件路径、`agentSessionId` 和可变身份覆盖。未知 message/key/version、过大 payload 全部拒绝。

- [ ] **Step 5: 实现 MAIN bridge service**

`AssistantConversationBridgeService` `exported=false`、默认 MAIN 进程；每次请求重新经 Kernel/active account policy 验证后调用同一 `ConversationController.dispatch`。订阅只推 immutable UI snapshot，不发送秘密。

- [ ] **Step 6: 实现 lightweight keeper/session**

`AssistantVoiceService` 不初始化 UI、Gateway 或 Kernel。`AssistantSessionService` 在 `:assistant-session` 创建 `AssistantSession`；`onCreateContentView` 返回 ComposeView，`onShow` 绑定 bridge 并读取“最近明确活动 Gateway × 最近打开线程 × 当前草稿”。

- [ ] **Step 7: 实现生命周期**

- `onHandleScreenshot(Bitmap?)` 只保存在当前 `AssistantSession` 内存；null 显示“不允许共享”。
- `onHide`/`onDestroy`/Home/锁屏/撤权/进程死亡清除截图、裁剪和 dock。
- UI close 只终止 surface，不发送 cancel。
- “在 App 中打开”发显式 MainActivity Intent + conversation ID，并验证 route。

- [ ] **Step 8: 运行 GREEN 与系统证据**

Run:

```bash
:assistant-holder:testDebugUnitTest \
:app:testFullDebugUnitTest \
:app:processFullDebugMainManifest \
:assistant-holder:connectedDebugAndroidTest \
:app:connectedFullDebugAndroidTest
```

设备存在时额外：

```bash
adb shell dumpsys package com.openandroidintelligence.mobile
adb shell ps -A | rg 'com.openandroidintelligence.mobile'
```

Expected: 一个 package/UID、三个进程角色；无设备则系统角色/进程证据 BLOCKED。

- [ ] **Step 9: 审查并提交**

```bash
git add apps/android/assistant-holder apps/android/core-model apps/android/app
git commit -m "新增: 接入系统默认助理会话"
```

### Task 15: 实现助理栏↔球连续 Morph、真实停靠物理与返回语义

**Files:**
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/ui/AssistantSurface.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/ui/AssistantMorphContainer.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/ui/DockedAssistantBall.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/ui/DockPhysics.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/ui/DockSafeBounds.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/ui/DockDropTarget.kt`
- Create: `apps/android/assistant-holder/src/test/kotlin/com/openandroidintelligence/assistant/ui/DockPhysicsTest.kt`
- Create: `apps/android/assistant-holder/src/androidTest/kotlin/com/openandroidintelligence/assistant/ui/AssistantMorphInstrumentedTest.kt`
- Create: `apps/android/assistant-holder/src/androidTest/kotlin/com/openandroidintelligence/assistant/ui/DockedBallGestureInstrumentedTest.kt`

**Interfaces:**
- Consumes: Task 10 MotionPolicy、Task 13 SharedComposer、Task 14 session state。
- Produces: `Shared element transition + Morph`、`Drag + Momentum + Rubber-banding + Spring`、可中断/可反向/减少动态版本。

- [ ] **Step 1: 写物理 RED**

```kotlin
@Test
fun projectedTargetAndReleaseVelocityFeedIndependentSprings() {
    val result = DockPhysics.release(
        position = Offset(220f, 900f),
        velocity = Velocity(1400f, -300f),
        bounds = Rect(40f, 120f, 900f, 1800f),
    )
    assertEquals(900f, result.target.x)
    assertEquals(1400f, result.initialVelocity.x)
    assertEquals(-300f, result.initialVelocity.y)
}
```

- [ ] **Step 2: 写快速反向与 reduced RED**

Compose manual-clock test：展开 120ms 后立刻收起，容器第一帧从当前 bounds 开始，无跳到端点；reduceMotion=true 时 bounds 不位移，只在同位置 150ms Crossfade。

- [ ] **Step 3: 运行 RED**

Run: `:assistant-holder:testDebugUnitTest :assistant-holder:connectedDebugAndroidTest`

- [ ] **Step 4: 实现单一常驻容器**

`AssistantMorphContainer` 在 EXPANDED/DOCKED 间始终挂载容器、输入、按钮、附件层和 Signal Stitch；只改变 bounds、position、corner radius、background、shadow、clip 和内容 alpha。禁止两个互斥 `AnimatedVisibility` 重建同一对象。

normal 编排：

```text
0–120ms    dock ring inward/fade
0–480ms    container bounds/position/radius/material Morph
120–300ms  stitch/actions/text/attachments stagger 30–60ms
```

480ms 是 accepted spec 对大容器 Morph 的明确例外；高频小控件仍 <300ms。

- [ ] **Step 5: 实现可中断 presentation-value retarget**

使用 `updateTransition`/`Animatable` 从当前 presentation value retarget；手势期间停止目标动画并接管当前 x/y；X/Y 使用独立 `Animatable`，释放 `animateTo` 传各自 initial velocity。

- [ ] **Step 6: 实现安全区域和 Rubber-banding**

从 `WindowInsets.safeDrawing`、IME、当前 layout size、LocalDensity 计算 bounds；旋转、分屏、折叠、IME 变化时 clamp/retarget。四边都使用同一渐增阻力公式，不出现 `maxY+100px` 或 1080×2400/3x 假设。

- [ ] **Step 7: 实现 drop target 与语义动作**

拖动开始才显示底部 drop target；进入区域视觉/触觉同帧反馈，释放才结束 session。TalkBack 提供“展开助理”“移动到左侧”“移动到右侧”“结束助理”，无需拖动。

- [ ] **Step 8: 实现一次性完成反馈**

result 到达不自动展开；只播放一次小于 1.04 scale 的 critically damped pulse + 一次克制 haptic + 状态公告，随后静止。thinking stitch 不无限旋转；error 静止 error 色。

- [ ] **Step 9: 实现返回/关闭**

展开 Back：若 IME 开启先收 IME；否则 Morph 到 dock。dock 再收到 session Back 才终止。关闭不 cancel；停止按钮 dispatch `CancelGeneration` 并等待真实终态。

- [ ] **Step 10: 运行 GREEN 与 feel check**

机械：

```bash
:assistant-holder:testDebugUnitTest \
:assistant-holder:connectedDebugAndroidTest \
:app:connectedFullDebugAndroidTest
```

Feel check（真机）：

- 10 次快速展开/收起，逐帧无重建闪烁、文字泄露或端点跳变。
- 拖动中反向，球始终跟手；释放延续速度而非瞬移。
- 旋转/IME/分屏后球在安全边缘。
- 动画播放 10% 时内容交接顺序符合 0–120/120–300/0–480ms。
- reduceMotion 只 Crossfade，业务状态相同。

无设备时上述全部 BLOCKED。

- [ ] **Step 11: 审查并提交**

```bash
git add apps/android/assistant-holder
git commit -m "新增: 实现助理连续形变与停靠物理"
```

### Task 16: 实现受限 Assist screenshot 圈选、真实 PNG 与共享元素交接

**Files:**
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/selection/ScreenSelectionController.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/selection/SelectionPath.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/selection/ScreenCropEncoder.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/ui/ScreenSelectionOverlay.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/ui/AccessibleRectangleEditor.kt`
- Create: `apps/android/assistant-holder/src/test/kotlin/com/openandroidintelligence/assistant/selection/SelectionPathTest.kt`
- Create: `apps/android/assistant-holder/src/androidTest/kotlin/com/openandroidintelligence/assistant/selection/ScreenCropEncoderInstrumentedTest.kt`
- Create: `apps/android/assistant-holder/src/androidTest/kotlin/com/openandroidintelligence/assistant/ui/ScreenSelectionAccessibilityTest.kt`
- Modify: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSession.kt`
- Modify: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/attachment/AttachmentDraftChip.kt`

**Interfaces:**
- Consumes: Task 7 coordinator、Task 14 screenshot callback、Task 15 shared transition scope。
- Produces: full screenshot memory-only；transparent-outside PNG bytes/digest；screen attachment draft；TalkBack rectangle alternative。

- [ ] **Step 1: 写像素与多触点 RED**

```kotlin
@Test
fun cropMakesOutsideTransparentAndLocksTheFirstPointer() {
    val source = solidBitmap(width = 100, height = 100, color = Color.RED)
    val path = polygon(20, 20, 80, 80)
    val encoded = ScreenCropEncoder.encode(source, path)
    val decoded = decodePng(encoded.bytes)
    assertEquals(0, decoded.getPixel(0, 0).alpha)
    assertEquals(255, decoded.getPixel(50, 50).alpha)
    assertEquals(sha256(encoded.bytes), encoded.sha256)
    assertTrue(pointerLock(primary = 1, incoming = 2).ignoresIncoming)
}
```

- [ ] **Step 2: 运行 RED**

Run: `:assistant-holder:testDebugUnitTest :assistant-holder:connectedDebugAndroidTest`

- [ ] **Step 3: 实现 1:1 原始路径与视觉平滑**

锁定首个 PointerId，其他触点直到本手势结束都忽略。raw points 用于 mask/边界；渲染层用 quadratic Bézier 平滑，但不得改变最终选择区域。闭合阈值使用 `24.dp.toPx()`，不写 `72f`。

- [ ] **Step 4: 渲染真实 screenshot**

overlay 底层显示 `onHandleScreenshot` 的 Bitmap；null/策略阻止显示“当前内容不允许共享”。不得降级到 Accessibility、MediaProjection、后台截图或纯黑盲圈。

- [ ] **Step 5: 实现真实裁剪与即时释放**

用 Android Canvas/Path 将圈外 alpha 清零，裁到 bounding box，PNG 写入内存 buffer，再对 exact PNG bytes 计算 size/SHA-256。确认后立即回收/丢弃 full screenshot；只把 PNG staging handle 交给 Task 7。取消/Home/锁屏/session destroy/process death 同样清除。

- [ ] **Step 6: 实现 TalkBack rectangle editor**

提供上/下/左/右边界增减动作、当前坐标说明、确认/取消/重选；语义顺序和按钮 >=48dp。动态字号不遮挡确认条。

- [ ] **Step 7: 实现 Shared element transition**

crop preview 与目标 `AttachmentDraftChip` 使用同一个 draft ID key；从预览 bounds 缩入 chip，保持裁剪图身份。减少动态时在同位置 Crossfade，不执行位移。

- [ ] **Step 8: 生命周期/配置回归**

旋转、fold、分屏只在同一存活 session 内从 `ScreenSelectionController` 恢复 raw path/rectangle；process death 不恢复 screenshot 或 draft。屏幕选区永不写 mirror/media cache。

- [ ] **Step 9: 运行 GREEN 与真机检查**

Run:

```bash
:assistant-holder:testDebugUnitTest \
:assistant-holder:connectedDebugAndroidTest \
:app:connectedFullDebugAndroidTest
```

真机必须验证允许截图 App、`FLAG_SECURE` App、旋转、多点触控、TalkBack、取消/Home/锁屏、确认后上传仅为 crop PNG。无设备则 BLOCKED。

- [ ] **Step 10: 审查并提交**

```bash
git add apps/android/assistant-holder apps/android/conversation-ui
git commit -m "新增: 实现受限屏幕选区附件"
```

### Task 17: 接入受保护插件声明式 UI 与 Developer Trust 原生接管恢复门

**Files:**
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/plugin/DeclarativePluginRenderer.kt`
- Create: `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/plugin/PluginUiAction.kt`
- Create: `apps/android/conversation-ui/src/androidTest/kotlin/com/openandroidintelligence/ui/plugin/DeclarativePluginRendererTest.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugin/PluginUiCoordinator.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugin/NativeUiExtensionRegistry.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/plugin/PluginManagementScreen.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/settings/PlatformSettingsScreen.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/recovery/SafeModeScreen.kt`
- Modify: `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/DeveloperTrustMode.kt`
- Modify: `apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/NativePluginLoader.kt`
- Create: `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugin/PluginUiCoordinatorTest.kt`
- Create: `apps/android/app/src/androidTest/kotlin/com/openandroidintelligence/mobile/plugin/PluginManagementInstrumentedTest.kt`

**Interfaces:**
- Consumes: `plugin-ui` `UiContribution/UiComponent`、Kernel grant/lifecycle、BuildConfig flavor policy。
- Produces: exhaustive host renderer；每 action 再授权；full-only native UI extension；先于插件加载的 safe mode。

- [ ] **Step 1: 写 exhaustive renderer RED**

```kotlin
@Test
fun everyWhitelistedComponentRendersAndUnknownContributionNeverExecutes() {
    val contribution = contributionWithAllEightKinds()
    composeRule.setContent {
        DeclarativePluginRenderer(contribution, onAction = actions::add)
    }
    listOf("section", "text", "status", "toggle", "select", "button",
        "permission-request", "capability-picker").forEach {
        composeRule.onNodeWithTag("plugin-$it").assertExists()
    }
    assertTrue(actions.isEmpty())
}
```

- [ ] **Step 2: 写 Play flavor/Kernel RED**

Play build 中 Developer Trust 开关和 Native takeover route 必须不存在；full build 的 switch 未经确认不能改变 `DeveloperTrustMode`；action 展示不能等于 capability grant。

- [ ] **Step 3: 运行 RED**

Run: `:app:testFullDebugUnitTest :app:testPlayDebugUnitTest :conversation-ui:connectedDebugAndroidTest`

- [ ] **Step 4: 实现 declarative renderer**

对 `Section/Text/Status/Toggle/Select/Button/PermissionRequest/CapabilityPicker` exhaustive `when`；renderer 只发 `PluginUiAction(pluginIdentity, contributionId, componentId, actionId, value)`，不接受 HTML/JS/WebView/Intent/class/expression。

- [ ] **Step 5: 实现 coordinator 再授权**

`PluginUiCoordinator` 每次 action 调用 Kernel 重新核对 plugin identity、enabled state、capability version、phone limits、pairing grant revision 和 current session；任一变化失败关闭并显示可恢复原因。

- [ ] **Step 6: 实现平台管理 routes**

PluginManagement 是独立 destination；附件 route 不再空操作。设置显示插件 lifecycle、授权、安全审计、媒体 cache 和 Developer Trust。无 Gateway/无插件用具体行动文案。

- [ ] **Step 7: 实现 Developer Trust**

`play` flavor 永不暴露 enable 路径。`full` 开启前显示“同 UID/进程、可读完整镜像与媒体、可影响确认和审计”的持续警告；确认后调用真实 Kernel `DeveloperTrustMode`。关闭立即停用全部 native plugins。

- [ ] **Step 8: 实现 NativeUiExtensionRegistry 与安全恢复**

```kotlin
interface NativeUiExtension {
    val pluginIdentity: PluginIdentity
    @Composable fun Render(host: NativeUiHost)
}
```

只有显式安装、包身份/作者/摘要验证、full flavor、全局 trust enabled 时注册。SafeMode/全局停用/崩溃恢复入口在加载 native extension 前可达，不允许 extension 覆盖。

- [ ] **Step 9: 运行 GREEN**

Run:

```bash
:plugin-ui:testDebugUnitTest \
:platform-kernel:testDebugUnitTest \
:conversation-ui:connectedDebugAndroidTest \
:app:testFullDebugUnitTest \
:app:testPlayDebugUnitTest \
:app:connectedFullDebugAndroidTest
```

- [ ] **Step 10: 安全/规格审查并提交**

```bash
git add apps/android/conversation-ui apps/android/app apps/android/platform-kernel
git commit -m "新增: 接入受保护插件界面"
```

### Task 18: 全量无障碍、性能、物理 E2E、清理与集成门禁

**Files:**
- Modify: `apps/android/app/build.gradle.kts`
- Create: `apps/android/macrobenchmark/build.gradle.kts`
- Create: `apps/android/macrobenchmark/src/main/AndroidManifest.xml`
- Create: `apps/android/macrobenchmark/src/main/kotlin/com/openandroidintelligence/benchmark/ConversationFrameBenchmark.kt`
- Modify: `apps/android/settings.gradle.kts`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/preview/ConversationPreviews.kt`
- Create: `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/preview/AssistantPreviews.kt`
- Create: `apps/android/app/src/androidTest/kotlin/com/openandroidintelligence/mobile/AccessibilityAndLocalizationTest.kt`
- Create: `apps/android/app/src/androidTest/kotlin/com/openandroidintelligence/mobile/EndToEndConversationInstrumentedTest.kt`
- Create: `apps/android/assistant-holder/src/androidTest/kotlin/com/openandroidintelligence/assistant/DefaultAssistantPhysicalE2ETest.kt`
- Create: `docs/superpowers/reviews/2026-09-01-android-conversation-assistant-ui-final-review.md`
- Create: `docs/superpowers/handoffs/2026-09-01-android-conversation-assistant-ui-handoff.md`

**Interfaces:**
- Consumes: Tasks 1–17。
- Produces: release decision，不新增产品行为；所有旧实现、假状态和未验证声明被关闭或明确 BLOCKED。

- [ ] **Step 1: 建立 Preview matrix**

至少包含浅/深、zh-CN/en/RTL、fontScale 1.0/1.3/2.0、compact phone、600dp medium、SM-X710 expanded/landscape、folded/half-open、无 Gateway、空对话、长 Markdown、工具 running/failed、tombstone、offline/stale/outcome unknown、附件各状态、assistant expanded/docked/selection/reduced。

- [ ] **Step 2: 建立自动无障碍检查**

Compose semantics test 验证 48dp target、contentDescription、heading/role、TalkBack 顺序、错误/完成 live region、流式内容不逐 token 宣告、颜色非唯一信号。Espresso AccessibilityChecks 对主 routes 扫描。

- [ ] **Step 3: 建立 Macrobenchmark**

`ConversationFrameBenchmark` 使用 `FrameTimingMetric` 测：

- 1000 条 timeline 的启动/滚动；
- 10 token/s 流式 60 秒且用户上滑；
- ToolResult 展开/折叠；
- assistant Morph 快速反向；
- dock drag/snap；
- selection path。

最低门槛 60Hz 设备无持续 jank；高刷新率记录实际帧期限。不能只用平均 FPS 掩盖峰值 dropped frames。

- [ ] **Step 4: 跑全量机械门禁**

```bash
./tools/run-node24 npm run gateway:v2:conformance
./tools/run-node24 npm --prefix gateway-contract test
./tools/run-node24 npm --prefix gateway-contract run typecheck
./tools/run-node24 npm --prefix integrations/openclaw test
./tools/run-node24 npm --prefix integrations/openclaw run typecheck
PYTHONPATH=integrations/hermes python3 -m pytest integrations/hermes/tests -q
./tools/run-node24 npx vitest run --exclude '.worktrees/**'
```

Android：

```bash
check \
testDebugUnitTest \
:app:testFullDebugUnitTest \
:app:testPlayDebugUnitTest \
:app:assembleFullDebug \
:app:assemblePlayDebug
```

Expected: 0 failures；记录精确 suite/test 计数，不沿用旧报告数字。

- [ ] **Step 5: 跑设备/模拟器门禁并如实分类**

```bash
adb devices -l
```

有目标才执行：

```bash
:gateway-client:connectedDebugAndroidTest \
:encrypted-store:connectedDebugAndroidTest \
:conversation-ui:connectedDebugAndroidTest \
:assistant-holder:connectedDebugAndroidTest \
:app:connectedFullDebugAndroidTest \
:macrobenchmark:connectedCheck
```

模拟器可证明 Compose/导航/普通 lifecycle；默认助理角色、跨 App screenshot、FLAG_SECURE、球外触摸、Home/锁屏、IME/分屏和物理手势必须使用真实 Android 14+ 设备。没有设备时最终状态仍是 BLOCKED，不能合并为 release-ready。

- [ ] **Step 6: 完成真实 Android↔Gateway E2E**

分别对 Hermes 和 OpenClaw 验证：登录/刷新、线程列表、普通消息、流式 sequence、附件三步/门控、命令目录、`/new`、批次成员映射、cancel 四种 outcome、SSE 重连、snapshot/tombstone、离线 mirror、媒体 grant。账号 A/B、Gateway A/B、安装实例 A/B 做负向隔离。

- [ ] **Step 7: 慢放/逐帧与视觉证据**

以 10% playback 检查方向转场、消息入场、栏↔球内容交接、圈选↔附件；检查 dark/light、动态字号、RTL 和 reduceMotion。截图/录屏证据放仓库 review 引用的证据目录，不放 `test-results/`、`build/` 或 `/tmp`。

- [ ] **Step 8: 原子清理与假状态扫描**

```bash
rg -n 'GatewayPresenter|ConversationPresenter|AttachmentPresenter|example\.com|gateway\.local|192\.168\.1\.100|快速笔记|测试附件|status *= *AttachmentStatus\.VERIFIED|onStopClick *= *\{\}' \
  apps/android/app apps/android/assistant-holder apps/android/conversation-ui apps/android/conversation-domain
```

Expected: 0 matches（测试中的显式 negative fixture 必须放专用 fixture 文件并在审查说明）。

同时运行 `git diff --check`、依赖边界/no-VPN gate、secret/privacy scan。废弃文件只用 `mv`/trash。

- [ ] **Step 9: 两轴 code review + motion review**

- Standards 轴：AGENTS、架构、契约、Fowler smells。
- Spec 轴：设计 `1–24` 和 ADR-0041–0046。
- Motion 轴：purpose/frequency、easing、physicality、interruptibility、performance、accessibility、cohesion。

任一 BLOCK/critical/important 未关闭，不得合并。

- [ ] **Step 10: 写 final review 与 handoff**

review 逐项记录报告 01–72 的 CLOSED/REJECTED-WITH-REASON/BLOCKED；handoff 写真实 commit range、命令、计数、设备/OEM、未证明边界和下一步。不得复制旧 PASS。

- [ ] **Step 11: 提交验收材料**

```bash
git add apps/android/macrobenchmark apps/android/app apps/android/assistant-holder \
  docs/superpowers/reviews/2026-09-01-android-conversation-assistant-ui-final-review.md \
  docs/superpowers/handoffs/2026-09-01-android-conversation-assistant-ui-handoff.md
git commit -m "测试: 完成前端重构全量验收"
```

- [ ] **Step 12: 安全集成 dirty main**

先在 clean merge worktree 创建 `--no-ff` merge 并重跑相称门禁，把 reviewed merge 固定到本地分支 `codex/android-conversation-ui-reviewed-merge`。回到 dirty main 前后比较 HEAD、index/worktree diff hash、未跟踪文件 hash；只有完全不变才执行：

```bash
REVIEWED_MERGE="$(git rev-parse codex/android-conversation-ui-reviewed-merge)"
git merge --ff-only "$REVIEWED_MERGE"
```

不得 stash/reset/clean 用户文件。`feat/conversation-assistant-ui` 及其 dirty worktree继续保留，除非用户另行明确要求归档。

## 报告 01–72 → Task 可追溯矩阵

| Report issues | Primary closure task | Acceptance evidence |
|---|---|---|
| 01–04 | 11, 14 | merged manifest、系统 role/process、Navigation back test |
| 05–07 | 15 | Morph/interrupt/velocity slow-motion + gesture tests |
| 08, 12, 13 | 4, 5, 9, 11 | ports/controller 单状态源、旧 Presenter 0 matches |
| 09, 31, 38, 43, 69 | 7, 13, 16 | digest/upload/verified/exactly-once、真实进度 |
| 10, 33 | 6, 14, 15 | cancel outcome matrix、停止按钮真实 dispatch |
| 11, 34, 39, 49 | 5, 8, 11 | encrypted mirror、真实 sync state、scoped clear |
| 14, 19, 24 | 12, 13 | 180ms item entry、连续 batch progress、scroll policy |
| 15, 17, 18, 20, 64 | 10, 15 | MotionPolicy、一次反馈、press node、reduced |
| 16 | 15 | no-jump/1:1/velocity behavior test（重述后关闭） |
| 21 | 12, 18 | trace/FrameTimingMetric 后选择实现 |
| 22 | 12 | typed running status + progress semantics，不强制循环 |
| 23, 61, 72 | 15 | dynamic safe bounds、60% height、drop target |
| 25, 27, 30, 60 | 16 | real screenshot、pointer lock、session state、TalkBack editor |
| 26, 55, 56 | 11 | predictive back、edge-to-edge、startup theme |
| 28, 29, 48, 54 | 11, 17 | typed routes、all-Gateway picker、plugin renderer |
| 32, 53, 68 | 4, 9, 14 | open-thread Intent、process graph、no agentSessionId |
| 35, 40 | 6 | title policy、dynamic command catalog |
| 36 | 9, 11 | no default URL/online；真实 session setup |
| 37, 45–47, 51–52, 70 | 12 | typed tool、Markdown/code、tombstone/retry/copy/layout |
| 41–42 | 4, 5 | MessagePart + four axes + one reducer |
| 44 | 11 | compact/medium/expanded tests |
| 50 | — | REJECTED-WITH-REASON；非缺陷，不制造 churn |
| 57–59, 62–63 | 10, 18 | em tracking、ColorScheme、48dp、documented grid、Preview matrix |
| 65 | — | REJECTED-WITH-REASON；旧 helper 随原子替换删除 |
| 66–67, 71 | 11, 13, 18 | clean imports、真实 Compose instrumentation、IME Send |

## Spec 1–24 覆盖

| Spec section | Tasks |
|---|---|
| 1–3 权威/表面 | 1–3, 11, 14 |
| 4 视觉 | 10, 11, 12, 13, 15–17 |
| 5 主 App IA | 11 |
| 6 时间线/标题 | 6, 12 |
| 7 共享编辑器 | 4, 5, 13 |
| 8 防抖批次 | 1–3, 6, 13 |
| 9 助理状态/进程 | 4, 9, 14, 15 |
| 10 屏幕圈选 | 16 |
| 11 动效 | 10, 12, 15, 16, 18 |
| 12 附件门控 | 1–3, 7, 13, 16 |
| 13 流式/取消 | 1–3, 5, 6, 12–15 |
| 14 命令 /new | 1–3, 6, 13 |
| 15 本地镜像 | 5, 8, 11 |
| 16 领域 interfaces | 4, 5 |
| 17 协议扩展 | 1–3 |
| 18 插件 UI | 17 |
| 19 错误/空态 | 5, 7, 8, 11–13 |
| 20 无障碍/适应 | 10–18 |
| 21 测试 | 每 Task + 18 |
| 22 分阶段 | 1–18 顺序门禁 |
| 23 非目标 | Global Constraints + reviews |
| 24 验收 | 18 |

## 计划自检命令

在计划落盘后、执行前运行：

```bash
PLAN=docs/superpowers/plans/2026-09-01-android-conversation-assistant-ui-full-refactor.md
rg -n 'T[B]D|T[O]DO|implement later|fill in|类似 Task|适当处理|按需实现' "$PLAN" \
  | rg -v '^[0-9]+:rg -n '
git diff --check -- "$PLAN"
rg -n '^### Task [0-9]+:' "$PLAN"
rg -n '^\*\*Files:\*\*|^\*\*Interfaces:\*\*|^- \[ \] \*\*Step' "$PLAN"
```

Expected:

- placeholder scan 0 matches；
- `git diff --check` 退出 0；
- 恰好 18 个 Task；
- 每个 Task 都有 Files、Interfaces、RED、正确失败原因、GREEN、验证、review 和中文 commit；
- 所有 target types/方法名在首次使用前或同 Task 中定义；
- report 01–72 与 spec 1–24 无未映射项。

## Execution handoff

本计划是完整 program plan，但执行仍严格一次只做一个 Task。Task 1 开始前，先把设计 spec front matter 从 `proposed` 改为 `accepted`，并在同一文档提交中记录用户于 2026-09-01 授权进入实施计划；该文档提交不得夹带实现代码。

执行选择：

1. **Subagent-Driven（推荐）**：每个 Task 使用全新 `gpt-5.6-luna / max` implementer，随后独立 Spec reviewer 和 Quality reviewer；主 Agent 每 Task 只集成一次。
2. **Inline Execution**：使用 `superpowers:executing-plans`，按 Task 分批执行，每个 Task 完成后停下等待审查，不跨边界。

无论选择哪种，Task 18 的真机证据当前都保持 BLOCKED，直到 `adb devices -l` 出现可用 Android 14+ 目标。
