# 本地原件与下载实现及验证边界

本文记录始于 2026-10-14 的 Android 媒体与下载子任务，2026-10-15 补齐独立审查发现的第三项媒体修订问题。当前是未提交源码：媒体变更队列、原件代数、旧流与下载发布保护已取得真实回归通过；最后将待办读取改为按资源类型分批查询，等待主代理补跑受影响的镜像回归和平台验证。此前阶段的通过记录不等于后续新补丁通过。

## 实现

- 持久媒体按资料、Gateway、账号、安装实例隔离；索引再按会话与附件身份隔离。同名附件身份不能借用另一会话的原件、缩略图或删除状态。
- 原件复用已有分块认证格式，保存到独立的 `noBackupFilesDir/history-media` 目录，不采用上传暂存的 TTL，不设置旧 8 MiB 单文件或 64 MiB 总量门槛。长度、SHA-256 与块认证必须通过，才发布本地索引。
- 用户确认发送后，在发送事实落盘、消息 HTTP 提交之前等待原件归档；归档失败保留草稿、意图和暂存。原件索引同一次提交保存 localDraftIds，可按会话及远端附件身份反查原本地草稿。`ensureSubmittedSource()` 只从持久原件重建已过期暂存，不联网或发送；`retrySubmittedSource()` 仅供明确手动重试，等待本次上传/核验任务完成，保留本地草稿身份，消息身份由控制器沿用。
- Agent 与历史附件的 metadata、cache-grant、content 三条签名地址均带 `format=stream-v1`；content 的 canonical query 以 format、grantId 排序，响应以媒体格式头标明原始流。旧 JSON 回退限制响应缓冲，未知媒体格式明确拒绝。
- 旧加密媒体逐项迁移：新原件和索引提交后才退休旧副本，失败保留旧内容。账号清理将本 scope 原件目录移至私有退休目录并销毁密钥，不让新实例重新读到旧活动内容。
- 消息正文、删除修订、事件游标及 `media-transition` 待办在同一 SQLite 事务提交。待办包含会话、消息、正文修订、附件身份及删除/恢复动作，只有媒体端完成后才确认退休；进程中断后按持久序号补做。读取通过 `documents.keys("media-transition")` 每批只认证媒体待办，逐条提交后再查询新加入的批次，避免逐条重扫全部元索引。Runtime 在恢复下载和已知附件之前等待 `mirror.applyPendingMediaTransitions(media)`，事件流开始和每次正文提交后也先处理待办。
- 原件保存消息修订与附件代数：普通墓碑 r2 后的可信正文 r3 能恢复同一附件身份，删除和恢复均使旧代数失效。下载开始前和原件读取前捕获代数，流的每次读取及下载最终发布都校验；发布与媒体变更共用提交锁。旧任务即使忽略协程取消、字节摘要相同，也不能提交到新的一代。
- 处理待办失败时，待办留在镜像中，私有媒体读写、预览与下载发布进入真实恢复屏障；正文和附件身份仍可阅读，已公开的下载不受影响。后续成功补做全部待办（包括确认已无待办）才解除屏障，不把失败冒充零项成功。
- 消息、附件库与浮动面板共用附件卡片，按类型显示图片或文件；去掉 `media_` 前缀判断和人工离线保留确认。纯附件消息不会被显示为没有文本，原件不可用时保留附件信息。
- 用户点击下载后，经 `MediaStore.Downloads` 写普通原件到 `Download/OpenAndroidIntelligence`，保留净化后的原名并让系统处理重名。平台扫描前的 DISPLAY_NAME 可能暂未同步为最终物理名称；不同 URI、不同物理路径及原始字节是同名不覆盖的判断依据，控制器成功提示只承诺下载目录。写入期间设置 `IS_PENDING`，完整性校验及文件同步成功后发布。
- 账号范围的加密日志覆盖创建条目、写入、核验和发布。真实进程终止可以重试未发布任务；首次恢复失败保留日志和 `DOWNLOAD_RECOVERY_FAILED` 提醒，独立的新下载可以成功并携带 recoveryWarningCode，不重复阻塞。显式恢复依然真实抛错并可重试，完成全部旧任务后才清除提醒，不以零项成功掩盖失败。
- 主动取消在 `NonCancellable` 中先记录取消事实，再以 `IS_TRASHED` 回收未发布条目。取消回收失败只能继续回收，不能重新复制或发布。解除配对/移除账号可调用 `cancelPendingDownloads()` 与 `wipePrivateDownloadJournal()`；已公开副本始终归用户所有，不改写或回收。

