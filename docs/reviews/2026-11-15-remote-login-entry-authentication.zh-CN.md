# 官方 Remote 登录入口与认证证据

关联：PR #190、issue #194。本文只记录官方服务现有能力、本仓库接入边界与验收缺口，不改变上游或维护分叉。

## 事实来源

2026-11-15 匿名读取官方 `https://dsh.r2049.cn/api/v1/auth/oauth/providers`，实际响应包含：

```json
{"capabilities":{"devicePairing":"dsh-pairing-v1","devicePairingQr":"fragment-ticket-v1"},"items":[{"id":"zhihu","displayName":"知乎","startUrl":"/api/v1/auth/oauth/start","qrStartUrl":"/api/v1/auth/oauth/qr/start?provider=zhihu"},{"id":"github","displayName":"GitHub","startUrl":"/api/v1/auth/oauth/github/start","qrStartUrl":"/api/v1/auth/oauth/qr/start?provider=github"},{"id":"google","displayName":"Google","startUrl":"/api/v1/auth/oauth/google/start","qrStartUrl":"/api/v1/auth/oauth/qr/start?provider=google"}]}
```

公开源码核对基点为 `liguobao/ds-harness-remote@25a81da11b667be8f61a8d3f676615d7ff38fd18`；此次仅调查，没有修改上游。该快照的以下文件定义了接入协议：

- `apps/android/src/services/login/oauth-redirect-channel.ts`：匿名发现 provider，打开它实际返回的 `startUrl`，`return_to` 使用 `dshremote://oauth`；收到 account token 后请求 `GET /api/v1/auth/me`，再注册设备。
- `apps/android/src/services/api.ts` 与 `docs/plugin-integration.md`：provider 入口须为同源 `/api/v1/auth/` 相对路径；账号 token 不具有 refresh 接口；设备 token 沿用现有 refresh 流程。
- `docs/device-pairing-v1.md`、`docs/dsh-go-pairing-integration.md`、`packages/plugin/src/pairing-contract.ts`：扫码设备配对、手机与电脑职责、响应结构、安全校验和恢复窗口。
- `fixtures/pairing/v1/proof.json`：公开 X25519/HKDF/HMAC 固定向量，作为本仓库手机证明的互操作检查材料。

原有 Remote 核心固定来源仍为 `ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`；本次新增的设备配对扩展以以上公开协议和官方实际能力发现结果为依据，不将旧来源清单伪称为包含这份新协议。

## OAuth 接入

1. 点击 GitHub、知乎或 Google 时重新匿名发现 provider，使用服务端返回的入口，不根据按钮名称猜地址。自部署保持邮箱/密码入口。
2. 在 Android Keystore 保护的既有 `RemoteCredentialVault` 中保存 256 位随机 state、provider、十分钟有效期和授权代次。将 state/provider 放进官方允许的 `dshremote://oauth` 回调查询参数，再通过系统浏览器打开授权入口。
3. 独立回调 Activity 严格核对 scheme、host、空路径、无用户信息/端口/fragment、查询键唯一、state/provider 匹配、票据未过期及授权代次未失效。不存在本机发起票据的回调直接拒绝。非法回调不能抢占合法尝试。
4. 先以原子写盘清空票据，才把 account token 留在内存请求 `auth/me`，复用既有 Client/Host 独立身份注册、加密保存、写后读回、token 刷新及连接链路。重复回调不能再次注册。消费后进程退出须重新登录，不能恢复一个未经本机验证的任意 token。
5. 取消先提交并读回独立非秘密撤销待办位，再清理加密票据；清理失败也不能在重启后恢复旧尝试，界面明确显示取消未完成。票据准备、claim写盘、状态发布和取消共用同一原子边界核对代次，防止后台旧任务在取消后重写票据。退出及切换登录方式同样失效本地操作代次。无系统浏览器、授权错误、失效票据及恢复失败均显示可重新开始的失败状态。

无真实账号的受控检查只创建本次浏览器授权 state，然后回传 `error=access_denied`，没有登录或修改任何用户账号。GitHub、Google 错误分支实测保留 `dshremote://oauth?state=…&provider=…&error=…`；知乎错误分支回到 `/app?error=oauth_failed`，因此 App 保留明确取消入口。三种提供商的真实成功授权回跳仍须人工验收。

