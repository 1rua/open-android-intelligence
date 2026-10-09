# App 认证成功后连接失败修复

日期：2026-10-09，时区 Asia/Shanghai。

## 结论与故障阶段

已确认当前手机安装的 App 包含一处本地初始化故障：密码认证与设备注册完成后，工作台第一次同步保存加密镜像，向要求随机化加密的 Android Keystore 密钥传入调用方生成的 GCM IV，系统拒绝初始化。异常被封装为 `ENCRYPTED_BLOB_CORRUPTED`，登录外层将连接转为 `Failed`，旧界面显示通用连接失败。

这一步在 `ConnectionPhase.Connected` 之前，也在首次事件流请求之前。Hermes 平台 ID 不一致是另一个已复现的宿主路由缺陷，不应将它冒充成此次本地加密初始化错误。

| 阶段 | 实际证据与判断 |
|---|---|
| 网络与协商 | 本轮回环地址和 LAN 地址 `/health` 均为 HTTP 200；用户此前已取得协议 2.1 协商成功证据。健康检查不能证明登录后流程成功。 |
| 认证 | 用户已取得 `session.password.created`、活动会话、刷新凭据、设备公钥与 `session.ready` 证据。本轮未重新登录生产账号，也未重建账号。 |
| 本地初始化 | 当前安装 APK 的实际字节码、真实手机密钥库 RED/GREEN、真实 `GatewayRuntime` 调用链 RED/GREEN共同锁定首次工作台持久化失败。 |
| 事件流 | 当前故障可在事件流启动前复现；本轮现场日志没有足够逐请求信息，不能将其判为 SSE 401/403。回归测试独立核验事件接口 401 会进入 `SESSION_REJECTED`。 |

具体调用链为：

```text
GatewayRuntime.establish
  → session.ready
  → WorkbenchController.init
  → refreshThreads
  → update / persistCheckpoint
  → EncryptedConversationMirror.save
  → EncryptedDocuments.write
  → AesGcmEncryptedBlobStore.writePlaintext
  → Android Keystore 拒绝调用方提供的加密 IV
```

没有旧镜像也会执行第一次保存，因此这不是账号不存在、密码错误或必须清缓存才能解决的问题。Android 官方建议让 `Cipher` 生成随机 IV，再保存 `Cipher.getIV()`；参考：[KeyGenParameterSpec.Builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setRandomizedEncryptionRequired(boolean))。

## Android 修改

