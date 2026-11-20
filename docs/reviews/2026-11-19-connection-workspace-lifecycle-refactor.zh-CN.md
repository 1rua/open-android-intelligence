# 连接生命周期、Agent/工作区解耦、冷启动缓存优先与就地重连重构报告

关联 Issue：#206  
关联 PR：#207  
范围约束：仅修改 Android 客户端（`apps/android/`）与设计文档（`docs/`），严格未触碰任何上游服务端代码。

---

## 一、问题诊断与根因分析

1. **[P1 Bug #1] Agent / 工作区切换失效、菜单仅见当前工作区、登录页混杂工作区、同 Agent 切工作区断连重连**
   - 原 `RemoteRuntime` 将 Host、Backend（Agent）与 Workspace 压平为单层 `RemoteWorkspaceState(hosts, endpoints, selected)`。
   - 原 `selectLocked()` 在每次切换工作区时均调用 `retire()` 关闭底层 `RemoteRpcChannel` 并清空 `controller`，再重新通过 `accountRuntime.connect(...)` 建立传输通道，导致同一 Agent 下切换工作区产生不必要的断连与重连，并在切换窗口期使 `controller == null` 触发根路由跳回登录页。
   - 原 `restoreOffline()` 仅回填上次选中的单条工作区，`discover()` 跨宿主后在 `finally` 中重建恢复，导致进入菜单/设置页仅见当前工作区，需要二次点击 Host 才触发加载其余工作区。
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
- **设置页（`RemoteHostAndAgentSettings`）**：按 `Host 主机` → `可用 Agent`（已连接态可即时切换其他 Agent）→ `当前 Agent 工作区`（同 Agent 复用通道切换）三层展示。
- **工作区分组抽屉（`WorkspaceConversationBrowser` / `ThreadDrawer` / `WorkbenchScreen`）**：
  - 在 `WorkbenchScreen` 抽屉打开或宽屏展示时自动触发 `workspaceMenu.onHydrate()`；
  - `RemoteRuntime.hydrateWorkspaces()` 自动拉取当前 Agent 下全部工作区及各个工作区的会话列表（并预加载本地加密镜像缓存）；
  - `WorkspaceConversationBrowser` 彻底移除抽屉创建/管理文件夹、文件夹筛选与移动到文件夹操作，替换为按工作区分组的可折叠会话树（支持单工作区折叠/展开、跨工作区直达打开会话及按工作区新建会话），全部新增中文文案统一纳入 `apps/android/ui-resources/src/main/res/values/strings_*.xml`。

### 3. Cache-First 冷启动与宽限窗口原地热升级
- **同步凭据探测（`RemoteAccountRuntime.peekRestorableAccount`）**：不触网同步识别本地有效凭据；`RemoteRuntime` 构造时同步执行 `bootstrapColdStartCacheFirst()`，命中缓存后首帧即同步挂载 `WorkbenchController` 与本地加密镜像，进入 `AgentConnectionPhase.CONNECTING`（`offlineConfirmed = false`）。
- **宽限期恢复（`runColdStartGracefulRecovery` & `ConnectionRecoveryPolicy`）**：在展示缓存会话与历史消息的同时后台静默执行建链与宽限期内重试；宽限期内仅在顶栏展示轻量连接状态（`StreamHealth.CONNECTING` / `RECONNECTING`），不弹出离线横幅；仅当宽限期耗尽仍不可达才置位 `offlineConfirmed = true`、`phase = OFFLINE` 并转入指数退避后台静默重连循环。
- **可热升级仓储（`HotSwappableConversationRepository`）与 `WorkbenchController`**：
  - `HotSwappableConversationRepository`、`HotSwappableAttachmentCoordinator`、`HotSwappableCommandCatalogRepository` 与 `HotSwappableHistoricalMediaSource` 支持先由 `OfflineMirrorRepository` 离线提供缓存读，建链成功后通过 `attachOnline(...)` 与 `WorkbenchController.updateRemoteCapabilities(online = true, ...)` 原地升级为在线态并自动同步增量消息，全程保持同一个 `WorkbenchController` 实例不变，保留滚动锚点与用户输入草稿。
  - 修复了当本机存在有效账号但尚未保存上次工作区时，`restoreSessionIfAvailable()` 误跳过 `accountRuntime.restore()` 与 `refreshHostsInternal()` 的问题。

### 4. 离线提示就地重连与凭据撤销分支
- **就地重连（`RemoteRuntime.reconnectInPlace` / `RemoteWorkbenchNotices` / `RemoteWorkbenchRoot`）**：
  - 点击顶部离线卡片「重新连接」不再调用 `select(...)`，改为触发 `runtime.reconnectInPlace()`，原位显示轻量 `CircularProgressIndicator` 与「正在重新连接…」提示，并在 `reconnectingInPlace == true` 时禁用按钮防止重复提交；
  - 成功恢复后通过 `connectAndUpgradeCurrentWorkspaceLocked` 原地升级为 `CONNECTED`，`offlineConfirmed` 复位，自动收起横幅；
  - 普通网络异常（如 `NETWORK_UNREACHABLE`）失败后保持停留在会话页并恢复可重试；
  - 仅当服务端明确返回凭据撤销或认证失效错误码（由 `isAuthRevokedError()` 判定，例如 `DEVICE_REVOKED`、`SESSION_REVOKED`、`ACCOUNT_AUTH_REQUIRED`、`UNAUTHORIZED` 等）时，转入 `AUTH_REVOKED` 回退登录页并显示清晰中文说明（`conn_toast_session_revoked`）。

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

1. **静态门禁（已通过）**：
   - `python3 tools/check_hardcoded_chinese.py` → 通过（新增违规 0 处）
   - `python3 tools/test_string_resources_integrity.py` → 通过

2. **Conversation UI 工作区分组与既有抽屉回归（已通过，全绿 0 跳过）**：
   - 命令：`./apps/android/gradlew -p apps/android :conversation-ui:testDebugUnitTest --tests "com.openandroidintelligence.conversation.workbench.WorkspaceConversationBrowserTest" --tests "com.openandroidintelligence.conversation.workbench.ConversationBrowserTest" --tests "com.openandroidintelligence.conversation.workbench.ThreadDrawerAccountTest" --tests "com.openandroidintelligence.conversation.workbench.WorkbenchLayoutRegressionTest"`
   - 结果：
     - `WorkspaceConversationBrowserTest`：3/3 通过
     - `ConversationBrowserTest`：10/10 通过
     - `ThreadDrawerAccountTest`：2/2 通过
     - `WorkbenchLayoutRegressionTest`：17/17 通过

3. **App 连接生命周期、冷启动缓存优先、UI 解耦与既有回归（已通过，全绿 0 跳过，零 CloseGuard 警告）**：
   - 命令：`./apps/android/gradlew -p apps/android :app:testFullDebugUnitTest --tests "com.openandroidintelligence.mobile.RemoteConnectionLifecycleTest" --tests "com.openandroidintelligence.mobile.RemoteWorkspaceUiDecouplingTest" --tests "com.openandroidintelligence.mobile.RemoteRuntimeTest" --tests "com.openandroidintelligence.mobile.RemoteLoginPresentationTest" --tests "com.openandroidintelligence.mobile.RemoteRootInteractionTest" --tests "com.openandroidintelligence.mobile.SettingsNavigationTest" --tests "com.openandroidintelligence.mobile.MaterialTokenGuardTest"`
   - 结果：
     - `RemoteConnectionLifecycleTest`：8/8 通过（完整覆盖已连接切换不同 Agent、同 Agent 切工作区复用通道、打开菜单自动拉取工作区与会话、冷启动首帧直达会话与宽限期不弹离线、瞬时抖动宽限期内原地热升级同步增量消息、宽限期耗尽降级与后台静默重连升级、离线重试防重入且保持会话路由、明确凭据撤销 `DEVICE_REVOKED` 回退登录）
     - `RemoteWorkspaceUiDecouplingTest`：4/4 通过（登录页不暴露工作区、设置页已连接切换即时生效、离线横幅原位重连展示与防重入、凭据撤销说明与宽限期横幅隐藏）
     - `RemoteRuntimeTest`：21/21 通过
     - `RemoteLoginPresentationTest`：9/9 通过
     - `RemoteRootInteractionTest`：5/5 通过
     - `SettingsNavigationTest`：2/2 通过
     - `MaterialTokenGuardTest`：2/2 通过
