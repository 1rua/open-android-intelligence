# Open Android Intelligence (OAI) 设计规范（Material Design 3）

本文档是 Open Android Intelligence（OAI）Android 客户端的权威设计系统规范。全篇严格以 **Google Material Design 3（MD3）官方文档**（`m3.material.io`）及 **Jetpack Compose Material 3 官方实现指南**（`developer.android.com`）为准绳，覆盖色彩、动效、交互、排版、形状、海拔、自适应布局与组件选用，并将官方标准映射至本项目的多模块架构与自动化守卫门禁。

> **适用范围与技术基线**
> - 目标模块：`apps/android/app`、`apps/android/conversation-ui`、`apps/android/assistant-holder`、`apps/android/plugin-ui`、`apps/android/ui-resources`。
> - 平台基线：`minSdk 31`（Android 12，原生支持动态取色 Monet）、`targetSdk 35`、`compileSdk 36`，当前依赖 `androidx.compose:compose-bom:2024.12.01`（Compose `1.7.6` / Material 3 `1.3.1`）。
> - 核心原则：**一切界面均优先采用 Material 3 官方语义令牌与标准组件**；自定义样式仅允许在标准组件无法表达业务安全边界（如无障碍无焦点急停浮窗、圈选层、流式思考卡）时使用，且仍须严格复用官方色彩、动效与交互令牌。

---

## 目录

