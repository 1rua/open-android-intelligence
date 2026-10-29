# Android 唯一设备能力授权：分支实施记录

开始日期：2026-10-28；最新验证：2026-10-29，基于 `dev@4a5fd397c5612fe8b3024c9b3b2754e217c68ccd`。状态：**已在 `feature/android-device-authority-20261028` 分支实施，PR 准备中；真机验收待完成**。

本记录描述本 feature 分支的源码实现与当前验证，不表示 PR 已合并、正式发布或真实设备验收已通过。历史只读排查仍固定在 `dev@e5c7f42a59262f404888858045aa975830dafb61`，见[只读修复计划](2026-10-28-device-capability-authorization-repair-plan.zh-CN.md)和[Core 基线证据](2026-10-28-device-capability-authorization-core-evidence.zh-CN.md)。二者中的代码事实、当时测试结果和原 32 份 Markdown 清单属于 e5 阶段记录；本报告不回写为 e5 已实现。

架构决定见 [ADR-0059](../adr/0059-android-is-the-sole-device-capability-authority.md)，需求与验收背景见[授权需求修订](../superpowers/specs/2026-10-28-device-capability-authorization.md)。

## 实施概览

Android 是手机设备能力许可的唯一权威。已安装并启用的插件，其普通设备能力默认开启；用户可以在手机本地关闭插件能力。插件生命周期、能力开关、Android 系统权限、通知读取范围、屏幕本地授权和每次敏感操作确认仍是彼此独立的检查。后台任务使用独立设置且默认关闭，不因普通能力默认开启而授予。安装或启用插件、服务器配置、Agent 会话或协议协商都不会自动授予这些独立许可。

Core 继续认证调用者、绑定账号与设备、校验能力声明/Schema/提供者、管理可靠请求状态机，并镜像 Android 发布的可执行状态。Core 不再根据账号级能力策略授予或否决设备能力。Hermes、DSH 和其他 Agent Gateway 仍是可选会话接入层，不增加设备授权页，也不能用会话桥 Token 代替设备调用凭据。

## 协商与版本兼容

| 组合或事件 | 分支行为 |
| --- | --- |
| 新 Android ↔ 支持该模型的 Core | Android 在既有 `/v2/negotiate` 请求中发送可选单例头 `X-Open-Android-Intelligence-Device-Authorization: android-v1`；Core 仅在收到唯一、值为 `android-v1` 的请求头时回送同值。该扩展在 HTTP 头中协商，不改变封闭 JSON 请求 Schema。 |
| Core 收到未知值或重复模型头 | 返回 HTTP 400，拒绝有歧义的协商请求。 |
| 新 Android 未收到精确协商回执 | Android 保持聊天连接，但不启动设备能力发布或执行；状态为服务端不支持该授权模型，提示升级 OAI Core。不会把缺少回执视作成功，也不会进入重试设备授权的循环。 |
| 旧 Android ↔ 新 Core | 旧客户端未发送新模型头时，Core 不回送新模型回执；旧客户端仍可发布它实际持有的旧授权快照。用户升级到新 Android 后才使用本地默认和一次迁移复核。 |
| Agent/会话适配器 | 不参与设备授权模型协商，也不替 Android 确认许可。Core 的会话功能与设备模型握手分离。 |

Core 的配对能力 GET 响应只返回 Android 能力目录及其 Android 状态，不再输出旧 `capabilityPolicy` 或 `gatewayEnabled`。旧 `capabilities.set` 和 `oai policy set` 写入口停止写策略并返回 `DEVICE_AUTHORIZATION_MANAGED_BY_ANDROID`。目录中的 `GATEWAY_DISABLED` 仅用于兼容旧状态诊断；它提示服务端授权模型过时，不是新模型下的许可条件。

协议 JSON Schema、`SchemaContractHash` 与公开 Gateway Protocol v2 版本没有改变。唯一新增协商信息是可选 HTTP 头，不生成 Schema 或哈希变化。

## Core 旧状态迁移和请求栅栏

Core 账号初始化执行一次性迁移，并在单一数据库事务内完成迁移标记、旧状态归档、旧请求栅栏和设备 `grantRevision` 推进：

- 账号级 `device-capability-policy:v1` 原始值归档到 `device-capability-policy:legacy-v1`，只保留审阅/回滚证据，不再参与新能力判定。
- 每个设备的旧 publication `device-capabilities:{deviceId}` 归档到 `device-capabilities:legacy-v1:{deviceId}`，并推进该设备的 `grantRevision`。离线或旧 Android 不能凭迁移前 publication 继续执行。
- 迁移标记写入 `device-authorization-model:v1=android-v1`。遇到未知标记时 Core 拒绝该授权模型，不自动降级。
- 每个旧请求写入永久 fence `device-request-authority-migrated:{requestId}`。pending 请求转为 cancelled；claimed 或 cancel_requested 转为 outcome_unknown；其他既有终态保持其状态。请求参数和结果正文不再通过迁移后的路由恢复；待处理事件同步释放。
- 原有永久撤销标记、幂等信息、账号/设备/配对身份和代次检查继续保留。迁移不会复活或重放旧请求，也不清除旧请求 fence。

