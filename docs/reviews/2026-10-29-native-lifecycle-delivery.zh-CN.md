# 原生受理、Agent 接收与完成修订

本步使用合成 HTTP/SSE 和独立测试状态，不读取真实消息或用户数据库。

## 真实反馈

UI 原生用户身份及 server-only acceptance 回归最初为 29 项中 3 项失败；客户端生命周期与仓储 queued/received 两项也分别先失败。修复后执行联合验证得到客户端 32 项、UI 91 项、仓储和恢复所有者 34 项，共 157 项通过、0 失败、0 跳过。该数量对应当次冻结源码，不把它追溯套用到后续新增的终态失败测试。

随后补充 queued=0、running=1、done=2 的完整回归。原客户端忽略 done 的生命周期版本，仓储仍用固定 1 发送完成状态，造成已接收版本 1 后终态被 UI 的版本屏障拒绝。这两项实际得到 2 项失败。新增终态 DTO 默认字段与失败断言已随中间提交进入分支；其最终结论以后续真实绿结果为准，不将中间提交记作完整通过。

## 最终协议边界

- 服务端初始 queued 帧只证明受理。真实 userMessageId/clientMessageId 身份映射发 MessageAccepted(agentReceived=false)，SSE 和恢复 GET 两条路径保持同样语义。
- generation.state/revision 是独立生命周期事实。较旧 queued 不得使已运行状态回退；工具快照缺 state 时不会自行推进 queued。明确 running 才发相同真实用户身份的 DELIVERED。
- 老宿主缺省新字段时保留原兼容行为，原 Gateway 的 MessageAccepted 默认 agentReceived=true；不削弱原有签名或精确关联保护。
- done.revision 是宿主已经持久化的生命周期修订，和 contentRevision 分别读取。NativeGenerationResult 透传可选 revision，仓储完成回执使用该真实值；缺省字段按旧宿主兼容规则处理，不能拿正文版本或本机时间替代宿主版本。
- 确定的 done 完成状态不依赖再一次网络 GET 才能推进；后续 GET 继续负责完整历史补偿。

最后终态版本步实际执行：客户端 **33 项通过**、仓储及恢复所有者 **34 项通过**，共 **67 项通过、0 失败、0 跳过**；Gradle `BUILD SUCCESSFUL in 2m 59s`。已通过且未变化的 UI 91 项未重复运行。本轮 green 验证覆盖包含终态 DTO 与新增失败断言的最终源码；不把先前 157 项结果套用到中间提交。`git diff --check` 通过。

真实设备、模型与跨机器延迟不属于本步验证范围。
