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
3. **页面转场对称性与不对称节奏（`AppTransitions`）**：
   - 进场（`enter`、`navEnter`、`navPopEnter`、`modalEnter`）：位移与缩放使用 `MotionSpecs.spatialDefault(false)`，淡入使用 `MotionSpecs.effectsDefault(false)`；
   - 退场（`exit`、`navExit`、`navPopExit`、`modalExit`）：位移与缩放使用更轻快的 `MotionSpecs.spatialFast(false)`，淡出使用 `MotionSpecs.effectsFast(false)`，保证「离开快于进入」且全程可由手势任意打断。

核心源码位置：
- [MotionPolicy.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/MotionPolicy.kt)
- [MotionSpecs.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/MotionSpecs.kt)
- [AppTransitions.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/AppTransitions.kt)

### 1.2 `SharedTransitionLayout` 作用域架构

为避免嵌套 `SharedTransitionLayout` 破坏作用域匹配，同时保证跨 `840dp` 窗口断点迁移 `movableSettings` 时导航与滚动状态不销毁，采用 **分层作用域 + 按需复用 + Key 命名空间隔离** 的三层结构：

```text
OpenAndroidIntelligenceTheme
 └─ AppSharedTransitionHost（RemoteWorkbenchRoot 根宿主，提供根 LocalSharedTransitionScope）
     ├─ AnimatedContent("native-plugin-surface") -> ProvideSharedVisibilityScope
     └─ AnimatedContent("root-surface") -> ProvideSharedVisibilityScope
         ├─ RemoteLoginSettingsHost
         │   └─ RemoteLoginScreen（复用根宿主 LocalSharedTransitionScope；无宿主时由 m3SharedBounds 安全退化以兼容软件画布渲染）
         │       └─ AnimatedContent(selfHosted) -> ProvideSharedVisibilityScope
         │           ├─ 表单容器：m3SharedBounds(SharedTransitionKeys.loginCard())
         │           └─ 主操作按钮：M3MorphingButton(sharedBoundsKey = morphingButton("remote-login-primary"))
         └─ WorkbenchSettingsHost -> WorkbenchScreen -> EnsureSharedTransitionHost（复用根宿主）
             ├─ 平板侧栏 AnimatedContent(sidebarPanel) -> ProvideSharedVisibilityScope
             ├─ 手机全屏设置 AnimatedVisibility(settingsOpen) -> ProvideSharedVisibilityScope
             └─ ComposerBar AnimatedContent(recording) -> ProvideSharedVisibilityScope
                 ├─ 输入/录音容器：m3SharedBounds(SharedTransitionKeys.composerContainer())
                 └─ 发送/停止按钮：M3MorphingIconButton(sharedBoundsKey = composerAction("send-stop"))

SettingsNavHost（独立隔离作用域，刻意挂在 movableSettings 内部）
 └─ AppSharedTransitionHost（随 movableSettings 在 839dp <-> 840dp 间整体迁移；不调用 EnsureSharedTransitionHost 复用外层工作台宿主，以防跨越 840dp 响应式断点重挂宿主时丢失设置子路由与共享边界状态）
     └─ NavHost
         ├─ OVERVIEW：ProvideSharedVisibilityScope + 每张分类卡外层 m3SharedBounds(settingsCategoryCard(category.route))
         ├─ 一级分类页（APPEARANCE / GATEWAY / REMINDERS / PAIRING / SECURITY / ADVANCED）
         │   └─ ProvideSharedVisibilityScope + LocalSettingsSharedCategoryKey
         │       └─ SettingsPageScaffold(sharedCategoryKey = ...) -> m3SharedBounds(..., RemeasureToBounds)
         └─ 二级子页（CAPABILITY / CONNECTION_DETAILS / AUDIT_LOG / APP_UPDATES / PLUGINS 等）
             └─ ProvideSharedVisibilityScope（不注入 LocalSettingsSharedCategoryKey，保持纯水平 Shared Axis X 转场）
```

