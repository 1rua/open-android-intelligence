# 提示词：App 端前端设计风格、交互动效与接口对接方案

> 用法：把「需求」以下的内容整段交给执行者（人或 AI）。本文描述项目现状与需求，不规定执行步骤与文档结构，由执行者自行判断。
> 期望产出是一份设计文档，而不是代码。

---

## 一、项目是什么

Open Android Intelligence 是一套让 Android 设备连接**用户自有的** Agent Gateway 的产品：手机端始终是本机数据与设备操作的最终授权者。产品形态由三部分组成——极简的 Android 宿主 App、可安装的设备插件、以及运行在 Agent 宿主里的 Gateway 适配器（目前有 Hermes 的 Python 插件与 OpenClaw 的 TypeScript 插件两个实现，共享协议与一致性向量）。

通信走 Gateway Protocol v2：直接 HTTPS + SSE，基路径 `/open-android-intelligence/v2`。每个已认证请求都带九个单例头（Bearer Authorization 与 `X-Open-Android-Intelligence-` 前缀的 Protocol / Account / Device / Session / Request-Id / Timestamp / Nonce / Signature），变更请求还要带与 Request-Id 绑定的 `Idempotency-Key`；query 参数必须客户端预排序后再签名发送。这些是硬约束，界面设计时不需要重述，但接口对接说明必须与之一致。

Android 端的技术栈是 Kotlin + Jetpack Compose + Material 3（AGP 8.9.2 / Kotlin 2.1.20 / compileSdk 35 / minSdk 34）。产品的主导航向只有三个：账号/Gateway、对话、附件；插件、权限与审计一律放在设置的平台管理界面，不得成为顶级入口。这条边界由代码里的 `CoreNavigation` 与 `ArchitectureBoundaryTest` 守着。

## 二、前端现状

### 2.1 已经写出来的代码（判断"保留 / 改造 / 新建"的事实来源）

- `apps/android/app/` — 宿主组合根：`MainActivity`（登录页与工作台按连接阶段切换）、`GatewayRuntime`（连接生命周期 Disconnected → Negotiating → Authenticating → Connected/Failed）、`GatewayLoginScreen`、`PlatformSettingsScreen` 与 `PlatformSettingsBottomSheet`、`Navigation` 与 `navigation/AppDestination`。
- `apps/android/conversation-ui/` — 界面层：`workbench/`（WorkbenchScreen 的对话/工作流分段、ThreadDrawer、ComposerBar、MessageTimeline、CommandMenu）、`components/`（含 SignalStitch 信号缝线与 StateViews 状态视图）、`state/`（Loadable 五态与 WorkbenchController）、`theme/`（AppColors、Theme）、`motion/MotionSpecs.kt`、`ui/design/MotionPolicy.kt`、`assistant/AssistantView.kt`（停靠球与浮层雏形）、`selection/ScreenSelectionOverlay.kt`（矩形拖拽圈选雏形）。
- `apps/android/conversation-data/` — 领域适配层：`GatewayConversationRepository`、`GatewayEventDecoder`（SSE → 领域事件）、`GatewayCommandCatalogRepository`、`GatewayAttachmentDraftCoordinator`（三步上传状态机）、`GenerationTracker`。
- `apps/android/gateway-client/` — 真实网络层：认证、协商、HTTPS 与 SPKI 固定、签名、SSE、会话与消息、命令目录、附件三步上传。

### 2.2 视觉参考稿及其边界

`fronted-preview/` 下有 5 个自包含 HTML 页面（Login 动画与深色切换、Workbench 工作台、Thread Drawer 线程抽屉、Platform Settings 平台设置、Assistant Session 助手会话），根目录还有 `mobile_motion_preview.html`。它们是**早期 Bridge 时代的视觉原型**，只能作为配色气质、字体策略、形状与间距尺度、信号缝线视觉签名、弹簧与缓动语汇的参考，**不能作为实现依据**。

其中的这些内容属于已废止概念或纯假数据，不得出现在方案里：

- 「Bridge 地址 / 密钥登录 / 扫码登录」——独立 Bridge 的架构已被取代（ADR 0037），v1 到 v2 必须重新配对（ADR 0038）；
- `ADB`、`ws://192.168.1.100:7788`、Pixel 8 Pro、PID 8842、CPU 12%、内存 128MB、电池 87% 之类设备指标——没有任何接口提供；
- 「工作台 / 通知中心 / 文件管理 / 帮助与反馈」这类导航项——越过主导航边界；
- 「端到端加密 / 零保留推理 / 设备独有密钥」之类徽章——无法由运行时证明；
- 打字机式流式回复、假通知列表、假命令目录、假审计日志、假「Agent 运行中/版本号/线程数」。

