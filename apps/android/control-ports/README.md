# Android control ports (source-only contract)

This source-only module defines the high-risk command Interface without implementing an
Android provider or executor. Typed writes, semantic screen actions and a
small set of reviewed restricted templates all require a local grant, exact
revision/generation fencing, an unexpired parameter-bound confirmation and a
server-issued operation identity. The ports fail closed; they do not run a
process, accept a command string or expose a generic automation surface.

The source is intentionally independent of platform services. `screen-control`
now implements the native screen backend and `device-primitives` connects it
to the existing execution ledger. `ScreenLocalAuthorization` retains legacy
local UI sessions; request authorization comes from the current global policy
through a trusted driver callback. Neither grants Android system permissions.
The host integration lives behind `ScreenHostRegistry.current`; missing trusted
request authorization or required system permission keeps access unavailable.

```sh
python3 -m unittest apps/android/tools/test_control_ports_static.py
```

Passing this check proves only the closed source boundary, not a working
screen controller or background command executor.

See the [screen Interface](../../../docs/guides/android-screen-interfaces.zh-CN.md)
and [frontend handoff](../../../docs/guides/android-screen-frontend-handoff.zh-CN.md)
for the implementation, authorization order and remaining device validation.

屏幕请求由 driver 注入当前全局策略裁决。账号与配对只校验请求归属；旧
`ScreenLocalAuthorization` 仅为旧本地界面保留，不参与新请求的能力裁决。
`READ`、`CAPTURE`、`CONTROL` 分开安装，请勿因截图或控制而追加未获批准的权限。
Accessibility、锁屏与 MediaProjection 检查仍独立执行，不由 OAI 许可替代。

每条已认证 Core 连接由其拥有者注册一次并保存返回句柄：

```kotlin
val binding = screenHost.registerConnection(accountId, pairingId, pairingGeneration) {
    currentLocalPolicyRevision()
}

// Execute only after the current global policy allows this request.
screenHost.installRequestAuthorization(
    requestId, setOf(ScreenPermission.READ), expiresAtEpochMs, parameterDigest, binding,
) {
    requireCurrentGlobalPolicyAndConnection()
}
```

这些调用只接受可信进程内接线，不能注册成远程能力。`registerConnection` 返回的
本机连接代次区分不同 Core，即使其账号、pairing 和服务端代次字符串完全相同。
每次请求固定快照本次策略版本，执行前和交付数据前都重验回调与请求期限。后续
新请求可复用注册句柄，读取新的策略版本，无须重新打开 OAI 屏幕会话。
`installControlAuthorization` 是仅安装 CONTROL 的兼容包装；无 binding 的旧安装入口
只在连接唯一时兼容，多连接返回 `SCREEN_CONNECTION_BINDING_REQUIRED`。

`selectConnection(binding)` 只选择当前聊天的界面展示；不取消其他连接的请求。
旧 `connect` 也只注册并选择当前连接，不断开后台连接。连接断开/退出时必须调用
`disconnectConnection(binding)`，仅取消本连接请求、控制租约和截图句柄；旧
`disconnect()` / `connectionChanged(false)` 只作用于当前选择连接。真正退出全部
账号调用 `disconnectAll()`；用户紧急停止调用 `revoke()` 撤销所有已批准请求。
除全局紧急停止或系统撤销外，一条连接断开不会停止另一条连接的系统截图源。

截图分块读取与释放属于 CAPTURE，每次调用均需新的 CAPTURE 请求裁决和参数摘要。
句柄最多存活 60 秒且受原捕获请求期限限制，完成原捕获请求后可由同一已认证连接
的新请求读取；其他账号、Core 或连接代次不能读取，连接失效时按连接清空像素。
它不依赖旧 OAI 屏幕会话，也不授予 READ 或 CONTROL。

屏幕控制独占租约使用 `ScreenHostRegistry.controlLeases`，同一 App 进程中所有合法
Core、账号和 Agent 共用 FIFO 队列。`RemoteScreenRuntime` 在控制分支内部进入队列，
读取和截图不占控制锁。排到队首和最终系统动作前重验本次许可、回调和屏幕状态。
`AndroidScreenBackend.execute` 缺少协程租约时返回 `SCREEN_CONTROL_LEASE_REQUIRED`。

初始租约 30 秒，从取得控制权起累计最多 5 分钟，并且不超过请求期限。当前点击、
滑动、输入、切换应用均为有限单次操作，driver 不需要启动周期 heartbeat。只有
后续真实持续操作需要续租，且可信 driver 刚确认连接仍存活及策略仍允许时，才调用
`heartbeatControl(requestId, binding)`；每次最多延长至当前时刻后 30 秒，不能固定延长 5 分钟，
不能续已过期、排队或已完成请求。Runtime 不自动发送 heartbeat。

等待最多 5 分钟且受请求期限限制，同时最多登记 64 个请求；完成、取消与超时
释放租约。锁只会在执行协程真正退出后交给下一项。已提交的手势取消时等待系统
回调；回调缺失时清理最多等待手势时长加 1.5 秒，按结果未知处理，不重试。
时长使用单调时间源并检查绝对请求期限及墙钟经过时间，不创建调度界面。

请求内部键包含本机连接代次、账号、pairing、配对代次与原始 requestId；控制租约
也使用连接命名空间 ID，截图句柄继续按已认证连接 scope 隔离。driver 的生命周期
必须使用 `finishRequest(requestId, binding)`、`cancelRequest(requestId, binding)` 和
`heartbeatControl(requestId, binding)`；无 binding 的三个旧入口统一拒绝，避免旧
连接迟到的清理影响另一连接。保存句柄中的旧策略版本仍可用于精确清理所属连接。

目前 `LocalGrantContext` 未携带本机连接代次，不能无歧义识别同时存在的跨 Core
同原始 ID 调用。安装许可时在同一锁内保留原始 ID 的唯一归属：另一连接同时安装
相同原始 ID 返回 `SCREEN_REQUEST_ID_COLLISION`，不会覆盖原批准。取消或结束后，
若执行尚未清理退出，保留占用直到任务退出；之后其他连接才能使用该 ID。此限制
是明确拒绝冲突，不宣称已支持跨 Core 同 ID 并发执行。未来放开需要把本机连接代次
传入内核上下文，不得仅用不可信原始 ID 猜测归属。

这些 JVM 单元测试验证策略接线、连接隔离、队列与撤销边界；不代表 Android
Accessibility、MediaProjection 或实体手机验收。
