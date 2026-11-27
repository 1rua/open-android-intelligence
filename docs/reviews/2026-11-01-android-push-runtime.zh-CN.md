# Android 独立主动推送子任务交接

日期：2026-11-01。工作树：`.worktrees/global-device-authorization-20261101`。
本报告只说明本子任务写集合；Application、GatewayRuntime、MainActivity、ProductionPluginHost、配对授权和生产调度由主 Agent 修改。
未执行 Git 提交、推送、设备安装或个人数据采集。

## 已实现接口

所有类型位于 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/push/`。

```kotlin
val runtime = DevicePushRuntime(
    EncryptedPushStateStore.fromContext(context, installationId),
    PushSystemAccess { kind -> /* 主 Agent 注入当前真实系统权限与本机能力启停状态 */ },
)
runtime.onWorkAvailable = { coordinator.kick() }
runtime.onEndpointRevoked = { endpoint -> /* 主 Agent 在 IO scope 立即撤回，离线时保留清理任务 */ }
runtime.configure(configuration)
runtime.onLoginStateChanged(hasLoggedInAccount)
runtime.onNotification(notification)
runtime.onSms(sms)
runtime.smsCursor()
runtime.deliver(authenticatedTransport)
runtime.maintain()
```

这是接线示例，注释位置不是生产默认允许实现。构造必须提供真实 `PushSystemAccess`。

- `PushConfiguration` 按安装实例保存。短信/通知总开关默认关闭，范围和端点为空。
- 两种 `includeContent` 默认 `true`：用户显式启用、限定范围并选择端点后，默认包括可读取正文；可独立收窄为元数据。
- 端点稳定键为 `coreInstanceId/accountScope/endpointId/generation`，其中 `accountScope` 来自认证 Core 响应；名称仅显示。
- 保留期默认 72 小时，允许 1–168 小时；默认 1000 条、4 MiB，上限 4096 条、8 MiB，预留接收端收据空间。
- 队列满拒绝新事件并累计 `capacityRejections`，保留已有未确认事件；拒绝事件不推进短信游标。
- 复用现有 `AesGcmEncryptedBlobStore`、Android Keystore 与 `FileEncryptedOutboxPersistence`。密文、配置、游标、去重信息统一持久化到 no-backup 安装实例文件。
- 已有密文但缺密钥、损坏数据、错误安装实例或并发写入版本冲突均停止出站，不重置队列、不生成替代密钥解读旧数据。
- 每个事件 ID 稳定，每个端点保存独立收据。HTTP `pending` 只表示 Core 已接受；`acknowledged` 且有 ACK 时间才清除该端投递。
- 全部退出取消在途 Job，停止新采集/出站，保留有效队列；恢复后重新核验系统权限、完整端点及当前连接。
- 本地撤销先持久化并清理，再取消在途 Job，锁外回调通知远端撤回；回调失败不回滚本地决定。
- 修改范围逐条保留仍获允许的数据；扩大范围不丢弃已有未确认事件。
- 缩短保留期只缩短本地清理期限，不改写重投事件，避免破坏 Core 幂等摘要。
- `onWorkAvailable` 仅为进程内系统任务唤醒钩子，不持久化、不自建循环轮询。生产 JobScheduler 由主 Agent 持有。

## 真实事件源接线

### 通知

`notification-collector/.../NotificationPushSource.kt` 提供：

- `install(NotificationPushObserver)`：在 Application/生产 composition 中安装，不能依赖默认 deny factory。
- `NotificationPushObserver.accepts(packageName)`：提取内容前检查登录、系统权限、主动推送开关、端点及应用范围。
- `includesContent()`：决定是否读取正文。
- `onPosted(RawNotification)`：进入 `runtime.onNotification()`，runtime 再次检查门槛和范围。
- `captureActive()`：从当前已连接的真实 NotificationListenerService 重新核验活动通知，供登录恢复和设置变化后的系统任务调用。

`OpenAndroidIntelligenceNotificationListenerService.onNotificationPosted()` 已直接调用此入口；不经过原被动读取授权。连接时重建真实活动快照，断开/销毁后不保留旧快照。系统已移除且未入队的历史通知无法从活动列表恢复。

### 短信

`SmsPushSource(contentResolver).capture(request, isCurrent, enqueueDurably)` 复用真实 `AndroidSmsInboxReader`，不经过 `SmsRuntimeFactoryRegistry` 的默认 deny 工厂：

- request 从独立主动推送配置取得发件人、正文开关、时间范围和批量大小。
- `request.after` 从 `runtime.smsCursor()` 转换。
- `isCurrent` 在查询前和每条入队前重新核验登录、系统权限与当前推送授权。
- `enqueueDurably` 调用 `runtime.onSms()`，仅把 `ENQUEUED/DUPLICATE` 视为成功；满队列、撤销和其他拒绝停止推进。
- 查询最早优先，避免积压分页跳过较早数据；元数据模式不查询 `body` 列。
- 原被动查询继续默认最新优先和原投影。

本子任务不注册新的 SMS_RECEIVED 广播；生产 coordinator 使用现有系统数据变更观察和有界任务调度，由主 Agent 接线。

## HTTP 与用户设置

`GatewayPushTransport` 使用实际 Core 的 `/open-android-intelligence/v2/push/endpoints`、`/deliveries`、`/deliveries/{id}`、`/deliveries/revoke`，通过既有签名 `GatewayHttpClient` 发送。

- `discover(http, isCurrent)` 可在未知 Core 实例 ID 时启动发现，从响应 `data.coreInstanceId/accountScope` 建立连接；空端点列表也能启动。
- `PushGatewayConnections.current()` 仅提供既有认证连接，不接受任意 URL 或名称路由。
- `beforeSend` 在每次真实 HTTP 尝试及 401 续期重放前重新核验。真实网络取消随 runtime Job 取消传播；没有空的 transport cancel 方法。
- 格式损坏的发现响应抛出异常以保留待重试内容，不伪装成端点撤销。
- Core 短缓冲过期后保留本地原事件，再以同 ID/内容补发；消费 ACK 与 HTTP 接受明确区分。

`PushSettingsScreen(runtime, endpoints, onRefreshEndpoints, onBack)` 和纯状态 `PushSettingsContent` 已提供：

- 短信/通知独立开关、正文开关、应用包名/发件人范围。
- 完整稳定身份多选端点；同名不合并；不可验证的旧选择可取消。
- 1–168 小时保留期、事件数和容量上限。
- 当前队列、过期、容量拒绝和错误状态。
- 离线可改本地设置；持久化失败不显示为已保存。

## 验证证据

| 验证 | 本子任务实际结果 | 证据 |
|---|---|---|
| App runtime/transport/Compose 定向回归，第一次完成 | 20 passed | `docs/plans/2026-11-01-global-device-authorization/push-app-tests.log` |
| 新增撤回回调和损坏发现防护后 | 22 passed，0 failures/errors/skipped | `docs/plans/2026-11-01-global-device-authorization/push-final-tests.log` |
| 短信采集模块 | 44 passed，0 failures/errors/skipped | `docs/plans/2026-11-01-global-device-authorization/push-source-tests.log`；最终 XML 位于短信模块 build/test-results |
| 通知采集模块，含活动通知恢复测试 | 38 passed，0 failures/errors/skipped | `docs/plans/2026-11-01-global-device-authorization/push-notification-recovery-tests.log` |
| Core 真实签名 HTTP/认证端点/逐端 ACK | 12 passed | `docs/plans/2026-11-01-global-device-authorization/push-core-http-tests.log` |
| 新增扩大范围保留队列、缩短本地期限不改重投内容的两项 App 回归 | 已写入，交主 Agent 统一执行 | `DevicePushRuntimeTest.kt`；最近尝试被其他并行测试 `ClientCapabilityAuthorizationTest.kt` 缺少 `CapabilitySchemaValidator` 导入拦住 |
| 真机系统通知、真实 SMS、后台/息屏/回收、跨 Core 完整安装验收 | 未执行，不能以以上结果替代 | 主 Agent/Terra 验收范围 |

App/源测试执行命令（工作目录 `apps/android`，复用既有 JDK 21、`.toolchains/gradle-user-home`、Android SDK）：

```sh
./gradlew --max-workers=2 :app:testFullDebugUnitTest \
  --tests 'com.openandroidintelligence.mobile.push.*' \
  :sms-collector:testDebugUnitTest :notification-collector:testDebugUnitTest
