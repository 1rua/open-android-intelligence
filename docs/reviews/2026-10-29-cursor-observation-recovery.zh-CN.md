# 原生观察游标过期的安全恢复

最新 dev 整合后的完整 Android 受影响门禁先已通过：control 14、capability 51、screen 21、device 22、domain 32、UI 655、gateway 308、App Full 1113，共 2216 项通过、0 失败、0 跳过，noVpnSurfaceCheck 通过。Play 编译及仓储、所有者、API 兼容关键类 52 项通过。上述验证源码仍保留原来缺失的 CURSOR_EXPIRED 观察错误映射。

用户验收要求包含事件游标失效后的补偿。此前 Adapter 持久事件游标过期会透传 CURSOR_EXPIRED，但 Android 把它归入执行失败，不发起原身份对账。把该代码追加到已有观察错误恢复测试列表后实际得到 1 项失败：原消息已受理且收到部分正文，观察流报游标过期，预期 GET generation 1 次、实际 0 次。

修复仅把 CURSOR_EXPIRED 纳入既有观察失败集合，保留已收正文和 client/generation 身份，通过 GET 持久回执及 GET 历史补偿；不重复 POST，不把游标过期伪造成宿主执行失败，也不修改真实执行失败代码的处理。

CURSOR_EXPIRED 窄修先在 Full/Play 仓储与所有者各 34 项、共 68 项回归通过，0 失败、0 跳过，Gradle `BUILD SUCCESSFUL in 1m 24s`。

## 已受理任务的失败结果也必须携权威修订

随后补 queued=0、running=1 后收到仅带 AGENT_FAILED 的错误流，查询持久 failed=2 的最小回归，实际 1 项失败：预期 GET generation 1 次、实际 0 次。原 else 分支用固定状态版本 1 发送 FAILED，可能被已经应用 running 版本 1 的 UI 拒绝。

最终窄修只对已经获得真实 userMessageId、且声明 generationRecovery 的任务，把 AGENT_FAILED 转为已有的不确定结果查询路径。先 GET 同一原身份的持久失败证据，再用真实失败版本推进；同一用例断言 POST 仍为 1。没有恢复能力、尚未受理的旧宿主错误继续原处理，不变为永久未知。CURSOR_EXPIRED 仍是观察失效，与 Agent 真正 failed 的权威证据分别处理。

最终相关回归完成：客户端 **33 项通过**，Full 仓储与恢复所有者 **35 项通过**，Play 同类 **35 项通过**，共 **103 项通过、0 失败、0 跳过**。Gradle `BUILD SUCCESSFUL in 1m 33s`，`git diff --check` 通过。新增失败回归明确确认持久 failed=2 与 POST 仍为 1。

没有重复无变更的 UI 或全部模块编译。此前完整 Android 2216 项及 Play 关键 52 项已在同一合入 dev 的工作区源码通过；最终两处窄修改另用上述 103 项复核。真实用户手机、代理及模型没有在本步运行。
