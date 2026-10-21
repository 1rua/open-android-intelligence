---
status: accepted
date: 2026-10-21
title: App 界面与动效重构：品牌默认色、M3 层级、连续动效
refines:
  - 2026-08-29-android-conversation-assistant-ui-design.md
  - 2026-09-12-app-material-ui-and-motion.md
  - 2026-09-18-conversation-session-ui-completion-spec.md
authorization: 2026-10-21 用户直接授权分支 `ui-motion-refactor-20261021` 上重构 App 前端 UI 与动画
---

# App 界面与动效重构

## 1. 范围与硬约束

- **只改前端**：`apps/android/app` 的界面组合根（Activity / 根表面 / 各 Screen 的 Composable）与
  `apps/android/conversation-ui` 的界面层、主题、动效令牌。
- **不改**：Gateway 协议与 Schema、`gateway-contract/`、`*-ports` 契约、领域模型、插件运行时、
  WASM 宿主、通知/短信/通话采集逻辑、任何后端代码。发现契约缺口只记录，不实现。
- **保持 Material 3**：优先使用 M3 标准组件与角色化令牌；自定义组件只在标准组件表达不了时出现，
  并在代码注释里说明原因。
- **不新增第三方 UI 依赖**：只使用已有的 Compose BOM 2024.12.01（Compose 1.7.6 / Material3 1.3.1）。
- **不伪造状态与数据**：所有内容仍来自既有真实端口；空态、加载、失败、离线必须可区分。
- **守卫测试继续有效**：`MaterialTokenGuardTest`（app 模块禁止硬编码色值与非法圆角）、
  `ThemeContrastTest`、`AppTransitionsTest`、`MotionPhysicsTest`、工作台布局与输入回归测试。

## 2. 现状诊断（设备实拍 + 控件树证据）

在 Pixel 尺寸模拟器（1080×2400 / 420dpi，Android 14）上对当前构建取证：

| # | 现象 | 证据 | 根因 |
|---|---|---|---|
| 1 | 顶栏压进状态栏，标题与图标与系统时钟/电池图标重叠 | 控件树：标题 `bounds=[258,31][428,94]`、状态栏图标区域同高 | `WorkbenchScreen` 的 `TopAppBar(windowInsets = WindowInsets(0,0,0,0))` |
| 2 | 顶栏左侧 6 个动作图标把标题挤到 170px 宽，标题被截断成 `Use the…` | 控件树：导航图标 126px + 5×126px 动作图标 | 动作没有收敛，全部直接挂在 `actions` 里 |
| 3 | 顶栏按钮点不到（点击无响应） | 点击 `content-desc=设置` 的 84dp 中心点无反应；点击内容区正常 | 按钮落在系统状态栏触摸区 |
| 4 | 生成状态文字落进手势导航条区域 | 控件树：`正在接收回复 bounds=[63,2332][239,2374]`，窗口高 2400 | 输入坞未应用导航栏 inset |
| 5 | 全 App 只有一套薰衣草紫，用户没有任何配色选择 | 实拍截图 | 只有动态取色一条路径，缺少可选预设；本次保留动态取色为默认，并补 5 套预设色板 |
| 6 | 用户气泡与页面底色几乎同一个紫，方向感弱 | 实拍截图 | 用 `secondaryContainer` 承载气泡；M3 语义上它表示"第二强调面"，不是"用户说的内容" |
| 7 | 抽屉头部大面积紫渐变，白字压在浅紫上对比度不足 | 代码 | `Brush.linearGradient(primary, primaryContainer, secondaryContainer)` + `onPrimary` 文字 |
| 8 | 时间线没有进入节奏：消息一次性出现，滚动跟随时是硬跳 | 代码 | 没有 `animateItem`；跟随用 `scrollToItem` 每帧硬定位 |
| 9 | 发送/停止只做图标交叉淡化，按钮本身没有形变 | 代码 | `Crossfade` 包 Icon，容器尺寸不变 |
| 10 | 深浅主题硬切，切换瞬间白闪 | 代码 | `MaterialTheme` 直接换 scheme |

