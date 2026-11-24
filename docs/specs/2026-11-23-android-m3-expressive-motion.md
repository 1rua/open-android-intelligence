---
status: implemented
date: 2026-11-23
title: Android Material 3 Expressive 动效架构、共享转场与交互组件规格手册
refines:
  - 2026-10-21-app-ui-motion-refactor.md
  - 2026-11-03-android-settings-sidebar-and-tablet-panel.md
---

# Android Material 3 Expressive 动效架构与使用手册

本文档记录 Open Android Intelligence（OAI）Android 客户端基于 `compose-bom:2024.12.01`（Compose `1.7.6` / Material 3 `1.3.1`）完成的 Material 3 Expressive 弹簧物理动效、共享容器转场（`SharedTransitionLayout`）、预测性返回兼容层与自适应形变组件体系改造，并作为后续开发的权威接入指南。

> **技术边界**
> - 不引入任何第三方动画库，全部能力基于原生 Compose `1.7.6` 动画、共享元素与 `androidx.activity.compose` 实现。
> - 只调整前端视觉、动效与导航组合层，不涉及任何后端、Gateway 协议或运行时状态语义变更。
> - 所有示例与路径均使用相对路径，严格遵守 `MaterialTokenGuardTest`（禁用 `Color(0x...)` 硬编码色值、禁用非 `AppRadius` 栅格圆角）与 `tools/check_hardcoded_chinese.py`（禁止新增硬编码中文文案）。

---

## 1. 改造后关键动效结构说明

### 1.1 Motion 物理弹簧体系（`MotionSpecs` + `AppTransitions`）

依据 [DESIGN.md](../../DESIGN.md) §2.1，动效系统从固定时长 `tween` 全面升级为 **物理弹簧（Spring Physics）** 主导的可中断体系，按属性性质严格划分两条正交轨道：

1. **Spatial（空间弹簧）**：驱动位移、尺寸、缩放、圆角与共享边界变换。
   - **Standard Spatial**（`dampingRatio = 0.9f`）：响应利落、几乎无回弹，适用于高频效率交互与常规页面路由；
   - **Expressive Spatial**（`dampingRatio = 0.6f / 0.8f`）：带有克制过冲，适用于共享容器变换、微交互弹跳与悬浮层释放；
   - **布局稳定性约束**：参与 `animateContentSize` 或父容器重测的尺寸展开/折叠（如 `ExpandableRegion`、`M3MorphingButton` 自适应宽度）统一使用临界阻尼 `MotionSpecs.morph`（`dampingRatio = 1.0f, stiffness = 700f`），防止列表重测时下方元素视觉抖动；圆角插值必须加 `.coerceAtLeast(0.dp)` 或 `.coerceIn(0f, 50f)` 保护，杜绝弹簧过冲产生负圆角崩溃。
2. **Effects（效果弹簧）**：驱动透明度（Alpha）与颜色角色插值（`Theme.kt` 主题换色、组件容器/内容色切换）。
   - **强制临界阻尼（`Spring.DampingRatioNoBouncy = 1.0f`）**：严禁过冲或闪烁。
3. **页面分层不透明视差转场（`AppTransitions` + `FullscreenPredictiveSlideOverlay` + `navPageTransitionDecoration`）**：
   - **同级表面切换（`enter` / `exit`）**：用于底层已有实色宿主的同级切换（例如登录页 ↔ 工作台），仍使用 `sharedAxisEnter` / `sharedAxisExit`。
   - **层级导航与全屏设置覆盖层（`navEnter` / `navExit` / `navPopEnter` / `navPopExit` / `modalEnter` / `modalExit`）**：在非减少动态下**彻底取消全页 `fadeOut(targetAlpha = 0f)` / `fadeIn(initialAlpha = 0f)` 透明淡化**，改为 **100% 不透明实色表面 + Z-Index 分层叠放 + 背景 `-28%` 视差位移 + `0.96f` 纵深缩放 + 背景 `0.16f` Scrim 压暗 + 前景 `20.dp`（最高 `0.24f`）左侧边缘渐变阴影**：
     - 进场前景（`navEnter` / `modalEnter`）：`MotionSpecs.spatialDefault(false)` 全宽全速滑入，全程 `alpha = 1f`，彻底杜绝底层聊天/登录界面瞬闪穿透；
     - 退至背景页（`navExit`）：使用更轻快的 `MotionSpecs.spatialFast(false)` 向左侧做 `28%` 视差滑移并缩放至 `0.96f`，搭配 `MaterialTheme.colorScheme.scrim`（最大透明度 `0.16f`）柔和压暗；
     - 出栈/手势预测性返回（`navPopEnter` / `navPopExit`）：上一级背景页从 `-28%` 视差位与 `0.96f` 缩放在底层立即可见并实时跟手复原，前景页全速全不透明向右滑出并在暴露给背景的侧缘绘制 `20.dp` 渐变阴影；
     - `navPageTransitionDecoration` 在 Compose 绘图阶段惰性读取页面角色（`NavPageTransitionRole.FOREGROUND` / `BACKGROUND`）与渐变因子（入场使用 `EffectsSlowSpring`、退场使用 `EffectsDefaultSpring`），且在过渡静止时（`!transition.isRunning && transition.currentState == transition.targetState`）立即早退，保持静止页面零 GPU 过绘。

