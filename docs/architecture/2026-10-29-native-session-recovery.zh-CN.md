# 原生会话断流恢复与结构化执行记录

本约定补充 [原生消息身份](2026-10-27-native-session-message-integrity.zh-CN.md)，继续沿用 Android → OAI Core → 宿主适配器 → Agent 的边界。Core 不成为第二份聊天历史的拥有者。

## 指定消息的持久执行回执

宿主描述中的可选能力 `generationRecovery=true` 表示支持按原始身份查询持久回执。未返回该字段的旧适配器视为不支持；不能用其“当前没有活动任务”推导完成。

公开端点为 `GET /open-android-intelligence/v2/agents/{agentId}/sessions/{sessionId}/generation`。不带选择条件时，保留只查询当前活动 generation 的旧行为。恢复请求带 `clientMessageId`、`generationId` 至少一项；同时带两项时，必须指向同一条受理记录。

恢复响应为 `{"generation": {...}}`，字段包括 `conversationId`、`clientMessageId`、`userMessageId`、`generationId`、`state`、`revision`。状态允许 `queued`、`running`、`unknown`、`completed`、`failed`、`cancelled`，后三项为明确终态。回执不存在、身份不匹配、宿主不支持和认证失效分别保留真实错误，不转换为成功。

恢复请求还可带 `afterRevision` 和 `waitMs`。`waitMs` 为 0 至 25000 毫秒；非零等待必须带 `afterRevision`。宿主在修订推进、终态出现或期限到达时返回。重复参数、未知字段和非法数值拒绝。客户端同一消息只保留一个恢复任务，不能重复 POST 原请求。

完成后的回执仍可读取；宿主停止接受新工作不应抹掉已经持久化的完成事实。读取始终经过当前账号与会话授权，长等待结束后再次核对认证。响应使用 `Cache-Control: no-store`。

## 执行、观察与历史补偿

受理身份和分派记录必须在远端执行前可靠保存。手机 HTTP 连接只观察任务，不拥有已受理执行；关闭观察器只解除其事件订阅，不能取消宿主 task。用户明确取消必须另走绑定 generation 的取消端点。

Hermes 观察器以账号 EventStore 中已经提交的事件顺序为缓冲，每批最多读取 128 项；内存通知只负责唤醒。文本突发不能淘汰审批、消息身份或终态。此内部读取游标不代表 Android 已经应用或持久化该事件，也不是公开的断线重放协议。

客户端断流后按原始 client/generation 查询权威回执，并重新读取同一 Agent、同一会话的宿主历史。先应用消息快照和必要的本地恢复记录，再确认终态；原已接收正文与原发送身份继续保留。新会话、新 Agent 或新账号不能接收旧恢复任务的状态。

`unknown` 代表确实缺乏终态证据；网络关闭、空的活动任务、后台 task 取消或没有新正文都不是完成证据。已经确认的终态不能被迟到的低修订事件重新打开。

## 原生工具历史字段

历史消息可携带可选的 `generationId` 和 `executionSteps`。每个步骤包括 `stepId`、`eventId`、`toolName`、`arguments`、`output`、`status`、`revision`。步骤以 `(generationId, stepId)` 归属，修订与事件身份来自宿主真实记录。

Hermes 从原生用户 `platform_message_id` 查当前账号与会话内的受理记录，再关联 generation；从 `tool_calls[].id` 和工具结果的 `tool_call_id` 关联步骤。缺乏可靠关联时按普通消息显示，不用正文、时间相近或 ID 前缀猜测。下一条原生用户记录或无请求身份的压缩摘要关闭之前的归属范围。

没有工具结果时状态为 `unknown`；有明确结果时为 `completed`，这只表示有结果，不表示操作成功。只有原生明确的错误元数据才能标记 `failed`。工具输出与参数属于正常授权消息内容，不写入诊断日志。

同一 generation 的步骤在最终回答前归入一张可折叠卡。步骤卡和各条真实 Assistant 回复分别保存身份；后续回复不能吞并先前正文或改变旧步骤归属。没有结构化字段的其他 Agent 继续走既有展示路径。审批与手动命令保留自己的权威状态和操作流程。

固定 Hermes SDK 的 `format_tool_event` 显示回调不能完整提供所有工具 finish 事件。适配器改用公开的 `pre_tool_call` / `post_tool_call` 观察钩子：以已受理分派的可信上下文绑定账号、会话、client 和 generation，再核对原生 session、turn、call ID 及已经写入的原生调用行。没有唯一关联时不创建步骤，观察器始终返回空指令，不修改工具参数、结果、审批策略或执行语义。

步骤保存在原生数据库的隔离 KV 中，与模型聊天正文分开；实时和历史使用同一个执行修订域。已有 `generation` SSE 可附带 `executionSteps`、`executionRevision`、`eventId`，表达该轮完整步骤快照，不改变 SSE 事件类型。外层 `eventId` 是账号 EventStore 已提交通知身份；步骤的 `eventId` 是原生调用来源行身份，两者不混用。工具修订、消息正文修订和 generation 生命周期修订相互独立。

账号事件中不含正文的 `gateway.notice` 只负责唤醒，必须同时精确匹配工具通知代码和原受理消息关联。观察器再读取原生持久步骤，不从通知文字猜执行内容。账号通知与原生 KV 属于两份存储，不宣称跨库原子提交；通知提交失败时，原生历史及终态补偿仍可恢复已经记录的步骤，不能伪造一个已提交的通知 ID。

生成取消或完成时，仍缺少结果的 RUNNING 步骤降为 UNKNOWN，不能一直显示执行中，也不能伪造完成。执行观察失败只记录脱敏的错误类型，不能反向改变 Agent 执行终态。会话删除和账号清理沿原有授权路径清理相应隔离前缀，其他账号与会话的步骤继续保留。

固定 SDK 回归已验证工具 pre 后可在 post 发生前观察到 RUNNING 快照、完成后的持久事实与历史版本一致。真实手机画面及跨机器阶段延迟仍需单独验收。

验证与已知限制见 [PR 续接记录](../reviews/2026-10-29-pr64-continuation.zh-CN.md) 和 [结构化工具回归](../reviews/2026-10-29-structured-tool-regression.zh-CN.md)。
