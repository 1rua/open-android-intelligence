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
