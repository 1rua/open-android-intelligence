package com.openandroidintelligence.sms

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.telephony.SmsManager
import com.openandroidintelligence.capability.SmsSendRequest
import com.openandroidintelligence.capability.SmsSendStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 真实 Android 适配器和 PendingIntent，仅 SmsManager 的系统发送由 Robolectric shadow 接管。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33], application = Application::class)
class AndroidSmsManagerPortTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val manager get() = shadowOf(SmsManager.getSmsManagerForSubscriptionId(2))
    private val permission get() = "${app.packageName}.permission.SMS_SENT"
    private fun receivers() = shadowOf(app).registeredReceivers.filter {
        it.intentFilter.actionsIterator().asSequence().any { action -> action.startsWith("com.openandroidintelligence.sms.SEND_RESULT.") }
    }
    private fun drainCallbacks() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun private_immutable_callbacks_reject_unprivileged_broadcast_and_duplicate_segments() {
        val events = mutableListOf<Pair<Int, Int>>()
        var fences = 0
        val registration = AndroidSmsManagerPort(app, 2).sendMultipartTextMessage("+15550001", listOf("a", "b"),
            SmsSendBeforeSystemEffect { fences++ }, SmsSegmentCallback { segment, code -> events += segment to code })
        try {
            assertEquals(1, fences)
            val receiver = receivers().single()
            if (Build.VERSION.SDK_INT < 33) assertEquals(permission, receiver.broadcastPermission)
            else assertTrue(receiver.flags and android.content.Context.RECEIVER_NOT_EXPORTED != 0)
            val sent = manager.lastSentMultipartTextMessageParams
            assertEquals(listOf("a", "b"), sent.parts)
            assertNull(sent.deliveryIntents)
            val intents = sent.sentIntents
            intents.forEach {
                assertTrue(it.isImmutable)
                assertEquals(app.packageName, shadowOf(it).savedIntent.`package`)
                assertFalse(shadowOf(it).savedIntent.toUri(0).contains("+15550001"))
            }
            // 31/32 的系统没有 NOT_EXPORTED flag，必须由签名权限阻止无权限应用伪造回执。
            if (Build.VERSION.SDK_INT < 33) {
                shadowOf(app).denyPermissions(permission)
                app.sendBroadcast(Intent(shadowOf(intents.first()).savedIntent))
                drainCallbacks()
                assertTrue(events.isEmpty())
            }
            shadowOf(app).grantPermissions(permission)
            intents[0].send(app, Activity.RESULT_OK, null); drainCallbacks()
            intents[0].send(app, Activity.RESULT_OK, null); drainCallbacks()
            assertEquals(listOf(0 to Activity.RESULT_OK), events)
            assertEquals(1, receivers().size)
            intents[1].send(app, Activity.RESULT_OK, null); drainCallbacks()
            assertEquals(listOf(0 to Activity.RESULT_OK, 1 to Activity.RESULT_OK), events)
            assertTrue(receivers().isEmpty())
        } finally { registration.close() }
        manager.lastSentMultipartTextMessageParams.sentIntents.forEach { assertTrue(shadowOf(it).isCanceled) }
    }

    @Test fun rejected_final_fence_closes_receiver_and_pending_intents_without_system_dispatch() {
        manager.clearLastSentMultipartTextMessageParams()
        assertThrows(IllegalStateException::class.java) {
            AndroidSmsManagerPort(app, 2).sendMultipartTextMessage("+15550002", listOf("secret"),
                SmsSendBeforeSystemEffect { throw IllegalStateException("本机已拒绝") }, SmsSegmentCallback { _, _ -> fail("不应收到回执") })
        }
        assertNull(manager.lastSentMultipartTextMessageParams)
        assertTrue(receivers().isEmpty())
    }

    @Test fun missing_system_callback_timeout_and_cancel_both_close_native_receiver() = runBlocking {
        val provider = AndroidSmsSendProvider(timeoutMs = 20) { AndroidSmsManagerPort(app, it) }
        val result = provider.send(SmsSendRequest(2, "+15550003", "secret")) { }
        assertEquals(SmsSendStatus.OUTCOME_UNKNOWN, result.status)
        assertTrue(receivers().isEmpty())
        manager.lastSentMultipartTextMessageParams.sentIntents.forEach { assertTrue(shadowOf(it).isCanceled) }

        val dispatched = CompletableDeferred<Unit>()
        val cancelingProvider = AndroidSmsSendProvider { id ->
            val native = AndroidSmsManagerPort(app, id)
            object : SmsSendPort by native {
                override fun sendMultipartTextMessage(recipient: String, parts: List<String>, beforeSystemEffect: SmsSendBeforeSystemEffect,
                    callback: SmsSegmentCallback): AutoCloseable = native.sendMultipartTextMessage(recipient, parts, beforeSystemEffect, callback)
                    .also { dispatched.complete(Unit) }
            }
        }
        var canceledResult: com.openandroidintelligence.capability.SmsSendResult? = null
        val sending = async { cancelingProvider.send(SmsSendRequest(2, "+15550004", "secret")) { }.also { canceledResult = it } }
        dispatched.await(); assertEquals(1, receivers().size)
        sending.cancel(); runCatching { sending.await() }
        assertEquals(SmsSendStatus.OUTCOME_UNKNOWN, canceledResult?.status)
        assertTrue(receivers().isEmpty())
        manager.lastSentMultipartTextMessageParams.sentIntents.forEach { assertTrue(shadowOf(it).isCanceled) }
    }
}
