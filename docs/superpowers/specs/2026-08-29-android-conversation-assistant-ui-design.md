---
status: accepted
date: 2026-08-29
accepted_date: 2026-09-01
authorization: 2026-09-01 用户授权进入实施计划（docs/superpowers/plans/2026-09-01-android-conversation-assistant-ui-full-refactor.md）
refines:
  - 2026-08-24-modular-plugin-architecture.md
related_adrs:
  - ADR-0041
  - ADR-0042
  - ADR-0043
  - ADR-0044
  - ADR-0045
  - ADR-0046
---

# Open Android Intelligence Android 对话、数字助理界面与动效设计

## 1. 权威范围

本文定义 Open Android Intelligence Android 主界面、系统数字助理界面、屏幕选区附件、共享消息编辑器、视觉系统、动效系统，以及这些前端交互所必需的 Gateway/Agent 接口。

本文细化 `2026-08-24-modular-plugin-architecture.md`，不取代其中的账号隔离、Gateway Protocol v2、平台内核、插件运行类型、附件暂存与审计边界。领域术语以根目录 `CONTEXT.md` 为准；难回退决定以 ADR 0041–0046 为准；后续线协议修改必须写回 `docs/contracts/gateway-protocol-v2.md`、严格 Schema 和双宿主一致性向量后才可实现。

当前浏览器视觉伴侣只是设计验证工具，不是 Android 运行证据。本文获书面审阅前不进入 Compose 实现；后续 APK 编译、模拟器截图和静态测试也不能替代默认助理真机交互、跨 App 圈屏和物理端到端证据。

## 2. 目标与体验原则

目标是在 Android 14+ 上形成一套对话优先、Material 3 一致、可被系统数字助理入口唤起、并具有连贯物理动效的 Open Android Intelligence 产品界面。

设计借鉴 ChatGPT 的对话信息架构和 Gemini 的底部助理心智模型，但不复制其品牌色、私有系统动画、Circle to Search 特权或内部组件。产品应让用户感到：

1. **安静**：正文比装饰重要，界面不持续发光、旋转或弹跳。
2. **可信**：当前 Gateway、对话、本地副本、附件状态和失败结果都清楚可见。
3. **连续**：底部助理栏、停靠球和附件预览保持同一物体身份，不凭空切换。
4. **可控**：动画可中断；关闭界面不冒充取消任务；取消必须等真实终态。
5. **适应**：手机、平板、横屏、深浅主题、动态字号、TalkBack 和减少动态共享同一状态模型。

## 3. 产品表面

### 3.1 主 App

主 App 是完整对话与历史入口，固定拥有：

- 活动 Gateway 与账号切换；
- 对话线程列表、新建、切换和重命名；
- 完整聊天时间线；
- 附件历史与本地媒体缓存管理；
- 设置中的平台管理、安全恢复和插件管理。

### 3.2 助理会话界面

助理会话界面由用户选择 Open Android Intelligence 为默认数字助理后，经设备支持的系统 Assist 手势或按键唤起。它是同一个 Android Host 的临时对话表面，不是通用常驻 Overlay。

助理界面默认绑定：

```text
最近明确使用的活动 Gateway
  × 该 Gateway 的最近打开对话线程
  × 当前本地草稿
```

顶部始终显示当前 Gateway/对话，并提供 Gateway 切换和“新建对话”按钮。切换目标不会迁移未确认的屏幕选区、附件快照或插件操作。

### 3.3 助理停靠球

停靠球是同一助理会话的最小化状态。第一次返回可将展开栏连续收缩到安全边缘；球外触摸由原 App 正常处理。Home、锁屏、系统关闭会话、角色撤销、服务终止或进程丢失都会结束停靠球，不跨会话复活。

### 3.4 屏幕选区附件

屏幕选区附件只来自本次用户明确唤起且系统允许提供的 Assist screenshot。系统、设备策略或前台 App 阻止截图时显示明确不可用状态，不降级到无障碍截图、后台截图或静默 MediaProjection。

## 4. 视觉方向：设计系统

### 4.1 视觉命题

“设计系统”把 Open Android Intelligence 设计成一张安静、可信的本机工作台，而不是带紫蓝渐变、星光、机器人头像和持续光晕的通用 AI 应用。

界面只使用一个强记忆点：**信号缝线（Signal Stitch）**。它是一段 2–3dp 的墨绿短线与一个茶金小点：

- 助手正文左侧是竖向引导线；
- 助理栏上缘是短缺口；
- 停靠球上变成带缺口的环；
- 圈选附件边缘出现同一段缝线；
- 失败时颜色变为错误色并静止。

