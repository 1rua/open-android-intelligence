# OAI Android Material Design 3 Expressive 全面 UI 审查报告

- **日期**：2026-11-11
- **关联 Issue**：`#138`
- **关联主规范**：`DESIGN.md`
- **审查范围**：`apps/android/conversation-ui`、`apps/android/app`、`apps/android/assistant-holder`、`apps/android/plugin-ui` 及 `docs/specs/` 既有设计规格
- **审查性质**：只读全面审查与规范建模（本次不重构现有界面、不修改业务功能、不直接覆盖现有主题）

---

## 一、审查背景与权威基准

本次审查以 **Google 官方 Material Design 3 (M3) Expressive** 与 **Jetpack Compose 官方指南**为最高权威依据，同时对标以下三份参考资料：

1. [`google-labs-code/design.md`](https://github.com/google-labs-code/design.md)：确立面向 AI Coding Agent 的设计规范文档组织范式（气韵散文定调 + 精确令牌表格 + 明确的正反护栏 Do's & Don'ts）。
2. [`canine-labs/m3-expressive-design-md`](https://github.com/canine-labs/m3-expressive-design-md)：提炼 M3 Expressive 四大核心维度——感知均匀色彩（Perceptual Color）、表现力排版（Expressive Typography）、形状对比与动态形变（Shape Morphing）、空间与形态双轨弹簧物理动效（Spatial vs Effects Springs）。
3. [`hamen/material-3-skill`](https://github.com/hamen/material-3-skill)：对照 Jetpack Compose 组件契约、色调表面高度（Tonal Elevation）、自适应断点（Window Size Classes）、无障碍对比度与最小触控热区（48dp）检查项。

### 社区规范与官方规范 / 工程依赖冲突裁决

| 冲突点 | 社区仓库表述 | Google 官方与当前工程实际情况 | 最终裁决（写入 `DESIGN.md`） |
| :--- | :--- | :--- | :--- |
| **技术栈与单位** | `canine-labs` 与 `hamen` 部分章节混用 Web `@material/web`、CSS 变量、`rem`/`px`、`cubic-bezier` | Google 官方已将 `@material/web` 置为维护模式，Android 端以 Jetpack Compose 为唯一主力实现 | 全量采用 Jetpack Compose 原生 `dp`/`sp`、`ColorScheme`、`SpringSpec`、`WindowInsets` 体系 |
| **M3 1.4.0+ 实验性 API** | 提及 `MaterialExpressiveTheme`、`MotionScheme`、`SplitButtonLayout`、`FloatingToolbar`、`WavyProgressIndicator` | 当前工程锁定 `androidx.compose:compose-bom:2024.12.01`（`material3:1.3.1`），字节码尚不包含上述 1.4.0-alpha+ 类 | 以当前依赖版本实际支持的 API 为准，统一复用 `OpenAndroidIntelligenceTheme`、`MotionSpecs`、`AppRadius` 与 `SignalStitch` |
| **平板设置呈现形态** | 通用教程倾向全屏 `ListDetailPaneScaffold` | OAI 既有规格（`docs/specs/2026-11-03-android-settings-sidebar-and-tablet-panel.md`）明确要求 `>=840.dp` 时设置在左侧 `320.dp` 边栏原位替换，不遮挡右侧主对话 | 保留并固化 OAI 已建立的「手机左入全屏 + 平板 `320.dp` 侧栏原位替换」双态架构 |

---

## 二、OAI Android 既有优秀设计资产盘点（应在后续开发中严格保留）

通过全面代码走读，OAI Android 已经在 `:conversation-ui` 与 `:app` 中建立了一套辨识度高、工程纪律严密的原生设计语言：

### 1. 主题、感知均匀色彩与自动化对比度守护
- **核心实现**：
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/Theme.kt`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppStateColors.kt`
- **已验证能力**：
  - 支持 Android 12+ (API 31+) 系统动态取色（Monet），并内置 6 套基于 CIE LCh 色彩空间生成的预设调色板（默认 `EMBER` 炽橙 `h=46°, C=80`，以及 `INDIGO`, `AZURE`, `VIOLET`, `CORAL`, `GRAPHITE`）。
  - 提供独立于主题色相漂移的 `LocalAppStateColors`（`success` / `warning` 及其容器色），并通过 `ThemeContrastTest.kt` 自动验证全部 6 套调色板明暗模式下的 WCAG AA（`>= 4.5:1`）对比度。
  - `animatedColorScheme()` 实现了全角色 `250ms` 平滑颜色过渡，切换明暗或色板零闪烁。

### 2. 形状令牌、非对称气泡与 Shape Morphing
- **核心实现**：
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/DesignTokens.kt`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/MessageTimeline.kt`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/WorkbenchScreen.kt`
- **已验证能力**：
  - `AppRadius` 严格收敛为 `4/8/12/16/20/24/28.dp` 七档刻度，由 `MaterialTokenGuardTest.kt` 静态扫描守护。
  - 用户气泡（右下 `4.dp` 尾角）与智能体气泡（左下 `4.dp` 尾角）共享 `surfaceContainerHigh` 底色，以几何指向替代刺眼的高饱和度大色块。
  - 发送按钮到停止按钮采用 `SnappySpring` 驱动宽度（`48.dp -> 56.dp`）、圆角（`50% -> 36%`）与底色连续物理形变。

### 3. Signal Stitch（信号缝线）品牌签名与真实执行反馈
- **核心实现**：
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/SignalStitch.kt`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/VoiceRecordingRow.kt`
- **已验证能力**：
  - `2.dp` `primary` 竖线 + `2.3.dp` `tertiary` 脉冲圆点（失败时切换为静态 `error`）贯穿登录、引导、空状态与智能体回复左侧导轨，完全摒弃了廉价的 AI 星星/渐变套路。
  - `ExecutionProcessPanel` 使用 `AppTextStyles.Thought` (`13.sp/18.2.sp`) 收纳思考与工具调用，流式完成自动平滑折叠，且严格尊重用户的手动展开/收起干预。
  - `VoiceRecordingRow` 原位替换输入栏，以 `50ms` 周期维护 24 帧真实麦克风振幅滚动波形，并合并无障碍语义节点。

### 4. 双轨物理弹簧、手势阻尼与无障碍动效降级
- **核心实现**：
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/MotionSpecs.kt`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/AppTransitions.kt`
  - `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/MotionPolicy.kt`
- **已验证能力**：
  - 区分空间位移弹簧（`SpatialSpring`, `MomentumSpring`, `SheetSpring`, `EntranceSpring`）、形态弹簧（`MorphSpring`, `SnappySpring`）与临界阻尼折叠弹簧（`ExpandSpring`, `CollapseSpring`）。
  - 内置 `MotionSpecs.rubberband(c = 0.55f)` 橡皮筋阻尼与 `MotionSpecs.projectMomentum(d = 0.998f)` 动量投射公式，由 `MotionPhysicsTest.kt` 校验数学性质。
  - 全局 `LocalMotionPolicy` 同时响应应用内「减弱动态」设置与系统级 `ANIMATOR_DURATION_SCALE == 0f`，由 `AppTransitionsTest.kt` 守护降级行为。

---

## 三、全面审查发现的现有实现偏差与技术债清单

本次只读审查在 4 个 Android 模块中识别出以下偏离 Material 3 Expressive、Jetpack Compose 官方最佳实践或 OAI 自身令牌规范的具体问题，按严重程度分为 **P0（功能/无障碍缺陷）**、**P1（Insets 与组件一致性问题）** 与 **P2（令牌覆盖率与架构演进项）**。

### 1. P0 级问题：WindowInsets 双重叠加与无障碍语义缺陷

| 编号 | 模块与文件相对路径 | 行号 | 问题描述 | 规范依据与后续修复方向 |
| :--- | :--- | :--- | :--- | :--- |
| **P0-1** | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt` | `171` | **气泡窗口软键盘双重缩进 Bug**：`Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding())`。`safeDrawing` 已包含 `ime`，再次串联 `.imePadding()` 会导致软键盘弹出时键盘高度被扣除两次。 | 违反 `DESIGN.md` §8.4 铁律 1。应移除冗余的 `.imePadding()` 或改为 `systemBarsPadding().imePadding()`。 |
| **P0-2** | `apps/android/plugin-ui/src/main/kotlin/com/openandroidintelligence/plugin/ui/PluginDeclarativeUi.kt` | `246` | **插件开关无障碍角色错误**：`RenderToggle` 直接调用了 `SettingsListItem(onClick = ...)`，导致整行被赋予 `Role.Button` 而非 `Role.Switch`，TalkBack 无法朗读开关状态。 | 违反 `DESIGN.md` §9.7。应直接复用 `:conversation-ui` 已有的 `SettingsSwitchItem`（内置 `Role.Switch` 与 `Modifier.toggleable`）。 |
| **P0-3** | `apps/android/plugin-ui/src/main/kotlin/com/openandroidintelligence/plugin/ui/PluginDeclarativeUi.kt` | `261-284` | **插件单选组缺失无障碍语义且触控热区不足**：`RenderSelect` 未使用 `Modifier.selectableGroup()`，子项仅用 `Row.clickable` 而未声明 `Role.RadioButton`，且垂直内边距仅 `4.dp`（远低于 `48.dp` 最小热区）。 | 违反 `DESIGN.md` §4.2 & §9.7。应补充 `selectableGroup()`、`Modifier.selectable(role = Role.RadioButton)` 及 `heightIn(min = Dimensions.MinimumTouchTarget)`。 |
| **P0-4** | `apps/android/app/src/main/AndroidManifest.xml` | `91-100`, `125-137` | **子 Activity 缺失预测性返回声明**：`MainActivity` 声明了 `android:enableOnBackInvokedCallback="true"`，但 `BubbleConversationActivity` 与 `AssistantActivity` 均未声明。 | 违反 `DESIGN.md` §8.2。应在上述 Activity 或 `<application>` 节点统一开启 `enableOnBackInvokedCallback="true"`。 |

### 2. P1 级问题：Insets 顶栏位移、触控热区不足与 Edge-to-Edge API 统一

| 编号 | 模块与文件相对路径 | 行号 | 问题描述 | 规范依据与后续修复方向 |
| :--- | :--- | :--- | :--- | :--- |
| **P1-1** | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsScaffold.kt` | `50-52` | **设置页顶栏随软键盘被整体顶起**：在 `Scaffold(modifier = modifier.imePadding(), ...)` 最外层施加了 `imePadding()`，当设置子页弹出键盘时，`TopAppBar` 会随整页一起被往上推。 | 违反 `DESIGN.md` §8.4 铁律 3。应将 `imePadding` 移入 `Scaffold` 内部内容容器，保持 `TopAppBar` 固定。 |
| **P1-2** | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt` | `148-150` | **登录页底部导航栏与键盘高度叠加留白**：串联 `.statusBarsPadding().imePadding().navigationBarsPadding()` 未消费 Insets，键盘展开时多出一条导航栏高度空白。 | 违反 `DESIGN.md` §8.4 铁律 2。应改用 `windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))`。 |
| **P1-3** | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt` | `128`, `284` | **侧栏模式下嵌套重复 `imePadding`**：外层 `Box`（284行）与内层 `LazyColumn`（128行）均调用 `.imePadding()` 且中间未 `consumeWindowInsets`。 | 违反 `DESIGN.md` §8.4 铁律 4。仅在单层消费 `ime` Insets。 |
| **P1-4** | `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantActivity.kt` | `40-52` | **数字助手 Activity 未统一使用 `enableEdgeToEdge`**：直接操作 `window.setDecorFitsSystemWindows(false)` 与 `insetsController`，未复用主应用的 `SystemBarStyle.auto` 模式。 | 违反 `DESIGN.md` §8.3。应迁移至 `enableEdgeToEdge()` 并与主题 `isDark` 联动。 |
| **P1-5** | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/` | 多处 | **引导页触控热区低于 `48.dp` 且存在无反馈点击**：`TelemetryRow.kt:41` (`44.dp`)、`DeploymentChoiceScreen.kt:156` (`44.dp`)、`PermissionsScreen.kt:174` (`44.dp`)、`GatewayGuideScreen.kt:447` (`34.dp`)；`DeploymentChoiceScreen.kt:235` 使用 `clickable(indication = null)` 抹除了触控反馈。 | 违反 `DESIGN.md` §4.2 & §9.1。统一提升至 `Dimensions.MinimumTouchTarget (48.dp)` 并改用 `bouncyClickable` 或标准 Ripple。 |

### 3. P2 级问题：硬编码字号/间距/圆角与重复组件收敛

| 编号 | 模块与文件相对路径 | 行号 | 问题描述 | 规范依据与后续修复方向 |
| :--- | :--- | :--- | :--- | :--- |
| **P2-1** | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/` | 多处 | **引导页硬编码非标字号与 `ExtraBold` 字重**：如 `WelcomeScreen.kt:99,175` (`54.sp`, `34.sp`)、`AppearanceSetupScreen.kt:53` (`30.sp`, `ExtraBold`)、`GatewayGuideScreen.kt:273,401` (`26.sp` 以及低于可读下限的 `9.sp`)。 | 违反 `DESIGN.md` §3.1 & §3.2。应收敛至 `MaterialTheme.typography` 标准角色，并将 `9.sp` 提升至 `labelSmall (11.sp)`。 |
| **P2-2** | `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/MarkdownViews.kt` | `119-129`, `216`, `253`, `332` | **Markdown 渲染器存在硬编码字号与非标圆角**：标题字号写死 `18.sp..13.sp`，表格与代码块写死 `12.5.sp` / `11.5.sp` / `10.sp`，引用竖条使用 `RoundedCornerShape(1.5.dp)`（目前 `MaterialTokenGuardTest` 仅扫描 `:app` 目录，未扫描 `:conversation-ui`，因此漏检）。 | 违反 `DESIGN.md` §3.2 & §6.1。应映射到排版令牌，并将 `MaterialTokenGuardTest` 的扫描范围扩展至 `:conversation-ui`、`:assistant-holder` 与 `:plugin-ui`。 |
| **P2-3** | `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/TimelineScaffold.kt` & `apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/ui/AssistantExpandedPanel.kt` | `133` / `334` | **重复定义的 `ScrollToLatestButton`**：两个模块各自持有一份私有 `ScrollToLatestButton` 实现，存在后续样式演进分叉风险。 | 应将 `:conversation-ui` 中的 `ScrollToLatestButton` 设为 `public` 并在 `:assistant-holder` 中直接复用。 |
| **P2-4** | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentSessionsScreen.kt` | 全文件 | **未被引用的孤立遗留文件**：全文件含多处硬编码 `dp` 间距，且在主流程与测试中均无任何调用点。 | 后续清理任务中可核实后移入回收区或归档移除。 |

---

## 四、结论与后续演进建议

1. **规范落地**：项目根目录已生成完整的 `DESIGN.md`，将 Google Material Design 3 Expressive 官方规范、Jetpack Compose 最佳实践与 OAI 既有的「安静、可信的本机工作台」设计语言融为一体，明确了色彩、排版、布局、表面、形状、动效、导航/Insets、专有组件与 Do's & Don'ts 检查表。
2. **分步治理路径**：
   - **第一阶段（P0 修复）**：优先单独开分支修复 `BubbleConversationActivity` 双重键盘缩进、`PluginDeclarativeUi` 开关/单选无障碍角色缺陷与 Manifest `enableOnBackInvokedCallback` 缺失。
   - **第二阶段（P1 治理）**：统一整改 `SettingsScaffold`、`GatewayLoginScreen`、`NotificationAppPicker` 的 `WindowInsets` 消费链路，并补齐引导页 `48.dp` 最小触控热区。
   - **第三阶段（P2 门禁扩围）**：将 `MaterialTokenGuardTest` 的源码静态扫描范围从仅 `:app` 扩展到 `:conversation-ui`、`:assistant-holder` 与 `:plugin-ui`，彻底消灭跨模块的硬编码字号与非标圆角。
