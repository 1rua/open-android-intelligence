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

## 源码修复

`EncryptedConversationMirror.mergeAtom` 现在将 `messageId == clientMessageId.value` 识别为明确客户端占位，允许同一客户端原子采用新取得的原生身份；旧 steering 占位兼容继续存在。若磁盘已经绑定原生 ID，迟到的普通或 steering 占位仅合并既有内容和状态事实，不改回占位。

这项变更位于持久原子合并边界，不增加身份推测。不同原生 ID 仍报 `MESSAGE_ID_CONFLICT`，同一原生 ID 绑定不同客户端原子也仍使事务回滚；跨会话记录继续报 `MESSAGE_CONVERSATION_CONFLICT`。正文修订、Agent 状态修订、删除屏障和首次时间的原有选择规则保持。

## 已取得的验证

- 原实现的最小真实镜像探针：1 项失败，错误与现场一致。
- 新增镜像回归基线：4 项中 3 项失败、1 项旧占位回归通过；三个失败都落在同一持久身份合并拒绝路径。
- 初修后真实镜像 17 项、删除持久化 6 项，共 23 项通过。覆盖取消后磁盘重开、重复权威历史、已接受身份升级、旧占位延迟回写、真实 ID/跨客户端/跨会话冲突回滚及旧 steering 兼容。
- 补充工作台回归通过真实 `CoreAgentConversationRepository → NativeConversationMirror → WorkbenchController`，从磁盘已有取消占位开始，读取原生历史后只显示一条真实 ID，且没有任何 `POST`。此回归使用合成传输和真实镜像，不能替代最终模拟器验收。

原症状工作台回归已用旧实现重放，1 项失败，明确命中“合法原生历史不能被镜像身份合并拒绝”的断言；脚本随后恢复修复源码。最后再执行镜像 18 项和删除持久化 6 项，共 **24 项通过**。同一回归同时断言没有发送任何 `POST`，故不能靠消息重投通过。

`npm run test:fast` 通过：包含静态与契约门禁、内置 DSH 120 项通过与 3 项可选宿主契约跳过、Android 越权面门禁。首次 `npm run test:pr` 因独立工作树未设置历史 Hermes 夹具路径，五项互通测试报 `HERMES_FIXTURE_MISSING`；保留该次失败记录，随后按现有 CI 配置显式复用主仓固定夹具与 SDK 后重跑。

补齐夹具后，PR 门禁模块阶段通过：会话领域 47 项、会话界面 855 项、会话数据 61 项，共 **963 项通过**，包括全部 5 项真实 Hermes HTTP/SSE 互通。App 首次广覆盖阶段为 710 项、25 项失败，XML 已确认环境原因：`Android SDK 36 requires Java 21 (have Java 17)`，以及独立工作树缺少编译后的 `notifications.wasm`。没有降低 SDK、修改断言或记作成功。

随后复用已有 JDK 21.0.10，以及主任务已核验的 CI `43055128442` 六个真实签名包中的 WASM。插件源码与该 CI 提交逐字比较无差异，载荷只放入忽略的构建目录；无需安装依赖或改变生产源码。使用 CI 同款固定 Hermes 夹具与 SDK、原 Gradle 和 Robolectric 缓存重跑 `npm run test:pr`，最终退出 **0**：App **1258 项全部通过**，上述模块 **963 项全部通过**，静态/契约/越权面门禁及内置 DSH 测试也通过。最终证据见 [结果摘要](../reviews/2026-11-12-history-placeholder-artifacts/results.json) 与 [PR 门禁摘要](../reviews/2026-11-12-history-placeholder-artifacts/pr-jdk21-green.txt)。

独立审查与原隔离模拟器数据回读仍待主任务完成，不能用上述受控测试代替真实 APK 验收。
