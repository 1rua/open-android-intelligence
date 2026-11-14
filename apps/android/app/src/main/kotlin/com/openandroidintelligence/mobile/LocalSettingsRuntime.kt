package com.openandroidintelligence.mobile

import com.openandroidintelligence.mobile.plugins.DeviceCapabilitySyncState
import kotlinx.coroutines.flow.StateFlow

/** 设置只持有本机偏好与刷新操作，不拥有账号认证或配对生命周期。 */
class LocalSettingsRuntime(
    val replyNotifications: ReplyNotificationSettings,
    val refreshNotifications: () -> Unit,
    val reloadMessageProcessing: () -> Unit,
    val refreshThreads: () -> Unit,
    val capabilitySync: StateFlow<DeviceCapabilitySyncState>? = null,
    val refreshCapabilities: () -> Unit = {},
)
