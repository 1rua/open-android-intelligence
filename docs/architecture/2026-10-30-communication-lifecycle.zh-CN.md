# 原生会话通信、操作回执与观察生命周期

本说明沿用 Android → OAI Core → Session Adapter → Agent 宿主。设备能力仍由 Android 本地授权。本文补充[生成回执](2026-10-29-native-session-recovery.zh-CN.md)和[持久事件同步](2026-10-30-native-event-sync.zh-CN.md)，不新增消息或事件数据库。

## 实际传输与任务所有者

| 路径 | 传输 | 所有者与关闭含义 |
| --- | --- | --- |
| Android → Core 创建、列表、历史、回执 | HTTP JSON | 创建由 Core 持有操作任务；手机请求关闭不取消已进入的创建。 |
| Android → Core 消息 | HTTP POST，响应 SSE | Core 持有读取 Adapter 结果的任务；手机只持有观察连接。 |
| Core → Hermes/DSH | HTTP JSON；消息返回 NDJSON | Adapter 桥负责宿主调用；NDJSON 关闭不代表宿主执行失败或明确取消。 |
| Core → Codex | 原生 app-server 的 JSON-RPC/stdin/stdout | 原生 turn 与流观察分别管理；首帧提供真实 turn ID，显式取消绑定该 turn。 |
| Android → Core 原生事件补偿 | HTTP JSON 长轮询、分页、ACK | 继续使用现有 eventSync 和不透明游标。 |
| Android → Core 平台事件 | SSE；Core 也支持协商的 WebSocket | 平台事件不能替代原生 generation 的受理或终态证据。 |

Core 的消息任务不依赖手机写 socket 的速度。中间观察缓冲有界；观察者过慢时关闭该观察并留下可恢复状态。平台 SSE/WS 队列溢出后必须停止发送更晚游标，避免客户端确认越过未交付事件。宿主原生历史、generation 回执和持久事件仍是恢复来源。

Hermes 后续分派订阅既有已提交事件，在前项权威状态变化时唤醒并重读持久队列；不依靠固定轮询间隔推测前项结束。共享 SQLite 连接的 execute、游标 fetch/迭代和事务继续使用同一把账号锁。

Core 关闭、Adapter 宿主关闭、手机断网是三个不同边界。不能把关闭观察者翻译成用户取消，也不能把进程退出翻译成 Agent 已完成或失败。

## 创建操作的权威回执

Core 在 Agent discovery 的 `capabilities.operationRecovery=true` 表达自己提供操作回执查询；它不表示 Adapter 或宿主支持 generationRecovery/eventSync。这三个能力分别协商，缺省均按不支持处理。

创建操作继续使用 `POST /open-android-intelligence/v2/agents/{agentId}/sessions`。Android 将工作台已经保存的创建操作 ID 作为请求 ID 和 Idempotency-Key；签名、nonce 与账号验证继续使用既有协议。相同账号下换 Agent 或修改请求内容不能把旧操作当成新请求。

新增只读端点：

```text
GET /open-android-intelligence/v2/agents/{agentId}/operations/{requestId}
```

不接受查询参数。它复用 NativeOperations 的同一数据库，按已认证账号及保存的 Agent 作用域读取，不接受调用者提供账号或会话身份。返回 `Cache-Control: no-store`。

| 响应 | 含义 |
| --- | --- |
| `200 {"state":"pending"}` | 持久账本已有该操作，当前 Core 进程仍持有相关操作任务。不是 Agent 已运行的证明。 |
| `200 {"state":"resolved","receipt":{"session":{...}}}` | 原创建结果已经提交；会话 ID 来自原生宿主。 |
| `200 {"state":"unknown"}` | 操作已登记，但没有持久结果，也没有当前进程持有的执行证据。 |
| `404`，`OPERATION_NOT_FOUND` | 当前账号与 Agent 的作用域内没有可查询记录。其他账号、其他 Agent 和无可验证作用域的旧记录不可见。 |

该端点也可读取相应作用域中已保存的其他操作回执，但它不复制聊天历史，不把消息观察错误当成 generation 终态。消息恢复仍应按原 clientMessageId/generationId 查询生成回执。

Android 创建响应丢失后先查询原操作。resolved 直接使用原权威会话；pending 使用有界等待；unknown 或查询失败保留原创建身份和待确认状态。没有权威结果时不能换一个 ID 自动再创建，不能用本地 ID 冒充已创建的服务端会话。旧 Core 未声明 operationRecovery 时，客户端仍保留原操作身份与未知结果，不假称已恢复。

账本增加的作用域表与原操作处于同一事务，原有幂等摘要和回执不改写。升级前未保存作用域的记录仍保留原重复请求行为，不依据正文、标题或时间猜测所属 Agent。该附加表可由旧版本忽略；回退前应停止新旧写入者并按既有备份程序操作，不能让两个 Core 同时写同一部署。

## Android 恢复顺序

1. 先从当前账号、Agent 的加密 native 镜像恢复选中会话、历史和草稿，本地可见性不等待在线认证完成。
2. 前台或网络恢复由一个所有者重建失效观察通道；旧账号、旧 Agent 或旧连接所有者的迟到结果不得覆盖当前状态。
3. 使用原 generation/client 身份、eventSync 页和历史补齐；明确未知状态保持未知，不重 POST 原输入。
4. 应用事件和必要的恢复事实，完成本地持久化后，更新运行 tracker、取消路由及恢复屏障。
5. 保存成功后 ACK 原页游标；ACK 丢失继续使用同一游标，不能退回或越过缺口。

已成功创建的会话立即进入工作台；列表刷新、历史读取和事件订阅独立进行。取消和 Steering 继续依赖真实 generation 及协商能力，Queue 保持本地未发送语义。恢复后的明确 running/queued 允许本地排队；未知和歧义保留屏障。非当前会话仍按原所有者补齐终态和本地落盘，不能把其恢复任务转交给当前会话。歧义保存真实身份集合，只有集合中每一项取得权威终态且成功保存后才能解除。

## 计时、身份与部署核对

`traceparent` 只传诊断上下文，不授权、不改变签名或幂等。Core 接受有效的 W3C v00 格式；无效或缺失时生成新的 trace。Core→Adapter 同时传递可信的原请求 ID；进程内异步任务及线程继承 trace 上下文。诊断阶段继续关联真实 clientMessageId、generationId、messageId、eventId。

每条诊断包含进程启动标识与单调时间。进程内阶段使用单调时钟差；墙上时间只帮助排列跨进程记录。跨机器单向延迟需要独立的时钟偏差及误差估计，缺少校正时必须标为不可测，不直接相减机器时间戳。

分别观察：点击、请求排队、网络发出、Core 收到、认证、Core 登记、Adapter 受理、宿主开始、首事件、事件提交、Core 转发、Android 接收、本地保存、UI 应用与帧提交。UI 帧提交也不是物理屏幕实际亮起时间。

诊断只记录允许的身份、数量、耗时、状态、修订和错误码。禁止正文、工具输入输出、凭据、签名和原始游标；拒绝控制字符和超长字段。日志缺失不证明执行未发生。

部署验收应记录代码 SHA/文件摘要、进程启动时间、实际加载来源、宿主 SDK 与运行时能力。磁盘文件相同、包版本相同或界面显示已连接，均不能单独证明当前进程使用了对应契约。
