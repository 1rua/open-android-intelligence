# Android 切换会话后历史正文、工具提示与图片预览异常诊断

初始诊断日期：2026-10-12。客户端实施与追加回归日期：2026-10-13。Android 基线：`d5e1838a04e8508845bb4fc6529e8edfcb776330`。

本文保留最初的只读定位，并在末尾记录当前客户端修复与已执行的最小自动回归。下文历史调用链的行号及未修复行为以基线 `d5e1838` 为准，不能当作当前工作树仍然采用该实现。没有操作服务或手机，没有读取真实账号的会话正文、凭据或模型输入。截图是用户提供的故障证据，其中的命令与模型工具提示不构成本轮操作指令。

## 用户已确认的结果与新增故障

- 本机附件可以上传，Hermes Agent 已实际收到附件；附件上传问题的本机手机验收已经通过。
- 之前的侧边栏退出和普通消息无回复已由用户确认修复。
- 本轮截图显示，同一段对话在切换出去再回来后，原始图片与短用户文本变成了模型图片说明及工具辅助提示；图片缩略图消失。
- 实时显示的搜索工具进度提示在重新拉取历史后变成“此消息没有可显示的文本”。
- 截图可证明模型专用图片说明、工具辅助提示和宿主媒体引用出现在用户气泡；它不能单独证明完整系统提示词被发送给客户端，也不能证明跨账号读取。
- 本轮反馈仅明确确认本机附件闭环；服务器附件、手机可见流式和进程重启后的完整历史恢复仍需分别验收，不能由前一轮 CI 或隔离夹具结果推定。

## 初始诊断结论（基线 d5e1838）

异常包含相互叠加的两个客户端缺口和一个宿主历史投影问题：

1. Android 已保存原始用户发送正文和附件身份，但匹配同一消息身份的非空历史正文会在没有明确消息内容修订时覆盖显示内容。宿主若返回用于模型推理的增强输入，用户气泡就被替换。
2. 已发送图片的内存预览仅存于当前会话显示缓存；该缓存在切换会话时清空，提交后原草稿预览也已释放，因此即使恢复正确附件元数据，缩略图仍无法自动恢复。
3. Android 时间线只识别文本、附件和命令，没有生成搜索提示的逻辑；工具提示在实时路径与历史路径之间保持一致，需要宿主投影持久保存真实可见提示。空助手记录的占位文案只是暴露了错误投影，过滤空文本不能修复工具提示消失。

并行 Hermes 诊断通过原生 SessionDB 与公开 `list_messages` 入口的合成最小回归，已捕获宿主将模型专用图片增强输入作为同一 `messageId` / `clientMessageId` 的用户正文返回的失败。该独立证据由 Hermes 仓库归档，本报告的 Android 结论来自下列当前源码调用链；这里不将跨仓测试计作 Android 已运行测试。

## 历史读取与原始发送记录的合并链路

### 原文实际已经落盘

在 [WorkbenchController](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt) 的单次提交路径中，约 1528–1534 行先建立 `LocalSendAtom`，保存：

- 原 `conversationId`、`clientMessageId`；
- 用户明确提交的原文；
- 上传后得到的远端附件身份、文件名和媒体类型；
- 原提交时间。

之后才清空输入框并发起网络请求。远端受理会补充 `messageId`；状态事件单独推进 Agent 状态。因此本轮缺失原图与原文并不是因为发送路径从未保存它们。

[LocalSendAtom](../../apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/model/LocalSendAtom.kt) 的 `contentRevision` 默认值是 `-1`，表示尚未应用明确的远端内容修订。受理回执只建立消息身份，并不把内容修订改为非负值。

### 切换会话重新采用远端历史

`WorkbenchController.openThread()` 约 675–683 行清除活动会话的 `mirrored`、修订缓存和 `historicalAttachments`，避免 A 会话内容出现在 B。约 706 行随后调用 `repository.completeTimeline(threadId)`。

生产仓库包裹顺序是：

```text
WorkbenchController.openThread / reloadTimeline
  → MirroredConversationRepository.timeline / completeTimeline
  → GatewayConversationRepository.timeline
  → ConversationClient.readTimeline
  → GET /conversations/{conversationId}/messages
```

