# Hermes `/model` 后持续发送失败：定位与修复记录

日期：2026-11-06。修复基线：远端 `dev` 的 `00f146f1`。

## 根因与证据

这次故障发生在 Android 对已完成命令的状态收尾。Hermes 命令没有创建模型生成任务，Android 却把它继续登记为需要查询生成结果的任务。该查询无法找到对应生成记录，恢复流程继续占有当前会话，后续发送在客户端被 `OUTCOME_UNKNOWN` 拦截，排队消息也无法继续。

源码事实：

1. [HermesSessionAdapter.send](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/sessions.py) 的纯命令分支调用 `dispatch_native_input`，随后返回 `delta` 和 `done(outcome=completed, clientMessageId)`。此路径不调用 `accept_message`，不创建用户消息投递记录或 `generationId`。模型切换回复可以携带助手消息 ID，但不是生成任务 ID。
2. [Core 原生消息路由](../../oai-core/src/oai_core/sessions.py) 把 `done` 作为请求结束依据，允许命令没有 `generationId`。[RemoteSessionAdapter](../../oai-core/src/oai_core/adapters/remote.py) 按请求转发结果，没有跨命令保存请求 ID 的状态。
3. [Android 会话数据仓库](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt) 的 `submitMessageForAgent` 原来在收到成功结束后，仅因 Agent 支持 `generationRecovery` 就设置 `recoverAfterTurn=true`，并将任务从 `turns` 转入 `recoveringTurns`。
4. 同文件的 `startRecovery` 会按该命令的 `clientMessageId` 查询生成结果；启用事件同步和本地存储时，会走事件同步恢复。命令没有生成记录，无法通过这条路径取得生成终态。随后 `submitMessageForAgent` 检测到该会话仍有恢复任务，拒绝普通发送；队列也保留在这条恢复屏障之后。
5. “等待合并”是客户端尚未交给宿主的队列状态，不能据此认定 Hermes 仍在执行 `/model`。不能删除队列、把 404 当成功，或通过重发命令消除歧义。

## 修复范围

只修改 Android 数据层对命令成功终态的处理，保留界面、交互和文案。识别依据是宿主已经返回的成功结果及本次消息关联 ID，不按回复文本猜测命令是否成功。

收到与本次 `clientMessageId` 匹配、且没有生成任务身份的 Hermes 纯命令成功结果后，结束当前本地发送并释放恢复占有。有排队消息时复用既有的当前生成状态查询：远端仍有活动生成就保持排队，确认远端空闲后再发送。已取得真实生成身份的消息仍沿原有生成恢复流程确认；断流、缺少成功结束或关联不明的请求仍保留未确认状态，禁止自动重发。

不改变模型配置、Core 操作账本、宿主会话绑定、账号映射、密钥或历史；不增加重试次数或等待时间。

## 针对性验证

[Hermes 通信回归](../../oai-core/tests/test_hermes_communication_reliability.py) 使用 CI 固定的 Hermes SDK、真实 SessionStore、SQLite 投递记录以及 HTTP 会话桥，在受控宿主处理函数返回模型切换成功的条件下执行：

```text
/model synthetic-a → 普通消息
/model synthetic-b → /model synthetic-c → 普通消息
```

断言每次请求收到自身 `clientMessageId` 的成功终态、命令没有生成任务、普通消息生成记录可以查询到 `completed`、没有活动生成残留、账号会话绑定保持一致、只生成两条普通用户投递记录、事件订阅完成释放。此测试不调用真实模型供应商，不能替代手机和真实模型的验收。

Android 数据层的两个新增用例在修复前均失败，断言结果相同：

```text
the successful command must release the queued message
expected: [model_a, ordinary_a]
actual:   [model_a]
```

Android 最终精准验证：9 个用例通过，0 失败、0 错误、0 跳过，Gradle 正常退出（`BUILD SUCCESSFUL`）。包括：

