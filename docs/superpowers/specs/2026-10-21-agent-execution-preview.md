---
status: accepted
date: 2026-10-21
title: 对话执行过程可视化与图片大图预览：思维链、工具调用、执行过程聚合与 Lightbox
refines:
  - 2026-09-18-conversation-session-ui-completion-spec.md
  - 2026-10-21-markdown-rendering-fix.md
  - 2026-10-14-local-conversation-media-download.md
authorization: 2026-10-21 用户直接要求优化聊天界面的 Agent 执行过程可视化与图片附件展示交互
---

# 对话执行过程可视化与图片大图预览

## 1. 范围与硬约束

- **只改界面层**：`apps/android/conversation-ui` 与 `apps/android/app` 的 Composable、UI 派生模型、主题令牌与测试。
- **不改**：Gateway 协议、Schema、`*-ports` 契约、领域模型、持久化格式、插件运行时、后端。
- **不新增第三方 UI 依赖**：继续使用 Compose BOM 2024.12.01 / Material3；图片方向优先使用平台 `ImageDecoder`（minSdk 31）。
- **不伪造状态**：未闭合的思考/工具标记在流结束时显示“已中断”；原图不可用时明示“当前为预览图”；下载失败可重试。
- **保持既有守卫**：`MaterialTokenGuardTest`、`ThemeContrastTest`、`AppTransitionsTest`、`MotionPhysicsTest` 与全部既有 Compose 测试继续通过。
- **走设计令牌**：颜色只取 `MaterialTheme.colorScheme` / `LocalAppStateColors`；圆角只取 `AppRadius`；容器尺寸、状态与动效时长取 `Dimensions` / `MotionSpecs`。既有 Markdown 行内排版的 11.5–13sp 档位继续沿用，新增卡片容器不写魔数。

## 2. 现状诊断

| # | 现象 | 根因 |
|---|---|---|
| 1 | 思维链正文是纯 `Text`，粗体/列表/代码不渲染 | `MarkdownThoughtBlockView` 直接 `Text(block.thought)` |
| 2 | 思考完成没有“深度思考 + 耗时”摘要，中断后仍显示“AI 正在思考” | 只判断 `ThoughtBlock.isComplete`，没有结合 `entry.isStreaming`，没有耗时状态 |
| 3 | 工具卡始终展开命令，输出还要再点一次“查看输出” | 卡片没有“执行中/完成”状态，没有自动收起 |
| 4 | 单轮结束时思考/工具分散在多条消息里，没有聚合 | 时间线按 `TimelineEntry` 逐条渲染；工具调用与最终回答可能是两条消息 |
| 5 | 图片上方暴露文件名、卡片底部有「下载」按钮 | `AttachmentFallbackChip` 对所有附件无条件渲染；下载按钮对所有附件显示 |
| 6 | 点击图片没有大图预览；只有 512px 缩略图 | 无 Lightbox；`previewBytes` 是有界缩略图，原图端口 `openOriginal` 未暴露给 UI |
| 7 | 图片解码在主线程、无 bounds/采样/方向处理 | 旧卡片直接 `BitmapFactory.decodeByteArray` |

## 3. 数据流与展示模型

```
TimelineEntry[]（真实端口数据，不改协议）
  └─ TimelineDisplayModel（纯函数 + 解析缓存）
       ├─ Single(entry)                  // 普通消息 / 审批 / 系统行
       └─ Execution(run)                 // 连续助手执行段折叠
            ├─ finalEntry + finalBlocks // 最终回答（可能为空）
            ├─ steps[]                  // 思考、工具、叙述、过程附件
            └─ stepCount / failedStepCount / interrupted
  └─ 宿主 LazyColumn items(displayItems)
       └─ TimelineDisplayRow
            ├─ Single → TimelineRow（既有渲染）
            └─ Execution → 执行过程面板 + 最终回答
```

### 3.1 轮次展示模型（新增 `workbench/TimelineDisplay.kt`）

