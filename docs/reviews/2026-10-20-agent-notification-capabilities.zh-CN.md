# Agent 通知读取与 Gateway 账号能力配置修复

## 范围与结论

最终 PR 基线：Android `ead16f84bd80394888f4a4ce18d877517b8c3adc`；Hermes 插件
`d69d21ac8f14bfa91beb1341acec1e3a59b7ea95`。已迁移到两个仓库最新 main，保留会话管理更新并补跑集成回归。
本次在两个仓库执行源码修复，未连接用户的生产 Gateway 或手机。

`generation=4, revision=2, bindings=[]` 只能证明当前配对没有可分发的能力。
`device_tools.execute()` 在进入设备请求队列之前便拒绝不存在的 binding，因此设备在线、正常对话与通知可读不能互相推导。
不能仅凭空数组判断通知插件未安装、未启用或未授权，也不能把系统通知使用权当成配对授权。

已修复可从源码和回归测试确认的恢复缺陷，同时增加原先缺失的账号能力配置和完整状态诊断。

## 根因与修复

| 源码问题 | 修复后的行为 |
| --- | --- |
| 重新配对后，旧能力摘要可能继续由 `/pairings/current` 返回，而 bindings 仍属于旧 generation | 摘要必须同时匹配当前 generation 和 grantRevision；相同摘要在新配对代次也必须重新注册，覆盖 `generation=4, revision=2` 的回归场景 |
| Android 冻结的 pending 发布可能在本地授权变化后被重发；清理磁盘 pending 后，原先已读取的局部变量仍可能被使用 | 每次发布从当前已验证插件、本地配对授权和原语可用性重建快照，检查本地修订号；旧快照不能覆盖新授权 |
| POST 已接受但响应丢失时，旧 expectedGrantRevision 的重发依赖幂等缓存，可能持续得到过期修订号错误 | GET 当前摘要已经确认最新快照时直接记录 ACK，包含进程重启后的恢复，不再次 POST 旧修订号 |
| 管理员提升授权修订号后仍保留旧 grantDigest，可能被 Android 误认为是自己的发布 | 外部授权变更删除旧摘要；客户端持久记录已观察到的外部修订号，并清理本地配对授权一次，避免重复清理循环 |
| 原发布内容只有授权后的 bindings，无法说明缺少能力的原因；系统权限和监听连接变化也不触发原有本地流 | 增加签名设备发布的 inventory；插件/配对变化触发同步，另每 5 秒校对系统权限、监听连接和 Gateway 开关，失败每 2 秒重试 |
| 内核 `disable()` 要求 `ENABLED -> INSTALLED_DISABLED`，状态机却缺少该转换，导致插件停用和卸载失败 | 补齐显式停用转换；停用立即禁止执行，重新启用仍经过既有授权检查 |
| Gateway 没有账号能力配置，也没有 Agent 能力发现工具 | 增加默认关闭的账号能力开关、setup 向导、CLI，以及 `open_android_capabilities`；分发仍只能使用设备实际发布的 binding |
| 执行层把所有 Android 拒绝原因折叠成 CAPABILITY_DENIED | 保留协议码并返回受限的机器可读 reason；区分通知使用权、本机策略授权、监听不可用和普通内核拒绝 |
| 已发起工具调用等待期间缺少当前配对授权再次校验 | 返回结果前再次检查配对 generation/revision 与账号开关；授权变化后的旧调用不能继续交付结果 |
| Android 等待本机操作确认期间可能错过 Gateway 取消事件 | 执行内核调用前再次通过已认证请求查询核对 Gateway 授权、请求状态与租期，已关闭能力不进入执行 |

## 模块与安全边界

Android 的 `ProductionPluginHost` 继续负责 `.alp` 验签、安装、启用、作者身份绑定、提供者选择与配对授权。
新增的 `KernelPrimitiveProvider.availability()` 仅提供只读诊断；不会授予任何权限。
通知、短信和通话记录原语通过现有运行期模块注册，App 生产编译面没有增加 collector/host 实现依赖。
端到端测试按 Manifest 类名装配真实运行期模块，通过公开控制端口授权；测试也没有增加 App 对采集器实现的直接编译依赖。

通知读取仍遵循以下真实路径：

