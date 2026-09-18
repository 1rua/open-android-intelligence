---
status: draft
date: 2026-09-18
title: 对话会话 UI 补全规范：自动标题、思维链卡片、工具调用卡片与命令补全悬浮窗
refines:
  - docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md
related_adrs:
  - ADR-0043
  - ADR-0044
  - ADR-0045
---

# 对话会话 UI 补全规范：自动标题、思维链、工具调用与命令补全

## 1. 范围与背景

本规范细化并补齐 `docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md` 中尚未完全落地的四个核心对话交互能力：

1. **会话标题自动生成与多端同步策略**（Automatic Conversation Title Policy & Sync）：从首条消息即时生成标题，同步至 Gateway，并实现用户手动重命名（`userRenamed`）的永久保护；
2. **思维链呈现卡片**（Chain of Thought / Thought Card）：在时间线中解析大模型 `<think>...</think>` 推理内容，流式阶段展开展示动态跳动点与光标，生成结束或思考闭合后平滑自动折叠；
3. **工具调用与执行结果卡片**（Tool Call & Result Card）：以复合结构化卡片呈现 Agent 调用的外部工具/命令，包括等宽工具名称 Badge、参数代码块、执行状态条及可折叠输出日志；
4. **斜杠命令补全悬浮窗**（Command Autocomplete Popup）：输入框键入 `/` 前缀时在输入栏上方弹出悬浮卡片，整合网关动态目录与离线内置命令兜底，提供精确补全替换。

本规范严格对齐 `fronted-preview/`（含 `Assistant Session - 助手会话.html`、`Workbench - 工作台.html`）以及 `mobile_motion_preview.html` 中的视觉动效与交互规范，领域术语遵循根目录 `CONTEXT.md`。

---

## 2. 领域模型与线协议契约

### 2.1 标题提取与更新策略 (`ConversationTitlePolicy`)

1. **提取算法**：
   - 优先提取首个发送单元中的第一段非空纯文本；
   - 规范连续空白（将连续空格、制表符、换行符折叠为单个空格）；
   - 使用 Unicode 48 字素簇（Grapheme Cluster）安全截断，避免截断 Emoji、组合字符或双字节文字；
   - 无文本且含附件时的降级命名：
     - 语音附件：`语音消息`
     - 屏幕选区：`屏幕选区`
     - 普通文件：文件名（如 `IMG_8921.jpg`）
     - 其他媒体：`附件内容`
   - `/new` 指令不参与标题生成，保留“新对话”。

2. **用户重命名永久权威（User Manual Override Protection）**：
   - 当用户在侧边抽屉或会话顶部手动重命名会话后，本地标记该会话为 `userRenamed = true`；
   - 后续任何由 Agent 建议的标题更新事件（`conversation.title.updated`）均静默丢弃，不得覆盖用户设定的标题；
   - 新建会话时，`userRenamed` 状态初始化为 `false`。

3. **网关线协议契约**：
   - 客户端在生成标题或用户重命名后，发送 HTTP 请求：
     ```http
     PATCH /open-android-intelligence/v2/conversations/{conversationId}
     Content-Type: application/json

     {
       "title": "量子计算与经典物理的核心区别"
     }
     ```
   - 网关校验会话归属并更新 SQLite，向活动 SSE 连接广播事件：
     ```json
     {
       "type": "conversation.title.updated",
       "occurredAt": 1758240000000,
       "payload": {
         "conversationId": "c-123456",
         "newTitle": "量子计算与经典物理的核心区别"
       }
     }
     ```

---

## 3. 思维链（Chain of Thought）卡片规范

### 3.1 语法解析与 AST 映射

主流推理大模型（DeepSeek R1、QwQ、Claude 等）通过 `<think>...</think>` 输出思考过程。在流式增量生成期间，闭合标签 `</think>` 尚未到达。

1. **AST 节点定义**：
   ```kotlin
   data class ThoughtBlock(
       val thought: String,
       val isComplete: Boolean = true,
   ) : TimelineBlock
   ```
2. **Markdown 解析规则 (`MarkdownParser`)**：
   - 若文本包含闭合 `<think>(.*?)</think>`，提取其内容构造 `ThoughtBlock(thought, isComplete = true)`，剩余文本继续递归解析；
   - 若文本包含未闭合 `<think>(.*)$`（流式增量场景），提取其后所有文本构造 `ThoughtBlock(thought, isComplete = false)`；
   - 对 `<think>` 标签前后的正文正常执行 Markdown AST 分词与格式化，确保主回答与思考块隔离。

### 3.2 交互状态机与视觉规范