```kotlin
sealed interface TimelineDisplayItem {
    val key: String
    data class Single(val entry: TimelineEntry) : TimelineDisplayItem
    data class Execution(val run: AssistantExecutionRun) : TimelineDisplayItem
}

data class AssistantExecutionRun(
    val key: String,                 // "execution:" + finalEntry.key
    val finalEntry: TimelineEntry,
    val finalBlocks: List<TimelineBlock>,
    val steps: List<ExecutionStep>,  // 按时间顺序展平
    val stepCount: Int,              // 思考 + 工具卡数量
    val failedStepCount: Int,
    val interrupted: Boolean,
    val isStreaming: Boolean,
    val hasFinalAnswer: Boolean,
)

sealed interface ExecutionStep {
    val stepKey: String?             // "<entryKey>#<blockIndex>"，用于耗时
    data class Thought(...) : ExecutionStep
    data class ToolCall(...) : ExecutionStep
    data class Narrative(val block: TimelineBlock, val entryKey: String) : ExecutionStep
    data class Attachments(val entry: TimelineEntry) : ExecutionStep
}

class TimelineDisplayModel {
    fun build(entries: List<TimelineEntry>): List<TimelineDisplayItem>
}
@Composable
fun rememberTimelineDisplayItems(entries: List<TimelineEntry>): List<TimelineDisplayItem>
```

**分组与折叠规则**

1. 用户消息、审批行、系统回执行是 `Single`，并打断连续助手执行段（审批是用户决策点，跨审批聚合会让顺序失真；作为已知限制记录）。
2. 连续助手消息组成一个执行段。每条助手消息计算：思考/工具块数量、最后一个执行块位置、尾部（最终回答）块、`finalVisible`（尾部有可见内容）、`entry.isStreaming`。
3. 折叠条件：段内至少有一个思考或工具块，且满足其一：
   - 某条消息已出现尾部正文（最终回答已开始，即使仍在流式）；
   - 整段不再有 `isStreaming`（执行结束，包括只有工具、没有最终回答）。
   例外：Hermes 会逐步发送「调用工具：xxx」占位消息与工具活动日志；当整段都是这类消息且还没有最终正文时，先逐条显示工具卡，等最终回答出现后再聚合，避免长工具序列一开始就全部藏进折叠面板。
   **生成状态优先**：Hermes 的实时快照可能一直停留在 `STREAMING`，直到切会话重载才变成 `CONFIRMED`；因此宿主把 `GenerationState` 一并传给展示模型。只有明确终态 `COMPLETED`/`FAILED`/`CANCELLED` 会强制允许折叠并关闭最终回答光标；`OUTCOME_UNKNOWN`/`UNSUPPORTED`/`IDLE` 仍按每条消息的 `isStreaming` 判断（取消结果未知时可能还有增量到达，不能提前折叠或收掉光标）。
4. 不折叠：段内没有任何思考/工具块（普通多条助手消息保持现状）；或仍在执行且尚未出现尾部正文（逐条显示实时卡片）。
5. `finalEntry`：最后一条 `finalVisible` 的助手消息；没有则取段内最后一条助手消息（仅用于元数据/附件承接）。
6. 折叠内容 = 段内更早助手消息的完整内容 + `finalEntry` 尾部正文之前的块；`finalEntry` 没有最终正文时，其附件也进入过程区。
7. 步骤数只统计思考/工具卡；叙述与附件不计数但保留。
8. 解析缓存按 `entry.key`，文本长度变化才重解析；上限 128。解析异常回退为纯文本段落，不崩溃。
9. 临时流式 id → 正式 id 换 key 时展示项重建，展开偏好与耗时丢失；这是既有 key 语义。

**折叠决策矩阵**

| 场景 | 有思考/工具 | 已出现尾部正文 | 仍在流式 | 呈现 |
|---|---|---|---|---|
| 普通回答 | 否 | — | 任意 | 逐条 Markdown |
| 正在思考/执行 | 是 | 否 | 是 | 逐条实时卡片（思考展开、工具执行中展开） |
| 最终回答开始 | 是 | 是 | 是 | 折叠面板 + 下方流式最终回答 |
| 执行结束但无最终回答 | 是 | 否 | 否 | 折叠面板 + 状态元数据 |
| 中断/失败 | 是 | 否 | 否 | 折叠面板（标题带“已中断”）+ 错误元数据 |
| 跨多条助手消息 | 任意一条有 | 最后一条有正文 | 任意 | 前面条目 + 最后一条的中间块进面板 |