1. `NotificationListenerService` 由系统通知使用权控制，Manifest 继续要求 `BIND_NOTIFICATION_LISTENER_SERVICE`。
2. `notification-host` 的 ContentProvider 安装持久化策略权威与监听运行时。
3. 当前账号的设备插件通过内核六项能力交集和作者身份授权检查。
4. 已验证的通知 WASM 调用 `kernel.notifications.read`。
5. 原语检查系统授权，取得已连接监听器的当前通知快照，再经过 `NotificationAgentQueryGateway`。
6. 查询检查本机采集授权、实际 policyRevision、应用范围与字段策略，并在采集后重新检查授权；元数据模式不会返回正文。
7. 结果经签名 claim/result 通路返回原始 Agent 调用；没有用自动通知推送替代按需读取。

Gateway 策略存储于每个账号自身数据库的 `device-capability-policy:v1`，使用独立策略修订号。
升级后未配置的能力也默认关闭，需通过向导或 CLI 显式开启。
开启是必要条件，不能替代手机安装、启用、配对授权、系统权限与采集策略。
关闭会取消该能力的排队请求、发出在途请求取消事件、清理保存的参数/结果并永久封锁旧请求。
再次开启只允许新请求，不能恢复旧调用或读取旧结果。已经开始的设备执行不宣称可以撤销其历史副作用。

## 发现、兼容与诊断

`GET /pairings/current` 通过 `capabilityPublicationVersion=2` 宣告 inventory 扩展，同时返回当前账号策略与设备状态。
新版 Android 对旧 Gateway 保留原发布形状；新版 Gateway 接受旧 Android 的 bindings 发布。
inventory 只用于诊断，READY 项必须与 bindings 一致，不能从 inventory 合成调用权限。
核心协议版本和核心 Schema 摘要没有更改。

setup 初始支持通知、短信、通话记录，还会合并设备声明的任意其他能力和账号已有配置。
相同能力不同提供者的状态按插件区分；实际调用仍按当前已授权提供者绑定分发。

| 状态 | 含义 |
| --- | --- |
| `GATEWAY_DISABLED` | 当前 Gateway 账号未开启该能力 |
| `PLUGIN_NOT_INSTALLED` | 当前完整设备清单中没有该能力插件 |
| `PLUGIN_DISABLED` | 插件已安装但未启用 |
| `LOCAL_GRANT_REQUIRED` | 缺少配对授权或本机采集策略授权；reason 进一步区分 |
| `ANDROID_PERMISSION_REQUIRED` | 缺少系统通知使用权、READ_SMS 或 READ_CALL_LOG |
| `PROVIDER_UNAVAILABLE` | 采集器或通知监听连接未就绪 |
| `PROVIDER_NOT_SELECTED` | 本机尚未选择该能力提供者 |
| `SYNC_REQUIRED` | 没有当前代次/修订号对应的发布记录 |
| `SYNC_FAILED` | Android 查询/发布失败，正在重试；在手机插件页面显示 |
| `ANDROID_STATE_UNKNOWN` | 旧 Android 未发布完整清单；不能断言未安装 |
| `REQUEST_REVOKED` | 该旧请求因 Gateway 关闭能力而被永久封锁 |

CLI 与 Agent 的状态来自最近一次签名设备快照。离线期间不能把该快照解释为实时 Android 授权；实际执行始终再次检查本机权限。

```bash
hermes gateway setup
hermes open-android-intelligence capabilities list phone1
hermes open-android-intelligence capabilities set phone1 org.openandroidintelligence.notifications.query on --confirm-local
hermes open-android-intelligence capabilities set phone1 org.openandroidintelligence.notifications.query off --confirm-local
```

独立部署使用 `python tools/hermes-account.py capabilities ...`，与 Hermes 原生 CLI 和管理服务共用存储与校验。
Agent 先用 `open_android_capabilities` 取得实际 ID、版本、参数 Schema 和诊断，再调用 `open_android_device`。

## 验证记录

测试使用隔离账号、临时 AES-GCM 密钥、真实 Ed25519 请求签名，以及编译后的参考 WASM。
Android 系统权限和通知由 Robolectric 提供受控场景，内容为合成测试数据，不读取用户通知。

