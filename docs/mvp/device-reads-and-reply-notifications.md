# 设备读取、实时通知与原生会话气泡

短信和通知读取通过已安装的受保护设备插件执行。Android 系统权限、插件启用、当前 Gateway 配对授权和通知采集策略分别控制不同的访问范围；只授予系统权限不会自动把数据开放给 Agent。

## 配置设备读取

1. 使用允许设备插件的 Full 构建，登录支持设备请求的 Gateway。
2. 在「设置 → 插件管理」安装并启用已签名的短信或通知 `.alp` 插件。
3. 点击插件的「配对授权」，确认列出的能力；短信插件会请求 Android 短信读取权限。更换 Gateway 账号后需要为对应配对授权；可点击「撤销当前配对授权」停止对应访问。
4. 对通知插件，打开「通知采集与访问范围」：开启「允许通知采集」，添加需要读取的应用包名；需要标题、正文时开启内容访问。同时在系统设置中授予本 App「通知使用权」。
5. Agent 通过 Gateway 的设备能力发现与设备请求接口调用已授权的插件。可用参数见 [短信 Schema](../../plugins/schemas/sms.json) 和 [通知 Schema](../../plugins/schemas/notifications.json)。

短信读取仅查询收件箱，筛选发件人、正文和时间后再应用条数限制。通知读取查询系统当前仍存在且被本机策略允许的通知，连接或重新授权后会刷新当前通知；系统已经移除的历史通知不可补取。撤销系统权限、本机策略或插件配对授权后，读取必须被拒绝。

「短信读取」设置入口跳转到真实的插件授权页面，不再通过一个独立开关声称已完成插件授权。

## 配置实时回复通知与气泡

「系统通知推送」控制本 App 的 Agent 会话通知，与读取其他应用通知的「通知采集设置」分开。首次连接 Gateway 后，App 会请求 Android 通知权限；拒绝后可通过该开关进入系统设置重新授权。

会话排队或运行时，App 持续更新同一条 Android 原生会话通知，展示最新的中间回复和最终回复；流式更新会合并刷新，不逐字响铃。收到助手回复及该轮任务成功终态后转为完成提醒；失败、取消和连接中断显示对应状态，工具输出不会被当作助手回复。事件重放不会重复触发完成提醒。

在「设置 → 原生会话气泡」进入系统设置，允许本应用或指定会话显示气泡。气泡使用 Android 原生 Bubble API；系统没有允许气泡时，普通通知仍可使用。

点击气泡后，会在展开窗口中显示当前账号正在运行的会话；选择会话即可查看历史和实时回复、输入并发送消息，也可停止正在生成的回复。整个过程留在气泡内，不会跳转到主界面；主界面的当前会话和草稿保持独立。通知对应的会话结束后仍可从该气泡查看最终回复。普通通知的点击入口继续打开对应账号的主界面会话。

旧 Gateway 若未携带 `generationId`，App 需要先收到该轮用户消息的 `queued` 或 `delivered` 状态，才能把随后助手回复与成功终态安全关联；只恢复到旧轮次中途、缺少开始状态时不会猜测归属并提醒。拒绝权限或关闭开关期间完成的任务不会在重新开启后补发旧提醒。

提醒依赖 App 已连接的 Gateway 事件流。App 进程被系统终止或 Gateway 连接不可用期间没有独立的远程唤醒服务。

## 回归验证

准备 Android SDK 35、Gradle 对应工具链与真实 WASM 产物后运行：

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
./gradlew --no-daemon --console=plain :app:testFullDebugUnitTest \
  --tests '*ReplyNotificationsTest' \
  --tests '*NativeConversationNotificationsTest' \
  --tests '*BubbleConversationTest' \
  --tests '*SettingsViewModelTest' \
  --tests '*SettingsHonestyCopyTest' \
  --tests '*SettingsCapabilityUiTest' \
  --tests '*ArchitectureBoundaryTest'
./gradlew --no-daemon --console=plain :app:assembleFullDebug
```

真机复测应包括：查询较早的匹配短信、已有通知在首次连接后可读、撤销权限后拒绝读取；中间回复与最终回复更新同一通知；允许气泡后在展开窗口切换运行中会话并发送消息；主界面草稿不被气泡修改；失败或取消停止运行状态；拒绝通知或气泡权限后对话继续正常完成。
