# Android 本地历史与附件：最终提交前独立静态审查

日期：2026-10-15。基线：`d0fbfaeb9c0acda04dbe72fa9306d9109c54bdf1`；审查时 HEAD 为已单独审查的契约提交 `851abc44e7ebf5a5aa0ea7ffe311d59be1538058`。本报告针对剩余工作区的 Android 生产改动及相关规格，不审查正在重建、尚未冻结的插件代码。

采用项目代码审查 Skill 的规范／规格两轴，依据当前用户提供的新 AGENTS、总架构、CONTEXT、ADR 0053 和本轮实施规格。审查者没有修改生产代码、运行 Gradle、操作设备或提交代码；此处结论是源码调用链和官方平台源码核对，不是最终 APK 的实机通过证明。

## 规范轴

未发现除下述功能问题之外、可单独报告的 P1／P2／P3 规范缺陷。账号范围参与数据库路径、记录 AAD 和媒体范围，公共下载与私有存储保持独立；移除旧媒体设置和新增文档的位置符合本轮边界。没有把未执行的验收列表、测试夹具失败或旧测试 XML 当作生产缺陷。

## 规格轴

### P2：单个合法长消息可写入，但重启时超出系统查询窗口而阻断账号恢复

位置：`SqliteConversationRecords.kt:64–76`、`:139–158`；`GatewayRuntime.kt:768–773`。

`writeRecord` 将单条消息 JSON 再 Base64 包装、GCM 加密后存入单个 BLOB；`refreshRecords` 则直接把 `payload` 放入普通 `rawQuery` 的查询结果并调用 `getBlob`。数据写入没有与该查询窗口对应的上界或分块读取。

Android 14 平台默认查询窗口为 2048 KiB，默认 `CursorWindow` 使用该配置；单行放不进窗口时原生 SQLite 连接抛出 `SQLITE_TOOBIG`。已直接获取并核对 [Android 14 窗口配置源码](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/res/res/values/config.xml#2364)、[CursorWindow 默认构造及配置读取](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/java/android/database/CursorWindow.java#752) 和 [单行过大异常分支](https://android.googlesource.com/platform/frameworks/base/+/android14-release/core/jni/android_database_SQLiteConnection.cpp#846)；其他厂商可调整配置，因此不声称所有设备阈值完全相同。

按当前包装方式计算，1600000 字节 ASCII 正文形成约 1600137 字节消息 JSON，Base64 包装后已为 2133570 字节，尚未加入加密开销就超过 2097152 字节。它低于旧单文档 8 MiB 预算，并没有被消息模型拒绝。冷启动在初始化镜像时扫描全表，故这种记录能够令整个账号停在 `MIRROR_RECOVERY`，不局限于打开该长消息时失败。

修复方向：记录分块，或先取小型行元数据再使用有界 `substr` 分块读取密文；不能通过扩大 CursorWindow 或恢复旧正文配额掩盖问题。需真实 Android 回归：保存大于窗口的单条消息，完全关闭后重开同一范围并恢复双方正文；迁移旧长消息也应覆盖。当前长会话测试只有很多较小消息，不能覆盖这个单行边界。

### P2：登录必须解密并常驻全部本地会话，持续保留历史使时间与内存没有界限

位置：`SqliteConversationRecords.kt:47–80`；`EncryptedConversationMirror.kt:26–28`；`GatewayRuntime.kt:768–773`。

构造存储时同步调用 `refreshRecords`，查询全部未删除记录；每条都完成 GCM 解密、JSON 解析及 Base64 解码，再把正文保留到 `records` 映射。Runtime 在建立工作台前等待构造完成。因此即使本次只打开一个很小会话，账号所有已保存历史、发送记录和检查点都已进入明文内存。

这与 SQLite 按记录读取、持久内容不淘汰的组合不相容：登录耗时和常驻堆随全部历史总量增长，未读的其他本地会话也参与登录前置工作；旧文档实现可以按目标文档读取。源码可以确认增长关系，但本审查没有给出某个手机的具体内存崩溃阈值或启动耗时。

修复方向：仅保留轻量记录索引，按目标记录／会话读取解密，明文缓存使用有界策略；媒体恢复任务通过独立元数据查找，不要把全量正文加载作为索引。缓存淘汰只能释放内存，不能删除持续保留的磁盘历史。补充多个未打开大历史会话的按需读取和内存边界回归。

### P2：普通墓碑被更高修订恢复后，同一附件仍永久无法保存或下载

位置：`WorkbenchController.kt:437–463`、`:2313–2331`、`:2414–2418`；`EncryptedHistoryMediaCache.kt:59–60`、`:126–129`、`:214–223`、`:262–267`。

当前契约明确区分不可撤销的无修订快照删除与可由更高正文修订恢复的普通事件墓碑。正文已有 `higherContentRevisionStillRestoresAnOrdinaryRevisionedTombstone` 回归，但附件没有对应恢复路径。

可达事件序列为：消息 r1 携带附件 a，墓碑 r2 删除消息，然后 r3 以同一附件身份 a 恢复消息。镜像允许 r3 恢复正文；控制器的 `cancelMessageMedia` 却把 a 永久加入 `deletedMedia`，后续自动保存和下载直接跳过。媒体库同时持久写入无版本的 `revoked` 记录，之后 `ensureLocal`／`save` 仍拒绝该身份，重新启动也不能恢复附件。

修复方向：将媒体删除屏障绑定消息身份、修订和任务代数；仅在更高可信正文明确恢复时开启新一代媒体操作，旧任务仍被代数屏障拒绝。无修订快照删除继续永久生效。补充带真实附件的 r1→墓碑 r2→恢复 r3 回归，同时验证旧事件和旧下载完成不能恢复内容。

## 已核对的修复与证据边界

此前“网络提交前未归档”和“坏恢复日志阻断全部新下载”两项 P2 的生产修复已能定位：发送路径先等待发送事实和原件归档，未知发送可从持久原件重建暂存；下载恢复失败被隔离，新下载可以继续。此处只确认代码顺序和控制流，不重复声称其测试已经执行。

本轮未发现新的 P1，未新增 P3。三个 P2 已即时通知主 Agent，均获认可并安排修复；报告记录的是修复前源码，不能在修改后不经复核直接当作最终通过报告。

本轮没有重跑 JVM、Android 仪器化测试、固定插件互通或 CI。下一步应先建立对应失败回归、修复后验证，再在实际推送提交上执行独立审查。此前契约审查的 117 项 Node 结果不覆盖本报告中的 Android 生命周期问题。

审查快照 SHA-256：SQLite 记录存储 `1defbccdb3e4b9a91567aa0e5bf386f6d0d2b371749c7b92b431fbf21cd63d5b`；媒体库 `209779f53cd74be806944a5820131b37b0a4892f2372c4d2cb0b10ff2db46081`；控制器 `eb3edb1cbe9328fdfd91d5bafad9aa3c9ced0cba906d6a1dccaf7f1fb5190d39`；Runtime `ceda73b076be57b331049ce7d81952a3c5cadf4af11a0a96d6b8bfb280b61038`。

规范轴：0 个独立 P1／P2／P3；规格轴：3 个 P2。