Key 命名空间统一收敛在 [SharedTransitions.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/SharedTransitions.kt) 的 `SharedTransitionKeys`（全部英文静态前缀，不受文案与语言影响）：
- `rootSurface(id = "workspace")` → `"root_surface:$id"`（预留 Key，供根层跨屏容器转场按需接入；当前登录与工作台切换由根 `AnimatedContent` 过渡，内部页面元素使用各自作用域 Key）
- `loginCard(id = "form")` → `"login_card:$id"`
- `settingsCategoryCard(route)` → `"settings_category_card:$route"`
- `composerContainer()` → `"composer_container"`
- `composerAction(id = "primary")` → `"composer_action:$id"`
- `expandableCard(id)` → `"expandable_card:$id"`
- `morphingButton(id)` → `"morphing_button:$id"`

### 1.3 Predictive Back 跨版本兼容策略

[PredictiveBackCompat.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/PredictiveBackCompat.kt) 屏蔽 Android 12–13（API 31–33）与 Android 14+（API 34+）的返回手势差异：

1. **平台分流**：`supportsNativePredictiveBack(sdkInt)` 判断 `sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE`（API 34）。
   - **API 34+**：使用 `PredictiveBackHandler` 实时收集 `BackEventCompat.progress` 写入 `Animatable<Float>`（限定 `[0f, 1f]`）。
   - **API 31–33**：自动回退为标准 `BackHandler`，进度保持 `0f`，保证返回语义完全一致。
2. **手势取消平滑复原**：当用户边缘侧滑到一半反滑取消时，`PredictiveBackHandler` 抛出 `CancellationException`。兼容层在 `withContext(NonCancellable)` 中以 `MotionSpecs.spatialDefault(false)` 弹簧将进度平滑回弹至 `0f`，完成后再按协程约定重抛 `CancellationException`。
3. **只作用于绘制层**：`Modifier.predictiveBackSlideTransform(progress, reduced, direction, maxScaleReduction)` 仅通过 `Modifier.graphicsLayer` 计算 `translationX` 与轻微缩放（默认 `maxScaleReduction = 0.04f`，即 `1.0f -> 0.96f`），不触发布局重新测量。
4. **返回栈优先级（LIFO）**：
   - 在工作台手机端全屏设置覆层（[WorkbenchScreen.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt)）与设置总览根页（[SettingsNavHost.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt)）中，子页面由 `NavHost` 内置 `BackHandler` 优先消费；只有退回 `SettingsRoutes.OVERVIEW` 且处于 `SettingsPresentation.SCREEN` 时，根级 `rememberPredictiveBackProgress` 才激活预览并回调 `onBack`。

### 1.4 Reduce Motion 降级决策树

全局通过 `LocalMotionPolicy.current.isReduced`（定义为 `reduceMotion || durationScale <= 0f`，同时响应应用内「减少动画」开关与系统 `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`）统一控制退化行为：

```text
LocalMotionPolicy.current.isReduced == true ?
 ├─ 空间/尺寸/缩放/圆角（spatial* / morph / press / boundsTransform）
 │   └─ 降级为 snap() 直接落位；按压缩放锁定 1.0f（保留 M3 Ripple 水波纹）
 ├─ 共享元素（m3SharedBounds / m3SharedElement）
 │   └─ 直接返回原 Modifier，不进入共享叠加层，不执行边界动画
 ├─ 颜色与透明度效果（effects* / fade / Theme 换色）
 │   ├─ 页面与可见性切换（AppTransitions / ExpandableRegion / M3MorphingIconButton）
 │   │   └─ 降级为 reducedFade()：tween(150ms, easing = LinearEasing)，零位移、零缩放
 │   └─ 主题配色角色切换（OpenAndroidIntelligenceTheme）
 │       └─ 降级为 snap() 瞬时切换
 └─ 预测性返回（rememberPredictiveBackProgress / predictiveBackSlideTransform）
     └─ 手势进度恒定 0f，不执行横向滑移与缩放；点击与完成返回时仍正常触发 onBack
```

---

## 2. `MotionSpecs` 完整物理参数配置表

下表列出 [MotionSpecs.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/MotionSpecs.kt) 中所有物理弹簧与节奏令牌的精确参数：