信号缝线只表达粗粒度状态，所有状态同时提供文字和无障碍说明，不能把颜色或图形当作身份、授权或安全事实。

### 4.2 色彩令牌

| 语义 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| Canvas | `#F1F4F1` 雾砾 | `#101613` 墨苔 | 全局阅读背景 |
| Surface | `#E3ECE7` 云青 | `#19231F` 深松 | 输入栏、用户消息、菜单 |
| Surface high | `#D5E2DC` 苔雾 | `#26332E` 砚松 | 工具卡、附件、浮层 |
| Primary | `#2F645C` 墨绿 | `#88BAAE` 月松 | 主动作、缝线、选中状态 |
| Accent | `#B8874A` 茶金 | `#D3A86F` 暖铜 | 附件验证、圈选、短暂提醒 |
| Text | `#1C2724` 炭墨 | `#E8EFEB` 月白 | 主要正文 |
| Text muted | `#63706B` 石灰 | `#9CABA5` 雾灰 | 时间、状态、辅助说明 |
| Error | `#A95E50` 赭红 | `#E1998C` 陶红 | 失败、阻断、撤销失败 |

浅色和深色必须共享语义角色。主题切换不改变成功、等待、失败和选中状态的含义。V1 不应用系统动态取色，只使用上述固定品牌令牌，避免不同设备破坏产品身份和状态辨识；动态取色若以后加入，必须作为独立设计任务重新验证语义色和品牌一致性。

### 4.3 字体

- UI、聊天正文、标题：Android 系统无衬线字体，中文优先 `Noto Sans SC` 回退；字重 400/500/600。
- 时间、连接状态、附件大小、防抖倒计时：`Roboto Mono`，仅用于真正的技术元数据。
- 正文基准 16sp、行高约 1.5；消息辅助信息 12sp；顶部标题 18–20sp。
- 大标题最多 24sp，避免营销页式巨型标题。
- 所有尺寸跟随系统字体缩放，不能用固定高度裁切文字。

### 4.4 形状、间距与材质

- 间距使用 4dp 基础网格，常用 8/12/16/20/24dp。
- 普通卡片使用 14dp 圆角；助理栏展开态使用 20dp 圆角；停靠球固定为 56dp 圆形。
- 用户消息使用右上较小圆角形成方向感；助手正文默认不套完整气泡。
- 浮层使用 Material 3 tonal surface 和轻阴影，不大面积使用玻璃模糊。
- 所有可触摸目标至少 48×48dp；图标光学尺寸 20–24dp。

## 5. 主 App 信息架构

### 5.1 手机

```text
┌──────────────────────────────┐
│ ☰  对话标题                  ＋ │
│    当前 Gateway · 同步状态     │
├──────────────────────────────┤
│                              │
│  助手开放式正文               │
│  ┃ 信号缝线                   │
│                              │
│                 用户消息色调面 │
│                              │
│  工具结果折叠卡               │
│                              │
│  已删除占位                   │
│                              │
├──────────────────────────────┤
│ 附件草稿 / 批次状态            │
│ ＋  输入框             停止/发送 │
└──────────────────────────────┘
```

侧边抽屉依次显示：

1. Gateway 切换器；
2. 新建对话；
3. 当前 Gateway 的对话线程；
4. 附件；
5. 设置与平台管理。

不同 Gateway 的线程不混排。切换 Gateway 后读取它自己的最近打开线程、命令目录、防抖覆盖设置和本地镜像。

### 5.2 平板与横屏

```text
┌──────────┬──────────────────┬──────────────────────────┐
│ Gateway  │ 对话线程          │ 当前对话                  │
│ 导航栏    │ 搜索 / 新建       │ 时间线                    │
│          │                  │                          │
│ 设置      │                  │ 共享编辑器                │
└──────────┴──────────────────┴──────────────────────────┘
```

- 中等宽度采用线程列表 + 对话双栏；扩展宽度增加 Gateway 导航栏。
- 助理会话仍是独立底部表面，不复制主 App 的多栏导航。
- 内容阅读宽度建议不超过约 680dp；多余空间用于边距和线程列表，而不是拉长每行正文。

## 6. 对话时间线

### 6.1 消息视觉

- 助手消息：开放式正文，左侧信号缝线，适合长文本、Markdown、代码与引用。
- 用户消息：右侧 tonal surface，最大宽度约 82–86%。
- 流式回复：按已验证增量自然增长，不使用打字机动画。
- 工具结果：默认紧凑卡片，显示工具名、状态、摘要、时间；点击后分页展开用户可读结构化结果，原始日志单独查看。
- 远端删除：保留“此内容已删除”的最小占位，不保留旧正文、缩略图或工具输出。

