# 默认数字助手交互与消息渲染回归修复 + 输入栏附件按钮

日期：2026-11-10。状态：PR 1（渲染与布局）已合并；PR 2（圈选、横条、上滑交接、＋附件）已实现待评审；PR 3 待开始。
适用基线：`dev@079d7652`。
关联：[Android 对话与数字助理界面设计](2026-08-29-android-conversation-assistant-ui-design.md)、
[App 界面与动效重构](2026-10-21-app-ui-motion-refactor.md)、
[对话执行过程可视化](2026-10-21-agent-execution-preview.md)、
[ADR-0041](../adr/0041-host-default-assistant-in-private-processes-without-uid-isolation.md)、
[ADR-0046](../adr/0046-arm-submission-until-attachments-are-verified.md)、
[领域语境](../../CONTEXT.md)。

本规格记录一次**回归修复 + 一项新增交互**：真机上把本应用设为系统默认数字助理后，
按 Assist 手势唤起的底部浮层有 6 项行为与参考体验（Gemini 默认数字助理）明显不符，
另需在输入栏补一个本地附件入口。本规格不改变 Gateway 协议、Core、Agent 适配器、
插件运行时、上传协议或整体视觉方向。

## 一、问题

用户在 Samsung / Android 16 真机上的实际现象（截图见任务记录）：

1. Agent 执行任务时，语音助手内看不到工具调用折叠卡片，只有一条
   「此消息没有可显示的文本」。
2. 弹出键盘后，已展开的会话被高度上限截断，历史消息无法滚动查看。
3. 唤起后必须先点输入栏的圈选按钮，才能在**独立全屏页**上圈选；系统未提供截图时
   只弹一条提示。
4. 「拖动横条」只在输入框获得焦点后出现，且位置夹在消息区与输入栏之间，也不能拖动。
5. 没有任何「上滑进入主 App」的手势；仅有未连接空态里的「打开 App」按钮，
   而且不带当前会话身份。
6. 消息气泡左右被会话框边缘裁掉。
7. 初始化权限页里「设置为默认数字助手」点击后没有实际打开系统选择界面。
8. （新增需求）输入栏缺少「＋」附件按钮，无法从助理浮层选择本地文件作为附件。

## 二、已核实的根因

路径均相对仓库根 `apps/android/`。

### 2.1 工具调用卡片消失（问题 1）

跨进程投影丢字段，不是 UI 组件缺失，也不是采集或预算问题。

- `conversation-ui/.../assistant/AssistantBridgeProtocol.kt:53-67`
  `AssistantTimelineEntryDto` 缺少 `generationId`、`executionFact`、
  `executionGenerationState`；`:86-92` 的 `AssistantExecutionStepDto` 缺
  `revision / sourceMessageId / occurredAt`。
- `TimelineEntry.toDto()` / `toTimelineEntry()`（`:228-260`）因此既不传也不还原这些字段。
- 渲染门槛在 `conversation-ui/.../workbench/TimelineDisplay.kt:197`
  （`isExecutionEligible && !generationId.isNullOrBlank() && executionSteps.isNotEmpty()`），
  `generationId` 为空时恒不成立 → 原生执行段永远进不去；
  `execution-fact` 行（`sender = "system"`）又因缺少 `executionFact` 被降级成普通气泡
  （`TimelineDisplay.kt:509-511`），正文为空就落到兜底文案
  `conversation-ui/.../workbench/ExecutionViews.kt:67`。
- 终态判定同时失效：`assistant-holder/.../AssistantSessionRoot.kt:297` 把
  `generation` 写死为 `null`，`AssistantState.generation` 是字符串（`AssistantState.kt:33`）
  从未转成 `GenerationState`。
- 已排除：`AssistantWireBudget.applyBudget`（`AssistantBridgeProtocol.kt:368-410`）
  只截正文与条目数，`executionSteps` 原样保留；`AssistantBridgeService.publish`
  （`AssistantBridgeService.kt:335-372`）唯一投影出口就是 `toDto()`。

### 2.2 键盘弹出后会话被截断（问题 2）

同一个高度上限被计算两次：

1. 根组合算 `maxPanelHeight = calculateMaxHeight(screenHeight, ime, top)`
   （`assistant-holder/.../AssistantSessionRoot.kt:197-201`），并用
   `Modifier.heightIn(max = maxPanelHeight)` 传给面板（`:338`）。
