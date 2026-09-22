package com.openandroidintelligence.notification.control

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 未装配时 binding 端口的 deny-first 契约：绑定查询返回 false 而不是抛异常；
 * 打开系统「通知使用权」设置的调用必须拒绝——未装配的监听器没有可授权的
 * 目标，引导用户去系统设置只会制造一个永远不会生效的开关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationBindingPortDenyFirstTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun uninstalled_listener_is_reported_as_not_bound_instead_of_throwing() {
        NotificationControlRegistry.reset()
        assertFalse(NotificationControlRegistry.bindingPort().isListenerBound())
    }

    @Test
    fun uninstalled_open_system_listener_settings_returns_false_and_launches_nothing() {
        NotificationControlRegistry.reset()

        val launched = NotificationControlRegistry.bindingPort().openSystemListenerSettings(context)

        assertFalse("未装配时不得发起系统设置跳转", launched)
        assertTrue(shadowOf(context).nextStartedActivity == null)
        assertSame(context, context)
    }
}
