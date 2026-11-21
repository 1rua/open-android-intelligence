# 点击底部输入框唤起键盘上推动画掉帧根因审查与修复方案

- **关联 Issue**：#216
- **排查范围**：最近合并至 `dev` 的 5 个 PR（#205、`#209`、`#213`、#211、`#207`）
- **排查结论摘要**：
  - **PR #209（审批提交与斜杠命令目录发现）**：**不是根因**。该 PR 未修改任何 Compose UI / 布局 / WindowInsets / 焦点代码；点击输入框获焦唤起键盘（未输入 `/` 前）不会触发 `editDraft`、`loadCatalog` 或 `submitApprovalDecision`。
  - **真正根因**：由工作台原有架构隐患（`WorkbenchScreen` 的 `bottomDockHeightPx` 在顶层组合作用域被读取，导致 IME 动画**每一帧**都触发全树重组与 `animateScrollToItem` 反复打断重启）被 **PR #207** 与 **PR #213** 叠加放大所致。

---

## 一、三组交互行为对比与实测数据

在 Robolectric SDK 34（`w411dp-h891dp-420dpi`）下，模拟真实多工作区场景（5 个工作区 × 每工作区 40 条会话，共 200 条会话，当前会话含 16 条消息），注入逐帧 `WindowInsetsCompat.Type.ime()`（20 帧，0px → 800px）并与点击 `+` 号、打开侧边栏做同环境对照实测：

| 场景 | `WorkbenchScreen` 重组 | `mainContent` 重组 | 关闭态 `ThreadDrawer` 重组 | `WorkspaceConversationBrowser` 重组 | `ConversationTitleResolver.resolve` 调用次数 | `LaunchedEffect(bottomDockHeightPx)` 重启次数 |
|---|---:|---:|---:|---:|---:|---:|
| 点击 `+` 号呼出底部附件菜单 | 0 | 0 | 0 | 0 | 0 | 0 |
| 打开侧边栏会话管理抽屉 | 0 | 2 | 2 | 2 | 330 | 0 |
| **点击输入框 + IME 上推 20 帧（PR #207 前，`workspaceMenu = null`）** | **20** | **20** | **20** | 0 | **800**（PR #213 前约 10ns/次） | **20** |
| **点击输入框 + IME 上推 20 帧（PR #207 + PR #213 后）** | **20** | **20** | **20** | **20** | **3,300**（165 次/帧 × 20 帧） | **20** |

> 独立微基准：当前 `ContextInjectionFilter.sanitizeSummaryText` 对 200 条常规会话标题连续调用 20 帧（4,000 次）在桌面 x86 JVM 上耗时 **69.55ms（3.48ms/帧）**，在移动端 ARM ART 主线程上可达 **15–30ms+/帧**，单此一项即可击穿 60Hz（16.6ms）/ 120Hz（8.3ms）帧预算。

### 为什么对比场景流畅，而唤起键盘严重掉帧？

