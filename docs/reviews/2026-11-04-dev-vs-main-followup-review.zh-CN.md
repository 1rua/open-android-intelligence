# dev 相对 origin/main 的复审（PR #81 合入后）

## 审查范围

- 审查日期：2026-11-04，Asia/Shanghai。
- 比较基点：`d88fffed134f57c5cf6ca4733e65b61f6e13120d`，即用户指定的 `origin/main` 合并基点。
- 被审查提交：`dev@54de1abbcb1312be6f815bcb044f44d62cff18b3`。
- 审查覆盖 Android 会话与账号状态、原生会话适配器、设备执行与屏幕控制、跨 Core 调度、安装与验证入口。
- 本报告记录本轮新增确认的 9 项问题，不重复上一轮已由 PR #81 修复的 12 项问题。上一轮结论及修复证据分别见[原审查报告](2026-11-04-dev-vs-main-code-review.zh-CN.md)和[修复记录](2026-11-04-review-fixes.zh-CN.md)。
- 本轮为审查与证据归档，没有实施以下修复，也没有提交、推送、发布或部署。文中源码行号均对应上述被审查提交。

固定范围可在仓库根目录查看：

```bash
git diff d88fffed134f57c5cf6ca4733e65b61f6e13120d 54de1abbcb1312be6f815bcb044f44d62cff18b3
```

## 结论与证据分级

确认 **9 项 P2 问题，均待修复**。

- **入口探针确认**：通过生产入口和合成输入观察到缺陷所需行为；测试替身、真实宿主 SDK 和 Android 调用链各自的证明范围在问题中注明，不等同于真机或生产部署复现。
- **源码确认**：当前代码及调用链能够确认问题，未运行对应 Android 界面、网络时序或设备验收。
- **建议**：下文修复方向与回归场景用于后续实施，不表示已经修改或通过验证。

| 编号 | 优先级 | 问题 | 证据 | 状态 |
| --- | --- | --- | --- | --- |
| F01 | P2 | DSH 取消丢弃执行轮次身份，迟到请求可能取消新任务 | 生产 HTTP handler 探针；宿主取消接口使用记录参数的替身 | 待修复 |
| F02 | P2 | 请求隔离后仍永久保留不可再交付的敏感结果 | 源码确认 | 待修复 |
| F03 | P2 | 宿主撤回消息后，旧正文事件阻塞同步消费位置 | 真实 Hermes SDK 与生产历史、事件代码探针；Android 检查路径为源码确认 | 待修复 |
| F04 | P2 | 屏幕控制请求在进入 FIFO 队列前被插件并发门拒绝 | 当前内核代码与合成运行时探针；生产队列位置为源码确认 | 待修复 |
| F05 | P2 | 切回会话草稿后恢复过期附件状态，发送不再推进 | 源码确认 | 待修复 |
| F06 | P2 | 明确连接失败后，本地工作台仍在但账号切换入口消失 | 源码确认；核对既有失败状态测试断言 | 待修复 |
| F07 | P2 | Agent 恢复在线后，命令目录仍使用旧描述 | 源码确认 | 待修复 |
| F08 | P2 | 顺序连接发现耗尽整轮预算，后方健康 Core 无法推进 | 源码与超时预算确认，未运行真实慢网络场景 | 待修复 |
| F09 | P2 | 分级测试识别插件改动，却没有运行插件测试 | 生产测试计划函数探针 | 待修复 |

## 问题详情

### F01：DSH 取消请求未绑定执行轮次