### 3.2 耗时与中断状态（新增 `workbench/ExecutionTiming.kt`）

- `ExecutionTimingStore(clock: () -> Long = { SystemClock.elapsedRealtime() })`，提供 `observe(stepKey, running)` 与 `durationOf(stepKey)`。
- 由宿主在会话级创建（`remember(activeThreadId)`），通过 `LocalExecutionTiming` 提供给卡片，保证实时卡片收进聚合面板时耗时状态不丢。
- 只记录真实观察到的运行区间；从未观察到开始时不编造耗时，标题省略耗时。不持久化。
- 思考有效完成 = `block.isComplete || !entry.isStreaming`；未闭合且流结束 → “思考已中断”。
- 工具运行中 = `block.isRunning && entry.isStreaming`；`isRunning && !entry.isStreaming` → “执行已中断 · 结果未知”。
- 用户手动展开/收起优先于自动折叠。

### 3.3 AST 与解析器

- `ToolCallBlock` 新增 `isRunning: Boolean = false`（默认值兼容既有构造与测试）。
- 未闭合 `<tool_call>` / 未闭合 fenced `tool_call` → `isRunning = true`；闭合 → `false`。
- 新增 `MarkdownParser.parseNarrative(text)`：只解析普通 Markdown/代码块，不识别 `<think>` / `<tool_call>`，供思维链正文与过程叙述使用，避免嵌套标签递归产生卡片。
- **Hermes/宿主纯文本工具调用**：独立成行的 `调用工具：execute_code`（兼容 `工具调用:`、`Tool call:`、`Calling tool:` 与可选行首装饰）解析为 `ToolCallBlock(invocationOnly = true)`；工具名后的剩余文本作为命令/参数，没有结果时不伪装「执行成功」，摘要栏显示「已调用」。Hermes 的实时进度行 `Running code` / `Searching files for` / `Reading` / `Writing` / `Editing` 等按 `agent/display.py` 的标签映射回 `execute_code` / `search_files` / `read_file` 等工具卡（`invocationOnly = true`，参数取行内剩余文本）；整条消息全是活动日志（至少两行、行尾无句读）时才按进度消息处理，普通正文里以 Reading/Running 开头的句子不会被误判成工具卡。
- 既有 `<think>` / `<tool_call>` / `$ adb` 兼容路径与全部既有解析语义保留。

### 3.4 附件原图状态

```kotlin
enum class OriginalImageState { IDLE, LOADING, READY, FAILED }

data class TimelineAttachment(
    /* 既有字段 */,
    val originalBytes: ByteArray? = null,
    val originalState: OriginalImageState = OriginalImageState.IDLE,
    val originalError: String? = null,
)
```

- 自定义 `equals/hashCode` 纳入新字段。
- `WorkbenchController.requestOriginalImage(attachmentId)`：
  - 复用 `media`、`mediaDispatcher`、`mediaEpochs`、`mediaTaskCurrent` 与单飞任务模式；
  - 先 `ensureLocal` 再 `openOriginal`，读取上限 24MB；bounds + `inSampleSize` 缩到最长边 2048px，再压缩 JPEG 85；
  - 原图字节放 `LruCache<HistoricalAttachmentKey, ByteArray>`（最多 3 张，淘汰清零），不塞进缩略图缓存；
  - `MediaPresentation` 增加 `originalState/originalError`，`repaintMedia` 时投射到 `TimelineAttachment`；
  - 失败 → `FAILED` + 错误码，Lightbox 回退预览并提示；
  - `invalidateMediaTask`、删除、退出登录、`close()` 清理缓存；
  - 无 `media` 端口时置 `FAILED`，不无限加载。
- 下载链路不变；Lightbox 的「保存至本地」复用 `downloadAttachment(attachmentId)`。

## 4. 渲染与交互

### 4.1 思维链卡片（改造 `markdown/MarkdownThoughtView.kt`）