### 6.2 滚动

- 用户停留在底部时，流式内容自然跟随。
- 用户一旦上滑，立即停止自动跟随，并显示“回到底部”按钮。
- 其他线程收到事件只更新对应镜像，不抢当前滚动位置。
- 回到底部按钮可以同时显示未读数量或排队批次数。

### 6.3 标题

新线程创建后暂显示“新对话”。第一条用户内容提交后：

- 普通文本：取第一发送单元的第一段非空文本，规范连续空白后截断至最多 48 个 Unicode 字素簇；
- 防抖批次：只取第一个发送单元，不取整个聚合文本；
- 附件且无文字：使用文件名；
- 语音且无文字：使用“语音消息”；
- 屏幕选区且无文字：使用“屏幕选区”；
- `/new` 不作为标题。

第一次完整往返后，Agent 可以给出标题建议并自动替换临时标题。用户一旦手动重命名，后续 Agent 标题不得自动覆盖。

## 7. 共享消息编辑器

主 App 与助理会话必须共用一个消息编辑器领域模型和 `ConversationRepository`，不能分别实现发送、附件、命令和历史。

```kotlin
sealed interface MessagePart {
    data class Text(val value: String) : MessagePart
    data class Attachment(val draftId: String) : MessagePart
    data class Command(val rawText: String, val catalogVersion: String?) : MessagePart
}
```

编辑器支持：

- 文字；
- Gateway 级 Agent 命令目录；
- 图片、文件、AAC/M4A 音频；
- 屏幕选区附件；
- 插件贡献的受保护对话动作；
- Developer Trust 原生 UI 扩展。

普通命令点击后先填入输入框，用户可补参数再发送；顶部“新建对话”直接调用 Gateway 的 `POST /conversations`，由插件创建独立宿主会话，不自动发送 `/new`。用户手动输入的 `/new` 仍作为命令透传。命令目录仅展示宿主实际返回的条目；离线、加载或失败均显示真实状态，不补入预设命令。

## 8. 消息防抖与批次

### 8.1 三层模型

```text
本地发送单元（每次点击发送）
       ↓ 防抖收集
消息合并批次（保留每个成员身份）
       ↓ newline-v1
一次 Agent 生成运行
```

### 8.2 设置

- 全局默认，可按 Gateway 覆盖；
- 时长范围 0–10 秒，默认 1.5 秒；
- 0 表示关闭；
- “新消息延长等待”默认开启；
- 固定窗口：`firstAt + delay`；
- 延长窗口：`lastAt + delay`；
- 从第一条起最长等待 30 秒；
- 每批最多 20 个发送单元；
- 总字节不超过协商批次上限；
- 到达任何上限后立即封存当前批次，新消息进入下一批；
- 设置修改只影响新批次。

### 8.3 边界

- 只合并普通纯文本；
- 任何斜杠命令是硬边界；
- 任何含附件、语音或屏幕选区的发送单元是硬边界；
- 不跨账号、Gateway 或对话合并；
- 一个对话只有一个运行中的 Agent generation，后续批次 FIFO；
- `/new` 由 Agent 命令入口立即创建新线程，旧线程 generation 继续运行。

### 8.4 UI

用户每次点击发送后立即看到独立发送单元，状态为“等待合并”。同一批次使用共同边线或折叠容器表示：

```text
● 室内也要适合拍照
● 晚餐不要太远
────────────────
同一批次 · 2 条 · 1.2s
```

封存后仍保留成员身份和顺序。其他设备从远端历史恢复相同成员与批次关系；Agent 只收到一次聚合输入。

## 9. 助理会话状态

### 9.0 运行归属

默认助理与主 App 使用同一个 Android package 和 UID，但按 Android SDK 建议分为三个进程：

```text
主进程                 平台内核、Gateway、主 App 与完整对话 UI
:assistant-keeper      轻量 VoiceInteractionService
:assistant-session     VoiceInteractionSessionService 与助理会话 UI
```

私有进程只隔离内存、生命周期、崩溃和 ANR，不隔离权限、私有文件或 Keystore。每个进程都会创建自己的 `Application` 实例；启动必须进程感知，助理进程不能顺带初始化完整 Gateway、插件运行时或旧业务组合根。助理会话通过版本化窄入口请求主进程平台内核，不直接持有 Gateway 凭据或 Agent 内部 session ID。

不能使用一个巨大互斥枚举，也不能用无约束布尔值。使用四个并行、由同一 reducer 管理的有限状态轴：

