# Android 对话时间线工具调用配对、提示词过滤与统一折叠动效实施方案

跟踪 Issue #250。开发基线为 `origin/dev`（`358896773cf9ee5633e3d8a6f0fbc1a9ea07f968`），实施分支为 `feature/android-timeline-tool-motion`。

## 1. 背景与目标

本方案在不修改后端协议与服务端、不重设计视觉配色体系、不改动无关会话业务及权限/安全裁决的前提下，一次性解决 Android 端对话时间线的 5 项核心问题与体验升级：

1. **全后端工具调用结果配对修复**：覆盖 DSH Harness V3 / Claude Code / Codex / OpenCode / Hermes ACP / OpenClaw 等后端，以配对键统一回写工具结果，消除「结果未知（未收到工具结果）」误报与底部孤立「`[tool]` 已完成 / 无输出」卡片；仅当确实未返回结果时在原调用卡标注「未返回结果」。
2. **回合结束折叠中间过程**：回合结束并产出最终回答后，将思考（thinking）与全部工具调用折叠进一张复用现有工具卡片视觉与动效的大卡片，标题为「N 次工具调用」。
3. **工具卡片默认折叠与入场显现动效**：工具卡片默认折叠，新出现时采用从左到右展开显现动画（复用统一动效曲线），支持 Reduce Motion 降级为无位移淡化，避免历史加载、列表回收与状态刷新重播。
4. **上下文提示词泄漏单一收口**：修复发送、流式回传、刷新与历史重载后上下文注入内容展示在聊天界面的问题，仅保留系统提示词合法范围，在投影/过滤层统一收口。
5. **统一动效令牌与工作区折叠动效**：将工具卡片展开/收起曲线抽离为单一来源的可复用动效令牌，供需求 2 / 3 / 5 共享，并为会话列表工作区（Workspace）折叠/展开补上平滑动画与 Reduce Motion 降级。

---

## 2. 分项技术设计

### 需求 1：工具调用结果配对单一权威收口

- **领域层唯一归一化与非破坏性合并（`apps/android/conversation-domain`）**
  - 在 `NativeExecutionStep.kt`（及必要的辅助文件）统一配对键规范化 `normalizeToolCallId(raw)`（裁剪空白、拒绝空串）。
  - 统一 `applyNewerSnapshot()` 与 `mergeWith()` 的字段保留语义：高 revision 的结果快照绝不以缺省 `"tool"`、`""` 或 `null` 覆盖原调用已有的有效 `toolName`、`arguments` 或 `output`。
- **数据投影层统一绑定（`apps/android/conversation-data/.../RemoteTimelineProjection.kt`）**
  - 统一全后端唯一 ID 提取函数 `extractToolCallId`：忽略空字符串（消除 `JsonValue.text()` 返回 `""` 导致的短路），严格优先提取 `callId` / `toolCallId` / `tool_call_id` / `call_id` / `tool_use_id` / `toolUseId` 及嵌套路径（`toolCall.*` / `tool_call.*` / `call.*` / `toolUse.*` / `result.*` / `item.*`），再回退 `stepId` / 非结果帧 `id`，消除 `assistant-stream`、ACP 与通用投影三处分叉。
  - 引入乱序结果缓冲 `pendingResults[canonicalId]`，维护 `knownTools[canonicalId]` 与 `toolOwnerMessageId[canonicalId]`（包含历史 `history-tool:$id` 行及 `assistant-stream end` 迁移）：
    - 收到调用时立即合入已缓冲结果；
    - 收到结果时仅按配对键回写原调用与原 owner 行，找不到调用先入 `pendingResults`，**禁止凭空构造 `[tool] 已完成 / 无输出` 孤立卡片**；
    - 已回写宿主行或历史态已落行时，不再无条件重复发出 `ExecutionStepsUpdated`，统一 `generationId` 口径（消除 `history-turn:` 与 `remote-execution:` 分裂）。
  - 补齐多后端细节修复：
    - **ACP（Claude Code / OpenCode / Hermes ACP 等）**：修复 `recoverAcpCatchUp` 在已有正文时跳过未完成 `tool_call_update` 的问题；跨轮或迟到 `tool_call_update` 优先回写 `toolOwnerMessageId`；解析 OpenAI/Hermes `assistant.tool_calls[]`，对 `role="tool"` + 顶层 `tool_call_id` 消息先按配对键回写结果再按元消息隐藏。
    - **Codex**：支持 `item.call_id` 配对 `functionCall` 与 `functionCallOutput`；提取 `contentItems`、`stdout`/`stderr`、`text`/`formattedOutput` 及 ACP 嵌套文本块，消除有结果却误显「无输出」；`declined` 判为失败；`hookPrompt` / `contextCompaction` 不再误入 `execution()` 变成工具卡片。
