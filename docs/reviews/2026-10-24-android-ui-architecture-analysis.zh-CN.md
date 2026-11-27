# apps/android UI 架构全面分析

日期：2026-10-24。分析范围：`apps/android` 下 `app`、`conversation-ui`、`plugin-ui`、`assistant-holder` 四个含界面的模块（不含 `build/`、`.gradle/`、`.toolchains/` 产物）。

## 一、结论速览

| 关注点 | 结论 |
|---|---|
| UI 组件栈 | **纯 Jetpack Compose + Material 3**，零 Fragment、零 XML 布局、零 AppCompat/MDC-View |
| 页面间导航 | **无 `nav_graph.xml`**。顶层用状态驱动的 `AnimatedContent`，只有设置子页用 Compose `NavHost` |
| 主题 | Compose `MaterialTheme`，动态取色（Monet）默认开启；5 套预设色板按 CIELAB 色调映射生成；无 `values-night` |
| 登录页 | 存在，`GatewayLoginScreen`，真实对接 Gateway Protocol v2（地址 + 账号 + 密码 / 邀请短码 / 设备密钥） |
| 持久化 | **SharedPreferences 为主**（14 个命名空间），SQLite + Keystore 加密镜像做会话持久化；**全仓无 DataStore、无 Room** |
| 首次启动检测 | **不存在通用的"首次启动"标记**。界面的首屏判定完全由 `ConnectionPhase` 驱动；只有若干"只提示一次"的细粒度标记 |

---

## 二、Activity / Fragment / Compose Screen 结构

### 2.1 Activity 清单（全仓只有 3 个）

