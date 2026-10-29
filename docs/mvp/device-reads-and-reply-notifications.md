# 设备读取、实时通知与原生会话气泡

短信、通知和通话记录读取通过已安装的受保护设备插件执行。**2026-10-28 授权规则**：Android 客户端是设备能力授权的唯一权威；已安装且启用插件的普通前台能力默认开启，不需要 Gateway 账号开关或重复配对 grant。实际执行仍受有效连接认证和设备绑定、Android 系统权限、本地显式开关及通知采集策略约束；后台同步另需本机明确许可，系统权限本身不代表已允许所有数据外发。规则已在 `feature/android-device-authority-20261028` 实施，待 PR 合入 `dev`；测试与真机验收边界见 [ADR-0059](../adr/0059-android-is-the-sole-device-capability-authority.md) 和[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。

## 配置设备读取

1. 使用允许设备插件的 Full 构建，登录支持设备请求的 Gateway。
2. 在「设置 → 插件管理」的「内置插件」分组，分别为通知读取、短信读取与通话记录读取点击安装入口，安装 Full APK 随附的三个已签名 `.alp`（notifications / sms / call-log），再逐个显式启用。安装本身不会启用插件；启用后能力默认开启，但不自动授予系统权限或扩大本地读取范围。已有同 ID 插件不会被这些入口覆盖；旧版或自行构建的 APK 若未携带某个内置包，可继续通过文件或固定摘要的远程来源导入。
3. 在 Android 插件管理中启用插件，并按需关闭本机能力开关；短信读取或通话记录仍须单独授予系统权限，不再要求额外点选「配对授权」或去服务端开能力。更换账号仍须验证连接身份与对应设备配对，本地明确关闭状态不得被切换账号或重启覆盖。后台任务（如插件支持）另有本机许可，不由普通查询默认开启。
4. 对通知插件，打开「通知采集与访问范围」：开启「允许通知采集」，点击「选择应用」，搜索并勾选本机已安装应用后保存；需要标题、正文时开启内容访问。同时在系统设置中授予本 App「通知使用权」。

应用选择页显示名称、图标与包名，支持多选；关闭或返回会放弃未保存的修改。白名单为空时不采集任何通知。原有白名单中已经卸载或当前不可见的应用仍会保留，直到用户明确移除。完整 APK 可查询当前 Android 用户的已安装应用（包括没有桌面入口的应用）；Play 发行版只列出系统允许查询的应用，并在选择页说明这一范围。
5. Agent 通过 Gateway 的设备能力发现与设备请求接口调用已授权的插件。可用参数见 [短信 Schema](../../plugins/schemas/sms.json) 和 [通知 Schema](../../plugins/schemas/notifications.json)。

短信读取仅查询收件箱，筛选发件人、正文和时间后再应用条数限制。通知读取查询系统当前仍存在且被本机策略允许的通知，连接或重新授权后会刷新当前通知；系统已经移除的历史通知不可补取。撤销系统权限、本机策略或关闭本地插件能力后，读取必须被拒绝。

「短信读取」设置应指向真实的本地插件能力与系统权限设置，不能用一个显示开关声称已完成全部执行条件。

## 配置实时回复通知与气泡

「系统通知推送」控制本 App 的 Agent 会话通知，与读取其他应用通知的「通知采集设置」分开。Android 13（API 33）及以上首次连接 Gateway 后，App 会请求通知运行时权限；Android 12/12L（API 31/32）不显示该权限请求，按系统通知开关和通知渠道状态判断。Android 13 及以上拒绝后，可通过该开关进入系统设置重新授权。

会话排队或运行时，App 持续更新同一条 Android 原生会话通知，展示最新的中间回复和最终回复；流式更新会合并刷新，不逐字响铃。收到助手回复及该轮任务成功终态后转为完成提醒；失败、取消和连接中断显示对应状态，工具输出不会被当作助手回复。事件重放不会重复触发完成提醒。

在「设置 → 原生会话气泡」进入系统设置，允许本应用或指定会话显示气泡。气泡使用 Android 原生 Bubble API；系统没有允许气泡时，普通通知仍可使用。

会话快捷方式同时包含本地用户与 Agent，保留 Agent 的机器人标记。Android 14–16 会排除参与者全部为机器人的会话；仅声明气泡元数据或开启系统开关不能满足这项系统要求。

Android 16 及以上另提供原生 Live Updates 运行状态卡片。在「设置 → Android 16 实时活动」允许系统突出显示通知后，兼容的设备可在状态栏、锁屏或厂商实时活动区域（如 Samsung Now Bar）显示任务状态和最新公开回复。App 使用不确定进度，不生成虚假的百分比或预计完成时间。是否提升、展示位置及布局由系统与厂商决定；较早 Android 版本继续使用原生会话通知和气泡。

Live Updates 与支持气泡的会话通知使用独立卡片：Android 16 的 Live Updates 不支持 `MessagingStyle`，不能通过替换会话通知样式同时满足两种资格。任务完成、失败、取消或连接中断时移除 Live Updates 运行卡片，最终回复仍由会话通知提醒，避免重复完成提醒。关闭通知、撤销允许或移除账号会清理对应运行卡片。

点击气泡后，会在展开窗口中显示当前账号正在运行的会话；选择会话即可查看历史和实时回复、输入并发送消息，也可停止正在生成的回复。整个过程留在气泡内，不会跳转到主界面；主界面的当前会话和草稿保持独立。通知对应的会话结束后仍可从该气泡查看最终回复。普通通知的点击入口继续打开对应账号的主界面会话。

旧 Gateway 若未携带 `generationId`，App 需要先收到该轮用户消息的 `queued` 或 `delivered` 状态，才能把随后助手回复与成功终态安全关联；只恢复到旧轮次中途、缺少开始状态时不会猜测归属并提醒。拒绝权限或关闭开关期间完成的任务不会在重新开启后补发旧提醒。

提醒依赖 App 已连接的 Gateway 事件流。有已提交或运行中的任务且通知已开启时，App 会在可见期间启动独立的 `dataSync` 前台服务，维持进入后台后的回复接收；任务结束、退出账号或关闭通知后自动停止。前台服务通知不充当会话气泡，避免被系统排除气泡资格。返回前台或离开前台时会重新核对权限并刷新当前运行通知。

发送操作会同步登记任务；即使 Gateway 尚未确认接收，切换主界面会话或折叠气泡也不会撤销正在提交的任务。任务按用户消息与生成身份跟踪，收到匹配的成功、失败或取消终态后释放；中间助手回复不会结束后台接收。

前台服务仍受系统后台策略和 Android 15+ `dataSync` 时限限制；达到时限时会及时停止，不在后台自动重启。用户强制停止 App 或系统结束进程后，没有独立远程唤醒服务；重新打开 App 会恢复连接并同步任务状态。

## 回归验证

准备 JDK 21、Android SDK 36、Gradle 对应工具链与真实 WASM 产物后运行：

```sh
cargo build --locked --target wasm32-unknown-unknown --release --manifest-path plugins/Cargo.toml
cd apps/android
./gradlew --no-daemon --console=plain \
  :device-primitives:testDebugUnitTest \
  :sms-collector:testDebugUnitTest \
  :notification-collector:testDebugUnitTest \
  :notification-host:testDebugUnitTest \
  :notification-control:testDebugUnitTest \
  :core-model:testDebugUnitTest \
  :policy-engine:testDebugUnitTest
./gradlew --no-daemon --console=plain :conversation-ui:testDebugUnitTest \
  --tests '*WorkbenchActiveWork*Test' \
  --tests '*WorkbenchSendRegressionTest' \
  --tests '*WorkbenchSendPersistenceTest' \
  --tests '*WorkbenchGenerationLifecycleTest' \
  :conversation-data:testDebugUnitTest --tests '*GatewayGenerationReceiptTest'
./gradlew --no-daemon --console=plain :app:testFullDebugUnitTest \
  --tests '*ReplyNotificationsTest' \
  --tests '*NativeConversationNotificationsTest' \
  --tests '*LiveUpdateNotificationsTest' \
  --tests '*AgentSessionKeepAliveTest' \
  --tests '*AgentSessionForegroundServiceTest' \
  --tests '*BubbleConversationTest' \
  --tests '*SettingsViewModelTest' \
  --tests '*SettingsHonestyCopyTest' \
  --tests '*SettingsCapabilityUiTest' \
  --tests '*ArchitectureBoundaryTest' \
  --tests '*PluginManagementScreenTest' \
  --tests '*GatewayWorkbenchLogoutTest'
./gradlew --no-daemon --console=plain :app:assembleFullDebug :app:lintFullDebug
```

真机复测应包括：查询较早的匹配短信、已有通知在首次连接后可读、撤销权限后拒绝读取；中间回复与最终回复更新同一通知；允许气泡后在展开窗口切换运行中会话并发送消息；主界面草稿不被气泡修改；失败或取消停止运行状态；拒绝通知或气泡权限后对话继续正常完成。

通知、附件、后台会话与插件生命周期的兼容性矩阵为 API 31、32、33、34、35、36。API 31/32 验证无需通知运行时权限；API 33 验证允许与拒绝权限；API 34 验证 MediaProjection 前台服务要求；API 35 验证 `dataSync` 超时回收；API 36 保留 Live Updates 验收。系统气泡与通知抽屉行为仍需在模拟器或真机检查：发送任务后按 Home，气泡显示且下一条回复继续更新，展开目标是会话气泡页面；系统返回的通知带 `FLAG_BUBBLE`，不能只看 App 构建对象是否有 metadata。Android 16 设备还应检查系统设置允许提升后，Live Updates 出现在设备支持的实时活动区域；撤销提升后普通会话通知继续工作，任务结束后后台服务和运行卡片都停止。Robolectric 不执行完整 SystemUI 资格审核，不能替代 Samsung 真机对 Now Bar 的展示验证。

## 本地授权与旧版本诊断

目标中，Hermes、DSH 及其他 Agent Gateway 不提供额外设备能力授权设置，Core 不维护账号级设备能力许可。Agent 通过统一的 `oai capabilities` / MCP `oai_capabilities` 获取实际 ID、版本、Schema、Android 状态和提供者，再用 Core 设备请求接口调用。模型不能绕过插件直接调用 `kernel.notifications.read`，宿主终端或 ADB 也不能替代该请求链。

旧 `dev@e5c7f42a` 基线可能在目录中返回 `GATEWAY_DISABLED`：它表示历史账号策略中能力被显式关闭，**不是 Android 系统权限不足，也不是统一的 HTTP 错误码**。该判断已在 `feature/android-device-authority-20261028` 分支修复，当前实现与验证见[授权实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。不应再推荐旧 Hermes setup 或另设账号许可；本分支仍可能因 Android 本地许可、系统权限、提供者或版本状态拒绝能力。旧排查未读取任何用户账号库，不能据此推断某个用户的历史值。

`PLUGIN_NOT_INSTALLED`、`PLUGIN_DISABLED`、`LOCAL_GRANT_REQUIRED`、`ANDROID_PERMISSION_REQUIRED` 是当前分支复用的诊断值，分别涉及未安装、未启用、本地限制和系统权限；`AUTHORIZATION_MODEL_UNSUPPORTED` 是本地协商状态，不新增 Gateway wire enum。`PROVIDER_UNAVAILABLE` 的 `reason=NOTIFICATION_LISTENER_UNAVAILABLE` 表示监听服务尚未连接。各状态与迁移边界见[授权实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。

通知采集、应用白名单、字段范围与设备插件能力默认开关是不同条件。空白名单不采集任何应用，未允许内容时不返回正文；启用插件不能扩大这些本地决定。系统权限、通知监听和本地设置变化后应重新同步能力状态；即使远端仍显示旧 READY，Android 在实际读取和结果外发前仍须复核。

当前 Android 插件管理页面还会显示 `SYNC_FAILED`、`ANDROID_STATE_UNKNOWN` 等同步诊断：旧版仅有空 bindings、包验签或加载失败时不能假定是未安装或已经授权。保留失败原因，修复包或升级后重新同步，不能通过清空 App 数据试错。当前同步器会因部分无法关联的远端 revision 清空本地 grant；目标只使旧请求/镜像失效，不覆盖 Android 用户决定，该路径及旧缓存迁移均列入修复计划。