- **状态层与展示层去重与文案（`WorkbenchController.kt`、`TimelineDisplay.kt`、`MarkdownToolCallView.kt`）**
  - `WorkbenchController.insertNativeExecutionRows()` 与 `TimelineDisplay.kt` 统一复用领域层合并规则，并优先按 `stepId` 原位更新，避免临时 `generationId` 切换复制底部卡片。
  - 确实未收到工具结果（回合终态仍为 `UNKNOWN`）时，在原调用卡片内显式标注「未返回结果」（文案收口至 `apps/android/ui-resources/src/main/res/values/strings_conversation.xml`，替换 `MarkdownToolCallView.kt` 硬编码中文并同步更新测试）。

### 需求 2：回合结束后折叠中间过程

- **折叠触发条件（`TimelineDisplay.kt`、`ExecutionViews.kt`）**
  - 流式进行中（`run.isStreaming == true`）：保持实时平铺思考卡、工具卡、中间叙述与过程附件，不套外层大卡片。
  - 回合结束且已产出最终回答（`!run.isStreaming`，兼顾纯原生工具段被拆分为 `[Execution, Single(final)]` 与内嵌 `finalBlocks` 两种形态，兼容既有结构契约）：将该回合的全部过程步骤（思考 + 工具调用 + 中间叙述/过程附件）折叠收敛进一张 `M3ExpandableExpressiveCard` 大卡片。
- **标题与视觉动效复用**
  - 当回合包含工具调用（`toolCallCount >= 1`）时，大卡片标题为「`N 次工具调用`」（资源 key：`conv_turn_process_tool_calls`）；当 `toolCallCount == 0` 仅有思考步骤时，**绝不显示「0 次工具调用」**（单条思考保留单张折叠思考卡，多条则使用「思考过程」标题）。
  - 容器配色、描边、圆角（`12dp ↔ 16dp`）、展开箭头与内容展收直接复用现有工具卡片视觉与统一动效令牌（`ExpandableRegion` + `rememberExpandChevronRotation`）。
  - 使用 `rememberSaveable(run.segmentKey)` 记忆用户手动展开态；展开后按原始发生顺序呈现全部思考与工具卡片，子卡默认折叠且禁用入场动画重播。

### 需求 3：工具卡片默认折叠与入场显现动效

- **默认状态调整（`MarkdownToolCallView.kt`）**
  - 工具卡片默认折叠（移除 `running == true` 时自动展开的默认行为），用户点击后再展开/收起。
- **从左到右展开显现（统一令牌 + Reduce Motion 降级）**
  - 通过统一组件 `HorizontalDisclosureReveal`（消费 `MotionSpecs.disclosureRevealProgress` 与 `MotionSpecs.disclosureFade`）在绘制层执行左→右圆角裁切显现并叠加淡入（RTL 自动镜像），保持首帧测量宽度稳定、避免文字挤压重排与滚动抖动。
  - 当 `LocalMotionPolicy.current.isReduced == true` 时跳过水平裁切/位移，降级为无位移线性淡化（`150ms LinearEasing`）。
- **防重播与防闪烁保障（`ToolCardRevealTracker`）**
  - 按会话生命周期维护 `LocalToolCardRevealTracker`（记录已展示工具步 `stepKey`）：
    1. 历史/快照一次性加载时批量登记已有 `stepKey`，不播入场动画；
    2. 仅对实时新出现的工具步播放一次入场动画，`LazyColumn` 滚出再滚回或步骤从 `RUNNING` 刷新为 `COMPLETED` 时不重播；
    3. 回合折叠大卡片展开后的内部子卡显式传 `animateEntrance = false`，避免二次播动画；
    4. 修复 `AssistantTimelineView.kt` 中误读 `.reduceMotion` 的位置，全端统一读取 `LocalMotionPolicy.current.isReduced`。