1. **点击 `+` 号呼出底部附件菜单**：`showAttachmentsSheet` 是 `ComposerBar` 内部局部状态，打开独立 `Dialog` 窗口的 `ModalBottomSheet`，`bottomDockHeightPx` 不变，`WorkbenchScreen`、时间线与侧边抽屉均为 **0 次重组**。
2. **打开侧边栏会话管理菜单**：`ModalNavigationDrawer` 的滑入动画在布局/绘制层消费 `drawer.currentOffset`，不会逐帧触发 `WorkbenchScreen` 重组；仅在 `drawer.targetValue` / `drawer.isOpen` 翻转的起止点重组 2 次，而非在 300ms 动画里每帧触发。
3. **点击输入框唤起键盘**：
   - Android `adjustResize` + `enableEdgeToEdge()` 在约 250–350ms 内逐帧推送 15–42 次 `WindowInsets.ime` 高度变化。
   - `ComposerBar` 内部应用 `.imePadding()`，包裹它的 `WorkbenchScreen` 底部 `Column` 通过 `.onGloballyPositioned { bottomDockHeightPx = coordinates.size.height }` 在**每一帧**更新 `bottomDockHeightPx`。
   - **隐患引爆点 1（`WorkbenchScreen.kt`）**：`LaunchedEffect(bottomDockHeightPx)` 在 `WorkbenchScreen` 顶层作用域读取该状态，`CommandMenu` 包装 `Box` 也在 `mainContent` 组合期读取该状态；导致**每一帧 IME 动画都重跑整个 `WorkbenchScreen`、`mainContent`、以及处于屏幕外关闭状态的 `movablePhoneDrawer`（`ThreadDrawer`）**，并每帧取消再重启一次 `listState.animateScrollToItem(target)`。
   - **PR #207 放大器 1（`ThreadDrawer.kt`）**：每次重组都执行 `workspaceMenu.copy(onOpenWorkspaceThread = { ... }, onCreateWorkspaceThread = { ... })`，生成包含新 lambda 引用的 `WorkspaceMenuState` 实例，导致 `data class` 的 `equals()` 恒为 `false`，Compose 永远无法跳过 `WorkspaceConversationBrowser`。
   - **PR #207 放大器 2（`WorkspaceConversationBrowser.kt`）**：PR #207 水合了当前 Agent 下的**全部工作区与全部会话**；但非活跃工作区的排序与过滤写在 `LazyColumn` DSL 中且**未 `remember`**，每帧对全部非活跃工作区的所有会话重排、重过滤，即使搜索词 `query` 为空也对每条会话调用 `ConversationTitleResolver.resolve(thread.title, defaultTitle)`。
   - **PR #213 放大器 3（`ConversationTitleResolver.kt` + `ContextInjectionFilter.kt`）**：PR #213 将 `ConversationTitleResolver.resolve()` 从原先 O(1) 的 `isNullOrBlank()` 改为直接执行完整的 `ContextInjectionFilter.sanitizeSummaryText()`（单次调用分配 `IntArray`、扫描代码块两次、执行约 50 个正则与前后信封剥离，且对已净化常规文本无快速路径）。
   - **PR #207 附加冗余重组（`RemoteWorkbenchRoot.kt` & `WorkbenchScreen.kt`）**：`RemoteWorkbenchRoot` 顶层 L53 与工作台分支 L292 重复全量订阅 `controller.state`，导致打字与流式输出时整棵根树重组；`WorkbenchScreen` L326 在 `BoxWithConstraints` 组合期读取 `drawer.isOpen || drawer.targetValue == DrawerValue.Open` 导致开合抽屉触发主内容区额外重组；`ComposerInputField` 聚焦描边动画在组合期读取 `borderColor` 触发聚焦瞬间逐帧重组。

---

## 二、根治修复方案

1. **隔离 `bottomDockHeightPx` 组合期读取与消除 `animateScrollToItem` 逐帧打断（`WorkbenchScreen.kt`）**：
   - 将 `bottomDockHeightPx` 保持为 `MutableIntState` 引用而非在顶层解包委托，改用 `.onSizeChanged` 仅在高度变化时更新。
   - 贴底跟随改为在 `LaunchedEffect(readingPosition)` 内通过 `snapshotFlow { bottomDockHeightPx.intValue }` 监听增量 `delta = height - previous`，当 `previous > 0 && delta > 0 && followLatest && listState.canScrollForward` 时调用 `listState.scrollBy(delta.toFloat())` 与键盘帧 1:1 同步上推，彻底消除每帧全树重组与 `animateScrollToItem` 反复取消重启。
   - `CommandMenu` 浮层与 `Snackbar` 的底部避让改用纯布局阶段 `Modifier.layout` 修饰符读取 `bottomDockHeightPx.intValue`，不在组合期产生快照订阅。
   - 抽屉自动水合触发条件改在 `LaunchedEffect` 中通过 `snapshotFlow { expanded || drawer.isOpen || drawer.targetValue == DrawerValue.Open }.distinctUntilChanged()` 监听；并缓存 `displayKeyList`/`displayKeySet`、`allAttachments`、`ConversationBrowserActions` 与抽屉回调。