> 截图只作为诊断证据保存在本任务的执行记录中，不新增伪造图形资源到仓库。

## 3. 设计目标

1. **安静**：底色是低饱和苔绿纸面，正文是唯一的主角；装饰只有一条缝线。
2. **可信**：连接、生成、附件、失败都有明确文字；颜色永远不是唯一信号。
3. **连续**：页面、容器、按钮的形变使用同一套弹簧与曲线；从不重建子树。
4. **可读**：正文 16sp/1.6、辅助 12–14sp；所有尺寸跟随系统字号缩放。
5. **有物理感**：按压即反馈（0.97 / 90ms），手势释放继承速度，边缘有阻力。

## 4. 视觉系统（实现即令牌）

### 4.1 颜色

**默认：系统动态取色（Material You / Monet）。** Android 12+ 一律由壁纸推导配色，代码只调用
官方 `dynamicLightColorScheme` / `dynamicDarkColorScheme`，不自行计算壁纸色。动态取色可以在
「外观与动效」里关闭；关闭后使用下面 5 套预设主题色之一。

**预设主题色（关闭动态取色时可选）。** 每套预设不是手写的几十个散值，而是同一个种子色按
Material 3 的色调映射推导出的完整角色表（见 `theme/AppPalettes.kt`）：

| 预设 | 种子（CIE LCh） | 气质 |
|---|---|---|
| 靛蓝 INDIGO | h=265° · C=52 | 沉稳蓝紫，接近 M3 基准蓝，长时间阅读最稳 |
| 青碧 AZURE | h=205° · C=44 | 清透青蓝，界面更轻盈 |
| 紫罗兰 VIOLET | h=312° · C=48 | 鲜明紫罗兰，强调动作更醒目 |
| 暖橙 CORAL | h=40° · C=58 | 温暖琥珀橙，消息与媒体氛围更亲切 |
| 石墨 GRAPHITE | h=250° · C=6 | 近乎中性的单色，最安静克制 |

色调映射（浅色 / 深色）：

| 角色 | 浅色 tone | 深色 tone |
|---|---|---|
| primary | 40 | 80 |
| onPrimary | 100 | 20 |
| primaryContainer / onPrimaryContainer | 90 / 10 | 30 / 90 |
| secondary（低色度）/ container | 40 / 90 | 80 / 30 |
| tertiary（色相错开 60°）/ container | 40 / 90 | 80 / 30 |
| surface / background | 98.5 | 6 |
| surfaceContainerLowest→Highest | 100 / 96 / 94 / 92 / 90 | 4 / 10 / 12 / 17 / 22 |
| onSurface / onSurfaceVariant | 10 / 30 | 90 / 80 |
| outline / outlineVariant | 50 / 80 | 60 / 30 |

状态色（`LocalAppStateColors`，M3 角色表没有 success / warning）：

| 角色 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| success / container | `#2E6B4F` / `#D3E7D9` | `#8FD3AE` / `#1E3A2C` | 完成 |
| warning / container | `#8A5A16` / `#F5E3C3` | `#E7BE7E` / `#453314` | 等待 / 待确认 / 结果未知 |
| error | 随配色方案（统一 tone 40 / 80） | | 失败、阻断 |

- **不再有「墨绿品牌色」**：原 `BrandLightColorScheme` / `BrandDarkColorScheme` 与
  `AppColors` 已删除，避免"品牌色 + 动态取色"两套身份并存。
- 状态色**不跟随主题预设**：换主题不应该改变"成功是绿色、等待是琥珀色"的约定。
- 所有色值只允许出现在 `theme/AppPalettes.kt`、`theme/AppStateColors.kt`、`theme/Theme.kt`；
  其余界面一律通过 `MaterialTheme.colorScheme` / `LocalAppStateColors` 取色。