```text
SurfaceState
  HIDDEN | EXPANDED | SELECTING_SCREEN | CROP_PREVIEW | DOCKED | TERMINATED

GenerationState
  IDLE | QUEUED | RUNNING | CANCEL_REQUESTED | CANCELLED |
  COMPLETED | FAILED | UNSUPPORTED | OUTCOME_UNKNOWN

ComposerState
  EDITING | DEBOUNCE_COLLECTING | SEALED | WAITING_NETWORK |
  WAITING_ATTACHMENTS | SUBMITTING | ACCEPTED | FAILED

AttachmentState[]
  LOCAL_PREPARING | CREATE_PENDING | UPLOADING | VERIFYING |
  VERIFIED | RETRYABLE_FAILURE | TERMINAL_FAILURE |
  OUTCOME_UNKNOWN | CANCELLED
```

### 9.1 展开态

底部助理栏包含：

- 当前 Gateway/对话；
- 新建对话；
- 添加/圈屏入口；
- 文本编辑器；
- 发送或停止按钮；
- 附件草稿条；
- 流式回复与首个完整结果；
- “在 App 中打开”。

手机展开态左右安全边距 16dp，基础高度 70dp，20dp 圆角；回复展开时高度由内容状态驱动，但不得超过当前可用窗口高度的 60%，超过后只让回复区域滚动。平板最大宽度 760dp 并水平居中。

### 9.2 返回与关闭

- 展开态收到会话 Back：先收键盘，再连续变形成停靠球。
- 停靠后若 Back 仍派发给助理会话则结束；若焦点已在原 App，则原 App 正常处理。
- 关闭助理 UI 不取消已被 Gateway 接受的消息或 Agent generation。
- 只有明确“停止生成”才调用取消接口。

### 9.3 停靠球

- 默认右侧安全位置，可拖至左右安全边缘；
- 拖动 1:1 跟手，保留抓取偏移；
- 松手继承速度并吸附；
- 边缘使用渐增阻力；
- 拖入关闭区结束助理会话；
- 旋转、折叠、分屏、IME 和系统手势区域变化时重新计算位置；
- 位置只在当前助理会话内保留。

停靠球状态：空闲、当前回复中、结果可查看、失败。结果到达不自动展开，只使用一次克制触觉反馈、信号环变化和无障碍状态说明。

## 10. 屏幕圈选流程

### 10.1 视觉流程

```text
Assist screenshot 可用
  → 全屏低透明度遮罩
  → 用户自由手绘圈选
  → 路径闭合与边界调整
  → 本地裁剪预览
  → 进入共享附件草稿条
  → 用户提交
```

自由手绘圈选是主要触摸体验：路径与手指 1:1 跟随，终点进入起点 24dp 范围时提示闭合；在范围外松手则以直线连接首尾。输出使用 PNG；圈外像素透明，不把完整截图上传。TalkBack 和无法精细拖动的用户获得矩形边界调整替代操作，可分别调节上、下、左、右边界。

### 10.2 生命周期

- 完整截图只在圈选期间的内存中存在；
- 确认后立即丢弃完整截图；
- 裁剪结果进入当前助理会话的附件草稿；
- 取消、Home、锁屏、会话销毁或进程死亡会清除屏幕图像；
- 屏幕选区不跨助理会话恢复；
- 无截图时提示“当前内容不允许共享”，不静默降级。

## 11. 动效系统

### 11.1 命名

- 栏与停靠球：`Shared element transition + Morph`；
- 圈选缩入附件：`Shared element transition`；
- 可拖停靠球：`Drag + Momentum + Rubber-banding + Spring`；
- 页面/层级：`Direction-aware transition`；
- 所有手势动画：`Interruptible animation`。

### 11.2 动效令牌

| 场景 | 参数 | 约束 |
|---|---|---|
| 触摸按下 | 约 90ms，scale 0.98 | pointer-down 即反馈 |
| 默认容器变形 | 临界阻尼，response 0.34s | 无过冲，可中断 |
| 拖动吸附 | damping 约 0.82，response 0.32s | 继承释放速度 |
| 消息进入 | 160–190ms，4dp + opacity | 不使用打字机 |
| 内容层交接 | 120–180ms | 容器开始移动后再出现 |
| 大层级移动 | ease-in-out / 对称路径 | 进入退出同一路径 |

### 11.3 助理栏 ↔ 停靠球编排

为防止展开时闪烁，输入内容、按钮、附件层和圆环必须始终保持挂载。禁止在 morph 中使用 `display:none/block` 或重建 Compose subtree。

展开：

1. 0–120ms：停靠圆环淡出并内收；
2. 0–480ms：同一容器从当前停靠位置连续改变宽度、高度、位置、圆角、背景和阴影；
3. 约 120–300ms：信号缝线、按钮、文字和附件错峰进入；
4. 动画中再次点击时从当前 presentation value 反向，不跳到旧逻辑端点。

