# Spec 轴独立审查（844b6cc..0334789）

**审查日期：** 2026-09-01
**审查对象：** `git diff 844b6cc..0334789`
**Spec 来源：** `docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md`、`docs/contracts/gateway-protocol-v2.md`、`docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md`、`docs/superpowers/plans/2026-09-01-android-conversation-assistant-ui-full-refactor.md`（Task 1–18）、`docs/adr/`
**结论：FAIL**

## 关于「独立审查」的执行说明

同 Standards 轴：本会话派发的两个独立审查子 Agent 均在完成前返回截断结果、未落盘报告，属第三次复现的编排工具失效。按交接文档「不要把错误当作审查完成」，本轴由主 Agent 依据现场源码与命令输出直接执行，不使用历史报告作为证据。

## (a) Spec 要求但缺失或只做了一部分

### S-1 协议路由缺失 8 条（Task 1 Step 4）

计划要求 `docs/contracts/gateway-protocol-v2.md` 精确增加：

```text
GET  /commands?languageCode=<bcp47>
POST /conversations/{conversationId}/message-batches
POST /conversations/{conversationId}/generations/{generationId}/cancel
GET  /attachments/{attachmentId}
DELETE /attachments/{attachmentId}
GET  /conversations/{conversationId}/messages?clientMessageId=<id>
GET  /conversations/{conversationId}/attachments/{attachmentId}/metadata
POST /conversations/{conversationId}/attachments/{attachmentId}/cache-grant
GET  /conversations/{conversationId}/attachments/{attachmentId}/content
```

现场 `rg` 对 `docs/contracts/gateway-protocol-v2.md` 的实测结果只有：

```text
387:GET  /conversations
388:POST /conversations
389:GET  /conversations/{conversationId}
390:POST /conversations/{conversationId}/messages
```

**9 条要求中只存在 `/conversations` 系列 4 条，其余 8 条（含全部 attachments 与 generations 路由）均不存在。**

### S-2 SSE 事件闭集完全缺失（Task 1 Step 4）

计划要求新增闭集 `conversation.message.accepted`、`conversation.generation.cancelled`、`conversation.command.result`、`conversation.title.updated`、`conversation.timeline.upsert`、`conversation.timeline.tombstoned`、`conversation.snapshot.invalidated`。

实测：`rg 'conversation\.(message|generation|command|title|timeline|snapshot)' gateway-contract/schemas/event.schema.json` **返回 0 行**。`event.schema.json` 仅有 `correlationId`/`occurredAt`/`payload`，`payload` 是开放的 `"type": "object"`，只留了一条 `$comment` 说明「缺子 Schema 必须 fail closed」，但没有任何事件类型枚举可供 dispatch。

### S-3 两个新 Schema 文件未创建（Task 1 Files）

计划要求 Create `gateway-contract/schemas/command-catalog.schema.json` 与 `conversation-snapshot.schema.json`。现场 `gateway-contract/schemas/` 只有 `attachment / conversation / device-request / envelope / event / negotiate / session / v1-bootstrap-export` 八个文件，**两个新 Schema 均不存在**；`commandCatalog` 与 `mirrorSync` 被塞进 `conversation.schema.json` 的 `$defs`。

### S-4 `joinMessageBatch` 语义与计划相反（Task 1 Step 3/6）

计划要求的向量：

```ts
expect(joinMessageBatch([
  { clientMessageId: "msg_a", text: "甲\n" },
  { clientMessageId: "msg_b", text: "\n乙" },
])).toEqual(new TextEncoder().encode("甲\n\n\n乙"));
```

实际 `gateway-contract/src/state-machines.ts:183-186`：

```ts
export const joinMessageBatch = (members: ReadonlyArray<MessageBatchMember>): string => {
  if (members.length === 0) return "";
  return members.map((m) => m.text.replace(/^\n+|\n+$/g, "")).join("\n");
};
```

四处偏差：返回 `string` 而非 `Uint8Array`；裁剪了每个成员首尾换行（计划要求 `newline-v1` = 原样以单个 U+000A 连接）；无 20 成员上限；无重复 `clientMessageId` 拒绝。按此实现，上述向量会得到 `"甲\n乙"` 而非 `"甲\n\n\n乙"`。

### S-5 向量集协议版本未升到 2.1（Task 1 Step 5）

`gateway-contract/vectors/conversation-ui.json:3` 为 `"protocolVersion": "2.0"`，而本 Task 的产物定义是 protocol minor `2.1`。

### S-6 assistant-holder 仍是独立 application（Task 14 Step 3）

计划要求改为 `com.android.library`、删除独立 `applicationId`、与 `app` 同为 `com.openandroidintelligence.mobile`。实际 `apps/android/assistant-holder/build.gradle.kts`：

```kotlin
plugins { id("com.android.application"); ... }
android {
    namespace = "com.openandroidintelligence.assistant"
    defaultConfig { applicationId = "com.openandroidintelligence.assistant" }
}
```