主要入口为 [持久原件库](../../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedHistoryMediaCache.kt)、[下载与恢复](../../../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/AndroidAttachmentDownloads.kt)、[通用附件卡片](../../../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt)。端口扩展位于 conversation-domain，上传源接线位于 GatewayAttachmentDraftCoordinator；控制器、镜像、Runtime 与服务端流式接口由对应子任务整合。

## 验证状态

| 证据 | 本轮状态 | 证明范围 |
|---|---|---|
| `git diff --check` | 已执行通过 | 补丁没有空白错误 |
| 媒体修订黑盒回归 | 修改生产前真实失败 | [本轮 RED 日志](android-20261015-storage-and-media-revision-red.txt)：App 4 项中普通恢复、旧流、冷重启补做失败，快照永久删除保护通过；UI 高修订附件保存次数失败 |
| 之前阶段 Android 定向回归 | 主代理取得 App 84 / UI 133 / Data 54 通过 | 早于本轮第三项 P2 补丁，不用于证明当前未提交源码通过 |
| 媒体定向回归 | 分批优化前的 61 项真实 XML 已通过 | [本轮 XML 摘要](media-download-current-unit-results.json)：含 8 项媒体修订、16 项下载、14 项本地附件控制器及原件、发送恢复、真实 HTTP 和通用卡片回归；源绑定到单独保留的优化前快照 |
| 整合矩阵 | 分批优化前 App 98 / UI 136 通过 | 主代理已修正旧跨实例清理夹具并取得真实通过；此后只修改待办分批读取和资源类型索引 |
| 最后受影响回归与平台验证 | 主代理继续收尾 | 分批优化后的 SQLite、媒体修订、镜像/发送回归，以及当前源码 Android 14/15 运行及最终 CI 由主代理统一收集 |
| Android 14/15 平台验证 | 前一源码阶段各 3 项通过 | 本目录 `media-device34-before-p2.txt`、`media-device35-before-p2.txt` 保存真实 MediaStore/Keystore 输出；最终源码仍待重验 |

本子任务的回归覆盖如下，完成执行后以复制到本目录的本轮日志、XML 和源码摘要为准：

- `PersistentHistoryMediaTest`：8 项，覆盖 2×33 MiB 原件、超过旧暂存 TTL 的重启、26 MiB 真流接口、离线复读、跨会话隔离、旧副本成功迁移与写失败重试、摘要拒绝、目录退休和重建空存储。
- `RevisionedMediaRecoveryTest`：8 项，使用生产 SQLite、原件库及真实文件失败，覆盖高修订恢复、旧打开流失效、晚到旧取件任务、游标提交后冷重启补做、快照永久删除、待办写失败回滚、媒体成功但待办确认失败的幂等重放、恢复屏障下正文可读且禁止导出私有字节。
- `AndroidAttachmentDownloadsTest`：16 项，覆盖普通字节、系统同名避让、进程终止恢复、真实协程取消、失败回收、系统条目回收、旧坏日志/来源丢失/回收失败不阻塞新下载且保留提醒、取消回收失败只重试回收、账号清理、复制后发布前换代拒绝，以及旧 verified 日志不能凭同摘要跨代发布。
- `SubmittedOriginalRecoveryTest`：2 项，使用生产分块原件、生产恢复映射和生产上传暂存，验证跨过 24 小时清理后仍能按原 localDraftId 重新暂存，以及持久 origin 的反查和会话隔离；上传端口由夹具替代。
- `HermesHistoryMediaHttpTest`：1 项，使用真实 Python `--history-agent`、固定 `75b083e9399115b12513385252811f09c3503c90` SDK、真实登录和 Ed25519 签名，核对三条 query、原始流响应、生产库补存与重启离线字节摘要。运输观察者全部委托生产 GatewayTransport，不制造返回值；本地文档密钥使用 JVM 注入，真实 Keystore 由平台层验证。
- `EncryptedHistoryMediaCacheTest`：现有有界旧 JSON 回取与账号/Gateway 隔离回归，以及会话间不能误删原件。
- `AttachmentOnlyTimelineTest`：纯 Agent 附件、纯用户历史附件、下载回调与无离线保留确认、原件不可用仍有附件卡片；图片识别与流式正文回归继续执行。
- `WorkbenchLocalMediaRegressionTest`：14 项，新增高修订恢复后重新保存与忽略取消的旧下载晚到不能覆盖新成功提示；既有发送前归档、明确手动重试和关闭等待保护继续运行。

