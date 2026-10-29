# 服务端消息恢复与执行生命周期修复记录

日期：2026-10-29。继续 PR #64，起点 `f10bf29b`。测试仅使用临时账号目录、合成消息和独立数据库。

## 慢消费者导致真实消息和审批丢失

已复现路径：`HermesSessionAdapter.send` 的 1024 项内存队列满后无差别丢弃队首。让手机在收到 generation 身份后暂停消费，再让宿主提交 1030 个事件，首条真实 Assistant 消息在输出流中消失。它仍存在于持久事件记录，说明这是传输投影丢失，而不是宿主没有生成回复。

反馈命令：`python3 -m pytest oai-core/tests/test_hermes.py::test_slow_stream_replays_every_native_message_in_commit_order -q`。使用共享 Python 测试依赖，并将 `oai-core/src`、`integrations/hermes-session-adapter/src` 加入 `PYTHONPATH`。修复前 1 项失败：预期首条 `reply-first` 保留，实际只收到 `reply-second`。

修复使用现有账号 EventStore 作为可靠缓冲。发送受理事务内记住事件位置，之后以每批最多 128 项读取已经提交的事件，保持顺序；内存事件通知只负责唤醒读取。任务完成也唤醒读取，不再每 200 毫秒检查一次。此游标仅是当前服务端观察器的内部读取位置，逐项读取时推进，既不持久化为手机消费确认，也不证明对端已应用事件。HTTP 断开后的 Android 恢复仍依赖指定 generation 查询和权威历史快照。审批和终态不会因文本突发被淘汰。取消后仅补齐权威审批撤回，迟到正文不能重启已取消生成。

回归强化为：在审批请求与决议之后提交 1030 条不同真实 ID 的消息，手机暂停消费到整批提交结束，再确认所有 ID 原序到达、审批两条边界均保留、done 位于最后。正常流和原生审批专项共 40 项通过；强化后的慢消费者专项 1 项通过。

这证明隔离服务端的慢消费者补偿，不代表已测量真实手机端各阶段延迟，也不替代弱网、厂商后台策略或生产代理验收。

## 观察连接中断与权威终态查询

增加 `generationRecovery` 可选能力。Hermes 在具备持久账号存储时声明支持，即使宿主 runtime 已离线也不撤销这项查询能力；Core 和远程适配器通过既有能力白名单传递。旧宿主缺少此字段时按不支持处理，客户端不能对其他 Agent 假定存在恢复接口。

隔离 HTTP 链路回归分别主动中断手机到 Core 的 SSE 和 Core 到 Adapter 的 NDJSON。测试等待服务端真实观察器解绑，再确认受理的任务仍在运行且没有中断回调，随后让宿主完成。重连只执行 GET 指定 `clientMessageId` 查询，得到同一 generation 的 completed，宿主分派次数和用户消息行数均为 1。两项断线专项与 Hermes 查询测试合计 24 项通过；覆盖的是合成执行处理器与真实账号存储、HTTP、事件和工作流，不是真实模型。

固定 Hermes SDK `75b083e9399115b12513385252811f09c3503c90` 中，`gateway/delivery_ledger.py` 的交付账本重试生成 `Recovered reply`；`gateway/run.py` 的 `build_resume_recovery_note` 和 `gateway/run_turn_runner.py` 的恢复路径在 `resume_pending` 下生成中断提示。客户端 SSE/NDJSON 观察关闭不会调用本适配器的宿主中断入口。上述测试断言一条原始用户记录和一次任务分派，不能冒充已测试 Hermes 进程真正重启后的 `resume_pending` 行为；不得按这些正文关键词删除合法上游消息。