**仍是独立 APK/UID，与 Task 14 的「同 APK 私有进程」要求直接冲突。**

### S-7 主 App 导航与旧屏幕未原子替换（Task 11）

计划要求 `MainActivity : ComponentActivity` + Navigation Compose，并原子移出 `GatewayScreen.kt`/`ConversationScreen.kt`/`AttachmentPicker.kt`/`PlatformSettingsScreen.kt`。实测四者全部仍在，且 `MainActivity.kt:24` 为 `class MainActivity : Activity()`，`MainActivity.kt:31` 仍用 `private var currentTab: String = "gateway"` 保存当前屏幕，与 Task 11 Step 3「不再以裸 String 保存 current screen」冲突。

### S-8 conversation-ui 包结构与模块内容不匹配（Task 10）

计划要求包 `com.openandroidintelligence.ui.design.*` 且含 `ColorTokens/TypeTokens/ShapeTokens/SpacingTokens/MotionPolicy/MotionTokens/PressFeedback/SignalStitch/OpenAndroidIntelligenceTheme`。实际包名为 `com.openandroidintelligence.conversation.*`，`src/main` 下只有 6 个文件：`assistant/AssistantView.kt`、`components/Components.kt`、`motion/MotionSpecs.kt`、`selection/ScreenSelectionOverlay.kt`、`theme/AppColors.kt`、`theme/Theme.kt`、`workbench/Workbench.kt`。**计划要求的 9 个设计系统文件一个都不存在，SpacingTokens/48dp 触控令牌无集中定义。**

### S-9 设计令牌与 accepted 规范不符（Task 10 Step 1）

计划的令牌 RED 断言 `0xFFF1F4F1`（Light canvas）、`0xFF101613`（Dark canvas）、`48.dp`、`56.dp`。实际 `conversation-ui/.../theme/AppColors.kt` 为 `LightBackground = Color(0xFFF7FAFC)`、`DarkBackground = Color(0xFF12161A)`，且**没有 Accent / Error / Muted 令牌，也没有 Dimensions**。

### S-10 conversation-domain 缺 plan Task 5–7 的全部实现（Task 5/6/7）

计划要求 `ConversationController.kt`、`DefaultConversationController.kt`、`internal/EventProjector.kt`、`commands/CommandCoordinator.kt`、`batch/DebounceBatcher.kt`、`title/ConversationTitlePolicy.kt`、`generation/GenerationQueue.kt`、`attachment/{SubmissionSnapshot,DefaultAttachmentDraftCoordinator,AttachmentSubmissionGate}.kt`。现场 `conversation-domain/src/main` 只有 `model/`（4 文件）、`ports/`、`reducer/`，**上述 9 个文件全部不存在**。

`ConversationSessionState`（`model/ConversationState.kt:35-44`）也不含计划 `Task 5` Step 1 测试所需的 `composerDraft`、`timeline`、`activeConversationId` 字段，且模块内只有 `ConversationEvent`、**没有 `ConversationIntent`**，因此 Task 5 的 RED 测试在当前模型上无法表达。

### S-11 加密对话镜像未实现（Task 8）

`apps/android/encrypted-store` 下无任何 `conversation/` 或 `media/` 源文件；`ConversationMirrorDatabase`、`AndroidConversationMirrorStore`、`MirrorPayloadCipher`、`MirrorScopePaths`、`LocalMediaCacheStore` 全部不存在。

## (b) 范围蔓延

- **`conversation-ui` 自带 `workbench/Workbench.kt` 与 `assistant/AssistantView.kt`**：按计划，Workbench/Shell 属于 Task 11 的 `app` 模块（`ui/shell/OpenAndroidIntelligenceApp.kt`、`AdaptiveConversationShell.kt`），助理 UI 属于 Task 14–16 的 `assistant-holder`。这两个文件提前把主 App Shell 与助理视图放进了共用设计模块，会导致 `app` 与 `assistant-holder` 反向依赖 `conversation-ui` 的业务结构，与计划「`conversation-ui` 只提供 stateless 设计系统，App 与 assistant-holder 都依赖它，不互相依赖」的边界冲突。
- **`conversation-ui` 自带 `selection/ScreenSelectionOverlay.kt`**：按计划 Task 16 该组件属于 `assistant-holder`。

## (c) 实现看起来有但明显错误

- **`conversation-domain` 与 `gateway-client` 各自定义 `VerifiedConversationEvent`**：见 Standards 轴 S2。计划把 sealed `VerifiedConversationEvent` 定义在 `gateway-client`（Task 3 Files），domain 只通过 ports 消费。现在 domain 里是一个 `data class(eventType: String, payload: Map<String, Any?>)`，与计划契约不兼容，且两处同名不同类型会造成后续集成歧义。
- **`MirrorScope(accountId)` 与 `ConversationScope(accountId, gatewayId)` 并存**：`ConversationRepository` 用后者、`ConversationMirrorStore` 用前者，同一模块内两套隔离键，使计划 Task 8 Step 1「一个 scope 打不开另一个 scope」的隔离语义无法一致表达。

