# checkpoint 保存恢复修复验证记录

日期：2026-11-12。关联 OAI #165、PR #168。基线 dev：02ad53e09049c2b06c21cacd9d009c2f14e6d5bd；首轮候选：6e9bf91f70bc24c6b0331097c90332d3f2d409b1。

## 已确认根因

- 原 persistCheckpoint 成功分支只清 checkpointFailure，未清旧通知。
- 首轮候选以相同字符串判断来源，能误清事件阶段后来发布的同值错误；真正异源故障现显式移交来源。
- 8529c861 的第二轮独立探针又确认：事件层收到 flushOpenPersistence 原样转抛的 checkpoint 异常时，不应无条件移交来源。现用异常/cause 的对象身份识别同源传播；协程栈恢复的异常复制仍能经共享 cause 识别。不按 message 相等判断。
- writer 已失败时，原 checkpoint == pendingCheckpoint 早退挡住既有排队快照的重试；现只在 writer 活跃时执行该去重。

checkpoint attempt 代表每次完整快照写入，draftVersion 只用作防倒退保护。账号级快照包含所有 composerDrafts，跨会话后成功快照可恢复旧会话草稿；强制比较当前 threadId 会延长已经恢复的旧错误。

NativeConversationMirror.saveCheckpoint 在 IO 调用 EncryptedConversationMirror.saveNativeCheckpoint，后者进入受管 transaction。该保存端口已经承担原子耐久语义，本修复不新增每写必全库回读。

## 红测

| 固定源码 | 用例/结果 | 证据 |
|---|---|---|
| 基线 02ad53e0 | successfulRetryClearsOnlyTheCheckpointFailureAndCanBeLoaded：1 失败；写入恢复可 load，但旧通知未清 | original-notice-red.xml / .txt |
| 首轮候选 6e9bf91f | sameValueEventFailureSurvivesAnUnrelatedCheckpointRecovery：1 失败；未恢复的事件来源错误被后续 checkpoint 成功误清 | same-source-red.xml / .txt |
| 新候选修正 pending 去重前 | 12 用例中 1 失败；跨会话 pending 未被重启，flush 再抛原 IOException | cross-thread-retry-red.xml / .txt |
| 固定候选 8529c861 | 独立公开事件探针 1/1 红；checkpointOrigin=true、tokenPresent=false，恢复后 committed=true、checkpointFailureCleared=true 但 finalNotice 仍失败 | event-propagation-red.xml / .txt |

## 当前验证

上一候选的公开控制器 14/14 结果保留为历史证据。第二轮独立发现修复后，最终聚焦回归为 **16 通过、0 失败、0 跳过**：新通知回归 11、恢复最终清提示断言的既有事件提交 4、独立公开事件探针 1。Gradle 1分15秒，测试体合计约 0.249秒。覆盖 checkpoint 同源经过事件/创建后 flush 传播、真正同值异源 atom 读取错误、单 writer、pending 重试、后续失败、取消与旧版本。测试在合成内存持久化端口上通过 load 回读，不冒称 SQLite。

独立探针修复后输出：checkpointOrigin=true、tokenPresent=true；恢复后 committed=true、checkpointFailureCleared=true、finalNotice=null。独立探针原文、注入 init、红绿 XML/文本均在证据目录；源码回归也恢复到既有 WorkbenchEventCommitRecoveryTest，不能依赖仅外部探针通过。

真实 NativeConversationMirror → EncryptedConversationMirror → SQLite 回归（源码候选 b4f0bc46）：1 通过、0 失败、0 跳过，Gradle 21 秒。第二轮只修复通知错误来源识别，持久化端口与 SQLite 测试不变，因此未重复运行该已绿测试。公开 editDraft 注入 checkpoint 保存端口一次失败，恢复后 load 可读、旧提示清除；controller.closeAndAwait 与 store 关闭后重新创建镜像，恢复草稿和 composerDrafts 均可读。故障注入发生在真正 SQLite 写入调用前，用于验证该调用链的失败恢复，不宣称模拟 SQLite 引擎内部崩溃或满盘。

首次 SQLite 运行在 Robolectric class 初始化阶段因 Maven HTTP 429 失败，未执行产品测试体；保留 sqlite-environment-first.txt/xml。随后复用本机既有相同 android-all jar，以 offline init 执行通过；没有更改产品代码、断言或 SDK 版本来绕过失败。

Fast：退出 0；依赖锁 8 行、硬编码中文新增违规 0、noVpnSurfaceCheck 通过。git diff --check 通过。源码身份见 artifacts/source-digests.json；远端 CI、独立复核与真实模拟器验收由主任务收口，未执行的不标通过。

## 环境与证据

- Linux x86_64；OpenJDK 17.0.19；Gradle 8.12；Android SDK 来自本机已有工具链。
- 源码位于隔离 clone：原仓 .git 只读，无法建立常规 worktree，因此通过远端 clone 固定同一 dev SHA；没有修改主工作树。
- 本轮仅合成账号/会话，未调用真实模型、模拟器或生产会话。
- offline init 只设置 robolectric.offline=true 和已有 robolectric.dependency.dir，不注入生产逻辑。
- 四组红测均真实执行并保留原失败；不把一次编译环境失败当成产品 Bug。

## 可复跑命令

复用已有 SDK、Gradle 分发和缓存，配置 GRADLE_USER_HOME、ANDROID_HOME、ANDROID_SDK_ROOT 后：

    gradle -p apps/android :conversation-ui:testDebugUnitTest \
      --tests 'com.openandroidintelligence.conversation.state.WorkbenchCheckpointNoticeRecoveryTest' \
      --tests 'com.openandroidintelligence.conversation.state.WorkbenchEventCommitRecoveryTest' --console=plain

    gradle -p apps/android :app:testFullDebugUnitTest \
      --tests 'com.openandroidintelligence.mobile.conversations.NativeConversationMirrorTest.checkpointFailureRecoversAndReopensTheRealSqliteDraft' --console=plain

    gradle -p apps/android -I ../../docs/reviews/2026-11-12-checkpoint-notice-artifacts/spec-probe.init.gradle \
      -Doai.specProbeSource=../../docs/reviews/2026-11-12-checkpoint-notice-artifacts \
      :conversation-ui:testDebugUnitTest \
      --tests 'com.openandroidintelligence.conversation.state.WorkbenchEventCommitRecoverySpecProbeTest.persistentStorageFailureStopsWithAVisibleErrorAndReentryCanApplyTheUnmarkedEvent' \
      --tests 'com.openandroidintelligence.conversation.state.WorkbenchCheckpointNoticeRecoveryTest' \
      --tests 'com.openandroidintelligence.conversation.state.WorkbenchEventCommitRecoveryTest' --console=plain

    npm run test:fast
    git diff --check

证据目录：[2026-11-12-checkpoint-notice-artifacts](2026-11-12-checkpoint-notice-artifacts/)。

## 未解决边界

真实模拟器首次短暂 LOCAL_SAVE_FAILED 的异常来源仍未确定。新增诊断只提供后续安全定位所需阶段/类型，不把日志缺失解释为现场没有故障；合成异常、内存回读、Robolectric SQLite、真实模拟器分别报告。