验证环境：Python 3.11.17（另用 3.12.14 交叉检查）、JDK 21、Gradle 8.12、Android SDK 35/36、Rust 1.90.0。
原生 Hermes SDK 固定为 `75b083e9399115b12513385252811f09c3503c90`，使用仓库现有 CI 的基础/原生隔离矩阵。

| 检查 | 结果 |
| --- | --- |
| Hermes Python 3.11 基础矩阵 | 428 通过，0 跳过；按最新独立插件 CI 隔离原生宿主用例 |
| Hermes Python 3.11 原生专项矩阵 | 92 通过，0 跳过，包含最新会话管理回归 |
| Hermes 能力/授权/向导专项 | 49 通过，包含在上述基础矩阵中 |
| Android App FullDebug | 18 通过，包含 5 个发布器恢复场景、真实通知 HTTP/WASM 端到端、Gateway 关闭期间的执行拒绝和模块边界检查 |
| Android device-primitives | 8 通过 |
| Android notification-collector | 35 通过 |
| Android notification-host | 14 通过 |
| Android platform-kernel | 57 通过，包含显式停用后拒绝执行的新增回归 |
| Android 合计 | 132 通过，0 失败，0 跳过 |
| SDK-free 通知运行期/能力端口边界 | 17 通过 |
| `noVpnSurfaceCheck`、插件 manifest/入口检查、`git diff --check` | 通过 |
| Hermes 官方插件校验 | 13 项通过，包括实际注册工具与清单一致性 |
| 最新 main 的归档 Gateway 补丁工具及契约 pin 检查 | 16 项通过；配套 Hermes 契约与当前应用一致 |
| Rust 参考 WASM 编译 | `wasm32-unknown-unknown --release` 通过；端到端测试实际执行该产物 |

通知端到端覆盖：完整安装、启用、配对授权、系统使用权、本机策略授权、监听连接的独立门控；
读取已存在通知；应用白名单排除；Gateway 关闭；Android 系统授权在同步前撤销时的实际内核拒绝；
监听断开/重连；发布器重建；插件停用与卸载后的 bindings 清理。
测试没有跳过本机授权或用假通知结果替代内核读取。

另外把旧 Hermes 基线 `8f7de532f096365d319250a728e1119ca88aad4c` 和修复代码分别放入隔离目录运行同一份重配对探针：
两者均设置 `generation=4, revision=2`，当前 bindings 为 0；旧代码仍返回旧代次 grantDigest，
修复代码不再返回该摘要。随后相同能力必须重新发布，新代次的有效 bindings 得以恢复。
该场景同时由 Hermes 能力发布回归测试覆盖。

主要复现命令（先按项目文档配置 Android SDK、JDK 21 和固定 Hermes SDK）：

```bash
cargo +1.90.0 build --target wasm32-unknown-unknown --release --manifest-path plugins/Cargo.toml
cd apps/android
./gradlew --no-daemon --max-workers=2 --console=plain \
  :app:testFullDebugUnitTest \
  --tests '*CapabilityPublisherTest' --tests '*NotificationCapabilityHttpTest' \
  --tests '*ProductionPluginHostTest' --tests '*PluginManagementScreenTest' \
  --tests '*ArchitectureBoundaryTest' \
  :device-primitives:testDebugUnitTest :notification-collector:testDebugUnitTest \
  :notification-host:testDebugUnitTest :platform-kernel:testDebugUnitTest noVpnSurfaceCheck
```

跨仓测试需设置 `HERMES_PLUGIN_ROOT` 指向本次修复后的 Hermes 仓库、
`OPEN_ANDROID_GATEWAY_CONTRACT_ROOT` 指向本仓 `gateway-contract`，
`OAI_HERMES_PYTHON` 指向已安装插件测试依赖的 Python 3.11。

## 交付与验收边界

两个仓库通过关联 PR 交付完整源码修改。Android CI、安装命令和目录条目固定引用配套 Hermes 修复提交
`7d21b862c85987568d8abc8a40ecff2b942a0d43`，避免跨仓回归使用旧插件。
本次未发布插件/APK 或修改生产 Gateway 配置，也没有目标手机的 adb/账号连接，未执行真机验收。
上线需同时更新两端，按账号开启所需能力，并在手机上完成实际安装、启用、配对授权与系统通知使用权。
升级后可先通过 CLI/Agent 发现工具确定剩余门控；无需手工伪造 bindings、降低 generation 或跳过内核检查。
