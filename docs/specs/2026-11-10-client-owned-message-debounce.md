# 消息合并改为纯客户端配置

日期：2026-11-10。状态：已实现，待合并。
关联 issue：[#123](https://github.com/1rua/open-android-intelligence/issues/123)。
关联文档：[设置系统](../architecture/2026-11-05-android-settings-system.zh-CN.md)、
[设置备份与恢复](../architecture/2026-11-08-android-settings-backup.zh-CN.md)、
[ADR-0045](../adr/0045-preserve-send-atoms-and-merge-agent-input-in-debounce-batches.md)、
[ADR-0062](../adr/0062-own-message-merging-in-the-android-client.md)。

## 一、问题

「高级设置与关于 → 消息处理」把合并策略拆成了三块：全局默认、按当前 Core 覆盖、
以及一个「新消息延长等待」开关。按 Core 覆盖让用户以为这是 Core 服务端的配置，
但它实际上一直只存在手机本地；默认 1.5 秒也让「点发送却没马上发出去」成为默认体验。

## 二、目标行为

| 项目 | 行为 |
|---|---|
| 默认值 | 0 秒，点击发送立即提交，不等待、不合并 |
| 可配置范围 | 0～30 秒，每秒一档 |
| 单批上限 | 20 条；总字节仍受协议批次上限约束（64 KiB） |
| 硬上限 | 从首条起最长 30 秒，之后必须提交 |
| 作用范围 | 仅同一会话；不跨会话、账号、Agent 合并 |
| 与 Core 的关系 | 只写本机偏好，不发任何 Core 配置请求 |
| 旧数据 | 旧的按 Core 覆盖键不读取、不迁移；旧备份文件的合并值无法区分是否用户主动设置，整节不导入并如实告知 |

界面上「消息处理」只剩一组：

- 标题「合并连续消息」；
- 当前设置一行（`立即发送（不合并）` 或 `等待 N 秒合并`）；
- 0～30 秒滑块；
- 一句说明：「设置为 0 秒时立即发送；大于 0 秒时合并短时间内连续发送的消息。」（按需求原文，
  30 秒 / 20 条上限由实现保证，不写进这句说明。）

删除「全局默认」「覆盖当前 Core 的设置」「当前 Core：…」「当前 Core 使用全局默认」「未选择 Core」
「新消息延长等待」「当前 Core 覆盖」这些入口与文案。

## 三、实现位置

| 关注点 | 位置 |
|---|---|
| 偏好读写 | `apps/android/app/.../conversations/DebouncePreferences.kt`，键 `global:delay` |
| 合并窗口 | `apps/android/conversation-domain/.../batch/DebounceBatcher.kt` |
| 发送分支 | `apps/android/conversation-ui/.../state/WorkbenchController.kt` 的 `if (supportsMessageBatches && batcher.merges …)` |
| 运行时装配 | `GatewayRuntime` 的 `updateDebounceSettings(delayMillis)` / `reloadDebounceSettings()` |
| 设置界面 | `apps/android/app/.../settings/advanced/MessageProcessingSettings.kt` |
| 备份 | `MessageProcessingSection(delayMillis)`；旧文件的 `global`/`cores` 只计数不写入 |

### 0 秒为什么不能走批次

零时长的批次虽然会在同一次调用里 `flush`，但成员要先写进 `collectingUnits`、封存一个批次 id
再提交，一次发送多绕一层持久化与恢复路径。因此 `DebounceBatcher.merges` 明确表达
「当前策略是否要合并」，控制器在 0 秒时直接走原有的单条发送路径。

注意 0 秒仍然会在点击时写入 `pendingBatch` 作为待发送行：这是既有的本地先行显示，
与是否合并无关；受理或失败后该行照常移除。区别只在于不进入合并队列、不以批次提交。

### 设置改回 0 秒时

`updatePolicy` 在时长归零时会立刻 `flush` 已收集的批次：否则新消息按 0 秒直接发出、
旧批次仍在等原窗口，会话里会出现「后发的先到」。改大或改小时长不影响已封存的批次。

### 待发送消息的状态

`> 0` 秒时，成员在窗口内仍以 `pendingBatch` 与 `LocalSendAtom` 两份事实存在：
前者驱动输入坞上方的「N 条 · 等待合并」计数条，后者负责崩溃恢复。时间线上该行仍显示
「等待 Gateway 接收」——「等待合并」只是批次条的计数文案，不是单条消息的状态。窗口到期后
`submitBatch` 用同一个 `batchId` 原子提交；失败或结果未知时保留原身份供用户确认重试，
不伪造成功。

0 秒路径没有合并批次可保留：发送失败时该行从待发送列表移除、正文回填输入框，用户在
原处重试即可。两者都不丢消息、不重复提交，区别只是恢复入口不同（批次恢复 vs 草稿回填）。

回填后**未再改动**的草稿仍绑定原 `clientMessageId`：再次点击走同身份先查询，避免远端
其实已受理时被当成第二条执行。用户改过正文（修订号前移）才视为新的发送；引导（STEER）
不受此约，每次点击都是一次独立操作。

## 四、不改变的部分

- Gateway 协议 `POST …/message-batches`（`joinMode: newline-v1`）不变；
- 斜杠命令与含附件的发送仍然是硬边界，直接单条发出；
- 同一会话的批次仍按 FIFO 排队，不同会话的窗口互不影响；
- 排队 / 引导交互、流式渲染、Core 与 Agent 的排队策略都不动。

## 五、验收对照

| 验收项 | 覆盖方式 |
|---|---|
| 全新安装与升级后默认 0 秒 | `MessageProcessingSettingsTest.freshInstallDefaultsToImmediateSendWithoutMerging`、`legacyPerCoreKeysDoNotChangeTheClientSetting`、`DebouncePreferences` 默认值 |
| 0 秒不额外等待 | `DebounceBatcherTest.the default policy sends every message immediately`、`zero delay never holds a batch for the timer` |
| 开启防抖后正确合并 | `DebounceBatcherTest.one conversation still aggregates its own members`；`the twentieth member flushes the batch` 同时验证第 21 条不会丢失 |
| 不产生 Core 配置请求 | 运行时只写本机 SharedPreferences；无新增网络调用 |
| 不同客户端互不影响 | 配置只存在各自手机的 `conversation-debounce-v1` |
| 旧备份不意外开启合并 | `SettingsBackupCoordinatorTest.messageProcessingDelayRoundTripsAndLegacyMergingValuesAreReportedNotImported` |
| 不丢失 / 重复 / 乱序 / 错合并 | `DebounceBatcherTest.two conversations keep separate batches`、`turning merging off flushes what is already collected`、`WorkbenchSendPersistenceTest`、`WorkbenchCloseSubmissionTest` |
| 界面无 Core 覆盖入口 | `MessageProcessingSettingsTest` 断言相关文案不存在 |
| 单测与回归 | `:conversation-domain:testDebugUnitTest`、`:conversation-ui:testDebugUnitTest`、`:app:testFullDebugUnitTest` |
