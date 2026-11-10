# 后台连接维护与独立自动恢复排查

关联 issue：#130、PR #131。目标分支：`dev`。固定诊断基线：`4cbe21b8324e7dc2b5bee44bce0174213c627980`。本文随实际验证补充，尚未完成的测试不得视为通过。

## 需求与约束

在系统允许进程执行及联网时，独立维护 Platform WS/SSE、原生会话同步和设备请求恢复。前台失效立即发起恢复，实测目标十秒内可用。后台、息屏与 Activity 停止不应自行取消进程连接。保留现有 Local-First、协议、授权、账号、设备身份、绑定与历史；退出、停止和强停仍具有约束力。

本轮禁止新增、修改或删除任何面向用户的文案、通知内容、提示触发规则或交互。系统若要求无法复用的新可见内容，只记录待确认事项。日志不得包含令牌、签名、原始游标、消息正文或设备能力结果。

## 已确认事实与未闭合问题

- Application 已持有进程级 Runtime，已有前后台与网络变化入口。不能预先认定由 Activity 销毁直接取消连接。
- Platform、原生会话同步和普通 HTTP 是三个独立健康边界，不能用一次会话读取成功证明全部通道恢复。
- 现有任务前台服务采用 `dataSync`，不能未经评估改成无限空闲保活。Android 15 对此类型有时限；Doze 期间必须记录系统是否允许联网和执行。
- 用户现场“长时间一直不恢复、切换会话才恢复”的全部因果尚未闭合。已复现的缺陷是：真实半开连接返回前台不满足十秒恢复、Native 最长退避不响应明确恢复信号。不能据此排除现场还有系统冻结、存储或部署版本问题。

## 已复现的根因与证据

### Platform 的活跃协程不等于可用连接

`GatewayRuntime.startPlatform` 原逻辑在旧 `platformJob.isActive` 且不是路由/凭据/用户重试时直接返回；普通 `FOREGROUND` 被合并，但没有验证 socket 是否仍能传输。`GatewayHttpClient.collectSseAttempt` 与 WS 的既有完整帧失活预算为六十秒，前台返回不能提前识别半开。Application 的作用域本来就独立于 Activity，本次没有发现 `onStop()` 直接关闭主连接的证据。

在真实 TCP 上先持续心跳，再保持旧 socket 开放并停止数据，完整 `ColdStartConnectionTest.returningToForegroundReplacesASilentPlatformConnectionWithoutChangingTheConversation` 在十秒新订阅断言失败。使用 Android 平台 `com.android.okhttp.internal.huc.HttpURLConnectionImpl` 的同路径对照：禁用唯一前台健康调用时仍失败；启用后恢复 4091 毫秒，并保留同一 controller、会话和草稿。测试桩并发接受普通请求，避免长 SSE 阻塞测试服务器形成假故障。

Robolectric 默认 JDK HTTP 连接取消时有不同的读取锁行为，曾使取消额外等待；它不是手机根因。Runtime 仅增加标准连接工厂注入，生产默认值保持；测试使用 Android 真实平台实现，不迁移生产 SSE 网络库。

### Native 的恢复信号没有唤醒退避等待

`CoreAgentConversationRepository.startEventSync` 原 `connectionRestored()` 只合并 `wakeRequested`；活跃循环直接 `delay(nativeRetryDelay(...))`，最长六十秒睡眠不会被明确网络恢复或真实前台切入缩短。公开仓储用例连续九次读失败进入六十秒退避后，恢复信号没有触发第十次读取，断言得到 expected 10 / actual 9。修复保留同一个 synchronizer、pending ACK 与游标，只允许明确恢复提前唤醒当次等待；普通健康活动不打断读取或退避。

这一缺陷能解释“已有恢复入口却暂时不刷新”的一部分路径；旧代码仍持续重试，不能把它夸大成已证明永久停止。

随后公开仓储回归也复现正在执行的半开 Native 长轮询不会被恢复信号替换（预期取消一次，实际零次）。正式传输的独立 `eventRecoveryEpoch` 现由真实 ready 事实递增，不依赖可能合并中间状态的 `EventStreamStatus`。Runtime 将其转为进程单调恢复代次，只替换当前网络请求，不重建外层同步器。同代次及过期代次不取消健康读取；pending ACK 使用原游标补传，不重新保存或提交消息。

独立评审进一步发现早期实现将完整 `synchronize` 包在可取消子任务中，会在落盘后、发布受阻时丢失尚未发布的事实。公开仓储回归复现八条只发布前五条；修复后八条按序发布。最终取消边界仅覆盖 events、history、generation、current 与 ACK 的实际网络请求，本地提交、状态提交、事件及审批发布均不在取消范围内。