- 正文：`parseNarrative(block.thought)` + `MarkdownDocumentView`，通过 `LocalMarkdownCompact` 使用 13sp/1.4、`onSurfaceVariant`、紧凑间距；支持粗体、代码、列表、链接、引用。
- 流式：默认展开，标题「AI 正在思考」+ `TypingDots`，正文末尾 `StreamingCursor`，`liveRegion=Polite`。
- 完成：自动收起；标题 `已深度思考 · N 字 · X 秒 · 点击展开`（展开态为“点击收起”）。
- 中断：`思考已中断 · N 字 · 点击展开`，warning 色文字 + 图标。
- 空内容：流式中只显示光标；结束后显示“无思考内容”。
- 用户点击标题记录 override，后续流式完成不强制重置。
- 标题栏 ≥48dp；chevron 用 `MotionSpecs.spatial`；正文用 `ExpandableRegion`。

### 4.2 工具调用卡片（改造 `markdown/MarkdownToolCallView.kt`）

- 摘要栏始终可见：状态图标 + 等宽 `[toolName]` + 状态文字 + 可选耗时 + chevron。
  - 执行中：`执行中` + `TypingDots`（减少动态时静态）；默认展开。
  - 成功：`执行成功`（或 summary）+ 耗时；默认收起。
  - 失败：`执行失败`（或错误摘要）+ 耗时；默认收起，error 色。
  - 中断：`执行已中断 · 结果未知`；warning 色；默认收起。
- 详情展开：
  - 「命令」代码块：等宽、横向滚动、复制按钮（1.5s「已复制」反馈）。
  - 「输出」/「错误输出」代码块：完整 stdout/stderr/result，纵向 + 横向滚动，最大高度 `Dimensions.CommandMenuHeight`，`SelectionContainer`；不再保留第二层「查看输出」开关。
  - `summary` 在输出上方以正文展示。
- 颜色：`LocalAppStateColors.success/warning`、`MaterialTheme.colorScheme.error`；容器 `surfaceContainerHigh` + `outlineVariant` 细边框；圆角 `AppRadius.Compact`；代码块 `surfaceContainerLowest`。
- 用户手动操作优先于自动收起。

### 4.3 执行过程聚合面板（新增 `workbench/ExecutionViews.kt`）

- 容器：`Surface`，`surfaceContainerLow`，`AppRadius.Medium`，`outlineVariant` 细边框。
- 标题栏（≥48dp，`role=Button`）：
  - 折叠：`已完成 N 步执行与思考 · 点击展开`；
  - 展开：`执行过程 · N 步 · 点击收起`；
  - 有失败/中断时追加 `· 含 M 步失败`，使用 warning/error 图标与文字；
  - 右侧 chevron 旋转。
- 内容按时间顺序渲染 `ExecutionStep`：
  - `Thought` → 思维链卡片（默认收起）；
  - `ToolCall` → 工具卡（默认收起）；
  - `Narrative` → 紧凑 Markdown（`LocalMarkdownCompact`）；
  - `Attachments` → 宿主传入的附件 slot 渲染，不丢过程附件。
- 面板首次出现即折叠；用户展开后保持，不因流式增量强制收起。例外：没有最终正文且过程区含附件时默认展开，避免唯一的可见附件被折叠藏起来。
- 标题栏提供 `contentDescription` / `stateDescription`（“执行过程，已完成 N 步，已展开/已收起”）。
- 面板本身不内滚，交给页面滚动；单个代码块内部滚动。

### 4.4 实时与聚合布局

- `Single` 助手消息：附件（宿主负责）→ `AssistantMessageContent(entry)` → 元数据/复制。没有思考/工具块时保持现有 `MarkdownDocumentView`；有执行块且尚未聚合时逐块渲染实时卡片与叙述，末尾显示光标。
- `Execution`：最终消息附件（宿主负责）→ `AssistantExecutionTurnBody(run, processAttachmentsSlot)`（面板 + 最终回答）→ 元数据/复制。
- 最终回答使用普通 `MarkdownDocumentView`，`isStreaming` 只在最终消息仍流式时传入，光标只出现在真正末块。
- 折叠时过程条目从列表项移除，只保留一个 `Execution` 展示项；展示项/面板展开态使用段身份 `segmentKey`（段内第一条助手消息 key），而不是随最终回答变化的 finalEntry key；宿主用 `animateItem` 处理出现/消失/重排。
- 最终消息没有最终正文时，面板下方只显示元数据，不伪造回答。