**位置**：[index.js:338–340](../../integrations/dsh-session-adapter/index.js#L338)。

**触发场景与影响**：旧轮次的取消请求在同一会话的新轮次开始后到达。Core 与 Remote 已将 `generationId` 放入 `body.value`，DSH 适配器却忽略该值，仅调用 `controller.cancel({sessionId:id})`，因此取消目标成为宿主当前任务。

**证据**：核对了 [Core 取消入口](../../oai-core/src/oai_core/sessions.py#L1086)及 [Remote 转发](../../oai-core/src/oai_core/adapters/remote.py#L386)，两者没有替 DSH 校验当前宿主轮次。通过生产 `createSessionHandler()` 发起带旧 `generationId` 的 HTTP 请求，返回 200，记录到的宿主参数只有 `sessionId`。该探针没有启动真实 DSH 宿主。

**修复方向**：在实际取消操作处校验并传递真实执行轮次；若宿主接口无法保证按轮次取消，则不要声明相应能力。取消应符合[绑定真实 generation 的约定](../architecture/2026-10-30-native-event-sync.zh-CN.md#L7)。

**回归场景**：旧轮次已完成且新轮次正在执行时，提交旧 `generationId` 的取消请求；新轮次继续运行。补充当前轮次取消、重复取消及轮次切换竞态验证。

### F02：隔离日志永久保留不可再交付的敏感结果

**位置**：[DeviceExecutionDriver.kt:217–221](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt#L217)。

**触发场景与影响**：短信或通知读取成功，结果上传因断网失败；随后请求到期，或恢复时发现授权版本变化。`fence()` 复制整份日志，只增加 `stage=fenced`、取消标记和隔离原因，原 `result.data` 完整保留。

**证据**：[恢复扫描](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt#L243)在期限检查之前跳过所有 `fenced` 记录，后续恢复不会再清理正文。基点实现的对应到期、授权失效和终态路径会移除日志；新增隔离标记改变了这一行为。本项为源码确认，未执行 Android 存储恢复测试。

**修复方向**：隔离时保留用于防止重复执行的必要元数据，清除不可再交付的敏感正文。不得通过移除隔离事实而允许旧请求重新执行。

**回归场景**：保存含合成敏感正文的成功结果，模拟上传失败后请求到期或授权失效；恢复及重启后，正文已清除，隔离事实仍有效，插件不会再次执行。

### F03：撤回消息使旧正文事件永久无法通过覆盖检查

**位置**：[NativeEventSynchronizer.kt:131–136](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/NativeEventSynchronizer.kt#L131)。

**触发场景与影响**：待消费事件页包含一条消息的正文修订，但读取补偿历史前，该消息已被宿主撤回。覆盖检查要求同 ID 历史消息的 `contentRevision` 不小于事件修订；Hermes 历史对 `DELETED` 消息[明确移除该字段](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/host_history.py#L813)，因此检查必然抛出 `NativeHistoryBehind`。

**证据**：真实 Hermes SDK fixture 配合生产消息发布、历史和事件页代码，得到同一消息的事件 `revision=1`，而历史已为 `DELETED` 且不含 `contentRevision`。Android 的检查发生在保存及 ACK 之前，原页会被保留；[连续六次失败后同步任务退出](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt#L376)。Android 部分为源码确认，未运行其测试。

**修复方向**：让可信的撤回终态能够覆盖旧正文事件，同时保留普通消息的修订与作用域检查。消费与恢复仍须遵守[先保存、后 ACK 的约定](../architecture/2026-10-30-native-event-sync.zh-CN.md#L49)。

**回归场景**：先记录正文事件，再在消费前撤回同一原生消息；同步后保存撤回状态并推进原页 ACK，随后发布的新消息能够继续同步，旧正文不得恢复。

### F04：屏幕控制 FIFO 位于插件并发拒绝之后

**位置**：[screen-control/manifest.json:50](../../plugins/screen-control/manifest.json#L50)。

**触发场景与影响**：两个已获本地授权的 Agent 同时通过内置屏幕控制插件提交操作，第一项尚未返回。该插件并发上限为 1；[PluginKernel](../../apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PluginKernel.kt#L286)先执行 `semaphore.tryAcquire()`，第二个请求直接得到 `BUDGET_EXCEEDED:CONCURRENCY`，无法到达 [RemoteScreenRuntime 中的 FIFO 租约队列](../../apps/android/device-primitives/src/main/kotlin/com/openandroidintelligence/device/primitives/RemoteScreenRuntime.kt#L369)。

**证据**：定向编译当前提交的内核源码，用合成运行时保持第一项调用未结束，再发起第二项，结果为 `second=BUDGET_EXCEEDED:CONCURRENCY; runtimeCalls=1`；主审复跑现有编译产物得到相同结果。该探针验证内核提前拒绝，生产屏幕队列的位置通过源码确认，没有操作真实屏幕。

**修复方向**：将控制请求排队放在插件并发拒绝点之前，出队后重新核实授权、租约和屏幕前置条件；保持底层执行互斥。行为要求见[全局授权规格的 FIFO 与出队鉴权条款](../superpowers/specs/2026-11-01-global-device-authorization.md#L403)。

**回归场景**：通过真实宿主调用链提交两个控制请求，第二项等待并按 FIFO 执行；等待期间撤权、取消或前置屏幕状态变化后，应拒绝执行。

### F05：切回草稿时恢复过期附件上传状态

**位置**：[WorkbenchController.kt:1874](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L1874)。

**触发场景与影响**：在会话 A 添加附件但尚未发送，切到 B，等上传完成后返回 A。此处从 `savedComposer` 恢复仍为上传中的旧快照；随后点击发送，会因附件状态不是 `VERIFIED` 停在 `WAITING_ATTACHMENTS`。

**证据**：[observeAttachment()](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L2767)只更新活动附件与冻结提交，没有更新 `composerDrafts[A]`；[回切时重新订阅](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L1892)仅发生在 `restored` 分支，内存草稿不在其中。[发送前检查](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L3084)又只读取这些附件快照。本项为源码确认。

**修复方向**：恢复内存草稿时，从附件协调器同步真实状态或重新订阅，并保持附件、草稿和发送操作属于原会话。

**回归场景**：A 上传中切到 B，分别在上传完成、失败及取消后返回 A；状态与真实协调器一致，已完成附件只发送一次，B 的草稿不受影响。

### F06：连接失败时隐藏本地账号操作

**位置**：[SettingsViewModel.kt:48–49](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsViewModel.kt#L48)。

**触发场景与影响**：例如恢复时协商返回 `PROTOCOL_INCOMPATIBLE`，Runtime 保留 `LocalSessionState.Workspace`，但将连接阶段设为 `Failed`。`hasLocalSession` 仅识别 `Connected` 与 `OfflineMirror`，因此设置隐藏整个账号操作区域；[主界面](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L571)仍显示工作台，登录页的账号选择器也不可达，用户无法从现有入口切换到其他账号或 Core。

**证据**：[ColdStartConnectionTest](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/ColdStartConnectionTest.kt#L236)明确断言协议不兼容时保留本地工作台和账号上下文；[SettingsScreen](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt#L1268)却以此属性控制“退出登录”“切换账号”等入口。该测试本轮仅核对源码，未运行。

**修复方向**：根据真实本地会话上下文决定账号入口是否可见，将本地账号管理与远端连接成功状态分离。此处涉及[AGENTS.md 第 26 行](../../AGENTS.md#L26)要求保留原有界面能力的规则。

**回归场景**：协议不兼容或恢复暂停后保留本地工作台时，仍可退出当前账号、进入账号选择并切换到另一 Core；凭据和历史的保留范围不变。

### F07：命令目录永久捕获创建工作台时的 Agent 描述

**位置**：[GatewayRuntime.kt:1912–1915](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt#L1912)。

**触发场景与影响**：创建工作台时所选 Agent 离线。之后同一 Agent 恢复在线，工作台未重建；目录仓库仍持有 `ownerAgent.online=false`，重试目录读取也不会发出 HTTP 请求。

**证据**：`currentAgent = { ownerAgent }` 捕获固定描述，而[同 ID 工作台的更新逻辑](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt#L2035)不会因上线而重建。[AgentSessionClient.commands()](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/agents/AgentSessionClient.kt#L146)在发送请求前检查能力和在线状态，因此持续拒绝旧描述。本项为源码确认。

**修复方向**：保留固定 Agent ID，从所属发现控制器按该 ID 取得最新在线状态及能力。不要改成随全局所选 Agent 变化，以免重新引入错误请求归属。

**回归场景**：离线 Agent 下创建工作台，随后同 ID 上线，重试命令目录应成功；在线期间能力更新应反映到目录行为，切换 Agent 时旧操作仍属于原 Agent。

### F08：顺序连接发现可能耗尽整轮调度预算

**位置**：[GatewayRuntime.kt:416–420](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt#L416)。

**触发场景与影响**：保存的账号列表中，多个前置 Core 接受连接但持续不返回响应，健康目标排在后面。`pushConnections()` 逐个执行网络请求，全部处理完才返回连接列表；后续设备恢复与推送统一等待它，且下轮仍从相同顺序开始。

**证据**：单次默认[读取超时为 30 秒](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayConnectionFactory.kt#L40)，而生产组合的[前台整轮预算为 45 秒](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/DevicePushComposition.kt#L128)，[后台整轮预算为 120 秒](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/DevicePushComposition.kt#L216)。两个这样的慢 Core 即可耗尽前台预算，多个慢 Core 也可耗尽后台预算，导致健康目标持续无法参与投递。本项为源码预算推导，未运行真实慢网络场景。

**修复方向**：隔离各账号发现任务的预算与失败，确保已成功发现的连接可继续推进；避免一个总的返回屏障使所有目标共同超时。

**回归场景**：多个慢 Core 排在健康 Core 前，分别验证前台和后台周期；健康目标仍能推进设备请求与推送，慢目标保留独立恢复状态。

### F09：插件影响标记没有接入实际测试计划

**位置**：[smart-test.py:110–111](../../tools/smart-test.py#L110)。

**触发场景与影响**：仅修改 `plugin-tooling/src/bundled-plugins.ts` 或 `build-references.ts`，按项目要求运行 `test:pr`。代码设置了 `affects_plugins=True`，但 `execute_strategy()` 从未读取该字段，也没有执行插件测试的分支。打包逻辑回归可能被报告为验证通过。

**证据**：使用内存命令记录器替换实际执行器，将插件文件改动传给生产计划函数；`fast` 与 `pr` 都只生成以下两项命令，`full` 也不包含插件测试或根目录 `npm test`：

```text
npm run mvp:lock:check
./gradlew --max-workers=2 noVpnSurfaceCheck
```

现有根目录 [package.json](../../package.json) 的 `npm test` 包含 `plugin-tooling` 测试，但新增分级入口没有接入。探针只记录计划，没有执行其中的 Gradle 或全量验证命令。

**修复方向**：将插件影响标记接入对应测试，让 `full` 无条件包含插件模块验证，并补充计划选择的回归断言。此处涉及[AGENTS.md 第 11–12 行](../../AGENTS.md#L11)规定的 PR 影响范围验证及发布全量验证。

**回归场景**：插件工具源码单独改动时，`fast`、`pr` 均运行对应插件测试并传递失败退出码；`full` 在任何改动范围下都包含该模块验证。

## 本轮已执行的验证

以下结果来自本轮审查的实际执行记录，均针对 `54de1abb`，不沿用上一轮 PR #81 的通过记录。

| 范围 | 实际选择 | 结果 | 证明范围 |
| --- | --- | --- | --- |
| 推送端点 | `oai-core/tests/test_push_endpoints.py` | 28 passed，21.19 秒 | 现有签名 HTTP、端点生命周期与投递回归；不覆盖 F08 的 Android 多 Core 调度 |
| 测试选择器 | `tools/test_smart_test.py` | 29 passed | 既有计划选择测试；F09 由额外内存计划探针发现 |
| 原生操作回执、事件及 Remote | `test_native_operation_recovery.py`、`test_native_event_sync.py`、`test_remote_generation.py`、`test_remote_stream_frames.py` | 50 passed，1 warning，4.11 秒 | 现有 Core/Remote 回归，不等同于 Android 消费覆盖检查 |
| Hermes 定向筛选 | `test_hermes_communication_reliability.py` 与 `test_hermes_interactions.py`，共同使用下述 `-k` 条件 | 12 passed，64 deselected，1 warning，1.51 秒 | 执行屏障、重启、压缩后继与断流的筛选用例 |
| F01 入口探针 | 生产 DSH HTTP handler；取消宿主接口为记录参数的替身 | 带旧 generation 的请求返回 200，宿主参数仅有 sessionId | 确认参数丢失；没有启动真实 DSH 宿主 |
| F03 宿主探针 | 真实 Hermes SDK 隔离数据与生产发布、历史、事件页代码 | 旧事件 revision=1，撤回历史无 contentRevision | 确认覆盖冲突的输入组合；Android 失败路径为源码确认 |
| F04 内核探针 | 当前内核代码与合成运行时；复跑现有编译产物 | 第二项返回并发预算错误，运行时只被调用一次 | 确认插件入口提前拒绝；没有操作真实屏幕 |
| F09 计划探针 | 生产 `analyze_impact()` 与 `execute_strategy()`，记录命令 | 插件被识别，但三个级别均无插件测试 | 确认选测遗漏；未运行生成的全量计划 |

定向自动化测试合计 **119 项通过**，其中 Hermes 的 12 项为筛选运行；上述探针不计入该总数。两次原生/Hermes 测试的 warning 均为 `bridge.py` 的 `aiohttp NotAppKeyWarning`。

Hermes 筛选条件为：

```text
running_or_unknown_turn_fences or restart_preserves_unknown or real_compression_successor or real_http_observer_disconnect
```

本轮没有执行 Android 界面测试、APK 全量构建、全量发布验证或真机验收，没有验证共享部署。Android 界面问题的证据级别维持源码确认；探针结果不扩大为整条生产链路已复现或已修复。

审查时的全范围 `git diff --check` 还报告既有审查文档中的冲突标记和若干空白问题；这些未作为上述 9 项功能缺陷，也没有在本轮修改。归档文档自身另行执行结构、相对链接、行号与空白检查，不能据此宣称整个比较范围的差异检查通过。
