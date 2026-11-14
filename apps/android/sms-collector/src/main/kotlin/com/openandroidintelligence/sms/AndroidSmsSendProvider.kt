package com.openandroidintelligence.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import com.openandroidintelligence.capability.SmsSendRequest
import com.openandroidintelligence.capability.SmsSendResult
import com.openandroidintelligence.capability.SmsSendStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/** 每个系统回调只携带分段序号与 RESULT_*，不携带短信正文。 */
fun interface SmsSegmentCallback {
    fun onResult(segment: Int, resultCode: Int)
}

interface SmsSendPort {
    fun divideMessage(text: String): List<String>
    fun sendMultipartTextMessage(recipient: String, parts: List<String>, beforeSystemEffect: SmsSendBeforeSystemEffect, callback: SmsSegmentCallback): AutoCloseable
}

/** Android SmsManager 的窄适配器；subscriptionId 在构造时固定，不从系统默认订阅推断。 */
class AndroidSmsManagerPort(
    private val context: Context,
    private val subscriptionId: Int,
) : SmsSendPort {
    init {
        require(subscriptionId >= 0) { "subscription ID must be explicit and non-negative" }
    }

    private val manager: SmsManager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId)

    override fun divideMessage(text: String): List<String> = manager.divideMessage(text).toList()

    override fun sendMultipartTextMessage(recipient: String, parts: List<String>, beforeSystemEffect: SmsSendBeforeSystemEffect, callback: SmsSegmentCallback): AutoCloseable {
        require(parts.isNotEmpty())
        val operationId = UUID.randomUUID().toString()
        val action = "$ACTION_PREFIX.$operationId"
        val received = BooleanArray(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action) return
                val segment = intent.getIntExtra(EXTRA_SEGMENT, -1)
                if (segment !in received.indices) return
                val fresh = synchronized(received) {
                    if (received[segment]) false else {
                    received[segment] = true
                    true
                    }
                }
                if (!fresh) return
                callback.onResult(segment, resultCode)
                if (synchronized(received) { received.all { it } }) {
                    runCatching { context.unregisterReceiver(this) }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(action), "${context.packageName}.permission.SMS_SENT", null)
        }
        val sentIntents = ArrayList<PendingIntent>(parts.size)
        parts.forEachIndexed { index, _ ->
            val intent = Intent(action).setPackage(context.packageName).putExtra(EXTRA_SEGMENT, index)
            sentIntents += PendingIntent.getBroadcast(
                context,
                callbackIds.incrementAndGet().toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        try {
            beforeSystemEffect.check()
            @Suppress("DEPRECATION")
            manager.sendMultipartTextMessage(recipient, null, ArrayList(parts), sentIntents, null)
        } catch (failure: Throwable) {
            runCatching { context.unregisterReceiver(receiver) }
            throw failure
        }
        return AutoCloseable {
            sentIntents.forEach { it.cancel() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        const val ACTION_PREFIX = "com.openandroidintelligence.sms.SEND_RESULT"
        const val EXTRA_SEGMENT = "segment"
        val callbackIds = AtomicLong()
    }
}

fun interface SmsSendBeforeSystemEffect {
    fun check()
}

/**
 * 原生发送的唯一编排入口。检查授权后才调用 SmsManager；系统回执不完整时明确返回
 * OUTCOME_UNKNOWN，调用方不得据此自动重发。
 */
class AndroidSmsSendProvider(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val portFactory: (Int) -> SmsSendPort,
) {
    suspend fun send(request: SmsSendRequest, beforeSystemEffect: SmsSendBeforeSystemEffect): SmsSendResult {
        require(timeoutMs > 0)
        val port = portFactory(request.subscriptionId)
        val parts = port.divideMessage(request.text).takeIf { it.isNotEmpty() }
            ?: return SmsSendResult(SmsSendStatus.FAILED, 1, 0, 1, "EMPTY_SEGMENTS")
        val completion = CompletableDeferred<SmsSendResult>()
        val results = IntArray(parts.size) { Int.MIN_VALUE }
        var dispatched = false
        var registration: AutoCloseable? = null
        fun unknown() = synchronized(results) {
            SmsSendResult(SmsSendStatus.OUTCOME_UNKNOWN, parts.size,
                results.count { it == Activity.RESULT_OK },
                results.count { it != Int.MIN_VALUE && it != Activity.RESULT_OK }, "OUTCOME_UNKNOWN")
        }
        try {
            run {
                registration = port.sendMultipartTextMessage(request.recipient, parts, SmsSendBeforeSystemEffect {
                    beforeSystemEffect.check()
                    dispatched = true
                }) { segment, resultCode ->
                    if (segment !in results.indices) return@sendMultipartTextMessage
                    synchronized(results) {
                        if (results[segment] != Int.MIN_VALUE) return@synchronized
                        results[segment] = resultCode
                        if (results.all { it != Int.MIN_VALUE }) {
                            val failed = results.count { it != Activity.RESULT_OK }
                            completion.complete(
                                SmsSendResult(
                                    status = if (failed == 0) SmsSendStatus.SENT else SmsSendStatus.FAILED,
                                    segmentCount = parts.size,
                                    sentSegments = parts.size - failed,
                                    failedSegments = failed,
                                    failureCode = results.firstOrNull { it != Activity.RESULT_OK }?.toString(),
                                ),
                            )
                        }
                    }
                }
            }
            return withTimeout(timeoutMs) { completion.await() }
        } catch (_: TimeoutCancellationException) {
            return unknown()
        } catch (_: CancellationException) {
            // 取消发生在系统调用之后，无法证明系统没有发送，故仍然是未知结果。
            if (dispatched) return unknown()
            return SmsSendResult(SmsSendStatus.CANCELED, parts.size, 0, 0)
        } catch (_: SecurityException) {
            return SmsSendResult(SmsSendStatus.FAILED, parts.size, 0, parts.size, "SECURITY_EXCEPTION")
        } catch (failure: IllegalArgumentException) {
            if (!dispatched) throw failure
            return SmsSendResult(SmsSendStatus.FAILED, parts.size, 0, parts.size, "INVALID_ARGUMENT")
        } finally {
            registration?.close()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
