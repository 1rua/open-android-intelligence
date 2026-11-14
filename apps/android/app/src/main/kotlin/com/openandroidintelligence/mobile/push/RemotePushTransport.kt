package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.gateway.schema.Json
import kotlinx.coroutines.CancellationException

/** 当前选中的明确会话主动接收通知；其他白名单目标留在原加密队列，绝不自动切换。 */
class RemotePushTransport(
    private val runtime: DevicePushRuntime,
    private val session: () -> RemotePushSession?,
    private val identityCurrent: (RemotePushTarget) -> Boolean,
) : PushTransport {
    override suspend fun verifyEndpoint(endpoint: PushEndpoint): EndpointVerification {
        val target=endpoint.remoteTarget ?: return EndpointVerification.UNAVAILABLE
        if (!identityCurrent(target)) return EndpointVerification.UNAVAILABLE
        val borrowed=session() ?: return EndpointVerification.UNAVAILABLE
        return if (borrowed.target == target && borrowed.isCurrent()) EndpointVerification.VALID else EndpointVerification.UNAVAILABLE
    }
    override suspend fun send(delivery: PushDelivery,stillAuthorized: () -> Boolean): PushReceipt {
        val target=delivery.endpoint.remoteTarget ?: error("PUSH_REMOTE_TARGET_REQUIRED")
        val borrowed=session()?.takeIf { it.target == target && it.isCurrent() } ?: error("PUSH_SESSION_UNAVAILABLE")
        fun beforeSend() { if (!stillAuthorized() || !borrowed.isCurrent() || !identityCurrent(target)) throw CancellationException("PUSH_AUTHORIZATION_CHANGED") }
        beforeSend()
        val dispatch=runtime.prepareRemoteDelivery(delivery)
        if (dispatch.token != null) {
            // 正文仍只在加密队列；prompt携带一次收件通知，不等于事件已被消费。
            val notification=Json.canonical(Json.of(mapOf("type" to "oai.android.delivery","version" to 1,
                "phoneHostId" to target.phoneHostDeviceId,"deliveryId" to dispatch.receipt.deliveryId,
                "eventId" to dispatch.receipt.eventId,"payloadDigest" to dispatch.receipt.payloadDigest,
                "token" to dispatch.token,"expiresAtEpochMs" to dispatch.receipt.expiresAt,
                "readTool" to "android_read_delivery","ackTool" to "android_ack_delivery")))
            runtime.remoteDispatchState(dispatch.receipt.deliveryId,"dispatched")
            try {
                borrowed.notify(dispatch.receipt.deliveryId,notification,::beforeSend)
                runtime.remoteDispatchState(dispatch.receipt.deliveryId,"accepted")
            }
            catch (cancelled: CancellationException) {
                runtime.remoteDispatchState(dispatch.receipt.deliveryId,"outcome_unknown");throw cancelled
            }
            catch (_: Exception) { runtime.remoteDispatchState(dispatch.receipt.deliveryId,"outcome_unknown") }
        } else if (dispatch.receipt.dispatchState in setOf("prepared","dispatched")) {
            runtime.remoteDispatchState(dispatch.receipt.deliveryId,"outcome_unknown")
        }
        return poll(delivery,dispatch.receipt.deliveryId,stillAuthorized)
    }
    override suspend fun poll(delivery: PushDelivery,deliveryId: String,stillAuthorized: () -> Boolean): PushReceipt {
        val target=delivery.endpoint.remoteTarget ?: error("PUSH_REMOTE_TARGET_REQUIRED")
        fun requireCurrent() {
            val queuedAuthorized=stillAuthorized()
            if (!identityCurrent(target) || (!queuedAuthorized && !runtime.consumedReceiptCurrent(delivery,deliveryId)))
                throw CancellationException("PUSH_AUTHORIZATION_CHANGED")
        }
        requireCurrent()
        val record=runtime.remoteReceipt(deliveryId) ?: return PushReceipt(delivery.endpoint,delivery.event.eventId,deliveryId,PushReceiptState.RETRY)
        check(record.endpoint == delivery.endpoint && record.eventId == delivery.event.eventId) { "PUSH_RECEIPT_SCOPE_MISMATCH" }
        requireCurrent()
        return PushReceipt(record.endpoint,record.eventId,record.deliveryId,
            if (record.consumed) PushReceiptState.CONSUMED else PushReceiptState.ACCEPTED)
    }
    /** 本机撤销已经原子移除正文，未发给远端正文，无远端缓冲需要再清理。 */
    suspend fun revoke(revocation: PushRevocation) {
        check(revocation.endpoint.remoteTarget != null) { "PUSH_REMOTE_TARGET_REQUIRED" }
        runtime.revokeRemoteReceipts(revocation)
    }
}