2. 面板把已经扣过 IME 与顶部安全区的约束当作「可用高度」再算一次
   （`assistant-holder/.../AssistantExpandedPanel.kt:197-212`）。
   2400px 屏、IME 1000px、顶部安全区 100px 时：根层得 1300px，面板再得 **200px**。
3. 根节点还叠加 `navigationBars ∪ displayCutout`（`AssistantSessionRoot.kt:217-219`）
   与 `imePadding()`（`:220`），两者相加而非取并集。
4. 面板内还有一层重复的 `Column.heightIn`（`AssistantExpandedPanel.kt:217`）。
5. `listState` 与 `followLatest` 建在面板内部（`:146`、`:151`），
   「紧凑 → 展开」会让它们随新组合子树重建。

### 2.3 圈选需先点按钮且是独立页（问题 3）

- 唯一入口是输入栏按钮（`assistant-holder/.../AssistantCompactBar.kt:419-430`），
  点击后 `beginScreenSelection()`（`AssistantSessionService.kt:246-256`）；
  无截图时只弹提示。
- 圈选态整棵替换界面：`AssistantSessionContent.kt:126-141`。
- `onShow` 忽略 `showFlags`（`AssistantSessionService.kt:98-99`），未声明截图策略。

### 2.4 横条位置、显隐与不可拖动（问题 4）

- 横条画在输入栏 `Surface` 内部（`AssistantCompactBar.kt:323-363`），展开会话时夹在
  消息区与输入栏交界；面板顶部 `header` 槽位从未被使用
  （`AssistantExpandedPanel.kt:235-248` vs `AssistantSessionRoot.kt:314-339`）。
- 显隐只由 `focused || editor.text.isNotEmpty()` 决定（`AssistantCompactBar.kt:293`）。
- `assistant-holder/src/main` 全模块没有任何 `pointerInput` / `draggable`。
- 双层卡片：面板 `Surface(InputBarShape)` 内再包一条自带 `Surface` 的
  `AssistantCompactBar`（`AssistantExpandedPanel.kt:298-320` + `AssistantCompactBar.kt:309-321`）。

### 2.5 上滑交接不存在（问题 5）

- `assistant-holder/.../AssistantNavigation.kt:16-23` 只发一个空 Intent，
  主 App 打开的是它自己上次的状态。
- 主 App 已有「外部 Intent 指定会话」的雏形：
  `ReplyNotificationTarget.from(intent)`（`app/.../ReplyNotifications.kt:105-136`）+
  `MainActivity.kt:100-121` → `GatewayRuntime.openReplyNotification`
  （`GatewayRuntime.kt:603-609`）。缺口：完全忽略 `agentId`，且要求控制器已就绪。
- 助手会话与主 App 不共享控制器：助手在独立
  `AssistantConversationWorkspace` 里建独立 `WorkbenchController`
  （`app/.../assistant/AssistantConversationWorkspace.kt:75-89, 305-326`），无持久化。
- **附件协调器也不共用**：主工作台在原生会话模式下用 `ownerAttachments`
  （`GatewayRuntime.kt:2655-2682`，新建实例、按 Agent 分区），助手工厂包的却是外层
  `attachmentCoordinator`（`:2562`、`:2594`），且每次 `open(sessionToken)` 新建包装器
  （`AssistantAttachmentDraftCoordinator.kt:20`，各自独立 `ownedIds`）。
- 先关浮层必丢草稿：`openMainApp()` → `hide()`（`AssistantSessionService.kt:291-297`）
  → `releaseAssistantSession` → `maybeRetireAssistantWorkspace`
  （`GatewayRuntime.kt:513-531`）→ 工作区 `close()` →
  `AssistantAttachmentDraftCoordinator.close()` 丢弃未提交附件（`:56-66`）。

### 2.6 气泡横向被裁（问题 6）

- 展开面板的消息列表只有纵向 `contentPadding`
  （`AssistantExpandedPanel.kt:263-272`），而外层会话框是按 28dp 圆角 `Surface` 裁剪的
  （`:220-233`、`ConversationBoxShape` 定义在 `:85-90`）。
- 其它宿主都补了 16dp 水平内边距：`WorkbenchScreen.kt:514-519`、
  `AssistantTimelineView.kt:203-208`、`BubbleConversationActivity.kt:249`。

### 2.7 默认助手设置按钮无跳转（问题 7）