旧策略值与旧 publication 归档是可审计记录，不转换成 Android 用户明确关闭或允许。Core 无权从历史旧策略推断用户本地意图。

## Core 迁移快照、写入栅栏与回滚边界

对包含旧设备授权或请求数据的账号，Core 在持有 SQLite `BEGIN IMMEDIATE` 写锁时先生成迁移前快照，再提交模型标记、旧请求终态栅栏、授权版本推进与写入触发器。快照由[device_authority.py](../../oai-core/src/oai_core/gateway/device_authority.py)创建，位置为账号目录 `backups/device-authority-pre-android-v1.sqlite`；目录权限为 `0700`，文件为 `0600`。快照使用独立只读连接和 SQLite backup API，包含当时完整业务数据库逻辑内容，但不含原 master key。`account_metadata['device-authorization-backup:v1']` 只记录相对快照路径、`masterKeyRef` 和需要原密钥的标志，不复制或替换密钥。

每个账号只保留这一份迁移前快照。若迁移重试时快照已存在，Core 会比较完整逻辑数据库导出，包括 schema、表行和 sequence；只有与当前旧数据库完全一致且快照完整时才复用。数据不同、快照不完整或无法验证时，迁移以 `DEVICE_AUTHORIZATION_BACKUP_CONFLICT` 停止，不覆盖、删除或追加第二份快照。迁移代码见[core.py 的 `_migrate_device_authority`](../../oai-core/src/oai_core/gateway/core.py)，安全回归见[设备授权 Core 测试](../../oai-core/tests/test_android_device_authority.py)。

授权模型标记与所有现有业务表的 `INSERT`、`UPDATE`、`DELETE` 前置触发器在同一迁移事务内安装。每个新建的 `AccountStore` 数据库连接都会注册 `oai_device_authorization_writer()`，触发器仅允许持有当前 Android 模型的连接写入。此前已打开的旧连接以及缺少该函数的 e5 旧 binary 在 schema 重编译后都无法继续修改业务表。基础 `SessionStore` 不会因为打开账号存储就自动执行设备授权迁移。

此迁移是单向的业务状态切换。测试验证了迁移前快照可在隔离副本恢复其原有数据，且原 master key 仍可解密原记录；这只证明快照和密钥引用可用，不等于已通过安全业务回滚验证。不得直接换回旧 binary，也不得将迁移前快照原样覆盖迁移后的账号库。若确需回退，必须停机并在副本中处理：旧在途请求要终结，结果未知必须继续保持未知；推进版本栅栏并要求重新认证；逐项协调迁移后新增的业务数据，不能用旧快照覆盖。生产回滚没有执行或验收。

## Android 本地模型、发布与界面

Android 本地授权模型版本为 v2，记录 `explicitDisabled`，并以模型版本识别需复核的旧数据。迁移复核前，已有 v1 明确 grant 可继续按原授权范围使用；缺少 grant 不会自动变成全开。用户须在插件管理中主动打开“复核旧的本机能力设置”并确认，之后新模型才按启用插件的默认规则生效，同时保留用户明确关闭的设置。该操作只完成本机能力模型迁移，不自动授予 Android 系统权限、通知读取范围、后台同步或屏幕会话。

用户关闭插件能力时，Android 使用稳定的 plugin ID 与作者密钥指纹范围保存否决；同一插件身份后续更新声明不能清除此决定。后台任务许可有独立开关，不随普通设备能力默认开启。实现见[PairingGrantState.kt](../../apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PairingGrantState.kt)中的稳定否决逻辑及[PluginKernel.kt](../../apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PluginKernel.kt)中的`identityGrantKey`。

插件管理提供每插件一个本机能力开关，与插件“启用/停用”操作分开显示。没有当前配对状态或一次迁移复核尚未完成时，开关禁用并说明原因。能力诊断仍使用既有 wire 状态；`AUTHORIZATION_MODEL_UNSUPPORTED` 是本地协商状态，显示需要升级 Core 和当前设备能力不可用。用户关闭本地能力时，界面说明服务端状态不能覆盖本机决定。

需要 Android 短信或通话记录权限时，界面仍通过 Android 原生权限请求；通知读取范围、通知使用权、屏幕投屏/无障碍许可和敏感控制确认仍各走原入口。多提供者场景显示“使用此插件提供能力”操作，并由 Android 宿主记录 provider 选择；provider 未选不会被 READY 掩盖。系统权限确认、provider 选择和插件本机能力开关互不代替。

HTTP 设备请求在每次真正发送前重新检查授权，包括收到 401 并刷新登录后的自动重发。恢复中的设备请求日志和 journal 使用可跨进程重启比较的宿主能力快照摘要及持久 `localAuthorizationRevision`；如果插件提供者、身份或本地授权版本已变，旧执行不会因进程内计数器重置而继续。相关实现见[GatewayHttpClient.kt](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt)、[DeviceExecutionDriver.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt)和[HttpDeviceRequestTransport.kt](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/device/HttpDeviceRequestTransport.kt)。

