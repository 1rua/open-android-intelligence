# HTML 动效预览转 App 前端（真实接口对接）交接记录 (2026-09-01)

## 任务目标

把根目录 `mobile_motion_preview.html`（动效实验室原型）转换为 Android 宿主的真实前端页面：所有展示、交互与数据获取都必须走 Gateway Protocol v2 真实接口，严禁死数据；空、加载、失败状态必须可区分。

本任务遵循 `docs/superpowers/specs/2026-08-29-android-conversation-assistant-ui-design.md`（视觉/交互/接口权威）与 `docs/contracts/gateway-protocol-v2.md`（线协议权威）。

## 已完成

### 1. 真实网络层（`gateway-client` 模块）

- `http/HttpsGatewayTransport.kt`（新增）：唯一的出站 HTTPS 面。直连 HTTPS + SSE（默认传输，ADR 0039），SPKI pin 在信任响应字节前核验，SSE 只吐原始字节不越权分帧。
- `http/GatewayHttpClient.kt`（重写）：认证头对齐契约 §6.1 的九个 singleton header（Bearer Authorization + Protocol/Account/Device/Session + Request-Id/Timestamp/Nonce/Signature），所有变更请求携带 `Idempotency-Key`（绑定 request id）；SSE 携带 `Accept: text/event-stream` 并从存储 cursor 恢复。
- `auth/GatewayAuthClient.kt`（新增）：`POST /negotiate`（认证前）、`POST /sessions/password`（密码登录，密码用后即清）、`POST /sessions/refresh`（轮换）、`DELETE /sessions/current`（登出，可撤销刷新凭据）。登录引用协商返回的 `negotiationId`。
- `auth/Ed25519DeviceKeyStore.kt`（新增）：软件 Ed25519 设备密钥，私钥以 Keystore AES-GCM 包络落盘（帧格式 `[iv][len][ciphertext][spki]`），签名用平台 JCA。
- `negotiation/NegotiationClient.kt`（重写）：执行器可注入（认证前用纯传输，认证后可换签名客户端），返回协商交集与 `limits`（附件上限、媒体类型、TTL）。
- `attachments/HttpAttachmentTransport.kt`（新增）：三步附件通道 `POST /attachments` → `PUT …/content`（带 Content-Length + Digest）→ `POST …/commit`；`AttachmentUploader.upload` 改为 suspend。
- `conversations/ConversationClient.kt`（扩展）：新增 `POST /conversations`（创建）、`GET /conversations/{id}`、`GET …/messages`（分页时间线，query 参数预排序以满足 canonical target）、`POST …/message-batches`（newline-v1 批次）、`POST …/generations/{gid}/cancel`（404 → ALREADY_COMPLETED）、`GET …/messages?clientMessageId=`（结果恢复）；`JsonFields.kt`（新增）提供空安全的 JSON 读取。

### 2. 领域适配层（`conversation-data` 模块，新增）

- `GatewayConversationRepository.kt`：实现领域端口 `ConversationRepository` + `GenerationTracker`，全部转发真实客户端；取消结果是闭集（CANCELLED / ALREADY_COMPLETED / UNSUPPORTED / OUTCOME_UNKNOWN）。
- `GatewayEventDecoder.kt`：SSE → `VerifiedConversationEvent` 封闭映射（accepted/delta/completed/cancelled/command.result/title.updated/timeline.upsert/tombstoned/snapshot.invalidated）；未知事件名返回 null 而不是猜测；`generationIdOf` 只认服务端载荷。
- `GatewayCommandCatalogRepository.kt`：`GET /commands?languageCode=` → 领域 `AgentCommandCatalog`。
- `GatewayAttachmentDraftCoordinator.kt`：三步上传编排，真实状态机 LOCAL_PREPARING → UPLOADING → VERIFYING → VERIFIED / RETRYABLE / TERMINAL，失败保留草稿可重试。

### 3. UI 层（`conversation-ui` 模块）

