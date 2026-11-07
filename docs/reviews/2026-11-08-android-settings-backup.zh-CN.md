# Android 设置一键导出与导入：实现与验证记录

日期：2026-11-08。基线：`a688dd63`（`dev`）。分支：`feature/android-settings-backup-20261108`。

架构与格式说明见 [Android 设置备份与恢复](../architecture/2026-11-08-android-settings-backup.zh-CN.md)。
本文只记录「需求 → 代码 → 测试」的对应关系与验证结果。

## 需求映射

| 需求 | 实现位置 | 验证 |
|---|---|---|
| 「高级设置与关于」增加「备份与恢复」入口 | `settings/advanced/AdvancedSettingsScreen.kt`（应用维护分组） | `AdvancedSettingsScreenTest.fourGroupsExposeRealNavigationWithoutStartingUpdateCheck` |
| 导出走系统保存器，默认名 `oai-settings-YYYYMMDD.json` | `SettingsBackupScreen.kt`（`CreateDocument`）+ `SettingsBackupFileNames` | `SettingsBackupCoordinatorTest.exportThenImportRestores…`（文件名正则） |
| 导入走系统选择器 | 同上（`OpenDocument`） | `SettingsBackupScreenTest.bothActionsAreReachable…` |
| 导入前展示摘要与将被覆盖项并确认 | `SettingsBackupContent` + `ImportConfirmationDialog` + `SettingsBackupViewModel.preview` | `SettingsBackupScreenTest`、`SettingsBackupCodecTest` |
| 覆盖外观/通知/会话/OAI Core/Agent/设备能力等 | 十个节（见架构文档表格） | `SettingsBackupCodecTest.roundTrip…`、`SettingsBackupCoordinatorTest` |
| 不含会话历史、附件、媒体；不改服务端 | 模型层无对应字段 | `exportedFileNeverContainsCredentialsOrIdentifiers` |
| 不导出密码/令牌/主密钥/加密密钥 | 同上（无字段即无法写出） | 同上 |
| 不恢复设备能力实际授权状态 | `CapabilityPolicyValue.appliedTo` 沿用本机 `enabled`；`NotificationPolicyUpdate.granted = null` | `capabilityImportKeepsLocalEnabledSwitchAndIgnoresUnknownKeys` |
| 系统级权限需用户重新授予 | 页面「不会导入」说明与确认框文案 | `SettingsBackupScreenTest` |
| 带 `schemaVersion`、App 版本、导出时间 | `SettingsBackupCodec.encode` | `roundTripKeepsEverySupportedSetting` |
| 统一序列化与恢复机制 | 单一 `SettingsBackupCodec` + 单一 `SettingsBackupCoordinator` | —— |
| 旧字段迁移、未知字段忽略并记诊断 | `SettingsBackupSchema.MIGRATIONS`、`ignoredFields` | `unknownSectionsAndFieldsAreReportedNotApplied` |
| 导入前格式/版本/合法性校验 | 外层 `decode` + 各节 `decode`（闭集/范围/地址/唯一性） | `invalidValuesRejectTheWholeFile`、`brokenJsonAndMissingMetadata…` |
| 失败不破坏原配置（快照回滚） | `SettingsBackupCoordinator.Snapshot.restore()` | `corruptFileLeavesEveryStoreUntouched` |
| 重复导入无副作用 | 每步先比现值再写 | `importingTheSameFileTwiceWritesNothingTheSecondTime` |
| 导入后刷新 UI 与运行时 | `refreshThreads()`、`reloadDebounceSettings()`、Compose 状态流 | 代码路径 + 单测 |
| 离线可用 | 只读写本地权威，没有网络调用 | `connectionAddressIsRestorableOfflineAndNeverContainsCredentials` |

## 未覆盖项与理由

- **插件启用状态**（`verified-plugin-enablement-v1`）：键绑定作者公钥指纹，把它纳入等于在导入时
  替用户授予插件执行权，与「导入不绕过客户端能力授权」冲突。
- **开发者信任模式**：原生插件共享宿主进程与 UID，属于安全红线，永不导出。
- **主动推送 endpoint**：由 Core 认证后动态发现，需要登录态，不是本地偏好；只迁移范围与容量。
- **引导完成状态**：新设备首次启动应看到引导，迁移它会跳过部署教程。

## 已知局限

1. 能力策略键包含作者指纹、Schema 摘要与风险等级；插件升级后键会变化，旧键被跳过。
   这是有意的：键代表「同一份已验证声明」，变了就不该沿用。
2. 会话组织的 `profileId` 由「地址 + 用户名」派生；换了 Core 地址等于换了账号档案，
   会话排序不会跟进。
3. 登录页预填地址在显式登录成功后写入；导入的地址同样写入这一处，
   因此「导入后立即显示在登录页」需要重新进入登录界面。

## 验证

```sh
cd apps/android
LC_ALL=C.UTF-8 ./gradlew :app:testFullDebugUnitTest --tests 'com.openandroidintelligence.mobile.settings.backup.*'
LC_ALL=C.UTF-8 ./gradlew :app:testFullDebugUnitTest --tests 'com.openandroidintelligence.mobile.settings.*'
LC_ALL=C.UTF-8 ./gradlew :platform-kernel:testDebugUnitTest :encrypted-store:testDebugUnitTest
```

新增测试共 3 个类、22 个用例：`SettingsBackupCodecTest`（编解码与拒绝规则）、
`SettingsBackupCoordinatorTest`（真实偏好与加密存储上的导出/导入/回滚/幂等）、
`SettingsBackupScreenTest`（页面、禁用态、错误提示、环境缺失）。

完整 `testFullDebugUnitTest` 在本机受环境限制，与本次改动无关：
`AdditionalDeviceConnectionsTest` 等用例需要一个仓库外部的 `.hermes-gateway-plugin` 夹具
（CI 里由 `Build signed plugin packages` 步骤生成），另有部分用例在整包并发执行时因类加载
失败告终、单独执行对应类全部通过。

CI（PR #96，`dev` 基线）全绿：`android`、`apk`、`core (3.11)`、`core (3.12)`、
`dsh`、`hermes-native` 与 `sign-plugin-packages` 全部通过；三项备份测试已加入
`.github/workflows/oai-core.yml` 的显式 `--tests` 过滤，随 Android 任务一起运行。