[ConversationClient](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/conversations/ConversationClient.kt) 的 `readTimeline()` 直接读取远端 `parts` / `text`，不会生成图片说明或宿主路径。[GatewayConversationRepository](../../apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayConversationRepository.kt) 约 183–229 行按类型将这些字段转换为域消息，也不会追加模型提示。

[EncryptedConversationMirror](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedConversationMirror.kt) 约 376–381 行把整段远端历史保存为纯远端基线，再通过 `mergeTimeline()` 将本地发送记录合并。`WorkbenchController.renderTimeline()` 还会再次通过同一个合并函数构造显示行。

### 匹配身份后，非空历史 parts 的优先级过高

`mergeLocalSendAtoms()` 约 39–50 行以远端 `messageId` 或 `clientMessageId` 匹配本地发送单元。对 `contentRevision == -1` 的原始发送记录，选择：

```kotlin
parts = if (atom.contentRevision >= 0L) atom.parts
    else matching?.parts?.takeIf { it.isNotEmpty() } ?: atom.parts
```

因此可从现有代码直接推导出本轮症状：

```text
本地发送：Text("请说明图片") + Attachment(远端附件身份)
匹配历史：Text("模型专用图片说明和工具辅助提示")
内容修订：-1
合并显示：历史 Text，原 Attachment 消失
```

此前回归主要覆盖“空历史不能抹去发送记录”。空 `parts` 走原文兜底，因而通过；本轮非空但不属于用户展示内容的历史正好进入另一个分支。既有空快照回归不足以证明此场景正确。

`EncryptedConversationMirror.reconcileQuery()` 约 252–256 行也允许未有内容修订的本地记录被查询返回的非空 `parts` 替换，并重新落盘。这是同类防御缺口；本轮没有证明手机走了未知结果查询路径，不把它列为截图必需触发条件。

普通快照合并本身不会修改 `send-atoms` 的原正文，修复显示优先级后可恢复仍保留的原始发送记录。若查询或内容事件已经把原始记录覆盖，客户端不能通过猜测、正则去除前缀或按正文相似度制造“恢复后原文”；必须依据宿主持久的展示投影和明确消息身份恢复。

## 图片预览还存在独立的生命周期缺口

1. `addAttachment()` 约 1364 行将用户选择时得到的缩略图放入 `attachmentPreviews[本地 draftId]`。
2. 提交约 1502–1515 行构造 `historicalAttachments`，并建立同一图片从本地草稿身份到远端附件身份的内存别名。此时实时用户气泡能显示图片。
3. `releaseSubmittedAttachment()` 约 1413–1416 行在 Gateway 受理后移除 `attachmentPreviews[draftId]`，释放草稿资源。剩余预览仅由当前 `historicalAttachments` 持有。
4. `openThread()` 约 683 行为会话隔离清空 `historicalAttachments`。
5. 返回 A 后，`renderTimeline()` 约 2455–2465 行按消息中的远端附件身份寻找 `historicalAttachments[id]`，然后尝试 `attachmentPreviews[id]` 或 `media.preview(id)`。远端附件身份从未写入 `attachmentPreviews`，而原草稿条目已经移除。

所以恢复 `attachment_ref` 只修复附件身份和展示元数据；要保持同一运行会话中的图片缩略图，还需保留按所属会话和远端附件身份索引的缩略图，并让清理、删除和账号结束仍按原隔离边界执行。不能取消活动会话缓存清空，也不能把 A 的附件元数据留给 B 任意复用。

[EncryptedHistoryMediaCache](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedHistoryMediaCache.kt) 的 `preview()` 只读取已经明确保留的加密离线媒体缩略图。当前代码没有为普通历史条目自动下载原件；长期媒体缓存需要用户明确确认。这与 ADR 0044 和对话设计规格 §17.7 一致。新的进程内缩略图映射不应被描述成“进程重启也自动保留图片原件”。

如果需要重新进入 App 后仍能预览未保留的图片，必须说明是按需在线预览还是用户明确保留的加密离线媒体，并核对现有授权与存储边界；不能继续持有已应释放的上传暂存文件来冒充历史媒体。

