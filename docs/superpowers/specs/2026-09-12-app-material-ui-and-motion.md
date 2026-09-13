# App 原生 Material 3 界面与交互动效

日期：2026-09-12。实施基线：`b34ea107befd1fa1f3958905279cb30de2a878f5`。

## 需求与证据边界

本次用户要求制作可运行的 App 界面与动画，所有业务事实对齐真实后端。提示文档中“只输出设计文档”的指令仅属于参考材料，不替代本次实施授权。原工作目录有四个已暂存文件，本次使用隔离副本实施，交付时仅带回本次文件，保留原暂存区。

实际视觉目录为 `fronted-preview/`。已在实现前阅读 HTML 源码并查看随附 Login、Workbench JPG；JPG 是历史静态参考，不是本次运行截图。HTML 含外部资源及演示脚本，不在已有登录浏览器中执行。五份 HTML 的 SHA-256：

| 页面 | SHA-256 |
|---|---|
| Assistant Session | `64ccb45a109adb96934de52484fef494f989316d1a4b79f49443381ca543c8fb` |
| Login | `78c4ae8bc8b76282d121b277d311b18475c50d1d52e6b6f260aef36041fafda3` |
| Platform Settings | `d7505d09b54b4f176a6f8787652ce5f672ecafe945bebf5f433fe0f38438c53f` |
| Thread Drawer | `1070718f9596bbdc4c4c56e2f451869306d939495869623f22b41f9b85e48922` |
| Workbench | `702c26b5166c5ab047c6e2c6c8b63a826c43cb497561dd6aa13c1e09e09c835f` |

总规格、`CONTEXT.md`、ADR 和 `docs/contracts/gateway-protocol-v2.md` 约束身份、权限和数据。不改变协议或新增服务端事实。栈保持 AGP 8.9.2、Kotlin 2.1.20、minSdk 34、compile/targetSdk 35。默认助手系统角色与屏幕截图权限不是界面控件能够创造的能力。

## 唯一视觉系统

视觉系统采用轻浅苔绿背景、深墨绿夜间背景、茶金装饰信号点。品牌方案默认启用，跟随系统深浅模式。已有动态取色参数保留为显式可选项，使用 Android 官方 `dynamicLightColorScheme` / `dynamicDarkColorScheme`；不自行计算壁纸颜色。当前默认动态取色与旧琥珀深色不再覆盖品牌默认值。

所有页面使用 `MaterialTheme.colorScheme`、`typography`、`shapes`，原始色值只保存在主题文件。surface 容器层次替代厚阴影、玻璃模糊；错误和连接状态同时有文字，颜色不单独表达权限。

| 角色 | 浅色 | 深色 |
|---|---|---|
| background / surface | `#F1F4F1` | `#101613` |
| surfaceContainerLow | `#EBF0EB` | `#151D19` |
| surfaceContainer | `#E3ECE7` | `#19231F` |
| surfaceContainerHigh | `#D5E2DC` | `#26332E` |
| primary | `#2F645C` | `#88BAAE` |
| onSurface | `#1C2724` | `#E8EFEB` |
| onSurfaceVariant | `#4D5D57` | `#AFBDB6` |
| tertiary 装饰来源 | `#B8874A` | `#D3A86F` |

正文使用系统 SansSerif，让 Android 选择中文字体回退；命令、地址等标识采用 Monospace。保留 Material 3 全套字号层级与 sp 缩放，不以固定高度裁剪多行内容。正文主要使用 bodyLarge，说明使用 bodyMedium，状态使用 labelMedium，标题使用 titleLarge/headlineMedium。

布局使用 4dp 基础细分及 8dp 主网格，档位 4/8/12/16/24/32/48/64。触摸目标至少 48dp。M3 shape 档位 4/8/12/16/28dp。消息气泡只对方向尾角使用小档位。紧凑窗口为抽屉 + 对话，宽窗按当前可用窗口宽度展开线程列表，正文约束最大阅读宽度；使用 safeDrawing/IME Insets，不绘制假状态栏或键盘。

## 设计转译与数据所有者