扩展四文件回归时又复现取消竞争：宿主 Task 先被显式取消，权威 cancelled 回执稍后提交。观察器立即 await 已取消 Task 会提前退出，得到 error 而不是 done；原先 200 毫秒等待掩盖了此窗口。修复后只以持久 generation 终态关闭流，任务取消本身不能证明 Agent 失败；完成回调或取消交接尚未提交时等待其真实事件。异常任务没有持久终态时明确返回 `OUTCOME_UNKNOWN`。

上述四文件回归修复后共 70 项通过。后续将持久消息的 `revision` 作为 SSE `contentRevision` 透传，并保留 `eventId`、`generationId`，让历史和实时内容使用同一原生修订域。generation 生命周期版本保持独立。消息与审批、HTTP 链路专项共 49 项通过，未把手机时间作为宿主版本。

## 阶段日志和可测量边界

通过 Python 标准日志器 `oai_core.native_sessions` 的 DEBUG 级别按需开启诊断，默认不输出。Core 记录 T2（请求验证后）和 T5（SSE write 返回后）；真实 Hermes runtime 回调记录 T3（宿主开始处理）、T4（每条真实消息首个增量持久化）和 T10（终态写入完成）。T5 只采样首个文本增量、身份和控制/终态，不逐 token 连续刷日志。日志仅包含公开消息、请求、事件、generation 身份、版本、状态及单调时钟，不包含账号名称、会话密钥、令牌、正文、工具参数或附件。

`monotonicNs` 只能直接比较同一进程的阶段；Core 与 Adapter 位于不同机器时，不能相减后声称网络时延。Core 的 `elapsedMs` 从本次 HTTP 处理入口计时。T5 的 write 返回证明服务端写入调用完成，不等于 Android 已读取或 Compose 已渲染。合成 HTTP 用例验证 T2→T5 首帧→done 时序并断言私有合成正文及令牌未进入日志；固定 SDK 隔离进程验证真实宿主 T3→T4→T10 回调时序及相同脱敏边界。没有真实手机 T0/T1/T6–T9/T11 记录时，不声称已测量全链路吞吐或视觉流畅度。

现有 `delta.text` 只表达追加片段。同一真实 ID 的非前缀编辑后若出现另一条真实消息，前条编辑不能由最后一个 done.text 表达。固定 SDK 合成处理器覆盖此序列，并核对前条编辑在权威历史中保留更高 `contentRevision`。Android 正常终态也应按每个真实 ID 补偿历史；本轮不新增未经协商的 snapshot 事件，也不宣称已支持编辑的实时替换。

固定 SDK 六文件专项：`test_hermes.py`、`test_hermes_native.py`、`test_hermes_interactions.py`、`test_interaction_pipeline.py`、`test_hermes_approval_prompts.py`、`test_hermes_dispatch_delivery.py`，71 项通过、0 跳过，耗时 26.05 秒。使用共享依赖的隔离测试解释器，SDK 固定为上文提交；该结果包含真实宿主生命周期/SessionStore 合约，但处理器返回合成回复，不调用真实模型。

## 公开工具回调与实时步骤

继续核对固定 SDK 后发现，`format_tool_event` 的显示回调并非完整工具生命周期的唯一入口。公开插件 `pre_tool_call` / `post_tool_call` 回调包含原生 `session_id`、`tool_call_id`、`turn_id`，且 SDK 对有界回调线程复制调用上下文。第一条真实固定 SDK 回归先失败：插件没有注册这两个回调。

适配器现在只将回调当作观察器，始终返回 `None`，不修改参数、结果、审批策略或工具执行。原消息分派前，从该条已受理记录建立可信账号、会话、client、generation 与原生 session 上下文。工具回调必须精确匹配该上下文，并在真实原生历史中找到当前用户轮次内的 `tool_calls[].id`；不会查询“当前活动 generation”代替身份。缺少身份、跨账号/会话、错 turn 或已经终结的回调均不认领步骤。