### 需求 4：上下文提示词泄漏单一收口

- **规则层唯一真源（`apps/android/conversation-domain/.../ContextInjectionFilter.kt`）**
  - 修复 `parseOpenTagAt()` 在空格处截断标签名的问题，完整识别多词 XML 标签（如 `<permissions instructions>...</permissions instructions>`）。
  - 补齐真实 Agent 上下文注入标签（`<collaboration_mode>`、`<app-context>`、`<skill>`、`<turn_aborted>`、`<subagent_notification>`、`<ide_opened_file>`、`<ide_selection>`、`<fast_mode_info>`、`<local-command-stderr>`、`<antml:...>`/`<antml_...>`、`<mcp_instructions>`、`<personality_spec>` 等）及无标签 `Caveat:` / 会话续接提示 / `# AGENTS.md instructions` 变体识别。
  - 将严格机器注入标签的中段剥离扩展至用户消息（非 Markdown 围栏/行内代码保护区），解决携带附件尾注或后端中段插入 `<system-reminder>`/`<environment_context>` 后漏网的问题；解包单层正文容器后继续剥离内层残留注入。
  - 将 `SystemPromptSanitizer.kt` 彻底收口为对 `ContextInjectionFilter` 的纯委托，消除任何独立正则分叉。
- **全链路统一入口与合法范围保护**
  - `RemoteTimelineProjection.kt`：检查并规范化 `data.source.kind`（如 `system`/`hook`/`compaction`/`context`/`injected`/`schedule`/`heartbeat`/`subagent`）、内容块级 `isMeta`/`hidden`/`visibility`/`synthetic`/`injected` 与 Codex `hookPrompt`/`contextCompaction`/`userMessage.input`，离站即经由 `ContextInjectionFilter.projectMessage()` 投影。
  - `EncryptedConversationMirror.kt` / `MirroredConversationRepository`：底层保留原始记录，统一所有读取、事件流出与 `refreshCompleteHistory` 的会话标题/时间线投影出口。
  - `WorkbenchController.kt`：收敛为单一 `ingestMirroredMessage()` 写入闸门，覆盖 `openThread()`、`reloadTimeline()`、`restoreLocalTimeline()`、`applyConversationEvent()` 与乐观发送落库，彻底消除 `openThread()` 等单点漏过滤。
  - **严格保留合法范围**：本地合成系统提示（`isLocalSyntheticHint = true`，含 `/new` 跳转回执、审批卡、执行事实行）、代码块与反引号内文本、用户自然语言讨论标签、常规日期与日志文本、以及剥离注入后仍含附件/工具/思考的有效消息。

### 需求 5：统一动效令牌与工作区折叠动效

- **单一来源动效令牌（`MotionSpecs.kt`、`AppTransitions.kt`、`ExpandableRegion.kt`）**
  - 在 `MotionSpecs.kt` 抽离折叠/揭示语义令牌，对齐工具卡片现有无过冲曲线并满足物理不变量：
    - `DisclosureSpatialSpring = MorphSpring`（`dampingRatio = 1.0f, stiffness = 700f`）
    - `DisclosureEffectsSpring = EffectsDefaultSpring`（`dampingRatio = 1.0f, stiffness = 1600f`）
    - `disclosureSize<T>(reduced, visibilityThreshold)`、`disclosureRevealProgress(reduced)`、`disclosureFade<T>(reduced)`、`disclosureChevron(reduced)`。
  - 在 `AppTransitions.kt` 新增 `disclosureExpandVertical(reduced)` 与 `disclosureCollapseVertical(reduced)`（`reduced == true` 时纯无位移 `fadeIn/fadeOut`，不再叠加高度位移），由 `ExpandableRegion` 与 `HorizontalDisclosureReveal` 消费，供需求 2 / 3 / 5 共同调用，Composable 层零手写时长/曲线/插值。