仅 Native HTTP 半开、Platform 仍健康的场景也分别完成红绿：明确前台恢复可独立重校验网络；持续前台将同一个事件接口的等待改为五秒，每个网络阶段使用八秒绝对预算，超时进入既有可恢复退避。主工作台、气泡及助手由当前认证会话所属集合统一接续，关闭时移除。后台后续读取仍等待二十五秒并沿用原读取预算。

### 为什么切换会话可能暂时改善

`WorkbenchController.openThread()` 会取消旧 `timelineJob`，再通过 `repository.completeTimeline(threadId)` 读取远端历史；Runtime 的选中会话回调也会调用 `CoreAgentConversationRepository.activeConversationChanged()`，后者恢复相应读者。它与前台只合并活跃 Platform owner、Native 仍睡在退避或旧网络请求中的路径不同，所以会话切换确实有额外的数据读取与恢复作用。

这是当前源码及公开接口回归支持的机制解释，不是用户现场最初断链原因的唯一证明。原修复没有重新选择会话、重启 Activity、反复刷新历史或自动重发 prompt；连接管理、网络失活检测与恢复继续由进程级所有者独立承担。

## 实际设计

- 连接仍由 Application 持有，UI 订阅既有状态。实际前台切入转发可见性并发起合并健康检查；重复前台与相同网络通知不重建健康连接。路由、凭据交接仍先取消并等待旧正式流退出。
- WS 前台在原 socket 每五秒发送带随机 nonce 的控制 Ping，以匹配 Pong 在三秒内证明双向可达；不生成业务事件、不另建订阅。明确前台恢复可立即发 Ping。后台保留原六十秒完整帧检测，不增加前台式主动 Ping。
- SSE 明确恢复先等待三秒新完整帧，静默则关闭旧流并从已提交游标续传。Core 的当前实现实际上每约一秒发送空闲心跳（原代码 `min(1.0, 15.0)`），本次只在已认证 SSE 响应增加 `X-Open-Android-Intelligence-Event-Heartbeat-Ms` 提示，不改变原频率。持续前台按三倍声明节奏、至少三秒检测；后台仍六十秒。缺失、重复或不可信节奏提示沿用保守预算，旧服务端不承诺持续前台十秒半开检测。
- 恢复等待沿用现有指数退避和随机抖动；明确恢复信号可唤醒一次，重复信号合并并受五秒冷却约束，不重置连续失败次数。仅 Native 网络失效也有独立前台预算；普通活动不取消健康读取。认证、撤销、证书及协议拒绝不能因切换传输绕过。
- 本地消息提交造成的背压不算网络静默；探活排除该等待。已解码安全拒绝优先于并发探活超时。独立提前审查复现这两类竞态后，已补回归并修复。
- Core 持久事件、Native eventSync/ACK、设备执行日志继续沿用。手机离线不取消 Agent 已受理任务，不重新提交消息或注册设备，不清空本地历史。

本轮没有新增空闲前台服务、WakeLock、WorkManager 实时轮询或系统权限。已有用户任务 `dataSync` 服务和通知、启停及权限规则不变；系统冻结或强停期间不声称可以继续联网。若后续必须扩大服务用途并新增用户可见通知，仍按用户边界单列确认。

## 日志

Android 使用现有 `GatewayLog` 输出内部 JSON：`connection.state_changed`、`connection.heartbeat_timeout`、`connection.reconnect_scheduled`、`connection.reconnect_started`、`connection.reconnect_succeeded`、`connection.reconnect_failed`、`connection.subscription_restored`、`lifecycle.foreground`、`lifecycle.background`。Core 仅在积压帧成功写入后记录 `connection.message_replayed`。

字段包含墙上时间、单调时间、进程启动标识、连接代次、原因、次数及结果；不记录令牌、签名、异常原文、原始游标、用户正文或设备结果。进程内恢复耗时使用单调时钟，不跨机器直接相减。日志的发送成功不是 Android 已持久保存的 ACK 证明，二者不能混用。

## 验证入口

用户确认的公开测试边界为：登录后 Runtime 生命周期与网络入口，以及 Android↔Core 的真实 WS/SSE、游标补发、去重及设备调用恢复接口。每项修复按一条失败行为、最小修复、通过证据顺序推进，不写只验证内部实现的测试。

## 已执行自动化与真实传输验证