核心源码位置：
- [MotionPolicy.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/MotionPolicy.kt)
- [MotionSpecs.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/MotionSpecs.kt)
- [AppTransitions.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/AppTransitions.kt)
- [PredictiveBackCompat.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/PredictiveBackCompat.kt)

### 1.2 作用域与页面层次架构（`SharedTransitionLayout` + 分层视差）

为避免嵌套 `SharedTransitionLayout` 破坏作用域匹配，同时保证跨 `840dp` 窗口断点迁移 `movableSettings` 时导航与滚动状态不销毁，采用 **共享容器与不透明页面层级解耦** 的架构：登录页表单/按钮及输入坞微交互继续保留共享元素形变，而工作台全屏设置覆盖层与设置内部 `NavHost` 各路由统一采用 100% 不透明分层视差，确保手势拖拽、横竖切换或断点重排时零透底、零裁切闪烁。

```text
OpenAndroidIntelligenceTheme
 └─ AppSharedTransitionHost（RemoteWorkbenchRoot 根宿主，提供根 LocalSharedTransitionScope）
     ├─ AnimatedContent("native-plugin-surface") -> ProvideSharedVisibilityScope
     └─ AnimatedContent("root-surface") -> ProvideSharedVisibilityScope
         ├─ RemoteLoginSettingsHost -> PlatformSettingsDrawer
         │   ├─ 宽屏 >= 840dp：ModalNavigationDrawer（320dp 侧栏抽屉）-> movableSettings(SIDEBAR)
         │   ├─ 窄屏 < 840dp：FullscreenPredictiveSlideOverlay（Z=0 实色登录背景视差 + Z=1 全屏设置覆盖层）
         │   └─ RemoteLoginScreen（根 Box 强制 surface 实色背景）
         │       └─ AnimatedContent(selfHosted) -> ProvideSharedVisibilityScope
         │           ├─ 表单容器：m3SharedBounds(SharedTransitionKeys.loginCard())
         │           └─ 主操作按钮：M3MorphingButton(sharedBoundsKey = morphingButton("remote-login-primary"))
         └─ WorkbenchSettingsHost -> WorkbenchScreen -> EnsureSharedTransitionHost（复用根宿主）
             ├─ 平板侧栏（>= 840dp）：AnimatedContent(sidebarPanel, targetContentZIndex = 1f / -1f)
             │   └─ ProvideSharedVisibilityScope -> Box(surfaceContainerLow + navPageTransitionDecoration)
             ├─ 手机全屏设置（< 840dp）：FullscreenPredictiveSlideOverlay（Z=0 工作台主界面视差 + Z=1 实色设置覆盖层）
             │   └─ LocalPredictiveOverlayBackController -> movableSettings(SCREEN)
             └─ ComposerBar AnimatedContent(recording) -> ProvideSharedVisibilityScope
                 ├─ 输入/录音容器：m3SharedBounds(SharedTransitionKeys.composerContainer())
                 └─ 发送/停止按钮：M3MorphingIconButton(sharedBoundsKey = composerAction("send-stop"))

SettingsNavHost（独立轻量容器，刻意挂在 movableSettings 内部）
 └─ Box(fillMaxSize + background(containerColor) + clipToBounds)
     └─ NavHost（navEnter / navExit / navPopEnter / navPopExit 全透明淡出移除；非快照 transitionRoles + visibleEntries 清理）
         ├─ OVERVIEW / 一级分类页 / 二级子页（统一经 settingsComposable；设置内部无跨路由共享边界需求，不再挂载冗余 AppSharedTransitionHost / LookaheadScope 以避免双倍测量）
         │   └─ Box(fillMaxSize + background(containerColor) + navPageTransitionDecoration)
         │       └─ SettingsPageScaffold(fillMaxSize + containerColor)
         └─ NOTIFICATIONS / DEVICE_PUSH 重定向占位页
             └─ Box(fillMaxSize + background(containerColor))（消除重定向期间透明瞬闪）
```

Key 命名空间统一收敛在 [SharedTransitions.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/SharedTransitions.kt) 的 `SharedTransitionKeys`（全部英文静态前缀，不受文案与语言影响）：
- `rootSurface(id = "workspace")` → `"root_surface:$id"`（预留 Key，供根层跨屏容器转场按需接入）
- `loginCard(id = "form")` → `"login_card:$id"`
- `settingsCategoryCard(route)` → `"settings_category_card:$route"`（预留分类卡片共享 Key；设置路由自身已统一升级为 100% 不透明分层视差导航）
- `composerContainer()` → `"composer_container"`
- `composerAction(id = "primary")` → `"composer_action:$id"`
- `expandableCard(id)` → `"expandable_card:$id"`
- `morphingButton(id)` → `"morphing_button:$id"`

### 1.3 Predictive Back 跨版本兼容与协调结构（`SettingsNavHost` + `FullscreenPredictiveSlideOverlay`）

[PredictiveBackCompat.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/PredictiveBackCompat.kt) 屏蔽 Android 12–13（API 31–33）与 Android 14+（API 34+）的返回手势差异，并通过 `PredictiveOverlayBackController` 打通「外层全屏设置覆层」与「内层设置根路由」的单一真实进度：