| HTML 证据 | 产品意图 | 原生归属 | 决策 | 状态与真实来源 |
|---|---|---|---|---|
| Login 卡片、绿白品牌 | 清晰连接到自己的 Agent | app/GatewayLoginScreen | Translate | GatewayRuntime 的协商/认证/恢复/失败；密码认证与刷新接口 |
| Bridge/ADB/扫码标签 | 接入方式 | Gateway 接入表单 | Replace | HTTPS 地址、账号、密码；未接入的邀请配对不假装可用 |
| Workbench 仪表盘、假运行指标 | 当前任务上下文 | WorkbenchScreen | Replace | 对话为主；只展示真实会话、消息、命令与附件，无 CPU/在线/版本假数据 |
| 抽屉选中态 | 在同一 Gateway 内切换线程 | ThreadDrawer | Translate | repository.listConversations / timeline；本地筛选不编造分组 |
| 通知/终端/文件管理顶级入口 | 扩展能力入口 | 平台设置 | Replace | 插件与授权由 Kernel 驱动，保持主导航边界 |
| 附件弹出菜单 | 主动选择内容 | M3 ModalBottomSheet + 系统 Picker | Translate | LocalAttachmentSelection → AttachmentDraftCoordinator → 三步上传 |
| 假进度百分比 | 传输反馈 | 附件列表与编辑器条 | Replace | 九态文本 + 不定进度；接口未提供字节进度时不显示百分数 |
| 模拟逐字回复 | 让回复到达可见 | 时间线 | Replace | SSE 原文直接增长，稳定消息 key，无定时器追加文本 |
| 助理展开/停靠 | 同一内容连续变化 | AssistantSurface | Translate | 状态由调用方提供、内容保持挂载；不创造系统角色或截图权限 |
| 圈选画框 | 确认选区 | ScreenSelectionOverlay | Translate/Block | 必须有外部真实截图，否则明确不可用；确认后才能回调 |

所有读区分别渲染未请求、加载、空、失败、成功。HTTP/SSE 失败不能变成空列表。命令菜单来自 `/commands`，选择只填入原始文本；`/new` 不在 UI 解释或授权。附件未核验、结果未知、取消待确认须保持不同表现。发送、生成完成及取消以 repository 返回和验证后的事件为准。历史媒体若缺少读取端口，明确未接入，不填占位图片。

## 动效契约

唯一实现入口为 `conversation/motion/MotionSpecs.kt`。原 `ui/design/MotionPolicy.kt` 只保留策略与系统减少动态观察，不再另定义同名令牌。

| ID | 触发与对象 | 实现参数 | 中断及减少动态 |
|---|---|---|---|
| M01 | 登录阶段、加载/失败提示 | 150ms alpha；不重建输入表单 | 新阶段可覆盖旧阶段；系统 0 倍时即时完成 |
| M02 | 页面导航 | M3 层级转场，300ms 标准缓动 `(0.2,0,0,1)` | 使用平台返回栈；减少动态只淡化 |
| M03 | 抽屉、附件/设置底部弹层 | 标准 M3 Drawer/Sheet | 平台手势、返回、焦点与关闭行为 |
| M04 | 编辑器焦点、附件条高度变化 | 无过冲 spring，damping=1，stiffness=400 | 从当前几何值继续；减少动态直接改变结构 |
| M05 | 发送/停止图标 | 同一按钮内 150ms 交叉淡化 | 未确认取消保持待确认文字，按钮内容不创造终态 |
| M06 | 命令筛选与批次条 | 150ms 淡入淡出、固定最大可用高度 | 不对输入节流；无循环呼吸 |
| M07 | 消息/真实流式增长 | 稳定 key，正文直接渲染 | 用户离开底部时保留阅读位置，显式“回到最新” |
| M08 | 附件准备/传输/核验/失败/未知 | M3 不定进度 + 状态图标与文字 | 不显示虚构百分数，不把未知状态变为成功 |
| M09 | 助理栏与球 | 同一 Surface bounds/radius 插值，内容始终挂载 | 可逆、可打断；减少动态结构直接切换 |
| M10 | 停靠球拖动/释放 | 1:1 跟手 + VelocityTracker，spring damping=0.85/stiffness=400 | 接续释放速度，限制到安全区域；提供非手势移动/关闭 |
| M11 | 圈选 | 跟手绘制；显式确认/取消 | 无截图禁用；不以松手冒充提交 |

后台线程事件不触发当前线程动效、不抢滚动；动画期间仍可输入。60fps/高刷新率和无障碍实测须使用运行设备，本次源码和 JVM 检查不能代替设备证据。

## 实施与验收顺序

1. 锁定输入、当前状态与上述转译表；执行原基线构建/单测。
2. 统一主题/尺寸/动效策略，修复错误与空状态。
3. 改造登录、对话/线程、附件、平台设置；对照真实端口修复缺失接线。
4. 完成助理几何连续组件、圈选可用性与确认交互，明确系统能力边界。
5. 执行模块单元测试、APK 构建、静态真实性/导航检查和独立审查。
6. 若设备/模拟器可用执行原生 UI 与后端闭环；否则记录 BLOCKED 及准确边界。

开始时 `adb devices -l` 为空，项目 SDK 无 emulator/system-images。首次基线构建停在沙箱外 debug keystore 目录不可写；将 Android 用户目录改到可写临时目录后继续。验收结果将另存计划目录，不在此预先声明通过。

## 官方实现依据

- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)：使用角色化主题和平台 Material 组件。
- [Customize animations](https://developer.android.com/develop/ui/compose/animation/customize)：spring 支持目标变化时的速度连续性。
- [Support different display sizes](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes)：以窗口可用空间而不是机型判断布局。