| 常量 / 工厂方法 | 类别 | `dampingRatio` | `stiffness` | 适用属性与典型场景 | Reduce Motion 降级方案 |
| --- | --- | :---: | :---: | --- | --- |
| `SpatialFastSpring` (`SnappySpring`) / `spatialFast(expressive = false)` / `snappy` | Standard Spatial | `0.9f` | `1400f` | 卡片/按钮圆角形变、固定按钮宽度切换、页面退场位移与缩放 | `snap()` 直接落位 |
| `SpatialDefaultSpring` (`SpatialSpring`) / `spatialDefault(expressive = false)` / `spatial` | Standard Spatial | `0.9f` | `700f` | 页面进场位移与缩放、时间线消息上浮、`PredictiveBack` 取消回弹 | `snap()` 直接落位 |
| `SpatialSlowSpring` / `spatialSlow(expressive = false)` | Standard Spatial | `0.9f` | `300f` | 全屏级空间过渡、大容器展开 | `snap()` 直接落位 |
| `ExpressiveSpatialFastSpring` / `spatialFast(expressive = true)` | Expressive Spatial | `0.6f` | `800f` | 微交互弹跳指示、「回到最新」按钮出现缩放 | `snap()` 直接落位 |
| `ExpressiveSpatialDefaultSpring` / `spatialDefault(expressive = true)` / `boundsTransform` | Expressive Spatial | `0.8f` | `380f` | `SharedTransitionLayout` 共享边界与共享元素变换、悬浮吸附 | `snap()` 直接落位 |
| `ExpressiveSpatialSlowSpring` / `spatialSlow(expressive = true)` | Expressive Spatial | `0.8f` | `200f` | 整屏沉浸式容器变换 | `snap()` 直接落位 |
| `EffectsFastSpring` / `effectsFast` | Effects（临界阻尼） | `1.0f` | `3800f` | 组件容器/内容/边框色过渡、图标切换淡入淡出、页面退场淡出、顶栏分隔线 | `reducedFade()`（`150ms LinearEasing`） |
| `EffectsDefaultSpring` / `effectsDefault` / `fade` | Effects（临界阻尼） | `1.0f` | `1600f` | 页面进场淡入、共享边界交叉淡化、`ExpandableRegion` 内容淡化、主题角色换色 | `reducedFade()`（Theme 为 `snap()`） |
| `EffectsSlowSpring` / `effectsSlow` | Effects（临界阻尼） | `1.0f` | `800f` | 大面积背景遮罩（Scrim）、大块内容显现 | `reducedFade()`（`150ms LinearEasing`） |
| `PressSpring` / `press` | Press 反馈 | `0.75f` | `1200f` | `M3ExpressiveCard` / `M3MorphingButton` / `bouncyClickable` 按压缩放（`PressScaleDefault = 0.97f`、`PressScaleCompact = 0.95f`） | `snap()` 且缩放固定 `1.0f`（保留 Ripple） |
| `MorphSpring` / `morph` / `emphasized` | 布局形变（临界阻尼） | `1.0f` | `700f` | `ExpandableRegion` 垂直展开/折叠、`M3MorphingButton` 自适应宽度、设置项胶囊形变、列表重排 `animateItem` | `snap()` 直接落位 |
| `EntranceSpring` / `entrance` | 悬浮进场 | `0.72f` | `380f` | 助理悬浮栏 `graphicsLayer` 进场位移与缩放 | `snap()` 直接落位 |
| `ExpandSpring` / `expand` | 柔和展开（临界阻尼） | `1.0f` | `360f` | 助理输入栏展开成输入态高度变化 | `snap()` 直接落位 |
| `CollapseSpring` / `collapse` | 快速收起（临界阻尼） | `1.0f` | `800f` | 助理输入栏收起恢复 | `snap()` 直接落位 |
| `MomentumSpring` | 手势惯性 | `0.75f` | `400f` | 停靠球拖拽释放动量吸附 | 直接吸附落位 |
| `SheetSpring` | 抽屉/弹层（临界阻尼） | `1.0f` | `400f` | 底部弹层、侧边抽屉 | `snap()` 直接落位 |

