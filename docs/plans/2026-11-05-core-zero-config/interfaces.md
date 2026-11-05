# 本次实现共用 Interface（工程内部）

工作目录均为仓库独立 worktree `.worktrees/oai-core-zero-config-admin-auth`，禁止修改主检出 dev。计划见同目录 implementation.zh-CN.md。

## 身份底座

新增 `oai_core.identity.IdentityStore(database: Path, *, key_store=None, create=False)`。禁止构造函数默默创建已缺失安全库；明确 bootstrap/migrate 才 create。默认身份库位置 `storageRoot.parent / 'security' / 'identity.sqlite'`，优先安装实例描述/显式 `identityDatabase`。

需要的公共方法（可补充，但这些保留）：

- `bootstrap(username, password, *, confirmed: bool) -> dict`：仅空安装受保护一次引导，返回 accountId；初始化新账号 role=admin。调用方须真实终端/受控FD并提供限定一次引导上下文，不能把 confirmed 单独当授权。
- `migrate_legacy(storage_root, username, password, *, confirmed: bool) -> dict`：只读扫描旧账号摘要、验证指定账号后原ID导入，其余 user；非空安全库拒绝重复。
- `authenticate(username, password, *, otp=None, recovery_code=None, purpose='login', client_id=None) -> dict`：返回 accountId/username/role/authEpoch/mfaVerified，2FA需挑战时抛 GatewayError，details 只含非秘密 challenge。
- `account(account_id) -> dict`、`resolve_username(username) -> str`、`assert_epoch(account_id, epoch)`、`password_digest(account_id)`；返回值不带秘密。
- `create_account(management, username, password) -> dict`、`list_accounts(management) -> dict`、`reset_password(management, username, password)`、`delete_account(management, username)`、`set_role(management, username, role)`；所有验证管理scope/期限/角色/epoch，普通账号默认user，最后管理员原子保护。
- `begin_management(username, password, *, otp=None, scopes=None) -> str`：随机handle，默认900秒，内存/FD传递；`require_management(handle, scope) -> dict`、`revoke_management(handle)`。
- `set_mfa_required(management, enabled: bool)`；MFA自助 `begin_mfa_enrollment(account_id, proof)`/`confirm_mfa_enrollment(...)`；以安全实现为准，但关着开关不要求OTP。
- `audit(...)`、`close()`。

`oai_core.management` 为 CLI/Shell 提供隐藏输入、FD授权、账号命令与setup/config执行。不允许密码/OTP/管理handle进入argv/env；可传FD编号、非秘密用户名/实例路径。

`oai_core.instance` 提供 `discover_instance(config: Path|None=None) -> dict`：返回 configPath/url/storageRoot/identityDatabase 等非秘密引用，和 `register_instance(...)`。显式配置保留，相对引用；多实例不猜。普通登录首次远端可交互地址或--url。

## Core HTTP/客户端契约（主线程实现）

前缀 `/open-android-intelligence/core/v1`：

- `POST /tokens` 输入 username/password/clientId?；可选 deviceId 仅用于旧设备限定兼容，新的无device登录返回 token/accessToken/expiresAt/refreshCredential/loginSessionId/clientId/accountId；2FA challenge用结构化GatewayError提示，并支持同一登录请求附 otp/recoveryCode（秘密只在TLS正文）。
- `POST /tokens/refresh` 输入 refreshCredential/clientId/operationId；返回同一凭据束，幂等恢复丢响应。
- `DELETE /tokens/current` 撤family；客户端先有本地退出屏障。
- `GET /devices` 返回 devices[{deviceId,displayName,online,pairingGeneration,grantRevision}],defaultDeviceId。
- `PUT /devices/default` 输入 deviceId (null清除)，仅自己账号。
- `GET /capabilities?deviceId=...` 可选；返回 selectedDevice 和 capabilities。
- `POST /device-requests` 保留 capabilityId/capabilityVersion/parameters/caller，新增可选 deviceId、originContext；幂等header不变。originContext必须受验证，不接受裸sessionId当身份。
- get/cancel/ack按已受理目标，不能按当前默认选。

