# 会话本地恢复、附件持久保存与下载：交付记录

日期：2026-10-15。基线：`d0fbfaeb9c0acda04dbe72fa9306d9109c54bdf1`。

## 交付内容

- 打开已同步会话先展示本地保存的双方消息，再后台刷新远端；远端历史按消息身份与修订合并，迟到旧页或缺行不能覆盖已收到的回复和附件。
- 用户与 Agent 附件默认保存到 App 私有持久原件区；旧镜像、发送记录与已保留媒体事务迁移，失败保留原数据；移除“加密离线媒体”设置、配额与手动保留确认流程。
- 下载统一改为 `MediaStore.Downloads` 直出普通文件到 `Download/OpenAndroidIntelligence`：写入期间 pending，长度与 SHA-256 核验后发布，同名由系统避让，失败/中断可恢复且不阻塞其他下载。
- 历史媒体在既有 metadata、cache-grant、content 路径支持签名 `format=stream-v1` 有界流式读取；未指定格式保留旧有界 JSON/Base64 响应；附件事件补齐正式分派 Schema。
- 核心摘要变更为 `sha256:63223ba149355032800eeae8a9f0827d542bbde7ca9e4eeb19f93df30e7febde`。App 2.1.1、Hermes 插件 2.1.1、OpenClaw 插件 1.0.2 必须配套升级；只升级一端会被 `PROTOCOL_INCOMPATIBLE` 拒绝。

## 源码版本

| 仓库 | 版本 | 提交 |
|---|---|---|
| 应用主仓（本记录所在提交） | App 2.1.1 | 本次提交（`git log -1`） |
| hermes-gateway-plugin | 2.1.1 | `30b2847ba65c95c4e434445a0a0c577b5603db4f` |
| openclaw-gateway-plugin | 1.0.2（tag `v1.0.2`） | `9b81b977dc8190536b1ac2944d97fbb004b450b9` |
| 契约冻结提交 | CORE 如上 | `851abc44e7ebf5a5aa0ea7ffe311d59be1538058` |

## 验证证据（提交前，本机执行）

- Android 受影响矩阵（`--no-build-cache`）：App 98 项、conversation-ui 136 项、conversation-data 54 项（含固定 SDK 真实 Hermes HTTP 互通）、gateway-client 45 项、encrypted-store 14 项，全部通过；日志见 `docs/reviews/evidence/2026-10-14-local-history/android-20261015-final-matrix-green2.txt`。
- 跨宿主一致性：两宿主各 70/70 共享向量通过，等价对照（同向量 ID 的 operation 与 resultHash 全等）通过；见 `cross-host-conformance-final/`、`cross-host-compare-final-python.txt`。
- 契约与插件固定校验：两个插件仓 `contract-pin.json` 均指向 `851abc4`，与人仓 HEAD 契约一致；`openclaw-plugin-pin.json` 指向 `9b81b97` 校验通过。
- 模拟器平台验收（Android 14 与 15 各一轮）：真实 MediaStore 3 项（离线原件、中文同名并发下载、33 MiB 附件）、超过 CursorWindow 的大消息冷重开、真实杀进程后新进程恢复未发布下载，全部通过；证据在 `platform-android14-final/`、`platform-android15-final/`。
- 附件媒体与恢复回归：`PersistentHistoryMediaTest`、`EncryptedHistoryMediaCacheTest`、`AndroidAttachmentDownloadsTest`、`SubmittedOriginalRecoveryTest`、`RevisionedMediaRecoveryTest`、`HermesHistoryMediaHttpTest` 等均包含在上述矩阵内。

## 安装顺序

1. 先在本机确认已取得与本次提交匹配的 APK（CI `android-apk` 产物，`tools/download-latest-apk`）与两个插件版本。
2. 维护窗口内先升级插件（Hermes 2.1.1 / OpenClaw 1.0.2），随后立即安装匹配 APK；升级期间允许短暂 `PROTOCOL_INCOMPATIBLE`，不要只升级一端长期运行。
3. 账号、配对、刷新凭据、宿主会话与既有历史均不重置；旧镜像在 App 首次打开账号时事务迁移。

## 未验证边界

- 真机（用户手机）验证由用户手动执行；仓库内平台证据来自 Android 14/15 模拟器。
- 三个仓库的 CI（主仓 `ci.yml`／`android-apk.yml`、两个插件 `plugin-ci`）在本记录写入时尚未完成，结论以推送后的 CI 运行结果为准。
- 已部署的 Hermes/OpenClaw 宿主尚未升级；端到端登录互通需在插件与 App 同步升级后验证。
