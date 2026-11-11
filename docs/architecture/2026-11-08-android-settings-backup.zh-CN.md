# Android 设置备份与恢复

读者：维护设置页面、新增可迁移设置的开发者。本文说明现行实现与边界；验收证据见[审查记录](../reviews/2026-11-08-android-settings-backup.zh-CN.md)。

## 入口与交互

「设置 → 高级设置与关于 → 应用维护 → 备份与恢复」。页面只有两个动作：

1. **导出设置**：走 Android 系统文件保存器（`ActivityResultContracts.CreateDocument`），
   默认文件名 `oai-settings-YYYYMMDD.json`。
2. **导入设置**：走系统文件选择器（`OpenDocument`），选中文件后先解析校验，
   再弹出确认框列出「将被覆盖的项目」、文件来源版本与导出时间、被忽略的未知字段数；
   用户确认后才写入。确认框正文可滚动——十类摘要加安全说明在短视口或大字号下会超出
   对话框高度，不可滚动就等于让用户在没看全的情况下点确认。

导入结果留在本页结果卡里，逐行列出成功项、跳过项与「需重新连接/重新进入才生效」的提示——
这些信息用户需要时间读完并照着做，一闪而过的 Snackbar 承载不了。导出成功/失败与文件读取
失败同样就地显示。

不新增中转页：路由 `settings/backup` 归属「高级设置与关于」，同级快捷跳转与返回沿用既有约束。

## 文件格式

顶层结构（RFC 8785 规范化输出，同一份配置每次导出字节序稳定）：

```json
{
  "schemaVersion": 1,
  "exportedAt": "2026-11-08T10:00:00Z",
  "appVersionName": "0.1.0-dev.1234",
  "appVersionCode": 12340,
  "settings": {
    "appearance": { "theme": "DARK", "dynamicColor": false, "dynamicIconColor": true,
                    "palette": "EMBER", "reduceMotion": false },
    "notifications": { "replyAlertsEnabled": true },
    "messageProcessing": { "delayMillis": 0 },
    "conversation": { "organizations": [ { "profileId": "<sha256>", "agentId": "hermes",
                      "sortMode": "custom", "customOrder": [], "folders": [], "assignments": {} } ] },
    "connection": { "gatewayUrl": "https://core.example" },
    "agents": { "selectedByProfile": { "<profile-sha256>": "hermes" } },
    "capabilities": { "policies": [ { "key": "plugin:…", "policy": {
                      "mode": "READ_ONLY", "allowedSources": [], "includeContent": true,
                      "smsScopeMode": "ALL", "smsKeywords": [] } } ] },
    "updates": { "channel": "PREVIEW" },
    "notificationCollection": { "packageIds": [], "fieldAccess": "METADATA", "mode": "ON_DEMAND" },
    "devicePush": { "scope": { "packageIds": [], "senderAddresses": [], "smsScopeMode": "ALL",
                    "smsKeywords": [], "notificationContent": true, "smsContent": true },
                    "retentionHours": 72, "maxEvents": 1000, "maxQueueBytes": 4194304 }
  }
}
```

每个节都可缺失：缺失表示「这份文件不涉及该项」，导入时保持本机现值。旧版本导出的文件
因此不会把新版本设置清空。

### 为什么用摘要键

- 会话组织与 Agent 选择按 **账号本地档案 ID 的 SHA-256** 记录。档案 ID 本身是
  「地址 + 用户名」的 Base64，摘要化后文件无法反推出账号名。
- 导入只写回本机**已知**的地址与档案：文件里多出来的键跳过并在结果里如实说明，
  绝不凭一个摘要凭空造出存储条目。

## 覆盖范围

| 节 | 权威 | 说明 |
|---|---|---|
| appearance | `AppearancePreferences` | 主题、动态取色、图标动态取色、预设色板、减少动画 |
| notifications | `ReplyNotificationSettings` | Agent 任务通知开关（系统通知权限不在其中） |
| messageProcessing | `DebouncePreferences` | 客户端合并时长（0～30000 毫秒，字段 `delayMillis`）；旧文件的 `global`/`cores` 无法区分是否用户主动设置，整节跳过并在结果里说明 |
| conversation | `EncryptedNativeConversationOrganizationStore` | 每个账号/Agent 的排序、文件夹、归类 |
| connection | `LoginGatewayPrefill` | 仅 Core 地址，用于登录页预填；缺失时回落到运行时最近地址与已保存账号的地址（老版本升级用户）。导出前会去掉 URL 里的 user-info（`https://user:pass@host`），去不干净就整节不写 |
| agents | `AndroidAccountProfileStore` | 每个账号上次选择的原生 Agent |
| capabilities | `PairingGrantStateHolder` | 每项能力的授权模式与读取范围（不含总开关） |
| updates | `AppUpdatePreferences` | 正式版/预览版渠道 |
| notificationCollection | `NotificationPolicyPort` | 包名白名单、字段访问、投递模式（不含读取授权） |
| devicePush | `DevicePushRuntime` | 范围、内容开关、保留与容量（不含推送总开关与登录态） |