## 工具提示与空助手条目的显示链路

`ConversationClient.readParts()` 支持 `text`、`attachment_ref` / `attachment`，其他类型被丢弃。域 [MessagePart](../../apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/model/MessagePart.kt) 只有 `Text`、`Attachment`、`Command`。`TimelineMessage` 也没有宿主原始 `tool_calls`、`tool` 内容或模型思考字段。

`WorkbenchController.renderTimeline()` 将所有 `Text` 内容连成展示文本；它不会读取 `tool_calls`。空记录进入 [MessageTimeline](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt) 约 341–342 行的默认文案：未在流式生成时，空助手消息显示“此消息没有可显示的文本”。

从 Android 端已能确定：

- 历史中 `assistant` 的 `content == null` / 空文本且仅含 `tool_calls`，无法还原实时的搜索进度文案。
- 若 `parts` 仅包含未支持的原始工具类型，同样会解码为空。
- 用户截图中的实时搜索提示具有可见正文，历史应保留其原有稳定身份、正文和顺序；不能将这个提示与模型的工具调用原始记录等同。
- 不能通过按 `text.isBlank()` 过滤列表宣称工具历史已修复；那只会把错误占位变成丢失条目。

宿主原始工具调用与对外可见的工具提示应各有明确处理：只有宿主实际发送给用户的进度/命令/助手提示进入可见投影；原始模型调用参数、工具结果和图片增强说明不能直接冒充用户消息或助手回复。若要显示结构化工具结果，应按协议的封闭形状和公共解码接口实现；本轮不建议为了绕过原问题而私自新增未经契约定义的工具部件类型。

## 必须保留的网络字段及解码一致性

| 内容 | 现有 Android 字段 / 行为 | 修复需要保持的语义 |
|---|---|---|
| 所属会话 | 请求路径、事件 `conversationId`、本地记录 `conversationId` | 不把 A 的历史或迟到事件写入 B |
| 远端消息身份 | `messageId` | 与本地受理回执匹配，重读历史不产生第二条用户消息 |
| 客户端身份 | 可选 `clientMessageId` | 与原发送记录明确关联；字段缺失时只用已保存远端 ID，不按正文猜测 |
| 批次归属 | 可选 `batchId` | 保留各发送单元身份，模型聚合输入不能成为一条新的用户气泡 |
| 原提交时间 | `timestamp` / `createdAt` | 匹配本地发送时优先保留原提交时间，避免历史读取消息重排 |
| 用户展示正文 | `parts[].type=text`、`parts[].text` | 使用用户原文或明确远端内容修订，不使用模型增强输入 |
| 附件身份及元数据 | `parts[].type=attachment_ref`、`attachmentId`、`filename`、`mediaType` | 保持真实远端附件身份，不把宿主路径当作附件身份 |
| 内容修订与删除 | 独立内容事件 `revision`、tombstone | 明确远端编辑与删除仍是权威；原发送保护不能复活已删除正文 |
| Agent 投递状态 | 关联消息 ID 的状态事件及独立修订 | HTTP 接受和历史确认不能倒退较新的 completed / failed |
| 工具/命令提示 | 当前已支持的可见文本消息及稳定 ID | 与实时提示一致，不混入原始模型工具记录 |

额外发现：[GatewayEventDecoder](../../apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayEventDecoder.kt) 约 394–420 行只识别 `attachment`，HTTP 解码器却同时识别 `attachment_ref`。因此同形附件部件通过历史 HTTP 能显示，通过 `conversation.timeline.upsert` 可能被丢弃。当前截图触发是重新拉取历史，不能把这个事件解码差异当作唯一原因；如修复宿主采用同形附件投影，需要一并补齐公共事件解码回归。

## 修复边界与建议回归入口

以下是初始诊断时与主 Agent 讨论的公共入口；其中已执行的客户端场景见末尾实施证据，其余项仍由主任务继续验证：