`Principal` 保留旧字段兼容，新增 `login_session_id/client_id/auth_epoch`；无设备账号Principal不受配对失效影响。Android签名Principal继续有context，不能伪造。

## Android 账号认证扩展（Core 与 Android）

协商请求与响应均须有单值 `X-OAI-Account-Auth: v1` 才启用本扩展；缺失、未知值不探测新路径。旧 v2 封闭 schema 与旧 Gateway 的密码、邀请、设备密钥、刷新/退出路径不变。Core 必须只在完整实现并实际执行当前服务端 2FA 策略后确认该能力；旧入口不得绕过已开启的策略。

- `POST /open-android-intelligence/v2/sessions/password/challenge`：请求 `{negotiationId,username,password,installation:{installationId,displayName,devicePublicKey},accountId?,deviceId?}`；仅本机有旧绑定时同时提供 `accountId` 和 `deviceId`。响应标准 v2 envelope 的 `data` 为 `{challengeId,accountId,negotiationId,installationId,deviceId?,purpose:'android-login',expiresAt,nonce,methods:[]|['totp','recovery-code'],enrollmentRequired:boolean}`。`deviceId` 仅旧设备复用时返回；必须与请求旧绑定一致。挑战在服务端绑定实例、账号、安装、协商、用途、期限及策略/账号版本；密码校验失败不签发。
- 客户端验证所有字段、作用域和期限，对响应 `data` 的全部字段按 JCS 规范序列化，用本设备私钥签名 UTF-8 `OPEN_ANDROID_INTELLIGENCE_ACCOUNT_LOGIN_V1\n` 与序列化结果。`POST /open-android-intelligence/v2/sessions/password/complete` 请求 `{challengeId,negotiationId,signature,otp?,recoveryCode?}`，成功返回旧 `SessionCredentials` envelope。首次登记证明新私钥，复用证明旧私钥；签名/账号/安装/策略校验与挑战消费必须在签发事务中完成，不得凭自报 ID 或公钥复用设备。
- 服务端关闭 2FA 时 `methods=[]` 且 `enrollmentRequired=false`，直接完成，不显示验证码。开启时已绑定返回 `['totp','recovery-code']` 并要求其一；未绑定返回 `enrollmentRequired=true`，客户端在受保护界面进行绑定。
- 未绑定：`POST /open-android-intelligence/v2/sessions/mfa/enroll` 请求 `{challengeId}`，返回 `data:{enrollmentId,provisioningUri,secret?}`；`POST /open-android-intelligence/v2/sessions/mfa/confirm` 请求 `{challengeId,enrollmentId,otp}`，返回 `data:{recoveryCodes:[...]}`。客户端展示恢复码并等待本人确认已保存，再调用 `complete`（无需再次提交已消耗 OTP）。绑定秘密、恢复码及输入不落普通存储或日志，取消、切号和过期清理内存；服务端必须限制重放与挑战权限。
- Android 刷新沿用原 journal，仅在协商返回旧 `X-Open-Android-Intelligence-Refresh-Replay: v1` 且 Core 真正支持幂等回放后启用；不能因本扩展头而推定回放能力。

## Agent

新 `agent_registration.py` 管理同用户有限候选发现、稳定实例、确认关联；提供可供 `sessions.build_registry` 使用的发现/关联读取，不修改 auth/device_service/cli/server/gateway/core（由主线程整合）。Hermes accounts旧映射仍可导入，未关联不能猜同名身份；可信新空间可自动准备。不能给共享宿主注入个人长期凭据。

## 目录所有权

- 身份Agent：新增 identity.py/mfa.py/management.py/instance.py及自身新tests，不改既有源码。
- 脚本Agent：scripts四入口/配置事务归包及相关tests；通过上方Interface接入，不改身份或CLI。
- 客户端Agent：client.py/cli.py/mcp.py及对应tests；不要改server/auth/identity。
- Agent接入Agent：agent_registration.py、sessions.py、adapters、integrations及对应tests；不要改认证/设备/CLI。
- 主线程：auth.py/server.py/device_service.py/routing.py/endpoints.py/gateway.core接线与跨模块整合，Android/协议另行分工。
