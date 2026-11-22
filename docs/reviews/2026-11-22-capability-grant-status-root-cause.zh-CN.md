# 「设备能力与授权」状态检测链路根因排查与修复方案（#219）

## 背景

在「设备能力与授权」页面及各能力详情页中，用户开启对应系统授权后，仍存在两处无法解除的阻塞码：

1. 开启无障碍授权后，屏幕内容读取 / 屏幕截图 / 屏幕控制 3 项能力持续显示 `手机服务未就绪；当前阻塞原因（SCREEN_CONNECTION_REQUIRED）`；
2. 开启通知访问权限后，通知读取持续显示 `未获手机授权；当前阻塞原因（LOCAL_GRANT_REQUIRED）`。

## 只读排查结论

### 清单与进程核对
- `apps/android/screen-control/src/main/AndroidManifest.xml` 已正确声明 `com.openandroidintelligence.screen.ScreenAccessibilityService`、`android:exported="true"`、`android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"` 及 `@xml/oai_screen_accessibility`，未设置 `android:process`（位于主进程）。
- `apps/android/notification-collector/src/main/AndroidManifest.xml` 已正确声明 `com.openandroidintelligence.notifications.OpenAndroidIntelligenceNotificationListenerService`、`android:exported="true"`、`android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`，未设置 `android:process`（位于主进程）。
- `PrimitiveBootstrapProvider` 与 `NotificationControlBootstrapProvider` 均在主进程完成注册，不存在跨进程拿不到内存服务实例的问题。

### 问题 1 断点（`SCREEN_CONNECTION_REQUIRED`）
1. **旧架构废弃条件残留**：`RemoteScreenRuntime.provider.availability()` 首行检查 `connections.values.none { connectionBinding(it) != null } -> "SCREEN_CONNECTION_REQUIRED"`。该 `connections` 集合原由旧版 Gateway v2 的 `GatewayRuntime` / `AdditionalDeviceConnections` 调用 `ScreenHostLifecycle.registerConnection(...)` 写入，在旧运行链退役（`cf1cbf19`）后，当前 DSH Remote 架构已不再调用该接口，导致 3 项屏幕原语在生产环境恒返 `SCREEN_CONNECTION_REQUIRED`。
2. **连接态变化缺少实时通知与 `ON_RESUME` 竞态**：`ScreenAccessibilityConnection` 仅提供断开监听，未暴露连接态响应流；主页面 `DeviceCapabilitiesScreen` 仅在 `ON_RESUME` 单次刷新，系统异步回调 `onServiceConnected()` 时无法即时刷新 UI。
3. **无障碍就绪探测方式单一且混杂锁屏态**：`AccessibilityServiceState` 仅做原始字符串比对，且直接以 `status.accessibilityError == null` 作为就绪依据，会将 `SCREEN_DEVICE_LOCKED` 混判为无障碍未就绪。

### 问题 2 断点（`LOCAL_GRANT_REQUIRED`）
1. **本地授权两套账本割裂**：`NotificationsReadPrimitive.availability()` 校验 `policy-engine` 的 `PersistentNotificationPolicyAuthority.granted`（默认 `false`），而能力页顶部总开关读写的是 `PairingGrantStateHolder`（默认 `true`），两者互不联动。用户开启系统通知使用权后，因 `authority.granted` 仍为 `false` 而恒返 `LOCAL_GRANT_REQUIRED`。
2. **通知使用权校验不统一且未联动监听服务连接态**：`HostNotificationBindingPort` 仅调用 `NotificationManager.isNotificationListenerAccessGranted`，未同时以 `ComponentName(context, OpenAndroidIntelligenceNotificationListenerService::class.java)` 交叉核验 `enabled_notification_listeners` 与 `NotificationListenerService` 真实连接态；`PermissionCatalog` 与 `DevicePushComposition` 仍按包名检查。
3. **`ON_RESUME` 缺少主动探测重连与连接态响应流**：`NotificationRuntimeFactoryRegistry` 未暴露连接态变化流；系统已授权但服务未连接时，返回前台未触发 `NotificationListenerService.requestRebind(componentName)`，主页面也未观察策略与连接态流。

## 修复方案

### 1. 屏幕 3 项能力
- 移除 `RemoteScreenRuntime.provider.availability()` 对旧架构 `connections` 的废弃前置检查（保留 `invoke()` 阶段原有请求级鉴权边界），使就绪状态直接由 `captureReadiness()` 与 `backend.availability()` 决定。
- 在 `control-ports`（`ScreenHostRegistry` / `ScreenHostStatus`）与 `screen-control`（`ScreenAccessibilityConnection` / `ScreenAccessibilityService`）暴露无障碍连接态观察流，将 `onServiceConnected()` / `onUnbind()` / `onDestroy()` 状态变化即时推送至 UI；增强 `AccessibilityServiceState` 使用 `ComponentName.unflattenFromString` + `AccessibilityManager` 交叉探测并区分锁屏与未连接。
- 在 `DeviceCapabilitiesScreen` 与 `CapabilitySettingsScreen` 中订阅无障碍连接态变化流，并在 `ON_RESUME` 时重新探测并刷新状态。

### 2. 通知读取
- 在 `notification-control` / `notification-host` / `notification-collector` 中：
  - `HostNotificationBindingPort` 统一使用 `ComponentName(appContext, OpenAndroidIntelligenceNotificationListenerService::class.java)` 动态校验 `NotificationManager.isNotificationListenerAccessGranted` 与 `Settings.Secure` 的 `enabled_notification_listeners`（解析为 `ComponentName` 精确比对）；
  - 暴露监听服务真实连接态（`isListenerConnected()`）及响应式连接状态流（`observeListenerConnected()`），并提供 `requestRebind()` 在系统已授权但服务未连时主动向系统发起重绑探测；
  - `PermissionCatalog` 与 `DevicePushComposition` 收口复用组件级校验。
- 厘清并同步“顶部通知读取能力开关 + Android 系统通知使用权”与 `policy-engine` 本地授权（`PersistentNotificationPolicyAuthority.granted`）的映射关系：
  - 当顶部通知读取能力处于启用态、且系统通知使用权已授予且监听服务就绪（或用户开启顶部开关且系统已就绪）时，同步将 `policy-engine` 的 `granted` 对齐为 `true`，正确解除 `LOCAL_GRANT_REQUIRED`；
  - 当用户显式关闭顶部通知读取开关时，同步将 `policy-engine` 的 `granted` 置为 `false`；反之在底部「通知采集范围」显式关闭「允许读取通知」时，同步关闭顶部通知读取开关，避免两套开关冲突。
- 在 `DeviceCapabilitiesScreen` 与 `CapabilitySettingsScreen` 的 `ON_RESUME` 及连接态/策略流变化时自动探测重连、对齐授权并即时刷新 UI。