### 4.5 图片附件卡

- 以 `attachment.isImage` 分支：
  - **图片**：不显示文件名 chip、不显示「下载」按钮；`Image` 用 `ContentScale.Fit` + `fillMaxWidth` + `heightIn(max = Dimensions.AttachmentImageMaxHeight)` + `clip(RoundedCornerShape(AppRadius.Medium))`，背景 `surfaceContainerLow`；`clickable(role=Button, onClickLabel="查看大图")`，`contentDescription="查看大图"`（可包含文件名供 TalkBack）。
  - 无 `previewBytes` 或解码失败：显示真实占位（图标 + “图片预览不可用” + 本地状态文字），不显示文件名；`localState != UNAVAILABLE` 时仍可点击进入 Lightbox 触发原图加载。
  - 图片下载状态不在列表内重复展示，改为 Lightbox 内就地显示；Snackbar/notice 仍有效。
  - **非图片**：保持文件名 chip、本地/下载状态与「下载」按钮，避免 PDF 失去唯一保存入口。
- 多图按列堆叠，间距 `Dimensions.SpaceSmall`；用户气泡与助手消息共用。
- 复制按钮仍在消息元数据下方，图片点击不触发复制。

### 4.6 全屏 Lightbox（新增 `workbench/AttachmentLightbox.kt`）

- Compose `Dialog`（`usePlatformDefaultWidth=false`、`decorFitsSystemWindows=false`、`dismissOnClickOutside=false`），挂在 `HistoricalAttachmentCard` 内部，四宿主 + 附件库一处生效。
- 窗口：`DialogWindowProvider.window` 透明背景、隐藏系统栏（平台 `WindowInsetsController`，minSdk 31），退出恢复；内容用 `scrim` 背景并处理 `safeDrawing` insets。
- 顶栏：关闭（48dp，`contentDescription="关闭大图预览"`）、文件名（labelMedium，1 行省略）、「保存至本地」（图标+文字，48dp）。
- 图片区：优先 `originalBytes`（READY），否则 `previewBytes`；原图加载中显示预览 + 小进度；失败显示预览 + “原图暂不可用，当前为预览图”。解码在 IO 线程，bounds + 采样，优先 `ImageDecoder` 处理 EXIF 方向，异常回退 `BitmapFactory`，失败显示明确错误而非空白。
- 手势：双指缩放 1f–5f、拖拽平移、双击 1x/2.5x；平移边界按缩放后图片与视口计算，越界回弹；拖拽释放使用 `VelocityTracker` 初速度 + decay 惯性，减少动态时直接吸附；双击动画 `MotionSpecs.snappy/spatial`，减少动态 snap。
- 长按图片弹出 `DropdownMenu`：「保存至本地」/「关闭」；下载中禁用并显示“保存中…”。
- 保存状态（`downloadState`）：IDLE「保存至本地」；DOWNLOADING「保存中…」；SAVED「已保存到下载目录」；FAILED 显示错误并允许“重试保存”。Dialog 会遮住宿主 Snackbar，必须就地反馈。
- 回调沿用 `onDownloadAttachment`；`onRequestOriginal` 可选，为 null 时只用预览图。
- 返回键由 Dialog 默认关闭；关闭后不改变列表滚动位置。

### 4.7 设计令牌与宿主

- 新增 `AppTextStyles.Thought`（13sp / 18.2sp）；新增 `LocalMarkdownCompact`，在 `MarkdownDocumentView` 的段落/标题/列表/代码/块间距中生效；普通正文不受影响。
- `Dimensions` 新增 `AttachmentImageMaxHeight = 320.dp`；工具栏复用 `TopBarContentHeight` / `MinimumTouchTarget`；不写魔数。
- 宿主：
  - `WorkbenchScreen`：`displayItems = rememberTimelineDisplayItems(entries)`；`items(displayItems)`；itemCount/tailKey/freshKeys/高度跟踪改用展示项 key；系统回执行仍走 `CreatedThreadJumpRow`；附件库 historical 保持由 entries 展平；新增原图回调。
  - `FloatingConversationPanel`：同样使用 displayItems，再按 `FloatingMessageWindow` 截取；`reverseLayout` 保持。
  - `BubbleConversationActivity`：LazyColumn 改用 displayItems + `TimelineDisplayRow`，补 `animateItem`。
  - `MessageTimeline`（测试/内部表面）：内部使用 displayItems 与 `TimelineDisplayRow`。
  - `AttachmentLibraryScreen`：`HistoricalAttachmentCard` 增加原图回调；图片同样可打开 Lightbox。
