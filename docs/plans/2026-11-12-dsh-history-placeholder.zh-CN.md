# DSH 历史占位身份恢复计划

关联问题：#150。固定基线：`dev@688e2b8197f67bbdcf9da00e8fb4948060466869`。

## 问题与目标

真实模拟器中，输入已经进入 DSH 运行，但在取得原生用户消息 ID 前停止。停止查询确认 `cancelled`。Core 和宿主重启后，服务端历史 ID 都唯一且既有记录摘要保持，App 刷新却报 `HISTORY_SYNC_FAILED:MESSAGE_ID_CONFLICT`。

最小真实镜像回归已复现：持久保存 `messageId == clientMessageId.value` 的明确占位原子，再提交同一客户端身份的原生历史事件，`LocalSendAtom.identified` 允许升级，`EncryptedConversationMirror.mergeAtom` 随后拒绝升级并回滚事务。

本次允许同一会话、同一客户端提交的明确占位身份升级到权威原生 ID，并保留已绑定身份不被陈旧占位回写覆盖。两个不同原生身份、跨客户端重复绑定、跨会话记录仍须拒绝。保留消息内容、终态、修订和删除屏障，不清理缓存或重发消息。

## 实施与验证

1. 以真实加密镜像回归覆盖占位升级、取消后历史回读、磁盘重开、重复历史与陈旧占位回写；保留真实身份冲突保护。
2. 只调整磁盘原子合并的身份选择，复用现有占位定义；若发现其他历史路径需要修改，先补回归并说明原因。
3. 执行现有镜像与删除持久化回归、必要聚焦门禁，并安排无开发上下文独立审查。
4. 由主任务安装最终 APK，在原隔离用户 10 数据上刷新、重启回读与继续发送验证；本分支不清理测试数据，不修改服务或操作模拟器。

## 初始证据

`HistoryIdentityProbeTest.historyCanUpgradeAnExplicitClientPlaceholderToItsNativeUserId` 在基线真实 `NativeConversationMirror` 中执行，1 项失败，异常位置 `EncryptedConversationMirror.kt:460`。前两轮真实输入有 `accept.identity`，停止轮次只有 `CANCELLED` 终态而没有原生受理身份，符合历史首次提供原生 ID 的触发条件。

当前状态：已建立问题和计划，尚未完成修复及最终模拟器验收。