1. **平台分流**：`supportsNativePredictiveBack(sdkInt)` 判断 `sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE`（API 34）。
   - **API 34+**：使用 `PredictiveBackHandler` 实时收集 `BackEventCompat.progress` 写入进度 `Animatable<Float>`（限定 `[0f, 1f]`）。
   - **API 31–33**：自动回退为标准 `BackHandler`，不产生半拖出进度直接执行关闭。
2. **单一进度源与「松手无弹回」提交**：
   - `PredictiveOverlayBackController` 维护覆盖层开合进度 `openProgress`（`0f` 关闭、`1f` 全开）：拖拽时 `snapTo(1f - progress)` 直接实时同步背景视差与前景偏移；
   - 手势取消（`CancellationException`）时由绑定组合生命周期与 `gestureToken` 的协程以临界阻尼 `MotionSpecs.morph(false)` 回弹至 `1f`；
   - **手势提交时绝不把进度重置为 `0f` 或 `1f`**，而是直接调用 `onDismiss()` 使 `syncOpenState(open = false)` 从当前已拖出的进度位接管并按 `spatialFast` 平滑滑出至 `0f`，彻底消除旧版嵌套滑移导致的「松手回弹后再关闭」视觉断层。
3. **协调结构图（`OnBackPressedDispatcher` LIFO 栈与控制权交接）**：

```text
PlatformSettingsDrawer / WorkbenchScreen（窄屏 < 840dp）
 ├─ 在 `if (expanded)` 之前创建 `phoneOverlayController = rememberPredictiveOverlayBackController(...)`
 │   └─ 注册最底层兜底 `PredictiveBackOverlayBridge(enabled = !expanded && open)`（始终处于 LIFO 栈底）
 └─ FullscreenPredictiveSlideOverlay(controller = phoneOverlayController)
     ├─ Z = 0：backgroundContent（0.28f 视差 + 0.96f 缩放 + 0.16f Scrim）
     └─ Z = 1：overlayContent（surface 实底 + 20.dp 边缘阴影），注入 LocalPredictiveOverlayBackController
         └─ SettingsNavHost
             ├─ 1) 当 overlayBackController != null（嵌入全屏覆盖层）：
             │   └─ 注册中层 `PredictiveBackOverlayBridge(enabled = hostOpen && atOverview && SCREEN)`
             │       ├─ atOverview == true  -> 直接驱动 phoneOverlayController 联动背景视差并滑出关闭
             │       └─ 禁止再对设置根容器叠加 .predictiveBackSlideTransform
             ├─ 2) 当 overlayBackController == null（独立渲染如单元测试）：
             │   └─ 退化挂载 `rememberPredictiveBackState` + `.predictiveBackSlideTransform(...)`
             ├─ 3) NavHost（处于 LIFO 更上层）：
             │   └─ 处于任意子页（atOverview == false）时优先消费返回事件，驱动 NavPopEnter/PopExit 视差预测返回
             └─ 4) BackHandler(enabled = hostOpen && shortcutSources.isNotEmpty())（LIFO 最顶层）：
                 └─ 同深度跨类目快捷跳转场景恢复源路由历史
```

4. **横跨 `840dp` 断点的不抢占保障**：由于 `phoneOverlayController` 位于 `if (expanded)` 之前，当设备从 `>= 840dp` 平板侧栏折叠至 `< 840dp` 手机全屏覆盖层时，外层控制器仍保持在 LIFO 栈底，`NavHost` 子页返回手势始终优先于外层覆盖层关闭手势。

### 1.4 Reduce Motion 降级决策树

全局通过 `LocalMotionPolicy.current.isReduced`（定义为 `reduceMotion || durationScale <= 0f`，同时响应应用内「减少动画」开关与系统 `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`）统一控制退化行为：

```text
LocalMotionPolicy.current.isReduced == true ?
 ├─ 空间/尺寸/缩放/圆角（spatial* / morph / press / boundsTransform）
 │   └─ 降级为 snap() 直接落位；按压缩放锁定 1.0f（保留 M3 Ripple 水波纹）
 ├─ 分层视差与边缘阴影（navPageTransitionDecoration / FullscreenPredictiveSlideOverlay 背景层）
 │   └─ 零横向视差位移、背景缩放锁定 1.0f、不绘制 Scrim 遮罩与 20.dp 边缘渐变阴影
 ├─ 共享元素（m3SharedBounds / m3SharedElement）
 │   └─ 直接返回原 Modifier，不进入共享叠加层，不执行边界动画
 ├─ 颜色与透明度效果（effects* / fade / Theme 换色）
 │   ├─ 页面与覆盖层切换（AppTransitions / FullscreenPredictiveSlideOverlay / ExpandableRegion / M3MorphingIconButton）
 │   │   └─ 降级为 reducedFade()：tween(150ms, easing = LinearEasing)，零位移、零缩放
 │   └─ 主题配色角色切换（OpenAndroidIntelligenceTheme）
 │       └─ 降级为 snap() 瞬时切换
 └─ 预测性返回（rememberPredictiveBackState / PredictiveOverlayBackController / predictiveBackSlideTransform）
     └─ 手势拖拽期间恒定保持原位与 0f 预览偏移，完成提交后仅执行 reducedFade 或直接触发 onBack
```