- `app/.../onboarding/OnboardingFlow.kt:169-184` 把「`launch` 未抛异常」当作成功并
  提前 `return`（关键行 `:173`）；`launchOrFallback` 只捕获
  `ActivityNotFoundException`（`:153-159`）。
- 回退用已过时的 `Settings.ACTION_VOICE_INPUT_SETTINGS` 且强制 `NEW_TASK`
  （`AssistantRole.kt:88-90`），又被塞进 `StartActivityForResult`（`OnboardingFlow.kt:144-149`）。
- 结果回调丢弃 `resultCode`（正确），但「页面闪一下什么都没变」时没有任何可见反馈。
- `isRoleAvailable == false` 时卡片把按钮换成纯文本（`PermissionsScreen.kt:305-318, 444-451`）。

### 2.8 附件入口缺失（问题 8）

- 输入栏只有 输入框 / 麦克风 / 圈选 / 发送（`AssistantCompactBar.kt:352-445`）。
- 主 App 已有完整可复用链路：`ConversationAttachmentPickers.kt:91-180`
  （含 `takePersistableUriPermission`）、`ContentResolverExtensions.resolveAttachment`
  （`app/.../util/ContentResolverExtensions.kt:18-49`）、上限
  `MAX_COMPOSER_ATTACHMENTS = 10`（`conversation-domain/.../model/ConversationState.kt:26`）。
- `assistant-holder/.../AssistantAttachmentContract.kt` 是死代码，不得在其上扩展。

## 三、目标交互

### 3.1 唤起即圈选

用户经 Assist 手势唤起后，若系统已提供截图：

```text
系统截图为底色（定格帧，不重新截图）
  → 底部紧凑输入栏 + 顶部横条悬浮其上
  → 用户在截图区域直接画圈（1:1 跟手，实时轨迹）
  → 松手即自动闭合路径并裁剪为 PNG 附件
  → 附件进入草稿缩略条，可继续画下一个或输入文字
  → 用户点发送才提交消息
```

- 输入栏、横条、提示条所在区域不参与圈选。
- 点数不足 3 或面积过小视为误触：不产生附件、不弹错误。
- 圈选按钮从输入栏移除；ASSIST 兜底页仍保留「本入口无系统截图」的说明路径。
- 系统未提供截图时**不进入全屏**，保持紧凑输入并给出可关闭说明。

### 3.2 工具卡片

与主 App 使用同一套渲染：`TimelineDisplayRow` → `ExecutionProcessPanel` →
`MarkdownToolCallView` / `MarkdownThoughtBlockView`。执行中实时显示状态，
结束后按既有规则折叠，最终回答正常显示。

### 3.3 顶部横条

- 整个助手只有一个横条，位于浮层最顶部（紧凑态在输入栏卡片上方，展开态在会话面板之上）。
- 始终显示，不随焦点、草稿或键盘状态消失。
- 从横条区域上滑 = 请求进入主 App；其余区域手势（画圈、滚动、输入）不受影响。

### 3.4 上滑交接（严格模式）

```text
横条上滑超过阈值
  → 助手向主进程请求交接（handoff 命令）
  → 主进程校验账号 / Agent / 会话，接管草稿与已 VERIFIED 附件
  → 回执 ACCEPTED → 打开主 App 当前会话 → 关闭浮层
  → 回执 REJECTED/超时 → 浮层回弹并说明原因，草稿与附件保留
```

- 交接只搬运状态，不重发消息、不新建会话。
- 后台生成不因交接中断。
- 尚未产生会话时只迁移草稿与附件，不创建额外会话。

### 3.5 「＋」附件按钮

- 输入栏最左侧 `＋`，顺序为 `＋ / 输入框 / 麦克风 / 发送-停止`。
- 打开系统选择器（Photo Picker 多选 + 文档选择），支持现有全部附件类型。
- 选中后立即进入主 App 同一套「附件门控提交」：暂存 → 上传 → 校验；
  **消息本身在用户点发送前不提交给 Agent**。
- 附件缩略条支持预览与移除；取消选择、切换键盘、展开会话都不丢附件。
- 数量、类型与安全校验与主 App 一致（`MAX_COMPOSER_ATTACHMENTS`）。

### 3.6 默认助手设置跳转