1. **状态流转**：
   - **流式思考中 (`isComplete = false`)**：卡片默认**展开**。
     - 顶部显示：`AI 正在思考` 辅助说明 + 3 个跳动圆点（`TypingDots`）；
     - 正文末尾：带动态呼吸/闪烁光标（`StreamingCursor`）；
     - 随着文本增量推送，视图自动扩展。
   - **思考完成 (`isComplete = true` 或会话生成结束)**：
     - 自动平滑折叠为紧凑卡片状态；
     - 标题行显示：`已折叠思考过程` 与字数/耗时概览（如 `共 184 字 · 点击展开`）；
     - 右侧显示展开/收起箭头（`ExpandMore` / `ExpandLess`）。
   - **用户手动干预（Manual Override）**：
     - 用户点击折叠条可随时手动切换展开/收起；
     - 一旦用户手动点击，记录该卡片的用户偏好，不再因流式完成而强制重置。

2. **视觉令牌**：
   - 容器底色：`MaterialTheme.colorScheme.surfaceContainerHigh`（深色 `#26332E`，浅色 `#D5E2DC`）；
   - 圆角：`12dp`，外边距：垂直 `4dp`；
   - 思考文字字体：13sp，行高 1.4，颜色 `MaterialTheme.colorScheme.onSurfaceVariant`；
   - 跳动点与光标：`MaterialTheme.colorScheme.primary`（墨绿/月松）。
3. **动效与无障碍（Reduced Motion）**：
   - 当系统开启“减少动态”（`LocalMotionPolicy.current.reduceMotion = true`）时：
     - 禁用 3 个跳动圆点的关键帧位移循环动画，转为静态点；
     - 禁用光标闪烁，转为常亮光标；
     - 展开/收起动画由弹簧过渡替换为立即切换（0ms）。

---

## 4. 工具调用与结构化结果卡片规范

### 4.1 语法解析与 AST 映射

根据 `fronted-preview/Assistant Session - 助手会话.html` lines 814–826 及 `docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md` §6.1 定义，工具调用呈现为复合紧凑卡片。

1. **AST 节点定义**：
   ```kotlin
   data class ToolCallBlock(
       val toolName: String,
       val command: String,
       val output: String? = null,
       val isSuccess: Boolean = true,
       val summary: String? = null,
   ) : TimelineBlock
   ```
2. **匹配模式**：
   - **Fenced 语法**：
     ````markdown
     ```tool_call:adb_shell
     am start -a android.settings.DISPLAY_SETTINGS
     ```
     ````
     或包含 ````tool_call` 代码块；
   - **XML 标签语法**：
     ```xml
     <tool_call name="adb_shell">
       <command>am start -a android.settings.DISPLAY_SETTINGS</command>
       <result status="success">设置应用已成功打开</result>
     </tool_call>
     ```
   - **兼容普通命令代码块**：当代码块首行为 `$ adb shell ...` 且后续紧跟状态提示时，自动聚合成复合工具卡片。

### 4.2 视觉布局与交互规范

1. **卡片结构**：
   - **标题栏（Header）**：
     - 背景色：`#161B22`（固定暗色代码头，提供清晰科技感识别）；
     - 左侧：工具徽标 Badge，如 `[执行命令]`、`[adb_shell]` 或 `[platform_setting]`（`font-family: monospace`，颜色 `#8B949E`，字号 12sp）；
     - 右侧：**复制按钮**（`ContentCopy` 图标 + 12sp 文字），点击将命令复制到剪贴板，并显示 1.5 秒绿色 `Check` 勾选状态与 Toast 提示。
   - **命令内容区（Body）**：
     - 背景色：`#0D1117`，字体：`FontFamily.Monospace`，字号 12.5sp，字体颜色 `#C9D1D9`；
     - 支持长命令横向滚动（`Modifier.horizontalScroll`）；
     - 内边距：水平 12dp，垂直 10dp。
   - **结果指示条（Result Strip）**：
     - 成功状态：背景 `rgba(74, 222, 128, 0.12)`，边框/图标颜色 `#4ADE80`（`CheckCircle` 图标），文字提示“执行成功”或摘要文本；
     - 失败状态：背景 `rgba(248, 113, 113, 0.12)`，边框/图标颜色 `#F87171`（`Cancel` 图标），文字提示错误原因；
     - 若包含详细返回值/日志：提供“查看输出”展开按钮，展开后在等宽文本框中显示，最大高度 240dp，支持纵向滚动。

---

## 5. 斜杠命令补全悬浮窗规范

### 5.1 交互形式与图层层叠

严格对齐 `mobile_motion_preview.html` lines 1890–1954 中 `.command-autocomplete-popup` 的设计：

1. **悬浮定位（Floating Overlay）**：
   - 位于消息输入框（`ComposerBar`）**正上方**，底部距离输入框顶部 `8dp`；
   - 水平方向铺满输入区域内边距（左右各 `16dp`）；
   - 最大高度限定为 `240dp`，超出内容纵向滚动（`LazyColumn`）；
   - **关键原则**：悬浮窗作为 `ComposerBar` 上方的覆盖层（Overlay），**绝对不挤占时间线视口高度**，不引起消息列表上下跳动。
2. **键盘与 IME 避让**：
   - 悬浮窗锚定在输入区域容器顶部，随输入框的 `WindowInsets.ime` 上升而同步上升，始终紧贴软键盘上沿的输入栏。

