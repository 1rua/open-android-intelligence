package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LoginGatewayPrefillTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() = clear()

    private fun clear() {
        context.getSharedPreferences("open_android_intelligence_login", 0).edit().clear().commit()
    }

    @Test
    fun aMissingPreferenceReadsAsNullAndNeverAsABogusAddress() {
        assertNull(LoginGatewayPrefill.read(context))
        // 存储里存在但非法的地址同样不得交给输入框。
        context.getSharedPreferences("open_android_intelligence_login", 0).edit()
            .putString("preferred_gateway_url", "ftp://nope").commit()
        assertNull(LoginGatewayPrefill.read(context))
    }

    @Test
    fun writeRejectsAMalformedAddressInsteadOfStoringIt() {
        assertTrue(!LoginGatewayPrefill.write(context, "not a url"))
        assertNull(LoginGatewayPrefill.read(context))
    }

    @Test
    fun observingReportsClearsAsWellAsWritesSoARolledBackImportCannotLinger() = kotlinx.coroutines.runBlocking {
        LoginGatewayPrefill.write(context, "https://core-a.example")
        val events = java.util.concurrent.LinkedBlockingQueue<String?>()
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
        )
        val job = scope.launch {
            LoginGatewayPrefill.observe(context).collect { events.put(it) }
        }
        // 首值：已保存的地址。
        assertEquals("https://core-a.example", events.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        // 导入回滚会清空地址；清空同样必须被观察到，否则登录页会继续显示失败那次导入的值。
        LoginGatewayPrefill.clear(context)
        assertNull(events.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        assertNull(LoginGatewayPrefill.read(context))
    }
}