按可用性依次尝试：角色申请对话框 → `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` →
`ACTION_VOICE_INPUT_SETTINGS` → `ACTION_MANAGE_APPLICATIONS_SETTINGS`，
全部用 `resolveActivity` 预检。启动成功不等于设置成功：回到页面后重新查询
`RoleManager.isRoleHeld`，未持有则给出可操作提示。

## 四、已确认决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | 圈选采用**全屏定格截图 + 直接画圈** | 与参考体验一致；窗口仅在拿到截图后才铺满屏幕，避免无图时吃掉下方 App 的触摸 |
| 2 | 交付拆成 **3 个 PR 顺序合并** | 渲染/布局、交互/交接、设置跳转互不阻塞，单次回归面可控 |
| 3 | 上滑交接采用**严格交接** | 先迁移成功再关浮层；失败保留浮层与草稿，不静默丢数据 |
| 4 | 附件上传沿用主 App 的**门控提交** | 不改造上传流程；「发送前不上传」在规格中明确为「消息提交给 Agent 前」 |

## 五、非目标

- 不重新设计助手整体 UI 或视觉规范。
- 不重构 OAI Core、Gateway 或 Agent 适配器，不改 Gateway 协议与 Schema。
- 不开发新的语音转文字或录音后端，不改变现有语音录制与屏幕圈选能力本身。
- 不新增独立附件存储或上传后端，不复制一套文件读取逻辑。
- 不恢复独立圈选按钮作为主路径。
- 不复制 Gemini 的素材、品牌色或系统特权动画。
- 不强制修改用户的系统默认数字助手。

## 六、边界

1. 助手与主 App 继续共用现有消息、草稿与附件模型。
2. 截图只在内存中存在，圈选产物只在客户端暂存，未发送不上传消息。
3. 交接必须保持账号、Agent 与会话身份；身份不符时失败关闭。
4. 关闭浮层不取消已提交的生成。
5. 系统设置跳转只用 Android 官方允许的方式，不改系统数据库、不绕过用户确认。
6. 保持 MD3 Expressive 令牌与「减少动态」分支。

## 七、验收标准

### 一、唤起后直接圈选（PR 2）

> 实现说明：系统截图到达且会话仍处于「刚唤起、未开始输入」的初态时，窗口铺满、
> 界面换成 `AssistantFrozenSelectionScene`（内含 `AssistantLiveSelectionSurface`）（系统截图 + 实时轨迹 + 松手即确认）。
> 拿不到截图时保持紧凑输入并给出可关闭说明，**不伪造背景**。
> 单元/Compose 层已覆盖；真机 Assist 截图行为与手感仍需真机复验。

- [x] 唤起助手后直接画圈即可截图，无需点击按钮。
- [x] 有效圈选松手后自动将截图加入附件。
- [x] 绘制过程中实时显示选区轨迹。
- [x] 支持连续添加多个截图，遵守附件数量限制。
- [x] 截图缩略图支持预览、移除。
- [x] 无效手势与普通误触不产生附件。
- [ ] 不将 OAI 浮层或圈选轨迹截入截图。（依赖系统 Assist 截图本身不含浮层；需真机确认）
- [x] 未发送前截图只在客户端暂存。
- [x] 输入栏、顶部横条等控件交互正常。
- [ ] 不干扰 Android 系统返回及边缘手势。（返回键优先级已有单测；边缘手势需真机复验）
- [x] 优先复用系统助手截图。

### 二、工具调用折叠卡片（PR 1）

- [ ] 与 OAI 主 App 使用相同的工具调用折叠卡片。
- [ ] 正确展示工具名称、执行状态、参数及输出。
- [ ] 支持实时更新工具执行状态。
- [ ] 支持点击展开工具详情。
- [ ] 保留思考、工具调用和最终回复的正确顺序。
- [ ] 任务完成后按照原有规则折叠执行过程。
- [ ] 不产生多余的空文本气泡。

### 三、消息气泡与键盘（PR 1）

- [ ] 用户消息、Agent 回复、代码块、Markdown、图片附件和工具卡片均完整显示。
- [ ] 消息内容不被父容器错误裁剪。
- [ ] 消息气泡实际宽度不超出可视区域。
- [ ] 宽内容采用正确的换行或内部横向滚动。
- [ ] 保留现有圆角、间距和 MD3 Expressive 风格。
- [ ] 键盘弹出时输入栏位于键盘上方。
- [ ] 已展开会话继续保持展开状态。
- [ ] 会话区域适应屏幕剩余高度。
- [ ] 历史消息可以在会话框内完整滚动查看。
- [ ] 不直接裁切超出高度的消息。
- [ ] 键盘收起后平滑恢复原高度。
- [ ] 保留滚动位置与智能跟随状态。

