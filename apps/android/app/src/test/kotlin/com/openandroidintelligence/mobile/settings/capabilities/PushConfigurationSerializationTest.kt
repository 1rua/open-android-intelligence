package com.openandroidintelligence.mobile.settings.capabilities

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.mobile.push.DevicePushRuntime
import com.openandroidintelligence.mobile.push.EncryptedPushStateStore
import com.openandroidintelligence.mobile.push.PushSystemAccess
import com.openandroidintelligence.mobile.push.PushConfiguration
import com.openandroidintelligence.mobile.push.NotificationPushScope
import com.openandroidintelligence.mobile.push.NotificationPushSettings
import com.openandroidintelligence.mobile.push.SmsPushScope
import com.openandroidintelligence.mobile.push.SmsPushSettings
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 推送设置必须串行保存、并以权威快照为准。
 *
 * 缺陷是：两次编辑都在异步刷新之前基于同一份旧快照构造新配置，第二次会覆盖第一次。
 * 例如先关掉正文推送、再换推送目标，正文开关会被悄悄恢复成开启——后续通知又开始带正文外发。
 * 这里在 `DevicePushRuntime` 上直接复现那条链路，验证修复后的写入口语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PushConfigurationSerializationTest {

    @get:Rule val compose = createComposeRule()

    private fun runtime(): DevicePushRuntime = DevicePushRuntime(
        EncryptedPushStateStore("serialization-test", InMemoryOutboxPersistence(), SecretKeySpec(ByteArray(32) { 1 }, "AES")),
        PushSystemAccess { true },
    )

    @Test
    fun aSecondEditBuiltFromAStaleSnapshotIsRejectedInsteadOfUndoingTheFirst() {
        val runtime = runtime()
        val initial = PushConfiguration(
            notifications = NotificationPushSettings(scope = NotificationPushScope(includeContent = true)),
        )
        runtime.configure(initial)
        val stale = runtime.snapshot().configuration

        // 第一次编辑：关闭推送正文。
        val first = stale.copy(notifications = stale.notifications.copy(
            scope = stale.notifications.scope.copy(includeContent = false),
        ))
        runtime.configure(first, expectedConfiguration = stale)
        assertFalse("第一次编辑必须生效", runtime.snapshot().configuration.notifications.scope.includeContent)

        // 第二次编辑仍基于旧快照：必须被拒绝，而不是把正文开关恢复成开启。
        val staleSecond = stale.copy(notifications = stale.notifications.copy(
            enabled = true,
            scope = stale.notifications.scope,
        ))
        val failure = runCatching { runtime.configure(staleSecond, expectedConfiguration = stale) }.exceptionOrNull()
        assertEquals("PUSH_CONFIGURATION_CHANGED", failure?.message)
        assertFalse("旧快照必须不能撤销前一次修改", runtime.snapshot().configuration.notifications.scope.includeContent)
    }

    @Test
    fun anEditBuiltFromTheAuthoritativeSnapshotAppliesBothChanges() {
        val runtime = runtime()
        runtime.configure(PushConfiguration())
        val base = runtime.snapshot().configuration

        val next = base
            .let { it.copy(notifications = it.notifications.copy(scope = it.notifications.scope.copy(includeContent = false))) }
            .let { it.copy(notifications = it.notifications.copy(enabled = true)) }
        runtime.configure(next, expectedConfiguration = base)

        val saved = runtime.snapshot().configuration
        assertFalse(saved.notifications.scope.includeContent)
        assertTrue(saved.notifications.enabled)
    }

    @Test
    fun theSectionDisablesFurtherEditsWhileASaveIsInFlight() {
        // 保存进行中必须禁用控件：这正是“第二次编辑”能被挡在 UI 层的原因之一。
        val runtime = runtime()
        runtime.configure(PushConfiguration(notifications = NotificationPushSettings(enabled = true)))
        val snapshot = runtime.snapshot()
        var commits = 0
        compose.setContent {
            var config by remember { mutableStateOf(snapshot.configuration) }
            MaterialTheme {
                com.openandroidintelligence.mobile.push.NotificationPushSection(
                    config = config,
                    endpoints = emptyList(),
                    saving = true,
                    error = null,
                    onConfigurationChange = { commits++; config = it },
                )
            }
        }
        compose.onNodeWithTag("push-notification-enabled").assertIsNotEnabled()
        compose.onNodeWithTag("push-notification-content").assertIsNotEnabled()
        assertEquals(0, commits)
    }

    @Test
    fun theSmsSectionAlsoDisablesFurtherEditsWhileSaving() {
        val runtime = runtime()
        runtime.configure(PushConfiguration(sms = SmsPushSettings(enabled = true, scope = SmsPushScope(allSenders = true))))
        val snapshot = runtime.snapshot()
        var commits = 0
        compose.setContent {
            var config by remember { mutableStateOf(snapshot.configuration) }
            MaterialTheme {
                com.openandroidintelligence.mobile.push.SmsPushSection(
                    config = config,
                    endpoints = emptyList(),
                    saving = true,
                    error = null,
                    onConfigurationChange = { commits++; config = it },
                    onRefreshEndpoints = {},
                )
            }
        }
        compose.onNodeWithTag("push-sms-enabled").assertIsNotEnabled()
        assertEquals(SmsScopeMode.ALL, snapshot.configuration.sms.scope.mode)
        assertEquals(0, commits)
    }
}