- 两个新增用例，分别覆盖普通生成结果恢复与启用存储的事件同步配置。验证命令完成且远端无活动生成后释放排队消息；普通恢复配置还验证后续连续两次切换及普通消息完整完成；事件同步配置验证后续普通消息已经实际交给传输层。
- 审查新增的远端活动生成保护用例：初版只结束命令便发出了普通消息，此用例已复现失败；最终改为复用现有远端状态探测。远端任务未结束时队列保持原样，远端空闲后才可继续，不把命令成功当作另一任务的完成证明。
- 已有 `droppedDoneAutomaticallyRestoresHistoryAndDurableTerminalWithoutResending` 和 `observationErrorsRecoverTheOriginalIdentityWithoutMarkingExecutionFailed`，验证普通消息未确认时仍按原身份恢复、不会重发。
- 已有 `eventSyncSettlesAnInterruptedPostAndReleasesItsActualUnknownSendBarrier`，验证真实生成记录的事件同步确认仍可释放阻塞。
- 已有普通排队、未确认命令不能转向、Codex 排队次序用例，验证相关现有行为。

Core/Hermes 精准验证：4 个用例通过，包含新增 HTTP 命令序列、运行中/结果未知时不越过队列的两个既有用例、Core 中断后不得重复提交的既有用例。既有 aiohttp `NotAppKeyWarning` 1 条，不影响退出结果。

本地命令（从修复 worktree 根目录执行；Python 使用已准备好测试依赖的环境，Hermes SDK 使用 CI 固定版本）：

```sh
PYTHONPATH=oai-core/src:integrations/hermes-session-adapter/src \
  python -m pytest -q oai-core/tests/test_hermes_communication_reliability.py \
  oai-core/tests/test_sessions.py \
  -k 'completed_model_commands or running_or_unknown_turn_fences_later_queued_work_without_redelivery or interrupted_native_turn_is_unknown_and_never_resent'

cd apps/android
./gradlew :app:testFullDebugUnitTest \
  --tests '*CoreAgentConversationRepositoryTest.completedModel*' \
  --tests '*CoreAgentConversationRepositoryTest.droppedDoneAutomaticallyRestoresHistoryAndDurableTerminalWithoutResending' \
  --tests '*CoreAgentConversationRepositoryTest.observationErrorsRecoverTheOriginalIdentityWithoutMarkingExecutionFailed' \
  --tests '*CoreAgentConversationRepositoryTest.aSlashCommandCannotSteerAnUnconfirmedGeneration' \
  --tests '*CoreAgentConversationRepositoryTest.aSendDuringAnActiveTurnIsQueuedAndSentAfterwards' \
  --tests '*CoreAgentConversationRepositoryTest.queueKeepsItsOrderWhileUnconfirmedSteeringCannotBeRetargeted' \
  --tests '*NativeEventTrackerRecoveryTest.eventSyncSettlesAnInterruptedPostAndReleasesItsActualUnknownSendBarrier'
```

Android 首次运行遇到原共享缓存 `fileHashes.bin` 读写 `Invalid argument`；随后复用仓库另一份已有 Gradle 缓存完成上述修前/修后验证。最终运行仍输出 `classAnalysis.bin` 的非致命缓存写入告警，但 9 个 JUnit 用例全部执行通过且 Gradle 退出码为 0。未删除或重建其他任务的缓存。

本次未调用 `npm run test:fast` / `npm run test:pr`：现有入口会先运行 `mvp:lock:check`，并因测试文件位于 `oai-core` 将范围扩大到全 Core，与本次禁止无关哈希和过度测试的要求冲突。因此直接执行上述受影响路径的自动化用例，并检查 `git diff --check`。

按本次要求仅做受影响路径的自动化验证。未启动无关全量编译、重复全量回归或额外内容哈希校验。CI 按仓库既有工作流执行，结果与本地测试分别记录。

## 交付与验收边界

本次提交至独立任务分支，PR 目标为 `dev`。本地原有 `dev` 的未推送文档提交和其他 worktree 保持原状。

实际 Android 手机、远端 Core/Hermes 部署和真实模型凭据未在本次自动化测试中使用。合并和升级后仍需按用户复现步骤检查真实设备上的模型切换与普通消息收发。