## Task 1–18 完成矩阵

| Task | 判定 | 依据（一句话） |
|---|---|---|
| 1 协议 v2.1 | **PARTIAL** | 协商/会话 Schema 与 `conversation-ui.json` 向量已有，但 8 条路由、SSE 事件闭集、两个新 Schema 文件缺失，`joinMessageBatch` 语义相反，向量仍标 2.0。 |
| 2 双宿主 v2.1 | **PARTIAL** | 双宿主向量与 conformance 产物存在（`.artifacts/conformance/*.jsonl`），但依赖 Task 1 完整契约，宿主侧路由未经本轴逐项映射验证，需 Task 1 完成后重判。 |
| 3 Android typed clients | **PARTIAL** | `gateway-client` 已扩展批次/选区/`ConversationClient`，但计划要求的 `ConversationClientV21`、不透明 wire 类型校验 `requireWireId`、`GatewayFeatureSet` 与 sealed `VerifiedConversationEvent` 未确认存在，需现场复核后定。 |
| 4 domain 模型与 reducer | **PARTIAL** | 四轴枚举与 reducer 骨架存在，但 `ConversationScope` 退化、终态唯一未完整强制、value class 字符集未校验。 |
| 5 单一 Controller | **NOT-STARTED** | `ConversationController`/`DefaultConversationController`/`EventProjector` 均不存在，`ConversationIntent` 与所需状态字段缺失。 |
| 6 命令/批次/标题/取消 | **NOT-STARTED** | `commands/`、`batch/`、`title/`、`generation/` 四个包全部不存在。 |
| 7 附件门控 | **NOT-STARTED** | `attachment/` 包不存在，`PendingSubmissionIntent` 退化为二元组。 |
| 8 加密镜像与媒体缓存 | **NOT-STARTED** | `encrypted-store` 下无 `conversation/`、`media/` 任何源文件。 |
| 9 进程感知 AppGraph | **NOT-STARTED** | 无 `process/AppProcessRole.kt`、`di/AppGraph.kt`；`MainActivity` 仍直接持有三个 Presenter。 |
| 10 设计与动效系统 | **NOT-STARTED** | 包名错误，9 个设计系统文件均不存在，调色板与 accepted 规范不符，无 `MotionPolicy`。 |
| 11 原子替换与导航 | **NOT-STARTED** | 四个旧屏幕仍在，`MainActivity : Activity()`，无 Navigation Compose。 |
| 12 时间线/Markdown/tombstone | **NOT-STARTED** | `conversation-ui` 无 `timeline/`、`message/`、`tool/`、`tombstone/`；无 `commonmark` 依赖。 |
| 13 共享编辑器 | **NOT-STARTED** | 无 `composer/`、`attachment/` 组件；无 `AndroidArtifactSelectionAdapter`。 |
| 14 同 APK 私有进程助理 | **NOT-STARTED** | `assistant-holder` 仍是独立 application，无 Messenger IPC 协议。 |
| 15 栏↔球 Morph 与停靠物理 | **NOT-STARTED** | 无 `AssistantMorphContainer`、`DockPhysics`、`DockSafeBounds`。 |
| 16 受限屏幕选区 | **NOT-STARTED** | 无 `ScreenCropEncoder`、`SelectionPath`；`ScreenSelectionOverlay` 位置错误（在 `conversation-ui`）。 |
| 17 插件声明式 UI | **NOT-STARTED** | 无 `DeclarativePluginRenderer`、`PluginUiCoordinator`。 |
| 18 全量验收 | **NOT-STARTED** | 无 `macrobenchmark` 模块、无 E2E/无障碍测试、无最终证据。 |

**第一个真实未完成的 Task：Task 1。**

## 明确排除的误报

- `gateway-contract/vectors/conversation-ui.json` 已存在且含 `conversation-ui-catalog`/`conversation-ui-cancel`/`conversation-ui-mirror` 三个 case——这不等于 Task 1 完成，因为 case 引用的 `conversation.commandCatalog` 等 Schema 名虽已注册，但被计划要求独立成文件的能力（命令目录、快照）尚未按 Files 清单落地，且事件闭集缺失使 `schema.validate` 无法覆盖 SSE 事件。仅记为「部分满足」，不作为完成证据。
- `conversation-ui` 存在 `motion/MotionPhysicsTest.kt` 与 `MotionSpecs.rubberband/project`——这两个纯函数方向正确，但计划 Task 15 的 `DockPhysics.release` 签名与可中断 retarget 不在本范围，不据此判定 Task 15 部分完成。

## 未覆盖范围

- 真机/仪表化：无设备，所有 `connectedDebugAndroidTest`、系统 Assist 角色、跨 App 截图证据均为 **BLOCKED**，本轴不给出运行时结论。
- 双宿主（Hermes/OpenClaw）生产代码的逐路由行为未逐行比对，仅确认向量与 conformance 产物存在。
- Motion 轴独立结论见单独审查（本轴只判定结构缺失，不判定动效手感）。