- `assistant-holder` 的助理会话附件 UI 是另一套实现，不在本次范围。

## 5. 动效与无障碍

| 交互 | 规范 | 减少动态 |
|---|---|---|
| 思考/工具/面板展开收起 | `ExpandableRegion`（`MotionSpecs.emphasized` 500ms + fade） | 150ms 线性淡化 |
| chevron 旋转 | `MotionSpecs.spatial` | 直接切换 |
| 列表折叠项出现/消失 | `animateItem` + `timelineItemRise`，仅新项播放 | 50% 时长、无位移 |
| 执行中的点 | 复用 `TypingDots`（1400ms 循环） | 静态点 |
| 流式正文 | 无逐 token 动画，仅锚定滚动 | 同左 |
| Lightbox 双击缩放 | `snappy` 弹簧 | snap |
| Lightbox 拖拽惯性 | `VelocityTracker` + decay，边界回弹 | 释放即吸附 |

- 所有交互元素 ≥48dp；图标有 `contentDescription` 或伴随文字；折叠标题有 `role`、`onClickLabel`、`stateDescription`。
- 流式思考正文/工具状态使用 `liveRegion=Polite`；状态颜色始终配文字/图标。
- 支持系统字号 200%：不用固定高度裁切文本；图片最大高度是约束而非文本容器。

## 6. 失败模式与边界情况

| 情况 | 处理 |
|---|---|
| 流式中断/取消/超时/断流 | 以 `entry.isStreaming=false` 为准；未闭合思考/工具显示“已中断”，自动收起 |
| 消息 FAILED | 卡片按失败/中断呈现；元数据展示 `Agent 处理失败：…` |
| OUTCOME_UNKNOWN / 行仍 STREAMING | 保持“执行中/正在输出”，不自行判定结束；由 StreamHealth/notice 告知 |
| 临时 id → 正式 id | 展示项重建、状态重置；规格记录，不伪造持久化 |
| 解析异常/病态 Markdown | `runCatching` 回退纯文本段落；思维链正文不递归解析执行块 |
| 工具无返回值 | 摘要栏“执行成功/已执行”，详情只有命令；不伪造输出 |
| 工具输出超长 | 纵向 + 横向滚动，完整保留文本，不截断 |
| 只有工具没有最终回答 | 面板折叠显示步骤 + 元数据；不显示空回答占位 |
| 普通多助手消息无执行块 | 不聚合，保持逐条 |
| 审批/系统行打断执行段 | 分段聚合，规格记录 |
| 图片无预览 | 占位 + 状态文字；可尝试原图；不显示文件名 |
| 原图加载失败 | 回退预览并提示，提供“重试加载原图”；不影响保存 |
| 超大/EXIF 旋转图片 | 控制器限制 2048px/24MB；优先 `ImageDecoder`，失败回退 |
| 下载失败/无下载端口 | Lightbox 内错误/重试；无端口隐藏保存动作 |
| 气泡窗口内 Lightbox | Dialog 填满宿主窗口；设备级全屏受限时记录 |
| 多图 | 列堆叠、单图预览；不做左右滑动画廊 |

## 7. 测试与验收

### 7.1 测试

- 解析：未闭合 XML/fenced tool call → `isRunning=true`；闭合 → false；`parseNarrative` 不识别执行块、不递归。
- `ExecutionDisplayTest`：折叠矩阵全部分支、跨条聚合、最终回答定位、步骤计数、失败/中断、审批打断、普通多消息不聚合、缓存命中、异常兜底。
- `ExecutionTimingTest`：假时钟记录、重复 observe 幂等、未观察不产生耗时、格式化。
- `ImagePreviewGeometryTest`：缩放 clamp、平移边界、双击目标、reduce motion。
- `WorkbenchOriginalImageTest`：加载成功/失败/无端口/epoch 失效/缓存上限；既有 `WorkbenchAttachmentPreviewRegressionTest` 继续回归，不改其语义。
- 更新 `ThoughtCardTest`、`ToolCallCardTest`；新增 `ExecutionProcessComposeTest`、`AttachmentImageCardTest`、`MessageTimelineExecutionTest`。
- 新增 `AttachmentImageCardTest`（图片分支无文件名/下载、点击预览、长按保存、PDF 回归）；`AttachmentOnlyTimelineTest` 的 PDF 断言保持不变。
- 回归 `MarkdownRenderingComposeTest`、`MarkdownInteractionComposeTest`、`MessageTimelineStreamingTest`、`WorkbenchLayoutRegressionTest` 等。

