---
status: accepted
date: 2026-10-21
title: 对话消息 Markdown 渲染与交互修复：链接、公式、任务列表、嵌套、HTML、逐条复制
refines:
  - 2026-08-29-android-conversation-assistant-ui-design.md
  - 2026-09-18-conversation-session-ui-completion-spec.md
authorization: 2026-10-21 用户直接要求修复移动端对话 Markdown 渲染与点击交互，并新增单条消息复制
---

# 对话消息 Markdown 渲染与交互修复

## 1. 范围与硬约束

- **只改渲染层**：`apps/android/conversation-ui` 的 Markdown 模型、解析器、行内渲染、Compose 视图与
  `MessageTimeline` 的消息操作区；不改 Gateway 协议、消息存储、发送链路与任何后端。
- **不替换渲染引擎**：继续使用项目自研的 Markdown 解析器 + Compose 视图，不引入 Markwon/CommonMark
  等第三方 Markdown 引擎；公式排版用本地 KaTeX（见 §4.4）。
- **不联网**：公式 WebView 只能读 `assets/katex/`，禁止任何网络请求与 URL 跳转。
- **不伪造状态**：公式失败必须显示原始 LaTeX 与「公式渲染不可用」描述，绝不留白或假装成功。
- **保持既有守卫**：`noVpnSurfaceCheck` 等静态门禁、`MaterialTokenGuardTest` 与全部既有 Compose 测试继续通过。

## 2. 现状诊断

在 Pixel 尺寸模拟器（1080×2400 / 420dpi，Android 15）上对修复前构建取证，同一段探针 Markdown 的表现：

| # | 用户可见现象 | 证据 | 根因 |
|---|---|---|---|
| 1 | `[文本](url)` 有链接样式但点击无反应；`<https://…>` 把尖括号原样显示、裸链接不变蓝 | `before-light.png` 第 1 段 | 行内渲染只做了样式，没有注册 Compose 链接注解；没有识别自动链接 |
| 2 | `$E=mc^2$`、`$$\int…$$` 原样显示，`$` 还会把整段拆行 | `before-light.png` 第 2 段 | 解析器完全没有数学公式概念，`$` 被当普通字符 |
| 3 | `- [x]` / `- [ ]` 显示成字面方括号 | `before-light.png` 第 3 段 | 列表项模型没有任务状态字段 |
| 4 | 多级列表全部顶格、有序子项与父项同级；`>>` 把 `>` 原样显示 | `before-light.png` 第 4、5 段 | 列表只记录文本不记层级；引用是单层扁平解析 |
| 5 | `<kbd>`、`<sub>`、`<sup>`、`<mark>` 被转义成字面文本 | 探针页 HTML 段（修复前截图下半屏） | 行内解析把 `<` 一律转义，没有安全白名单 |
| 6 | 每条消息没有复制入口，长按只能选当前屏幕可见文字 | 修复前全部截图 | `MessageTimeline` 没有消息级操作 |

同时，修复过程中用 Chrome 调试协议复核公式尺寸时发现第 7 个隐藏缺陷：公式 WebView 关闭了
`useWideViewPort`，`visualViewport.scale≈0.381`（= 1/2.625），公式实际只画到预期字号的 1/2.6，
而占位又按 density 放大，表现为「占位很大、公式很小」。该缺陷在首轮截图验收中被拦下并已修复（§4.4）。

> 对照截图见 `assets/2026-10-21-markdown-rendering/`：`before-light.png`、`after-light.png`、
> `after-dark.png`、`after-copy-feedback.png`。

## 3. 模型与解析

### 3.1 块模型（`markdown/MarkdownModel.kt`）

- `TimelineBlock.Blockquote` 从「一行文本」改为 `blocks: List<TimelineBlock>`，引用内部可再放段落、
  列表、代码块，从而支持任意层级递归。
- `MarkdownListItem` 增加 `level`、`taskState`、`number`；`taskState = NONE/UNCHECKED/CHECKED`，
  有序列表的 `number` 由解析器按层级计数，不在视图层猜。
- 新增 `TimelineBlock.MathBlock(latex)`；`TableAlignment` 独立成枚举。

### 3.2 列表与引用（`markdown/MarkdownParser.kt`）

- **缩进宽度自适应**：先收集本段所有列表行的缩进，取最小正差作为「一级缩进」（两空格、四空格、
  Tab 都能识别，兜底 2），`level = (offset + step/2) / step`，上限 6 级。