## 兼容与恢复边界

- 缺少单条修订的快照删除和显式手动 `remove` 都是永久删除，更高正文不能撤销。旧 `revoked` 记录缺少删除来源，无法可靠区分手动删除与旧事件删除，因此安全地保留永久语义，不盲目抹掉旧屏障。
- 新下载日志持久保存原件代数。旧日志没有这个字段时，未公开条目明确报告 `DOWNLOAD_RECOVERY_VERSION_UNAVAILABLE`，保留恢复日志并要求重新下载；已有完整性提交的公开文件只退休私有日志，不回收用户文件。
- 合法恢复允许重新取回仍可用的远端原件，不承诺恢复本地和服务器都已经丢失的原始字节。缺失原件时保留真实附件身份及不可用状态。
- 本地分块原件与草稿来源仍按账号、Gateway、资料及安装实例隔离；发布到外部 Downloads 后的文件属于用户，不随消息删除、解除配对或账号清理而删除。

## 复现入口

在 `apps/android` 目录执行定向任务，主代理统一串行安排 Gradle，避免多个代理争用构建锁：

```bash
./gradlew :app:testFullDebugUnitTest --rerun --no-build-cache --offline --no-daemon \
  --tests '*PersistentHistoryMediaTest' --tests '*AndroidAttachmentDownloadsTest' \
  --tests '*EncryptedHistoryMediaCacheTest' --tests '*SubmittedOriginalRecoveryTest' \
  --tests '*RevisionedMediaRecoveryTest'
./gradlew :conversation-ui:testDebugUnitTest --rerun --no-build-cache --offline --no-daemon \
  --tests '*AttachmentOnlyTimelineTest' --tests '*MessageTimelineImagePreviewTest' \
  --tests '*MessageTimelineStreamingTest' --tests '*WorkbenchLocalMediaRegressionTest'
```

真实 HTTP 用例另以 `--tests '*HermesHistoryMediaHttpTest'` 执行，必须提供 HERMES_PLUGIN_ROOT、HERMES_SDK_ROOT、OPEN_ANDROID_GATEWAY_CONTRACT_ROOT；解释器使用 OAI_HERMES_PYTHON 或 PATH 中的 python3。当前 Test inputs 已声明解释器选择、默认查找路径或指定解释器文件，并跟踪外部 Gateway、SDK 与契约源文件。该用例在 `app/build/test-evidence/hermes-history-media-http` 记录 SDK/插件提交、关键源文件摘要、夹具阶段，执行后由主代理收集到本证据目录。

资料核对采用 Android 官方的 [共享媒体存储说明](https://developer.android.com/training/data-storage/shared/media) 与 [DownloadColumns](https://developer.android.com/reference/android/provider/MediaStore.DownloadColumns)。`TITLE` 是只读索引，事务关联使用正式可写的 `DOWNLOAD_URI`；在其中写入非秘密的本地来源事务标识，不暴露服务器凭据或内部文件路径。
