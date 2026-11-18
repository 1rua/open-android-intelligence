package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemotePushTransportTest {
    private val target=RemotePushTarget("https://remote.example","owner",3,"client","sha256:"+"1".repeat(64),"desktop",
        "sha256:"+"2".repeat(64),"phone","sha256:"+"3".repeat(64),"harness","workspace","session")
    private val persistence=InMemoryOutboxPersistence()
    private val clock=TestPushClock()
    private fun runtime()=DevicePushRuntime(EncryptedPushStateStore("remote-test",persistence,SecretKeySpec(ByteArray(32){17},"AES")),PushSystemAccess { true },clock,2000)
    private fun ready()=runtime().also {
        it.configure(PushConfiguration(notifications=NotificationPushSettings(true,NotificationPushScope(setOf("test.app")),setOf(target.endpoint()))))
        it.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch)
        it.onNotification(PushNotification("key","test.app",clock.epoch,"title","secret body"))
    }
    private class Session(override val target: RemotePushTarget): RemotePushSession {
        override val label="明确目标"
        var current=true;var sends=0;var notification: JsonValue.JObject?=null
        var onNotify: () -> Unit = {}
        override fun isCurrent()=current
        override suspend fun notifyReference(requestId: String,reference: RemotePushReference,beforeSend: () -> Unit) {
            beforeSend();sends++;notification=reference.encode() as JsonValue.JObject;onNotify()
        }
    }
    private fun text(row: JsonValue.JObject,key: String)=JsonFields.string(row,key)!!
    @Test fun 主动通知只受理而不消费且重启不重发未知通知()=runBlocking {
        val runtime=ready();val session=Session(target)
        val transport=RemotePushTransport(runtime,{session}) { true }
        val first=runtime.deliver(transport)
        assertEquals(1,first.accepted);assertEquals(0,first.acknowledged);assertEquals(1,session.sends)
        assertEquals(1,runtime.snapshot().queuedEvents)
        assertFalse(Json.canonical(session.notification!!).contains("secret body"))
        val restored=runtime();restored.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch)
        restored.deliver(RemotePushTransport(restored,{session}) { true })
        assertEquals(1,session.sends);assertEquals(1,restored.snapshot().queuedEvents)
    }
    @Test fun 其他目标不可达保留队列且切回后仅向原会话主动通知()=runBlocking {
        val runtime=ready();var session=Session(target.copy(sessionId="other"))
        val transport=RemotePushTransport(runtime,{session}) { true }
        runtime.deliver(transport);assertEquals(0,session.sends);assertEquals(1,runtime.snapshot().queuedEvents)
        session=Session(target);runtime.deliver(transport)
        assertEquals(1,session.sends);assertEquals(1,runtime.snapshot().queuedEvents)
    }
    @Test fun prepared持久栅栏后崩溃不会重新发送且完整目标往返不串号()=runBlocking {
        val runtime=ready()
        var delivery: PushDelivery?=null
        runtime.deliver(object: PushTransport {
            override suspend fun verifyEndpoint(endpoint: PushEndpoint)=EndpointVerification.VALID
            override suspend fun send(value: PushDelivery,stillAuthorized: () -> Boolean): PushReceipt {
                delivery=value
                val prepared=runtime.prepareRemoteDelivery(value)
                return PushReceipt(value.endpoint,value.event.eventId,prepared.receipt.deliveryId,PushReceiptState.ACCEPTED)
            }
            override suspend fun poll(delivery: PushDelivery,deliveryId: String,stillAuthorized: () -> Boolean)=error("不应轮询")
        })
        val restored=runtime();restored.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch)
        val saved=restored.snapshot().configuration.notifications.endpoints.single()
        assertEquals(target,saved.remoteTarget)
        assertEquals(target,RemotePushTarget.decode(Json.parse(Json.canonical(target.encode()))))
        assertNotEquals(saved,target.copy(hostKeyFingerprint="sha256:"+"4".repeat(64)).endpoint())
        val session=Session(target)
        restored.deliver(RemotePushTransport(restored,{session}) { true })
        assertEquals(0,session.sends);assertEquals(1,restored.snapshot().pendingDeliveries)
        assertNotNull(delivery)
    }
    @Test fun 通知结果未知不重新投递且公开引用不携带授权凭证()=runBlocking {
        val runtime=ready();val session=Session(target)
        session.onNotify={ throw IllegalStateException("连接中断") }
        val transport=RemotePushTransport(runtime,{session}) { true }
        runtime.deliver(transport)
        val n=session.notification!!
        assertEquals("outcome_unknown",runtime.remoteReceipt(text(n,"deliveryId"))!!.dispatchState)
        val publicText=Json.canonical(n)
        assertFalse(publicText.contains("token"));assertFalse(publicText.contains("secret body"))
        runtime.deliver(transport)
        assertEquals(1,session.sends);assertEquals(1,runtime.snapshot().queuedEvents)
    }
}