- 对比度由 `ThemeContrastTest` 对**每一套预设**逐一断言（正文与辅助文字在所有 tonal 面上
  ≥ 4.5:1），并断言生成色的感知明度落在 M3 tone 档位上。

### 4.2 排版（`AppTypography`）

| 名称 | 规格 | 用途 |
|---|---|---|
| headlineMedium | 24/32 · 600 | 欢迎页、设置分组标题 |
| titleLarge | 20/28 · 600 | 页面标题、弹层标题 |
| titleMedium | 16/24 · 600 | 卡片标题、顶栏主标题 |
| bodyLarge | 16/26 · 400 | 消息正文、表单正文 |
| bodyMedium | 14/22 · 400 | 说明、列表副标题 |
| bodySmall | 12/18 · 400 | 辅助说明 |
| labelLarge | 14/20 · 500 | 按钮文字 |
| labelMedium | 12/16 · 500 | 状态、分组标签 |
| labelSmall | 11/16 · 500 | 时间戳、极小元数据 |

- 不指定 FontFamily，交给系统做中文字体回退；只有时间戳、字节数、错误码用
  `AppTextStyles.Mono` / `MonoSmall`（等宽 11–12sp）。
- 所有字号使用 `sp`，禁止固定高度裁切多行文本。

### 4.3 形状与间距

- 圆角：4 / 8 / 12 / 16 / 20 / 24 / 28（`AppRadius`）。消息气泡 20dp，尾部方向角逐角 4dp；
  卡片 16dp；设置分组 24dp；底部弹层沿用 M3 默认。
- 间距：4dp 基础，主网格 8dp，档位 4/8/12/16/20/24/32。页面横向 16dp（紧凑）→ 20dp（宽屏）。
- 触摸目标 ≥ 48dp：输入坞图标按钮视觉 44dp、触摸 48dp；发送按钮 48dp。

### 4.4 层级与材质

- 用 **tonal surface 层级**表达高度，不用厚阴影：页面 `surface` → 卡片 `surfaceContainer` →
  浮层 `surfaceContainerHigh` → 选中 `surfaceContainerHighest`。
- 不使用大面积模糊/玻璃；不允许渐变头部（抽屉改为 tonal 头部 + 缝线）。
- 顶栏与输入坞使用同色 `surface` 并与内容之间用 1dp `outlineVariant` 分隔（滚动时显示）。
- 品牌签名只有**信号缝线**：一段 `primary` 竖线 + 一个 `tertiary` 圆点。
  助手正文左侧引导线由内容高度驱动（`Modifier.stitchGuide`），不再每块各画一段。

### 4.5 图标

- 统一 `material-icons-extended`，光学尺寸 20–24dp（`Dimensions.Icon` / `SmallIcon`）。
- 每个图标按钮必须有 `contentDescription`；纯装饰图标为 `null` 并配文字说明。
- 动作图标一律收敛：顶栏最多 2 个直接动作 + 1 个溢出菜单。

## 5. 页面改造清单

### 5.1 工作台（`WorkbenchScreen`）

- 顶栏：正确应用 `TopAppBarDefaults.windowInsets`（状态栏 + 横屏侧边）。
  - 左侧：`navigationIcon`（紧凑宽度）或宽屏隐藏。
  - 标题：两行 —— 线程标题（`titleMedium`，最多 1 行省略）+ 上下文行
    （Gateway 主机名 · 连接状态，`labelMedium`，11sp，等宽只用于主机名）。
  - 动作：`新建对话`（主要动作，FilledIconButton）+ 溢出菜单（重命名、附件库、
    浮动助理、设置）。设置仍保留在抽屉里作为常驻入口。
