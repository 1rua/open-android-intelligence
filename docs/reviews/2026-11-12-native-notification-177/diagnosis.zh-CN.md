# #177 完成通知多匹配：受控生命周期诊断

固定基线：OAI `2caabfb1206fd93cd0232171f0bf287fdfeea498`，PR #178 工作分支计划提交 `2e1c0398abe00a6540bd515d1928f54c95bafbdc`。本轮只在测试源码中添加临时门闩、只读身份输出和候选清理逻辑，未修改生产源码、原 `single` 断言或业务断言。

## 结论与证据边界

已确认测试夹具存在一个可以稳定产生 CI 同一异常的生命周期缺口：`Fixture.close()` 仅取消 `scope`、关闭回环监听，没有等待所属 Job 真正结束。已经进入同步通知发布回调的工作线程可以在取消之后继续发布；若通知环境已经重置，它会把上一 Profile 的通知写入下一测试。当前 Profile 的真实工作台发送完成后，频道中存在两个不同 Profile 的通知，原 `single` 抛 `IllegalArgumentException: Array contains more than one matching element.`。

这证明该生命周期缺口可触发精确症状，但 **CI 当次额外通知的身份没有被采集**，因此不能把它写成那次失败的唯一已证实来源。原版 SDK34/36 整类共34项在本机通过；没有观察到自然多目标发布。生产通知以 Profile/账号/Agent/会话生成 tag；本轮没有证据要求修改产品 tag 或抑制真实通知。

## 可控反馈环

旧 Fixture 和新 Fixture 都使用现有 `LoopbackGatewayStub` 的真实 TCP HTTP/SSE、`GatewayRuntime`、`CoreAgentConversationRepository`、`WorkbenchController` 和 `AndroidReplyNotificationPublisher`。这属于 Android 仓储/工作台集成反馈环，**不是 DSH/真实模型端到端验收**。

临时测试通过反射包裹旧 `NativeConversationNotifications` 已有的 `publish` 回调，仅在终态调用的入口暂停；原回调仍实际执行，目标和通知内容未改。然后按现有 `Fixture.close()` 取消旧 scope，明确断言 `owner.isActive=false`、`owner.isCompleted=false`，模拟测试框架重置通知环境，再放行旧回调并完成新工作台发送。两份 Profile 摘要不同，Agent均为 `dsh`，会话均为 `native-session`，随后执行原频道 `single`。

关键调用链为 `CoreAgentConversationRepository.publishEvent` → `GatewayRuntime.publishNativeNotification` → `NativeConversationNotifications.accept` / `publishNow` → `AndroidReplyNotificationPublisher.update` → 系统 `NotificationManager`。

| 运行 | 层级与干预 | 通过/失败/错误/跳过 | 结果 |
|---|---|---|---|
| `class-both-diagnostic` | 原测试类，34/36，仅只读身份输出 | 34/0/0/0 | 两SDK的原方法均只有当前目标通知 |
| `controlled-real-publisher-cancellation` | 合成事件进入真实通知协调器，旧scope取消后重置再放行 | 0/1/0/0 | 原single精确异常；初步隔离生命周期候选 |
| `controlled-real-publisher-joined` | 同上，环境重置前等待旧owner结束 | 1/0/0/0 | 只剩当前目标 |
| `controlled-real-stream-cancellation` | 旧/新两端均真实Loopback工作台消息流，旧close不等待 | 0/1/0/0 | 原single精确异常；不是探针超时 |
| `controlled-real-stream-fixture-joined` | 等价候选Fixture.close等待owner结束，34/36全类含受控用例 | 36/0/0/0 | 两SDK受控用例和全部业务断言通过 |
| `minimal-fixture-joined-class-both` | 移除反射/门闩/DEBUG，仅最小补丁的原34/36测试类 | 34/0/0/0 | 原single与全部业务断言通过 |

实际隔离运行入口（脚本留在本机诊断证据目录；标准仓库入口为 `apps/android/gradlew :app:testFullDebugUnitTest --tests com.openandroidintelligence.mobile.NativeAgentBackgroundWorkTest`，使用既有离线JAR及JDK21/SDK36缓存配置）：

```bash
bash run-focused.sh controlled-real-stream-cancellation com.openandroidintelligence.mobile.NativeAgentBackgroundWorkTest.controlledCancelledFixturePublishesAfterTheNotificationReset
bash run-class-both.sh controlled-real-stream-fixture-joined
bash run-class-both.sh minimal-fixture-joined-class-both
```

脚本保留现有缓存、离线Robolectric JAR与FullDebug变体。`run-focused.sh` 固定SDK34；`run-class-both.sh` 保留类注解SDK34/36。最终一次移除反射/门闩/DEBUG，仅应用最小Fixture补丁的原整类结果为34/0/0/0，JUnit 17.658秒；Gradle含增量编译耗时1分5秒。

## 最小安全候选

仅修改 `NativeAgentBackgroundWorkTest.Fixture`：保留 `SupervisorJob` 引用，在现有释放测试流、恢复dispatcher、取消scope、关闭gateway之后，使用已有 `awaitState` 驱动Android主Looper并等待owner完成。这样同步回调必须先退出，测试清理才返回。正式补丁位于 `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NativeAgentBackgroundWorkTest.kt`。

不清空产品通知、不修改通知tag、不把single改为singleOrNull，不通过忽略多通知解决断言；不改生产生命周期。候选与 `ColdStartConnectionTest.savedLoginWithHistory` 中已有取消scope后等待owner.isCompleted的模式一致。

## 整合与关闭边界

1. 原红环与绿环源码、XML、日志仅保留在隔离证据目录，仓库只纳管本摘要和固定字段计数。临时门闩、反射及DEBUG未纳入正式测试。
2. PR178按测试修复范围整合最小清理补丁，不修改生产通知或原single及业务断言。
3. 独立Standards/Spec评审并运行相关门禁后，再执行最终Full。此次本地结果不能替代远端最终Full。
4. #177在最终Full完成前保持开放；CI当次第二条通知来源未捕获的边界应继续保留。
