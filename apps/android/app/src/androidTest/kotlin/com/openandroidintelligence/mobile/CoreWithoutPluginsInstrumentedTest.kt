package com.openandroidintelligence.mobile

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import java.io.File
import java.time.Instant
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.AuditOutcome
import com.openandroidintelligence.kernel.AuditEvent
import com.openandroidintelligence.kernel.InMemoryAuditSink
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantCapabilities
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.PersistentAuditSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreWithoutPluginsInstrumentedTest {

    @Test
    fun freshInstallStartsWithAnHonestDisconnectedRuntime() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val application = appContext.applicationContext as OpenAndroidIntelligenceApplication
        val runtime = application.gatewayRuntime
        runtime.resetFailure()

        assertNotNull(appContext)
        assertEquals(ConnectionPhase.Disconnected, runtime.phase.value)
        assertNull(runtime.controller.value)
        assertFalse(application.kernel.isEmergencyStopped())
        assertNull(application.kernel.registrationFor("org.openandroidintelligence.sms"))
    }

    @Test
    fun unsupportedSchemeIsRejectedBeforeAnyNetworkAttempt() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val application = appContext.applicationContext as OpenAndroidIntelligenceApplication
        val runtime = application.gatewayRuntime

        runtime.login("ftp://gateway.example.com", "alice", "secret".toCharArray())

        assertEquals(
            ConnectionPhase.Failed("AUTH_INVALID:url-scheme-required"),
            runtime.phase.value,
        )
        runtime.resetFailure()
    }

    @Test
    fun plainHttpPassesThePairingGateAndFailsOnTheConnectionInstead() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val application = appContext.applicationContext as OpenAndroidIntelligenceApplication
        val runtime = application.gatewayRuntime

        // 明文地址在配对阶段被接受（ADR 0047）：失败必须来自真实连接尝试，
        // 而不是被 scheme 校验提前拒绝。
        runtime.login("http://127.0.0.1:1", "alice", "secret".toCharArray())
        assertEquals(ConnectionPhase.Negotiating, runtime.phase.value)

        // 这次尝试必须结束：把未完成的会话状态留给下一个用例会让后者读到
        // Negotiating，那是测试互相污染，而不是被测代码的行为。
        val failure = awaitFailure(runtime)
        assertNotNull("明文配对必须真的发起连接尝试，并以失败结束", failure)
        assertFalse(
            "明文地址不得在 scheme 校验处被拒绝",
            failure!!.code == "AUTH_INVALID:url-scheme-required",
        )
        runtime.resetFailure()
    }

    private fun awaitFailure(runtime: GatewayRuntime): ConnectionPhase.Failed? {
        val deadline = System.currentTimeMillis() + AWAIT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            (runtime.phase.value as? ConnectionPhase.Failed)?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    @Test
    fun theInstalledAppPermitsThePlaintextGatewayConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)

        // 明文配对（ADR 0047）在 Android 平台上依赖该开关；关闭它会让 http:// 网关
        // 在平台层被直接拒绝，而不是由 App 自己决定。
        assertTrue(
            "usesCleartextTraffic 必须为 true，否则 http:// 网关在平台层不可用",
            info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0,
        )
    }

    @Test
    fun mainActivityUsesANoActionBarTheme() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )
        val attributes = context.obtainStyledAttributes(
            info.theme,
            intArrayOf(android.R.attr.windowActionBar),
        )
        try {
            assertFalse("主 Activity 不得包含系统 ActionBar", attributes.getBoolean(0, true))
        } finally {
            attributes.recycle()
        }
    }

    @Test
    fun pairingGrantPreferencesSurviveCreatingANewStateHolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(
            "open_android_intelligence_pairing_grants_test",
            android.content.Context.MODE_PRIVATE,
        )
        preferences.edit().clear().commit()
        val binding = PairingGrantBinding(
            gatewayId = "https://gateway.example.com",
            accountId = "account-test",
            installationId = "install-test",
        )

        try {
            val first = PairingGrantStateHolder(
                SharedPreferencesPairingGrantStore(preferences),
                AndroidAuditStore(InMemoryAuditSink()),
            )
            first.bind(binding)
            first.updatePrimitive(PairingGrantCapabilities.SMS, enabled = true)
            first.updateScreenSelection(enabled = true)

            val restored = PairingGrantStateHolder(
                SharedPreferencesPairingGrantStore(preferences),
                AndroidAuditStore(InMemoryAuditSink()),
            )
            restored.bind(binding)

            assertEquals(first.state.value, restored.state.value)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun persistentAuditSinkCanReloadADeviceWrittenRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "audit-test-${System.nanoTime()}.log")
        val event = AuditEvent(
            pluginId = "platform",
            accountId = "account-test",
            pairingId = "pairing-test",
            action = "emergency.stop",
            outcome = AuditOutcome.ALLOWED,
            correlationId = "correlation-test",
            timestampUtc = "2026-09-03T00:00:00.000Z",
        )

        try {
            PersistentAuditSink(
                file = file,
                clock = { Instant.parse("2026-09-04T00:00:00Z") },
            ).write(event)

            val reloaded = PersistentAuditSink(
                file = file,
                clock = { Instant.parse("2026-09-04T00:00:00Z") },
            )

            assertEquals(listOf(event), reloaded.events())
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val AWAIT_MILLIS = 10_000L

        const val POLL_MILLIS = 50L
    }
}