### 5.2 命令目录合并与离线兜底

1. **目录合并策略**：
   - 优先使用 Gateway 下发的 `AgentCommandCatalog`；
   - 当网关处于断网、目录为空或加载中时，自动合并内置标准通用命令：
     | 命令 | 参数提示 | 描述 |
     |---|---|---|
     | `/models` | `[provider]` | 切换或查看活动大模型与参数 |
     | `/status` | | 查看当前网关连通性、时延与配对状态 |
     | `/review` | `[diff]` | 审查代码变更、规范或当前文档 |
     | `/gateway` | `<url>` | 切换或添加连接的 Agent Gateway |
     | `/clear` | | 清空当前时间线临时渲染状态 |
     | `/help` | `[command]` | 查看所有可用指令与使用指南 |
     | `/new` | | 结束当前会话并创建全新对话线程 |
2. **筛选匹配**：
   - 当草稿以 `/` 开头时触发；
   - 取第一个空格前的子串作为搜索前缀 `queryPrefix`（如 `/st`）；
   - 对全部命令进行忽略大小写前缀匹配；匹配为空时显示友好提示“未找到匹配命令，回车可原样发送”。
3. **补全填入行为**：
   - 用户点击任一命令项（或通过硬件键盘方向键选择后按 Enter/Tab）：
   - 将输入框中当前键入的 `/xxx` 精确替换为选中命令，并自动追加一个空格（例如输入 `/st` 点击后变为 `/status `）；
   - 光标移动至末尾，关闭悬浮窗，供用户补充参数；
   - 对于无参数命令（如 `/new`、`/clear`），允许用户直接发送。

---

## 6. 物理动效、色彩令牌与无障碍要求

### 6.1 动效参数（Motion Specs）

- **悬浮窗展开与退场**：
  - 进入：`fadeIn(tween(150)) + slideInVertically(spring(dampingRatio = 0.82f, stiffness = 380f)) { it / 4 }`
  - 退出：`fadeOut(tween(100)) + slideOutVertically(tween(100)) { it / 4 }`
- **思维卡片折叠/展开**：
  - 高度过渡使用 `animateContentSize(spring(dampingRatio = 0.85f, stiffness = 400f))`；
  - 折叠箭头旋转：`animateFloatAsState(if (expanded) 180f else 0f)`.

### 6.2 无障碍（Accessibility）

- 所有卡片与悬浮项触摸靶点保持至少 `48×48dp`；
- 折叠卡片提供语义化 `contentDescription = if (expanded) "收起思考过程" else "展开思考过程"`；
- 复制按钮提供无障碍状态反馈并支持 TalkBack 单击播报。

---

## 7. 模块演进与影响范围

| 模块 | 文件 | 变更职责 |
|---|---|---|
| `:conversation-domain` | `ConversationTitlePolicy.kt` | 空白规范化、Unicode 48 截断、附件名称兜底与单元测试覆盖 |
| `:conversation-domain` | `ConversationPorts.kt` | `ConversationRepository.updateTitle(...)` 接口定义 |
| `:gateway-client` | `ConversationClient.kt` | 增加 `updateConversationTitle` 实现 `PATCH /v2/conversations/{id}` |
| `:conversation-data` | `GatewayConversationRepository.kt` | 实现 `updateTitle` 并桥接客户端调用 |
| `:conversation-ui` | `markdown/MarkdownModel.kt` | 新增 `ThoughtBlock` 与 `ToolCallBlock` AST 节点 |
| `:conversation-ui` | `markdown/MarkdownParser.kt` | 增加 `<think>` 与工具调用语法解析，支持流式容错 |
| `:conversation-ui` | `markdown/MarkdownViews.kt` | 实现 `MarkdownThoughtBlockView` 与 `MarkdownToolCallView` 组件 |
| `:conversation-ui` | `workbench/CommandMenu.kt` | 重构为悬浮式 `CommandAutocompletePopup`，内置兜底标准命令 |
| `:conversation-ui` | `workbench/WorkbenchScreen.kt` | 调整层叠结构使命令菜单浮动于 Composer 上方 |
| `:conversation-ui` | `state/WorkbenchController.kt` | 首条消息触发标题自动生成，接入 `userRenamed` 保护逻辑 |
| 网关服务 | `core.py` | 增加 `update_title` 处理、PATCH 路由及全量兜底命令目录 |

---

## 8. 验证准则

1. **标题策略测试**：覆盖纯文本截断、空白折叠、语音/选区/图片/文件附件命名降级、`/new` 忽略场景；
2. **AST 解析测试**：覆盖闭合思考块、流式未闭合思考块、多行工具调用块、普通 Markdown 混合嵌套场景；
3. **悬浮窗与命令替换测试**：验证在软键盘弹出时悬浮窗不错位、点击命令精确替换 `/` 前缀且不覆盖后续参数；
4. **编译与回归测试**：运行 `./gradlew testDebugUnitTest` 确保全部单测通过，无 UI 渲染退化。