---

## 2. `MotionSpecs` 与分层视差完整物理参数配置表

### 2.1 `MotionSpecs` 物理弹簧配置表

下表列出 [MotionSpecs.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/MotionSpecs.kt) 中所有物理弹簧与节奏令牌的精确参数：

| 常量 / 工厂方法 | 类别 | `dampingRatio` | `stiffness` | 适用属性与典型场景 | Reduce Motion 降级方案 |
| --- | --- | :---: | :---: | --- | --- |
| `SpatialFastSpring` (`SnappySpring`) / `spatialFast(expressive = false)` / `snappy` | Standard Spatial | `0.9f` | `1400f` | 卡片/按钮圆角形变、固定按钮宽度切换、`navExit`/`navPopExit`/`modalExit` 退场位移与视差缩放 | `snap()` 直接落位 |
| `SpatialDefaultSpring` (`SpatialSpring`) / `spatialDefault(expressive = false)` / `spatial` | Standard Spatial | `0.9f` | `700f` | `navEnter`/`navPopEnter`/`modalEnter` 进场位移与视差复原、时间线消息上浮、`PredictiveBack` 取消回弹 | `snap()` 直接落位 |
| `SpatialSlowSpring` / `spatialSlow(expressive = false)` | Standard Spatial | `0.9f` | `300f` | 全屏级空间过渡、大容器展开 | `snap()` 直接落位 |
| `ExpressiveSpatialFastSpring` / `spatialFast(expressive = true)` | Expressive Spatial | `0.6f` | `800f` | 微交互弹跳指示、「回到最新」按钮出现缩放 | `snap()` 直接落位 |
| `ExpressiveSpatialDefaultSpring` / `spatialDefault(expressive = true)` / `boundsTransform` | Expressive Spatial | `0.8f` | `380f` | `SharedTransitionLayout` 共享边界与共享元素变换、悬浮吸附 | `snap()` 直接落位 |
| `ExpressiveSpatialSlowSpring` / `spatialSlow(expressive = true)` | Expressive Spatial | `0.8f` | `200f` | 整屏沉浸式容器变换 | `snap()` 直接落位 |
| `EffectsFastSpring` / `effectsFast` | Effects（临界阻尼） | `1.0f` | `3800f` | 组件容器/内容/边框色过渡、图标切换淡入淡出、同级表面退场淡出、顶栏分隔线 | `reducedFade()`（`150ms LinearEasing`） |
| `EffectsDefaultSpring` / `effectsDefault` / `fade` | Effects（临界阻尼） | `1.0f` | `1600f` | 同级表面进场淡入、`navPageTransitionDecoration` 退场遮罩梯度、`ExpandableRegion` 内容淡化、主题角色换色 | `reducedFade()`（Theme 为 `snap()`） |
| `EffectsSlowSpring` / `effectsSlow` | Effects（临界阻尼） | `1.0f` | `800f` | `navPageTransitionDecoration` 入场背景 Scrim 恢复与前景边缘阴影显现、大面积内容显现 | `reducedFade()`（装饰层跳过绘制） |
| `PressSpring` / `press` | Press 反馈 | `0.75f` | `1200f` | `M3ExpressiveCard` / `M3MorphingButton` / `bouncyClickable` 按压缩放（`PressScaleDefault = 0.97f`、`PressScaleCompact = 0.95f`） | `snap()` 且缩放固定 `1.0f`（保留 Ripple） |
| `MorphSpring` / `morph` / `emphasized` | 布局形变（临界阻尼） | `1.0f` | `700f` | `ExpandableRegion` 垂直展开/折叠、`M3MorphingButton` 自适应宽度、设置项胶囊形变、`PredictiveOverlayBackController` 取消回弹 | `snap()` 直接落位 |
| `EntranceSpring` / `entrance` | 悬浮进场 | `0.72f` | `380f` | 助理悬浮栏 `graphicsLayer` 进场位移与缩放 | `snap()` 直接落位 |
| `ExpandSpring` / `expand` | 柔和展开（临界阻尼） | `1.0f` | `360f` | 助理输入栏展开成输入态高度变化 | `snap()` 直接落位 |
| `CollapseSpring` / `collapse` | 快速收起（临界阻尼） | `1.0f` | `800f` | 助理输入栏收起恢复 | `snap()` 直接落位 |
| `MomentumSpring` | 手势惯性 | `0.75f` | `400f` | 停靠球拖拽释放动量吸附 | 直接吸附落位 |
| `SheetSpring` | 抽屉/弹层（临界阻尼） | `1.0f` | `400f` | 底部弹层、侧边抽屉 | `snap()` 直接落位 |

### 2.2 分层视差、纵深缩放、Scrim 与边缘阴影配置表（`AppTransitions`）

下表列出 [AppTransitions.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/AppTransitions.kt) 与 [PredictiveBackCompat.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/PredictiveBackCompat.kt) 的分层视觉深度与视差参数：