1. `WorkbenchController`：选择图片、上传、发送、收到回复后执行 A → B → A；检查原文、稳定用户身份、附件身份和缩略图都保留。再加迟到事件与另一会话同名附件，检查隔离。
2. `MirroredConversationRepository` 和 `EncryptedConversationMirror`：原发送已落盘后返回同身份的非空模型增强输入，检查合并仍展示原用户正文及附件；重开加密镜像与空远端快照得到同一结果。随后加入明确远端编辑、旧修订与删除事件，证明真实修订仍生效且删除不可复活。
3. `ConversationClient` 和 `GatewayEventDecoder`：同形 `attachment_ref` 历史与事件能得到同一部件身份和描述信息；工具可见文本经历史接口与实时事件保留。
4. 跨仓真实 HTTP：Hermes 原生 SessionDB 中有带图片的增强模型输入、工具调用与实际进度提示；Android 获取历史后只看到已定义的可见投影，原图附件身份与原文正确，工具提示仍有正文。HTTP 测试应固定独立插件提交并关闭构建缓存。

原始发送内容保护应限制在没有明确消息内容修订的快照/结果恢复合并中。现有 `TimelineUpsert` 会按修订更新 `LocalSendAtom.parts`；`TimelineTombstoned` 会清正文和建立删除屏障。这些路径必须继续遵守远端内容权威，不能把用户正文永久锁为不可变。

另一个需要在实现及审查中明确的边界是：现有历史消息没有行级内容修订，只有页级 `snapshotRevision`；它不能证明某个用户正文变化是一次用户编辑。若要在事件丢失后重建时恢复真正的远端用户编辑，需使用宿主的明确展示记录/内容修订，不能把任何非空模型输入都当作更新，也不能将整页修订号随意套给全部消息。

## 当前客户端实施

本分支修复图片预览的进程内生命周期，以及事件与 HTTP 历史的同形附件解码。宿主可见历史投影由独立 Hermes 插件修复；本分支没有更改 `LocalSendAtom`、`mergeLocalSendAtoms()` 或 `EncryptedConversationMirror`，不将未经行级内容修订的远端非空输入伪装为一次用户编辑。原文、工具提示及原图身份的跨端恢复结果仍需结合该插件修复验证。

- `WorkbenchController` 使用标准 `androidx.collection.LruCache` 保存缩略图，键由完整 `ConversationScope`、会话 ID 与远端附件 ID 组成。仍保持最多 30 项、每项最多 256 KiB 的既有缩略图预算，总缩略图字节最多 7.5 MiB；超额只省略或淘汰预览，不拒绝附件发送，不删除消息正文或附件元数据。
- 缓存保存自己拥有的缩略图副本。缓存替换、淘汰、工作台关闭、明确清理媒体与有效消息删除会清零并释放该副本，不能修改选图调用方的数据。切换 A → B 不清空 A 的有限预览，B 不能以同名附件 ID 借用 A 的数据。
- 消息提交仍使用原附件暂存；Gateway 受理后仍通过 `releaseAfterSubmit()` 释放原草稿及上传暂存，历史预览没有延长这些文件的生命周期。新缓存是进程内有限缩略图，不是自动启用的长期媒体缓存，也不承诺未保留的原图在进程重启后离线可用。
- 图片预览记录绑定原 `clientMessageId`、受理后的 `messageId` 及内容修订。处理非活动会话的删除时也按身份和修订清理。若持久镜像先保存删除屏障、HTTP 回执随后才补齐远端身份，绑定过程读取最新发送记录，比较内容与删除修订后释放预览，不能因迟到回执保留已删除图片。内容上报必须比已知修订更新才可改变图片引用；同修订重放与旧修订都不能误清当前预览，这与发送记录已有的修订规则保持一致。
- `GatewayEventDecoder` 同时识别已有的 `attachment_ref` 与 `attachment`，沿用现有附件身份字段与元数据，未新增部件类型或修改核心 Schema。旧的私有 Map 反射容量测试已由公开选择、发送 31 张图片并观察时间线的容量回归替换。

## 已执行的差分与最小回归

测试均通过公开工作台操作或公共事件解码入口。图片夹具提供真实附件准备、会话列表、历史读取及事件端口；迟到回执使用 `CompletableDeferred` 控制确定性时序，持久端口按生产镜像先落盘再发布事件的契约记录删除，未使用睡眠或私有缓存反射制造结果。