### 2.3 三处已知的设计不一致（需要方案给出唯一结论）

1. `conversation-ui/theme/Theme.kt` 目前默认 `dynamicColor = true`（API 31+ 走系统 Monet 取色），而 UI 设计规格 §4.2 明确「V1 不应用系统动态取色，只使用固定品牌令牌」。
2. `conversation-ui/theme/AppColors.kt` 的深色调色板是琥珀色 MD3 体系，与设计规格里的墨苔/月松（Canvas `#101613`、Primary `#88BAAE`）不一致。
3. `conversation-ui/motion/MotionSpecs.kt` 与 `conversation-ui/ui/design/MotionPolicy.kt` 里各有一份重复的 `MotionSpecs`。

设计规格给出的固定品牌令牌（浅色 / 深色）是：Canvas `#F1F4F1` / `#101613`，Surface `#E3ECE7` / `#19231F`，SurfaceHigh `#D5E2DC` / `#26332E`，Primary `#2F645C` / `#88BAAE`，Accent `#B8874A` / `#D3A86F`，Text `#1C2724` / `#E8EFEB`，Muted `#63706B` / `#9CABA5`，Error `#A95E50` / `#E1998C`。唯一的主要视觉签名是**信号缝线**（Signal Stitch：一段墨绿短线加一个茶金小点）。

## 三、需求

为 App 端前端制定一套完整的设计风格与动画方案，覆盖全部核心页面，并把每个界面元素对齐到后端真实接口。具体要回答四类问题：

### 3.1 设计风格

方案要给出完整的设计系统，严格遵循 Material Design 3：配色（角色化 ColorScheme，浅色与深色两套，含成功/等待/失败/禁用/选中/离线等状态色，并说明对比度）、字体（排版层级、中英文字体回退、等宽字体的使用边界、跟随系统字号缩放的规则）、组件形态（圆角刻度与组件归属）、间距（基准网格与档位）、响应式栅格（不同窗口尺寸类下的断点、内容最大宽度、边距与安全区/输入法处理）、高程与材质（tonal surface 层级、阴影、何时允许模糊）、图标与品牌签名的使用规则。

设计系统里的每一项都应能落到 Compose 的具体常量和实际色值上，而不是停留在描述。

### 3.2 页面与交互

App 的核心页面包括：登录 / 账号与 Gateway 接入、对话工作台（主页）、线程抽屉与会话列表、附件与本地媒体、平台设置、助理会话（展开栏与停靠球）、屏幕圈选覆盖层。如果你判断还需要别的页面，可以提出，但要说明理由并确认不违反主导航边界。

对每个页面，方案要讲清楚：它的作用和入口出口、布局结构与组件构成、各个组件有哪些状态、空/加载/失败/离线这几种情况分别长什么样、文案怎么写、大屏与小屏如何变化、无障碍如何支持。对已有的实现，明确说哪些保留、哪些改造、哪些重做、哪些删除。

交互部分要覆盖用户真实会走的路径：登录与凭据恢复、新建与切换会话、发送与防抖批次、斜杠命令、附件上传与失败重试、流式回复与滚动跟随、取消生成、离线阅读、设置里的开关与危险操作确认、助理会话的展开与收起、屏幕圈选与不可用状态。

### 3.3 动效

每条动效都要能直接实现，说清楚：它属于哪个页面的哪个组件、什么条件触发、属于哪类动效（共享元素转场、容器形变、弹簧、淡入淡出、交叉淡化、位移、缩放、惯性、边缘阻力、错峰等）、时长或弹簧参数（阻尼比与刚度，以及是否允许过冲）、缓动曲线（M3 的标准曲线或具体贝塞尔值）、能否被中途打断、以及开启"减少动态"后如何降级。建议给每条动效编号，方便后面实现和测试引用；建议整理出统一的时长、缓动、弹簧令牌表，让实现阶段引用令牌而不是就地写数值。

需要特别交代的场景包括（不限于）：页面与层级之间的转场、抽屉开合、分段切换、列表项进入、消息进入、流式回复的自然增长（**不能用打字机动画**）、防抖批次条的封存、附件九个状态（LOCAL_PREPARING → CREATE_PENDING → UPLOADING → VERIFYING → VERIFIED / RETRYABLE_FAILURE / TERMINAL_FAILURE / OUTCOME_UNKNOWN / CANCELLED）各自的动效与失败表现、命令菜单的出现与过滤、发送与停止按钮的形变、Snackbar、登录阶段切换、连接状态指示、设置开关与底部弹层、助理栏与停靠球之间的连续形变（内容层必须始终挂载，不能重建）、圈选手绘与闭合提示、停靠球的拖动/惯性吸附/边缘阻力/关闭区、以及触觉反馈的时机与强度。