| 常量 / 层级角色 | 数值 / 取值 | 作用对象与数学曲线 | Reduce Motion / 静止行为 |
| --- | :---: | --- | --- |
| 前景图层 Z-Index（`NavHost` / 覆盖层 / 平板侧栏） | `1f`（高于背景层） | 当前滑入/滑出页（`FOREGROUND`）：`FullscreenPredictiveSlideOverlay` 使用 `zIndex(1f)`，平板侧栏使用 `targetContentZIndex = 1f`（返回切回会话时 `-1f`），`NavHost` 由内部 `zIndices` 栈自动保证出栈页位于上层 | 同层级结构，零透明穿透 |
| 背景图层 Z-Index（底层页面） | `0f` | 被覆盖/返回揭示页（`BACKGROUND`）：全程挂载实色 `surface` / `surfaceContainerLow`，彻底取消 `fadeOut(0f)` | 同层级结构 |
| `AppTransitions.ParallaxOffsetFraction` | `0.28f` | 背景页 `-28%` 横向视差位移（位于 MD3 `25%–30%` 区间）：`navExit` 向 `-0.28 * w` 滑出、`navPopEnter` 从 `-0.28 * w` 复原至 `0`；`FullscreenPredictiveSlideOverlay` 按 `-w * 0.28f * p * exitDirection` 联动 | 锁定 `0f` 无视差位移 |
| `AppTransitions.BackgroundDepthScale` | `0.96f` | 背景页退至下层时的纵深缩放（`1.0f -> 0.96f`），搭配 `ScaleVisibilityThreshold = 0.0005f` 防止尾段提前截断跳变 | 锁定 `1.0f` 无缩放 |
| `AppTransitions.BackgroundScrimMaxAlpha` | `0.16f` | 背景页叠加 `MaterialTheme.colorScheme.scrim.copy(alpha = 0.16f * (1f - factor))`（覆盖层为 `0.16f * p`）；页面内容本身维持 `alpha = 1f` 不透明 | 不绘制 Scrim 遮罩 |
| `AppTransitions.EdgeShadowMaxAlpha` | `0.24f` | 前景页（`FOREGROUND`）暴露侧边缘渐变阴影峰值透明度，采用二次缓冲曲线 `0.24f * (1f - (1f - factor)^2)` | 不绘制阴影 |
| `AppTransitions.EdgeShadowWidth` | `20.dp` | 前景页左侧（或覆盖层随 `exitDirection` 暴露侧）绘制 `20.dp` 水平渐变 `Brush.horizontalGradient(Transparent -> scrim.copy(alpha))` | 静止态（`!isTransitionActive`）与 Reduce Motion 下提前返回零绘制 |

> **保留合法 `tween` 例外与范围边界说明**：除上述物理弹簧外，`:conversation-ui` 与主工作台/登录/设置导航改造范围内仅保留三类特定时序的 `tween` 例外（另附未纳入本轮全弹簧重写范围的既有引导页与 `:assistant-holder` 边界说明）：
> 1. `MotionSpecs.reducedFade()`（`tween(150ms, LinearEasing)`）及兼容保留常量 `MotionSpecs.ReducedMotionSpec`：Reduce Motion / `durationScale <= 0f` 无障碍降级专用；
> 2. `MotionSpecs.staggerEnter()`（含 `delayMillis` 错峰）与 [AssistantView.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt) 详情延迟淡入（`ContentStaggerDelayMillis = 60`，减少动态时零延迟 `150ms`）；
> 3. [MessageTimeline.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt) 的 `StreamingStatusDot` 无限呼吸动画（`infiniteRepeatable(tween(1200))`，减少动态时静止）；
> 4. 非本轮核心重写范围边界：引导流程（`OnboardingFlow.kt`、`RemoteGuideScreen.kt`、`OnboardingStepHeader.kt`）与悬浮助理模块（`:assistant-holder` 下 `AssistantCompactBar.kt`、`AssistantExpandedPanel.kt`）仍保留既有固定时长 `tween` 过渡。

> **缓动与刻度补充**：`MotionSpecs.EmphasizedAccelerateEasing` 已对齐 MD3 官方 `CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)`；`ReducedMotionCrossfadeDuration = 150`（`Fast`）；列表错峰延迟 `staggerDelayMillis(index, reduced)` 每项步进 `40ms`、上限 `240ms`（减少动态时为 `0ms`）。

---

## 3. 核心组件与动效 API 调用示例

### 3.1 `M3ExpressiveCard` 与 `M3ExpandableExpressiveCard`

封装自 [M3ExpressiveCard.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/M3ExpressiveCard.kt)，内置按压缩放、圆角弹簧形变（已做 `coerceAtLeast(0.dp)` 下界保护）、M3 State Layer Ripple（`CompositionLocalProvider(LocalContentColor)` 置于点击 `Box` 外层确保水波纹继承组件自身前景色）、MD3 禁用态配色（`disabledContainerColor` 12% `onSurface`、`disabledContentColor` 38% `onSurface`）、最小触摸热区与可选共享边界。`M3ExpandableExpressiveCard` 围绕顶部中心缩放、展开态头部仅裁切顶部两角、禁用态仅置灰可点击头部而不影响已展开正文，且 `onExpandedChange` 为可空回调 `((Boolean) -> Unit)?`：传入 `null` 时按只读卡片渲染（不挂载 `.clickable`、`Role.Button` 与 `stateDescription` 语义，且不应用禁用态置灰）。

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.conversation.components.M3ExpandableExpressiveCard
import com.openandroidintelligence.conversation.components.M3ExpressiveCard
import com.openandroidintelligence.conversation.motion.SharedTransitionKeys
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.resources.R as UiR