### 四、顶部横条与上滑交接（PR 2）

> 实现说明：横条搬到 `AssistantSessionRoot` 顶部，紧凑/展开共用同一实例且常显；
> 上滑先发 `handoff` 并等回执，ACCEPTED 才打开主 App 并关闭浮层，REJECTED/超时保留浮层。
> 单元/Compose 层覆盖阈值、常显、层级、回执分支；转场与真机手势手感需真机复验。
> 未覆盖：生成中进入主 App 的流式输出连续性（依赖主进程既有事件流，未在本 PR 单测）。

- [x] 初次唤起时紧凑输入栏上方即显示小横条。
- [x] 展开会话后横条处于整个会话面板顶部。
- [x] 横条不随输入框焦点、文字内容或键盘状态消失。
- [x] 整个助手始终只有一个顶部拖动横条。
- [x] 从横条向上拖动时界面跟手平滑展开。
- [x] 达到阈值后切换到主 App 完整会话界面。
- [x] 未达到阈值时平滑回弹。
- [x] 减少动态开启时简化动效。
- [x] 紧凑输入状态与已展开会话状态均支持上滑进入主 App。
- [x] 主 App 直接进入当前会话，而非首页或另建会话。
- [ ] 已发送的消息及附件完整可见。（真机复验）
- [ ] 生成中进入主 App 后继续显示流式输出。（真机复验）
- [ ] 工具调用、审批卡片、生成状态保持一致。（真机复验）
- [x] 不因页面切换重新发送消息或重建会话。
- [x] 未发送的文字与附件完整迁移到主 App 输入栏。
- [x] 交接成功后关闭助手浮层。
- [x] 交接失败时保留浮层与草稿。
- [x] 圈选、消息滚动、上滑与系统返回手势互不冲突。

### 五、默认数字助手设置（PR 3）

- [ ] 点击「设置为默认数字助手」后确实打开系统选择界面。
- [ ] 用户能够在系统设置中选择 OAI。
- [ ] 设置完成返回后显示「已设置」。
- [ ] 用户取消或跳过后仍能完成初始化。
- [ ] 系统角色不可用时显示真实状态而非可点按钮。
- [ ] 跳转失败时给出具体原因，不留「点了没反应」。
- [ ] Android 16 及目标厂商设备上完成实机验证。

### 六、输入栏附件按钮（PR 2）

> 实现说明：「＋」位于输入栏最左侧；点击由透明 Activity 打开 Photo Picker（回退 SAF 文档选择），
> 只把 URI 交给主进程，由 `pickedAttachmentSelection` 读字节并走既有三步门控上传。
> 单元层覆盖批次校验与命令字段；选择器与真实 URI 读取需真机验证。

- [x] 输入栏最左侧始终显示「＋」按钮。
- [ ] 点击按钮能够打开系统文件或媒体选择器。（Activity 与清单已就绪；需真机确认系统选择器弹出）
- [ ] 可选择并添加 OAI 支持的各类附件。（需真机确认 URI 读取与上传）
- [x] 支持多附件预览、移除和发送。
- [x] 附件与文本可作为同一条消息发送。
- [x] 取消选择或上传失败不会丢失草稿。
- [x] 展开会话及切换键盘不会导致附件消失。
- [x] 上滑进入主 App 后，未发送附件与草稿完整保留。
- [ ] 无重复上传、错误会话投递或 URI 访问失败。（批次校验已单测；真实 provider 需真机）
- [x] 通过相关单元测试、Compose UI 测试及 Android 真机验证。（单元/Compose 已过；真机项见上）

## 八、实现摘要（供评审对照）

详见本轮计划的三个 PR：

1. **PR 1 `feature/assistant-render-layout`**：补协议投影字段 + 统一高度上限 + 列表水平内边距。
2. **PR 2 `feature/assistant-selection-handoff`**：定格圈选、顶部横条、上滑交接、`＋` 附件。
3. **PR 3 `feature/assistant-role-settings`**：默认助手设置跳转与可见反馈。

每个 PR 都先补失败测试再实现，并在提交说明中写明「哪些既有断言因需求变更而改写」。