收起采用镜像顺序：内容先淡出，容器随后收缩，圆环在中后段出现。容器必须使用可插值的明确尺寸并裁剪内部内容，避免窄容器阶段泄露文字。

### 11.4 减少动态

开启减少动态后：

- 停止位移弹簧、速度继承、圈选回缩和循环呼吸；
- 栏与球使用短 Crossfade；
- 屏幕选区保持静态遮罩和清晰边界；
- 状态仍通过文字、图标、形状和颜色表达；
- 不改变提交、取消或生命周期语义。

## 12. 附件门控提交

### 12.1 原则

用户选择附件后可以立即预上传。用户即使在附件未全部 verified 时也可以点击提交；点击只在 Android 本地建立一次待提交意图，不提前让 Gateway 接受未验证附件。

点击提交时冻结：

- 当前文字；
- 有序附件快照；
- 草稿 revision；
- `submitIntentId`；
- 稳定 `clientMessageId`；
- 每个 HTTP 操作的稳定 request ID。

所有附件 verified 后，协调器先原子切换为 `SUBMITTING`，再恰好发送一次消息。重复点击、多个附件同时完成、SSE 重连或进程恢复都不能产生第二次发送。

### 12.2 编辑、取消与失败

- 等待期间可“取消并保留草稿”；
- 修改文字、移除、替换或新增附件会使旧意图失效，必须重新提交；
- 任一附件失败阻止整条消息，不部分发送；
- 重试成功可以继续未修改的原意图；
- 已明确 failed/expired 的远端附件尝试作废，新的手动重试创建新远端尝试；
- 普通附件使用本地加密临时暂存，重启后可由用户手动重试；
- 屏幕选区附件不恢复；
- 正文允许显式空字符串，但至少必须有一个 verified 附件；空正文且无附件无效。

## 13. 流式生成与取消

每个对话线程最多一个运行中的 generation，后续消息批次 FIFO。前端可以在生成期间继续输入和发送；输入形成后续批次，不自动打断当前 generation。

每次生成拥有独立 `generationId`，不同于：

- `conversationId`；
- 助手 `messageId`；
- pairing generation；
- connection generation；
- Gateway 登录 `sessionId`。

取消状态：

```text
RUNNING
  → CANCEL_REQUESTED
  → CANCELLED | ALREADY_COMPLETED | UNSUPPORTED | OUTCOME_UNKNOWN
```

只有收到结构化 `CANCELLED` 后显示“已停止”。`OUTCOME_UNKNOWN` 时暂停同线程后续批次，先恢复真实终态。

## 14. Agent 命令与 `/new`

### 14.1 命令目录

第一版命令目录按 Gateway 和语言返回，该 Gateway 全部线程共用。最小字段：

```json
{
  "format": "agent-command-catalog-1.0",
  "catalogVersion": "cmdcat_...",
  "languageCode": "zh-CN",
  "commands": [
    {
      "id": "new",
      "invocation": "/new",
      "title": "新建对话",
      "description": "开始一个不继承当前上下文的新对话",
      "acceptsArguments": false,
      "availability": "available"
    }
  ]
}
```

目录只影响发现和显示。未列入目录的命令仍原样发送，由 Agent 返回未知、拒绝或完成结果。

### 14.2 新建按钮与用户发送的 `/new`（2026-10-15 修订）

App 新建按钮生成 `clientConversationId` 并调用 `POST /conversations`；插件创建独立空宿主会话并返回权威 `conversationId` 后，App 才切换。该操作不产生用户消息，不要求来源线程，也不依赖 `agent-command-new-v1`。失败时保留原线程与草稿。

用户手动发送 `/new` 保持以下命令流程：

```text
Android 生成 clientConversationId + requestId
  → 透传 /new
  → Agent 命令解析器创建 agentSessionId
  → Gateway Adapter 原子创建 conversationId 并保存绑定
  → conversation.command.result(created-conversation)
  → Android 切换到真实 conversationId
```

`/new` 保留在来源线程，来源线程显示紧凑的“已创建新对话”跳转项；新线程从空上下文开始。旧线程和旧 generation 继续存在。Gateway 认证 `sessionId` 不变化，Android 不接收或生成 `agentSessionId`。

## 15. 本地对话镜像

Android 保存按 Gateway、账号和安装实例隔离的完整加密持久镜像，供离线阅读。Agent Host 是跨设备长期权威，Gateway 不成为长期正文库。

镜像包含：