| Activity | 文件 | 基类 | 说明 |
|---|---|---|---|
| `MainActivity` | [MainActivity.kt](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L87) | `ComponentActivity` | 唯一主入口；登录 ↔ 工作台 ↔ 设置弹层全在这一个 Activity 内 |
| `BubbleConversationActivity` | [BubbleConversationActivity.kt](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/BubbleConversationActivity.kt#L39) | `ComponentActivity` | 系统"会话气泡"窗口，`documentLaunchMode="always"`，可多实例 |
| `AssistantActivity` | [AssistantActivity.kt](../../../apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantActivity.kt#L19) | **`android.app.Activity`** | 数字助理 ASSIST 兜底入口，跑在 `:assistant_session` 独立进程，半透明浮层主题 |

`AssistantActivity` 用的是裸 `Activity` 而非 `ComponentActivity`，因此没有现成的 `ViewTreeLifecycleOwner` / `SavedStateRegistryOwner`。[AssistantComposeHost.kt](../../../apps/android/assistant-holder/src/main/kotlin/com/openandroidintelligence/assistant/AssistantComposeHost.kt) 用 `FrameLayout` + 自建 `LifecycleRegistry` + `SavedStateRegistryController` 手工补齐这两个 owner，再把 `ComposeView` 挂上去。这是全仓唯一一处 Compose 与 View 树的显式桥接。

### 2.2 Fragment

**零 Fragment。** 全仓（排除 `build/`）没有任何 `androidx.fragment` 引用，也没有 `FragmentActivity` / `: Fragment` 声明。

### 2.3 Manifest 中的组件与 Activity 别名

[AndroidManifest.xml](../../../apps/android/app/src/main/AndroidManifest.xml) 里值得注意的两点：

1. **双启动器别名实现图标动态取色开关**（`activity-alias` + `PackageManager.setComponentEnabledSetting`）：
   - `.MainActivityThemed`（默认启用，图标含 `<monochrome>` 单色层，支持 Material You 主题图标）
   - `.MainActivityClassic`（默认禁用，无单色层，保持经典黑白 + 橙色行星点）
   - 切换逻辑在 [LauncherIconManager.kt](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/LauncherIconManager.kt#L20)

2. **显式声明 `androidx.activity.ComponentActivity`**（`exported=false`）供 Robolectric + `createComposeRule` 解析，因为 `ui-test-manifest` 只在 debug 变体的单元测试清单里出现。

### 2.4 Compose Screen 分层

```
app（宿主壳，71 文件 / 15k 行）
├── MainActivity.kt              根组合：主题 → 登录/工作台切换 → SnackbarHost
├── GatewayLoginScreen.kt        登录页（651 行）
├── SettingsScreen.kt            设置（1473 行，含 8 个 composable 子页）
├── PlatformSettingsBottomSheet.kt / PlatformSettingsScreen.kt   两个包装器
├── SettingsCapabilityUi.kt      能力开关、通告条
├── BubbleConversationActivity.kt 气泡窗口
├── notifications/NotificationCollectionScreen.kt
├── plugins/PluginManagementScreen.kt / NativePluginSurface.kt
└── updates/AppUpdateScreen.kt

conversation-ui（可复用 UI 库，47 文件 / 20k 行）
├── theme/          Theme.kt AppPalettes.kt AppStateColors.kt AppTypography.kt DesignTokens.kt
├── components/     SettingsComponents.kt StateViews.kt SignalStitch.kt ExpandableRegion.kt
├── workbench/      WorkbenchScreen.kt MessageTimeline.kt ComposerBar.kt ThreadDrawer.kt
│                   AttachmentLibraryScreen.kt FloatingConversationPanel.kt ApprovalCard.kt
│                   markdown/  AttachmentLightbox.kt ImagePreview.kt ScreenSelectionOverlayHost.kt
├── motion/         MotionSpecs.kt AppTransitions.kt
├── state/          WorkbenchController.kt WorkbenchPersistence.kt Loadable.kt
└── selection/      ScreenSelectionOverlay.kt

plugin-ui（声明式插件 UI SPI，3 文件）
assistant-holder（助理界面，14 文件 / 2.5k 行）
```

`app` 模块 8 个界面文件贡献 42 个 `@Composable`，其中 `SettingsScreen.kt` 一个文件 18 个。

---

## 三、导航机制

### 3.1 没有 nav_graph.xml

全仓 `find` 结果：**不存在任何 `nav_graph*.xml`**，也没有 `navigation/*.xml`。`androidx.navigation` 只被一个文件引用：

[SettingsScreen.kt](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt#L94-L96)
```kotlin
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
```

### 3.2 三层导航结构

**第一层：顶层会话壳 —— 状态驱动，不是导航库**

[MainActivity.kt:398](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L398)
```kotlin
val enterWorkbench = (phase is ConnectionPhase.Connected || phase is ConnectionPhase.OfflineMirror) && controller != null
```

外层是 `AnimatedContent(targetState = nativeUi)`（原生插件界面 ↔ 宿主界面交叉淡化），内层再套 `AnimatedContent(targetState = enterWorkbench)`，用 `AppTransitions.enter/exit` 做左右滑移 + 缩放。也就是说"登录页 → 工作台"没有回退栈，它是**同一棵组合里两个状态分支的动画切换**。

`showSettingsSheet` / `showAssistant` 是两个 `var ... by remember { mutableStateOf(false) }`，分别控制 `PlatformSettingsBottomSheet` 和 `FloatingConversationPanel`，同样不经过导航库。

**第二层：设置子页 —— Compose NavHost（唯一使用导航库的地方）**

[SettingsScreen.kt:118-128](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt#L118-L128)
```kotlin
object SettingsRoutes {
    const val OVERVIEW   = "settings/overview"
    const val APPEARANCE = "settings/appearance"
    const val GATEWAY    = "settings/gateway"
    const val PAIRING    = "settings/pairing"
    const val SECURITY   = "settings/security"
    const val AUDIT_LOG  = "settings/audit"
    const val PLUGINS    = "settings/plugins"
    const val NOTIFICATIONS = "settings/notifications"
    const val APP_UPDATES   = "settings/app-updates"
}
```

[SettingsScreen.kt:244-254](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt#L244-L254)
```kotlin
NavHost(
    navController = navController,
    startDestination = SettingsRoutes.OVERVIEW,
    modifier = Modifier.fillMaxSize(),
    enterTransition = { AppTransitions.navEnter(reduceMotion) },
    exitTransition = { AppTransitions.navExit(reduceMotion) },
    popEnterTransition = { AppTransitions.navPopEnter(reduceMotion) },
    popExitTransition = { AppTransitions.navPopExit(reduceMotion) },
) { ... 9 个 composable(route) { ... } }
```

路由推进统一用 `navController.navigate(route) { launchSingleTop = true }`，返回统一 `popBackStack()`。**没有类型安全路由、没有参数传递、没有嵌套图**，路由字符串全是扁平常量。

**第三层：容器内导航 —— M3 规范组件**

- `ThreadDrawer`：`ModalNavigationDrawer` + `ModalDrawerSheet`，`BackHandler(enabled = drawer.isOpen)` 先关抽屉
- `AttachmentLibraryScreen`：由 `WorkbenchScreen` 内 `showAttachmentLibrary` 布尔量控制（:611）
- `FloatingConversationPanel`：`AnimatedVisibility` + `AppTransitions.modalEnter/modalExit`
- `PlatformSettingsBottomSheet`：`ModalBottomSheet` + `rememberModalBottomSheetState(skipPartiallyExpanded = true)`

### 3.3 主导航边界的硬约束

[Navigation.kt](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/Navigation.kt) 定义 `CoreNavigation`（只有 gateway / conversations / attachments 三个目的地），但**生产代码从不使用它**，只有两个测试引用。它是被 [ArchitectureBoundaryTest.kt](../../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ArchitectureBoundaryTest.kt) 与 [WorkbenchNavigationTest.kt](../../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/WorkbenchNavigationTest.kt) 用来锁边界的"文档型数据类"。

对应 ADR：[docs/adr/0034-keep-main-navigation-minimal-and-plugin-management-in-settings.md](../../adr/0034-keep-main-navigation-minimal-and-plugin-management-in-settings.md)（已标记 superseded by ADR-0042）。

---

## 四、Theme 与 Material 3

### 4.1 XML 主题只做三件事

[themes.xml](../../../apps/android/app/src/main/res/values/themes.xml) 全文 35 行，只有两个 style：

```xml
<style name="Theme.OpenAndroidIntelligence" parent="android:style/Theme.Material.Light.NoActionBar">
    <item name="android:fontFamily">sans</item>
    <item name="android:windowActionBar">false</item>
    <item name="android:windowNoTitle">true</item>
    <item name="android:statusBarColor">@android:color/transparent</item>
    <item name="android:navigationBarColor">@android:color/transparent</item>
</style>
```

另一个 `Theme.OpenAndroidIntelligence.AssistantOverlay` 给 ASSIST 浮层用（`windowIsTranslucent=true` + 系统遮罩 0.45）。

注意 `parent` 是**平台** `android:style/Theme.Material.Light.NoActionBar`，**不是** `Theme.Material3.*`、也不继承 MDC。全仓无 `com.google.android.material` 依赖、无 AppCompat。

XML 里**刻意不写** `windowLightStatusBar`——深浅由运行时 `applySystemBarContrast(isDark)` 决定（见 4.3）。

另外：`res/` 下只有 `values/themes.xml`，**没有 `values-night/`、没有 colors.xml**。所有颜色都在 Kotlin 里。

### 4.2 Compose 主题桥接层

[Theme.kt](../../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/Theme.kt#L38-L73)
```kotlin
@Composable
fun OpenAndroidIntelligenceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = supportsDynamicColor(),
    palette: ThemePalette = ThemePalette.INDIGO,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dynamicAvailable = dynamicColor && supportsDynamicColor()
    val target = when {
        dynamicAvailable && darkTheme -> dynamicDarkColorScheme(context)
        dynamicAvailable -> dynamicLightColorScheme(context)
        darkTheme -> palette.darkScheme()
        else -> palette.lightScheme()
    }
    val scheme = animatedColorScheme(target, colorSpec)   // 逐角色 animateColorAsState
    val stateColors = if (darkTheme) DarkAppStateColors else LightAppStateColors
    CompositionLocalProvider(
        LocalMotionPolicy provides policy,
        LocalAppStateColors provides stateColors,
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
```

配色优先级：**系统 Monet 动态取色（默认）→ 预设色板**。
`supportsDynamicColor()` = `Build.VERSION.SDK_INT >= S`（API 31）。minSdk 31/34，所以动态取色在所有目标设备上均可用。

深浅切换不停留在"整棵子树 Crossfade"，而是对 **34 个 ColorScheme 角色逐个 `animateColorAsState`**（250ms `MotionSpecs.Standard` + `StandardEasing`；减少动态时 `snap()`）。注释里明确说明原因：Crossfade 会让两版界面同时挂载，导致重复组合、重复副作用、重复无障碍节点。

### 4.3 系统栏深浅：应用内主题优先于系统夜间模式

[SystemBars.kt](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SystemBars.kt#L22-L31)
```kotlin
internal fun ComponentActivity.applySystemBarContrast(isDark: Boolean) {
    val style = SystemBarStyle.auto(
        lightScrim = AndroidColor.TRANSPARENT,
        darkScrim = AndroidColor.TRANSPARENT,
    ) { isDark }
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}
```

调用时机有两处，缺一不可：
1. `MainActivity.onCreate` 中 `setContent` **之前**（首帧不出错）：[MainActivity.kt:118](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L118)
2. `setContent` 内部 `LaunchedEffect(isDark)`（用户改主题时实时同步）：[MainActivity.kt:128](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L128)

`BubbleConversationActivity` 用同一套（:49、:58），`appearanceIsDark()` 是两处共用的判定函数。这是为了修掉一个真实 bug：`windowLightStatusBar=true` 写死时，"应用内强制深色 + 系统浅色"会让状态栏图标压在深色画布上不可读。

### 4.4 预设色板：用 CIELAB 从种子色推导，不是手写散值

[AppPalettes.kt](../../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt#L29-L51) 定义 5 套色板，每套只声明「色相 / 色度 / 中性色度 / 第三色相」：

| 枚举 | 标签 | hue | chroma |
|---|---|---|---|
| `INDIGO` | 靛蓝 | 265 | 52 |
| `AZURE` | 青碧 | 205 | 44 |
| `VIOLET` | 紫罗兰 | 312 | 48 |
| `CORAL` | 暖橙 | 40 | 58 |
| `GRAPHITE` | 石墨 | 250 | 6 |

`tone(lightness, chroma, hue)` 做 CIELAB → sRGB，超 sRGB 色域时**逐级降色度**（二分 24 次）而非裁剪 RGB 分量，避免色相被压偏。角色映射遵循 M3 约定：primary tone 40 / primaryContainer tone 90 / onContainer tone 10；surface 从 tone 98.5 起，容器层 100/96/94/92/90 递进；outline tone 50/80。

### 4.5 补齐 M3 缺失的语义色

M3 角色表只有 `error`，没有 success / warning。[AppStateColors.kt](../../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppStateColors.kt) 通过 `LocalAppStateColors` 提供一套独立的状态色，并明确约束：

- **不跟随主题预设**（换色板不能让"成功是绿色"漂移）
- **颜色不是唯一信号**：任何用状态色的地方必须同时给文字或图标
- 只允许通过 `LocalAppStateColors.current` 读取，禁止写死

### 4.6 设计令牌体系

[DesignTokens.kt](../../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/DesignTokens.kt) 两个 object：

- `AppRadius`：4 / 8 / 12 / 16 / 20 / 24 / 28 dp（ExtraSmall…ExtraLarge + Compact/Bubble）
- `Dimensions`：8dp 主栅格（SpaceTiny 4 / Small 8 / Compact 12 / Medium 16 / Large 24 / XLarge 32），加上 `MinimumTouchTarget=48`、`FormWidth=480`、`ReadingWidth=680`、`MessageWidth=640`、`DrawerWidth=320`、断点 `MediumWindow=600` / `ExpandedWindow=840`

[AppTypography.kt](../../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppTypography.kt) 两条硬规则：**不指定 FontFamily**（让系统做中文字体回退），**所有尺寸都是 sp**。`AppTextStyles` 只补 `Mono` / `MonoSmall`（技术元数据）、`BrandWordmark`、`Thought`（13sp/18.2sp）。

### 4.7 令牌守卫测试

[MaterialTokenGuardTest.kt](../../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/MaterialTokenGuardTest.kt) 会扫 `app/src/main/kotlin` 全部 `.kt`：

```kotlin
private val HARDCODED_COLOR = Regex("""Color\(\s*0[xX][0-9A-Fa-f]{6,8}""")
private val HARDCODED_RADIUS = Regex("""RoundedCornerShape\(\s*(\d+)""")
```
- 任何硬编码 `Color(0x…)` → 失败
- `RoundedCornerShape(N)` 的 N 必须在 `{4,8,12,16,20,24,28}` 内

`conversation-ui` 里 26 处 `Color(0x…)` 全部集中在 `theme/AppPalettes.kt` 与 `theme/AppStateColors.kt` 两个主题文件内，符合"原始色值只保存在主题文件"的规格要求。

[ThemeContrastTest.kt](../../../apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/theme/ThemeContrastTest.kt) 对 **5 套色板 × 2 深浅 = 10 个 scheme**，逐个验证 onSurface/onSurfaceVariant 在 11 种 tonal surface 上的对比度，以及 9 组容器配对。

### 4.8 外观偏好的完整链路

```
SettingsScreen APPEARANCE 子页（外观与动效）
  → SettingsViewModel.setTheme / setDynamicColor / setDynamicIconColor / setPalette / setReduceMotion
  → AppearancePreferences（SharedPreferences "open_android_intelligence_appearance"，commit 同步落盘）
  → MutableStateFlow<AppearanceSettings>
  → MainActivity.collectAsState → OpenAndroidIntelligenceTheme(...)
```

[AppearancePreferences.kt:101-112](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/AppearancePreferences.kt#L101-L112) 的关键约束：**只有 `commit()` 成功后才发布新值**，因此重建的 Activity 观察到的值一定等于下一个进程读到的值。

`selectPalette()` 会**同时关掉动态取色**（:75-77），避免"选了色板却看不到效果"。

跨进程（助理跑在 `:assistant_session`）时，外观**不走 SharedPreferences**，而是由 `AssistantBridgeService.publish` 把 `{theme, dynamicColor, palette, reduceMotion}` 塞进状态 payload 下发；`readFallbackAppearance()` 只在拿不到桥接状态时兜底读一次本地值。[AssistantBridgeService.kt:100-109](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/assistant/AssistantBridgeService.kt#L100-L109) 的注释说明了原因：跨进程写 SharedPreferences 后对方进程的内存缓存不会失效。

---

## 五、登录页面

存在。[GatewayLoginScreen.kt](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt)，651 行，纯 Compose，无 View。
### 5.1 三种登录路径

| 路径 | 触发 | 调用 |
|---|---|---|
| 密码登录 | 主表单 | `onLogin(url, username, password: CharArray)` |
| 邀请短码 | 「使用邀请短码」切换 / `oai://pair` 深链 | `onInvite(url, account, code, expectedIdentityFingerprint)` |
| 已配对设备密钥 | 已保存账号且地址/用户名匹配时出现 | `onDeviceKey(url, account)` |

密码在提交瞬间即 `password.toCharArray()` 后清空字符串（:248-250），避免明文 String 长期驻留。

### 5.2 表单校验是"运行时会做的那套"

```kotlin
val endpoint = remember(normalizedUrl) { GatewayEndpoint.parse(normalizedUrl) }
val urlIsValid = endpoint != null
val plaintextAddress = endpoint?.isTls == false
```
地址栏的合法性与运行时判定共用 `GatewayEndpoint.parse`，不会出现"界面放行、运行时拒绝"。明文 `http://` 一旦成立就**持续显示** `PlaintextConnectionNotice()`（:223-225），不会因为用户继续操作而消失。

`sanitizeGatewayUrl()`（:642-650）默认补 `https://`，保留用户显式输入的 `http://`。

### 5.3 阶段状态机可视化

`ConnectionPhase` 是 sealed interface，6 个状态：

[GatewayRuntime.kt:106-136](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt#L106-L136)
```kotlin
sealed interface ConnectionPhase {
    data object Disconnected : ConnectionPhase
    data object Negotiating : ConnectionPhase
    data object Authenticating : ConnectionPhase
    data class OfflineMirror(val gatewayUrl: String, val username: String) : ConnectionPhase
    data class Connected(val gatewayUrl, username, pairingSummary, tlsSpkiSha256,
                         transportSecurity, conversationUi, requestedConversationUi,
                         deviceRequests, agentName) : ConnectionPhase
    data class Failed(val code: String) : ConnectionPhase
}
```

`PhaseBanner`（:488-557）用穷尽 `when` 覆盖全部 6 态：Disconnected 灰字、Negotiating/Authenticating 转圈、OfflineMirror 警告色、Connected 成功色 + `TransportSecurityChip`、Failed 错误容器 + 重试按钮。切换用 200ms fade，减少动态时 150ms。

`TransportSecurityChip`（:617-641）如实标注三档：`PLAINTEXT` / `TLS_PINNED` / `TLS_SYSTEM_TRUST`——注释明确"绝不明文伪装成已固定的 TLS 身份"。

### 5.4 安全区与自适应

根容器依次 `.statusBarsPadding().imePadding().navigationBarsPadding()`；内容 `.widthIn(max = Dimensions.FormWidth).fillMaxWidth()`（注释说明顺序不能反，否则限宽被通栏覆盖）；`verticalScroll` 保证键盘弹出后主按钮可达。

### 5.5 登录页的"死参数"

`isDarkTheme: Boolean = true` 与 `onToggleTheme: (() -> Unit)? = null` 两个参数**从未被读取**。KDoc 已说明是为兼容既有调用方保留，主题切换入口已收敛到「设置 → 外观与动效」。同样情况存在于 `WorkbenchScreen`（:69-70）。这是可清理项，不是缺陷。

### 5.6 登录页的其他入口

- 已保存账号列表（`ListItem` + 整行 `bouncyClickable`，TalkBack 朗读为按钮）
- 「重新确认网关身份」仅在 TLS + 已填用户名 + Failed 时出现，需 AlertDialog 二次确认
- 「设置与平台管理」页脚按钮
- Keystore 提示行："凭据只用于本次认证，刷新凭据由 Android Keystore 保护"

---

## 六、状态管理

### 6.1 全局状态：进程级 StateFlow 单例

没有使用 Hilt / Koin / Dagger。组合根是 [OpenAndroidIntelligenceApplication](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt#L31)，用 Kotlin `by lazy` 手工装配：

```kotlin
val appearancePreferences: AppearancePreferences by lazy { AppearancePreferences(this) }
private val gatewayRuntimeOwner = lazy { GatewayRuntime(...) }
val gatewayRuntime: GatewayRuntime by gatewayRuntimeOwner
lateinit var kernel: PluginKernel; private set
lateinit var pluginHost: ProductionPluginHost; private set
```

`GatewayRuntime`（1800+ 行）持有全部连接态：

| StateFlow | 类型 |
|---|---|
| `phase` | `ConnectionPhase` |
| `loginStatus` | `GatewayLoginStatus`（SIGNED_OUT / RESTORING / SIGNED_IN / REAUTHENTICATION_REQUIRED）|
| `credentialStatus` | `GatewayCredentialStatus`（UNAVAILABLE / AVAILABLE / REFRESHING / RECOVERY_PENDING）|
| `connectionStatus` | `EventStreamStatus` |
| `controller` | `WorkbenchController?` |
| `savedProfiles` | `List<AccountProfile>` |
| `operationNotice` / `isManagingProfiles` / `isRefreshingSession` | UI 瞬时提示 |

### 6.2 界面状态：三种模式

**模式 A：Activity 内直接 collect（MainActivity）**
```kotlin
val phase by runtime.phase.collectAsState()
val controller by runtime.controller.collectAsState()
```
无中间 ViewModel。登录页和工作台直接读 runtime。

**模式 B：ViewModel + 不可变 UiState（Settings / AppUpdate / BubbleWindow）**

[SettingsViewModel.kt](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsViewModel.kt#L23-L119) 定义 `SettingsUiState`，纯数据类 + 计算属性（`isGatewayConnected`、`connectedUsername`、`conversationUi` 等），然后用 3 层 `combine` 汇聚 11 个上游流：

```kotlin
private val baseSettingsFlow = combine(
    environment.appearance.settings, runtime?.phase ?: MutableStateFlow(ConnectionPhase.Disconnected),
    environment.pairingGrants.state, environment.auditSink.eventsFlow,
) { appearance, phase, grants, auditEvents -> BaseSettings(...) }
// + controlSettingsFlow（trust / emergency / notifications）
// + operationSettingsFlow（notice / refreshing）
val uiState: StateFlow<SettingsUiState> = combine(base, control, operations) { ... }
    .stateIn(scope, SharingStarted.Eagerly, initialValue = SettingsUiState(...))
```

**模式 C：Compose 内部 `remember { mutableStateOf }`（局部 UI 状态）**

`showSettingsSheet`、`showAssistant`、`drawer`、`showRenameDialog`、`commandPopupDismissed`、`followLatest` 等。全仓 22 处 `rememberSaveable`，主要分布在登录页表单字段（url / username）和滚动位置。

`SavedStateHandle` 只有两处：`BubbleConversationWindow`（气泡选中会话）与 `SharedConversationAttachments`（跨进程重建的待处理附件），都是 `by viewModels()` 创建的 ViewModel。

### 6.3 会话状态机：WorkbenchController

[WorkbenchController.kt](../../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L184)（4400+ 行）是对话状态的唯一权威。构造参数全部显式注入（repository、catalogRepository、scopeFactory、attachmentCoordinator、debouncePolicy、capability 布尔量、超时策略、流健康源……），没有任何服务定位器。

`Loadable<T>` 是最基础的状态容器（`Loading` / `Ready` / `Empty` / `Failed`），规格要求"所有读区分别渲染未请求、加载、空、失败、成功；HTTP/SSE 失败不能变成空列表"。

---

## 七、数据持久化

### 7.1 全仓无 DataStore、无 Room

`grep -ri "datastore\|room"` 在全部 `build.gradle.kts` 中**零命中**。持久化只有三条路径：SharedPreferences、SQLite（`SQLiteOpenHelper` 裸用）、Android Keystore。

### 7.2 SharedPreferences 清单（14 个命名空间）

| 文件名 | 内容 | 位置 |
|---|---|---|
| `open_android_intelligence_appearance` | 主题模式、动态取色、图标取色、色板、减少动态 | AppearancePreferences.kt:115 |
| `open_android_intelligence_runtime` | `last_gateway_url` / `last_username` / `last_profile_id` / `last_account_id` / `last_device_id` / `last_session_id` / `installation_id` | GatewayRuntime.kt:1806 |
| `open_android_intelligence_pairing_grants` | 配对授权（非机密） | PairingGrantPersistence.kt:61 |
| `open_android_intelligence_platform` | 开发者信任模式开关 | SharedPreferencesTrustModePersistence.kt:36 |
| `open_android_intelligence_app_updates` | 更新渠道 | AppUpdateViewModel.kt:21 |
| `gateway-identities` | TLS SPKI 指纹固定 | GatewayIdentityTrustStore.kt:12 |
| `gateway_refresh_operations` | 刷新凭据的写前日志（WAL） | GatewayRefreshJournal.kt:9 |
| `gateway-event-cursors` | 事件游标 | AndroidEventCursorStore.kt:9 |
| `agent-reply-alert-settings` | 回复通知开关 + `permission-requested` | ReplyNotifications.kt:36 |
| `agent-reply-completions-<hash>` | 每 profile+account 的完成通知去重 | ReplyNotifications.kt:416 |
| `agent-session-recovery` | 后台会话恢复 | AgentSessionKeepAlive.kt:79 |
| `conversation-debounce-v1` | 消息防抖（全局 + per-Gateway 覆盖） | DebouncePreferences.kt:10 |
| `verified-plugin-enablement-v1` / `native-plugin-recovery-v1` | 插件启用 / 崩溃恢复 | ProductionPluginHost.kt:47,50 |

**写模式高度一致**：几乎所有关键写入都用 `commit()` 而非 `apply()`，并且**校验返回值**：

```kotlin
check(preferences.edit()
    .putString(KEY_THEME, next.theme.name)
    ...
    .commit()) { "APPEARANCE_PREFERENCE_PERSISTENCE_FAILED" }
_settings.value = next          // 落盘成功后才发布
```

这是本项目一条贯穿始终的原则：**持久化失败必须显式抛出，不允许静默降级**。

### 7.3 SQLite：加密会话镜像

[SqliteConversationRecords.kt](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/SqliteConversationRecords.kt#L24-L42)
```kotlin
private val directory = File(context.noBackupFilesDir, "conversation-mirror/$scopeHash")
private val databaseFile = File(directory, "mirror-v1.db")
object : SQLiteOpenHelper(context, databaseFile.path, null, 1) {
    override fun onConfigure(db) {
        db.rawQuery("PRAGMA synchronous=FULL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
    }
    // CREATE TABLE records(row_id TEXT PK, resource_type, lookup_key UNIQUE,
    //   revision, sort_key, tombstone, payload BLOB)
}
```

`payload` 是 Keystore 加密后的 BLOB，明文只在有界内存缓存里。存储位置是 `noBackupFilesDir`（不参与云备份），符合 `allowBackup="false"`。

[EncryptedConversationMirror](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedConversationMirror.kt#L24-L34) 实现 `AwaitableWorkbenchPersistence`，是 `WorkbenchController` 的磁盘适配器；离线时 `OfflineMirrorRepository` 包在它外面，只提供读（写操作全部抛 `IOException("OFFLINE_MIRROR")`），这正是 `OfflineMirror` 阶段的真实语义。

### 7.4 机密数据

| 数据 | 存储 |
|---|---|
| 刷新凭据 | `AndroidKeystoreGatewayCredentialStore`（gateway-client 模块） |
| Ed25519 设备私钥 | `Ed25519DeviceKeyStore`，AES 包装密钥在 Keystore，密文落私有目录 |
| 附件暂存 / 文档 / 通知 outbox | `AesGcmEncryptedBlobStore` 系列（encrypted-store 模块） |
| 账号公开资料 | **Android `AccountManager`**（`AndroidAccountProfileStore`），密码不进 AccountManager |

---

## 八、首次启动状态检测

### 8.1 结论：不存在通用"首次启动"标记

全仓 `grep` 结果：**没有** `firstLaunch` / `firstRun` / `hasSeenOnboarding` / `onboarded` / `hasCompletedSetup` 这类键，也**没有** Welcome / Onboarding / Splash 界面。

### 8.2 首屏判定 = 会话恢复判定

启动时"看到登录页还是工作台"完全由 `ConnectionPhase` 决定：

```
ConnectionPhase.Disconnected  → GatewayLoginScreen（充值表单状态为空白）
ConnectionPhase.Negotiating   → 仍是登录页，PhaseBanner 显示"正在协商协议版本…"
ConnectionPhase.Authenticating→ 仍是登录页，显示"正在验证凭据…"
ConnectionPhase.Connected     → 工作台
ConnectionPhase.OfflineMirror → 工作台（顶部 offlineMirrorBanner + 只读）
ConnectionPhase.Failed        → 仍是登录页，错误容器 + 重试
```

`restoreSessionIfAvailable()` 就是"是否算首次启动"的**实际判定器**：

[GatewayRuntime.kt:950-965](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt#L950-L965)
```kotlin
fun restoreSessionIfAvailable() {
    if (backgroundRecoveryOnly && !appVisible && !sessionRecovery.hasRecoveryLease) return
    if (connectionJob?.isActive == true || _phase.value !is ConnectionPhase.Disconnected ||
        _loginStatus.value == GatewayLoginStatus.REAUTHENTICATION_REQUIRED) return
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val lastUrl    = prefs.getString(KEY_LAST_GATEWAY, null) ?: return   // ← 首次启动在此返回
    val lastUser   = prefs.getString(KEY_LAST_USER, null) ?: return
    val profileId  = prefs.getString(KEY_LAST_PROFILE, null) ?: return
    val accountId  = prefs.getString(KEY_LAST_ACCOUNT, null) ?: return
    val deviceId   = prefs.getString(KEY_LAST_DEVICE, null) ?: return
    ...
    val hasCredential = runCatching { credentialStore.loadRefresh(profileId)?.let { ... } }.getOrDefault(false)
    if (hasCredential != true) return                                   // ← 凭据已丢也回到登录页
    // 之后进入 Negotiating → refresh → establish
}
```

即"首次启动"= **`KEY_LAST_GATEWAY` 缺失 或 Keystore 里没有刷新凭据**。没有任何布尔标记，因此不会出现"标记为已完成但凭据失效"的状态漂移。

触发点共 6 处：`MainActivity:334`（`LaunchedEffect(Unit)`）、`BubbleConversationActivity:113`、`onNetworkAvailable`（:380）、`onBackgroundSessionRestarted`（:404）、`reconnectOfflineMirror`（:604）、`PluginJobScheduler:134`。

### 8.3 installation_id：一次生成，永不变

[OpenAndroidIntelligenceApplication.kt:121-124](../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt#L121-L124)
```kotlin
val installPreferences = getSharedPreferences("open_android_intelligence_runtime", MODE_PRIVATE)
val installId = installPreferences.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
    check(installPreferences.edit().putString("installation_id", it).commit())
}
```
`GatewayRuntime.installationId()`（:1697）读同一个 key，缺失则生成 `"install_" + newToken()`。这是最接近"首次启动标记"的东西，但它是**身份种子**（参与所有加密文档的 scope key），不是 UI 开关。

### 8.4 真正存在的"一次性"标记

| 标记 | 存储 | 用途 |
|---|---|---|
| `permission-requested` | `agent-reply-alert-settings` | 通知权限只自动请求一次（ReplyNotifications.kt:58）|
| `native-plugin-recovery-v1` | 插件崩溃恢复计数 | 首次崩溃尝试恢复，多次后停用 |
| `gateway_refresh_operations` | 写前日志 | 每条刷新操作一次，成功后清理 |

### 8.5 首次启动时的实际首屏

`AppearancePreferences` 的默认值就是"首次启动"的外观：

```kotlin
data class AppearanceSettings(
    val theme: ThemePreference = ThemePreference.SYSTEM,       // 跟随系统
    val dynamicColor: Boolean = supportsDynamicColor(),         // 默认开启 Monet
    val dynamicIconColor: Boolean = supportsDynamicColor(),     // 默认开启主题图标
    val palette: ThemePalette = ThemePalette.INDIGO,            // 动态取色关闭时回落到靛蓝
    val reduceMotion: Boolean = false,
)
```

初始化时立即 `LauncherIconManager.applyDynamicIconPreference(appContext, dynamicIconColor)`（:57-59），所以首次启动启动器图标就是 Material You 单色层版本（Android 13+ 开启主题图标时生效）。

---

## 九、发现的问题与可清理项

### 9.1 确认无缺陷但值得注意

1. **`CoreNavigation` 是死代码**（仅测试引用）。Navigation.kt 三个 data class 在生产路径从未实例化。它们作为架构边界的"可执行文档"存在，但读者容易误以为是真实导航模型。
2. **`PlatformSettingsScreen` 是纯转发包装器**（57 行，只调 `SettingsScreen`），生产代码**没有任何调用方**，只有测试用。`SettingsPresentation.SCREEN` 分支同样只在测试中触发——生产唯一入口是 `PlatformSettingsBottomSheet`。
3. **`isDarkTheme` / `onToggleTheme` 双死参数**：`GatewayLoginScreen`（:72-73）与 `WorkbenchScreen`（:69-70）各有这两个参数，均未读取。KDoc 已注明为兼容保留。
4. **`GatewayLoginScreen` 651 行单文件**，含 8 个 composable；`SettingsScreen.kt` 1473 行含 18 个。两者都远超常见单文件可读上限，但内部结构清晰（每子页一个 private composable）。
5. **`SettingsScreen` 主览直接 `getSharedPreferences` 读 DebouncePreferences**（:539），绕过 ViewModel，与"状态全走 ViewModel"的注释略有不一致。

### 9.2 已验证的强项

- **零 View 系统遗留**：无 XML 布局、无 AppCompat、无 MDC、无 Fragment。唯一 View 是 `AssistantComposeHost` 的 `FrameLayout` 容器和 Markdown 公式的 `WebView`（本地 KaTeX，仅用于排版测量）。
- **令牌守卫自动化**：硬编码色值、非栅格圆角由测试拦截；10 个 ColorScheme 组合的对比度由测试保证。
- **深浅模式只有一个真相源**：`AppearanceSettings.theme`，XML 与 `enableEdgeToEdge()` 都不参与判定。
- **持久化失败必须抛出**，不存在"写失败但界面显示成功"的路径。
- **首屏判定不依赖布尔标记**，因此不会出现状态漂移。

---

## 十、关键文件索引

| 用途 | 路径 |
|---|---|
| 主入口 / 根组合 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt` |
| 登录页 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt` |
| 设置（含 NavHost） | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt` |
| 设置弹层 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/PlatformSettingsBottomSheet.kt` |
| 导航模型（边界文档） | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/Navigation.kt` |
| 外观偏好 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/AppearancePreferences.kt` |
| 系统栏 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SystemBars.kt` |
| 会话运行时 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt` |
| 组合根 | `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt` |
| 主题桥接 | `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/Theme.kt` |
| 色板 / 状态色 | `.../conversation/theme/AppPalettes.kt`、`.../AppStateColors.kt` |
| 令牌 | `.../conversation/theme/DesignTokens.kt`、`.../AppTypography.kt` |
| 动效 | `.../conversation/motion/MotionSpecs.kt`、`.../AppTransitions.kt` |
| 工作台 | `.../conversation/workbench/WorkbenchScreen.kt` |
| 工作台状态 | `.../conversation/state/WorkbenchController.kt` |
| XML 主题 | `apps/android/app/src/main/res/values/themes.xml` |
| Manifest | `apps/android/app/src/main/AndroidManifest.xml` |
| 设计规格 | `docs/superpowers/specs/2026-09-12-app-material-ui-and-motion.md` |
| 主导航 ADR | `docs/adr/0034-keep-main-navigation-minimal-and-plugin-management-in-settings.md` |
