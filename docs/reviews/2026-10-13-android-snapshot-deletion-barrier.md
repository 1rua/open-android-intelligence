# Android 历史快照明确删除的持久屏障与恢复验证

日期：2026-10-13。源码基线：`d5e1838a04e8508845bb4fc6529e8edfcb776330`。证据来自本轮历史恢复修复工作区，包含并行完成的缩略图和附件事件解码修复；尚未提交的测试结果不替代最终提交的 CI、部署与手机验收。

## 发现与复现范围

在补充 Hermes Native 历史恢复后，宿主会为明确移除的消息返回同一 `messageId`／`clientMessageId`，且 `state=DELETED`、正文为空。现有 Android 代码会依次出现以下问题：

1. 加密镜像的持久投递状态把 `DELETED` 覆盖成 `completed`，随后本地发送记录把原始正文和附件补回。同一消息已被宿主删除，重新进入会话后仍显示旧内容。
2. 仅修正加密镜像不能清除正在显示的 Workbench 缓存。用户和助手的旧行仍可能留在当前界面，下一次写检查点又将旧行保存。
3. 冻结批次恢复没有检查删除事实。用户确认查询旧批次、查询返回空结果时，客户端会再次提交已删除的原成员。

这三项都通过生产 `MirroredConversationRepository`、真实 `EncryptedConversationMirror` 和 Workbench 公共接口先复现为失败，再验证修复。没有操作生产账号、Gateway 服务或手机；测试宿主仅替代远端边界。

## 删除语义与实现

`LocalSendAtom.snapshotDeleted` 保存“宿主快照已明确删除此消息身份”的事实；旧记录缺少此字段时按 `false` 读取。它与现有 `tombstoneRevision` 分开保存：

- 明确快照删除没有单条内容修订，不使用分页 `snapshotRevision`，也不创建一个虚假的消息修订。已保存的 `contentRevision` 保持原值。
- 空快照、缺少正文的受理查询和未知发送结果均不构成删除。它们继续恢复本地发送的原始内容与身份。
- 当前没有快照撤销删除契约。明确快照删除属于该消息身份的持久屏障，普通内容 upsert、投递状态、旧 HTTP 回执和较晚的空页都不能撤销它。再次发送属于用户确认的新提交，使用新的身份。
- 现有带修订号的 `TimelineTombstoned` 仍按原规则比较修订；更高内容修订可以恢复该普通事件墓碑。回归已确认它不会被永久化成 `snapshotDeleted`。

内部 `TimelinePage.deletedMessageIds` 只透传已有明确删除行的身份，供完整分页、加密镜像和当前界面同步使用。Wire／核心 Schema 未改变，仍使用 Gateway Protocol 2.1。完整分页只有在整代快照读取成功后才返回删除事实；游标过期的尝试不会混入下一代快照。

加密镜像先保存删除屏障和待清理附件 ID，再清空发送记录中的正文及附件、清除 legacy 行、时间线行、检查点和恢复快照中的旧正文。相同快照可以在写入失败后重试。旧检查点的草稿、可读历史、未删除附件和未删除提交继续保留。较晚写回的旧发送记录也必须重新通过屏障。

Workbench 读取快照后刷新持久发送记录，清除同身份的当前用户／助手缓存与缩略图。Mirror 将受快照屏障保护的迟到内容事件投影为无正文的明确删除行，防止助手消息在重启后的事件重放中复活。投递状态不能覆盖删除状态。

## 冻结批次与未知结果

原批次成员集合、顺序、`clientMessageId` 和 `batchId` 不变。已删除成员的正文和附件从冻结副本中清空；未删除成员的原稿和未知状态继续保存。客户端禁止重发含已删除成员的原批次，也不会删掉一个成员后用旧 `batchId` 提交剩余成员。

全部成员都已明确删除时，原批次不再显示为可恢复发送。混合批次只查询未删除成员；未取得确认时显示“原批次含已删除消息，只能查询其余成员，禁止重发”，不将未知结果伪装成失败或完成。普通未删除未知发送继续遵循先查询、后由用户确认、复用原消息和批次身份的规则。

## RED → GREEN 证据