### 7.2 验收

1. `cd apps/android && ./gradlew :conversation-ui:testDebugUnitTest --stacktrace` 全部通过。
2. `./gradlew :app:compileFullDebugKotlin --stacktrace` 通过；条件允许时 `:app:assembleFullDebug`。
3. 模拟器（1080×2400 / 420dpi / Android 15）浅色、深色、减少动态实拍；TalkBack 抽查折叠标题、图片按钮、保存按钮。
4. GitHub Actions `check :app:assembleFullDebug` 通过。
5. 独立子 Agent 代码审查；P1/P2 清零后合并。

## 8. 已知取舍与假设

1. 非图片附件保留文件名与「下载」按钮；本次降噪只针对图片。
2. 原图按需加载；失败或未就绪时以 512px 预览回退，并明示“当前为预览图”。
3. Lightbox 只做单图预览；不做左右滑动、下拉关闭、GIF 播放（首帧）、视频预览。
4. 聚合以连续助手消息段为单位；审批/系统回执会打断聚合，以保持审批与执行顺序。
5. 耗时是客户端在卡片被组合期间观察到的真实区间；未观察到时省略，不向后端索取、不持久化。
6. 历史中仍标记为 STREAMING 的行继续按流式呈现（既有语义）；本次不新增超时判定。
7. 助理表面（`assistant-holder`）附件 UI 不在本次范围。
8. 气泡 Activity 被系统嵌入 bubble 窗口时，Lightbox 的“全屏”受宿主窗口范围限制。

## 9. 非目标

- 不改 Gateway 协议、Schema、`*-ports` 契约、领域模型、持久化/数据库、插件运行时。
- 不引入 Coil、ExifInterface、第三方动画/UI 库；不新增字体资源。
- 不做逐 token 打字动画、循环呼吸/发光。
- 不改 Markdown 解析器既有语义（除新增 `isRunning` 与 `parseNarrative`）。

## 10. 实现与验证记录（2026-10-21）

### 10.1 落地清单

| 模块 | 文件 |
|---|---|
| 执行过程模型 | `workbench/TimelineDisplay.kt`（展示项、执行段折叠、缓存、粘滞折叠）、`workbench/ExecutionTiming.kt`、`workbench/ExecutionViews.kt` |
| 卡片与解析 | `markdown/MarkdownModel.kt`、`MarkdownParser.kt`、`MarkdownViews.kt`、`MarkdownThoughtView.kt`、`MarkdownToolCallView.kt`；`theme/AppTypography.kt` |
| 附件与预览 | `model/TimelineAttachment.kt`、`workbench/ImagePreview.kt`、`workbench/AttachmentLightbox.kt`、`theme/DesignTokens.kt`、`state/WorkbenchController.kt`（`requestOriginalImage`） |
| 宿主整合 | `workbench/MessageTimeline.kt`、`WorkbenchScreen.kt`、`FloatingConversationPanel.kt`、`AttachmentLibraryScreen.kt`、`app/.../BubbleConversationActivity.kt` |
| 测试 | `ThoughtCardTest`、`ToolCallCardTest`、`ExecutionDisplayTest`、`ExecutionTimingTest`、`ExecutionProcessComposeTest`、`AttachmentImageCardTest`、`MessageTimelineExecutionTest`、`WorkbenchOriginalImageTest`、`ImagePreviewGeometryTest`、`AttachmentLightboxTest` |

实现中补充/修正了以下规格细节：

