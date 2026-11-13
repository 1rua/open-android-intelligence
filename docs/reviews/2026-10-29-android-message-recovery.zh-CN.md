# Android 原生消息恢复与发送时序修复

日期：2026-10-29。接续 PR #64，原始检查点 `f10bf29b`。本记录只使用合成消息与内存 HTTP 传输，不读取 Hermes 真实账号、SessionDB 或手机资料。

## 已核对的源码事实与验证入口

- `CoreAgentConversationRepository.submitMessage` 在 HTTP 请求返回前发送 `MessageAccepted`，并立即返回使用客户端 ID 的 `MessageAcceptance`。`WorkbenchController` 将其写为已受理的远端身份；真实 `userMessageId` 后到时又触发一次映射。候选影响为用户消息受理状态过早、临时与真实身份分叉。
- `WorkbenchController.sendDraft` 先更新生成状态；气泡创建与清空输入位于后续协程中的 `hydrateSendAtoms`、`persistCheckpoint` 等挂起步骤之后。候选影响是 UI 先显示任务状态、后显示用户气泡。
- 原生 SSE 提前结束仅转为 `OUTCOME_UNKNOWN`；Android 尚未调用 PR 已新增的指定消息持久终态查询。重新订阅内存 SharedFlow 不会重新建立 POST SSE，也不能补齐已经错过的帧。
- 仓储以 `System.currentTimeMillis()` 初始化展示修订，不能直接拿宿主较小的 `contentRevision` 覆盖它；恢复必须明确区分宿主权威修订与端侧展示顺序。

对应回归入口为 `CoreAgentConversationRepositoryTest.localSubmissionCannotInventAServerAcceptance`、`droppedDoneAutomaticallyRestoresHistoryAndDurableTerminalWithoutResending`，以及 `WorkbenchSendPersistenceTest.sendClickImmediatelyMovesTheFrozenDraftIntoTheTimeline`。这些测试锁定边界行为，不代表已经在真实网络和手机上复现全部用户现象。

## 契约与实现要求

- 新增 `generationRecovery` 能力，旧宿主缺省为不支持。指定请求查询使用原 `clientMessageId`，已知时同时校验 `generationId`，以宿主 `revision` 为状态推进依据。
- 持久回执可在宿主停止接收新消息后查询；离线状态不等于回执不可读。未知/空结果保持未知，不能解释成成功。
- 恢复只执行 GET 回执与 GET 历史快照，禁止自动再次 POST 原请求。条件长轮询使用 `afterRevision` 与有界 `waitMs`，避免固定间隔暴力查询。
- 工具步骤从原生结构化历史透传 `generationId`、`executionSteps`，不从正文推断。未知工具状态映射为 `UNKNOWN`。
- 本地气泡先以冻结的客户端身份显示；任何远端提交仍必须等待发送恢复记录可靠保存。真实回执到来才绑定远端身份。

## 自动化证据

初次 Gradle 验证未进入测试：与另一个 Gradle 进程同时构建导致中间产物竞态；其后编译期间编辑新能力 DTO，又产生新测试与旧 classpath 不一致。两者均属于验证过程失败，不计为通过或目标故障复现。后续验证统一串行运行并冻结编译期间源码。

精准用例红/绿、测试总数及环境边界将在完成后补充。

## 恢复后实际执行的验证

临时工作区丢失后，按本轮操作记录恢复未提交修改到仓库内独立 worktree，并重新执行所有直接验证，未复用中断任务的未完成结果。

第一轮联合验证发现三个真实阻塞：`ConcurrentHashMap` 的 `in` 被 Kotlin 拒绝，指定回执查询参数不符合既有签名所需的排序，以及空 `MessageAccepted.correlationId` 导致新增身份处理抛异常、后续正确受理事件不再消费。分别改为显式 `containsKey`、规范排序查询字段，以及只让非空且精确匹配本地记录的回执推进身份。既有签名与关联保护没有放宽。第二轮客户端 24 项通过，发送、持久化、身份直接类 60 项通过；仓储 28 项中 27 项通过、1 项失败，失败锁定原生事件观察器随当前选择漂移造成的跨 Agent 消费。

新增 `NativeRecoveryOwnershipTest` 的原始可控测试为 3 项中 2 项失败：原 Agent A 的排队消息在切换到 B 后被写向 B；查询旧消息 A 的 completed 回执会关闭仍在运行的新 generation B。修复将队列写操作绑定到入队 Agent，历史查询只返回旧事实，恢复任务不得抢占另一个客户端的所有权；观察器固定订阅创建时的 Agent，实际 Agent 发现/切换由现有控制器重建或重订阅路径接手。

快速队列完成原先在 `UnconfinedTestDispatcher` 下通过，不能称作已经复现恢复交接窗口。为提高反馈判定力，又改用明确内联执行新任务的调度器，单独还原“先排空队列、后释放恢复任务”旧顺序，专项仍为 1 项通过。因此该交接窗口保持为源码竞态候选，没有运行时复现证据；槽位先释放的调整是保持单所有者不变量的设计修正，不作为已复现故障汇报。测试后立即恢复最终修复源码，再执行联合回归。

恢复所有权交接必须在互斥区内从实时读取者移到恢复任务；终态要先释放旧恢复任务的槽位，再启动下一条排队消息。状态显示还需检查当前 turn 所有权，不能仅因 Agent 与会话名字相同就获得新 generation 的控制权。

完整命令输出保存在仓库忽略的 `.toolchains/` 验证日志；日志不是提交产物。一次增量构建出现 `javaCompile/classAnalysis.bin` 缓存 `Invalid argument` 警告，但未据此跳过业务用例或记为通过。后续按最小缓存范围处理，继续复用其余 Gradle 缓存。

最终第一阶段直接联合验证：`AgentSessionClientTest` **24 项通过**；`CoreAgentConversationRepositoryTest` **28 项通过**、`NativeRecoveryOwnershipTest` **3 项通过**、`NativeAgentsControllerTest` **4 项通过**；Workbench 发送、持久化、首会话、语音、附件预览五类 **83 项通过**。总计 **142 项通过、0 失败、0 跳过**，Gradle `BUILD SUCCESSFUL in 1m 16s`。该轮按已冻结源码串行执行，结果日志为仓库忽略的 `.toolchains/pr64-android-recovery-combined-green.log`。`git diff --check` 通过。

补充的跨 Agent 路由与旧消息回执两项先失败后通过；快速交接专项对旧顺序仍通过，相关保护仅作为单所有者设计改进。首次 Agent 列表发现后，已有工作台以 `onForegrounded()` 重新绑定固定 Agent 身份的事件订阅；Agent 实际切换继续走既有工作台重建，旧订阅不会因动态选择而接收另一个 Agent 的同名会话事件。

本轮回归没有运行真实手机、模型、厂商后台策略或生产代理。`AuthSessionLifecycleTest` 在本轮只完成测试源码编译，未将其运行结果计入以上数量；离线账号行为须在同步最新 dev 修复后另行验证。
