# checkpoint 保存恢复与通知归属修复计划

日期：2026-11-12。关联 OAI #165、PR #168；基线 dev 为 02ad53e09049c2b06c21cacd9d009c2f14e6d5bd。

## 问题与边界

公开草稿编辑触发 checkpoint 失败后，下一次写入成功只清除 checkpointFailure，旧 LOCAL_SAVE_FAILED 通知仍残留。初始修复按字符串、threadId 和 draftVersion 匹配；独立审查指出附件归档、事件提交和创建后 flush 也能写同值错误，字符串相等不能说明错误来源相同。

跨会话公开入口另确认一个直接相关的恢复问题：writer 写入 A 期间已排队快照 B，A 失败退出后，B 与 pendingCheckpoint 相等。原去重条件无条件返回，导致 flush 不会重启已失败的 writer，旧失败再次抛出。

以上均为受控复现的具体缺陷。真实模拟器首次短暂保存失败的原始异常尚未确定，不能把这些修复反推为该现场的根因。

## 实现

1. 每次真正开始写入 checkpoint 分配单调 attempt；失败通知记录其 attempt 和草稿版本。
2. 后续 writer 排空全部 pending 且原子耐久保存返回成功后，只有比失败更新的 attempt、草稿版本不倒退、当前通知仍未被其他来源接管时才清理旧通知。
3. 附件归档、事件提交、创建后 flush 三条非 checkpoint 本地保存错误路径显式使 checkpoint 恢复令牌失效；相同错误字符串也不能共享来源。
4. pending 相等仅在 writer 活跃时去重；writer 失败结束后允许相同 pending 快照重启一次。保持单 writer，不追加无条件后台重试。
5. checkpoint 是账号级快照，包含 composerDrafts。切线程后的成功快照含旧会话草稿，因此不使用当前 threadId 作为永久阻挡清理的条件。
6. 保存成功仍依赖既有 WorkbenchPersistence 原子耐久契约；不为了通知每次写入后全库回读。真实 NativeConversationMirror → EncryptedConversationMirror → SQLite 的恢复后重开回读由测试验证。
7. 诊断字段只允许固定 phase、kind、code 和 result；异常 message、正文、凭据、会话 ID 均不写日志。同阶段同类型连续错误去重，恢复时记录一次结果。

## 验收

- 公开 editDraft 失败后恢复成功：持久化端口 load 可回读，旧 checkpoint 通知清除。
- 同值异源事件读/提交错误：后续 checkpoint 成功仍保留该错误；原事件不被确认。
- 单 writer、后续排队写失败、取消、旧编辑版本和后来的不同通知均有回归。
- 跨会话快照恢复可回读旧会话 composerDrafts，失败后已有 pending 能再次保存。
- 真实 SQLite 合成账号故障注入后恢复，关闭并重新打开镜像仍可读取恢复草稿。
- 诊断日志不包含合成敏感 message 哨兵。
- Fast、独立审查、远端 CI 完成后由主任务合并；最终真实模拟器重验仍由主任务负责。

证据见[修复验证记录](../reviews/2026-11-12-checkpoint-notice-recovery.zh-CN.md)。