> **保留合法 `tween` 例外与范围边界说明**：除上述物理弹簧外，`:conversation-ui` 与主工作台/登录/设置导航改造范围内仅保留三类特定时序的 `tween` 例外（另附未纳入本轮全弹簧重写范围的既有引导页与 `:assistant-holder` 边界说明）：
> 1. `MotionSpecs.reducedFade()`（`tween(150ms, LinearEasing)`）及兼容保留常量 `MotionSpecs.ReducedMotionSpec`：Reduce Motion / `durationScale <= 0f` 无障碍降级专用；
> 2. `MotionSpecs.staggerEnter()`（含 `delayMillis` 错峰）与 [AssistantView.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/assistant/AssistantView.kt) 详情延迟淡入（`ContentStaggerDelayMillis = 60`，减少动态时零延迟 `150ms`）；
> 3. [MessageTimeline.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt) 的 `StreamingStatusDot` 无限呼吸动画（`infiniteRepeatable(tween(1200))`，减少动态时静止）；
> 4. 非本轮核心重写范围边界：引导流程（`OnboardingFlow.kt`、`RemoteGuideScreen.kt`、`OnboardingStepHeader.kt`）与悬浮助理模块（`:assistant-holder` 下 `AssistantCompactBar.kt`、`AssistantExpandedPanel.kt`）仍保留既有固定时长 `tween` 过渡。

> **缓动与刻度补充**：`MotionSpecs.EmphasizedAccelerateEasing` 已对齐 MD3 官方 `CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)`；`ReducedMotionCrossfadeDuration = 150`（`Fast`）；列表错峰延迟 `staggerDelayMillis(index, reduced)` 每项步进 `40ms`、上限 `240ms`（减少动态时为 `0ms`）。

---

## 3. 核心组件与动效 API 调用示例

### 3.1 `M3ExpressiveCard` 与 `M3ExpandableExpressiveCard`

封装自 [M3ExpressiveCard.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/M3ExpressiveCard.kt)，内置按压缩放、圆角弹簧形变（已做 `coerceAtLeast(0.dp)` 下界保护）、M3 State Layer Ripple、MD3 禁用态配色（`disabledContainerColor` 12% `onSurface`、`disabledContentColor` 38% `onSurface`）、最小触摸热区与可选共享边界。`M3ExpandableExpressiveCard` 的 `onExpandedChange` 为可空回调 `((Boolean) -> Unit)?`：传入 `null` 时按只读卡片渲染（不挂载 `.clickable` 与 `Role.Button` 语义，且不应用禁用态置灰）。

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

封装自 [M3MorphingButton.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/M3MorphingButton.kt)，支持胶囊圆角到方圆角的百分比插值（`.coerceIn(0f, 50f)` 保护）、固定宽或 `animateContentSize(MotionSpecs.morph(reduced))` 自适应宽、颜色临界阻尼切换及共享边界过渡。

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

封装自 [SharedTransitions.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/SharedTransitions.kt)。在需要跨页面/状态过渡的容器树顶部放置 `AppSharedTransitionHost`（或嵌套复用场景下使用 `EnsureSharedTransitionHost`），在 `AnimatedContent` / `AnimatedVisibility` / `NavHost.composable` 内容块内注入 `ProvideSharedVisibilityScope`，叶子节点直接挂接 `Modifier.m3SharedBounds`。

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
import com.openandroidintelligence.conversation.components.SettingsSectionCard
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
                AppTransitions.navEnter(reduced) togetherWith AppTransitions.navExit(reduced)
            },
            label = "category-shared-bounds-demo",
        ) { detail ->
            ProvideSharedVisibilityScope {
                if (!detail) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .m3SharedBounds(
                                key = SharedTransitionKeys.settingsCategoryCard("settings/appearance"),
                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                clipShape = RoundedCornerShape(AppRadius.Large),
                            ),
                    ) {
                        SettingsSectionCard {
                            Text(stringResource(UiR.string.settings_title_appearance))
                        }
                    }
                } else {
                    Surface(
                        Modifier
                            .fillMaxSize()
                            .m3SharedBounds(
                                key = SharedTransitionKeys.settingsCategoryCard("settings/appearance"),
                                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                                clipShape = RoundedCornerShape(AppRadius.Large),
                            ),
                    ) {
                        Text(stringResource(UiR.string.settings_section_theme_mode))
                    }
                }
            }
        }
    }
}
```

### 3.4 `rememberPredictiveBackProgress` 与 `predictiveBackSlideTransform`

封装自 [PredictiveBackCompat.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/PredictiveBackCompat.kt)。用于全屏抽屉、设置总览页等需要在返回前实时跟手预览并在取消时弹簧回弹的页面容器。

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.openandroidintelligence.conversation.motion.predictiveBackSlideTransform
import com.openandroidintelligence.conversation.motion.rememberPredictiveBackProgress
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced

@Composable
fun PredictiveBackPanelExample(
    visibleAtRoot: Boolean,
    onClosePanel: () -> Unit,
    content: @Composable () -> Unit,
) {
    val reduced = LocalMotionPolicy.current.isReduced
    val backProgress by rememberPredictiveBackProgress(
        enabled = visibleAtRoot,
        onBack = onClosePanel,
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .predictiveBackSlideTransform(
                progress = backProgress,
                reduced = reduced,
                direction = -1f,
                maxScaleReduction = 0.04f,
            ),
    ) {
        content()
    }
}
```

