package com.openandroidintelligence.mobile.updates

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppUpdateViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun closeScope() { scope.cancel() }

    @Test
    fun previewSelectionSurvivesRecreationWithoutTouchingAccountPreferences() {
        val accountPreferences = context.getSharedPreferences("unrelated_account", 0)
        assertTrue(accountPreferences.edit().putString("account", "preserved").commit())
        val preferences = AppUpdatePreferences(context, AppUpdateChannel.STABLE)
        val model = AppUpdateViewModel(
            installed = installedBuild(),
            preferences = preferences,
            checkUpdate = { AppUpdateResult.NotPublished },
            ioDispatcher = Dispatchers.Unconfined,
            externalScope = scope,
        )

        model.selectChannel(AppUpdateChannel.PREVIEW)

        assertEquals(AppUpdateChannel.PREVIEW, model.uiState.value.channel)
        assertEquals(AppUpdateResult.NotPublished, model.uiState.value.result)
        assertEquals(AppUpdateChannel.PREVIEW, AppUpdatePreferences(context, AppUpdateChannel.STABLE).readChannel())
        assertEquals("preserved", accountPreferences.getString("account", null))
    }

    @Test
    fun duplicateChecksAreBlockedAndOldChannelCannotReplaceNewResult() {
        val started = CountDownLatch(1)
        val finishOld = CountDownLatch(1)
        val calls = AtomicInteger()
        val model = AppUpdateViewModel(
            installed = installedBuild(),
            preferences = AppUpdatePreferences(context, AppUpdateChannel.STABLE),
            checkUpdate = { channel ->
                calls.incrementAndGet()
                if (channel == AppUpdateChannel.STABLE) {
                    started.countDown()
                    check(finishOld.await(5, TimeUnit.SECONDS))
                    AppUpdateResult.UpToDate("旧渠道结果")
                } else {
                    AppUpdateResult.NotPublished
                }
            },
            externalScope = scope,
        )
        try {
            model.checkIfNeeded()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            model.check()
            assertEquals(1, calls.get())

            model.selectChannel(AppUpdateChannel.PREVIEW)
            await { model.uiState.value.result == AppUpdateResult.NotPublished }
            finishOld.countDown()
            runBlocking { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertEquals(AppUpdateChannel.PREVIEW, model.uiState.value.channel)
            assertFalse(model.uiState.value.isChecking)
            assertEquals(AppUpdateResult.NotPublished, model.uiState.value.result)
        } finally {
            finishOld.countDown()
        }
    }

    @Test
    fun checkIfNeededDoesNotRepeatACompletedRequestButManualRetryDoes() {
        var calls = 0
        val model = AppUpdateViewModel(
            installed = installedBuild(),
            preferences = AppUpdatePreferences(context, AppUpdateChannel.PREVIEW),
            checkUpdate = { calls++; AppUpdateResult.Unavailable(AppUpdateFailure.NETWORK) },
            ioDispatcher = Dispatchers.Unconfined,
            externalScope = scope,
        )

        model.checkIfNeeded()
        model.checkIfNeeded()
        assertEquals(1, calls)
        assertFalse(model.uiState.value.isChecking)
        assertEquals(AppUpdateResult.Unavailable(AppUpdateFailure.NETWORK), model.uiState.value.result)
        model.check()
        assertEquals(2, calls)
    }

    @Test
    fun defaultChannelFollowsTheInstalledBuild() {
        assertEquals(AppUpdateChannel.PREVIEW, defaultAppUpdateChannel(installedBuild()))
        assertEquals(AppUpdateChannel.PREVIEW, defaultAppUpdateChannel(installedBuild().copy(channel = "preview")))
        assertEquals(AppUpdateChannel.STABLE, defaultAppUpdateChannel(installedBuild().copy(channel = "stable")))
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("更新状态没有在期限内完成", predicate())
    }
}

internal fun installedBuild() = InstalledAppBuild(
    versionName = "2.1.1-dev.100+abcdef0",
    versionCode = 1000,
    commit = "abcdef0123456789abcdef0123456789abcdef01",
    channel = "development",
    applicationId = "com.openandroidintelligence.mobile",
    distribution = "full",
    signingCertificateSha256 = "a".repeat(64),
)
