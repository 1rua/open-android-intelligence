# 连接生命周期、Agent/工作区解耦、冷启动缓存优先与就地重连重构报告

关联 Issue：#206  
关联 PR：#207  
范围约束：仅修改 Android 客户端（`apps/android/`）与设计文档（`docs/`），严格未触碰任何上游服务端代码。

---

## 一、问题诊断与根因分析

1. **[P1 Bug #1] Agent / 工作区切换失效、菜单仅见当前工作区、登录页混杂工作区、同 Agent 切工作区断连重连**
   - 原 `RemoteRuntime` 将 Host、Backend（Agent）与 Workspace 压平为单层 `RemoteWorkspaceState(hosts, endpoints, selected)`。
   - 原 `selectLocked()` 在每次切换工作区时均调用 `retire()` 关闭底层 `RemoteRpcChannel` 并清空 `controller`，再重新通过 `accountRuntime.connect(...)` 建立传输通道，导致同一 Agent 下切换工作区产生不必要的断连与重连，并在切换窗口期使 `controller == null` 触发根路由跳回登录页。
   - 原 `restoreOffline()` 仅恢复上次单条工作区缓存，菜单未自动触发当前 Agent 下全量工作区/会话水合，往往需二次点击 Host 触发 `discover()` 才填充全部端点。
   - 原 `RemoteLoginScreen` 复用 `RemoteEndpointList` 将工作区混入登录界面；`ThreadDrawer` / `ConversationBrowser` 围绕本地文件夹管理组织，而非基于当前 Agent 的工作区树分类。

2. **[P2 Bug #2] 冷启动闪现登录页、过早弹出离线横幅、在线升级销毁重建控制器**
   - 原 `RemoteRuntime` 初始化时 `controller` 为 `null`，待 `RemoteWorkbenchRoot` 首帧后的 `LaunchedEffect` 才调用 `restoreSessionIfAvailable()`，导致冷启动先闪现登录加载页。
   - `restoreSessionIfAvailable()` 仅执行静态只读 `OfflineMirrorRepository`（`allowSending = false`）恢复，缺乏自动建链与宽限窗口重试；且根路由直接以 `!workbenchState.isOnline` 驱动离线横幅与 `StreamHealth.FAILED`。
   - 原在线恢复通过 `retire()` + 重建 `WorkbenchController` 完成升级，丢失当前阅读位置、局部 UI 状态与输入上下文。

3. **[P2 Bug #3] 离线提示点击重试踢回登录页**
   - 原 `RemoteWorkbenchRoot` 点击「离线模式 / 重新连接」调用 `runtime.select(it)`，首步即 `retire()` 将 `controller` 置 `null`，导致根路由瞬间退出会话页切回登录页。

---

## 二、客户端系统重构架构

### 1. Host/Agent 与 Workspace 双层状态解耦
- **连接层（`AgentConnectionState` / `AgentConnectionPhase`）**：
  - 定义于 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteConnectionModels.kt`。
  - 使用扁平身份 `AgentKey(origin, accountId, hostId, backendId)` 表达所连接 Agent，独立维护 `hosts`、`agents`、`activeAgent`、`phase`（`DISCONNECTED / CONNECTING / CONNECTED / RECONNECTING / OFFLINE / AUTH_REVOKED`）、`switchingAgent`、`reconnectingInPlace`、`offlineConfirmed` 与 `errorCode`。
- **工作区上下文层（`WorkspaceContextState` / `WorkspaceCatalogPhase`）**：
  - 挂靠在已选 `agentKey` 之下，维护 `workspaces`、`catalogPhase`、`activeWorkspace`、`sessionsByWorkspace` 与 `models`。
  - 对外继续同步派生兼容投影 `RemoteWorkspaceState`，保障现有调用方平稳过渡。
- **差异化切换语义（`RemoteRuntime.kt`）**：
  - `selectAgent(...)`（切换不同 Host 或同 Host 下不同 Backend）：在 `selection` 互斥锁内先切入目标通道，保持旧控制器在位直到新工作区控制器原子替换（绝不先把 `controller` 置 `null` 踢回登录页），已连接态下即时生效不被阻拦。
  - `selectWorkspace(...)`（同一 Agent 下切换工作区）：严格复用现有健康 `RemoteRpcChannel` 与对应的 `RemoteSessionCarrier`，**严禁断开或重建底层通道**；离线状态下切换到同 Agent 的缓存工作区同样就地切换镜像并转为后台静默重连。

### 2. 登录页与会话工作区菜单职责分离与自动水合
- **登录页（`RemoteLoginAgentSelector`）**：在 `RemoteConnectionSettingsScreen.kt` 为登录页已登录态独立提供 `RemoteLoginAgentSelector(runtime)`，仅展示 `Host 主机` 与 `可用 Agent` 列表（单在线 Host 且尚未发现 Agent 时自动发现），彻底移除所有工作区路径与工作区选择。
- **设置页（`RemoteHostAndAgentSettings`）**：与登录页复用共享组件 `RemoteHostAndAgentSections`，按 `Host 主机` → `可用 Agent`（已连接态可即时切换其他 Agent）→ `当前 Agent 工作区`（同 Agent 复用通道切换）三层展示。
- **工作区分组抽屉（`WorkspaceConversationBrowser` / `ThreadDrawer` / `WorkbenchScreen`）**：
  - 在 `WorkbenchScreen` 抽屉打开、宽屏常驻切换 Agent 或恢复在线时自动触发 `workspaceMenu.onHydrate()`；
  - `RemoteRuntime.hydrateWorkspaces()` 自动拉取当前 Agent 下全部工作区及各个工作区的会话列表（预加载本地加密镜像缓存，并对离线未缓存兄弟工作区标记 `Loadable.Empty` 以避免无限加载）；
  - `WorkspaceConversationBrowser` 彻底移除抽屉创建/管理文件夹、文件夹筛选与移动到文件夹操作，替换为按工作区分组的可折叠会话树（支持单工作区折叠/展开、跨工作区直达打开会话、按工作区新建会话），同时完整保留既有排序模式切换（`UPDATED_DESC` / `CUSTOM`）、当前工作区长按拖拽与无障碍上下移排序，以及受 `deleteSupported` 能力开关控制的删除确认弹窗；全部中文文案均引用 `apps/android/ui-resources/src/main/res/values/strings_*.xml`。

### 3. Cache-First 冷启动与宽限窗口原地热升级
- **同步凭据探测（`RemoteAccountRuntime.peekRestorableAccount`）**：不触网同步识别本地有效凭据；`RemoteRuntime` 构造时同步执行 `bootstrapColdStartCacheFirst()`，命中缓存后首帧即同步挂载 `WorkbenchController` 与单个上次激活工作区的本地加密镜像，进入 `AgentConnectionPhase.CONNECTING`（`offlineConfirmed = false`），彻底消除冷启动登录页闪烁。
- **宽限期恢复（`runColdStartGracefulRecovery` & `ConnectionRecoveryPolicy`）**：在展示缓存会话与历史消息的同时后台静默执行建链与宽限期内重试；宽限期内仅在顶栏展示轻量连接状态（`StreamHealth.CONNECTING` / `RECONNECTING`），不弹出离线横幅；仅当宽限期耗尽仍不可达才置位 `offlineConfirmed = true`、`phase = OFFLINE` 并转入指数退避后台静默重连循环。
- **可热升级仓储（`HotSwappableConversationRepository`）与 `WorkbenchController`**：
  - `HotSwappableConversationRepository`、`HotSwappableAttachmentCoordinator`、`HotSwappableCommandCatalogRepository`（支持 `attach` / `detach` 热挂载）与 `HotSwappableHistoricalMediaSource` 支持先由 `OfflineMirrorRepository` 离线提供缓存读，建链成功后通过 `attachOnline(...)` 与 `WorkbenchController.updateRemoteCapabilities(online = true, ...)` 原地升级为在线态并自动同步增量消息，全程保持同一个 `WorkbenchController` 实例不变，保留滚动锚点与用户输入草稿。
  - 修复了当本机存在有效账号但尚未保存上次工作区时，`restoreSessionIfAvailable()` 误跳过 `accountRuntime.restore()` 与 `refreshHostsInternal()` 的问题。

### 4. 离线提示就地重连与凭据撤销分支
- **就地重连（`RemoteRuntime.reconnectInPlace` / `RemoteWorkbenchNotices` / `RemoteWorkbenchRoot`）**：
  - 点击顶部离线卡片「重新连接」不再调用 `select(...)`，改为触发 `runtime.reconnectInPlace()`，原位显示轻量 `CircularProgressIndicator` 与「正在重新连接…」提示，并在 `reconnectingInPlace == true` 时禁用按钮防止重复提交；
  - 成功恢复后通过 `connectAndUpgradeCurrentWorkspaceLocked` 原地升级为 `CONNECTED`，`offlineConfirmed` 与 `reconnectingInPlace` 复位，自动收起横幅；
  - 普通网络异常（含 `TimeoutCancellationException` 映射的 `CONNECT_TIMEOUT`）失败后保持停留在会话页并恢复可重试；
  - 仅当服务端明确返回凭据撤销或认证失效错误码（由 `isAuthRevokedError()` 按分隔符拆词精确匹配 `AUTH_REVOKED_ERROR_CODES`）时，转入 `AUTH_REVOKED` 回退登录页并显示清晰中文说明（`conn_toast_session_revoked`）。

### 5. 独立代码评审后的边界加固与 `dev` 变基兼容
- **热升级媒体与资源句柄**：移除 `retireOnlineHandlesOnly()` 对 `ownerMedia?.retireConnection()` 的误调用，防止原地重连或跨站发现后历史媒体读取抛出 `LOCAL_MEDIA_DELETED`；并在 `retireWorkspaceContextKeepingChannel()` 开头先解绑热升级仓储再关闭通道，对离线/切换期间的原生存储初始化失败增加异常关闭守卫，同时消除 `switchWorkspaceControllerLocked()` 中的重复在线绑定构造并抽取共享 `buildOnlineWorkspaceBinding()` / `buildAssistantWorkspace()` / `beginAccountReset()` helper。
- **超时与取消区分及并发态收敛**：新增 `isTrueCancellation()` 区分 `TimeoutCancellationException` 与真实协程取消；对 `selectAgentLocked()` 跨站切换失败时补齐 `detachOnlineWorkspaceInPlace()` 与原工作区后台重连恢复；为 `channel`、`activeCarriers`、`hydratedAgentForChannel` 添加 `@Volatile`，将 `cachedWorkspacesByAgent` 统一收口到 `stateLock` 保护，并为操作协程加入基于 `switchGeneration` ticket 的 `NonCancellable` 收尾清理；同时为 `HotSwappableConversationRepository.attachOnline()` 的流收集器加入身份锁校验。
- **水合刷新窗口与未缓存空态**：新增 `WORKSPACE_HYDRATION_STALE_WINDOW_MS`（2 秒）刷新窗口并扩展 `WorkbenchScreen` 宽屏自动水合触发依赖；对本地无缓存的兄弟工作区在 `loadCachedWorkspaceSessionsSnapshot()` 中写入 `Loadable.Empty` 以消除离线抽屉转圈。
- **变基最新 `dev`（兼容 PR #209）与竞态隔离**：`HotSwappableCommandCatalogRepository` 完整实现 `AgentCommandCatalogRepository` 的 `get(gatewayId, languageCode)`、`get(gatewayId, languageCode, conversationId)` 与 `observe(gatewayId, conversationId)` 公告流热切换委托；`WorkbenchController.updateRemoteCapabilities` 集成 `onCommandCatalogConnectivityChanged(online)` 避免重复拉取；`discover()` 仅回填自身 `ownDiscoveryError` 并在通道更换/失效关闭时自增 `modelSequence`，消除与后台 `refreshModels()` 的竞态。

### 6. PR #207 自动化评审反馈响应（5 项全部采纳并修复）
- **新工作区就绪前保留旧控制器存活（#4646712815）**：重构 `switchWorkspaceControllerLocked()` 与 `switchOfflineWorkspaceOnlyLocked()`，在目标工作区不共用本地存储路径时先完成全部加密存储、媒体与新控制器构建，就绪后再调用 `retireWorkspaceContextKeepingChannel()` 并原子发布新控制器；异常分支仅清理新上下文半成品句柄；同时在 `onActiveThreadChanged` 中校验 `activeThreadRef === active`，且在 `selectAgentLocked()` 命中相同在线工作区时直接复用现有控制器。
- **跨宿主发现恢复失败调度后台重连（#4646712824）**：在 `discover()` 的 `finally` 中当 `restoredOnline == false` 时显式复位 `reconnectingInPlace = false` 并调用 `startBackgroundReconnectLoop()`，确保原宿主重新上线后自动静默重连。
- **水合沿 `nextCursor` 翻页拉全会话（#4646712834）**：在 `RemoteRuntime.kt` 新增 `fetchAllWorkspaceSessions()` 循坏按 `nextCursor` 分页合并会话（带游标循环保护），使 Codex 等分页后端超过单页上限的会话不再被截断。
- **服务端返回空工作区列表时清空内存与持久化缓存（#4646712839）**：`hydrateWorkspaces()`、`discover()`（无错响应）与 `selectAgentLocked()` 无条件落盘最新工作区清单；`saveAgentWorkspaces()` 为空目录持久化 `"[]"` 并清理该 Agent 遗留的 `"endpoint:*"`、`"agent-last-workspace:*"` 与失效 `"selection:*"` 键，`loadCachedAgentWorkspaces()` 在存在 `"agent-workspaces:*"` 权威 JSON 时直接返回（空数组即返回空集合，不再回退旧端点键值），`updateLayerStates()` 在 `catalogPhase == READY` 时同步清空 `cachedWorkspacesByAgent`。
- **热升级后自动续订已恢复附件观察流（#4646712846）**：`HotSwappableAttachmentCoordinator` 增加 `delegateFlow` 并在 `observe(draftId)` 中通过 `flatMapLatest` 订阅，在离线分支发射可重试状态后使用 `awaitCancellation()` 挂起而非直接完成流；后台热升级 `attach(realAttachments)` 后自动无缝切流并向既有订阅者发布 `VERIFIED`。

---

## 三、涉及改动文件清单

- `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/remote/runtime/RemoteAccountRuntime.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteAccountConnection.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteConnectionModels.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/HotSwappableConversationRepository.kt`
- `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteRuntime.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteConnectionFeedback.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteConnectionSettingsScreen.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteWorkbenchNotices.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/RemoteWorkbenchRoot.kt`
- `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkspaceConversationBrowser.kt`
- `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ThreadDrawer.kt`
- `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt`
- `apps/android/ui-resources/src/main/res/values/strings_remote.xml`
- `apps/android/ui-resources/src/main/res/values/strings_connection.xml`
- `apps/android/ui-resources/src/main/res/values/strings_conversation.xml`
- `apps/android/ci/app-regression.txt`
- `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/RemoteConnectionLifecycleTest.kt`
- `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/RemoteWorkspaceUiDecouplingTest.kt`
- `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/RemoteRuntimeTest.kt`
- `apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/workbench/WorkspaceConversationBrowserTest.kt`

---

## 四、已执行的自动化验证与结果

1. **静态与规划门禁（已通过）**：
   - `python3 tools/check_hardcoded_chinese.py` → 通过（已豁免基线 1457 处，新增违规 0 处）
   - `python3 tools/test_string_resources_integrity.py` → 通过
   - `npm run lock:check` → 通过（96 份原文与许可）
   - `pytest tools/test_ci_planning.py` → 121 passed

2. **受影响 Android 模块全量测试与越权面校验（已通过，全绿 0 跳过，零 CloseGuard 警告）**：
   - 命令：`./apps/android/gradlew -p apps/android noVpnSurfaceCheck :gateway-client:testDebugUnitTest :conversation-domain:testDebugUnitTest :conversation-data:testDebugUnitTest :conversation-ui:testDebugUnitTest`
   - 结果（已于本地离线补装 `tools/remote-interop` 依赖以恢复 `ws` 互通校验）：
     - `noVpnSurfaceCheck`：通过
     - `:gateway-client:testDebugUnitTest`：307/307 通过（含 `OriginalPluginHostInteropTest`）
     - `:conversation-domain:testDebugUnitTest`：77/77 通过
     - `:conversation-data:testDebugUnitTest`：142/142 通过
     - `:conversation-ui:testDebugUnitTest`：1003/1003 通过（含 `WorkspaceConversationBrowserTest` 4/4、`ConversationBrowserTest` 10/10、`ThreadDrawerAccountTest` 2/2、`WorkbenchLayoutRegressionTest` 17/17、`WorkbenchCatalogRefreshTest`、`ApprovalStateMachineTest` 等）

3. **App 连接生命周期、冷启动缓存优先、UI 解耦与核心回归（22 个测试类共 136/136 通过，0 跳过）**：
   - 主体命令：`./apps/android/gradlew -p apps/android :app:testFullDebugUnitTest --rerun --tests com.openandroidintelligence.mobile.RemoteConnectionLifecycleTest ...`（共 21 个顶层包核心回归类：134/134 通过）
   - 独立子包补充：`./apps/android/gradlew -p apps/android :app:testFullDebugUnitTest --tests com.openandroidintelligence.mobile.assistant.AssistantConversationWorkspaceTest`（2/2 通过）
   - PR 评审修复后复测命令：`./apps/android/gradlew -p apps/android :app:testFullDebugUnitTest --rerun --tests com.openandroidintelligence.mobile.RemoteConnectionLifecycleTest --tests com.openandroidintelligence.mobile.RemoteRuntimeTest --tests com.openandroidintelligence.mobile.RemoteWorkspaceUiDecouplingTest --tests com.openandroidintelligence.mobile.RemoteHarnessCatalogTest --tests com.openandroidintelligence.mobile.RemoteSessionDeliveryIntegrationTest --tests com.openandroidintelligence.mobile.RemoteAttachmentMessageRecoveryTest`（全部通过，0 失败 0 跳过，零 CloseGuard 警告）
   - 重点覆盖结果：
     - `RemoteConnectionLifecycleTest`：16/16 通过（在原 11 项基础上新增：同 Agent 切工作区初始化失败保留旧控制器存活、跨宿主发现恢复失败自动触发后台重连并自愈、Codex 沿 `nextCursor` 翻页拉全多页会话、服务端返回空工作区列表清空内存与重启后持久化缓存、离线订阅 `HotSwappableAttachmentCoordinator.observe()` 后热升级自动接收 `VERIFIED`）
     - `RemoteWorkspaceUiDecouplingTest`：4/4 通过（登录页不暴露工作区、设置页已连接切换即时生效、离线横幅原位重连展示与防重入、凭据撤销说明与宽限期横幅隐藏）
     - `RemoteRuntimeTest`：21/21 通过
     - `RemoteHarnessCatalogTest`：3/3 通过
     - `RemoteSessionDeliveryIntegrationTest`：7/7 通过
     - `RemoteAttachmentMessageRecoveryTest`：1/1 通过
     - 前次全受影响 22 类（`4eb26c3a`）中其余 17 类（`RemoteLoginPresentationTest` 9/9、`RemoteRootInteractionTest` 5/5、`BubbleConversationTest` 30/30、`ArchitectureBoundaryTest` 2/2、`ConversationUiCapabilitiesTest` 5/5、`LocalConversationFirstFrameTest` 6/6、`MaterialTokenGuardTest` 2/2、`NativeNotificationNavigationTest` 2/2、`NativeNotificationRoutingTest` 3/3、`RemoteDeviceNameSettingTest` 2/2、`RemoteLocalEntryPointsTest` 4/4、`RemoteNewSessionNavigationTest` 5/5、`SettingsNavigationTest` 2/2、`SettingsResponsiveHostRegressionTest` 5/5、`SettingsStateIntegrityTest` 4/4、`WorkbenchNavigationTest` 2/2、`AssistantConversationWorkspaceTest` 2/2）：均已通过
   - 说明：`Remote*`、`Settings*`、`Bubble*`、`:conversation-*` 与 `:gateway-client` 测试报告中零 CloseGuard 警告；既有 `LocalConversationFirstFrameTest` XML 含既有的 Robolectric `SQLiteOpenHelper` CloseGuard 日志，与本次连接层改造无关。
   - 本地 `npm run test:pr` 说明：除上述已全部单独回归通过的受影响模块外，本地未修改模块的离线环境缺失前提仅出现在 `:plugin-runtime-wasm`（`CompiledReferencePluginTest` 2/3 需预编 Rust 参考插件）与 `:sms-collector`（`AndroidSmsManagerPortTest` 9/9 需离线拉取 Robolectric SDK 31/32/33 jar），相关全矩阵构建交由 GitHub Actions CI 验证。
