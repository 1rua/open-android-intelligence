# 原生实时工具事实的 Android 展示

本步延续 PR #64，只处理 Android 已认证原生事件的消费与展示。输入为 `ExecutionStepsUpdated`，含会话、generation、独立执行修订、真实步骤身份，以及可选的原用户消息/客户端消息锚点。正文修订、执行修订和 generation 生命周期保持独立。

## 已复现反馈

唯一 Gradle owner 运行 `WorkbenchNativeExecutionEventsTest`，实际得到 **4 tests / 4 failures**；四项均因 `ExecutionStepsUpdated` 只有分支占位，未生成 `executionFact` 展示行而失败。日志保存在仓库忽略目录的 `pr64-transport-green-execution-ui-red.log`。失败经过真实仓储事件流、Workbench 消费和 `TimelineDisplayModel`，不是只验证数据类。

## 修改

- Workbench 以 `(conversationId, generationId)` 保存工具事实，按独立 `executionRevision` 拒绝旧快照；步骤按真实 `stepId` 和步骤修订合并，明确完成或失败后不能被晚到的执行中/未知状态重新打开。
- 后续快照不能把已有 generation 重新绑定到另一个 prompt。展示以精确 `userMessageId` / `clientMessageId` 锚定原问题，或依赖同 generation 的真实消息；缺乏关联或存在多个候选时保留事实，等待可靠身份到达，不按正文或近似时间猜归属。
- 工具事实生成明确标记 `executionFact=true` 的空正文 system 展示行，只用于单张 generation 步骤卡。它不会进入 `mirrored`，不会写入消息 checkpoint，也不会声称是原生 Assistant 消息。
- 事件到达非活动会话时继续保留该会话事实，切回后按原 prompt 展示。会话删除和控制器关闭释放对应的临时事实。
- 对应 generation 已确认终态后，仍缺结果的 RUNNING 步骤只在展示中转为 UNKNOWN；不伪造成功，迟到的真实完成结果仍可推进。旧 generation 的终态不改变新 generation 的运行状态。

## 验证边界

保留初始四项回归，另补两项：较新快照不能重绑 prompt 或重开已完成步骤；终态先到、晚到工具开始与会话删除的处理。现共六项 UI 回归。唯一 Gradle owner 的联合验证已经运行该类，实际 XML 为 **6 tests / 0 failures / 0 skipped**；六项均通过，`git diff --check` 通过。联合运行中另外的 App 编译曾因仓储跨模块 smart-cast 失败而中断，不能把这一轮整个构建记为通过；相关修复与完整联合结果由传输步骤记录。

这是已认证工具事实到 Android 展示边界的自动化验证，不代替真实手机、模型、厂商后台策略、代理缓冲或显示屏呈现耗时验收。
