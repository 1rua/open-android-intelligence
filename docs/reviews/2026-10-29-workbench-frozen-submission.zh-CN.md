# 冻结发送消息与新草稿的时序修复

日期：2026-10-29。接续 PR #64。此次只修复冻结消息在首会话创建和附件等待期间的两个明确路径，保留已有立即显示、本地写前记录和服务端受理身份边界。

## 实际失败反馈

串行执行真实 `:conversation-ui:testDebugUnitTest`，以下两项分别失败，均未跳过：

- `WorkbenchOptimisticBootstrapTest.firstMessageBubbleSurvivesConversationCreationWhileDurableSaveIsPending`：点击后本地气泡已显示；释放会话创建请求，继续挂起可靠发送记录保存，预期原气泡 key 仍存在，实际列表为空。此时消息请求数为 0。
- `WorkbenchSendRegressionTest.editingTheNextDraftDoesNotWithdrawAFrozenAttachmentSubmission`：冻结带待上传附件的 A 后编辑草稿 B，原 A 气泡被移除，查询单条用户气泡失败。

联合验证日志为本地忽略目录中的 `pr64-native-owner-green-workbench-red.log`。该次 Gradle 同时包含其他 Agent 的工作区验证；本记录仅引用上述两项真实失败，不把其他任务失败推导为这两条路径的证据。

## 修复

`bootstrapConversationForFirstMessage` 现在显式接收本次已经显示的待发送条目。创建真实会话后，仅切换其所属会话，保留原客户端身份和同一个气泡 key；发送记录仍须完成持久化，之后才可提交消息。

`editDraft` 现在区分已经冻结的提交与尚可编辑的提交。已有冻结条目时，新输入只推进新草稿，不取消等待附件的旧提交；附件验证完成后仍提交原正文、原附件和原客户端身份。原提交的草稿修订号没有变化，后续异步流程不能清空新草稿。

显式 `cancelPendingSubmission` 继续有效；新增兼容断言覆盖：用户输入 B 后明确取消 A，A 消失，B 保留，随后附件完成上传也不再自动提交 A。会话切换与已有显式取消调用保持原语义。

## 验证状态

唯一 Gradle 验证负责人串行执行联合精准验证，结果 `BUILD SUCCESSFUL in 1m 16s`。本次对应 UI 用例为：

- `WorkbenchOptimisticBootstrapTest`：1 项通过。
- `WorkbenchSendRegressionTest`：45 项通过，含冻结 A 后编辑 B、以及显式取消两个场景。
- `WorkbenchSendPersistenceTest`：15 项通过。
- `WorkbenchVoiceSendTest`：13 项通过。
- `WorkbenchAttachmentPreviewRegressionTest`：9 项通过。

以上合计 **83 项通过，0 失败，0 跳过**，由本次 XML 结果逐类核对。`git diff --check` 通过。联合任务还运行了仓储、客户端等其他直接回归，其总数由 Android 总报告记录，不重复计入这里。

这些是本地合成传输、可控上传和持久化挂起测试，未验证真实手机画面、真实模型或生产网络。