上游公开接口没有提供 PKCE 或本 App 独占的一次性授权码交换接口。固定 `dshremote` scheme 可能同时被官方客户端或其他 App 注册；本次 state/provider 校验防止无本机尝试的回调注入，不能防止其他 App 抢接 scheme、拦截 bearer token，也不能宣称 Android 已验证该回调域名的所有权。没有改用上游未允许的 scheme，也没有猜测 PKCE 参数。

## 无账号扫码配对

使用 JourneyApps ZXing Android Embedded `4.3.0` 的原生扫码 Activity；没有 WebView 登录页、第三方短链或外部扫码服务。

- 短二维码严格为 `https://dsh.r2049.cn/pair#v1.<43字符canonical-base64url-ticket>`，不接受外域、明文协议、userinfo、query 或其他路径。手机先确认服务 origin，才以无 Bearer/Cookie 的 POST 正文调用 `device-pairing/resolve`。旧 JSON 格式按同一官方 origin、版本、字段、有效期和电脑身份校验；不允许二维码切换账号服务。
- 用户核对电脑名称和完整公钥指纹后，手机生成独立 X25519 身份，先加密保存身份及准确 claim 正文字节，再提交 claim。证明复用既有 Noise 密码提供者，实现协议规定的 X25519、HKDF-SHA256 和实际发送字节的 HMAC；不是自行设计新密码协议。
- 手机安全对话框显示保留前导零的八位验证码；明确只有电脑本机 Remote 插件能够人工输入确认，错误验证码只有一次机会。没有复制/分享/聊天/通知验证码入口；扫码 Activity 和验证码对话框使用 `FLAG_SECURE` 保护。
- 手机仅调用 claim、mobile/status、mobile/cancel；后台暂停轮询，回前台恢复时核对代次，重启恢复复用原设备密钥和 claim 字节。不会调用 host/confirm 或替电脑批准。
- 完成后核对会话、电脑 ID/公钥、手机 ID/公钥/角色、账号类型、权限和 token 有效期，再导入手机 Client 凭据及 Host 公钥 pin，沿用既有加密仓库的写盘读回确认后发布账号状态，再连接本次二维码指定的电脑。旧结果恢复不能覆盖已经刷新的设备 token。凭据导入完成与后续目录/电脑连接分阶段处理：保存成功即清理临时配对，后续网络失败保留已登录账号并显示连接错误；重启沿正常凭据恢复，不再消费旧配对结果。
- 上游配对只颁发手机 Client 凭据；受限设备没有账号级派生 Host 权限。本仓库不冒充双角色成功，不向上游请求不允许的账号注册。手机受控连接保持“未启动”，正常 OAuth/邮箱登录继续独立注册两个角色。

## 验证层级

| 项目 | 状态 | 证据或限制 |
| --- | --- | --- |
| 官方三个 provider 和配对能力发现 | VERIFIED | 2026-11-15 匿名实际 HTTP 响应 |
| GitHub/Google 带本机 state/provider 的错误回跳 | VERIFIED | 本次自行创建 state 的无账号取消检查 |
| 知乎取消回 App | BLOCKED | 官方错误分支返回 `/app`；App 提供本机取消恢复 |
| 本仓库协议与凭据导入测试 | UNVERIFIED | 测试源码已添加，执行结果由统一验证阶段填写 |
| 三家真实 OAuth 成功、浏览器选择及 scheme 争用 | UNVERIFIED | 未操作用户账号，未做真实设备验收 |
| 真机相机扫码、电脑人工确认和自动连接 | UNVERIFIED | 未启动模拟器、ADB，未操作真实 Host |
| PKCE/独占回调所有权保护 | BLOCKED | 上游未提供对应公开协议，本仓库没有伪造能力 |

必要回归包括：票据重启、单次消费、取消/过期/迟到回调、provider 错配、恶意回调 URI、入口越界、扫码格式/重复属性、固定证明向量、准确 claim 字节恢复、Host 身份替换、Client 独立保存、无 Host 派生、写盘失败不发布成功、退出后迟到结果拒绝、已刷新的 token 不被旧配对结果覆盖。