| 边界 | 实际结果 | 证据含义 |
| --- | --- | --- |
| 正确 Android HTTP Runtime 完整复现 | 红 → 绿，4091 毫秒；ColdStartConnectionTest 19 项通过 | 前台恢复无须会话操作，controller/会话/草稿不变 |
| Native 恢复等待 | 红：第九次后无法唤醒；绿：立即第十次读取、本地保存、ACK，无 POST 消息 | 明确恢复只影响等待，不取消健康读取 |
| 关联 App 三类 | 60 项通过，后续合并后仍须正式门禁 | ColdStart 19、NativeConnectionReliability 20、NativeEventSynchronizer 21 |
| 最终 Native 半开关联三类 | 64 项通过，集成业务 `69ac1360` 仍待正式门禁复核 | ColdStart 19、NativeConnectionReliability 24、NativeEventSynchronizer 21；含 ACK、重复代次及安全拒绝 |
| 评审修复后 App 关联三类 | 69 项通过，业务 `ee4f0e83` | ColdStart 19、NativeConnectionReliability 29、NativeEventSynchronizer 21；含落盘后发布背压、Native 独立前台预算及空页减写 |
| Gateway 模块完整回归 | 403 项通过，零失败/错误/跳过 | 含真实 TCP、认证/证书、事件重放、取消、前台探活和本地背压 |
| Core 关联测试 | 141 passed，1 个既有 aiohttp RequestKey 警告 | 真实签名 WS/SSE、离线补发、在线引用、Native ACK 和 OS 进程恢复 |
| 真实 Android 15 + 隔离 Core 重建 | WS 848 毫秒、SSE 1928 毫秒 | 同账号/端口补发，三事件按序各一次，登录一次 |
| 真实 Android 15 + SSE 半开 | 无检查十秒超时；显式检查 3839 毫秒 | socket 双向丢字节但不主动关闭，旧游标恢复，总订阅二/活一 |
| 真实 Android 15 + WS 半开 | 周期 9196 毫秒；显式检查 3871 毫秒 | 持续前台无需新生命周期信号；单次样本接近十秒，不能保证所有调度条件 |

模拟器原始脱敏结果及完整命令见[隔离验收入口](2026-11-10-background-connection-emulator.zh-CN.md)。上述分项在各修复提交执行，不冒充最终统一 SHA 的验收。最终 Fast/PR、独立双轴评审及集成 SHA 复核将在下方追加。

## 正式门禁与独立评审记录

2026 年 11 月 11 日，固定业务 `ee4f0e837745bef218275c7ef621953c49ef706d` 的 `npm run test:fast` 全阶段通过：Core 1224 项及 6 个子测试、Hermes 261 项、DSH 89 项、通信测量 25 项与 `noVpnSurfaceCheck`。PR 等级门禁仍在执行，不能提前写为通过。

执行环境复用了现有 Node 24、Java 21、完整 Python 和有效 Robolectric 缓存。先前环境失败分别是临时路径无法创建符号链接、私有回收目录权限、隔离 Python 子进程缺依赖、一次配置事务子进程偶发超时；在私有 tmpfs、0700 回收目录及已验证完整解释器下对照后恢复。生产代码与测试安全校验没有为这些环境失败放宽。测试网络 owner 按既有机制仅登记三份完整测试路径，生产网络、VPN 和监听禁令不变。

独立规范轴与需求轴第一轮发现并修复本地发布被恢复取消、Native 独立半开恢复缺口；发布背压真实回归由只送达五条转为八条完整送达。需求轴第二轮确认具体 P1/P2 可关闭，并要求同步文档与功耗边界，相关文档已同步。规范轴第二轮另发现关闭仓储会被迟到恢复信号重新启动，公开测试复现关闭后存储访问由两次增至四次；`66c85da7` 使用与 `close()` 相同仓储锁和关闭栅栏修复，第三轮独立复核确认关闭，无剩余实质规范发现。代码评审已在三轮内收敛，尚未测完的设备边界继续单独保留。

前台每个活动 Native 读取者最多约十二次事件 GET/分钟，多个活动会话会叠加；这是真实成本。已确认同游标、无事件/审批且无需刷新的空页不重复写镜像或 ACK；六十秒回归包含首次快照共十三次读取、一次历史与一次 ACK。八秒是单网络阶段预算，事件页串联历史或多个 generation 时总耗时可能超过十秒，不能把失效检测预算表述为整页总时限。真机电量影响尚未测量。

## 尚未完成与系统边界

前台断线、半开、Core 重启、后台及前台返回、网络恢复和切换、强制 Doze、设备调用恢复、离线消息有序补发、界面零变更。息屏 30 分钟、2 小时、6 小时和电量对照必须分别记录真实设备、系统条件、构建 SHA、实际时长与结果；缺少条件时明确列为未验证。

当前无实体手机接入；不得把 Android 15 模拟器的电池 100% 或 KVM 运行表现作为真实手机功耗证据。用户现场长期断联、Android 12～16 全机型、Wi-Fi/蜂窝真实切换及长时息屏未全部验收。本轮测试事件证明 Platform 有序恢复，不能单独替代 Native 双方消息镜像或真实能力插件端到端验证。既有 Core 设备事件有保留期限，请求也有自身期限，不承诺无限离线保存。