- **工作区折叠/展开动画（`WorkspaceConversationBrowser.kt`）**
  - 头部箭头改为单图标 + `rememberExpandChevronRotation`（消费 `MotionSpecs.disclosureChevron(reduced)`）。
  - 将 `LazyColumn` 项间距改为 item 内部 padding 以消除折叠至 `0dp` 时的残留空隙；按工作区维护过渡可见状态，折叠过程中保留独立 `item`/`items(key = "ws-row:$wsKey:$id")` 并通过 `ExpandableRegion` 收起，动画收敛后移出子项，同时配合 `Modifier.animateItem(placementSpec = MotionSpecs.disclosureSize(reduced))` 平滑推移相邻工作区。
  - 完整保留 `LazyColumn` 虚拟化性能、点击交互与基于 `ws-row:` key 的长按拖拽排序。

---

## 3. 并行分工与无冲突文件边界

任务拆分为两条并行开发子工作区，基于 `feature/android-timeline-tool-motion` 分叉并在完成后线性 rebase 回合：

| 子工作区 / 分支 | 负责范围 | 主要修改文件（相对路径） |
|---|---|---|
| `.worktrees/attm-pipeline`<br>`feature/attm-pipeline` | 需求 1（领域+投影+控制器）与需求 4（过滤+镜像+控制器） | `apps/android/conversation-domain/.../NativeExecutionStep.kt`<br>`apps/android/conversation-domain/.../ContextInjectionFilter.kt`<br>`apps/android/conversation-domain/.../SystemPromptSanitizer.kt`<br>`apps/android/conversation-data/.../RemoteSessionCarrier.kt`<br>`apps/android/conversation-data/.../RemoteTimelineProjection.kt`<br>`apps/android/conversation-data/.../RemoteConversationRepository.kt`<br>`apps/android/conversation-ui/.../WorkbenchController.kt`<br>`apps/android/app/.../EncryptedConversationMirror.kt` 及对应单测 |
| `.worktrees/attm-motion`<br>`feature/attm-motion` | 需求 5（统一令牌+工作区动效）、需求 3（工具卡折叠+入场）、需求 2（回合折叠大卡片）及 UI 文案资源化 | `apps/android/conversation-ui/.../MotionSpecs.kt`<br>`apps/android/conversation-ui/.../AppTransitions.kt`<br>`apps/android/conversation-ui/.../ExpandableRegion.kt`<br>`apps/android/conversation-ui/.../WorkspaceConversationBrowser.kt`<br>`apps/android/conversation-ui/.../TimelineDisplay.kt`<br>`apps/android/conversation-ui/.../ExecutionViews.kt`<br>`apps/android/conversation-ui/.../MarkdownToolCallView.kt`<br>`apps/android/conversation-ui/.../TimelineScaffold.kt` / `MessageTimeline.kt` / `WorkbenchScreen.kt`<br>`apps/android/app/.../BubbleConversationActivity.kt`<br>`apps/android/assistant-holder/.../AssistantTimelineView.kt`<br>`apps/android/ui-resources/src/main/res/values/strings_conversation.xml` 及对应单测 |

> 冲突规避：`TimelineDisplay.kt`、`MarkdownToolCallView.kt`、`strings_conversation.xml` 与 `i18n-baseline.json` 统一归口 `attm-motion` 侧按契约落地，避免与 `attm-pipeline` 并发修改同一文件。

---

## 4. 验证与验收计划

1. **静态与文案门禁**：`npm run lock:check`、`python3 tools/check_hardcoded_chinese.py`、`python3 tools/test_string_resources_integrity.py`、`./gradlew noVpnSurfaceCheck`（新增 `app/src/test` 类同步登记 `apps/android/ci/app-regression.txt`）。
2. **模块定向与联调单测（对齐 CI 使用 JDK 21）**：
   - `./gradlew :conversation-domain:testDebugUnitTest :conversation-data:testDebugUnitTest :conversation-ui:testDebugUnitTest :ui-resources:testDebugUnitTest -Poai.testParallelForks=1 --stacktrace`
   - `./gradlew :app:compileFullDebugKotlin :app:compileFullDebugUnitTestKotlin --stacktrace` 及受影响 App 用例（`ContextInjectionMirrorAndNotificationTest`、`FullTurnInteractionE2ETest`、`ExpressiveMotionNavigationTest`、`MaterialTokenGuardTest` 等）。
3. **集成自检与评审**：两支子分支 rebase 合入 `feature/android-timeline-tool-motion` 后执行 `npm run test:fast` 与 `npm run test:pr`，并由独立评审员对照 `origin/dev` 与 Issue #250 完成双轴代码审查后再转正式 PR。