@Composable
fun ExpressiveCardExamples(onOpenDetail: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceMedium)) {
        // 1. 可点击的共享边界动态卡片
        M3ExpressiveCard(
            onClick = onOpenDetail,
            restingCornerRadius = AppRadius.Medium,
            pressedCornerRadius = AppRadius.Large,
            sharedBoundsKey = SharedTransitionKeys.expandableCard("summary-card"),
            onClickLabel = stringResource(UiR.string.common_action_continue),
        ) {
            Text(
                text = stringResource(UiR.string.common_app_name),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(UiR.string.common_state_idle_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 2. 折叠卡：仅头部响应点击与按压形变，展开区使用 ExpandableRegion
        var expanded by remember { mutableStateOf(false) }
        M3ExpandableExpressiveCard(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            collapsedCornerRadius = AppRadius.Compact,
            expandedCornerRadius = AppRadius.Medium,
            toggleStateDescription = stringResource(
                if (expanded) UiR.string.common_accessibility_expanded
                else UiR.string.common_accessibility_collapsed,
            ),
            header = {
                Text(
                    text = stringResource(UiR.string.settings_build_details),
                    style = MaterialTheme.typography.titleSmall,
                )
            },
            expandedContent = {
                Text(
                    text = stringResource(UiR.string.settings_about_app_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
        )
    }
}
```

### 3.2 `M3MorphingButton` 与 `M3MorphingIconButton`

封装自 [M3MorphingButton.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/M3MorphingButton.kt)，支持胶囊圆角到方圆角的百分比插值（`.coerceIn(0f, 50f)` 保护）、双侧或单侧固定宽与 `animateContentSize(MotionSpecs.morph(reduced))` 自适应宽、颜色临界阻尼切换、共享边界与层级控制（`sharedBoundsZIndex`）以及正确继承按钮自身 `contentColor` 的 M3 Ripple（`ContentPadding` 默认含垂直 `SpaceSmall` 边距）。`M3MorphingIconButton` 将无障碍描述单一挂在外层语义节点并支持独立 `onClickLabel`，在 `icon == morphedIcon` 时跳过图标交叉闪烁动画。

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.M3MorphingButton
import com.openandroidintelligence.conversation.components.M3MorphingButtonDefaults
import com.openandroidintelligence.conversation.components.M3MorphingIconButton
import com.openandroidintelligence.conversation.motion.SharedTransitionKeys
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.resources.R as UiR

@Composable
fun MorphingButtonExamples(
    busy: Boolean,
    generating: Boolean,
    onSubmit: () -> Unit,
    onSendOrStop: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
        // 1. 文本形变主按钮（如登录提交按钮：静息胶囊形 -> 忙碌圆角方形）
        M3MorphingButton(
            onClick = onSubmit,
            morphed = busy,
            enabled = !busy,
            colors = M3MorphingButtonDefaults.primaryColors(),
            sharedBoundsKey = SharedTransitionKeys.morphingButton("submit"),
        ) { isMorphed ->
            if (isMorphed) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimensions.SmallIcon),
                    strokeWidth = Dimensions.StrokeStitch,
                    color = LocalContentColor.current,
                )
                Spacer(Modifier.width(Dimensions.SpaceSmall))
            }
            Text(
                stringResource(
                    if (isMorphed) UiR.string.conn_status_connecting
                    else UiR.string.common_action_confirm,
                ),
            )
        }

        // 2. 图标形变按钮（如输入坞发送/停止：48dp 圆形 <-> 56dp 圆角方形）
        M3MorphingIconButton(
            onClick = onSendOrStop,
            icon = Icons.AutoMirrored.Filled.Send,
            contentDescription = stringResource(UiR.string.common_action_continue),
            morphed = generating,
            morphedIcon = Icons.Default.Stop,
            morphedContentDescription = stringResource(UiR.string.common_action_close),
            restingWidth = 48.dp,
            morphedWidth = 56.dp,
            colors = M3MorphingButtonDefaults.sendStopColors(),
            sharedBoundsKey = SharedTransitionKeys.composerAction("send-stop"),
        )
    }
}
```

### 3.3 `Modifier.m3SharedBounds`（`AppSharedTransitionHost` + `ProvideSharedVisibilityScope`）

封装自 [SharedTransitions.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/SharedTransitions.kt)。在需要跨页面/状态过渡的容器树顶部放置 `AppSharedTransitionHost`（或嵌套复用场景下使用 `EnsureSharedTransitionHost`），在 `AnimatedContent` / `AnimatedVisibility` 块内注入 `ProvideSharedVisibilityScope`，叶子节点挂接 `Modifier.m3SharedBounds`（例如登录页官方/自建表单容器、输入坞发送/停止键等同源状态形变；`SettingsNavHost` 层级设置路由则统一使用 §3.5 100% 不透明分层视差导航）。

```kotlin
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.conversation.components.M3ExpressiveCard
import com.openandroidintelligence.conversation.motion.AppSharedTransitionHost
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.motion.ProvideSharedVisibilityScope
import com.openandroidintelligence.conversation.motion.SharedTransitionKeys
import com.openandroidintelligence.conversation.motion.m3SharedBounds
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import com.openandroidintelligence.ui.resources.R as UiR

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedBoundsNavigationExample(showDetail: Boolean) {
    val reduced = LocalMotionPolicy.current.isReduced
    AppSharedTransitionHost(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = showDetail,
            transitionSpec = {
                AppTransitions.enter(reduced) togetherWith AppTransitions.exit(reduced)
            },
            label = "shared-bounds-demo",
        ) { detail ->
            ProvideSharedVisibilityScope {
                if (!detail) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .m3SharedBounds(
                                key = SharedTransitionKeys.loginCard("demo-form"),
                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                clipShape = RoundedCornerShape(AppRadius.Large),
                            ),
                    ) {
                        M3ExpressiveCard {
                            Text(stringResource(UiR.string.common_app_name))
                        }
                    }
                } else {
                    Surface(
                        Modifier
                            .fillMaxSize()
                            .m3SharedBounds(
                                key = SharedTransitionKeys.loginCard("demo-form"),
                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                clipShape = RoundedCornerShape(AppRadius.Large),
                            ),
                    ) {
                        Text(stringResource(UiR.string.common_state_idle_hint))
                    }
                }
            }
        }
    }
}
```

### 3.4 `rememberPredictiveBackState` 与 `predictiveBackSlideTransform`（独立表面兜底）

封装自 [PredictiveBackCompat.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/PredictiveBackCompat.kt)。用于未接入 `FullscreenPredictiveSlideOverlay` 宿主（`LocalPredictiveOverlayBackController.current == null`）的独立全屏容器预览（支持按 `swipeEdge` 与 RTL 自动跟随方向，绘制阶段惰性读取零重组）。

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.motion.predictiveBackSlideTransform
import com.openandroidintelligence.conversation.motion.rememberPredictiveBackState
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced

@Composable
fun PredictiveBackPanelExample(
    visibleAtRoot: Boolean,
    onClosePanel: () -> Unit,
    content: @Composable () -> Unit,
) {
    val reduced = LocalMotionPolicy.current.isReduced
    val backState = rememberPredictiveBackState(
        enabled = visibleAtRoot,
        onBack = onClosePanel,
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .predictiveBackSlideTransform(
                stateProvider = { backState.value },
                reduced = reduced,
                maxScaleReduction = 0.04f,
            ),
    ) {
        content()
    }
}
```

### 3.5 `FullscreenPredictiveSlideOverlay` 与 `navPageTransitionDecoration`（双层视差宿主 + `NavHost`）

封装自 [PredictiveBackCompat.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/PredictiveBackCompat.kt) 与 [AppTransitions.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/AppTransitions.kt)。外层在宽/窄屏条件分支之前创建 `rememberPredictiveOverlayBackController`，并通过 `FullscreenPredictiveSlideOverlay` 暴露 `LocalPredictiveOverlayBackController`；内层 `SettingsNavHost` 在根路由通过 `PredictiveBackOverlayBridge` 直接驱动覆盖层进退，并在每个子路由上挂载实色背景与 `navPageTransitionDecoration`。

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.motion.FullscreenPredictiveSlideOverlay
import com.openandroidintelligence.conversation.motion.LocalPredictiveOverlayBackController
import com.openandroidintelligence.conversation.motion.NavPageTransitionRole
import com.openandroidintelligence.conversation.motion.PredictiveBackOverlayBridge
import com.openandroidintelligence.conversation.motion.ProvideSharedVisibilityScope
import com.openandroidintelligence.conversation.motion.navPageTransitionDecoration
import com.openandroidintelligence.conversation.motion.rememberPredictiveOverlayBackController
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced

@Composable
fun LayeredParallaxSettingsHostExample(
    expanded: Boolean,
    open: Boolean,
    atOverview: Boolean,
    onDismiss: () -> Unit,
    backgroundContent: @Composable () -> Unit,
) {
    val reduced = LocalMotionPolicy.current.isReduced
    // 在 if (expanded) 之前创建控制器，严守 OnBackPressedDispatcher LIFO 栈底
    val overlayController = rememberPredictiveOverlayBackController(
        overlayOpen = open,
        enabled = !expanded && open,
        reduced = reduced,
        onDismiss = onDismiss,
    )
    if (!expanded) {
        FullscreenPredictiveSlideOverlay(
            controller = overlayController,
            reduced = reduced,
            modifier = Modifier.fillMaxSize(),
            backgroundContent = backgroundContent,
        ) {
            val controllerFromHost = LocalPredictiveOverlayBackController.current
            if (controllerFromHost != null) {
                PredictiveBackOverlayBridge(
                    controller = controllerFromHost,
                    enabled = open && atOverview,
                    onDismiss = onDismiss,
                )
            }
            val nav = rememberNavController()
            val roles = remember { mutableMapOf<String, NavPageTransitionRole>() }
            val containerColor = MaterialTheme.colorScheme.surface
            Box(Modifier.fillMaxSize().background(containerColor).clipToBounds()) {
                NavHost(
                    navController = nav,
                    startDestination = "overview",
                    enterTransition = {
                        roles[targetState.id] = NavPageTransitionRole.FOREGROUND
                        AppTransitions.navEnter(reduced)
                    },
                    exitTransition = {
                        roles[initialState.id] = NavPageTransitionRole.BACKGROUND
                        AppTransitions.navExit(reduced)
                    },
                    popEnterTransition = {
                        roles[targetState.id] = NavPageTransitionRole.BACKGROUND
                        AppTransitions.navPopEnter(reduced)
                    },
                    popExitTransition = {
                        roles[initialState.id] = NavPageTransitionRole.FOREGROUND
                        AppTransitions.navPopExit(reduced)
                    },
                ) {
                    composable("overview") { entry ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(containerColor)
                                .navPageTransitionDecoration({ roles[entry.id] }, reduced),
                        ) {
                            // 实色设置总览页内容
                        }
                    }
                }
            }
        }
    }
}
```

---

## 4. 关键集成节点与回归门禁索引

> 下表索引核心动效架构与组件集成节点；本分支另包含全仓 `LocalMotionPolicy.current.isReduced` 统一迁移（`AttachmentLightbox.kt`、`ConversationBrowser.kt`、`MessageTimeline.kt`、`StreamingPresentationHost.kt`、`BubbleConversationActivity.kt`、`RemoteConnectionFeedback.kt`、`RemoteWorkbenchNotices.kt`、`OnboardingFlow.kt`、`ButtonRevealTransition.kt`、`RemoteConnectionChoiceScreen.kt`、`RemoteGuideScreen.kt`、`WelcomeScreen.kt`）以及 `apps/android/ci/app-regression.txt` 的 `[core]` 门禁登记。

| 模块 | 改造文件 | 核心接入内容 | 对应回归测试 |
| --- | --- | --- | --- |
| `:conversation-ui` | `MotionPolicy.kt`、`MotionSpecs.kt`、[AppTransitions.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/AppTransitions.kt) | 新增 `isReduced`、M3 Standard/Expressive/Effects/Press 弹簧、`navEnter`/`navExit`/`navPopEnter`/`navPopExit`/`modal*` 移除 `fadeOut(0f)` 并引入 `NavPageTransitionRole` + `navPageTransitionDecoration` | `MotionPhysicsTest`、`AppTransitionsTest` |
| `:conversation-ui` | `SharedTransitions.kt`、[PredictiveBackCompat.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/PredictiveBackCompat.kt) | 统一共享宿主/Key/安全修饰符、`PredictiveOverlayBackController`、`PredictiveBackOverlayBridge` 与 `FullscreenPredictiveSlideOverlay` 双层视差预测性返回兼容 | `SharedTransitionsAndPredictiveBackTest` |
| `:conversation-ui` | `M3ExpressiveCard.kt`、`M3MorphingButton.kt`、`Theme.kt`、`ExpandableRegion.kt`、`Interaction.kt` | 动态圆角卡片、形变按钮与主题/展开/按压弹簧升级 | `M3ExpressiveComponentsTest` |
| `:conversation-ui` | [WorkbenchScreen.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt)、`ComposerBar.kt`、`VoiceRecordingRow.kt`、`TimelineScaffold.kt`、`ApprovalCard.kt`、`CommandMenu.kt`、`ComposerAttachmentSheet.kt`、`MarkdownThoughtView.kt`、`MarkdownToolCallView.kt`、`ScreenSelectionOverlay.kt`、`SettingsItemMorph.kt`、`RecordingWaveform.kt`、`AssistantView.kt` | 手机设置改用 `FullscreenPredictiveSlideOverlay` 视差联动与松手不弹回提交、平板侧栏 `AnimatedContent` 接入 `navPageTransitionDecoration` 与 Z-Index 顺序、发送/停止与录音发送键共享边界形变、折叠思维链/工具卡升级 | `M3ExpressiveComponentsTest`、`ThoughtCardTest`、`VoiceRecordingRowTest`、`ScreenSelectionMinimumSizeTest`（包含在 `:conversation-ui:test`） |
| `:app` | [RemoteWorkbenchRoot.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteWorkbenchRoot.kt)、[RemoteLoginScreen.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteLoginScreen.kt)、[SettingsScreen.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt)、[NotificationAppPicker.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/notifications/NotificationAppPicker.kt) | 主题内层 `AppSharedTransitionHost`、登录/推送兜底/应用选择器实色 `surface` 背景补齐、`m3SharedBounds` + `M3MorphingButton` | `ExpressiveMotionNavigationTest`、`RemoteRootInteractionTest`、`RemoteLoginPresentationTest` |
| `:app` | [PlatformSettingsBottomSheet.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/PlatformSettingsBottomSheet.kt)、[SettingsNavHost.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt)、[SettingsScaffold.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsScaffold.kt) | 在 `if (expanded)` 前注册 `rememberPredictiveOverlayBackController`、手机端 `FullscreenPredictiveSlideOverlay`、移除 `SettingsNavHost` 冗余内嵌 `AppSharedTransitionHost`、`containerColor` + `clipToBounds` + `transitionRoles` + `navPageTransitionDecoration`、根路由 `PredictiveBackOverlayBridge` 与 `SettingsPageScaffold` 复用 `containerColor` 实色铺满 | `ExpressiveMotionNavigationTest`、`SettingsNavigationTest`、`SettingsHonestyCopyTest`、`SettingsResponsiveHostRegressionTest`、`MaterialTokenGuardTest` |