不在范围内：会话历史、消息、附件与媒体文件、Core 服务端配置、插件安装包。

## 安全边界

- **文件里没有敏感凭据字段**：密码、访问令牌、刷新凭据、主密钥、Keystore 别名、
  安装标识、TLS 证书固定值、配对原语授权、临时批准，在数据模型层就没有对应属性。
  `SettingsBackupCodecTest.exportedFileNeverContainsCredentialsOrIdentifiers` 逐词断言。
- **不恢复授权**：能力总开关（`CapabilityPolicy.enabled`，本机基线一律取自
  `PairingGrantStateHolder.capabilityPolicy()`，对没有显式条目的能力即 `enabled = true`，
  能否执行仍由既有原语授权检查决定）、通知读取授权
  （`NotificationPolicyUpdate.granted = null`）、主动推送开关与登录态、开发者信任模式
  一律沿用本机现值。导入看到的是一份「偏好」，不是一张通行证。
- **不声明系统权限**：Android 运行时权限与「通知使用权」只能由用户重新授予；
  页面文案直接说明这一点。

## 导入的原子性

1. `SettingsBackupCodec.decode` 校验外层结构、版本与字段类型；
   `SettingsBackupSections.decode` 校验每个节的取值（枚举闭集、毫秒范围、地址合法性、
   ID 唯一性）。任何一处失败都整份拒绝，不做部分导入。
   **不设条数上限**：导入侧的上限必须与导出侧一致，否则用户导出的文件会读不回来；
   导出本身没有任何条数限制，因此导入也不限制。只校验每条自身的合法性。
2. 写入前对每类权威取快照（外观、通知开关、合并偏好全部键值、预填地址、Agent 选择、
   更新渠道、通知策略快照、推送配置、能力策略、会话组织）。
3. 按固定顺序写入；每一步都先比较现值，相同即跳过，因此重复导入同一份文件是幂等的。
4. 任一步抛错 → 用快照恢复已写入的项目，并把失败原因返回给界面。
   恢复不是「调用即成功」：`Snapshot.restore()` 返回的是**确认未恢复**的项目清单，
   清单非空时界面会列出具体项，而不是笼统地说「已恢复原状」。
   能力策略若在审计阶段失败，内核会留下 `LOCAL_AUTHORIZATION_UPDATE_INCOMPLETE`
   拒绝屏障，此时回滚也会被拒——这种情况如实报告为「部分未恢复」，且**不绕过**该屏障
   （屏障本身就是防止权限被悄悄放开的保险）。
5. **有三节不可撤销，它们的顺序是设计的一部分**：
   - `DevicePushRuntime.configure()` 一旦提交就会删掉不再需要的排队事件、缩短保留期
     并排定远端撤销；
   - 通知采集范围一旦换 `policyRevision`，已排队记录的 `captureRevision` 就不再匹配，
     `allows()` 会拒绝它们，恢复旧策略也换不回投递资格；
   - 能力策略的审计失败会留下 `LOCAL_AUTHORIZATION_UPDATE_INCOMPLETE` 拒绝屏障。

   三者按「最难恢复的排最后」排列（能力 → 推送 → 通知采集），任何失败都发生在后面的
   节之前；其中推送语义是「提交或原样不动」——只要走到回滚，它就没有执行过。
   通知采集与推送的成功结果会在结果里明确写出「已排队记录不会补发 / 已移除且不可撤销」，
   而不是让用户以为一次导入是完全无痕的。
6. **全部节写成功后**才刷新运行时持有的合并策略与会话列表；刷新动作先登记、成功后统一执行，
   中途失败只做回滚、不触发任何刷新——把合并时长改回 0 会立即提交已收集的消息，这种副作用
   不能在导入可能失败时发生。确实无法热更新的项目（例如已登录账号的 Agent 选择、登录页预填）
   会在结果里明确写成「下次…后生效」。

## 扩展约定

新增一项可迁移设置时：

1. 在 `SettingsBackupModel.kt` 为它建一个 `…Section`，提供 `encode()` 与严格的 `decode()`
   （字段类型不符/取值越界就抛 `SettingsBackupFormatException`）；
2. 把节名登记进 `BackupSection` 与 `BackupFieldSets.SECTIONS`（后者用于诊断未知字段）；
3. 在 `SettingsBackupCoordinator` 里加一对 `current…()` / `apply…()`，使用该项**原有权威**
   读写，不要新建存储；
4. 在 `SettingsBackupSections.summaries()` 里给出面向用户的摘要；
5. 补测试：往返、重复导入幂等、坏数据不写入、敏感字段不出现在文件里。

`SettingsBackupSchema.MIGRATIONS` 用于跨版本字段改名或放宽。目前只有版本 1，表为空；
引入新版本时必须把迁移函数登记在那里，而不是散落在读取处。

诊断粒度：顶层字段与「节内一层字段」的未知项会记入 `ignoredFields` 并在确认框里说明；
更深层对象（例如 `conversation.organizations[]` 的条目字段）的未知项静默忽略，
因为它们的合法集合随节内结构变化，不值得在文件层面逐项列举。