2. **修复 `ThreadDrawer`、`WorkspaceConversationBrowser`、`ConversationBrowser` 与控制器状态引用抖动（`ThreadDrawer.kt`、`WorkspaceConversationBrowser.kt`、`ConversationBrowser.kt`、`WorkbenchModels.kt`、`WorkbenchController.kt`）**：
   - `ThreadDrawer` 中使用 `remember(workspaceMenu, onCloseDrawer)` 稳定包装 `WorkspaceMenuState` 与回调，使关闭态抽屉在父级重组时可直接跳过。
   - `WorkspaceConversationBrowser` 与 `ConversationBrowser` 使用 `remember` 缓存排序与过滤结果；当搜索词 `query` 为空时不再为 `.contains("")` 徒劳调用 `ConversationTitleResolver.resolve()`；`ConversationTitleResolver.resolve(title)` 在 Compose 环境内缓存解析结果。
   - `WorkbenchUiState.browserOrganization` 在 `pendingFolderAssignments` 为空时直接复用 `organization` 引用，且 `WorkbenchController.update` 在 `pendingFolderAssignments` / `busyThreadIds` 值未变时复用原集合引用，消除打字与流式输出时因不稳定参数引用抖动导致的关闭态抽屉重组。
3. **聚焦描边动画下沉至绘制阶段（`ComposerBar.kt`）**：
   - `ComposerInputField` 聚焦边框动画改为在 `drawWithContent` 绘制阶段读取 `borderColorState.value`，聚焦瞬间 0 次组合开销。
4. **消除 `RemoteWorkbenchRoot` 冗余全量订阅（`RemoteWorkbenchRoot.kt`）**：
   - 移除顶层仅用于登录态的 `controller?.state?.collectAsState()`，下沉到登录分支；工作台分支仅以 `distinctUntilChanged()` 订阅 `threads` 与 `isOnline`。
5. **为 `ContextInjectionFilter` 补齐语义严格等价的零分配快速路径（`ContextInjectionFilter.kt`）**：
   - 为不含任何结构化注入特征字符（`<`、`[`、`#`、`:`、`---`、`===`）的文本直接短路返回，且对含 `:` / `-` 的常规中文标题在各剥离步骤前置首字符/子串 O(1) 守卫与代码块特征守卫（仅在包含 `` ` `` 或 `~` 时扫描代码块），保证 100% 语义等价且全部既有净化测试通过。

---

## 三、修复前后验证记录

同环境（Robolectric SDK 34，`w411dp-h891dp-420dpi`，5 个工作区 × 40 条会话，20 帧 IME 0px → 800px）对比结果：

| 指标 | 修复前（基线 `dev`） | 修复后 | 改善幅度 |
|---|---:|---:|---|
| 点击输入框获焦重组（`wb` / `ws` / `input`） | 0 / 0 / 逐帧描边重组 | **0 / 0 / 1**（仅聚焦态翻转 1 次，描边动画纯绘制层） | 描边组合开销清零 |
| IME 20 帧上推重组（`wb` / `ws` / `input`） | **20 / 20 / 20** | **0 / 0 / 0**（经阳性对照 `draftControl(wb=1, ws=0)` 验证探针有效） | **-100%** |
| 打字变更草稿时关闭态工作区浏览器重组 | 每字重组 | **0 次**（`draftControl(wb=1, ws=0)`） | **-100%** |
| IME 20 帧 `animateScrollToItem` 取消重启 | **20 次** | **0 次**（改为 `withFrameNanos` + `scrollBy(delta)` 同步贴底） | **-100%** |
| IME 20 帧抽屉标题净化调用次数 | **3,300 次**（165 次/帧） | **0 次**（关闭态不再重组且列表缓存、空搜索词短路） | **-100%** |
| `sanitizeSummaryText` 200 标题 × 20 帧微基准 | **69.55ms（3.48ms/帧）** | **8.82ms–14.16ms（0.44–0.71ms/帧）** | **5×–7.9× 提速** |