- `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/AesGcmEncryptedBlobStore.kt`：改为 `Cipher.init(ENCRYPT_MODE, key)`，读取系统生成的 IV。仍使用原 V1 信封、12 字节 IV、128 位认证标签和原解密路径，旧密文不需要删除或迁移，密钥配置不需要放宽。
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt`：覆盖整个认证后装配流程，并记录会话装配、授权状态、设备执行、附件恢复、媒体缓存、镜像恢复、工作台、已连接和事件流阶段。失败只记录阶段、稳定错误码和异常类型，不记录原始异常正文、密码、令牌、响应正文或签名材料。
- `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt`：为 `POST_LOGIN_INITIALIZATION_FAILED:<阶段>`、`SESSION_REJECTED`、`SESSION_REVOKED` 增加明确中文提示。
- 同一 `establish` 路径的会话续期也已修复：成功轮换凭据但本地重建失败时，进入带阶段的失败状态，保留新刷新凭据，不再显示“当前连接仍然有效”。
- 登录、续期、加密格式和错误文案测试覆盖上述行为；`RandomizedEncryptionRequiredProvider.kt` 在 JVM 中约束不可导出的密钥及 IV 来源，实际加解密仍由 JCE 执行。独立真机探针直接运行生产加密类。

## Hermes 平台和旧历史兼容

插件源码仓库初始提交为 `7fc91869e0494316b78ef254e2560ff4556fb658`，现场 Hermes 为 `0.21.3`。原插件注册 `open-android-intelligence-gateway`，适配器却构造 `Platform("open_android")` 并在失败后回退 `LOCAL`。真实宿主验证已复现这个不一致，以及旧来源在 JSON 和 SQLite 路由加载时被跳过。

兼容修复在独立插件仓库维护，不向 Android 主仓复制插件实现：

1. 插件注册与适配器采用同一 canonical 平台 ID，未知平台明确失败，不回退 `LOCAL`。
2. 通过 Hermes 原生平台注册 API 保留 `open_android` 和 `open_android_intelligence` 兼容入口，保留旧 `Platform.value`、路由键和 Agent 会话 ID，不直接改写用户数据库或配置。
3. 三个入口共用一个适配器，以锁和幂等启动/关闭确保只有一个 HTTP 监听器、一个事件投递入口和一个维护任务。
4. 旧配置通过宿主标准配置读取路径选取，仅读取；混合配置采用明确优先级并发出不含秘密配置值的提示。
5. 对已有 Gateway 对话绑定核验账号、对话、宿主会话键及 Agent 会话 ID，复用原来源。模糊来源、未知绑定或错误归入 `LOCAL` 的绑定明确拒绝，不静默创建新记忆或认领其他本地会话；原历史仍保留。

现场日志每次分别跳过 33 条 routing 和 33 条 legacy session 条目，可能是同一组历史的两份副本，不能相加称为 66 条独立会话。旧历史文件仍在。本轮没有对这些生产记录执行迁移或修复写入。真实 Hermes 的兼容验证使用独立临时配置、路由数据库和历史哨兵。

## 已运行的验证

- Android 定向测试：33 项全部通过，0 失败、0 错误、0 跳过。初始登录失败与续期失败分别取得修复前 RED、修复后 GREEN。使用 `--no-build-cache`，不是从旧构建缓存取得互通结论。
- 真实手机密钥库：原生产加密类失败；修复后生产类写入/读取、独立解密、IV 随机性、原 V1 格式兼容及篡改拒绝全部通过。
- 当前已安装 APK：只读提取并核验 ZIP 与 SHA-256，再通过 `apkanalyzer dex code` 确认原加密冲突实际存在于手机上的二进制。
- 插件：普通 Python 回归、真实 Hermes 平台注册/路由/历史连续性、旧配置读取和共享监听器验证，由独立插件仓库保存详细结果。
- `git diff --check` 与真机探针脚本语法检查通过。

本机没有执行全量 Gradle `check` 或完整 APK 组装；按仓库规则由推送后的 CI 和 Android APK 工作流执行。没有把修复 APK 安装到用户手机，也没有更新运行中的插件或重启 Gateway，因此尚未以生产账号完成部署后的登录、历史读取、发送和事件接收闭环。

## 数据保留与最小部署步骤

本轮没有新建生产账号、重置或删除账号数据库、撤销生产访问会话/刷新凭据/设备配对，未清除手机日志。现场账号资料、设备凭据、刷新凭据及旧宿主历史均保留；连接失败的处理仅取消本次本机运行态。

部署只需两项：

1. 将独立插件仓库的修复更新到已有安装目录，保留插件数据与账号目录，然后重启现有 `hermes-gateway.service`。不运行账号创建、密码重置或历史数据库清理命令。
2. 从本次修复提交的 Android APK 工作流取得 APK，使用相同签名覆盖安装，再打开 App，选择现有 `test` 账号。刷新凭据有效时自动恢复；若 Gateway 明确要求重新输入口令，使用原账号重新登录。

本次没有修改 `gateway-contract/schemas/*.schema.json` 或核心 Schema 摘要。不存在由本次修复导致的跨端 Schema 版本错配。

## 证据入口

- [部署只读核查](evidence/2026-10-09-app-connect-fix/live-environment.zh-CN.md)
- [当前已安装 APK 代码核对](evidence/2026-10-09-app-connect-fix/installed-apk-code.zh-CN.md)
- [真实 Android 密钥库 RED/GREEN](evidence/2026-10-09-app-connect-fix/keystore-red-green.zh-CN.md)
- [Android 定向测试摘要](evidence/2026-10-09-app-connect-fix/android-targeted-summary.zh-CN.txt)

本轮开始时已有的插件拆分审查报告与其他证据未被覆盖，也不会混入本次提交。