- `state/Loadable.kt`（新增）：Idle/Loading/Empty/Ready/Failed 五态 + `Result.toLoadable` + `map`；**Empty 与 Failed 刻意分开**（网络错误不能伪装成"没有数据"）。
- `state/WorkbenchController.kt`（新增）：工作台唯一状态所有者。线程列表、时间线、命令目录、防抖批次（复用 `DebounceBatcher`）、生成状态、通知全部来自仓库结果或服务端事件；本地发送单元保留身份（`local_*`），接受以服务端为准。
- `components/StateViews.kt`（新增）：`LoadableRegion` 强制每个远端区域给出空态提示与重试动作。
- `components/SignalStitch.kt`（新增）：信号缝线（唯一视觉签名，墨绿短线 + 茶金点，失败变赭红）。
- `workbench/MessageTimeline.kt`：助手开放式正文（左缝线）/ 用户 tonal 气泡（右下角状态"等待合并"或时间）。
- `workbench/ComposerBar.kt`：共享编辑器；生成中发送变停止（停止只请求取消，终态等事件）；`PendingBatchStrip` 展示防抖批次成员。
- `workbench/CommandMenu.kt`：`/` 前缀触发，从 Gateway 目录过滤；**选择只填入输入框，永不直接发送**。
- `workbench/ThreadDrawer.kt`：会话抽屉（线程列表同样具有空/载/败三态）。
- `workbench/WorkbenchScreen.kt`：装配页；底部跟随阅读位置（上滑停跟随）。

### 4. App 组合根（`app` 模块）

- `GatewayRuntime.kt`（新增）：连接生命周期 Disconnected → Negotiating → Authenticating → Connected/Failed，全部真实协商+登录；登录成功后才装配 WorkbenchController（Ed25519 签名 + access token 注入签名客户端）。
- `MainActivity.kt`（重写，592 行死数据 UI 全部移除）：登录页 ↔ 工作台按真实 phase 切换；保留助理交接门 `AssistantHandoffGate` / `DefaultAssistantHandoffGate` / `evaluateAssistantHandoff`（`tools/test_assistant_handoff_static.py` 门禁通过）。
- `GatewayLoginScreen.kt`（新增）：真实登录表单，阶段横幅只反映运行时已证明的事实（协商中/认证中/失败含 Gateway 错误码/已连接含协商到的附件上限）。
- `PlatformSettingsScreen.kt`（重写）：开发者信任模式开关驱动内核 `DeveloperTrustMode`（含强制确认文案）；插件卡片如实呈现"当前运行时尚未安装任何设备插件"；审计列表渲染 `AndroidAuditStore` 真实记录（无记录给空态说明）。
- `OpenAndroidIntelligenceApplication.kt`：审计 sink 持久持有；`gatewayRuntime` 进程级懒加载。
- `Navigation.kt`（新增）：`CoreNavigation` 三主目的地契约（`ArchitectureBoundaryTest` 依赖）。
- **死数据清除**：`GatewayScreen.kt`、`ConversationScreen.kt`、`AttachmentPicker.kt`（含 `GatewayPresenter.connect()` 假在线、`ConversationPresenter.receiveReply()` 假回复）已移入 `/tmp/open-android-intelligence-trash/` 待人工复核后删除。

## 接口映射（HTML 原型 → 真实实现）

| 原型行为（原为死数据） | 后端接口 | 现实现 |
|---|---|---|
| `sessionsData` 内存 HTML 会话 | `GET /conversations` | ThreadDrawer + `readThreads()` |
| `STREAM_SAMPLE_RESPONSES` 打字机 | `POST …/message-batches` + SSE `message.delta/completed` | 防抖批次 + 事件流渲染，无本地假回复 |
| `COMMAND_CATALOG` 常量 | `GET /commands?languageCode=` | `CommandMenu` 读真目录 |
| `activeModelId` 仅改标签 | （无对应接口） | 未实现，不展示假模型选择 |
| `attachAssistMedia` 生成假文件名 | `POST/PUT/POST /attachments` 三步 | 真上传状态机（SAF 选择器待接，见未完成） |
| `stopStreaming` 改本地旗标 | `POST …/generations/{gid}/cancel` | 真取消 + 闭集终态；无 generationId 时明确"停止不可用" |
| `renderAuditLogs` 假日志 | 本机 `AndroidAuditStore` | 真实审计记录渲染 |
| 设置页假"已配对/在线"徽章 | 真实连接 phase | 只显示运行时证明的状态 |

