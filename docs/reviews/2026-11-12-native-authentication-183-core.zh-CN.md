# OAI Core 原生认证错误分类修复

关联 OAI #183 / PR184。基线为 `7c599ab0d8b85cc6f68a7b5fd15c82bf2aea249d`。本修复只修改 OAI Core 认证与原生会话路由，不修改 Android、DSH Adapter、设备授权或真实服务数据。

## 已确认根因

`Authentication.assert_current()` 原来把 `core.is_event_session_active()` 返回的所有 `False` 都转换为 `SESSION_REVOKED`。Core 的事件会话检查实际可以区分 `SESSION_EXPIRED`，并可能抛出 `AUTHENTICATION_STORAGE_UNAVAILABLE` 或 `AUTHENTICATION_STORAGE_FAILED`。服务器的 `is_active()` 又捕获所有 `GatewayError` 并返回 `False`，因此已经准备好的原生 SSE 在第二次活动检查时统一写成 `SESSION_REVOKED`。

Android 收到已经准备好的 SSE `error` 后由 `AgentSessionClient` 转成异常；仓储的既有恢复流程具有认证阻塞与凭据代次门禁，但发送异常入口尚未把本次认证终帧移交该恢复流程。收到撤销错误后，已受理的 turn 会走普通失败分支，可能留下 `FAILED + queueUnknownBarrier + queued successor` 且没有恢复 owner。该现场与本次最终设备验收中精确观察到的 `SESSION_REVOKED` 一致，但不能单凭现场证明它原本一定是过期或存储故障；真实撤销仍应拒绝。

## 最小实现

- `auth.py`：在有 `principal.context` 时直接读取 `event_session_failure()`，保留稳定错误码；不绕过 pairing、epoch、token 或登录族校验。
- `server.py`：原生 Agent 路由的 `is_active()` 不再吞掉 `GatewayError`，让活动检查把真实认证错误交给上层。
- `sessions.py`：受理等待期间将准确错误写入 operation receipt；SSE 已准备后用同一个错误码写单一 `error` 终帧，不写 JSON、不伪造撤销。
- 新增 `test_native_authentication_failures.py`：覆盖过期、撤销、SQLite BUSY、SQLite ERROR，在已准备 SSE 和受理等待两个边界验证主机调用次数、错误码与敏感信息不泄漏。

## 验证

- 新增正式回归：修复工作树 `8/8` 通过；基线旧源码受控红环为过期/存储不可用分类失败，真实撤销对照保持通过。
- Core 相关回归：新类连同 `test_authentication_storage.py`、`test_native_operation_recovery.py`、`test_native_transport_lifecycle.py`、`test_sessions.py`、`test_core_transport.py` 共 `104` 项通过；`test_account_refresh.py`、`test_android_account_auth.py`、`test_account_core_integration.py` 另为 `26` 项通过。两轮共 `130` 项；新类单独的8项已包含在104中，不重复计数。
- 受控红绿仅使用合成账号、真实 Core HTTP/原生 SSE 和临时 SQLite 状态，不连接设备、不读取凭据或私人会话、不发送生产消息。

## 边界

真实撤销仍返回 `SESSION_REVOKED`；存储不可用仍返回 `AUTHENTICATION_STORAGE_UNAVAILABLE` 或 `AUTHENTICATION_STORAGE_FAILED`，由 HTTP 层既有状态映射处理。该修复不把任何认证失败加入 Android 的无条件重试名单；Android 仍需凭据代次变化后才按原身份恢复。最终 Android/DSH 现场需在 PR 合并后单独复验。