- 对话与标题；
- 用户/助手消息；
- 本地发送单元和批次关系；
- 命令和结构化工具结果；
- 消息状态、revision、tombstone；
- 附件元数据与本地缓存状态；
- 草稿；
- snapshot revision、SSE cursor 和同步时间。

同步状态：

```text
SYNCED | CATCHING_UP | OFFLINE_MIRROR | STALE_MIRROR |
RESYNC_REQUIRED | ACCOUNT_LOCKED | LOCAL_DATA_REMOVED |
PAIRING_REVOKED
```

- 离线可阅读和编辑草稿，不自动发送；
- 远端编辑/删除覆盖镜像；
- tombstone 不保留隐藏旧正文；
- 退出登录保留但锁定；
- 解除配对、移除本地账号和删除 Gateway 按各自范围清除；
- 不进入 Android Auto Backup；
- 已同步媒体原件默认持久保存，不按 TTL 或固定缓存配额淘汰（ADR 0053）；
- 本地附件临时暂存只服务当前草稿和手动重试，不等同于媒体缓存。

## 16. 前端领域接口

```kotlin
interface ConversationRepository {
    suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage
    suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation
    suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage
    suspend fun submitBatch(batch: MessageBatch): BatchAcceptance
    suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance
    fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent>
    suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult
}

interface AgentCommandCatalogRepository {
    suspend fun get(gatewayId: String, languageCode: String): AgentCommandCatalog
}

interface AttachmentDraftCoordinator {
    suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft
    suspend fun armSubmission(draftId: String, revision: Long): PendingSubmissionIntent
    suspend fun cancelSubmission(intentId: String): CancelSubmissionResult
    fun observe(draftId: String): Flow<AttachmentDraftState>
}

interface ConversationMirrorStore {
    suspend fun open(scope: MirrorScope): MirrorSession
    suspend fun lock(scope: MirrorScope)
    suspend fun wipeForUnpairing(scope: MirrorScope)
    suspend fun wipeForLocalAccountRemoval(scope: MirrorScope)
}
```

所有接口使用相对模块边界，不暴露真实文件路径、provider URI、Gateway 凭据、Agent 内部 session ID 或可变身份覆盖字段。

## 17. Gateway Protocol v2 必需扩展

以下仅是设计目标。实现前必须更新权威协议、严格 Schema、向量和 Hermes/OpenClaw 一致性门禁。

本节所有相对路由均以 `/open-android-intelligence/v2` 为基准。

### 17.1 功能协商

新增显式功能与限制：

- `agent-command-catalog-v1`；
- `message-batches-v1`；
- `newline-v1`；
- `generation-cancel-v1`；
- `conversation-mirror-v1`；
- `attachment-status-v1`；
- `maxBatchMembers`；
- `maxBatchBytes`。

`clientBatchId`、`generationId` 以及新增命令目录/结果标识若进入 HTTP 或 SSE，必须加入协议封闭 wire ID 集合，并保持不透明；它们不能复用或替代 `requestId`、`sessionId`、pairing generation、connection generation 或授权 revision。

不支持的功能必须在 UI 中降级或隐藏，不能尽力猜测。

### 17.2 消息批次

```text
POST /conversations/{conversationId}/message-batches
```

```json
{
  "clientBatchId": "batch_client_...",
  "joinMode": "newline-v1",
  "members": [
    { "clientMessageId": "msg_client_1", "text": "第一条" },
    { "clientMessageId": "msg_client_2", "text": "第二条" }
  ]
}
```

响应必须返回批次 ID、每个成员的 `clientMessageId → messageId` 映射、状态和 generation 排队信息。同一请求 ID + 不同批次内容返回幂等冲突；每个成员 ID 在当前对话内唯一。

`newline-v1` 精确定义为：相邻成员之间插入一个 U+000A；不裁剪成员文本、不删除成员内部或首尾换行、不追加尾部换行。

### 17.3 命令目录与结果

```text
GET /commands?languageCode=zh-CN
```

命令继续通过普通消息路径原样发送。消息可以携带严格的命令显示上下文和 `/new` 候选 `clientConversationId`，但 Android 不解析业务语义。新增封闭事件：

```text
conversation.command.result
```

结果至少包含来源消息、来源对话、命令 ID、闭集 outcome 和可选新 `conversationId`，不返回 `agentSessionId`。

### 17.4 生成取消

```text
POST /conversations/{conversationId}/generations/{generationId}/cancel
```

取消使用独立 request ID 和幂等结果；已完成、未支持和未知结果是不同状态。

### 17.5 附件与消息查询

补充：

```text
GET    /attachments/{attachmentId}
DELETE /attachments/{attachmentId}
GET    /conversations/{conversationId}/messages?clientMessageId=...
```

