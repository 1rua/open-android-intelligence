# USB 真机连接与新建会话故障追踪

调查日期：2026-11-09，时间为 Asia/Shanghai（UTC+8）。本报告补充[前次源码调查](2026-11-09-lan-connection-session-creation-investigation.zh-CN.md)，将真机已确认事实、受控反例与未闭合问题分开。

关联 issue：[连接与恢复 #104](https://github.com/1rua/open-android-intelligence/issues/104)、[新建会话 #105](https://github.com/1rua/open-android-intelligence/issues/105)、[数据库写锁误报认证失败 #106](https://github.com/1rua/open-android-intelligence/issues/106)。

## 采集范围与版本

用户通过 USB 连接三星 SM-S9380 并手动复现。Android 16/API 36，App 包名 `com.openandroidintelligence.mobile`，versionName `2.2.1-dev`，versionCode `8871`，安装更新时间 04:54:42。

只读提取安装 APK 后，直接解析 DEX 中 `com.openandroidintelligence.mobile.BuildConfig.GIT_COMMIT`，得到 `2825828ed694485d8bc2e6cfbc40974fe586cdde`。APK SHA-256 为 `a0cda79a87ada7b332dd943414c7162df97abde0fc85479d5cb4dc1afcf875d2`。已核对该提交与前次调查 `c4191c20` 的 GatewayRuntime、NativeAgentsController、原生会话仓储、gateway-client 和 conversation-ui 相关代码没有差异，因此源码链路适用；不再仅凭“最新 dev”描述版本。

手机配置的 Core 地址是本机局域网接口上的直接 HTTP 地址，双方为同一网段；不公开具体地址。采样时默认网络为 Wi-Fi，未发现全局 HTTP 代理配置。电脑与手机时钟在 06:05:23 的采样同秒；这不替代过去每个请求的签名时间校验。

采集保留日志和两段有界实时日志，覆盖约 **05:49:10—06:05:07**。只持久化允许的时间、PID、通道、错误类、HTTP 状态、请求/跟踪身份、连接转换和创建阶段；不采消息正文、密码、令牌、签名原文或原始事件游标。未清空日志、重装 App、清理数据、重新登录或重启服务。Android CLI 只读界面检查使用了其标准辅助 instrumentation；没有点击界面、发送消息或代用户创建会话。

## 新建会话：真机直接原因已确认

三次点击都沿用同一个待确认创建身份：

- 创建 ID：`cconv_1832c9c691b84431a974935261242a39`。
- 逻辑 traceId：`af3f7ffd88a070a8d2ccee9acbbac479`。
- 三次均为 `recoveryOnly=true`，当前 Controller 有效，没有重复点击或引导发送的入口拦截。

| 点击时间 | 回执查询 requestId | 本机持久化 | GET 结果时间 | 点击至结果 |
| --- | --- | --- | --- | --- |
| 05:58:34.324 | `reqa0d0b2c386db42178965` | 约 22ms | 05:58:34.432，404 | 108ms |
| 05:58:35.597 | `reqa2417ecf28a54c73adf6` | 约 19ms | 05:58:35.744，404 | 147ms |
| 05:59:19.885 | `req343dc3dbd45245ceaa84` | 约 1ms | 05:59:19.942，404 | 57ms |

实际链路为：

```text
create.clicked
→ create.persistence_started（旧 ID，recoveryOnly=true）
→ create.persistence_completed
→ 同一逻辑 trace 的操作回执 GET
→ HTTP 404
→ OUTCOME_UNKNOWN
→ creatingThread=false，卡片消失，留在原会话
```

在这三次有完整 debug 阶段日志的操作中，没有同 trace 的创建 POST、`native.creation_saved` 或 `create.ui_applied`。窗口中存在其他 POST200，但身份不同，不能算作新建会话。Core 操作账本以标准 `mode=ro`、包含 WAL 的快照查询，目标 ID 在 `native_operations` 与 `native_operation_scopes` 均为 0 行，与实际接口404一致。

源码对应 [WorkbenchController.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt) 第 1012—1025、1999—2000 行，以及 [CoreAgentConversationRepository.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt) 第 882—883、910—925 行。

结论：本次真机卡片迅速消失的直接原因是**旧待确认身份使按钮持续走“只恢复”分支，查询不存在的回执后快速失败**。本轮不是存储缓慢、创建 POST 被远端拒绝，或创建成功后的导航竞争。

证据边界：保留窗口里的第一次点击就已为 `recoveryOnly=true`，因此旧身份最初如何形成仍未知。前次“离线预检未发送却污染身份”的反例证明一种来源存在，不能代替早期现场记录。账本当前没有记录也不证明最初请求从未抵达。修复不能遇到404就无条件重新 POST，仍需保护已发送但响应丢失的情况。

## 连接问题：本轮真实链路与旧协商500不同

### 底层 SocketException 引发短暂误离线

真机日志多次出现 Native 同步 `SocketException/status=0`，同毫秒附近工作台切为离线，下一次 HTTP200 又恢复。例如：

| 时间 | 事件 |
| --- | --- |
| 05:52:39.959—39.960 | online=false；Native SocketException |
| 05:52:40.314 | GET200；online=true |
| 05:53:14.107 | Native SocketException；online=false |
| 05:53:16.059 | GET200；online=true |
| 05:53:20.215—20.216 | Native SocketException；online=false |
| 05:53:21.539 | GET200；online=true |

最清楚的一组跨流证据为：

```text
05:58:12.404  SSE socket error
05:58:12.407  conversation.connection：online=false，canSend=true
05:58:12.492  GET200；online=true，canSend=true
```

源码 [GatewayTransport.kt](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt) 把活动请求的 IOException 交给全局失败回调；[GatewayRuntime.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt) 第 2103—2123 行设为全局 HTTP 不可达，第 2837—2848 行再投影到 Hermes 在线状态。这与真机中85ms的离线闪变一致，发送能力在该窗口没有被撤回。它不能证明 Core 已确认 Hermes 服务离线。

### WS 已退到 SSE，SSE 也会失败

```text
05:57:52.728  attempt32，WS
05:58:00.513  SocketException，action=fallback
05:58:00.513  attempt33，SSE
05:58:00.521  SSE open
05:58:12.404  SSE socket error
05:58:12.430  SocketException，继续退避
05:59:12.432  attempt34，WS
```

所以“从不回退 SSE”不是本次原因，问题跨两种传输。当前日志只记录异常类别，没有 SocketException 的具体 errno/安全筛选后原因，也没有 TCP FIN/RST 发起方证据，不能确定由手机、服务端、局域网设备或应用内部哪个环节首先关闭连接。

初次检查手机在锁屏/息屏状态；解锁进入 App 后的 **06:02:13—06:05:07** 窗口，143 个 HTTP 响应均为200，没有同类事件失败或在线转换。它只证明该短前台窗口恢复，不能将锁屏、Doze、厂商后台策略或外部网络干扰直接定为唯一原因。

### 同一 Native 请求自动续签后仍收到401

请求 `req2d627a9ed3ea4e34b19f` 的真实日志：

```text
05:55:01.388  GET dispatched
05:55:22.713  HTTP401
05:55:22.958  同 requestId 重新签名后 dispatched
05:55:30.751  再次 HTTP401
05:55:30.752  Native sync failed：AUTHENTICATION_REQUIRED，status=401
```

相邻请求多数在一次401后自动恢复200，另有一条双401。因此不是整个账号始终不可用。重复401让 Native 以认证错误暂停；后续选择会话/重新订阅又出现页面同步成功，不能称为永久失效。

排除与限制：

- 账号库标准只读快照中访问会话 TTL 为900秒，近期会话 active；频繁的约95秒轮换不等于正常15分钟到期。
- 这些401样本的签名后 IO 排队仅0—3ms，没有120秒排队导致签名过期的证据。
- 真实控制实验中仅使协商绑定过期仍为200；签名时间越界、nonce重放和令牌过期都会统一401，因此单一错误码无法区分内部失败点。
- 手机本轮没有捕获 `NEGOTIATION_FAILED:500`。旧PID协商500和前次本地反例仍有效，但不能作为本轮USB窗口的主要现场解释。
- 捕获到三条快速GET400，尚缺具体路由和受控错误码，未强行归因。

## 新发现：数据库写锁被伪装成认证拒绝

为追查“凭据有效仍频繁401”，用临时 Core、真实 HTTP 路由、合成账号和 Ed25519 签名构造控制实验：

| 条件 | 结果 |
| --- | --- |
| 正常有效签名 | 200，0.007秒 |
| 另一连接仅 `BEGIN IMMEDIATE` 持有写锁，普通只读查询有效会话 | 成功 |
| 同一有效会话签名请求，写锁尚未释放 | 401 `AUTHENTICATION_REQUIRED`，5.006秒 |
| 释放写锁，同一凭据请求 | 200，0.008秒 |

主代理独立重放得到相同结果。临时 wrapper 在异常被吞前记录 `sqlite3.OperationalError: database is locked`，调用栈为：

```text
server.py:70 open_gateway_account
→ core.py:4840 open_gateway_account
→ core.py:1209 AccountStore.__init__
```

根因：每次认证打开账户都重建 `AccountStore`，第1209行执行 `INSERT OR IGNORE INTO account_metadata ...` 等写初始化；即使数据库已经建立，也会申请写锁。默认 SQLite 等待五秒后异常向外传播，被 [verifier.py](../../oai-core/src/oai_core/gateway/verifier.py) 第227—231行通用捕获转为 None，再由 [auth.py](../../oai-core/src/oai_core/auth.py) 第272—276行变为401。开户调用在 `_resolve_session` 内层 try 之前，本次异常不是由该内层 catch 捕获。

这是已确认的独立缺陷，已登记 #106。它能把存储忙放大成错误续签与认证阻断，但没有手机05:55当时的持锁者或锁错误日志，因此还不能断言它就是本次所有401、SocketException的唯一来源。

## 证据保存与后续入口

原始安全 metadata 保存在本轮私有临时证据目录，未提交账号数据、APK或完整界面内容。四份手机日志的 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| phone-retained.jsonl | `4afaedd8219c7e727c81956a525d3a3ff5113267a24b2a738be35b66d8f11939` |
| phone-live.jsonl | `aeb98a50524b94332e57b392ae4dc240b27822dece90811b86069ed6f66cff66` |
| round2/phone-retained.jsonl | `bbd6ae1d7ddfcc0a5e5347fd505c7099b77cc15aa2f256fb33c996520b294dc6` |
| round2/phone-live.jsonl | `2ac837a53a086a1189cd17f6db51a5d665a095fff4667608b9b906051a9d8091` |

真实数据库锁复现脚本和输出附在 #106，USB关键时序附在 #104/#105。采集进程均有界结束，没有留下持续抓日志任务。没有业务代码变动，没有运行不相关全量编译。

后续优先修复已确认的旧创建身份恢复状态与认证存储误分类，再以原手机数据验证。连接根层仍需区分：具体 SocketException原因/关闭方向、401内部失败阶段及当时持锁源。诊断只增加受控阶段和错误类别，不记录凭据、正文、nonce或完整SQL参数；不得靠重新登录、清数据、改会话ID或放宽身份校验绕过。