新的能力发布写入独立加密命名空间 `capability-publication:android-v1:{scopeKey}`，不会读取或重放旧 pairing grant publication 的 pending/acknowledged 正向快照。每次发布前后继续检查本机 revision 与授权模型协商状态。

## 主要源码位置

| 层 | 关键位置 |
| --- | --- |
| 协商头与协商结果 | [NegotiationClient.kt](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/negotiation/NegotiationClient.kt)、[server.py](../../oai-core/src/oai_core/server.py) |
| Android 仅在取得回执后启用设备路径 | [GatewayRuntime.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt) |
| Core 单事务迁移、迁移前账号快照和旧 writer 栅栏 | [core.py](../../oai-core/src/oai_core/gateway/core.py)、[device_authority.py](../../oai-core/src/oai_core/gateway/device_authority.py) |
| Core 只读退役旧策略及新目录 | [capability_policy.py](../../oai-core/src/oai_core/gateway/capability_policy.py)、[admin.py](../../oai-core/src/oai_core/gateway/admin.py)、[cli.py](../../oai-core/src/oai_core/cli.py) |
| Android 本地版本、明确关闭与兼容投影 | [PairingGrantState.kt](../../apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PairingGrantState.kt)、[PairingGrantPersistence.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/PairingGrantPersistence.kt) |
| publication journal、稳定授权摘要及逐次 HTTP 发送栅栏 | [CapabilityPublisher.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityPublisher.kt)、[DeviceExecutionDriver.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt)、[GatewayHttpClient.kt](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt) |
| 插件本机开关、独立后台开关、provider 选择与旧数据复核 | [PluginManagementScreen.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt)、[SettingsScreen.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt) |

## 当前验证状态

| 检查 | 结果 | 范围与限制 |
| --- | --- | --- |
| 最新 `dev` 分支 | PR 提交基线为 `4a5fd397c5612fe8b3024c9b3b2754e217c68ccd`；代码提交 `18f6490009d8439e4e20a9f87a7b0bcbce1da9c0` 已重放到该基线 | PR 仍待 Review，没有合并或部署 |
| PR 级门禁 | `npm run test:pr` 返回成功，Fast 和 PR 阶段的锁检查、Core、DSH 与 Android 模块任务全部通过 | 不代表真实设备验收或 GitHub Actions 已完成；Actions 结果不轮询 |
| Core 与迁移 | Core 测试 311 项通过；独立授权/迁移回归 21 项通过 | 覆盖缺失/true/false/损坏旧策略、单事务迁移、快照冲突与失败重试、旧写入者拒写、旧请求 fence 及 Claim/Result/ACK |
| Core 发行包 | `open-android-intelligence-core` 0.2.0 wheel 离开源码 checkout、在无 Hermes 插件/SDK 环境下启动 `oai serve`；错误 Core Schema Hash 被拒绝 | `verify_distribution.py` 输出 `hermes_installed: false, serve: passed`；Core Schema Hash 未改变 |
| Session Adapter 与依赖锁 | 固定 Hermes SDK 下 `test_hermes.py` 18 项通过，1 条现有 aiohttp 警告；MVP dependency lock 8 行通过 | DSH Session Adapter 测试另有 18 项通过。Hermes 只负责会话接入 |
| Android Gateway Client 单测 | 297 项通过 | 覆盖新旧授权模型 HTTP 头解析以及认证续期后每次发送前的授权复核 |
| Android Platform Kernel 单测 | 68 项通过 | 覆盖默认本地决定、作者范围稳定关闭、明确拒绝优先、后台许可独立及原语返回前撤权栅栏 |
| 其他 Android 模块 | `control-ports` 14、`capability-ports` 51、`screen-control` 21、`device-primitives` 22、`conversation-domain` 32、`conversation-ui` 638 项通过 | 按各模块 JUnit 报告计数 |
| Android App 完整单测 | `app:testFullDebugUnitTest` 汇总 1105 项，0 failures/errors/skips；PR 级任务成功 | 覆盖旧记录复核、插件关闭/同作者更新、后台隔离、系统权限拒绝、真实 Core HTTP、丢 Claim/Result 回复及登出/刷新竞态 |
| Fast 首轮报告 | `npm run test:fast` 的 Gradle App 测试阶段曾出现多个 `Could not write XML test results` 报告错误并返回失败；其中对应 App JUnit XML 为 1105 项、0 failures/errors | Fast 结果仍按失败记录，不改报为通过。随后 `npm run test:pr` 对最新基线重新执行完整 Android 任务并成功 |
| 真实手机与混合版本部署 | 尚未验收 | 未在真实设备申请/撤销系统权限、通知范围、屏幕许可，也未做真实 Gateway 升级或生产回滚 |

设备授权的实现与最终本地验证已完成；部署升级、真机验收和 GitHub Actions 后续状态尚未完成。交付仍是未合入 `dev` 的 PR，请 Review 后再安排生产迁移和设备验收。