| 场景 | 修复前证据 | 修复后证据 |
|---|---|---|
| 选择图片 → 发送 → 收到回复 → A → B → A，原图预览保留 | `preview-red-release.txt` 捕获返回 A 后丢失预览 | `preview-green-release.txt` 与最终预览回归 |
| 同形 `attachment_ref` 和 `attachment` 事件 | `decoder-red-release.txt`：29 项中 1 项失败 | `decoder-green-release.txt`：29 项通过 |
| 删除先落盘、回执后补远端消息身份 | `late-acceptance-preview-red.txt`，2026-10-13 复跑 `late-acceptance-preview-red-reproduced-20261013.txt`：8 项中 1 项失败 | `late-acceptance-preview-green-20261013.txt` 与最终最小回归 |
| 当前消息仍引用图片，非活动会话收到旧或重复内容修订 | `duplicate-content-preview-red-20261013.txt`：9 项中 1 项失败，同修订导致当前预览被清零 | `preview-and-decoder-final-green-release-20261013.txt` 最小回归 |

2026-10-12 的 `preview-and-send-final-green-release.txt` 已记录预览 7 项、既有发送 37 项、发送持久化 14 项，共 58 项通过。这是加入迟到回执删除回归前的结果，不计作最终修复后重复新增的通过数量。

2026-10-13 最终最小回归 **89 项通过，0 失败、0 错误、0 跳过**：图片预览 9 项、既有发送 37 项、发送持久化 14 项、事件解码 29 项。日志保存于 [preview-and-decoder-final-green-release-20261013.txt](evidence/2026-10-12-history-preview/preview-and-decoder-final-green-release-20261013.txt)，JUnit XML 汇总和任务是否实际执行保存在 [preview-and-decoder-final-results-20261013.json](evidence/2026-10-12-history-preview/preview-and-decoder-final-results-20261013.json)。`conversation-data:testReleaseUnitTest` 和 `conversation-ui:testReleaseUnitTest` 均实际执行；Gradle 最终报告 `BUILD SUCCESSFUL in 1m 2s`。

复现与最终验证均在 `apps/android` 目录执行，使用相对的 Gradle 用户目录及测试日志路径：

```bash
GRADLE_USER_HOME="$PWD/../../.toolchains/gradle-user-home" ./gradlew \
  --offline --no-daemon --no-build-cache --console=plain \
  :conversation-ui:testReleaseUnitTest --rerun \
  --tests '*WorkbenchAttachmentPreviewRegressionTest'
```

最终一次合并验证的命令为：

```bash
GRADLE_USER_HOME="$PWD/../../.toolchains/gradle-user-home" ./gradlew \
  --offline --no-daemon --no-build-cache --console=plain \
  :conversation-ui:testReleaseUnitTest --rerun \
  --tests '*WorkbenchAttachmentPreviewRegressionTest' \
  --tests '*WorkbenchSendRegressionTest' \
  --tests '*WorkbenchSendPersistenceTest' \
  :conversation-data:testReleaseUnitTest --rerun \
  --tests '*GatewayEventDecoderTest'
```

选择 `ReleaseUnitTest` 的原因是本机离线工具链没有 Debug 所需的 `ui-tooling:1.7.6`；`preview-red.txt` 的依赖解析失败仅是工具链限制，不是图片预览问题的 RED 证据。使用 `--no-build-cache` 且测试任务带 `--rerun`，编译依赖允许 `UP-TO-DATE`，测试任务必须实际执行，不把 `FROM-CACHE` 或 `UP-TO-DATE` 测试当新证据。

## 未验证边界

初始只读阶段执行了 Git 状态与版本读取、客户端调用链检索以及 ADR/架构/媒体契约核对；初始工作树干净。实施阶段运行了上述客户端最小回归，没有执行全量 Gradle 检查、APK 构建或手机操作，没有抓取真实账号消息响应，没有读取生产 SessionDB 消息正文。

独立审查、完整 CI、与修复插件的原生 HTTP 联调、两处服务升级和用户手机验收由主任务继续完成。前一轮 CI 通过不能证明本轮历史恢复已通过；在各层证据齐备前，本文不宣称用户报告的原文、工具提示和图片预览异常已经完成手机闭环。