- GET 只返回当前账号可见的附件状态和元数据；
- DELETE 只撤销尚未被消息引用的附件，幂等清理暂存；
- 消息查询用于网络响应丢失后的真实结果恢复；
- 已 failed/expired 附件的手动重试创建新远端尝试；
- outcome unknown 使用原请求身份查询或重试，不生成第二条逻辑消息。

### 17.6 对话快照与事件

`GET /conversations` 与 `GET /conversations/{conversationId}` 必须定义分页、稳定排序、snapshot revision、消息/工具结果 revision、tombstone、标题和同步 cursor。

封闭 SSE 至少覆盖：

- `conversation.message.accepted`；
- `conversation.message.delta`；
- `conversation.message.completed`；
- `conversation.generation.cancelled`；
- `conversation.command.result`；
- `conversation.title.updated`；
- `conversation.timeline.upsert`；
- `conversation.timeline.tombstoned`；
- `conversation.snapshot.invalidated`。

每个消息流使用连续正序 sequence，至多一个终态，终态后拒绝增量；SSE event ID 仍是恢复 cursor，不代替消息 ID、generation ID 或 sequence。

### 17.7 历史媒体按需读取

本地媒体缓存不能从 Gateway 附件暂存区长期取数；历史媒体原件由拥有长期对话内容的 Agent Host 通过 Gateway Adapter 提供受认证、对话绑定的按需读取能力：

```text
GET  /conversations/{conversationId}/attachments/{attachmentId}/metadata
POST /conversations/{conversationId}/attachments/{attachmentId}/cache-grant
GET  /conversations/{conversationId}/attachments/{attachmentId}/content
```

- 先返回文件名、媒体类型、大小、摘要、远端是否仍可用和预计本地占用；
- Android 自动补存时签发短期、单用途读取 grant，仍受会话、设备和配对绑定约束；
- content 先流入当前 Gateway/账号范围的私有持久媒体，用户明确下载后才导出系统公共目录；
- 不接受任意 URL、provider URI 或文件路径；
- 本地空间不足时明确提示保存失败，不自动淘汰已保存原件；
- 远端已删除或不再保留原件时显示 `REMOTE_UNAVAILABLE`，保留附件元数据但不伪造可下载状态。

## 18. 插件界面

### 18.1 受保护插件

受保护插件继续使用宿主验证并渲染的设置、状态卡和对话动作槽。动作得到展示不等于已获得设备执行授权。

### 18.2 Developer Trust 原生插件

开启全局 Developer Trust 后，显式安装并验证包身份的原生插件可以通过版本化扩展：

- 替换主 App Shell；
- 注册顶级路由；
- 修改对话页、助理栏、主题和动画；
- 提供任意 Compose/原生页面。

宿主保留先于插件加载的安全模式、全局停用和崩溃恢复入口。原生插件与宿主共享安全身份，可能访问本地完整对话镜像和媒体缓存；该事实必须在界面中持续说明。

## 19. 错误、空状态与离线

### 19.1 空状态

- 无 Gateway：引导添加或登录 Gateway；
- 无对话：输入框可用，首发时创建对话；
- 无命令：不显示空菜单，仅保留普通输入；
- 无附件历史：说明如何从输入栏添加；
- 无截图：明确说明当前内容不允许共享。

### 19.2 消息与附件错误

- 防抖批次未送达：显示“继续发送”，不标记已发；
- Gateway accepted 但 Agent failed：显示“已送达，Agent 处理失败”；
- generation outcome unknown：暂停后续批次并检查状态；
- 附件失败：保留草稿、逐项重试或移除；
- 移除附件后不得静默发送剩余内容；
- 消息 outcome unknown：使用原 `clientMessageId` 查询，不能创建新消息。

错误文案使用具体动作，例如“重新登录后继续”“重试附件”“检查发送状态”，不使用“发生未知错误”。

## 20. 无障碍与可适应性

- 动态字号下不裁切标题、编辑器、按钮或工具卡；
- TalkBack 顺序：顶部目标 → 时间线 → 批次状态 → 附件草稿 → 编辑器 → 发送/停止；
- 流式内容降低无障碍播报频率，不逐 token 朗读；
- 停靠球提供“展开助理”“移动到左侧/右侧”“结束助理”动作；
- 自由圈选提供矩形边界调整替代操作；
- 颜色不是唯一状态信号；
- 错误与完成使用语义公告；
- 深浅主题均达到正文和操作控件可读对比；
- 减少透明度时使用更实的 Material surface；
- 减少动态时按第 11.4 节降级。

## 21. 测试与验收

### 21.1 设计系统与主 App