0. **终态边界**：复审指出 `OUTCOME_UNKNOWN`/`UNSUPPORTED` 不能被当成完成；现在只有 `COMPLETED`/`FAILED`/`CANCELLED` 强制折叠，其余状态保留逐条流式语义，避免“折叠→展开→再折叠”闪烁。
1. **Hermes 纯文本工具调用**：解析器识别 `调用工具：<name>` 行（同一条消息用「、」连接多个工具名时逐张卡呈现），摘要栏显示「已调用」，展开后说明“Hermes 在消息中未提供命令参数”；Hermes 工具活动日志视为过程叙述，与工具卡一起在最终回答出现后聚合。修复了实机反馈“Hermes 未显示工具卡片、回答结束后也未折叠”。
0. **生成状态驱动折叠**：展示模型接收 `GenerationState`；终态强制结束流式判定，修复“会话进行中 Hermes 工具卡不折叠、切会话重载后才折叠”的问题。
2. **粘滞折叠**：执行段一旦出现过最终正文，后续又出现工具块时不把已折叠面板展开回实时卡片，避免流式过程中阅读位置来回跳（`TimelineDisplayModel.latchedFinalSegmentKey`）。
2. **闭合块之后的未闭合块**：XML 与 fenced 两条路径都继续走执行块入口，避免把「闭合工具/围栏 + 后续未闭合工具/围栏」的最后一段误当普通正文，否则最终回答定位会提前、围栏原文甚至会出现在最终回答里。
3. **长会话快速路径**：展示模型先做廉价预扫描，只有含 `<think`/`<tool_call`/fenced `tool_call`/`$ adb` 的消息才完整解析；普通历史只在所属执行段真的折叠时解析，避免流式增量反复解析整段历史。
4. **最终回答光标**：光标只看 `finalEntry.isStreaming`，而不是「段内任一条仍流式」；粘滞折叠后较早的已完成回答不会常驻光标。
5. **工具摘要栏**：优先显示真实 `summary`（`执行成功（或 summary）`），长文本单行省略，展开详情仍展示完整摘要与输出。
6. **Lightbox 生命周期**：预览 id 与唯一的 `AttachmentLightbox` 提升到宿主级 `AttachmentPreviewHost`；列表项滚动回收、折叠换 key 不会销毁用户正在看的大图，附件库/四个宿主共用同一槽。
7. **耗时语义**：卡片在移出组合时用 `DisposableEffect` 结算当前区间；`ExecutionTimingStore` 由 `rememberExecutionTimingStore` 在会话切换/销毁时清理；59,950ms 正确格式化为「1 分 0 秒」。
8. **原图失败可解释**：`originalError` 映射为「超过 24MB 上限 / 解码失败 / 原件不在本机 / 原件已被替换或删除」，Lightbox 提供「重试加载原图」；没有下载端口时宿主传 `null` 隐藏保存动作。

### 10.2 自动化结果

| 验证 | 结果 |
|---|---|
| `:conversation-ui:testDebugUnitTest` | 57 个测试类 / 542 个用例 / 0 失败 |
| `:app:compileFullDebugKotlin` | BUILD SUCCESSFUL |
| `:app:assembleFullDebug` | BUILD SUCCESSFUL |
| `noVpnSurfaceCheck` | BUILD SUCCESSFUL |
| `git diff --check`、绝对路径/硬编码色值/圆角扫描 | 无命中 |

### 10.3 设备冒烟与限制

- 已重新构建并安装当前 HEAD 的 `app-full-debug.apk` 到 `emulator-5554`（1080×2400 / 420dpi / Android 15），App 正常启动、无崩溃；离线镜像中的普通 Markdown 消息、非图片附件（文件名 + 下载）回归正常。
- **限制**：模拟器当前没有可用的 Gateway（`10.0.2.2:43535` 未监听），附件选择器在控制器未就绪时给出明确提示，因此无法在真机/模拟器上制造带有 `<think>`、`<tool_call>` 或图片附件的新会话。新卡片与新 Lightbox 的交互由上述 Compose/Robolectric 测试覆盖（含聚合、自动收起、长按语义、保存反馈）；真实图片解码（EXIF/2048px）与真实触摸长按仍需在有 Gateway 的环境补一次设备冒烟。
- 未提交 `tmp/screens/` 下的临时截图。
