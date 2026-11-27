# DSH 取消错误终帧的原身份恢复

日期：2026-11-12。关联 OAI #179、#180、#142；基线 `2caabfb1206fd93cd0232171f0bf287fdfeea498`。本次实现与聚焦验证没有操作模拟器、服务、私人数据或真实模型，没有修改根仓 dev。

## 确认根因

DSH 把原生 `aborted` 及持久取消回放投影成 `error/code=CANCELLED`，而非 `done/outcome=cancelled`。Core 保留错误事件；Android `AgentSessionClient` 转换成 `AgentSessionException(CANCELLED)`。原 `CoreAgentConversationRepository` 的按身份恢复错误名单缺少 CANCELLED，因此走普通失败分支：留下队列屏障与从未发送的后继，但移除原 turn，不再保留恢复所有者。

原生仓储没有恢复所有者且 DSH 未开启 eventSync 时，`connectionRestored()` 只重启读取而无读取可重启；工作台只在仓储健康转 LIVE 时调用队列重试，全局平台 LIVE 不会自动改变仍 FAILED 的仓储。这与真实现场的 `FAILED + queued=1 + barrier=1 + turns/recoveryJobs=0` 一致。

固定源码位置：

- DSH 终帧：`integrations/dsh-session-adapter/index.js:26`、`:466`、`:533`。
- 客户端错误转换：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/agents/AgentSessionClient.kt:462`。
- 仓储分类与移交：`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt:1569`、`:1592`。
- 连接恢复与安全重试：同文件 `:324`、`:1726`。
- 工作台恢复触发：`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt:1779`。

## 受控红绿与范围

[固定摘要](2026-11-12-native-cancel-recovery-artifacts/controlled-red-green-summary.json)记录源字节摘要、编译类摘要、三种终帧结果及两次相同红环。合成传输调用真实客户端与仓储，不自行实现队列逻辑，不访问网络或设备。

| 场景 | 原版结果 | 实验候选结果 |
| --- | --- | --- |
| `done/cancelled` | 自动按原身份恢复，后继各一次 | 保持 |
| DSH 实际 `error/CANCELLED` | FAILED、pending1、barrier1、owner0；三次连接恢复及90秒虚拟时间均无新GET | 查询原client与generation，权威取消后后继各一次，取消事件准确 |
| 指定回执仍unknown | 未重投，后继受屏障保护 | 保持 |

原 client 外部只读回读证据为 `state=cancelled sameClient=true sameGeneration=true resubmitted=false`。原版显式队列重试在安全 currentGeneration 为空后能排空从未发送的后继；这个对照不能被泛化为允许自动重发未知输入。

正式新增 `NativeCancelledStreamRecoveryTest`：修复前实际 **5项，4失败、1通过、0跳过**；最小修复后 **5项通过、0失败、0跳过**，见[白名单结果](2026-11-12-native-cancel-recovery-artifacts/formal-regression-public-summary.txt)。覆盖原身份确认前不投递、查询暂时失败保留、unknown防重投、相同会话双Agent隔离、未协商恢复能力不伪造取消。新类已登记 `apps/android/ci/app-regression.txt` 的 conversations 分组。

上述正式类红绿使用已有 Kotlin2.1.20/JUnit4.13.2 编译运行，本分支真实生产仓储源码与固定2caab依赖组合。标准 Gradle 聚焦命令另已尝试：

```sh
./apps/android/gradlew -p apps/android --offline --max-workers=2 \
  :app:testFullDebugUnitTest \
  --tests com.openandroidintelligence.mobile.conversations.NativeCancelledStreamRecoveryTest
```

前两次标准 Gradle 尝试受既有缓存 `module-metadata.bin` 不可读与 wrapper `.lck` 只读限制，没有进入测试；保留为环境失败。主任务随后使用上一轮实际可写 Gradle 缓存、已有 JDK21/SDK36、离线 Robolectric JAR 配置运行同一命令，实际 **5项通过、0失败、0错误、0跳过**，`BUILD SUCCESSFUL in 1m 14s`，443个任务中177执行、266来自缓存。该结果为真实 Gradle 聚焦回归，不能替代 PR/Full 或真实 DSH 端到端。

## 最小修复与后续验收

只有当宿主已协商 `generationRecovery` 且已有原生受理用户身份时，将 CANCELLED 接入现有 AGENT_FAILED 的按原身份恢复分支。原 client/generation 查询、历史恢复、本机提交、取消事实发布和 FIFO 排空继续使用既有路径。错误码本身不直接确立终态；未协商能力、查询失败和unknown继续保守保护，不新增首条 POST，不清理任意屏障。

本次没有广泛更改平台LIVE的行为。#180 的全局唤醒假设在本次具体现场中是分类遗漏的后续表现，不能以本次修复宣称所有无owner队列均已覆盖。发布前仍需独立审查、PR门禁、固定最终APK的真实DSH重启复验，并按原身份验证后继各一次及全过程存储状态；当前不关闭总跟踪。