同时要写明**不该有动效的地方**：比如流式回复不逐 token 做动画，后台线程收到的事件不抢当前阅读位置、不播放动效，颜色不能作为唯一的状态信号。性能上以 60fps 为最低要求，要能支持高刷新率设备，动画进行中仍可输入，不要在形变过程中重建 Compose 子树或使用等价于 `display:none/block` 的瞬时切换。

### 3.4 接口对接

所有展示的数据都必须来自真实接口，**严禁硬编码任何假数据**——包括假的在线状态、统计数字、版本号、模型名、设备名、时间线和审计记录。每个页面、每个组件的数据来源都要能说清楚：走哪个接口、由哪层领域代码承接、对应界面的哪种状态。空状态和失败状态必须能区分，不能把网络错误伪装成"没有数据"。

截至当前代码，App 端已经实现的接口有：

| 用途 | 接口 |
|---|---|
| 认证前协商 | `POST /negotiate` |
| 密码登录 | `POST /sessions/password` |
| 刷新会话 | `POST /sessions/refresh` |
| 登出 | `DELETE /sessions/current?revokeRefresh=<bool>` |
| 事件流 | `GET /events?cursor=<opaque>`（SSE） |
| 会话列表 / 创建 / 详情 | `GET|POST /conversations`、`GET /conversations/{conversationId}` |
| 消息 | `GET|POST /conversations/{conversationId}/messages`（分页，也支持用 `clientMessageId` 查询结果） |
| 防抖批次 | `POST /conversations/{conversationId}/message-batches`（`joinMode: "newline-v1"`） |
| 取消生成 | `POST /conversations/{conversationId}/generations/{generationId}/cancel` |
| 命令目录 | `GET /commands?languageCode=<bcp47>` |
| 附件三步 | `POST /attachments` → `PUT /attachments/{attachmentId}/content` → `POST /attachments/{attachmentId}/commit` |

协议里已经定义、但 App 端**尚未实现**的有：`POST /pairings/exchange`（邀请配对）、`POST /sessions/device`（设备密钥会话）、`GET|DELETE /attachments/{attachmentId}`、以及历史媒体的按需读取三接口 `GET …/attachments/{attachmentId}/metadata`、`POST …/cache-grant`、`GET …/content`，还有设备请求的 `/device-requests/{requestId}/claim` 与 `/result`。这些可以在方案里描述未来的形态，但目前必须如实呈现为"未接入/不可用"，不能画成能用，也不能用假数据填满。

错误处理要有一套统一策略：把协议错误码与 HTTP 状态映射成具体、可行动的提示，例如凭据失效时引导重新登录、幂等冲突如何处理、SSE 游标过期（`CURSOR_EXPIRED`）如何先重建快照再恢复、生成结果未知（`OUTCOME_UNKNOWN`）时如何暂停同线程后续批次并恢复真实终态。避免"发生未知错误"这类无法行动的文案。

## 四、需要遵守的约束

- 遵循 Material Design 3，优先使用 M3 的标准组件，自定义组件要说明为什么标准组件不满足。
- 不引入新的第三方 UI 依赖；不新增刻度表之外的魔数尺寸与颜色。
- 一切数据来自真实接口；未实现的能力如实标注，不伪造状态。
- 不违反主导航边界（插件、权限、审计、通知中心、文件管理这类不能成为顶级入口）。
- 不沿用已废止的独立 Bridge、ADB、v1 配对、扫码登录等概念。
- 系统动态取色、品牌色变体等有争议的点，先在方案里给出唯一结论再引用。
- 身份、授权、安全事实不能只靠颜色或图形表达，必须有文字与无障碍说明。
- 这是前端设计与对接说明，不要修改协议、Schema 或领域契约；如果发现契约缺口，单独列出来。

## 五、可以参考的权威文档

按下面的顺序查，越靠前越权威：

1. `docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md` — 总规格
2. `docs/contracts/gateway-protocol-v2.md` — 网络协议唯一权威（协商、账号与设备、已认证请求、对话路由、附件三步与状态机、SSE、错误码）
3. `docs/contracts/device-plugin-package-v1.md` — `.alp` 产物契约
4. `docs/adr/` — 架构决定（与本任务强相关的有 0037 独立 Bridge 被取代、0038 v1→v2 重新配对、0039 Tailscale 降级为可选插件，以及 0041–0046 的助理与界面相关决定）
5. `CONTEXT.md` — 领域术语与边界
6. `docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md` — Android 视觉、动效与前端接口的设计规格
7. 上面第二节列出的现有代码

文档请落库到 `docs/superpowers/specs/` 下，命名沿用仓库惯例（日期 + 主题）。写作使用中文，结论要有依据（引用到具体文档章节或代码文件），避免"视情况而定"这类无法执行的表述。