## 验证证据

- `./gradlew :app:assembleFullDebug`：BUILD SUCCESSFUL。
- `./gradlew :app:testFullDebugUnitTest :gateway-client:testDebugUnitTest :conversation-domain:testDebugUnitTest :conversation-ui:testDebugUnitTest :conversation-data:testDebugUnitTest`：全绿（含新增 `GatewayEventDecoderTest` 5 例）。
- `python3 -m unittest tools.test_assistant_handoff_static`：3 例 OK。
- `tools/` 全量静态门禁：88 例中 9 例失败，**全部为 main 分支既有缺口**（期望的是迁移 worktree 状态）：`mvp-forbidden-surfaces.gradle.kts` 未登记 `artifact-ports`/`capability-ports`/`control-ports`/call-log、app 依赖未接这三个契约模块、旧 Application 组合断言（NotificationBridgeDispatcher 等从未存在于 main 的 Application）。本次会话未触碰这些文件（`git diff` 为空佐证）。

## 未完成 / 下一步（按设计规格切片顺序）

1. **SAF 附件选择**（`ActivityResultContracts.OpenDocument` → `GatewayAttachmentDraftCoordinator.prepare` → 消息携带 verified attachmentId）。当前附件按钮给出"未接入"Toast（诚实未连接状态）。
2. **助理会话界面**（设计规格切片 3）：`assistant-holder` 进程、展开栏 ↔ 停靠球 morph、系统 Assist 入口。`conversation-ui/assistant/AssistantView.kt` 仅有雏形。注意 `MainActivity.evaluateAssistantHandoff` 目前用 `DefaultAssistantHandoffGate()` 默认拒绝（fail-closed 正确）；接通时需要真实用户开关。
3. **屏幕选区附件**（切片 4）：Assist screenshot + 自由圈选 + `NormalizedRect` visualContext 上传。
4. **账号持久化**：`GatewayRuntime` 的 installationId 已持久化，但 `SessionCredentials`/refresh credential 尚未落盘（`AndroidKeystoreGatewayCredentialStore` 已具备，接上即可支持重启恢复登录）。
5. **conversation-data 单测补强**：`GatewayConversationRepository` 的失败路径（404/409/网络中断 → Loadable.Failed 码）建议用 fake transport 补表驱动测试。
6. 9 个既有静态门禁失败是否在本分支修复，需用户决策（它们属于迁移 worktree 的门禁期望）。

## 关键技术约束（后续会话必读）

- 环境变量：跑 Gradle 前必须设 `LANG=zh_CN.UTF-8 LC_ALL=zh_CN.UTF-8 ANDROID_HOME/GRADLE_USER_HOME/ANDROID_USER_HOME` 指向 `.toolchains/`（仓库路径含中文，`LANG=C` 会让 JVM 无法解析路径）。
- `CanonicalTarget.canonicalize` 拒绝重写：query 参数必须在客户端预排序（`ConversationClient.query()` 已按 name/value 排序）。
- 时间戳必须用 `RequestSigner.formatTimestamp`（固定 3 位毫秒，勿用 `ISO_INSTANT`）。
- 平台 `org.json` 在 JVM 单测不可用；解析一律走 `gateway-client` 的 `Json`/`JsonFields`。
- 契约铁律：本地 UI 不得自造 generationId / messageId / 终态；Empty ≠ Failed；取消只请求不宣布。