- 时间线：
  - 内容边距 16dp，消息间距 20dp，顶部 8dp；到底部自动留出输入坞高度。
  - 用户气泡：`surfaceContainerHigh` + `onSurface`，最大宽度 86%，尾部 4dp 圆角。
  - 助手正文：左侧连续缝线 + 无气泡正文；下方元数据行（时间 · 状态）统一
    `onSurfaceVariant` + `AppTextStyles.MonoSmall`，状态用文字而非变色圆点。
  - 消息进入：`Modifier.animateItem` 的淡入 + 位移；历史消息不重放。
  - 流式增长：不打字机、不逐 token 动画；用户停在底部时用锚定滚动（见 M12）。
  - 思考中：M3 打字点（3 点错峰），减少动态时静态。
- 输入坞：
  - `navigationBarsPadding()` + `imePadding()`，生成状态文字放在输入坞内部左侧，
    不再落进手势条。
  - 附件条、输入框、语音、发送/停止：容器 `surfaceContainer`，圆角 20dp；
    发送/停止按钮 48dp，图标与容器一起做形变（M14）。
  - 生成状态行：`labelMedium`，等待/失败用 `warning`/`error` 色 + 文案。
- 空态：品牌缝线 + 一句标题 + 一句说明；有真实命令目录时才展示建议 chips。
- Snackbar：底部留出输入坞高度，圆角 12dp。

### 5.2 线程抽屉（`ThreadDrawer` / `ConversationBrowser`）

- 去掉紫渐变头部，改为 `surfaceContainerLow` + 顶部缝线；用户名首字母头像用
  `primaryContainer`/`onPrimaryContainer`。
- 宽度 320dp（宽屏双栏时也使用同一宽度）；安全区：顶部 `statusBarsPadding`，
  底部 `navigationBarsPadding`。
- 列表项：48dp 高、选中态 `surfaceContainerHighest` + 左侧 3dp `primary` 指示条；
  标题 1 行省略 + 相对时间（MonoSmall）。
- 底部常驻：附件库、设置、退出登录，与列表之间 1dp 分隔。

### 5.3 设置（`PlatformSettingsBottomSheet` / `SettingsScreen` / 平台设置）

- 呈现：`ModalBottomSheet`（`skipPartiallyExpanded`）—— 与当前一致，补齐顶部把手与
  分组间距；危险操作（退出登录、删除本地数据）使用 `errorContainer` 分组 + 二次确认。
- 分组卡片：`surfaceContainer` + 24dp 圆角；组标题 `labelMedium` + `onSurfaceVariant`。
- 子页面：统一 `TopAppBar`（返回箭头 + 标题）与 `M3 SegmentedButton`（外观模式）。
- 开关使用 M3 `Switch`，切换有 250ms 颜色过渡；禁用项给出原因文字。
- **主题色**：`系统动态取色` 开关（默认开）+ 5 套预设色板的选择行（靛蓝/青碧/紫罗兰/暖橙/石墨）。
  选择预设时自动关闭动态取色；开启动态取色时预设行禁用并说明原因。切换即时生效（M09）。

### 5.4 登录（`GatewayLoginScreen`）

- 单列布局，最大宽度 480dp，垂直居中偏上；品牌缝线 + 字标。
- 阶段横幅：连接中 / 认证中 / 失败，使用 tonal surface + 图标 + 文案，不只用颜色。
- 输入框使用 M3 `OutlinedTextField`，错误通过 `supportingText` 呈现。
- 已保存账号：列表项显示账号 + Gateway 主机名，长按/菜单移除。
- 转场：登录 ↔ 工作台使用 `AppTransitions.enter/exit`（M02），不刷新页面。

### 5.5 助理与圈选（系统数字助理表面 + `ScreenSelectionOverlay`）

助理的产品形态是**系统数字助理**：用户把本应用设为默认数字助理后，用 Assist 手势 /
长按 Home 在**任意 App 之上**唤起浮动会话；App 内的浮动面板与 `action.ASSIST` 兜底页
复用同一套界面与状态，不再有"只能在 App 内使用"的助理。

