# Android 冷启动页面与连接提示修复记录

日期：2026-10-31。基线：`e28243b57e6a1a195e618cf243894f8530c33450`。

## 范围与预期

本次仅修改 Android 启动导航、连接状态呈现及相关初始化。冷启动显示新对话空白页，后台连接成功不改变当前页面，不自动创建服务端会话；后台恢复保留当前会话及阅读位置。保留账号、密钥、历史、草稿、离线缓存和现有 Core 协议。

## 源码事实

- `GatewayRuntime.restoreSessionIfAvailable()` 在首次协商之前调用 `showOfflineMirror()`；后者直接发布 `OfflineMirror` 和离线通告，尚未发生网络失败就会显示离线横幅。
- `WorkbenchController` 构造时从 `WorkbenchPersistence.load()` 还原活动会话；`refreshThreads()` 在没有活动会话时又会自动打开列表第一条。禁止其中一个入口仍不能保证冷启动保持空白页。
- `GatewayRuntime.establish()` 先创建在线工作台，再启动原生 Agent 发现；发现 Agent 后又可能重建工作台，重新执行持久化恢复。初次读取可能早于 Agent 描述信息准备完成。
- `CoreAgentConversationRepository.startEventSync()` 对可以重试的读取错误同时发布 `RECONNECTING` 和 `NATIVE_EVENT_SYNC_FAILED`；界面会把后者显示为同步失败提示。
- Activity 的页面由 Application 持有的 Runtime 驱动；`onAppForegrounded()` / `onNetworkAvailable()` 复用现有工作台。`WorkbenchScreen` 的列表滚动状态由 Compose 保存。

## 修改范围

| 文件 | 修改内容 |
| --- | --- |
| [GatewayRuntime.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt) | 首屏恢复、有限宽限、局部工作台交接、owner 检查与重试 |
| [WorkbenchController.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt) | 可选初始选择策略、读取就绪门禁、旧草稿保护与交接操作转发；默认行为不变 |
| [CoreAgentConversationRepository.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt) | 可恢复事件同步不发布致命提示，耗尽/协议失败保留 |
| [MainActivity.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt)、[WorkbenchScreen.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt) | 顶栏初始化/重连呈现、错误可见、初始化不误报审批能力 |
| [GatewayLoginScreen.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayLoginScreen.kt)、[SettingsScreen.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt)、[SettingsCapabilityUi.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt) | 既有界面的状态映射及可选重试按钮 |

没有修改 Core、Gateway 协议、SSE 数据格式、历史存储格式、账号密钥、设备授权或服务端会话创建规则。

## 回归与结果

### 修复前的行为复现

- `ColdStartConnectionTest.coldStartWithBlockedNegotiationDoesNotEnterOfflineMirror`：1 例执行、1 例失败、0 个执行错误，用例 5.661 秒。真实登录保存凭据后创建新 Runtime，确认握手实际到达回环服务器且响应仍被阻塞；旧代码已发布 `OfflineMirror` 和离线通告。
- `WorkbenchInitialNavigationTest` 首批 4 例全部按预期失败，用例总计 0.139 秒：恢复 `conv_b`、无 checkpoint 时自动打开 `conv_a`、旧草稿恢复直接进入旧页面、Agent 发现尚未完成时提前读取两次会话列表。
- `NativeEventOwnerTest.transientReadFailuresKeepHistoryAndCursorWhileReconnectingWithoutFatalFailure`：1 例执行、1 例失败、0 个执行错误，用例 0.113 秒。第一次 `IOException` 即发布 `NATIVE_EVENT_SYNC_FAILED:READ_FAILED:IOException`，尽管读取逻辑仍准备重试。

以上是通过实际业务入口执行的自动化复现；截图中的手机未连接到本测试环境，未把回环测试写成手机实测。

### 实施方式