```

Core 测试命令（项目根目录，使用既有 approval-python 环境）：

```sh
PYTHONPATH=oai-core/src ../../.toolchains/approval-python/bin/python \
  -m pytest -q oai-core/tests/test_push_endpoints.py
```

Core 测试证明实际 Python Core HTTP 路由/签名/ACK；App transport 单测使用测试字节传输，不能合并声称 Android 到 Core 的真实完整链路已通过。没有新增或执行跨进程 Android/Core 集成夹具。

主 Agent 要求停止启动 Gradle 后，本子任务已停止启动 Gradle；npm test:fast/test:pr 和最终 App 套件由主 Agent 统一执行，避免同工作树并发写报告。

早期失败是既有 `.toolchains/gradle-home` 的 fileHashes.bin 损坏；没有清理该缓存，按仓库工具链记录复用 `.toolchains/gradle-user-home` 后完成上述测试。

## 主 Agent 必须保留的整合事项

1. 生产 composition 安装通知独立观察器，并在有界系统任务中调用 `captureActive()`、短信 capture、deliver、maintain。
2. 注入真实系统权限/能力启停/紧急停止门槛，真实凭据退出状态与当前签名连接；被动 MANUAL 不作为主动推送前提。
3. `onWorkAvailable` 接 JobScheduler 的唯一任务；`onEndpointRevoked` 接立即 best-effort 撤回，离线保留撤回工作并在重连先清理。
4. Core 现有 revoke 是整条 endpoint 路由；同端同时接收短信和通知时可能连带撤回另一类有效缓冲。细化到 `eventIds/eventType` 需要 Core 契约与 Android 回调共同调整，不能宣称已实现逐类型远端撤回。
5. 当前设置页使用包名/发件人文本输入；可操作但未集成已有应用选择器。
6. 最终执行 App 新增两项回归、统一 Fast/PR 门禁以及设备验收，再按最终实际结果更新报告。

## 写集合

新增：

- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushModels.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/push/EncryptedPushStateStore.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/push/DevicePushRuntime.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/push/GatewayPushTransport.kt`
- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreen.kt`
- `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/DevicePushRuntimeTest.kt`
- `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/GatewayPushTransportTest.kt`
- `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/PushSettingsScreenTest.kt`
- `apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/NotificationPushSource.kt`
- `apps/android/notification-collector/src/test/kotlin/com/openandroidintelligence/notifications/NotificationPushSourceTest.kt`
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsPushSource.kt`
- `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsPushSourceTest.kt`
- 本报告。

修改：

- `apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/AndroidNotificationCollector.kt`
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsInboxReader.kt`
- `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/AndroidSmsInboxReader.kt`

未修改主 Agent 独占的生产装配、全局授权、执行驱动或应用 Gradle/Manifest 文件。