实现分三层，共用同一套 Compose 界面与设计令牌：

| 入口 | 系统组件 | 进程 | 形态 |
|---|---|---|---|
| 默认助理（主路径） | `VoiceInteractionService` + `VoiceInteractionSessionService` | `:assistant_keeper` / `:assistant_session` | 系统浮层：展开卡片 ↔ 72dp 停靠球，浮在其他 App 之上 |
| ASSIST 兜底 | `AssistantActivity`（`action.ASSIST`） | `:assistant_session` | 半透明浮层 + 系统遮罩，同样浮在当前 App 之上 |
| App 内预览 | `FloatingConversationPanel` | 主进程 | 与本 App 共用同一份真实会话状态 |

声明要点（真机踩过的坑，已修复）：

- `android.voice_interaction` 元数据里的 `sessionService` 与 `recognitionService` 必须是
  **类名**（不是 `pkg/cls` 扁平名）：框架用
  `new ComponentName(serviceInfo.packageName, <value>)` 组装组件，扁平名会得到类名里
  带 `/` 的非法组件。
- `recognitionService` **不能缺**：`VoiceInteractionServiceInfo` 缺它时直接判
  "No recognitionService specified"，应用被判 unqualified（日志
  `has an unqualified voice interaction service`），在「默认数字助理」里看不到。
  本应用不做系统级语音识别（语音在 App 内录音后作为附件发送），该服务实现里
  明确返回「未接入」，不伪造识别结果。
- `VoiceInteractionSessionService` 必须 `android:exported="true"`（系统以不同 UID 绑定），
  仍由 `android.permission.BIND_VOICE_INTERACTION` 收口。
- 助理界面必须跟随用户在 App 里保存的外观偏好（深浅 / 动态取色 / 预设色板 / 减弱动态）：
  助理进程与主进程同应用同数据目录，只读那份 SharedPreferences。
- ASSIST 兜底 Activity 使用 `windowIsTranslucent=true` 的浮层主题：否则窗口不透明，
  即使背景设成透明也看不到下面的 App，只剩黑底。

动效与交互：

- 展开与停靠保持同一容器身份：宽度/高度/位置/圆角插值，内容层始终挂载（M16）。
- 停靠球 48dp（会话窗口内 72dp），点击展开；系统会话窗口不能拖动位置，长按切换左右边缘。
- 圈选：路径跟手，闭合提示 24dp 范围，确认/取消显式；无系统截图时明确不可用。

## 6. 动效编排（新增/重做部分）