- 浅色/深色 Compose Preview；
- 手机、SM-X710 平板和至少一个折叠/横屏尺寸；
- 动态字号、TalkBack、RTL 和中文/英文；
- 对话列表、标题、批次、工具卡、删除占位、离线状态截图基线；
- 用户上滑后流式回复不得抢滚动位置。

### 21.2 助理会话

- 默认助理角色授予/撤销；
- 展开、返回收球、快速反向、拖动吸附、Home、锁屏、旋转、IME、分屏；
- 球外触摸是否回到原 App；
- 收球后回复不自动展开；
- 关闭 UI 不取消 generation；
- 进程死亡后停靠球和屏幕内容不复活。

### 21.3 动效

- 栏与球的内容层始终挂载，不使用瞬时 display 切换；
- 展开/收起快速交替无闪烁、跳帧或内容泄露；
- 60fps 为最低门槛，支持高刷新率设备；
- 动画中输入仍可响应；
- 减少动态版本通过同一业务状态测试；
- 慢放/逐帧检查容器、文字和圆环的交接顺序。

### 21.4 接口与一致性

- Android/Hermes/OpenClaw 对命令目录、`/new`、批次聚合、成员映射、generation 取消、附件查询/删除、消息查询、标题更新和 tombstone 使用同一向量；
- `newline-v1` 字节级黄金向量；
- 相同 request ID + 不同 body 失败关闭；
- 进程死亡、超时、重复回调和 SSE 重放不重复提交；
- 账号、Gateway、对话和安装实例隔离；
- 终态后增量拒绝；
- 附件未 verified 时消息拒绝；
- 空正文且无附件拒绝，空正文且 verified 附件允许。

## 22. 分阶段实施边界

设计整体一致，但实现仍按独立可验收纵向切片推进：

1. **共享 UI 基础**：Material 3 主题、令牌、字体、窗口适配、共享状态模型。
2. **主 App 对话闭环**：Gateway/线程、历史、编辑器、流式回复、工具卡、标题、镜像。
3. **默认助理文字闭环**：一个 APK、私有进程、系统入口、展开栏、停靠球、真实取消。
4. **屏幕选区附件**：Assist screenshot、自由圈选、预览、附件门控提交。
5. **命令与消息批次**：Gateway 级目录、`/new`、防抖批次、跨设备成员身份、FIFO generation。
6. **插件 UI 扩展**：受保护动作槽与 Developer Trust 原生界面接管。

每个切片先补严格协议/领域测试，再实现 UI，再进行 Compose 测试和真机验收；不以后一切片的 mock 冒充当前切片完成。

### 22.1 计划边界

本文是统一视觉、交互和接口的总设计规格，不对应一次性实现。书面规格获批后只为第 1 个纵向切片创建实施计划；该切片通过测试、自审、真机或明确阻塞证据并完成独立提交后，才为下一切片创建新计划。不得把六个切片合并为一次宽泛开发任务。

## 23. 明确非目标

- 逐像素复制 Gemini、ChatGPT 或其商标视觉；
- 承诺所有 OEM 都有相同助手按键、窗口重力或系统动画；
- 使用 `SYSTEM_ALERT_WINDOW` 做跨会话常驻悬浮球；
- 用 Accessibility 绕过截图限制；
- 后台或 Agent 自动截图、自动圈选、自动外发；
- 在第一文字切片实现手机端 TTS/ASR；
- 离线消息、命令和附件在重启后自动发送；
- 让 Gateway Core 成为长期对话正文数据库；
- 让 App 解释 Agent slash command 或引入设备自动化 DSL；
- 让插件命令目录授予 Android 权限；
- 把视觉原型、静态检查、APK 编译或模拟器截图报告成真机产品完成。

## 24. 设计验收标准

书面设计通过必须同时满足：

- 主 App 和助理栏共享同一对话、草稿、附件、命令与流式状态；
- 浅色和深色使用同一“设计系统”语义系统；
- 信号缝线是唯一主要视觉签名，其他装饰保持克制；
- 对话优先，Gateway 和状态明确但不压过正文；
- 栏、球、圈选和附件保持空间连续性；
- 快速展开/收起不闪烁，动画可中断；
- 防抖批次保留每次发送身份，Agent 只收到一次有序聚合输入；
- 附件提交先有用户意图，全部 verified 后只发送一次；
- `/new` 由 Agent 解释，Android 使用结构化结果切换真实新线程；
- 本地完整镜像可离线读，远端编辑/删除仍是权威；
- 插件 UI 权限与运行模式一致；
- 所有失败、离线、取消和未知终态均有真实可恢复 UI；
- 无障碍、减少动态、平板和深色模式不是后补分支。