---

## 4. 关键集成节点与回归门禁索引

| 模块 | 改造文件 | 核心接入内容 | 对应回归测试 |
| --- | --- | --- | --- |
| `:conversation-ui` | `MotionPolicy.kt`、`MotionSpecs.kt`、`AppTransitions.kt` | 新增 `isReduced`、M3 Standard/Expressive/Effects/Press 弹簧、`AppTransitions` 全弹簧化 | `MotionPhysicsTest`、`AppTransitionsTest` |
| `:conversation-ui` | `SharedTransitions.kt`、`PredictiveBackCompat.kt` | 统一共享宿主/Key/安全修饰符与 API 31–36 预测性返回兼容 | `SharedTransitionsAndPredictiveBackTest` |
| `:conversation-ui` | `M3ExpressiveCard.kt`、`M3MorphingButton.kt`、`Theme.kt`、`ExpandableRegion.kt`、`Interaction.kt` | 动态圆角卡片、形变按钮与主题/展开/按压弹簧升级 | `M3ExpressiveComponentsTest` |
| `:conversation-ui` | `WorkbenchScreen.kt`、`ComposerBar.kt`、`VoiceRecordingRow.kt`、`TimelineScaffold.kt`、`ApprovalCard.kt`、`CommandMenu.kt`、`ComposerAttachmentSheet.kt`、`MarkdownThoughtView.kt`、`MarkdownToolCallView.kt`、`ScreenSelectionOverlay.kt`、`SettingsItemMorph.kt`、`RecordingWaveform.kt`、`AssistantView.kt` | 工作台共享作用域、发送/停止与录音发送键共享边界形变、折叠思维链/工具卡升级、选区/形态 `isReduced` 统一、手机设置预测性返回防抢占 | `M3ExpressiveComponentsTest`、`ThoughtCardTest`、`VoiceRecordingRowTest`、`ScreenSelectionMinimumSizeTest`（并包含在 `:conversation-ui:test`） |
| `:app` | [RemoteWorkbenchRoot.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteWorkbenchRoot.kt) | 主题内层 `AppSharedTransitionHost` 与双层 `AnimatedContent` 可见性注入 | `ExpressiveMotionNavigationTest`、`RemoteRootInteractionTest` |
| `:app` | [RemoteLoginScreen.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteLoginScreen.kt) | 官方/自建切换 `ProvideSharedVisibilityScope` + `m3SharedBounds` + `M3MorphingButton` | `ExpressiveMotionNavigationTest`、`RemoteLoginPresentationTest` |
| `:app` | [SettingsNavHost.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsNavHost.kt)、[SettingsScaffold.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/settings/SettingsScaffold.kt) | 内置 `AppSharedTransitionHost`、一级分类 `sharedCategoryKey` 共享转场、二级页纯水平滑入、`rememberPredictiveBackProgress` | `ExpressiveMotionNavigationTest`、`SettingsNavigationTest`、`SettingsHonestyCopyTest`、`SettingsResponsiveHostRegressionTest`、`MaterialTokenGuardTest` |
