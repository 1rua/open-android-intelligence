# Open Android Intelligence Android 前端全量代码与动效深度审查报告

**审查范围**: `conversation-assistant-ui` 前端全量 UI 组件、动效物理引擎、状态机、VoiceInteraction 系统服务、插件声明式 UI、应用进程初始化、领域接口及底层网络契约  
**依据标准**:
1. [`2026-08-29 前端设计规格`](file:///mnt/数据/项目/open-android-intelligence/docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md) & [`2026-08-24 模块化架构`](file:///mnt/数据/项目/open-android-intelligence/docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md) & [`Gateway Protocol v2`](file:///mnt/数据/项目/open-android-intelligence/docs/contracts/gateway-protocol-v2.md)
2. **Animation Craft Bar**: Emil Kowalski 动效工程标准与十项不可妥协法则
3. **Apple Design Guidelines**: WWDC 2018–2026 流体界面（Designing Fluid Interfaces）物理模型与八大设计原则
4. **Code Quality & Architecture**: Martin Fowler 重构代码异味基准与 Android Architecture Best Practices

---

## 目录
1. [执行摘要](#1-执行摘要)
2. [第一部分：动效工程与 Apple 流体设计审查 (Animation & Apple Fluid Design)](#2-第一部分动效工程与-apple-流体设计审查)
   - [2.1 Emil Kowalski 动效十项准则逐条判定](#21-emil-kowalski-动效十项准则逐条判定)
   - [2.2 Apple WWDC 流体物理模型与手势动量审查](#22-apple-wwdc-流体物理模型与手势动量审查)
   - [2.3 动效问题整改全景对照表 (Findings Table A-01 ~ A-14)](#23-动效问题整改全景对照表-findings-table)
   - [2.4 动效综合裁决 (Animation Verdict)](#24-动效综合裁决-verdict)
3. [第二部分：Standards 轴审查 (代码异味与架构规范 S-01 ~ S-18)](#3-第二部分standards-轴审查-代码异味与架构规范)
4. [第三部分：Spec 轴审查 (设计规格与协议契约偏离 P-01 ~ P-24)](#4-第三部分spec-轴审查-设计规格与协议契约偏离)
5. [第四部分：全量问题与代码位置全景矩阵 (Master Issue Matrix 01–72)](#5-第四部分全量问题与代码位置全景矩阵-master-issue-matrix)

---

## 1. 执行摘要

本次审查对 Open Android Intelligence 前端工程（涵盖 Compose UI、动效物理引擎、状态机、VoiceInteraction 服务、插件声明式 UI、Application 组合根及相关适配模块）共计 30+ 关键源文件进行了全量透视与代码穷尽审查。

共确认 **72 项具体缺陷与潜在系统性风险**：
- **动效物理与流体手势**: 14 项 (A-01 ~ A-14)
- **架构设计与代码异味**: 18 项 (S-01 ~ S-18)
- **设计规格与协议契约偏离**: 24 项 (P-01 ~ P-24)
- **组件交互、生命周期与无障碍**: 16 项 (C-01 ~ C-16)

**综合审查裁决：🔴 BLOCK（阻断上线）**。  
核心阻断诱因为：**系统级数字助理入口为空壳、关键流体 Morph 连续形变缺失、手势速度交接存在断层瞬移、消息门控提交与加密镜像领域管线未建立、遗留 MVP 与 Compose 双架构发散修改、受保护插件声明式 UI 未渲染接入**。

---

## 2. 第一部分：动效工程与 Apple 流体设计审查

### 2.1 Emil Kowalski 动效十项准则逐条判定

| 动效标准 | 审查现状 | 判定 |
|---|---|---|
| **1. 正当性 (Justified)** | 停靠球完成状态使用 `rememberInfiniteTransition` 无限循环呼吸；缝线环 360° 无休止旋转；缺乏单次微脉冲阻尼收敛。 | 🔴 违规 |
| **2. 频次自适应 (Frequency)** | 新建对话、清除等高频动作瞬时切换零动画；时间线新消息进入无位移与淡入过渡，突兀跳出。 | 🔴 违规 |
| **3. 响应式缓动 (Easing)** | 防抖进度条使用 100ms 离散协程步进 `animateFloatAsState` (100ms tween)，肉眼可见微卡顿，缺乏连续物理时钟。 | 🟡 警告 |
| **4. 300ms 预算 (Sub-300ms)** | 信号缝线无限旋转周期 2400ms，脉冲呼吸周期 1200ms/800ms 过长，超出微交互注意力预算。 | 🟡 警告 |
| **5. 物理起点与缩放 (Origin & Physicality)** | 展开栏与停靠球未共享物体身份，球消失后栏从底部滑入，无宽/高/圆角/位移连续几何插值。 | 🔴 违规 |
| **6. 可中断性 (Interruptibility)** | 页面切换使用 `when(currentScreen)` 裸切；悬浮球吸附未采用连续物理插值，手势无法中途反向抓取。 | 🔴 违规 |
| **7. 仅在 GPU 属性动画** | 工具卡展开使用 `expandVertically` / `shrinkVertically` 触发布局重新测量（Relayout），而非 `graphicsLayer` 变换。 | 🟡 警告 |
| **8. 减少动态 (Reduce Motion)** | `AppleMotion.kt` 中定义了 `reducedMotionSpec` 但全工程零处消费，无系统无障碍动画比例感知。 | 🔴 违规 |
| **9. 非对称时间 (Asymmetric timing)** | 按下缩放反馈基于 Compose `clickable`，存在手势仲裁延迟；缺乏 pointer-down 极速 (<16ms) 与回弹非对称对比。 | 🟡 警告 |
| **10. 整体一致性 (Cohesion)** | 苹果流体物理模型（动量投影/橡皮筋）仅作为工具方法存在，未贯穿核心视图层级。 | 🔴 违规 |

---

### 2.2 Apple WWDC 流体物理模型与手势动量审查

1. **响应（Response / Kill Latency）**:
   - **源码位置**: [`AppleMotion.kt:130-156`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/AppleMotion.kt#L130-L156)
   - **分析**: `pressScaleFeedback` 依赖 `MutableInteractionSource.collectIsPressedAsState()`，由于 Compose 的点击防误触仲裁机制，手指按下至产生缩放反馈存在 ~100ms 延迟，违背 Apple 流体手势“在 pointer-down 首帧立即响应”原则。
2. **直接操纵与抓取偏移（1:1 Tracking & Grab Offset）**:
   - **源码位置**: [`DockedBall.kt:113-135`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L113-L135)
   - **分析**: 拖动悬浮球时，代码直接累加 `dragAmount` 到球体位置，未记录用户手指最初按在球体上的相对抓取偏移量（Grab Offset），导致拖拽启动瞬间球体与手指接触点失准。
3. **动能交接（Velocity Handoff）与物理吸附断层**:
   - **源码位置**: [`DockedBall.kt:145-153`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L145-L153)
   - **分析**: 手势释放（`onDragEnd`）时，计算出 `computeSnapTargetX` 后直接将结果写入 `offsetX` 状态变量，**未创建 `Animatable` 弹簧动画，更未将 `velocityX` 作为 `initialVelocity` 传递**，造成球体在手势释放后瞬移跳变（Teleportation）。
4. **空间对称性与路径一致性（Spatial Consistency）**:
   - **源码位置**: [`MainActivity.kt:58-79`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L58-L79); [`AssistantOverlay.kt:98-151`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantOverlay.kt#L98-L151)
   - **分析**: 主界面切往设置页瞬时硬切，无右入/右出空间转场；助理栏展开/收起分为球淡出与栏滑入两个独立组件分支，破坏了物体恒常性（Object Permanence）。
5. **排版与文字光学微调（Typography Optical Sizing & Tracking）**:
   - **源码位置**: [`ThemeTokens.kt:105`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/ThemeTokens.kt#L105)
   - **分析**: `displayLarge` 字体追踪度设置了 `letterSpacing = (-0.02).sp`（绝对 sp 单位），而非相对字号的 em/比例单位，在不同动态字号缩放下产生字间距畸变。

---

### 2.3 动效问题整改全景对照表 (Findings Table)

| # | 现存代码实现 (Before) | 规范标准实现 (After) | 违背原理与原因 (Why) |
|---|---|---|---|
| **A-01** | [`AssistantOverlay.kt:98-151`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantOverlay.kt#L98-L151)<br>`AnimatedVisibility` 两个独立分支（球 fade-out + 栏 slide-in） | 统一容器连续 Morph 几何弹簧插值（宽/高/圆角/位移同步演化） | 违背物体恒常性与规格 §11.3。双元素交替造成瞬时视觉断裂。 |
| **A-02** | [`DockedBall.kt:145-153`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L145-L153)<br>`offsetX = targetX` 瞬时状态赋值，无动画吸附 | `Animatable.animateTo(targetX, spring(damping=0.82f, stiffness=400f), initialVelocity=velocityX)` | 违背 Apple Design #5（Velocity Handoff）。释放速度丢失导致手感硬断层。 |
| **A-03** | [`DockedBall.kt:113-135`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L113-L135)<br>`offsetX + dragAmount.x` 裸增量累加 | 记录 `onDragStart` 相对接触点 Offset，保持手指与球体锚点恒定 | 违背 Apple Design #2（Direct Manipulation）。手指与控制中心错位。 |
| **A-04** | [`ConversationTimeline.kt:140-145`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/ConversationTimeline.kt#L140-L145)<br>`items(messages)` 裸渲染，新消息瞬时跳出 | `Modifier.animateItem()` + 160–190ms，`translateY(4.dp)` + `opacity(0f -> 1f)` | 违背规格 §11.2。新内容突兀插入破坏视觉视觉锚点。 |
| **A-05** | [`MainActivity.kt:58-79`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L58-L79)<br>`when(currentScreen)` 裸切页面，零转场动画 | `AnimatedContent` / `NavHost` 方向感知对称平移（Slide in from right / out to right） | 违背 Apple Design #7（空间对称一致性）。破坏页面层级心智模型。 |
| **A-06** | [`AppleMotion.kt:130-156`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/AppleMotion.kt#L130-L156)<br>`pressedScale = 0.97f` 基于 `clickable` 的 `collectIsPressedAsState` | `pointerInput` 监听首帧 pointer-down，`scale = 0.98f`，~90ms 非对称弹簧 | 违背 Apple Design #1（Kill Latency）与规格 §11.2。点击仲裁延迟产生手感迟钝。 |
| **A-07** | [`DockedBall.kt:84-94`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L84-L94)<br>`rememberInfiniteTransition` 800ms 无限循环缩放脉冲 | 单次阻尼弹簧微脉冲（One-shot spring pulse），完成后静止常态 | 违背 Standard #1 & Apple Design #13。无休止循环动效造成注意力与认知疲劳。 |
| **A-08** | [`SignalStitch.kt:90-117`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/SignalStitch.kt#L90-L117)<br>`isThinking` 时 2400ms 无限匀速旋转与 1200ms 呼吸混合 | 临界阻尼微动效，并在系统“减少动态”时降级为静态高亮 | 违背 Standard #8（A11y）。无限旋转易引发前庭敏感不适，缺乏降级开关。 |
| **A-09** | [`AppleMotion.kt:74-77`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/AppleMotion.kt#L74-L77)<br>定义了 `reducedMotionSpec` 但全工程零处调用 | 建立全局 `LocalReduceMotion` 环境组合，所有 Spring/Transition 自动降级 Crossfade | 违背规格 §11.4 与 WCAG 无障碍准则。死代码未生效。 |
| **A-10** | [`DebounceBatchContainer.kt:49-54`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/DebounceBatchContainer.kt#L49-L54)<br>每 100ms 协程触发 `animateFloatAsState` (100ms tween) | 单一连续时钟驱动 `Animatable` (从 1500ms 匀速过渡至 0)，支持平滑插值中断 | 违背 Standard #3。100ms 阶梯跳跃导致进度条产生肉眼可见的微卡顿。 |
| **A-11** | [`ToolCallCard.kt:147-151`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/ToolCallCard.kt#L147-L151)<br>`expandVertically` / `shrinkVertically` (Tween 200ms/150ms) | `graphicsLayer` 配合 GPU `clip` 与临界阻尼弹性展开 | 违背 Standard #7。频繁触发布局重新测量（Relayout），列表卡片展开易掉帧。 |
| **A-12** | [`DockedBall.kt:64-68`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L64-L68)<br>硬编码 `1080f` x `2400f` 与 `56f * 3f` 计算吸附边界 | 基于 `LocalDensity` / `WindowMetrics` / `safeDrawingPadding` 动态计算安全可视域 | 违背 Apple Design #5（Flexibility）。非 1080p 或非 3x 密度的设备上吸附越界或留白。 |
| **A-13** | [`ConversationTimeline.kt:82-89`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/ConversationTimeline.kt#L82-89)<br>流式输出每个 Token 增量均触发 `animateScrollToItem` | 仅在用户位于底部时通过状态标志自然贴底，防抖或使用 `scrollToItem` 避免频繁动画重入 | 违背 Emil Kowalski #7。流式高频触发滚动动画导致 GPU 与主线程严重浪费。 |
| **A-14** | [`ScreenSelectionOverlay.kt:84-129`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/ScreenSelectionOverlay.kt#L84-129)<br>单点触控裸画，多指触碰产生坐标跳跃畸变 | 结合 PointerId 锁定首根触控手指，采用贝塞尔平滑插值（Bézier Spline） | 违背 Apple Design #10。手势缺乏防畸变与平滑滤波。 |

---

### 2.4 动效综合裁决 (Verdict)

**裁决结果：🔴 BLOCK (阻断)**

---

## 3. 第二部分：Standards 轴审查 (代码异味与架构规范)

### S-01. 发散式修改与散弹式修改 (Divergent Change & Shotgun Surgery)
- **源码位置**: [`MainActivity.kt:37-41`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L37-L41); [`ConversationScreen.kt:19-45`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ConversationScreen.kt#L19-L45); [`GatewayScreen.kt:15-27`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayScreen.kt#L15-L27); [`AttachmentPicker.kt:20-36`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/AttachmentPicker.kt#L20-L36)
- **异味特征**: 4 个遗留 MVP Presenter 文件与新的 Compose `ConversationViewModel` 并存。`MainActivity` 依然维护 `gatewayPresenter`, `conversationPresenter`, `attachmentPresenter` 实例仅为 `currentNavigation()` 返回旧模型。当用户在 UI 修改 Gateway 时，Presenter 数据不更新，造成双重状态源与发散修改。

### S-02. 基本类型偏执 (Primitive Obsession)
- **源码位置**: [`MainActivity.kt:35`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L35); [`ConversationUiState.kt:61`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationUiState.kt#L61)
- **异味特征**:
  1. 页面导航状态使用裸字符串 `var currentScreen = "main"`，类型不安全且无法携带类型化参数。
  2. `ToolCallInfo.status` 直接使用 `String = "SUCCESS"`，在 `ToolCallCard.kt:63` 中通过 `tool.status.uppercase() == "SUCCESS"` 进行不安全字符串对比，未采用强类型枚举 `ToolExecutionStatus`。

### S-03. 中间人与空代理 (Middle Man / Speculative Generality)
- **源码位置**: [`ConversationViewModel.kt:173-195`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L173-L195); [`ConversationViewModel.kt:376-401`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L376-L401)
- **异味特征**: `triggerAgentGeneration` 与 `sendAssistantMessage` 在 ViewModel 中启动协程后立即将状态重置为完成，未连接到底层 `gateway-client` 或 SSE 管道，属于典型的虚假空代理桩。

### S-04. 死代码与不可达分支 (Dead Code)
- **源码位置**: [`ConversationUiState.kt:150`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationUiState.kt#L150); [`MessageItem.kt:303-327`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L303-L327); [`AppleMotion.kt:74-77`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/AppleMotion.kt#L74-L77)
- **异味特征**:
  1. `isOffline` 初始为 `false`，全工程零处更新写入，导致 `MainScreen` 的离线 UI 分支永远不可达。
  2. `DeletedTombstoneItem` 完整定义但未在 `MessageItemView` 的 `when` 分支中挂载。
  3. `reducedMotionSpec` 声明后从未被调用。

### S-05. 抽象泄漏与破坏性全局文件操作 (Leaking Abstractions & Dangerous Disk Operations)
- **源码位置**: [`ConversationViewModel.kt:467-505`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L467-L505); [`PlatformSettingsScreen.kt:259-265`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/settings/PlatformSettingsScreen.kt#L259-L265)
- **异味特征**: ViewModel 内部的 `clearCache` 直接递归删除 Android 系统分配的整个应用私有缓存目录 `context.cacheDir`，这会意外损毁其他系统组件与第三方 SDK 的关键运行时临时文件。规范 §15 与 §17.7 明确要求媒体缓存必须隔离于领域专用的加密存储区并受配额管理。

### S-06. 组合局部静态全量重组陷阱 (CompositionLocal Recomposition Bottleneck)
- **源码位置**: [`ThemeTokens.kt:62`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/ThemeTokens.kt#L62)
- **异味特征**: `LocalColorTokens` 采用了 `staticCompositionLocalOf`。当发生深浅主题切换或系统配置变更时，会导致整棵 Compose 视图树自顶向下无条件全量重组（Recomposition），极大降低渲染性能。

### S-07. 已废弃的 Composed 修饰符反模式 (Deprecated Composed Modifier Pattern)
- **源码位置**: [`AppleMotion.kt:130-156`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/AppleMotion.kt#L130-L156)
- **异味特征**: `Modifier.pressScaleFeedback` 采用了已被 Compose 官方明确不推荐的 `composed { ... }` 包装，增加了额外的修饰符节点开销并阻碍了编译期优化，应迁移至 `Modifier.Node` 或无状态组合修饰符。

### S-08. 双状态流与状态机脱节 (Dual StateFlow Divergence)
- **源码位置**: [`ConversationViewModel.kt:27-32`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L27-L32)
- **异味特征**: `ConversationViewModel` 同时暴露 `_conversationState` 与 `_assistantState` 两个并行的 StateFlow，各自维护一套 `draftText`、`attachments` 与 `isThinking/isGenerating` 状态，直接违反了规范 §7（“主 App 与助理会话必须共用一个消息编辑器领域模型和 ConversationRepository”）。

### S-09. 无边界协程滚动风暴 (Unbounded Scroll Coroutine Storm)
- **源码位置**: [`ConversationTimeline.kt:82-89`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/ConversationTimeline.kt#L82-L89)
- **异味特征**: `LaunchedEffect(messages.size, streamingReplyText)` 将 `streamingReplyText` 作为 Key。在流式 Token 频繁到达时，每秒触发十数次 `animateScrollToItem`，导致大量并发协程频繁启动并打断前序滚动动画，引发严重的掉帧和卡顿。

### S-10. 动态内容强制双重测量 (Intrinsic Measurement on Dynamic Content)
- **源码位置**: [`MessageItem.kt:202`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L202)
- **异味特征**: 助手消息行在包含长文本的 Row 上声明了 `Modifier.height(IntrinsicSize.Min)`，导致 Compose 在排版长文、代码块时必须执行两次完整测量过程（Double Measurement Pass），对长会话时间线性能产生显著损耗。

### S-11. 配置变更下的临时状态丢失 (Transient State Loss on Configuration Change)
- **源码位置**: [`ScreenSelectionOverlay.kt:75-78`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/ScreenSelectionOverlay.kt#L75-L78); [`PlatformSettingsScreen.kt:76-81`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/settings/PlatformSettingsScreen.kt#L76-L81)
- **异味特征**: 圈选笔画 `strokePoints`、外接框 `boundingBox` 以及设置页中的弹窗开关和输入框状态仅使用普通的 `remember` 进行保存，未采用 `rememberSaveable`。屏幕旋转或配置变更发生时，用户的圈选绘制和表单输入会瞬间被清空。

### S-12. 数学边界除零与 NaN 隐患 (Arithmetic Division & Logarithmic Edge Cases)
- **源码位置**: [`ConversationViewModel.kt:507-514`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L507-L514)
- **异味特征**: `formatBytes` 使用了 `Math.log10(bytes.toDouble())`。当 `bytes <= 0` 时虽然有前置判断，但对于异常数值输入容易产生 `-Infinity` 或 `NaN`，引发格式化崩溃。

### S-13. 缺少超时自动复位的永久反馈状态 (Sticky Feedback State)
- **源码位置**: [`MessageItem.kt:197, 246-247`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L197)
- **异味特征**: 复制消息文本后，`isCopied` 被置为 `true` 且按钮文案变为“已复制”，但代码未启动延时协程将其复位为 `false`，导致卡片永久停留在已复制状态。

### S-14. 密度与物理尺寸硬编码异味 (Hardcoded Density & Dimension Assumptions)
- **源码位置**: [`DockedBall.kt:64-68`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L64-L68)
- **异味特征**: `DockedBall` 默认参数中硬编码了 `1080f` 宽度、`2400f` 高度以及 `56f * 3f`（假定屏幕密度为 3.0x），完全破坏了组件的自适应能力与重用性。

### S-15. 依赖硬编码与缺少容器注入 (Direct Instantiation & Missing Inversion of Control)
- **源码位置**: [`MainActivity.kt:38-41`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L38-L41)
- **异味特征**: Activity 内部直接使用 `GatewayPresenter()`、`ConversationPresenter()` 进行硬编码构造，未通过依赖注入容器管理生命周期。

### S-16. 残留垃圾导入与代码洁净度违规 (Residual Imports & Unused Symbols)
- **源码位置**: [`MessageItem.kt:46`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L46); [`SharedComposer.kt:33`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/SharedComposer.kt#L33); [`AttachmentDraftBar.kt:3-7`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/AttachmentDraftBar.kt#L3-L7)
- **异味特征**: 多处存在未使用的 Compose Animation / Material Icons 导入声明，未进行代码清理。

### S-17. Application 缺乏私有进程感知初始化隔离 (Missing Process-Aware Application Initialization)
- **源码位置**: [`OpenAndroidIntelligenceApplication.kt:31-53`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt#L31-L53)
- **异味特征**: 当 Android 启动 `:assistant-session` 私有进程时，同样会实例化 `OpenAndroidIntelligenceApplication`。现存代码在 `onCreate()` 中无条件初始化 `PluginKernel`、`AndroidAuditStore` 与加载器，造成主进程与助理进程产生双重内核实例与审计数据库锁争用，直接违背规范 §9.0。

### S-18. 仪表化测试依赖已废弃的 MVP Presenter 假象 (Instrumented Test Fake Assertions)
- **源码位置**: [`CoreWithoutPluginsInstrumentedTest.kt:19-44`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/androidTest/kotlin/com/openandroidintelligence/mobile/CoreWithoutPluginsInstrumentedTest.kt#L19-L44)
- **异味特征**: 设备端仪表化测试完全架空了 Compose UI 与 `ConversationViewModel`，仅实例化内存 `GatewayPresenter` 和 `ConversationPresenter` 做纯假象断言，测试对真实前端界面的回归保护力为 0。

---

## 4. 第三部分：Spec 轴审查 (设计规格与协议契约偏离)

### P-01. 规范 §9.0 四大有限状态轴未建立
- **偏离条款**: §9.0
- **源码位置**: [`ConversationUiState.kt:135-152`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationUiState.kt#L135-L152); [`AssistantUiState.kt:44-56`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/AssistantUiState.kt#L44-L56)
- **分析**: 规范要求采用由统一 Reducer 管理的四个并行有限状态轴：
  `SurfaceState` (`HIDDEN | EXPANDED | SELECTING_SCREEN | CROP_PREVIEW | DOCKED | TERMINATED`)、  
  `GenerationState` (`IDLE | QUEUED | RUNNING | CANCEL_REQUESTED | CANCELLED | COMPLETED | FAILED | UNSUPPORTED | OUTCOME_UNKNOWN`)、  
  `ComposerState` (`EDITING | DEBOUNCE_COLLECTING | SEALED | WAITING_NETWORK | WAITING_ATTACHMENTS | SUBMITTING | ACCEPTED | FAILED`)、  
  `AttachmentState[]` (`LOCAL_PREPARING | CREATE_PENDING | UPLOADING | VERIFYING | VERIFIED | RETRYABLE_FAILURE | TERMINAL_FAILURE | OUTCOME_UNKNOWN | CANCELLED`)。  
  现存代码充斥着无约束的布尔字段（`isGenerating`, `isThinking`, `hasCompletedResult` 等），易引发非法组合状态。

### P-02. 附件门控提交与意图冻结机制缺失
- **偏离条款**: §12.1, §12.2, ADR-0046
- **源码位置**: [`ConversationViewModel.kt:81-93`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L81-L93); [`SharedComposer.kt:274`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/SharedComposer.kt#L274)
- **分析**: 附件被选取后直接在本地标记为 `status = VERIFIED`。用户点击发送时直接构造消息并触发生成，缺少 `PendingSubmissionIntent` 意图冻结（包含 `submitIntentId`、`clientMessageId`、草稿 revision 及有序快照），未实现“待全部附件 verified 后原子切换为 SUBMITTING 并恰好发送一次”的门控保证。

### P-03. 系统级数字助理 VoiceInteraction 核心实现为空类
- **偏离条款**: §3.2, §9.0, ADR-0041
- **源码位置**: [`AssistantSessionService.kt:8-13`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionService.kt#L8-L13); [`app/build.gradle.kts:34-56`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/build.gradle.kts#L34-L56)
- **分析**:
  1. `AssistantSession` 为纯空类，未覆写 `onCreateContentView`、`onShow`、`onHandleScreenshot`。
  2. `app/build.gradle.kts` 甚至未依赖 `:assistant-holder` 模块。
  3. `AssistantOverlay` 被硬编码放置于 `MainActivity` 内部，无法作为系统默认数字助理跨应用悬浮唤起。

### P-04. 规范 §16 四大领域核心接口全量缺失
- **偏离条款**: §16, §22
- **源码位置**: [`state/`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/)
- **分析**: 规范定义的领域核心接口（`ConversationRepository`, `AgentCommandCatalogRepository`, `AttachmentDraftCoordinator`, `ConversationMirrorStore`）及其具体实现均未在工程中建立，ViewModel 直接与脆弱的内存模型耦合。

### P-05. 停止生成未对接远端取消协议
- **偏离条款**: §13, §24
- **源码位置**: [`ConversationViewModel.kt:197-225`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L197-L225)
- **分析**: 用户点击停止生成时，代码仅在本地取消协程并伪造文本 `(已由用户停止)`，未调用 `POST /conversations/{conversationId}/generations/{generationId}/cancel`，也未等待 `conversation.generation.cancelled` 事件确认，违背“关闭界面不冒充取消任务；取消必须等真实终态”的原则。

### P-06. 斜杠命令动态目录与 `/new` 协议偏离
- **偏离条款**: §14.1, §14.2, ADR-0043
- **源码位置**: [`ConversationViewModel.kt:227-232`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L227-L232)
- **分析**:
  1. 仅硬编码匹配字符串 `/new`，未通过 `GET /commands` 动态拉取网关命令目录。
  2. `/new` 仅在本地生成随机标题（`新对话 ${System.currentTimeMillis() % 10000}`），未将 `/new` 发送给 Agent 解释并等待 `conversation.command.result` 结构化切换真实新线程。

### P-07. 屏幕选区伪造内存数据与缺少真实截图底图
- **偏离条款**: §3.4, §10.1, §10.2
- **源码位置**: [`ConversationViewModel.kt:337-361`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L337-L361); [`ScreenSelectionOverlay.kt:80-84`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/ScreenSelectionOverlay.kt#L80-L84)
- **分析**:
  1. 圈选界面仅有黑色半透明遮罩，未渲染 Assist 提供的真实屏幕截图位图。
  2. 确认选区后，伪造文件大小 `(box.width * box.height * 4f).toLong()` 并直接标为 `VERIFIED`，未执行真正的 Bitmap 裁剪、PNG 压缩编码与 SHA-256 校验。

### P-08. Markdown 解析与代码语法高亮渲染缺失
- **偏离条款**: §6.1
- **源码位置**: [`MessageItem.kt:226-232`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L226-L232)
- **分析**: 助手正文直接采用纯 `Text(text = message.text)` 渲染，无法解析 Markdown 段落、列表、代码块（Fenced Code Blocks）及代码复制功能。

### P-09. 平板与横屏多栏响应式布局缺失
- **偏离条款**: §5.2, §20
- **源码位置**: [`MainScreen.kt:64-275`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/main/MainScreen.kt#L64-L275)
- **分析**: 界面仅针对单栏手机屏幕编写，缺少针对 SM-X710 等平板设备或横屏模式下的双栏（线程列表 + 对话）/三栏（Gateway + 线程 + 对话）自适应布局实现。

### P-10. 附件选取未遵循封闭式契约限制
- **偏离条款**: §12, `artifact-ports`
- **源码位置**: [`MainScreen.kt:78-107`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/main/MainScreen.kt#L78-L107); [`AssistantOverlay.kt:51-80`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantOverlay.kt#L51-L80)
- **分析**: 文件选择器直接调用 `filePickerLauncher.launch("*/*")`，未在 UI 层校验 `MAX_ARTIFACT_FILES = 4`、单文件 25MB、总文件 50MB 限制，未限制 MIME 为闭集（JPEG, PNG, WEBP, PDF, TEXT_PLAIN, AUDIO_MP4）。

### P-11. 本地加密对话镜像存储与离线状态未接入
- **偏离条款**: §15, ADR-0044
- **源码位置**: [`state/ConversationViewModel.kt`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt)
- **分析**: 未接入 `:encrypted-store` 模块的 `ConversationMirrorStore`，应用退到后台或被杀死后对话历史全部丢失；顶栏离线状态永远硬编码。

### P-12. 助理展开栏停止生成按钮为空回调
- **偏离条款**: §9.1, §13
- **源码位置**: [`AssistantExpandedBar.kt:223`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantExpandedBar.kt#L223)
- **分析**: 展开栏内 `SharedComposer` 的 `onStopClick = {}` 写死为空 Lambda，导致在数字助理展开态下用户无法终止生成任务。

### P-13. 助理展开栏“在 App 中打开”未路由到真实线程
- **偏离条款**: §9.1
- **源码位置**: [`AssistantOverlay.kt:131-133`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantOverlay.kt#L131-L133)
- **分析**: 点击“在主 App 中打开”仅执行 `viewModel.closeAssistant()` 关闭浮层，未触发向 `MainActivity` 的 Intent 派发与对应 `conversationId` 的页面切换。

### P-14. 消息发送失败缺少重试交互
- **偏离条款**: §19.2
- **源码位置**: [`MessageItem.kt:170-176`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L170-L176)
- **分析**: 发送失败的消息仅展示静态错误图标，未提供“点击重试”、“继续发送”或错误详情弹窗入口。

### P-15. 抽屉“附件与媒体缓存”项为无响应空操作
- **偏离条款**: §3.1, §4.1
- **源码位置**: [`MainScreen.kt:137-139`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/main/MainScreen.kt#L137-L139)
- **分析**: 侧边抽屉中的“附件与媒体缓存”点击事件仅关闭抽屉，未导航至任何媒体缓存管理页面。

### P-16. Gateway 切换器不支持 3 个以上网关选择
- **偏离条款**: §5.1
- **源码位置**: [`SidebarDrawer.kt:208-209`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/main/SidebarDrawer.kt#L208-L209)
- **分析**: 抽屉顶部的 Gateway 选择器点击时仅在第一和第二个网关之间 toggle，当配置了 3 个及以上网关时无法选择后续网关。

### P-17. 缺少全量 Composable 预览函数 (@Preview)
- **偏离条款**: §21.1
- **源码位置**: 全量 UI Composable 文件
- **分析**: 全工程所有 UI 文件中没有编写任何 Compose `@Preview` 预览函数，违反了规范中关于浅色/深色模式及多尺寸屏幕预览基线的要求。

### P-18. 缺少 Android 14+ 预测性返回支持
- **偏离条款**: §2, §20
- **源码位置**: [`app/src/main/AndroidManifest.xml`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/AndroidManifest.xml)
- **分析**: Manifest 未声明 `android:enableOnBackInvokedCallback="true"`，导致系统预测性返回手势动效失效。

### P-19. 缺少全局 App Theme 资源声明导致冷启动闪白
- **偏离条款**: §21
- **源码位置**: [`app/src/main/AndroidManifest.xml:5-18`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/AndroidManifest.xml#L5-L18)
- **分析**: Application 与 Activity 标签未设置 `android:theme`，应用冷启动在 Compose 首帧加载前会展示系统默认白色窗口背景。

### P-20. 存在多处低于 48×48dp 最小触摸目标的控件
- **偏离条款**: §4.4, §20
- **源码位置**: [`AssistantExpandedBar.kt:135, 151, 167`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantExpandedBar.kt#L135); [`PlatformSettingsScreen.kt:196`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/settings/PlatformSettingsScreen.kt#L196); [`AttachmentDraftBar.kt:154`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/AttachmentDraftBar.kt#L154)
- **分析**: 按钮尺寸显式设置为 28dp、32dp、18dp 且未配置 `minimumInteractiveComponentSize`，不符合无障碍触控目标规范。

### P-21. 基础 4dp 间距网格违规
- **偏离条款**: §4.4
- **源码位置**: [`AssistantExpandedBar.kt:129`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantExpandedBar.kt#L129); [`MessageItem.kt:106, 250`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L106); [`PluginActionSlots.kt:45`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/plugin/PluginActionSlots.kt#L45)
- **分析**: 代码中散落使用了 `6.dp`、`3.dp`、`7.dp`、`300.dp` 等非 4dp 倍数的间距参数。

### P-22. 助理展开栏最大高度硬编码 240dp
- **偏离条款**: §9.1
- **源码位置**: [`AssistantExpandedBar.kt:193`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantExpandedBar.kt#L193)
- **分析**: 回复区域高度被硬编码为 `heightIn(max = 240.dp)`，而非规范要求的“不超过当前可用窗口高度的 60%”。

### P-23. 受保护插件声明式 UI (DeclarativeUiSchema) 未在宿主端接入渲染
- **偏离条款**: §18.1, ADR-0010
- **源码位置**: [`PluginActionSlots.kt:22-67`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/plugin/PluginActionSlots.kt#L22-L67); [`plugin-ui/src/main/kotlin/com/openandroidintelligence/plugin/ui/DeclarativeUiSchema.kt`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/plugin-ui/src/main/kotlin/com/openandroidintelligence/plugin/ui/DeclarativeUiSchema.kt)
- **分析**: `:plugin-ui` 模块定义了白名单受限的声明式 UI 解析器（`DeclarativeUiSchema`，涵盖 `Section`, `Text`, `Status`, `Toggle`, `Select`, `Button`, `PermissionRequest`, `CapabilityPicker`），但主 App 前端仅定义了简易的 `PluginQuickAction` 动作条，完全未实现 `UiContribution` 状态卡与设置项的 Compose 宿主渲染器。

### P-24. 架构边界测试与 CoreNavigation 强行耦合遗留 Agent 内部 session ID
- **偏离条款**: §9.0, §13
- **源码位置**: [`ArchitectureBoundaryTest.kt:41-45`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ArchitectureBoundaryTest.kt#L41-L45)
- **分析**: `ArchitectureBoundaryTest` 在断言 `CoreNavigation` 时显式构造了 `activeSessionId = "session-1"`，将前端协议概念与 Agent 内部私有 Session ID 混为一谈，违反了“Android 不接收或生成 agentSessionId”的协议隔离原则。

---

## 5. 第四部分：全量问题与代码位置全景矩阵 (Master Issue Matrix)

以下为工程中全部 **72 项现存与潜在缺陷清单**，按严重程度、模块分类及行号精准定位：

| 编号 | 类别 | 严重程度 | 问题简述 | 具体代码文件及行号 | 对应标准条款 |
|---|---|---|---|---|---|
| **01** | 系统服务 | 🔴 阻断 | `AssistantSession` 为纯空类，系统数字助理入口完全瘫痪 | [`AssistantSessionService.kt:8-13`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantSessionService.kt#L8-L13) | §3.2, §9.0 |
| **02** | 系统服务 | 🔴 阻断 | `app/build.gradle.kts` 缺失 `:assistant-holder` 依赖声明 | [`app/build.gradle.kts:34-56`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/build.gradle.kts#L34-L56) | ADR-0041 |
| **03** | 系统服务 | 🔴 阻断 | `AssistantOverlay` 硬编码在主 Activity 内，无法作为系统悬浮窗跨应用唤起 | [`MainActivity.kt:82`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L82) | §3.2, ADR-0041 |
| **04** | 导航生命周期 | 🔴 阻断 | 缺乏 BackHandler，在设置页按系统返回键直接退出杀掉 App | [`MainActivity.kt:35, 58-79`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L35) | §3.1, §5.1 |
| **05** | 动效系统 | 🔴 阻断 | 助理展开栏 ↔ 停靠球连续 Morph 几何形变完全缺失（双分支硬切） | [`AssistantOverlay.kt:98-151`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantOverlay.kt#L98-L151) | §11.3 |
| **06** | 动效系统 | 🔴 阻断 | 页面层级切换零转场动画（`when` 瞬切导致画面视觉撕裂） | [`MainActivity.kt:58-79`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L58-L79) | §11.2, Apple #7 |
| **07** | 动效系统 | 🔴 阻断 | 悬浮球释放手势未传递初速度到弹簧系统，产生瞬移与断层 | [`DockedBall.kt:145-153`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L145-L153) | Apple Design #5 |
| **08** | 数据管线 | 🔴 阻断 | 规范 §16 定义的四大领域核心接口全量缺失 | [`state/ConversationViewModel.kt`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt) | §16 |
| **09** | 数据管线 | 🔴 阻断 | 附件门控提交与待提交意图（`PendingSubmissionIntent`）机制缺失 | [`ConversationViewModel.kt:81-93`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L81-L93) | §12.1, ADR-0046 |
| **10** | 数据管线 | 🔴 阻断 | 停止生成未对接远端取消协议，本地直接伪造生成终止文本 | [`ConversationViewModel.kt:197-225`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L197-L225) | §13 |
| **11** | 数据管线 | 🔴 阻断 | 本地加密对话镜像 (`encrypted-store`) 未接入，进程死亡历史全失 | [`state/ConversationViewModel.kt`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt) | §15, ADR-0044 |
| **12** | 架构坏味 | 🔴 阻断 | 遗留 4 个 MVP Presenter 与 Compose ViewModel 并存混用形成双重状态源 | [`MainActivity.kt:38-41`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L38-L41) | ADR-0001, §22 |
| **13** | 架构坏味 | 🔴 阻断 | ViewModel 暴露两个平行 StateFlow，违反主 App 与助理共享模型规范 | [`ConversationViewModel.kt:27-32`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L27-L32) | §7 |
| **14** | 动效系统 | 🟡 严重 | 时间线新消息进入 160–190ms 4dp offset + opacity 动效缺失 | [`ConversationTimeline.kt:140-145`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/ConversationTimeline.kt#L140-L145) | §11.2 |
| **15** | 动效系统 | 🟡 严重 | 全局减少动态 (Reduce Motion) 模式未生效（死代码） | [`AppleMotion.kt:74-77`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/AppleMotion.kt#L74-L77) | §11.4, §20 |
| **16** | 动效系统 | 🟡 严重 | 悬浮球拖拽缺少触控点局部抓取偏移（Grab Offset）锁定 | [`DockedBall.kt:113-135`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L113-L135) | Apple Design #2 |
| **17** | 动效系统 | 🟡 严重 | 悬浮球完成状态脉冲无限循环播放（应为单次脉冲收敛） | [`DockedBall.kt:84-94`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L84-L94) | Standard #1 |
| **18** | 动效系统 | 🟡 严重 | 信号缝线 360° 旋转与呼吸动效无限循环无休止播放 | [`SignalStitch.kt:90-117`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/SignalStitch.kt#L90-L117) | Standard #8 |
| **19** | 动效系统 | 🟡 严重 | 防抖批次进度条 100ms 离散阶梯跳跃导致视觉微卡顿 | [`DebounceBatchContainer.kt:49-54`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/DebounceBatchContainer.kt#L49-L54) | Standard #3 |
| **20** | 动效系统 | 🟡 严重 | 按下缩放参数 (0.97f vs 0.98f) 与手势仲裁延迟 | [`AppleMotion.kt:130-156`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/AppleMotion.kt#L130-L156) | §11.2, Apple #1 |
| **21** | 动效系统 | 🟡 严重 | 工具卡展开使用 `expandVertically` 触发布局全量重新测量 | [`ToolCallCard.kt:147-151`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/ToolCallCard.kt#L147-L151) | Standard #7 |
| **22** | 动效系统 | 🟡 严重 | 工具卡执行中状态无旋转/脉冲动效（静态图标） | [`ToolCallCard.kt:97-101`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/ToolCallCard.kt#L97-L101) | §6.1 |
| **23** | 动效系统 | 🟡 严重 | 悬浮球物理边界写死 1080x2400 @ 3x 分辨率与密度参数 | [`DockedBall.kt:64-68`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L64-L68) | §9.3, §20 |
| **24** | 动效系统 | 🟡 严重 | 流式输出每个 Token 均触发滚动动画协程重入 | [`ConversationTimeline.kt:82-89`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/ConversationTimeline.kt#L82-L89) | Standard #7 |
| **25** | 动效系统 | 🟡 严重 | 屏幕圈选缺少多点触控（Multi-touch）保护与贝塞尔平滑滤波 | [`ScreenSelectionOverlay.kt:84-129`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/ScreenSelectionOverlay.kt#L84-L129) | Apple Design #10 |
| **26** | 导航生命周期 | 🟡 严重 | 缺少 Android 14+ 预测性返回 (Predictive Back) 声明与适配 | [`app/src/main/AndroidManifest.xml`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/AndroidManifest.xml) | §2, §20 |
| **27** | 导航生命周期 | 🟡 严重 | 屏幕旋转/配置变更导致圈选笔画与弹窗表单状态丢失 | [`ScreenSelectionOverlay.kt:75-78`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/ScreenSelectionOverlay.kt#L75-L78); [`PlatformSettingsScreen.kt:76-81`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/settings/PlatformSettingsScreen.kt#L76-L81) | §20, §21 |
| **28** | 导航生命周期 | 🟡 严重 | 插件管理路由重定向至设置页，未提供独立插件页或深色入口 | [`MainActivity.kt:63`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L63) | §4.1, ADR-0034 |
| **29** | 导航生命周期 | 🟡 严重 | 侧边栏“附件与媒体缓存”点击为仅关闭抽屉的空操作 | [`MainScreen.kt:137-139`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/main/MainScreen.kt#L137-L139) | §3.1, §4.1 |
| **30** | 系统服务 | 🟡 严重 | 屏幕圈选遮罩未渲染底层截图位图，用户盲圈 | [`ScreenSelectionOverlay.kt:80-84`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/ScreenSelectionOverlay.kt#L80-L84) | §10.1 |
| **31** | 系统服务 | 🟡 严重 | 屏幕选区确认生成伪造字节大小并直接跳过哈希与压缩验证 | [`ConversationViewModel.kt:337-361`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L337-L361) | §3.4, §10.1 |
| **32** | 系统服务 | 🟡 严重 | 助理展开栏“在 App 中打开”仅关闭浮层，未实际跳转对应线程 | [`AssistantOverlay.kt:131-133`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantOverlay.kt#L131-L133) | §9.1 |
| **33** | 系统服务 | 🟡 严重 | 助理展开栏停止生成按钮写死 `{}` 空回调 | [`AssistantExpandedBar.kt:223`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantExpandedBar.kt#L223) | §9.1, §13 |
| **34** | 数据假状态 | 🟡 严重 | “在线同步中”永远硬编码显示，`isOffline` 为全局死代码 | [`MainScreen.kt:172`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/main/MainScreen.kt#L172); [`ConversationUiState.kt:150`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationUiState.kt#L150) | §5.1, §15 |
| **35** | 数据假状态 | 🟡 严重 | 新建对话标题使用随机数假数据 (`% 10000`) | [`ConversationViewModel.kt:230`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L230) | §6.3, §14.2 |
| **36** | 数据假状态 | 🟡 严重 | 设置与旧 Presenter 包含 `example.com` / `192.168.1.100` 假地址 | [`PlatformSettingsScreen.kt:358, 376`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/settings/PlatformSettingsScreen.kt#L358); [`GatewayScreen.kt:10`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayScreen.kt#L10) | §3.1, §4.3 |
| **37** | 数据假状态 | 🟡 严重 | ToolCallInfo 默认写死 `SUCCESS` 与 `240ms` 假耗时 | [`ConversationUiState.kt:61-62`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationUiState.kt#L61-L62) | §6.1 |
| **38** | 数据假状态 | 🟡 严重 | 附件选取直接标为 `VERIFIED` 与 `100%` 进度跳过上传校验 | [`ConversationUiState.kt:47-48`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationUiState.kt#L47-L48) | §12.1, §12.2 |
| **39** | 数据假状态 | 🟡 严重 | 未配置网关时抽屉显示假文案“离线镜像” | [`SidebarDrawer.kt:242`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/main/SidebarDrawer.kt#L242) | §19.1 |
| **40** | 数据管线 | 🟡 严重 | 斜杠命令仅硬编码匹配 `/new`，未对接动态命令目录接口 | [`ConversationViewModel.kt:227-232`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L227-L232) | §14.1, §17.3 |
| **41** | 数据管线 | 🟡 严重 | 消息模型未采用规范 §7 `MessagePart` 密封接口 | [`ConversationUiState.kt:41, 70`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationUiState.kt#L41) | §7 |
| **42** | 数据管线 | 🟡 严重 | 状态轴未建立规范 §9.0 四轴状态机 (`ComposerState` 缺失) | [`ConversationUiState.kt:135-152`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationUiState.kt#L135-L152) | §9.0 |
| **43** | 数据管线 | 🟡 严重 | 附件选取器未强制校验 4 个文件、25MB 大小及封闭 MIME 类型限制 | [`MainScreen.kt:78-107`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/main/MainScreen.kt#L78-L107) | §12, `artifact-ports` |
| **44** | 组件无障碍 | 🟡 严重 | 平板/横屏 (Tablet/Landscape) 多栏响应式布局缺失 | [`MainScreen.kt:64-275`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/main/MainScreen.kt#L64-L275) | §5.2 |
| **45** | 组件无障碍 | 🟡 严重 | 助手消息缺少 Markdown 与代码语法高亮渲染（纯 Text） | [`MessageItem.kt:226-232`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L226-L232) | §6.1 |
| **46** | 组件无障碍 | 🟡 严重 | 远端已删除占位 (`DeletedTombstoneItem`) 未在时间线接入 | [`MessageItem.kt:63-77, 303-327`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L63-L77) | §6.1 |
| **47** | 组件无障碍 | 🟡 严重 | 发送失败消息缺少“点击重试”与“继续发送”交互入口 | [`MessageItem.kt:170-176`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L170-L176) | §19.2 |
| **48** | 架构坏味 | 🟡 严重 | Gateway 选择器仅为二选一 toggle，不支持 3+ 网关选择 | [`SidebarDrawer.kt:208-209`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/main/SidebarDrawer.kt#L208-L209) | §5.1 |
| **49** | 架构坏味 | 🟡 严重 | `clearCache` 直接递归删除操作系统私有目录 `context.cacheDir` | [`ConversationViewModel.kt:467-505`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L467-L505) | §15, §17.7 |
| **50** | 架构坏味 | 🟡 严重 | `LocalColorTokens` 使用 `staticCompositionLocalOf` 触发全量根重组 | [`ThemeTokens.kt:62`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/ThemeTokens.kt#L62) | 架构规范 |
| **51** | 架构坏味 | 🟡 严重 | 消息行使用 `height(IntrinsicSize.Min)` 触发强制双重测量 | [`MessageItem.kt:202`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L202) | 架构规范 |
| **52** | 架构坏味 | 🟡 严重 | 复制状态缺少延迟复位机制导致永久显示“已复制” | [`MessageItem.kt:197, 246-247`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/MessageItem.kt#L197) | 编码规范 |
| **53** | 架构坏味 | 🟡 严重 | Application 缺乏进程感知隔离，助理私有进程并发重复初始化内核与审计库 | [`OpenAndroidIntelligenceApplication.kt:31-53`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt#L31-L53) | §9.0 |
| **54** | 架构坏味 | 🟡 严重 | 插件声明式 UI (DeclarativeUiSchema) 在前端缺少宿主渲染器 | [`PluginActionSlots.kt:22-67`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/plugin/PluginActionSlots.kt#L22-L67) | §18.1, ADR-0010 |
| **55** | M3与主题 | 🔵 一般 | 缺少 Android 15 Edge-to-Edge 边到边沉浸适配 (`enableEdgeToEdge`) | [`MainActivity.kt:43-55`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L43-L55) | §4.4, §20 |
| **56** | M3与主题 | 🔵 一般 | Manifest 缺少全局 App Theme 资源声明导致冷启动闪白 | [`app/src/main/AndroidManifest.xml:5-18`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/AndroidManifest.xml#L5-L18) | §21 |
| **57** | M3与主题 | 🔵 一般 | Typography 大标题字间距使用绝对单位 `(-0.02).sp` 而非相对 em | [`ThemeTokens.kt:105`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/ThemeTokens.kt#L105) | §4.3, Apple #15 |
| **58** | M3与主题 | 🔵 一般 | Material 3 ColorScheme 映射遗漏部分语义容器槽位 | [`ThemeTokens.kt:139-171`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/ThemeTokens.kt#L139-L171) | §4.2 |
| **59** | 组件无障碍 | 🔵 一般 | 存在多处低于 48×48dp 最小触摸目标的控件 (18dp, 28dp, 32dp) | [`AssistantExpandedBar.kt:135, 151, 167`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantExpandedBar.kt#L135); [`PlatformSettingsScreen.kt:196`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/settings/PlatformSettingsScreen.kt#L196); [`AttachmentDraftBar.kt:154`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/AttachmentDraftBar.kt#L154) | §4.4, §20 |
| **60** | 组件无障碍 | 🔵 一般 | 屏幕圈选缺少 TalkBack 矩形边界微调替代操作 | [`ScreenSelectionOverlay.kt:80-245`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/ScreenSelectionOverlay.kt#L80-L245) | §10.1, §20 |
| **61** | 组件无障碍 | 🔵 一般 | 助理展开栏回复最大高度写死 240dp，未按 60% 屏幕动态适配 | [`AssistantExpandedBar.kt:193`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/AssistantExpandedBar.kt#L193) | §9.1 |
| **62** | 组件无障碍 | 🔵 一般 | 4dp 基础间距网格违规数十处 (3dp, 6dp, 7dp, 300dp 等) | 全量 UI 文件 | §4.4 |
| **63** | 动效系统 | 🔵 一般 | 全工程 Compose `@Preview` 预览函数数量为 0 | 全量 UI Composable 文件 | §21.1 |
| **64** | 动效系统 | 🔵 一般 | `pressScaleFeedback` 采用已废弃的 `composed { ... }` 包装 | [`AppleMotion.kt:130-156`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/design/AppleMotion.kt#L130-L156) | 编码规范 |
| **65** | 架构坏味 | 🔵 一般 | 格式化字节计算 `formatBytes` 存在数学边界 NaN 风险 | [`ConversationViewModel.kt:507-514`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/state/ConversationViewModel.kt#L507-L514) | 编码规范 |
| **66** | 架构坏味 | 🔵 一般 | 存在多处未使用的 Import 垃圾代码残留 | `MessageItem.kt:46`, `SharedComposer.kt:33`, `AttachmentDraftBar.kt:3-7` 等 | 编码规范 |
| **67** | 架构坏味 | 🔵 一般 | 仪表化测试依赖已废弃的 MVP Presenter 假象，未覆盖 Compose/ViewModel | [`CoreWithoutPluginsInstrumentedTest.kt:19-44`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/androidTest/kotlin/com/openandroidintelligence/mobile/CoreWithoutPluginsInstrumentedTest.kt#L19-L44) | §21 |
| **68** | 架构坏味 | 🔵 一般 | 架构边界测试与 CoreNavigation 强行耦合遗留 Agent 内部 session ID | [`ArchitectureBoundaryTest.kt:41-45`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ArchitectureBoundaryTest.kt#L41-L45) | §9.0, §13 |
| **69** | 交互细节 | 🔵 一般 | 附件上传指示器使用固定转圈而非反映 `uploadProgress` 浮点数 | [`AttachmentDraftBar.kt:108-112`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/AttachmentDraftBar.kt#L108-L112) | §12.1 |
| **70** | 交互细节 | 🔵 一般 | 附件卡片文件名文本宽度硬编码 `90.dp` 易发生不必要截断 | [`AttachmentDraftBar.kt:129`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/AttachmentDraftBar.kt#L129) | §4.3 |
| **71** | 交互细节 | 🔵 一般 | 输入框缺少 IME 操作行为绑定 (`ImeAction.Send`) | [`SharedComposer.kt:123-138`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/chat/SharedComposer.kt#L123-L138) | 交互规范 |
| **72** | 交互细节 | 🔵 一般 | 悬浮球拖入关闭区判定缺少底部可视化放置靶点（Drop Target）容器 | [`DockedBall.kt:137`](file:///mnt/数据/项目/open-android-intelligence/.worktrees/conversation-assistant-ui/apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/ui/assistant/DockedBall.kt#L137) | §9.3 |