- **任务项**：`^\[([ xX])\]\s*(.*)$` → `taskState`，`- [X]` 与 `- [x]` 等价。
- **有序编号**：按层级维护计数器，出现更浅层级时重置更深层级，`1.` 可续写也可重复。
- **引用递归**：逐行剥掉一层 `>` 后重新走「块解析」，因此 `>>` 天然得到两层 `Blockquote`，
  代码围栏在引用里也能正确闭合（围栏正则按行锚定，不会被外层提前吞掉）。
- 流式输出时未闭合的 `$$`、围栏代码、引用都有稳定的中间态，不会把整段正文吞掉。

### 3.3 行内解析（`markdown/MarkdownInline.kt`）

- **安全链接**：只接受 `http/https/mailto/tel`，`isSupportedLinkUrl` 统一判定；不支持的 scheme
  退化成可读文本，不产生可点击注解。
- **链接注解**：使用 `LinkAnnotation.Clickable(tag, styles, linkInteractionListener)` +
  `AnnotatedString.Builder.addLink`，由 Compose `Text` 原生命中测试处理点击，不自己写
  `pointerInput` 命中逻辑；回调在语言层注入，测试可断言。
- **自动链接**：`<https://…>`、`<user@example.com>`（转 `mailto:`）与裸 `https://…` 都识别；
  裸链尾部的中文标点、右括号会被裁掉（`，。；：！？、）》】` 等）。
- **数学占位**：`$…$`、`$$…$$` 生成 `MarkdownInlineMathSpan(id, latex)`，正文里替换为
  inline content 占位；货币写法 `$5`、`$10` 不误判（未闭合或纯数字不进入公式）。
- **安全 HTML 白名单**：`a/strong/b/em/i/u/s/del/strike/code/kbd/mark/small/sub/sup/br/span`，
  逐标签映射样式；`<a href>` 走同一套链接校验；`<script>`、`<img>` 等不在白名单一律按文本显示。
- **实体解码**：命名实体（`&nbsp; &amp; &lt; &gt; &quot; &copy;` 等）与数字实体统一解码，
  上限 12 个字符防止把长串误当实体。
- **强调**：`**粗**`、`*斜*`、`***粗斜***`、`~~删除~~` 用「配对标记 + 跳过代码段」的扫描，
  不会跨越 inline code 误配对。

## 4. 渲染与交互

### 4.1 统一行内文本（新增 `markdown/MarkdownInlineText.kt`）

段落、标题、引用、列表项、表格单元格、任务项全部走同一个 `MarkdownInlineText`：
构建 annotated string → 为数学公式建 `InlineTextContent` → 流式光标用 inline content 追加 →
外层 `SelectionContainer` 允许长按选择。链接默认用 `LocalUriHandler.openUri` 打开系统浏览器，
失败时 Toast「无法打开链接」，不静默失败。

### 4.2 任务列表与嵌套列表（`markdown/MarkdownViews.kt`）

- 复选框用 Material `Icons.Filled.CheckBox` / `CheckBoxOutlineBlank`，`contentDescription`
  为「已完成」/「未完成」；已完成项文字加删除线并使用弱化颜色。
- 每级缩进 `Dimensions.ListIndent`（16dp），标记列宽 `Dimensions.ListMarkerWidth`（20dp），
  不再用魔法数字；一级实心点、更深层级空心点，有序项显示解析出的序号。

### 4.3 多级引用

`MarkdownBlockquoteView` 递归渲染，左侧竖线透明度随层级 `0.85 - depth*0.18` 递减，内容逐级内缩；
引用内段落使用 `onSurfaceVariant` + 斜体，和正文区分但不抢注意力。

### 4.4 数学公式（新增 `markdown/MarkdownMath.kt` + `assets/katex/`）

- **离线 KaTeX 0.16.22**（MIT）：`katex.min.js`、`katex.min.css`、20 个 woff2、LICENSE、
  带逐文件 SHA256 的 README，共约 548 KiB；`loadDataWithBaseURL("file:///android_asset/katex/", …)`。
- **安全**：`blockNetworkLoads=true`、`allowContentAccess=false`、全部 URL 跳转拦截、
  不注册 `JavascriptInterface`、`trust:false`；LaTeX 源码经 JS 字符串转义后注入。