| ID | 场景 | 触发 | 参数 | 可中断 | 减少动态 |
|---|---|---|---|---|---|
| M01 | 顶栏滚动收起/展开（内容阴影与分隔线） | 列表滚动 | 150ms `StandardEasing`，alpha + 1dp 分隔线 | 是 | 直接切换 |
| M02 | 登录 ↔ 工作台 | 连接阶段 | 既有 `AppTransitions.enter/exit`（400/200ms） | 是 | 交叉淡化 |
| M03 | 抽屉 / 底部弹层 | 用户手势、返回 | M3 标准 Drawer/Sheet | 是 | 平台处理 |
| M04 | 消息进入（仅新消息） | 列表新增项 | `animateItem`：淡入 + 8dp 上浮，200ms | 是 | 50% 时长无位移 |
| M05 | 流式正文增长 | SSE 增量 | 无动画（文本直接增长），底部锚定滚动（M12） | — | 同左 |
| M06 | 思考点 | 生成中 | 3 点错峰 1400ms 循环（位移 2dp + alpha） | — | 静态点 |
| M07 | 发送 / 停止按钮 | 生成状态变化 | 图标 150ms 交叉淡化 + 容器 `SnappySpring` 尺寸形变 | 是 | 直接切换 |
| M08 | 按压反馈 | 指针按下 | 0.97 缩放 90ms；松手立即回收 | 是 | 只保留 ripple |
| M09 | 主题换色 | 深浅切换 | 逐角色 250ms `StandardEasing` | 是 | 直接切换 |
| M10 | 设置分组进入 | 打开弹层 | 静态呈现，不做错峰（弹层已由 M3 承担主体转场；避免 200% 字号下列表跳动） | — | 同左 |
| M11 | 命令菜单出现/过滤 | 输入 `/` | 150ms 淡入 + 4dp 上浮；过滤即时 | 是 | 直接切换 |
| M12 | 停留在底部时的滚动锚定 | 新内容/键盘 | 仅增量 `scrollBy`，不做整段跳转 | — | 同左 |
| M13 | 「回到最新」出现 | 用户上滑 | 150ms 淡入 + 缩放 0.9→1 | 是 | 淡化 |
| M14 | 附件条高度变化 | 附件增删 | `MorphSpring`（临界阻尼） | 是 | 直接切换 |
| M15 | Snackbar | 提示 | M3 默认 + 底部避让输入坞 | 是 | 平台处理 |
| M16 | 助理栏 ↔ 停靠球 | 展开/收起 | 容器插值 `MorphSpring`，内容错峰 120–300ms | 是（反向） | 交叉淡化 |
| M17 | 停靠球拖动/吸附 | 拖动释放 | 1:1 + `VelocityTracker` + `MomentumSpring` + 边缘阻力 | 是 | 直接吸附 |
| M18 | 圈选路径 | 手指移动 | 1:1 跟手，闭合提示 24dp | 是 | 同左 |
| M19 | 列表错峰（抽屉/设置） | 首次进入 | 40ms/项，上限 240ms | 是 | 无错峰 |
| M20 | 页面内容切换（设置子页） | 点击 | `AppTransitions.navEnter/navExit` | 是 | 交叉淡化 |

**禁止出现动效的地方**：流式回复逐 token 动画、后台会话事件抢当前阅读位置、循环呼吸/发光、
加载时整屏闪烁、把"等待"伪装成"完成"的提前动效。

## 7. 无障碍与适配

- 对比度：正文与辅助文字在所有 tonal 面上 ≥ 4.5:1（`ThemeContrastTest` 扩展覆盖状态色）。
- 状态不只靠颜色：完成/等待/失败均带文字；TalkBack 朗读消息状态与附件阶段。
- 触摸目标 ≥ 48dp；图标按钮有 `contentDescription`；列表项有 `role` 与 `onClickLabel`。
- 系统字号 200% 时布局不裁切：不用固定高度的文本容器。
- 窗口宽度：紧凑（< 600dp）单栏 + 抽屉；中等/展开为双栏；横屏处理左右安全区。
- 减少动态：全部 M04–M20 有降级分支，功能语义不变。

## 8. 验收

1. `:conversation-ui:compileDebugKotlin`、`:app:compileFullDebugKotlin`、`:app:assembleFullDebug` 通过。
2. `:conversation-ui:testDebugUnitTest` 全绿；app 模块单测不新增失败（基线已有 17 个
   环境相关失败：Robolectric `DefaultSdkProvider` 与本地网络用例，记录在案）。
3. 模拟器安装后实拍：动态取色下的浅色工作台、深色工作台、抽屉、设置弹层、附件菜单、空态/失败态，
   并逐一切换 5 套预设确认无低对比度或错色；
   顶栏不再压状态栏、标题不再被截断、底部文字不再落进手势条、品牌色生效。
4. TalkBack 抽查：消息状态、附件阶段、图标按钮可朗读。
5. 独立子 Agent 代码审查，问题修复后复验。

## 9. 不在本次范围

- 协议、Schema、领域契约、插件运行时、通知采集与权限逻辑。
- 新增系统能力（默认助理角色、截图权限、配对接口）。
- 新的第三方 UI/动画库；新增字体资源文件（继续使用系统字体）。
- 生成真机性能报告以外的基准测试工具。