步骤正文与参数仅保存在 Hermes 原生 KV，与模型 transcript 分开。Core 账号 EventStore 只提交合法 `gateway.notice` 唤醒：`noticeCode=oai.native.execution.updated`，`correlationId` 精确等于原已受理 userMessageId，不携带工具参数或结果。原生观察器读到此通知后，通过既有 `generation` SSE 类型发送可选的 `executionSteps`、`executionRevision` 和真实通知事件 `eventId`。旧客户端可以忽略扩展字段，不新增其会拒绝的 SSE 类型。

实时步骤与历史投影读取同一份原生 KV，按原生 call ID 及持久执行修订合并。收到真实工具结果只证明完成，只有宿主显式错误状态才标失败；未收到 post 但已有原生工具结果时可由历史补偿。生成终态时，仍无结果的运行中步骤转为 `unknown`，不伪造成功或长期保持执行中。

新增隔离回归覆盖无可信上下文、原生身份不匹配、重复回调、错 turn、终态后晚到回调、缺 post 的持久补偿以及 live/history 同版本。固定 SDK 测试经过真实插件注册与公开回调，并让宿主在 pre 后等待手机收到运行中步骤，再发出 post，验证执行期间追加和最终归属。八文件专项共 83 项通过、0 跳过，耗时 27.95 秒；`git diff --check` 通过。测试仍使用合成处理器，不调用真实模型或工具，不代替用户手机验收。

交叉审查再补两条真实失败回归：历史先初始化两个步骤、随后收到第一步 pre 时会重排；工具终态观察器异常会阻止已有 generation 结算。修复为历史与实时共用原生 source 行 ID/索引排序，观察器终态关闭失败只记录异常类型，执行终态及媒体收尾继续完成。两条先失败后通过；受影响工具观察与投递专项 14 项通过，不记录异常携带的私有合成文本。

显式取消的独立路径也会在 done 之前关闭尚无结果的步骤并送达 `unknown` 快照；不只依赖宿主稍晚的完成回调。固定 SDK 场景已验证执行中取消后收到步骤 unknown，再收到 cancelled done。读取工具快照失败只报告异常类型并继续观察执行，不把旁路展示存储故障解释为 Agent 执行失败。

最终八文件回归：85 项通过、0 跳过，耗时 29.59 秒；只有既存 aiohttp request key 建议警告。此结果包含上述 source 顺序、旁路失败和真实 SDK 取消快照补偿。

## 工具内容的删除边界与跨库来源身份

只读复核发现新工具 KV 没有进入原有会话/账号清理范围。合成账号在删除会话和移除账号后仍能直接取得工具参数与结果，两个回归分别失败。现将工具 KV 按存储拥有者、账号、会话及 generation 的独立哈希分级命名，沿已有原生历史 `manage(delete)` 与 `cleanup_account` 授权路径清理匹配范围。同数据库中的另一个会话、另一个账号仍保留；固定 SDK 的真实删除流程另行检查对应工具 KV 已不可取。

另一个故障注入确认：Hermes 原生 KV 与账号事件账本是两个数据库，工具 KV 写入成功、随后账号事务提交失败时，不能把尚未提交的通知 ID 写成步骤来源。`executionSteps[].eventId` 现明确引用已经存在的原生调用 source 行，形式为原生 session ID 加 source 行 ID；它不是通知确认。外层 `generation.eventId` 继续使用已经提交且真正触发观察器的通知 ID，`executionRevision` 属于原生 KV 的独立修订域。

注入 `fail_next_commit` 的回归先失败，修复后确认：没有未提交的通知被交付，已存工具步骤仍引用真实原生来源，后续原生结果可以通过历史补偿为完成且不会执行工具。此处没有声明跨两个数据库的原子事务。

重新接续后再次运行固定 SDK 八文件回归：**88 项通过、0 失败、0 跳过**，耗时 33.28 秒；`git diff --check` 通过。覆盖两个清理隔离场景及跨库提交失败注入，日志保存在仓库忽略的 `.toolchains/pr64-final-tools-storage.log`。