- **尺寸协议**：Kotlin 用 `evaluateJavascript` 主动测量，页面回传
  `nw,nh,sw,sh,failed,settled`；字体加载完成后（`document.fonts.ready`）再测一次。
  行内占位用 `InlineTextContent`，块级用 40dp 起占位，实测后精确回填；尺寸缓存 LRU 256。
- **单位约定**：WebView 必须 `useWideViewPort=true` 让 `initial-scale=1` 生效，此时
  1 CSS px = 1dp；`buildKaTeXHtml` 收 CSS px（= 物理 px / density），测量结果乘 density 得物理 px。
  关闭 wide viewport 会让页面被整体缩到 1/density（实测 0.381），公式比正文小一半以上。
- **深浅色**：公式颜色取 `MaterialTheme.colorScheme.onSurface`、错误色取 `error`，
  背景透明；主题变化时重建 WebView，尺寸缓存不失效。
- **失败兜底**：4 秒内测不到尺寸或 KaTeX 初始化失败 → 等宽字体显示原始 LaTeX +
  「公式渲染不可用」，块级额外显示一行说明。

### 4.5 单条消息复制（`workbench/MessageTimeline.kt`）

- 用户气泡在气泡下方右侧、助手消息在元信息下方，各有一个紧凑的「复制」按钮
  （图标 + 文字，`heightIn(min = Dimensions.SpaceXLarge)`），不遮挡正文。
- 点击写入系统剪贴板的是**消息原始文本**（含 Markdown 源码，公式保留 LaTeX），不是渲染后的可见文字。
- 反馈内联完成：按钮立即变为「✓ 复制成功」并高亮 1.6 秒后恢复，不弹 Toast/Snackbar，
  （实拍见 `assets/2026-10-21-markdown-rendering/after-copy-feedback.png`），
  因此在 `WorkbenchScreen`、`FloatingConversationPanel`、`BubbleConversationActivity` 三个宿主里行为一致；
  反馈区域带 `liveRegion = Polite`，读屏用户也能听到状态变化。

## 5. 验收与测试

| 验收项 | 自动化 | 设备实机 |
|---|---|---|
| 链接可点击、打开系统浏览器 | `MarkdownInlineTest`：链接注解存在、回调触发、scheme 白名单 | 点击 OpenAI/自动链接/裸链均跳到 Chrome |
| 行内与块级公式 | `MarkdownMathTest`（HTML、转义、颜色、缓存、尺寸解析）+ Robolectric 冒烟 | 浅色/深色截图，字号与正文一致 |
| 任务列表 | `MarkdownParserNestedTest` 状态与层级；`MarkdownInteractionComposeTest` 复选框语义 | 勾选/未勾选图标、删除线 |
| 嵌套列表 | `MarkdownParserNestedTest` 2/4 空格、编号重启、流式前缀 | 三级缩进逐级 +16dp，有序子项对齐 |
| 多级引用 | `MarkdownParserNestedTest` 三层引用、引用内代码围栏 | 三条级联竖线、无裸露 `>` |
| HTML 白名单 | `MarkdownInlineTest` 键帽/上下标/高亮、危险标签按文本 | `Ctrl`+`C` 键帽、H₂O 下标、x² 上标、高亮底色 |
| 逐条复制 | `MarkdownInteractionComposeTest` 剪贴板内容与反馈 | 点击变「复制成功」，1.6s 恢复，剪贴板为原文 |

- 纯 JVM/ Robolectric：`cd apps/android && ./gradlew :conversation-ui:testDebugUnitTest`
- 静态门禁：`./gradlew noVpnSurfaceCheck`
- 设备证据：`tmp/screens/probe-fixed-*.png`（对话内验证产物，不提交）；仓库内长期对照见
  `assets/2026-10-21-markdown-rendering/`。

## 6. 已知取舍与后续项

1. **任务项读屏语义**：复选框是 Compose 图标 + `contentDescription`，TalkBack 能读出完成状态，
   但 uiautomator 里 `checkable=false`；后续可给整行加 `stateDescription`/`toggleable` 语义。
2. **键帽与高亮视觉**：`<kbd>` 与 `<mark>` 目前底色接近，后续可给键帽加圆角描边以区分。
3. **公式首帧**：WebView 测量前的占位按 1.5em 估算，正常设备 50–300ms 内被真实尺寸覆盖；
   极慢设备仍可能看到一次轻微行高变化。
4. **深色切换瞬间**：公式 WebView 会重建，尺寸不闪但内容有 1–2 帧空白；如需零闪，
   可改为复用 WebView + CSS 变量换色。