- 继续由 `GatewayRuntime.phase` 管理初始化和本地恢复阶段，增加 `Initializing`、`Reconnecting`；已认证事件通道仍沿用 `connectionStatus`。顶栏只转换这些现有事实，不保存另一套连接状态。
- 首次恢复可立即读取本地历史列表，但工作台不选择旧会话、不因列表刷新选择第一条。在线与本地控制器交接期间不发布 `controller=null`；Agent 发现完成后才创建其在线工作台。
- 8 秒为首次连接可恢复失败的提示宽限期，并非额外网络超时。没有失败事实的握手等待不会因为时钟到期被判离线；网络请求继续受既有 15 秒连接、30 秒读取超时约束。明确认证拒绝、身份校验或不可恢复错误立即进入相应处理。退避重试在宽限边界重新检查已发生的失败，不增加独立计时状态源。
- 冷启动不恢复导航，但保留旧 checkpoint 的草稿、附件和未确认发送记录；用户手动打开对应历史时恢复。用户在空白页新输入的内容沿用已有保存语义，在线交接不清空它。
- Agent 尚未就绪时不调用依赖其描述信息的列表、目录或历史读取。可恢复的原生事件读取重试仅报告重连；重试耗尽和协议错误仍报告真实失败。
- 前后台切换沿用当前 Runtime 与工作台，通过既有 cursor 补同步缺失事件，不新建服务端会话；既有消息身份、去重、排序与确认协议保持不变。

### 修复后的验证

已通过 `npm run test:fast`：静态依赖/契约门禁、`noVpnSurfaceCheck` 及三个受影响模块全部完成，Gradle 返回成功。

| 模块 / 定向组 | 执行例数 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| `conversation-domain` | 34 | 0 / 0 / 0 |
| `conversation-ui` | 684 | 0 / 0 / 0 |
| `app` Full Debug | 1215 | 0 / 0 / 0 |
| 其中 `ColdStartConnectionTest` | 11 | 0 / 0 / 0 |
| 其中 `WorkbenchInitialNavigationTest` | 9 | 0 / 0 / 0 |
| 其中 `GatewaySessionRefreshTest` | 47 | 0 / 0 / 0 |
| 其中 `NativeEventOwnerTest` / `NativeEventSynchronizerTest` | 7 / 8 | 均为 0 / 0 / 0 |

三个模块合计 **1933 例**；表中定向组已经包含在模块合计中，不重复计数。冷启动组覆盖真实回环握手挂起、快速恢复、宽限内 503 后恢复、宽限后确认离线及重试、406 协议不兼容及显式重试、前后台保留同一工作台、原生会话列表且无新建 POST、Agent 发现挂起/空列表仍可读本地历史、局部读取故障及重试、构造被闩锁阻塞期间的选择/输入交接。Compose 测试直接验证初始化不显示未确认的审批能力警告，以及前台补同步保持阅读位置。

审查还发现并通过新增失败测试修复了两处交接问题：过早关闭本地工作台导致等待 Agent 时历史点击无效；异步工作台读取失败错误使用全局 teardown，造成空工作台和不可见通告。交接仍等待旧写入完成，关闭后的旧页面导航/输入按顺序转给同一账号、同一 Agent 的有效新 owner，跨 Agent 不转发。版本不兼容和局部读取失败均有明确提示与用户重试入口。

### 测试环境与边界

- 首次完整快速自测等待缺失的 Robolectric Android 系统镜像，线程转储确认停在镜像下载器；已停止该次执行并复用主工作区已有缓存。没有把中断记成成功。
- 第二次运行完整执行后暴露旧测试预期和缺失外部夹具：两例仍断言启动即离线，其他失败为未复制 WASM、未指定隔离 Core/Hermes Python 与旧版互通夹具。两例启动预期已更新为初始化不报离线并保留独立本地告警，其余仅补齐未跟踪的环境依赖，不修改业务、协议或测试跳过条件。
- 最终快速自测使用既有 JDK 21、固定 Android SDK/Gradle 缓存、Robolectric API 31–36 镜像、已编译真实插件 WASM、独立历史 Gateway 夹具及固定 Hermes SDK `75b083e9399115b12513385252811f09c3503c90`。这些只是已有回归环境，不修改现网或用户账号。
- 最终审查收紧了同 Agent 交接边界；`npm run test:pr` 在该最终源码上执行并成功完成（Gradle 3 分 6 秒）；同样通过 1933 例，失败、执行错误与跳过均为 0。两个等级均使用智能测试器选出的受影响模块，不宣称执行了发布级 `test:full`。

### 尚需设备验收

自动化已覆盖受控握手延迟、网络失败与恢复、新进程恢复、前后台回切及真实协议桩。Galaxy S25 Ultra 的实际冷启动画面、Wi-Fi/移动网络切换、系统回收进程和阅读位置仍需安装本次 APK 后验收；不会用 JVM 测试代替这些设备结论。
