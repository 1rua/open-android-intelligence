# 收到终态回复时闪退（MESSAGE_ID_CONFLICT）修复记录

- 日期：2026-10-28
- 影响版本：`2.2.1-dev`（PR #55 之后仍复现）
- 修复 PR：#56
- 真机：SM-S9380 / Android 16（SDK 36）

## 现象

每轮对话在**收到 Agent 最终回复**时概率性直接闪退（进程退出，无 ANR）。是否复现取决于宿主是否回传 `userMessageId`。

## 现场堆栈（`adb logcat -b crash`）

```
FATAL EXCEPTION: main
Process: com.openandroidintelligence.mobile, PID: 25105
java.lang.IllegalStateException: MESSAGE_ID_CONFLICT
    at com.openandroidintelligence.conversation.model.LocalSendAtom.accepted(LocalSendAtom.kt:32)
    at com.openandroidintelligence.conversation.model.LocalSendAtom.accepted$default(LocalSendAtom.kt:30)
    at com.openandroidintelligence.conversation.state.WorkbenchController.applySendEvent$lambda$33(WorkbenchController.kt:479)
    at com.openandroidintelligence.conversation.state.WorkbenchController.changeSendAtom(WorkbenchController.kt:388)
    at com.openandroidintelligence.conversation.state.WorkbenchController.applySendEvent(WorkbenchController.kt:477)
    at com.openandroidintelligence.conversation.state.WorkbenchController$observeThreadEvents$1$4.emit(WorkbenchController.kt:3552)
    at kotlinx.coroutines.flow.SharedFlowImpl.collect$suspendImpl(SharedFlow.kt:397)
    at android.os.Looper.loopOnce(Looper.java:288)
```

## 根因

同一轮对话里，同一条 prompt 先后使用了两个 id：

1. `CoreAgentConversationRepository.submitMessage` 在受理阶段用**客户端 id** 作为 `MessageAcceptance.messageId`
   （`var messageId = message.clientMessageId.value`），发送原子 `LocalSendAtom.messageId` 因此绑定 clientId；
2. 流式过程中 `MessageIdentity.userMessageId` 到达后，仓库把内部的 `messageId` 更新为**服务端 id**，
   最终 `MessageStatus(COMPLETED)` 用服务端 id 上报；
3. `WorkbenchController.applySendEvent` 对终态事件执行 `atom.accepted(event.messageId)`，
   而 `accepted()` 的不变量是 `messageId == null || messageId == serverId`，于是抛 `MESSAGE_ID_CONFLICT`；
4. 该异常发生在 `Dispatchers.Main.immediate` 的事件收集协程里，没有 `CoroutineExceptionHandler` 兜底，
   直接进入未捕获异常处理，进程退出。

PR #55 修的是镜像层（`EncryptedConversationMirror`）里的同名断言，而实际崩溃点在控制器的
**事件投影链路**，所以问题依旧。

## 修复

| 位置 | 改动 |
| --- | --- |
| `LocalSendAtom` | 新增 `identified()`：事件链路专用、永不抛异常；客户端占位 id 可被服务端 id 升级，已绑定身份不再改写，但状态/revision/错误码照常推进 |
| `WorkbenchController.applySendEvent` | `MessageStatus` / `TimelineUpsert` 改用 `identified()`；身份已绑定却收到另一 id 时打 `OaiWorkbench` 警告（带两个 id） |
| `WorkbenchController` `MessageAccepted` 分支 | 收到真实 `userMessageId` 时把占位身份升级，后续终态事件不再分叉 |
| `WorkbenchController` 手动重试删除回执 | 同样改用 `identified()` |
| `EncryptedConversationMirror` | `apply` / `reconcileSnapshotContent` / `reconcileQuery` / `rememberSnapshotDeletions` 全部改用 `identified()` |
| `WorkbenchController.workScope` | 增加 `CoroutineExceptionHandler`（`Log.e` 记录堆栈），单个事件投影异常不再带走进程；`retryWhen` 重连语义不变 |

设计取舍：**不改写已绑定的身份**。`LocalSendAtom.messageId` 会作为时间线行标识使用，途中改 id 会把同一条消息拆成两行；
因此选择"身份只升级一次、状态照常推进"。

## 验证

自动化（本地 + 子代理独立复跑）：

- `:conversation-domain:testDebugUnitTest` 32/32
- `:conversation-ui:testDebugUnitTest` 611/611
- `:app:testFullDebugUnitTest` 976 个用例，24 个失败均为本地环境缺件（插件 wasm 未编译 18、Hermes fixture 缺失 5、python3 fixture 1），与本次改动无关
- 新增回归：
  - `LocalSendAtomIdentityTest`：占位升级、分叉不改写身份且状态推进、空白 id 无操作、严格 `accepted()` 仍拒绝静默改写
  - `WorkbenchMessageIdentityConflictTest`：复现现场堆栈（受理 id 与服务端 id 不同 → `MessageStatus(COMPLETED)` 不再崩溃、状态正确、时间线只有一条用户消息）；`MessageAccepted` 先升级身份后终态可直接匹配
  - `EncryptedMirrorRecoveryTest`：同一 clientMessageId 以另一服务端 id 到达不抛异常、身份保持、正文更新
- 独立复核：把 `identified()` 换回 `accepted()`，`statusWithDivergentServerIdSettlesThePromptInsteadOfCrashing` 会精确复现原崩溃

真机（发行签名 dev 预览包，versionCode 5661）：

- 覆盖安装成功，连续多轮"发送 → 收到完整回复"，进程 pid 全程不变（未重启）
- `logcat -b crash` 为空；采集窗口内 `MESSAGE_ID_CONFLICT` / `FATAL EXCEPTION` 计数为 0
- `OaiWorkbench` 身份分叉告警计数为 0（说明占位身份在终态前已被正确升级）

## 经验

- 崩溃修复必须以真实堆栈为准：这次两轮修复的差别就在于是否拿到了 `FATAL EXCEPTION` 的完整 `at ...` 链。
- 身份类不变量要区分"创建/受理路径"（可以强断言）与"事件投影路径"（必须容错，不能让远端 id 把进程打崩）。
- 控制器的工作协程需要兜底处理器：事件投影失败是"这一帧没应用上"，不是"App 该退出"。