1. [色彩系统（Color System）](#1-色彩系统color-system)
2. [动效系统（Motion System）](#2-动效系统motion-system)
3. [交互与状态系统（Interaction & States）](#3-交互与状态系统interaction--states)
4. [排版、形状、海拔与图标（Typography, Shape, Elevation & Iconography）](#4-排版形状海拔与图标typography-shape-elevation--iconography)
5. [自适应布局与全面屏（Adaptive Layout & Edge-to-Edge）](#5-自适应布局与全面屏adaptive-layout--edge-to-edge)
6. [OAI 核心界面组件映射与安全边界](#6-oai-核心界面组件映射与安全边界)
7. [代码映射、自动化守卫与现状对齐清单](#7-代码映射自动化守卫与现状对齐清单)
8. [Google 官方参考文档](#8-google-官方参考文档)

---

## 1. 色彩系统（Color System）

Material Design 3 的色彩系统基于 **HCT（Hue 色相 / Chroma 色度 / Tone 明度）** 感知色彩空间构建，用「语义角色（Color Roles）」替代散落的硬编码色值，用「基于 Tone 的表面容器阶梯（Tone-based Surface Containers）」替代旧版半透明着色叠加。

### 1.1 色彩来源与优先级

| 优先级 | 来源 | 实现方式 | 规范要求 |
| --- | --- | --- | --- |
| 1 | **系统动态取色（Dynamic Color / Monet）** | Android 12+（API 31+）调用官方 `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` | 严禁自行解析壁纸计算色值；开启后全量角色由系统 Monet 引擎提供 |
| 2 | **静态预设色板（Static Baseline / Presets）** | 基于单一种子色经感知明度色调阶梯推导出完整 `ColorScheme` | 当用户关闭动态取色或设备不支持时生效；所有预设均须满足同一套 MD3 Tone 映射与对比度门禁 |
| 3 | **扩展语义色（Custom Semantic Colors）** | `LocalAppStateColors` 提供 `success` / `warning` 及其容器与前景角色 | 仅用于 MD3 标准角色未覆盖的「成功 / 警告待确认」结果表达，遵循相同对比度标准 |

- **深浅色模式**：支持「跟随系统 / 浅色 / 深色」三种模式，切换时使用 `animateColorAsState` 逐角色平滑过渡，禁止整棵组件树 `Crossfade` 重建造成闪烁或状态丢失；开启「减少动态」时瞬时切换（`snap()`）。
- **硬编码红线**：除主题定义文件外，全仓 UI 代码**严禁**出现 `Color(0x...)` 字面量，所有取色一律经由 `MaterialTheme.colorScheme` 或 `LocalAppStateColors`。

### 1.2 官方 MD3 完整语义角色与 HCT Tone 映射（Standard 对比度）

MD3 将颜色划分为 **强调色（Accent）**、**固定强调色（Fixed Accent）**、**中性表面色（Neutral Surface）**、**反色（Inverse）**、**错误色（Error）** 与 **辅助线/遮罩（Outline & Scrim）** 六大组。下表严格对齐 Google MD3 官方基准色调映射（Tone 范围 `0` 纯黑 ～ `100` 纯白）：

#### 1.2.1 强调色组（Primary / Secondary / Tertiary）

| 语义角色（Compose `ColorScheme`） | 调色板来源 | 浅色 Tone | 深色 Tone | 官方用途说明 |
| --- | --- | :---: | :---: | --- |
| `primary` | Primary | `40` | `80` | 最高优先级填充元素、主操作按钮、激活态指示、FAB 主强调 |
| `onPrimary` | Primary | `100` | `20` | 位于 `primary` 之上的文字与图标 |
| `primaryContainer` | Primary | `90` | `30` | 柔和主强调容器（如 FAB 容器、选中芯片、关键摘要底衬） |
| `onPrimaryContainer` | Primary | `10`（增强对比可至 `30` 下限内） | `90` | 位于 `primaryContainer` 之上的文字与图标 |
| `secondary` | Secondary | `40` | `80` | 次级强调元素、低权重筛选与辅助控件 |
| `onSecondary` | Secondary | `100` | `20` | 位于 `secondary` 之上的文字与图标 |
| `secondaryContainer` | Secondary | `90` | `30` | 导航栏/导航轨选中药丸（Active Indicator）、Tonal 按钮容器 |
| `onSecondaryContainer` | Secondary | `10` | `90` | 位于 `secondaryContainer` 之上的文字与图标 |
| `tertiary` | Tertiary | `40` | `80` | 平衡对比与补充高亮（如辅助徽章、次要引导标记） |
| `onTertiary` | Tertiary | `100` | `20` | 位于 `tertiary` 之上的文字与图标 |
| `tertiaryContainer` | Tertiary | `90` | `30` | 第三强调容器 |
| `onTertiaryContainer` | Tertiary | `10` | `90` | 位于 `tertiaryContainer` 之上的文字与图标 |

#### 1.2.2 固定强调色组（Fixed Accent — 跨深浅主题恒定）

用于在浅色与深色模式下均需维持恒定明度关系的组件（如品牌徽章、固定高亮标签、不受主题反转影响的卡片内强调块）：

| 语义角色（Compose `ColorScheme`） | 调色板来源 | 浅色 Tone | 深色 Tone | 官方用途说明 |
| --- | --- | :---: | :---: | --- |
| `primaryFixed` / `secondaryFixed` / `tertiaryFixed` | 对应主/次/三调色板 | `90` | `90` | 跨深浅模式恒定不变的柔和填充容器 |
| `primaryFixedDim` / `secondaryFixedDim` / `tertiaryFixedDim` | 对应主/次/三调色板 | `80` | `80` | 比 Fixed 略深的强调层级（悬停/按下/层次区分） |
| `onPrimaryFixed` / `onSecondaryFixed` / `onTertiaryFixed` | 对应主/次/三调色板 | `10` | `10` | 位于 Fixed / FixedDim 上的高对比正文与图标 |
| `onPrimaryFixedVariant` / `onSecondaryFixedVariant` / `onTertiaryFixedVariant` | 对应主/次/三调色板 | `30` | `30` | 位于 Fixed / FixedDim 上的次级文本与低强调图标 |

#### 1.2.3 中性表面与五级表面容器（Surface & Surface Containers）

MD3 将「表面层级」从阴影海拔中解耦，**禁止**以单一 `surface` 叠加半透明 `primary` 模拟层次，而是按视觉包裹与聚焦权重选用五级 `surfaceContainer*`：

| 语义角色（Compose `ColorScheme`） | 调色板来源 | 浅色 Tone | 深色 Tone | 官方用途与层级含义 |
| --- | --- | :---: | :---: | --- |
| `surface`（同 `background`） | Neutral | `98` | `6` | 页面默认主画布底色 |
| `surfaceDim` | Neutral | `87` | `6` | 在浅/深模式下始终最暗的背景表面，用于衬托高亮度面板 |
| `surfaceBright` | Neutral | `98` | `24` | 在浅/深模式下始终最亮的背景表面 |
| `surfaceContainerLowest` | Neutral | `100` | `4` | 最低强调容器（下沉区、浅色下纯白内容卡与主背景形成洁净对比） |
| `surfaceContainerLow` | Neutral | `96` | `10` | 低强调容器（Elevated Card、次级内容面板、侧边导航抽屉） |
| `surfaceContainer` | Neutral | `94` | `12` | **推荐默认容器**（标准卡片、底部导航栏、导航轨、设置分组底板） |
| `surfaceContainerHigh` | Neutral | `92` | `17` | 高强调容器（对话框 Dialog、底部弹层 Sheet、搜索栏、输入坞） |
| `surfaceContainerHighest` | Neutral | `90` | `22` | 最高强调容器（Filled Card、Filled 文本输入框、切换开关轨道、嵌套高亮区） |
| `onSurface` | Neutral | `10` | `90` | 表面上的主正文、标题与高强调图标 |
| `onSurfaceVariant` | Neutral Variant | `30` | `80` | 表面上的副标题、说明文案、次要图标与未选中项 |

#### 1.2.4 反色、错误与辅助线（Inverse, Error, Outline & Scrim）

| 语义角色（Compose `ColorScheme`） | 调色板来源 | 浅色 Tone | 深色 Tone | 官方用途说明 |
| --- | --- | :---: | :---: | --- |
| `inverseSurface` | Neutral | `20` | `90` | 与周围画布反差明显的临时容器（Snackbar、Plain Tooltip） |
| `inverseOnSurface` | Neutral | `95` | `20` | `inverseSurface` 上的文本与图标 |
| `inversePrimary` | Primary | `80` | `40` | `inverseSurface` 上的操作按钮（如 Snackbar 的 Action 文字） |
| `error` | Error | `40` | `80` | 错误提示、校验失败边框、危险/破坏性操作强调 |
| `onError` | Error | `100` | `20` | 位于 `error` 之上的文本与图标 |
| `errorContainer` | Error | `90` | `30` | 错误横幅、告警卡片与高风险确认容器 |
| `onErrorContainer` | Error | `10` | `90` | 位于 `errorContainer` 之上的文本与图标 |
| `outline` | Neutral Variant | `50` | `60` | 关键边界线（Outlined Button / Outlined TextField / Outlined Card） |
| `outlineVariant` | Neutral Variant | `80` | `30` | 弱化装饰分隔线（`HorizontalDivider`、低强调边框） |
| `scrim` | Neutral | `0`（纯黑 + `0.32f` 透明度） | `0`（纯黑 + `0.32f` 透明度） | 模态弹窗、抽屉与底部弹层后方的背景遮罩 |

### 1.3 对比度分级与无障碍色盲红线

1. **WCAG 2.1 最低对比度门禁**：
   - 常规正文与标签（`< 18sp` 常规字重或 `< 14sp` 粗体）：对比度 **≥ 4.5 : 1**。
   - 大号文字（`≥ 18sp` 常规或 `≥ 14sp` 粗体）及非文本图形控件（图标、输入框描边、开关轮廓、状态指示点）：对比度 **≥ 3.0 : 1**。
   - 禁用态（Disabled）免于强制对比度阈值，但必须与启用态形成清晰可辨的弱化区分（详见 §3.1）。
2. **用户可控对比度等级（User-Controlled Contrast Levels）**：
   - MD3 定义 **Standard（标准）**、**Medium（中对比）**、**High（高对比）** 三个对比度阶梯；Android 14+ 系统高对比度/动态色偏好下，`dynamicLightColorScheme` / `dynamicDarkColorScheme` 自动响应系统对比度档位。
   - 自定义静态色板须确保在全部 `surface` 与 `surfaceContainer*` 层级上，`onSurface` 与 `onSurfaceVariant` 均达到 ≥ 4.5:1。
3. **颜色绝非唯一信号**：任何连接状态、权限授权结果、错误阻断、待确认告警，**必须同时配合清晰文字标签或语义图标**，禁止仅靠红/绿/黄小圆点传达状态。

### 1.4 扩展语义状态色（Custom Semantic Colors）

MD3 官方标准 `ColorScheme` 仅包含 `error`，不含 `success` 与 `warning`。按照 MD3 Custom Colors 指南，OAI 通过 `LocalAppStateColors` 补充四元组结构（主色 / `on*` / `*Container` / `on*Container`），且**不随用户主题色板漂移**（保证绿色始终代表成功/已完成、琥珀色始终代表等待/需确认/结果未知）：

| 扩展语义角色 | 浅色值 | 深色值 | 适用场景 |
| --- | --- | --- | --- |
| `success` / `onSuccess` | `#2E6B4F` / `#FFFFFF` | `#8FD3AE` / `#0A2A19` | 任务完成、校验通过、连接就绪 |
| `successContainer` / `onSuccessContainer` | `#D3E7D9` / `#14361F` | `#1E3A2C` / `#C7EBD8` | 已完成徽章、成功提示条 |
| `warning` / `onWarning` | `#8A5A16` / `#FFFFFF` | `#E7BE7E` / `#3B2A08` | 等待确认、临时授权倒计时、结果未知 |
| `warningContainer` / `onWarningContainer` | `#F5E3C3` / `#3B2A08` | `#453314` / `#F7E4C2` | 风险提示横幅、待确认状态胶囊 |

---

## 2. 动效系统（Motion System）

Google Material Design 3 现行官方动效规范包含两套协同体系：
1. **现代弹簧物理系统（Motion Physics System — M3 Expressive & Standard `MotionScheme`）**：以刚度（Stiffness）和阻尼比（Damping Ratio）驱动，天然支持手势跟手、任意时刻打断与速度继承，为**首选默认范式**。
2. **传统缓动与时长刻度系统（Easing & Duration Tokens）**：用于非物理插值、固定时间节拍转场或不支持原生弹簧的过渡场景。

### 2.1 官方 MD3 弹簧物理系统（Motion Physics / Springs）

MD3 将全部动画属性严格区分为两大类：
- **Spatial（空间弹簧）**：用于位移、尺寸、缩放、圆角形变等几何变化。允许存在克制的弹性过冲（Overshoot），传达物理质量感。
- **Effects（效果弹簧）**：用于透明度（Alpha）、颜色（Color）等非空间属性。**必须采用临界阻尼（`dampingRatio = 1.0f`），严禁过冲或闪烁**。

每个类别按组件尺度与运动幅度分为 `fast`（小控件/瞬态反馈）、`default`（常规容器与局部布局）、`slow`（大面积容器与整屏转场）三档，并提供 **Standard（实用克制）** 与 **Expressive（富有表现力）** 两套官方方案（质量均按标准 `mass = 1`）：

#### 2.1.1 Standard 方案（实用型 — OAI 默认推荐）

适合高频效率工具、对话阅读与设置界面，响应利落、几乎无回弹：

| 官方 Token（`md.sys.motion.spring.*`） | 类别 | `dampingRatio` | `stiffness` | 等效非弹簧回退（Cubic-Bezier / 时长） | 典型场景 |
| --- | --- | :---: | :---: | --- | --- |
| `standard.fast.spatial` | Spatial | `0.9f` | `1400f` | `cubic-bezier(0.27, 1.06, 0.18, 1.00)` · `350ms` | 开关滑块、按钮形态切换、小图标位移 |
| `standard.default.spatial` | Spatial | `0.9f` | `700f` | `cubic-bezier(0.27, 1.06, 0.18, 1.00)` · `500ms` | 卡片展开/收起、底部弹层、抽屉、附件条高度变化 |
| `standard.slow.spatial` | Spatial | `0.9f` | `300f` | `cubic-bezier(0.27, 1.06, 0.18, 1.00)` · `750ms` | 全屏级空间过渡、大容器展开 |
| `standard.fast.effects` | Effects | `1.0f` | `3800f` | `cubic-bezier(0.31, 0.94, 0.34, 1.00)` · `150ms` | 按钮/图标颜色切换、状态层淡入淡出 |
| `standard.default.effects` | Effects | `1.0f` | `1600f` | `cubic-bezier(0.34, 0.80, 0.34, 1.00)` · `200ms` | 页面内容淡入淡出、主题色平滑切换 |
| `standard.slow.effects` | Effects | `1.0f` | `800f` | `cubic-bezier(0.34, 0.88, 0.34, 1.00)` · `300ms` | 大面积背景遮罩（Scrim）、大块内容显现 |

#### 2.1.2 Expressive 方案（表现型 — 关键时刻选用）

适合欢迎引导页、默认数字助理唤起、FAB/停靠球弹性交互等需要突出情感与触感的英雄时刻（Hero Moments）：

| 官方 Token（`md.sys.motion.spring.*`） | 类别 | `dampingRatio` | `stiffness` | 等效非弹簧回退（Cubic-Bezier / 时长） | 典型场景 |
| --- | --- | :---: | :---: | --- | --- |
| `expressive.fast.spatial` | Spatial | `0.6f` | `800f` | `cubic-bezier(0.42, 1.67, 0.21, 0.90)` · `350ms` | 小型微交互、选中弹跳指示、快捷操作弹开 |
| `expressive.default.spatial` | Spatial | `0.8f` | `380f` | `cubic-bezier(0.38, 1.21, 0.22, 1.00)` · `500ms` | 助理悬浮栏进场、局部弹层展开、手势吸附释放 |
| `expressive.slow.spatial` | Spatial | `0.8f` | `200f` | `cubic-bezier(0.39, 1.29, 0.35, 0.98)` · `650ms` | 全屏容器变换、大型沉浸式展开 |
| `expressive.fast.effects` | Effects | `1.0f` | `3800f` | `cubic-bezier(0.31, 0.94, 0.34, 1.00)` · `150ms` | 同 Standard（Effects 永不过冲） |
| `expressive.default.effects` | Effects | `1.0f` | `1600f` | `cubic-bezier(0.34, 0.80, 0.34, 1.00)` · `200ms` | 同 Standard（Effects 永不过冲） |
| `expressive.slow.effects` | Effects | `1.0f` | `800f` | `cubic-bezier(0.34, 0.88, 0.34, 1.00)` · `300ms` | 同 Standard（Effects 永不过冲） |

> **布局稳定性约束**：当使用带过冲（`dampingRatio < 1.0f`）的 Expressive Spatial 弹簧时，**优先作用于 `Modifier.graphicsLayer`（位移、缩放）**；若直接驱动会触发布局重测的高度/宽度属性（如 `animateContentSize`），应优先采用 `Standard` 或无过冲阻尼（`dampingRatio = 1.0f`），避免下方消息列表或按钮发生视觉抖动。
> **Compose 版本衔接**：当前项目使用 `material3 1.3.1`，直接在 `MotionSpecs.kt` 中用 `spring(dampingRatio, stiffness)` 精确实现上述官方数值；待依赖升级至 Compose Material 3 `1.4+` 时，直接接入 `MaterialExpressiveTheme(motionScheme = MotionScheme.standard())`（局部高光节点按需覆盖 `MotionScheme.expressive()`）。

### 2.2 官方 MD3 缓动曲线与时长刻度（Easing & Duration Tokens）

当使用 `tween(...)`、`EnterTransition` / `ExitTransition` 或时间驱动动画时，**必须**严格使用以下官方曲线与时长刻度，禁止凭感觉手写毫秒数与贝塞尔控制点。

#### 2.2.1 官方缓动曲线（`md.sys.motion.easing.*`）

| 官方 Token | 精确曲线定义 | 官方适用场景 |
| --- | --- | --- |
| `emphasized` | 两段式 `PathInterpolator`：`M 0,0 C 0.05,0, 0.133333,0.06, 0.166666,0.4 C 0.208333,0.82, 0.25,1, 1,1` | 屏幕内起止的重要容器形变、主导航与焦点转移（快速起步、长尾缓落） |
| `emphasized.decelerate` | `CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)` | **元素进入屏幕**（页面推进进入、底部弹层升起、对话框浮现） |
| `emphasized.accelerate` | `CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)` | **元素离开屏幕**（页面退出、弹层关闭、临时提示收起） |
| `standard` | `CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)` | 屏幕内起止的中性/工具型状态变化（主题换色、小控件切换） |
| `standard.decelerate` | `CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)` | 轻量元素进入屏幕（次级菜单、简单浮层出现） |
| `standard.accelerate` | `CubicBezierEasing(0.3f, 0.0f, 1.0f, 1.0f)` | 轻量元素离开屏幕（次级菜单、简单浮层消失） |
| `linear` | `LinearEasing` (`0, 0, 1, 1`) | 纯进度条、循环旋转加载器、减少动态降级淡化 |

#### 2.2.2 官方时长刻度（`md.sys.motion.duration.*`）

| 分组 | Token 与精确时长 | 官方适用范围 |
| --- | --- | --- |
| **Short（短）** | `short1` `50ms` · `short2` `100ms` · `short3` `150ms` · `short4` `200ms` | 小型工具交互：复选框/开关、图标交叉淡化、按压回弹、页面快速离场 |
| **Medium（中）** | `medium1` `250ms` · `medium2` `300ms` · `medium3` `350ms` · `medium4` `400ms` | 中等区域过渡：导航切换、页面进场、列表项展开、底部弹层、主题颜色过渡 |
| **Long（长）** | `long1` `450ms` · `long2` `500ms` · `long3` `550ms` · `long4` `600ms` | 大面积表达性过渡：整屏容器变换、大面板展开 |
| **Extra Long（超长）** | `extraLong1` `700ms` · `extraLong2` `800ms` · `extraLong3` `900ms` · `extraLong4` `1000ms` | 极少使用的环境/非直接触发过渡（严禁用于常规点击响应） |

#### 2.2.3 官方推荐配对（Easing + Duration）

| 运动类型 | Emphasized 推荐组合 | Standard 推荐组合 | 说明 |
| --- | --- | --- | --- |
| **屏幕内起止（Begin & End on screen）** | `emphasized` + `long2`（`500ms`） | `standard` + `medium2`（`300ms`） | 展开/折叠、状态形变、主题平滑过渡 |
| **进入屏幕（Enter screen）** | `emphasized.decelerate` + `medium4`（`400ms`） | `standard.decelerate` + `medium1`（`250ms`） | 进场留出充足减速段让视线落焦 |
| **离开屏幕（Exit screen）** | `emphasized.accelerate` + `short4`（`200ms`） | `standard.accelerate` + `short4`（`200ms`） | **离场必须快于进场**，避免用户感到拖沓或卡住 |

### 2.3 官方 MD3 四大标准转场模式（Transition Patterns）

| 转场模式 | 运动机制 | 官方适用条件 | OAI 对应场景 |
| --- | --- | --- | --- |
| **Container Transform（容器变换）** | 共享同一持久化容器，对边界、圆角与内容做连续形变 | 前后两态存在明确的「同一实体展开/收起」关联 | 助理输入栏 ↔ 停靠悬浮球、消息附件缩略图 ↔ 全屏预览、思考/工具折叠卡展开 |
| **Shared Axis（共享轴 X / Y / Z）** | 出入双方沿同一坐标轴同步位移 + 交叉淡入淡出（Z 轴伴随轻微缩放） | 存在顺序、层级或父子导航关系 | **X 轴**：引导页多步向导、设置中心子页面推进/返回；**Y 轴**：垂直步骤切换；**Z 轴**：层级深入（如工作台 ↔ 子详情） |
| **Fade Through（淡穿）** | 离场页面先淡出，进场页面伴随轻微放大（`0.92 → 1.0`）淡入 | 彼此**无直接空间/层级关联**的同级目标切换 | 切换不同 Host / Workspace 会话列表、无空间关系的主模块切换 |
| **Fade（淡入淡出）** | 仅透明度变化（伴随微小缩放 `0.8 → 1.0` 进场），无长距离位移 | 在当前屏幕边界内原地出现/消失的临时组件 | `AlertDialog`、权限确认弹窗、`DropdownMenu`、命令提示浮层、Snackbar |

### 2.4 动效四条工程铁律

1. **可中断与速度连续（Interruptibility & Velocity Continuity）**：所有手势与状态动画在未结束前被反向触发时，必须从当前帧位置与瞬时速度平滑转向新目标，禁止重置到起点或锁死用户输入（动画期间输入栏与返回键保持响应）。
2. **异步流不干扰阅读**：后台线程/非当前会话事件禁止触发当前界面的滚动或动效；流式回复到达时若用户已手动向上滚动离开底部，禁止强制拉回底部，仅提供显式「回到最新」按钮。
3. **尊重无障碍减少动态（Reduce Motion）**：同时监听系统 `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` 与应用内「减少动态」开关（统一经由 `LocalMotionPolicy` 暴露）：
   - 开启后：**所有位移、缩放、弹性过冲一律取消**；
   - 空间形变降级为 `snap()` 直接落位；
   - 页面与内容切换降级为极短无位移淡入淡出（`short3` `150ms` + `LinearEasing`）。
4. **禁止装饰性无限循环**：除真实进行中的加载指示器（Indeterminate Progress）与正在生成中的状态提示外，禁止给静态页面元素添加持续呼吸、闪烁或漂浮循环动画；一旦任务结束或失败，相关动效立即停止。

---

## 3. 交互与状态系统（Interaction & States）

Material Design 3 通过统一的「状态层（State Layers）」、最小触摸热区、可预测手势与多模态无障碍语义，确保触屏、外接键盘、鼠标与屏幕阅读器（TalkBack）获得一致、可预期的交互反馈。

### 3.1 官方 MD3 状态层（State Layers）与透明度规范

MD3 不再为每个组件手写悬停色或按压色，而是在组件容器之上叠加一层半透明 **State Layer**，其颜色**始终继承该组件的前景内容色（`on*` 角色）**（例如 `primary` 按钮的状态层取 `onPrimary`，`surface` 列表项的状态层取 `onSurface`）。

| 交互状态 | 官方 Token | 状态层透明度 | 视觉与行为规范 |
| --- | --- | :---: | --- |
| **Enabled（启用）** | — | `0.00` | 默认静息态，完整对比度 |
| **Hover（悬停）** | `md.sys.state.hover.state-layer-opacity` | `0.08`（8%） | 鼠标/触控笔悬停触发；不可与触屏常驻混淆 |
| **Focus（聚焦）** | `md.sys.state.focus.state-layer-opacity` | `0.10`（10%） | 键盘 `Tab`/方向键或辅助设备聚焦触发；叠加清晰 Focus Indicator 焦点环（外轮廓 3dp / 间距 2dp 或平台标准焦点框） |
| **Pressed（按下）** | `md.sys.state.pressed.state-layer-opacity` | `0.10`（10%） | 点击/触摸触发，叠加 MD3 **Ripple 水波纹**从触点扩散的瞬时触觉视觉反馈 |
| **Dragged（拖拽）** | `md.sys.state.dragged.state-layer-opacity` | `0.16`（16%） | 长按并拖动元素重排/停靠时触发，通常伴随海拔抬升（如 Level 0 → Level 3/4） |
| **Selected / Activated（选中）** | 容器角色切换 | 见说明 | 通过切换语义容器色（如 `secondaryContainer` / `primaryContainer`）与图标实心化表达，并允许与 Hover/Focus/Pressed 状态层**叠加组合** |
| **Disabled（禁用）** | 容器 `0.12` / 内容 `0.38` | 不可交互 | 容器填充降至 `onSurface` **12%（`0.12f`）**、前景文字与图标降至 `onSurface` **38%（`0.38f`）**；**完全阻断 Hover、Focus、Pressed、Dragged 与点击回调** |

> **按压微形变约束**：若对自定义圆形按钮或卡片叠加轻微按压缩放（如 `bouncyClickable` 的 `0.97f`），仅作为标准 Ripple 状态层的补充，**不得移除或替换 `LocalIndication.current` 的 MD3 Ripple**；且「减少动态」开启时必须关闭缩放，保留 Ripple 与完整点击语义。

### 3.2 触摸目标与热区（Touch Targets）

1. **最小热区 `48dp × 48dp`**：所有可交互控件（图标按钮、芯片、复选框、开关、列表行操作图标、关闭按钮）的逻辑触摸热区不得小于 `48dp × 48dp`。
2. **视觉与热区分离**：当图标视觉尺寸为 `24dp` 或紧凑控件为 `32dp` / `40dp` 时，必须借助 `minimumInteractiveComponentSize()` 或外层透明点击区域将可点击范围扩展至 `48dp × 48dp`。
3. **相邻间距 `≥ 8dp`**：同一操作行内相邻两个可点击目标的视觉边界之间保留至少 `8dp` 间距，防止单手误触（尤其是「拒绝 / 允许」或「取消 / 发送」等互斥操作）。

### 3.3 手势操作与预测性返回（Gestures & Predictive Back）

1. **手势基本规范**：
   - **1:1 直接操纵**：拖拽停靠球、滑出侧边抽屉、下拉关闭底部弹层、圈选画框时，视觉位移与手指移动保持严格 1:1 实时跟随；到达边界时应用平滑阻尼阻力（Rubber-banding），严禁突然撞墙截断。
   - **速度投射与可逆性**：松手瞬间通过 `VelocityTracker` 继承瞬时初速度交由 Spatial Spring 吸附；手势未过阈值反拉时必须无损回到原状态。
   - **非手势等价入口**：任何依赖拖拽、长按或滑动触发的功能（如停靠球移动/关闭、删除会话、展开抽屉），**必须同时提供可点击按钮或无障碍自定义动作（Custom Accessibility Action）**，保障行动不便用户与 TalkBack 用户完整可用。
2. **Android 预测性返回（Predictive Back）**：
   - 遵循 Android 14+ / 15+ 与 MD3 预测性返回规范，所有路由页面、标准 `ModalBottomSheet`、`ModalNavigationDrawer`、`SearchBar` 须支持系统边缘侧滑的实时预览进度（跟手缩放/回退），用户中途反滑取消时弹性复原。
   - 拦截返回键（如编辑草稿未保存、全屏圈选中、模态权限确认中）必须使用 Compose 标准返回调度机制（`BackHandler` / `PredictiveBackHandler` / `NavigationBackHandler`），禁止劫持系统返回导致无法退出。

### 3.4 多模态输入、无障碍与触觉反馈（Input, Accessibility & Haptics）

| 维度 | 官方 MD3 与 Android 规范要求 |
| --- | --- |
| **键盘与外设** | 完整支持 `Tab` / 方向键焦点遍历、`Enter` / `Space` 激活、`Esc` 关闭弹窗/浮层；物理键盘输入时 `Ctrl+Enter` 或按用户习惯支持快捷提交，焦点顺序遵循从上到下、从起始到结束的逻辑阅读流 |
| **屏幕阅读器（TalkBack）** | 所有纯图标按钮必须提供准确 `contentDescription`（来自 `:ui-resources` 国际化资源）；装饰性图标/引导线显式设置 `contentDescription = null` 或 `clearAndSetSemantics`；状态切换通过 `stateDescription` 与 `Role`（`Button`、`Switch`、`Tab`、`Checkbox`、`RadioButton`）暴露；关键异步提示（如连接断开、错误横幅、授权结果）合理使用 `LiveRegionMode.Polite` |
| **触觉反馈（Haptics）** | 仅使用 Android 标准 `HapticFeedbackConstants` / Compose `LocalHapticFeedback`（`Confirm`、`Reject`、`LongPress`、`TextHandleMove`、`GestureThresholdActivate`），严禁滥用高强度长震动；尊重系统触觉总开关，流式生成触觉反馈须额外服从应用内 `LocalStreamingHapticFeedbackEnabled`（默认关闭） |

### 3.5 反馈、选择与临时浮层交互规范

| 交互组件 | 官方 MD3 适用边界 | 交互规则 |
| --- | --- | --- |
| **Snackbar** | 轻量、非阻断、可瞬时消失的操作回执（如「已复制」「已撤销」） | 单次仅展示一条；最多 1 个文本操作按钮（使用 `inversePrimary`）；不遮挡 FAB 或输入坞核心操作；不承载关键安全决策 |
| **AlertDialog / BasicAlertDialog** | 需要用户显式确认或阻断决策的事项（如危险删除、发短信/屏幕控制授权确认） | 明确陈述「谁在请求、将执行什么、影响范围」；操作按钮右对齐且主/次语义清晰；**高危/敏感确认弹窗禁止点击外部 Scrim 误关闭** |
| **ModalBottomSheet** | 与当前上下文直接关联的局部操作或选项面板（如附件来源选择、快捷筛选） | 顶部配备标准 `32dp × 4dp` 拖拽手柄（Drag Handle，且具备 `48dp` 点击/关闭语义）；顶部双圆角采用 `ExtraLarge`（`28dp`）；支持手势下拉关闭与预测性返回 |
| **Plain / Rich Tooltip** | 解释纯图标按钮含义或复杂权限项简短说明 | Plain Tooltip 仅放简短标签，不放交互按钮；Rich Tooltip 可包含标题、说明与 1–2 个操作链接 |
| **Progress Indicators** | 加载、上传、等待远端响应 | **诚实进度铁律**：接口提供精确字节/步数时才用 Determinate 确定进度条；接口未提供进度百分比时**必须使用 Indeterminate 不定进度指示器 + 清晰状态文字**，严禁编造假百分比 |

---

## 4. 排版、形状、海拔与图标（Typography, Shape, Elevation & Iconography）

### 4.1 官方 MD3 排版层级（Type Scale）

MD3 定义 5 组 × 3 档共 **15 个基础排版角色**（Display / Headline / Title / Body / Label）。下表列出 **Google MD3 官方基准规格** 及本项目的中文混排落地准则：

| 角色（`MaterialTheme.typography`） | 官方字号 | 官方行高 | 官方字重 | 官方字距 | 官方与 OAI 典型用途 |
| --- | :---: | :---: | :---: | :---: | --- |
| `displayLarge` | `57sp` | `64sp` | Regular (`400`) | `-0.25sp` | 大屏/欢迎页超大展示数字（手机常规业务页慎用） |
| `displayMedium` | `45sp` | `52sp` | Regular (`400`) | `0sp` | 大屏展示主视觉文字 |
| `displaySmall` | `36sp` | `44sp` | Regular (`400`) | `0sp` | 大号引导标语 |
| `headlineLarge` | `32sp` | `40sp` | Regular (`400`) | `0sp` | 页面级大标题（折叠式 Large TopAppBar） |
| `headlineMedium` | `28sp` | `36sp` | Regular (`400`) | `0sp` | 引导页主标题、核心功能页标题 |
| `headlineSmall` | `24sp` | `32sp` | Regular (`400`) | `0sp` | 对话框主标题、二级大区块标题 |
| `titleLarge` | `22sp` | `28sp` | Regular (`400`) | `0sp` | 标准 `TopAppBar` 标题、底部弹层主标题 |
| `titleMedium` | `16sp` | `24sp` | Medium (`500`) | `0.15sp` | 卡片标题、设置列表主项、次级顶栏标题 |
| `titleSmall` | `14sp` | `20sp` | Medium (`500`) | `0.1sp` | 分组小标题、折叠卡摘要标题 |
| `bodyLarge` | `16sp` | `24sp`（中文长文可放宽至 `26sp`） | Regular (`400`) | `0.5sp`（中文可收紧至 `0.1sp`） | **对话正文、长段阅读与表单输入文本** |
| `bodyMedium` | `14sp` | `20sp`（中文可放宽至 `22sp`） | Regular (`400`) | `0.25sp`（中文可收紧至 `0.1sp`） | 列表副标题、权限说明、帮助文本 |
| `bodySmall` | `12sp` | `16sp`（中文可放宽至 `18sp`） | Regular (`400`) | `0.4sp`（中文可收紧至 `0.1sp`） | 辅助注释、表单校验说明 |
| `labelLarge` | `14sp` | `20sp` | Medium (`500`) | `0.1sp` | 按钮文字、Tab 标签、筛选 Chip |
| `labelMedium` | `12sp` | `16sp` | Medium (`500`) | `0.5sp` | 导航栏标签、状态徽章、分组标签 |
| `labelSmall` | `11sp` | `16sp` | Medium (`500`) | `0.5sp` | 极小角标、次级时间戳 |

**排版补充规范**：
- **系统字体回退**：默认不硬指定西文字体族，交给 Android 系统原生字体回退链（Roboto + Noto Sans SC / 厂商系统中文黑体），确保中英混排基线与字面高度一致。
- **等宽字体边界**：`FontFamily.Monospace`（如 `AppTextStyles.Mono` `12sp` / `MonoSmall` `11sp`）**仅**用于代码块、命令行、时间戳、协议版本、文件大小、设备 ID 与错误码，禁止用于普通界面文案。
- **动态字体缩放**：所有字号与行高必须使用 `sp`；严禁使用固定 `dp` 高度裁切可能折行的多行文本，确保系统 130%～200% 字体放大下文字完整可读、不重叠不截断。

### 4.2 官方 MD3 形状体系（Shape Scale）

MD3 形状体系遵循「容器越大、越外层，圆角越大」的层级关系：

| 官方 Token（`MaterialTheme.shapes`） | 官方圆角值 | 典型 MD3 组件 | OAI 映射示例 |
| --- | :---: | --- | --- |
| `None` | `0dp` | 全宽顶部栏、全屏画布、水平分隔线 | `TopAppBar`、页面底画布 |
| `ExtraSmall` | `4dp` | `DropdownMenu`、Snackbar、TextField 顶部角、Tooltip | 命令补全菜单、Snackbar、消息气泡方向尾角 |
| `Small` | `8dp` | Chip、Rich Tooltip、小型标签底衬 | 筛选芯片、内联状态徽章、附件小缩略图 |
| `Medium` | `12dp` | 标准 Card、Small FAB | 思考/工具折叠卡、常规功能卡片、输入框容器 |
| `Large` | `16dp` | Standard FAB、Navigation Drawer（单侧）、Search View 展开卡 | 设置分组卡片、抽屉边缘、大号操作容器 |
| `ExtraLarge` | `28dp` | `AlertDialog`、`ModalBottomSheet`（仅顶角）、Large FAB | 权限确认弹窗、附件底部弹层、助理展开面板 |
| `Full` | `50%`（胶囊/正圆） | 胶囊按钮、SearchBar、Navigation 选中指示药丸、Switch、Avatar、Badge | 发送/停止按钮、用户头像、悬浮停靠球、状态胶囊 |

- **非对称圆角规范**：底部弹层仅顶部左右两角取 `28dp`（底部贴边为 `0dp`）；对话气泡采用方向尾角（如其余三角取大圆角，发言侧尾角收敛为 `4dp`）强化对话方向归属。
- **禁止散值**：禁止在界面中随意编写 `RoundedCornerShape(10.dp)`、`RoundedCornerShape(14.dp)` 等游离于形状刻度之外的数值。

### 4.3 官方 MD3 海拔系统（Elevation Levels）

MD3 将**阴影海拔（Z 轴物理高度）**与**表面色调（Surface Container）**配合使用，大部分静息界面依靠 Surface Container 区分层次（保持 Level 0，减少视觉噪点），仅在需要表达「浮起、可拖拽、模态覆盖」时使用阴影：

| 海拔等级 | 官方 `dp` 值 | 对应默认表面参考 | 典型组件与场景 |
| :---: | :---: | --- | --- |
| **Level 0** | `0dp` | `surface` / `surfaceContainerHighest`（Filled Card） / Outlined Card | 页面底面、标准填充/描边卡片、静息态 TopAppBar |
| **Level 1** | `1dp` | `surfaceContainerLow` | Elevated Card、Elevated Button、悬停态卡片 |
| **Level 2** | `3dp` | `surfaceContainer` | `NavigationBar`、`Menu`、`TopAppBar` 滚动重叠态、标准悬浮栏 |
| **Level 3** | `6dp` | `primaryContainer` / `surfaceContainerHigh` | `FAB`、`ModalBottomSheet`、`SearchBar`、`AlertDialog`、拖拽态组件 |
| **Level 4** | `8dp` | `surfaceContainer` / `surfaceContainerHigh` | 拖拽中的卡片、高优先持久浮层 |
| **Level 5** | `12dp` | `surfaceContainerHigh` | 极少数最高层级临时浮层与系统级确认覆盖层 |

### 4.4 图标系统（Iconography）

- 统一采用 **Material Symbols / Material Icons**（优先 `Rounded` 或 `Outlined` 单一风格族，全应用保持一致），标准画布尺寸 `24dp × 24dp`（次级内联图标 `18dp`）。
- **激活态切换**：导航项、收藏/置顶等双态图标在未选中时使用 Outlined 空心轮廓，选中时切换为 Filled 实心填充，同时配合 `secondaryContainer` 指示药丸。
- 图标颜色一律来自语义角色（`onSurface` / `onSurfaceVariant` / `primary` / `error`），不得单独硬编码灰阶。

---

## 5. 自适应布局与全面屏（Adaptive Layout & Edge-to-Edge）

### 5.1 官方 MD3 窗口尺寸分级（Window Size Classes）

布局切换**一律基于当前窗口可用宽高**（`WindowSizeClass`），严禁根据设备型号、硬件形态或屏幕方向硬编码判断。下表对齐 Google MD3 最新五档宽度断点：

| 窗口等级 | 宽度范围 | 典型设备场景 | 官方推荐页面外边距 | 官方推荐导航组件 | 推荐典型布局（Canonical Layout） |
| --- | :---: | --- | :---: | --- | --- |
| **Compact（紧凑）** | `< 600dp` | 手机竖屏、分屏窄窗 | `16dp` | 底部 `NavigationBar` 或模态 `ModalNavigationDrawer` | 单窗格（Single Pane） |
| **Medium（中等）** | `600dp – 839dp` | 平板竖屏、大折叠屏展开竖屏 | `24dp` | 侧边 `NavigationRail` | 单窗格（限定最大阅读宽）或轻双窗格 |
| **Expanded（展开）** | `840dp – 1199dp` | 平板横屏、折叠屏横屏、小桌面窗 | `24dp` | 侧边 `NavigationRail` 或常驻侧栏（`PermanentNavigationDrawer`） | 双窗格 List-Detail / Supporting Pane |
| **Large（大屏）** | `1200dp – 1599dp` | 笔记本 / 标准桌面显示器 | `24dp` | 展开式 `NavigationRail` 或常驻侧栏 | 双窗格 / 三窗格 + 居中阅读区约束 |
| **Extra-large（超大屏）** | `≥ 1600dp` | 超宽屏显示器 | `24dp` | 展开式 `NavigationRail` 或常驻侧栏 | 多窗格工作台 + 内容宽度上限保护 |

### 5.2 栅格、间距与阅读宽度

1. **8dp 基准栅格**：所有页面留白、卡片间距、控件尺寸严格落在 `8dp` 步进体系（`8 / 16 / 24 / 32 / 48 / 64 dp`）；`4dp` 仅允许用于图标与文字间的紧凑微调，`12dp` 仅用于卡片内次级元素间距。
2. **舒适阅读宽度上限**：在 Medium 及以上窗口中，对话时间线正文区约束最大宽度（如 `680dp`），表单区约束最大宽度（如 `480dp`），避免超长单行文本降低扫描效率。
3. **OAI 工作台与设置自适应规则**：
   - 窗口 `< 840dp`（Compact / Medium）：工作台采用 `ModalNavigationDrawer`（抽屉宽 `320dp`）+ 主会话区；设置页采用单栈列表 → 详情推进。
   - 窗口 `≥ 840dp`（Expanded 及以上）：工作台切换为固定常驻侧栏（`320dp`）+ 右侧主会话区；设置中心采用 List-Detail 双窗格布局。

### 5.3 全面屏与安全区（Edge-to-Edge & WindowInsets）

Android 15（API 35）起强制 Edge-to-Edge，所有 Activity 均须遵循以下规范：

1. **透明系统栏与对比度自适应**：使用 `enableEdgeToEdge(statusBarStyle, navigationBarStyle)` 将状态栏与导航栏设为透明，图标明暗**严格跟随应用内当前深浅主题**（而非仅看系统夜间模式），避免「系统浅色 + 应用强切深色」时状态栏文字不可见。
2. **单一消费 WindowInsets**：
   - `TopAppBar` 消费顶部 `statusBars`（及横屏 `displayCutout`）；
   - 底部输入坞/操作栏消费底部 `navigationBars` 与 `ime`（`imePadding()` + `navigationBarsPadding()` 组合去重），确保键盘弹出时输入栏平滑紧贴键盘顶部，收起时避开手势横条；
   - 列表滚动容器优先将底部导航栏安全区设为 `contentPadding`，使内容可自然滚动至透明导航栏后方而不被截断；
   - 严禁父子容器重复叠加同一条安全区造成大块空白，也严禁使用 `WindowInsets(0, 0, 0, 0)` 导致内容压进状态栏或手势条。

---

## 6. OAI 核心界面组件映射与安全边界

本节将上述 MD3 官方规范落实到 OAI 项目的 7 个核心界面表面，并明确产品安全与真实性约束：

| OAI 界面表面 | 所属模块 | 推荐 MD3 组件与表面层级 | 色彩、动效与交互要点 |
| --- | --- | --- | --- |
| **1. 对话工作台（Workbench）** | `:app`、`:conversation-ui` | `Scaffold` + `TopAppBar`（`surface` / 滚动升 `surfaceContainer`）+ `ModalNavigationDrawer` / 常驻侧栏（`surfaceContainerLow`）+ 底部输入坞（`surfaceContainerHigh`） | 顶栏收敛高频动作避免截断标题；用户气泡与助手正文通过容器层次与左侧引导线清晰区分；流式生成不强抢用户滚动位置；发送/停止按钮使用同一容器平滑过渡 |
| **2. 思考过程与工具调用卡** | `:conversation-ui` | `OutlinedCard` 或 `surfaceContainerLow` 紧凑卡（`Medium` `12dp` 圆角） | 默认采用无过冲 `standard.default.spatial` 展开/收起；状态由 `LocalAppStateColors` + 文字 + 图标共同表达，严禁仅靠颜色区分成败 |
| **3. 默认数字助理与圈选层** | `:assistant-holder`、`:conversation-ui` | 悬浮输入栏 ↔ 停靠球（`surfaceContainerHigh` + Level 3 海拔、`ExtraLarge`/`Full` 形状）+ 全屏圈选覆层 | 展开/停靠使用 Container Transform 保持内容挂载；停靠球支持 1:1 拖拽、速度投射与非手势关闭入口；无真实外部截图时明确展示不可用态，松手不自动越权提交 |
| **4. 设置中心与本机能力管理** | `:app` | 分组卡片（`surfaceContainerLow` / `surfaceContainer`）+ 标准 `ListItem` + `Switch` + `SegmentedButton` / `RadioButton` | Compact 单栏 Shared Axis X 推进，Expanded 双栏 List-Detail；能力独立开关与三级授权模式（每次手动 / 仅允许只读 / 自动允许）状态文案清晰无歧义 |
| **5. 气泡会话窗口（Bubble）** | `:app` | 精简 `Scaffold` + 透明系统栏（复用 `applySystemBarContrast`） | 与主应用共享完全一致的 `OpenAndroidIntelligenceTheme`、`LocalMotionPolicy` 与安全区策略 |
| **6. 本机敏感确认与无障碍急停浮窗** | `:app`、`:screen-control` | `AlertDialog`（应用内）或 `TYPE_ACCESSIBILITY_OVERLAY` 无焦点浮窗（`surfaceContainerHigh` + Level 5 + `error` 急停按钮） | 强制清晰展示调用方、动作内容与影响范围；急停按钮热区 `≥ 48dp × 48dp` 且使用高对比 `error`/`onError`；禁止点击穿透确认浮窗；锁屏期间不拉起界面 |
| **7. 插件声明式 UI** | `:plugin-ui` | 严格受限于 MD3 令牌渲染的 `Surface` / `Card` / `Text` / `Button` | 仅允许映射 `MaterialTheme.colorScheme` 与 `typography`，禁止插件透传任意硬编码色值或自定义不受控动画 |

**跨界面通用铁律**：
1. **文案国际化**：所有面向用户的 UI 文案与无障碍 `contentDescription` 必须在 `apps/android/ui-resources/src/main/res/values/strings_*.xml` 中集中维护，严禁在 Kotlin 代码中硬编码中文字符串（受 `tools/check_hardcoded_chinese.py` 与 `tools/check_string_resources_integrity.py` 门禁检查）。
2. **状态诚实与禁止伪造**：所有异步读区必须明确区分「未请求 / 加载中 / 空状态 / 失败 / 离线 / 成功」；网络或通道失败绝不可静默伪装为空列表；未接通真实数据源的能力须明确展示未连接或不可用状态。

---

## 7. 代码映射、自动化守卫与现状对齐清单

### 7.1 现行设计令牌与守卫测试映射

| 规范维度 | 现行代码入口（相对路径） | 自动化守卫测试 |
| --- | --- | --- |
| 主题入口与动态取色 | [Theme.kt](apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/Theme.kt) | [ThemeContrastTest.kt](apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/theme/ThemeContrastTest.kt) |
| 预设色板与色调推导 | [AppPalettes.kt](apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppPalettes.kt) | [ThemeContrastTest.kt](apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/theme/ThemeContrastTest.kt)、[MaterialTokenGuardTest.kt](apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/MaterialTokenGuardTest.kt) |
| 扩展状态色 | [AppStateColors.kt](apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppStateColors.kt) | [ThemeContrastTest.kt](apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/theme/ThemeContrastTest.kt) |
| 排版与等宽样式 | [AppTypography.kt](apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/AppTypography.kt) | — |
| 圆角、间距与断点 | [DesignTokens.kt](apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/DesignTokens.kt) | [MaterialTokenGuardTest.kt](apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/MaterialTokenGuardTest.kt) |
| 动效时长、曲线与弹簧 | [MotionSpecs.kt](apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/MotionSpecs.kt)、[AppTransitions.kt](apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/motion/AppTransitions.kt) | [MotionPhysicsTest.kt](apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/motion/MotionPhysicsTest.kt)、[AppTransitionsTest.kt](apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/motion/AppTransitionsTest.kt) |
| 减少动态策略与触觉偏好 | [MotionPolicy.kt](apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/ui/design/MotionPolicy.kt)、[StreamingFeedback.kt](apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/theme/StreamingFeedback.kt)、[AppearancePreferences.kt](apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/AppearancePreferences.kt) | — |
| 交互修饰符与系统栏 | [Interaction.kt](apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/Interaction.kt)、[SystemBars.kt](apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SystemBars.kt) | — |

### 7.2 现状核对与后续源码对齐清单（Audit Gap）

对照本文档所确立的 Google MD3 官方规范，现行代码存在以下小范围历史偏移，后续进行 UI 迭代或重构时**须按本规范逐步收口**：

| 编号 | 现行实现 | Google MD3 官方规范 | 建议对齐方向 |
| :---: | --- | --- | --- |
| G1 | `MotionSpecs.EmphasizedEasing` 使用 `CubicBezierEasing(0.2f, 0f, 0f, 1f)`（实为 `Standard` 曲线） | 两段式 `PathInterpolator(M 0,0 C 0.05,0, 0.133333,0.06, 0.166666,0.4 C 0.208333,0.82, 0.25,1, 1,1)` | 补齐真实 Emphasized 曲线或直接迁移至 §2.1 官方 Spring 令牌 |
| G2 | `MotionSpecs.EmphasizedAccelerateEasing` 使用 `(0.3f, 0f, 1.0f, 0f)` | `CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)` | 修正控制点为官方 `(0.3f, 0.0f, 0.8f, 0.15f)` |
| G3 | `MotionSpecs` 自定义了 `SpatialSpring (0.9/380)`、`MorphSpring (1.0/520)`、`SnappySpring (0.85/900)` 等历史参数 | Standard (`0.9 / 1400, 700, 300` & `1.0 / 3800, 1600, 800`) 与 Expressive (`0.6/800, 0.8/380, 0.8/200`) | 按 §2.1 收敛至官方 Standard/Expressive 弹簧参数，升级 Material 3 `1.4+` 后切换为原生 `MotionScheme` |
| G4 | `AppRadius` 中 `Medium = 16dp`、`Large = 24dp`，并额外保留 `Compact = 12dp`、`Bubble = 20dp` | 官方 `Shapes`：`ExtraSmall = 4dp`、`Small = 8dp`、`Medium = 12dp`、`Large = 16dp`、`ExtraLarge = 28dp` | `AppShapes` 回归官方 `4 / 8 / 12 / 16 / 28dp` 映射，业务特化圆角（如气泡 `20dp`）仅作为语义别名而非改写 `MaterialTheme.shapes` 基准槽位 |
| G5 | `AppPalettes` 浅色 `surface = tone 98.5`、`surfaceBright = tone 100`，且尚未显式填充 `primaryFixed*` 等 Fixed 角色 | 浅色 `surface = tone 98`、`surfaceBright = tone 98`；完整支持 `*Fixed` / `*FixedDim` / `on*Fixed*` 角色 | 同步校正浅色 `surface`/`surfaceBright` 至 `tone 98`，并补全 Fixed 角色及 `ThemeContrastTest` 校验 |
| G6 | `AppTypography` 对 `headlineLarge/Medium/Small` 与 `titleLarge` 做了字号微调与 `SemiBold` 统一 | 官方基准 `headlineLarge 32/40`、`headlineMedium 28/36`、`headlineSmall 24/32`、`titleLarge 22/28`（均 `Regular`） | 保留中文长文行高/字距可读性优化的同时，新页面优先回归 MD3 标准字阶层次，避免不同页面标题尺度分裂 |

---

## 8. Google 官方参考文档

- **Material Design 3 总览**：https://m3.material.io/
- **色彩系统与角色规范（Color System & Roles）**：https://m3.material.io/styles/color/roles
- **动态取色与 HCT（Dynamic Color）**：https://m3.material.io/styles/color/dynamic/choosing-a-source
- **动效物理系统与弹簧令牌（Motion Physics & Specs）**：https://m3.material.io/styles/motion/overview/specs
- **缓动与时长刻度（Easing & Duration）**：https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
- **转场模式（Transitions）**：https://m3.material.io/styles/motion/transitions/transition-patterns
- **交互状态与状态层（Interaction States）**：https://m3.material.io/foundations/interaction/states/overview
- **手势与预测性返回（Gestures & Predictive Back）**：https://m3.material.io/foundations/interaction/gestures
- **排版、形状与海拔（Typography / Shape / Elevation）**：https://m3.material.io/styles/typography/type-scale-tokens · https://m3.material.io/styles/shape/shape-scale-tokens · https://m3.material.io/styles/elevation/tokens
- **自适应窗口断点与布局（Applying Layout & Window Size Classes）**：https://m3.material.io/foundations/layout/applying-layout/window-size-classes
- **Jetpack Compose Material 3 官方实现指南**：https://developer.android.com/develop/ui/compose/designsystems/material3
- **Android 全面屏与系统栏指南（Edge-to-Edge）**：https://developer.android.com/develop/ui/compose/layouts/insets
