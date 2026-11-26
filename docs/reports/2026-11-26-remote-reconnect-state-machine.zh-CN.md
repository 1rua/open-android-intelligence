# Remote 断线自动重连状态机实现报告

关联 Issue：#244

## 问题

Server（Relay/Control 服务或远端 Host）断开并重新上线后，Remote Client 可能停留在断连或离线状态，
必须冷启动 App 才能恢复。该问题同时影响 OAI-Remote-Client 的控制面、当前 Agent 的数据面，以及
OAI-Virtual-Host 的在线注册与心跳恢复。

## 实现范围

- 为 Client、Virtual Host 与当前数据面建立独立、可恢复的重连生命周期。
- 使用带抖动的指数退避，并支持网络恢复、前台恢复、Host Presence 上线和手动刷新即时唤醒。
- 每次恢复完整退役旧 WebSocket、Relay/WebRTC、Noise 与 connectionId，再执行全新握手。
- 数据面恢复后保留当前工作台，重新读取目录、工作区与当前会话时间线。

## 关键修复

- 统一使用 `RemoteReconnectPolicy`：1、2、4、8、16、30 秒指数退避，±20% 对称抖动；连接成功后从零重置。
- OAI-Remote-Client 与 OAI-Virtual-Host 各自持有独立 `SupervisorJob`。旧服务关闭、清理或未知内部异常只结束当前尝试，不再永久终止保持循环。
- 只有明确授权、设备替代、身份或加密终态停止重连；未知清理/传输错误继续退避恢复。
- 数据通道终止按 `RemoteRpcConnectionScope` 精确匹配。迟到旧连接关闭不会拆毁新连接，恢复时强制关闭旧通道并创建新 connectionId/Noise 会话。
- Client 控制面每次完成 `hello.ack` 后递增连接代际并刷新 Host 列表；当前 Host Presence 恢复、控制代际变化或前台恢复均可打断数据面长退避。
- Virtual Host 使用持久设备身份重新上线，恢复控制 WS 与服务端心跳；手动刷新只重建 Client，不影响手机 Host 的在线状态。
- 会话列表顶部“刷新会话”按钮在 Remote 页执行 Client 控制面与当前数据面的完整干净重建；强制刷新会中断普通重连并先关闭旧 Client 保持器/WS，确认旧代际退出后再建立新连接。无回调场景保持原目录刷新语义。
- 抖动结果始终钳制在 1～30 秒硬边界内；因此首个负抖动与上限正抖动会落在边界值，而非突破用户指定范围。

## 验证

### VERIFIED

- Android 编译：`:gateway-client:compileDebugKotlin`、`:app:compileFullDebugKotlin`、`:conversation-ui:compileDebugKotlin`。
- `RemoteConnectionLifecycleTest`：28 项通过，0 失败；覆盖数据通道恢复、Presence 唤醒、控制代际、前台打断退避、手动全链路刷新及原有恢复场景。
- `RemoteClientControlLifecycleTest` 与 `RemoteReconnectPolicyTest`：通过；覆盖 Client/Virtual Host 清理异常恢复、重复唤醒合并、控制恢复刷新 Presence、Host 持久身份重连与退避边界。
- `WorkspaceConversationBrowserTest`：6 项通过，0 失败；覆盖强制刷新回调存在与缺失两种按钮语义。
- `npm run test:fast` 通过，包括固定来源完整性、硬编码中文扫描、MCP 单测、CI 门禁自测、ACP 风险观察、固定 Server 互通和 `noVpnSurfaceCheck`。
- `npm run test:pr` 的静态门禁、MCP 互通、ACP 风险观察、固定 Server 与 Android 模块级 PR 测试通过；JDK 21 下 App 全量因仓库未提供内置插件编译产物而出现既有文件缺失，未计入本次代码失败。

### UNVERIFIED

- 尚未在物理 Android 设备上执行真实服务器进程重启、真实 Host 进程重启、Wi-Fi/蜂窝切换与后台长时间保持验收。
- LAN、P2P、TURN、Relay 四路径的本次重连行为未逐一做实网验证；本地自动化仅证明状态机、生命周期和受控 WebSocket 场景。
- PR 的最终同 SHA 远端 CI 与独立双轴评审结果以 PR #245 最新状态为准。
