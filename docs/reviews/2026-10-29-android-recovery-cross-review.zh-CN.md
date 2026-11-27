# Android 消息恢复交叉审查

日期：2026-10-29。审查恢复中的 PR #64 Android 代码相对 `91782bf6` 的工作区差异。仅检查源码和已有测试，未启动额外 Gradle。以下是可以明确构造的触发路径；在对应回归实际执行之前，不称作运行时复现。

## P0：旧客户端消息的恢复不能取得新生成的所有权

位置：`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt` 的 `queryMessage`、`startRecovery`、`isSelected`。

`queryMessage` 为任意旧 client 的持久回执创建 `NativeTurn` 并启动恢复，没有拒绝同一 `(agent, conversation)` 已有的新活跃 turn。`isSelected` 只比较当前 Agent 和会话。因而旧 A 的 completed 回执恢复能把仍在执行的 B 的 `_generationId` 改为旧 A，随后清空，并把 `_generationState` 改成 COMPLETED。Workbench 再按仓储已经被覆写的当前 ID 判断终态，无法补救这一层的错误所有权。

最小断言：B 的真实 SSE 已交付 `gen_B`，继续持有连接；查询旧 `client_A`，返回 A completed；B 的 ID 必须仍为 `gen_B`，状态仍 RUNNING，POST 数必须为 1。

## P0：自动排队必须继续使用入队时的 Agent

位置：同一仓储的 `drainQueuedSends` 与公开 `submitMessage`。

队列按原 Agent 和会话取出项目，但 `drainQueuedSends` 调用公开 `submitMessage(conversationId, message)`，后者重新读取当前选中的 Agent。原 Agent A 完成时，如果用户已切换到 B，A 的队列消息可能被发给 B 的同名会话。这条旧路径也会被新加入的恢复后自动排空队列调用触发。

最小断言：A 收到第一条并排队第二条；切到 B，再完成 A；第二条只能送往 A 的原会话，不能向 B 发起写请求。身份校验和排空必须使用原队列所有者，不仅是 UI 当前选择。

## P1：恢复结束与下一轮启动的所有者交接窗口

位置：`startRecovery` 的终态分支和 `finally`。

终态分支先 launch `drainQueuedSends`，旧恢复 job 随后才从 `recoveryJobs` 移除。若第二条队列任务快速完成，新 turn 先写入 `recoveringTurns`，随后 `startRecovery` 会被仍活跃的旧 job 挡回。旧 job 结束后，新 turn 留在恢复集合却没有恢复 job，后续发送会一直得到 OUTCOME_UNKNOWN。

最小断言：以可控调度让第二条队列消息在旧 job 清理前完成；第二条必须完成自己的回执及历史补偿，恢复集合与 job 所有者一致，并允许下一条正常提交。修复应先完成旧所有者交接，再启动下一轮，不能放弃单恢复任务互斥。

## P1：首会话创建不能清掉已经显示的冻结消息

位置：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt` 的 `sendDraft`、`bootstrapConversationForFirstMessage`、`submitWhenAttachmentsVerified`。

`sendDraft` 已同步显示本地气泡；首个会话创建返回时，bootstrap 又把 timeline 和 pendingBatch 清空。其后的身份装载、保存 checkpoint 和保存发送记录仍可能挂起，气泡要等这些操作结束才重新加入。现有即时显示测试使用已经打开的会话，覆盖不到此路径。

最小断言：无会话发送首条，创建请求挂起时气泡已可见；释放创建、继续挂起真实发送记录保存，气泡仍保持原 key 和正文，POST 数为 0。

## P1：编辑下一条草稿不能隐式取消已经冻结的附件提交

位置：同一控制器的 `sendDraft`、`editDraft`、`cancelPendingSubmission`。

附件未验证时，冻结提交保留在 pendingSubmission；本轮已把旧正文移出输入框并显示成气泡。用户开始输入下一条草稿时，`editDraft` 无条件调用取消函数，移除旧气泡并取消上一条等待附件的提交。应由显式取消撤回冻结提交，新草稿应独立保存。

最小断言：发送带未完成上传的 A 后编辑 B；附件验证结束，A 以原 client 身份最多分派一次并保持可见，B 留在输入框。显式取消仍应能取消 A。

## 验证安排

独立旧回执所有者测试和首会话气泡测试已准备为临时测试草稿，待当前构建结束后由 Android 修复 Agent 放入测试目录，先运行失败反馈，再修复并扩大精准回归。本文不把草稿存在或源码检查记为测试通过。最终状态以修复记录和实际测试结果为准。