| 公共流程 | 修复前 | 修复后 | 证据 |
|---|---:|---:|---|
| 原正文＋附件＋完成状态，收到明确删除快照 | 1 运行／1 失败 | 1 通过 | [RED](evidence/2026-10-13-history-deletion/snapshot-deletion-red.txt)、[GREEN](evidence/2026-10-13-history-deletion/snapshot-deletion-green.txt) |
| 已显示 user＋assistant，刷新删除快照并重新进入 | 1 运行／1 失败 | 两项累计 2 通过 | [RED](evidence/2026-10-13-history-deletion/workbench-deletion-red.txt)、[失败 XML](evidence/2026-10-13-history-deletion/workbench-deletion-red.xml)、[GREEN](evidence/2026-10-13-history-deletion/workbench-deletion-green.txt) |
| 确认恢复全部已删除的冻结批次 | 1 运行／1 失败 | 1 通过 | [RED](evidence/2026-10-13-history-deletion/frozen-batch-deletion-red.txt)、[失败 XML](evidence/2026-10-13-history-deletion/frozen-batch-deletion-red.xml)、[GREEN](evidence/2026-10-13-history-deletion/frozen-batch-deletion-green.txt) |

首次沙箱内 Gradle 尝试仅发生本机通信端口权限错误，未执行测试，不计入 RED。允许既定离线测试的本机端口后，上述三次 RED 均为真实行为断言失败。首次 GREEN 编译中的跨模块 Kotlin 属性读取错误已修正；表中的 GREEN 为修正后的实际测试结果。

## 最终最小回归矩阵

| 测试类 | 运行 | 失败／错误／跳过 |
|---|---:|---:|
| `EncryptedSendAtomRecoveryTest`，包含新增 8 项删除、批次、迁移与分页回归 | 19 | 0／0／0 |
| `EncryptedMirrorRecoveryTest` | 2 | 0／0／0 |
| `WorkbenchSendRegressionTest` | 37 | 0／0／0 |
| `WorkbenchSendPersistenceTest` | 14 | 0／0／0 |
| `WorkbenchAttachmentPreviewRegressionTest` | 9 | 0／0／0 |
| `GatewayEventDecoderTest` | 29 | 0／0／0 |
| 合计 | **110** | **0／0／0** |

新增回归同时覆盖 user／assistant 迟到 upsert、较晚 status／HTTP 回执、空 baseline 恢复和 reopen；混合冻结批次完整成员身份、仅查询未删除成员、原稿与新草稿保留；普通更高修订事件恢复；旧版本明确删除行迁移；完整分页的删除身份汇总以及过期尝试重新开始。

从 `apps/android` 执行：

```bash
GRADLE_USER_HOME="$PWD/../../.toolchains/gradle-user-home" ./gradlew \
  :app:testFullReleaseUnitTest \
  --tests '*EncryptedSendAtomRecoveryTest' --tests '*EncryptedMirrorRecoveryTest' --rerun \
  :conversation-ui:testReleaseUnitTest \
  --tests '*WorkbenchSendRegressionTest' --tests '*WorkbenchSendPersistenceTest' \
  --tests '*WorkbenchAttachmentPreviewRegressionTest' --rerun \
  :conversation-data:testReleaseUnitTest --tests '*GatewayEventDecoderTest' --rerun \
  --offline --no-daemon --no-build-cache --console=plain
```

结果：`BUILD SUCCESSFUL`。所有选定测试任务实际执行，未使用测试结果缓存；只运行相关最小测试，不执行全量 Gradle 检查或 APK 构建。完整 [任务日志](evidence/2026-10-13-history-deletion/final-focused-matrix.txt)、[各类实际 XML 计数](evidence/2026-10-13-history-deletion/final-focused-counts.json) 和六份 XML 均随本报告保存在仓库内。

## 验证边界

本报告证明源码中的加密持久化、Workbench 显示、删除与恢复兼容。Hermes Native 宿主投影和真实 Android HTTP 互通由本轮其他报告记录；此处不将测试宿主回复当作真实模型回复。最终提交审查、CI、APK、两处 Gateway 部署和手动实机验证由主 Agent 单独汇总。

此前已丢失、没有稳定身份映射的正文或已经释放的原始媒体不能凭空重建。删除修复只按明确消息身份处理，不按相似文本猜测归属，不改变账号、配对、密钥或附件加密格式。
