# 连接生命周期、Agent/工作区解耦、冷启动缓存优先与就地重连重构报告

关联 Issue：#206

## 一、问题诊断与根因分析

1. **Bug #1（Agent/工作区切换失效、菜单仅见当前工作区、登录页混杂工作区、同 Agent 切工作区断连重连）**
   - `RemoteRuntime` 将 Host、Backend（Agent）与 Workspace 压平为单层 `RemoteWorkspaceState(hosts, endpoints, selected)`。
   - `selectLocked()` 在每次切换工作区时均调用 `retire()` 关闭底层 `RemoteRpcChannel` 并清空 `controller`，再重新通过 `accountRuntime.connect(...)` 建立传输通道，导致同 Agent 下切换工作区产生不必要的断连与重连，并在切换期间使 `controller == null` 触发根路由跳回登录页。
   - `restoreOffline()` 仅回填上次选中的单条工作区，`discover()` 跨宿主后在 `finally` 中重建恢复，导致进入菜单/设置页仅见当前工作区，需要二次点击 Host 才触发加载其余工作区。
   - `RemoteLoginScreen` 复用 `RemoteEndpointList` 将工作区混入登录界面；`ThreadDrawer` / `ConversationBrowser` 仍围绕本地文件夹管理而非当前 Agent 的工作区分类展示。

2. **Bug #2（冷启动闪现登录页、过早弹出离线横幅、升级重建控制器）**
   - `RemoteRuntime` 初始化时 `controller` 为 `null`，待 `RemoteWorkbenchRoot` 首帧后的 `LaunchedEffect` 才调用 `restoreSessionIfAvailable()`，导致冷启动先闪现登录加载页。
   - `restoreSessionIfAvailable()` 只执行只读 `OfflineMirrorRepository`（`allowSending = false`）恢复，未执行自动建链与宽限窗口重试；且根路由直接以 `!workbenchState.isOnline` 驱动离线横幅与 `StreamHealth.FAILED`。
   - 原实现通过 `retire()` + 重建 `WorkbenchController` 完成上线升级，会清空局部界面状态与滚动锚点。

3. **Bug #3（离线提示点击重试踢回登录页）**
   - `RemoteWorkbenchRoot` 点击「离线模式 / 重新连接」调用 `runtime.select(it)`，首步即 `retire()` 将 `controller` 置 `null`，导致根路由瞬间退出工作台切回登录页。

## 二、客户端重构方案

1. **双层状态解耦**：拆分 Host/Agent 连接层（`AgentConnectionState`）与 Workspace 上下文层（`WorkspaceContextState`）。
2. **职责分离与工作区抽屉折叠**：登录页只负责连接与选择 Agent；进入会话后，抽屉打开自动水合当前 Agent 下全部工作区及各工作区会话列表，移除创建文件夹交互并替换为按工作区折叠分类。
3. **Cache-First 与宽限窗口原地热升级**：冷启动同步识别有效本地凭据与工作区缓存，首帧直达会话页；引入 `HotSwappableConversationRepository` 与协同委托在后台静默建链与宽限期重试后原地升级在线态，保持同一个 `WorkbenchController` 引用不变。
4. **就地重连**：点击离线重试停留在会话页并展示轻量重试状态、防止重复触发，仅在明确收到凭据撤销/认证拒绝错误时给出清晰提示并回退登录页。

## 三、验证与回归记录

（待后续 TDD 切片实现后补充真实测试命令与输出摘要）
