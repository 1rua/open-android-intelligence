package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.encrypted.store.EncryptedOutboxPersistence
import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.gateway.schema.Json
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemotePushRecipientAuthorizationTest {
    private val clock=TestPushClock()
    private val disk=InMemoryOutboxPersistence()
    private val target=RemotePushTarget("https://remote.example","owner",3,"phone-client","sha256:"+"1".repeat(64),
        "desktop-host","sha256:"+"2".repeat(64),"phone-host","sha256:"+"3".repeat(64),"harness","workspace","session")
    private fun reader(id: String="desktop-client")=RemoteControlPrincipal(ControlOrigin.fromCanonical(target.origin),target.accountId,id,
        target.phoneHostDeviceId,"sha256:"+"4".repeat(64),target.phoneHostKeyFingerprint)
    private fun access(id: String="desktop-client")=ControlAccessBinding(reader(id),3,"connection-$id",1)
    private fun runtime(persistence: EncryptedOutboxPersistence=disk)=DevicePushRuntime(EncryptedPushStateStore("recipient-test",persistence,SecretKeySpec(ByteArray(32){19},"AES")),PushSystemAccess { true },clock)
    private fun ready(targets: Set<PushEndpoint> = setOf(target.endpoint()))=runtime().also {
        it.configure(PushConfiguration(notifications=NotificationPushSettings(true,NotificationPushScope(setOf("test.app")),targets)))
        it.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch)
    }
    private suspend fun delivery(runtime: DevicePushRuntime,selected: RemotePushTarget=target): ControlOperation.Delivery {
        runtime.onNotification(PushNotification("key-${clock.epoch}","test.app",clock.epoch,"title","secret body"))
        var operation: ControlOperation.Delivery?=null
        runtime.deliver(object: PushTransport {
            override suspend fun verifyEndpoint(endpoint: PushEndpoint)=if (endpoint == selected.endpoint()) EndpointVerification.VALID else EndpointVerification.UNAVAILABLE
            override suspend fun send(delivery: PushDelivery,stillAuthorized: () -> Boolean): PushReceipt {
                val row=runtime.prepareRemoteDelivery(delivery).receipt
                operation=ControlOperation.Delivery(row.deliveryId,row.eventId,row.payloadDigest,false)
                return PushReceipt(delivery.endpoint,row.eventId,row.deliveryId,PushReceiptState.ACCEPTED)
            }
            override suspend fun poll(delivery: PushDelivery,deliveryId: String,stillAuthorized: () -> Boolean): PushReceipt {
                val row=runtime.remoteReceipt(deliveryId)!!
                operation=ControlOperation.Delivery(row.deliveryId,row.eventId,row.payloadDigest,false)
                return PushReceipt(delivery.endpoint,row.eventId,row.deliveryId,PushReceiptState.ACCEPTED)
            }
        })
        return checkNotNull(operation)
    }
    private suspend fun authorize(runtime: DevicePushRuntime,operation: ControlOperation.Delivery,access: ControlAccessBinding=access(),
        current: () -> Unit = {},confirm: suspend (String) -> Boolean = { true }) {
        RemotePushRecipientAuthorizer(runtime).authorize(access,operation,current,{},confirm)
    }
    private fun read(runtime: DevicePushRuntime,op: ControlOperation.Delivery,principal: RemoteControlPrincipal=reader())=
        runtime.readRemoteDelivery(op.deliveryId,op.eventId,op.payloadDigest,principal)
    private fun ack(runtime: DevicePushRuntime,op: ControlOperation.Delivery,principal: RemoteControlPrincipal=reader())=
        runtime.acknowledgeRemoteDelivery(op.deliveryId,op.eventId,op.payloadDigest,principal)

    @Test fun 公开引用和同账号不能授权而首次本机批准允许不同于手机的实际桌面Client()=runBlocking {
        val runtime=ready();val op=delivery(runtime)
        assertNotEquals(target.clientDeviceId,reader().controllerDeviceId)
        assertTrue(runCatching { read(runtime,op) }.isFailure)
        var detail=""
        authorize(runtime,op,confirm={detail=it;true})
        assertTrue(detail.contains("desktop-client"));assertTrue(detail.contains(target.hostKeyFingerprint))
        assertTrue(detail.contains(target.backend));assertTrue(detail.contains(target.workspaceId));assertTrue(detail.contains(target.sessionId))
        assertFalse(detail.contains("secret body"));assertFalse(detail.contains("token"))
        assertTrue(read(runtime,op).contains("secret body"))
        assertTrue(ack(runtime,op).consumed);assertTrue(ack(runtime,op).consumed)
        assertEquals(0,runtime.snapshot().queuedEvents)
    }

    @Test fun 拒绝确认或确认后连接失效不能绑定也不丢未ACK正文()=runBlocking {
        val runtime=ready();val op=delivery(runtime)
        assertTrue(runCatching { authorize(runtime,op,confirm={false}) }.isFailure)
        var current=true
        assertTrue(runCatching { authorize(runtime,op,current={check(current)},confirm={current=false;true}) }.isFailure)
        assertTrue(runCatching { read(runtime,op) }.isFailure)
        assertEquals(1,runtime.snapshot().queuedEvents)
        authorize(runtime,op);assertTrue(read(runtime,op).contains("secret body"))
    }

    @Test fun 首次并发不同主体只有先批准主体生效且不能偷偷替换()=runBlocking {
        val runtime=ready();val op=delivery(runtime)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val first=async { runCatching { authorize(runtime,op,access("first"),confirm={entered.complete(Unit);release.await();true}) } }
        entered.await()
        authorize(runtime,op,access("second"))
        release.complete(Unit)
        assertTrue(first.await().isFailure)
        assertTrue(runCatching { read(runtime,op,reader("first")) }.isFailure)
        assertTrue(read(runtime,op,reader("second")).contains("secret body"))
    }

    @Test fun 批准后同目标自动取件且进程恢复保留许可和原未ACK事件()=runBlocking {
        val runtime=ready();val original=delivery(runtime)
        authorize(runtime,original);read(runtime,original)
        val restored=runtime();restored.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch)
        authorize(restored,original,confirm={error("已批准的精确主体不能再次弹窗")})
        assertTrue(read(restored,original).contains("secret body"))
        clock.epoch++;clock.elapsed++
        val next=delivery(restored)
        authorize(restored,next,confirm={error("同一已批准目标应自动取件")})
        assertTrue(read(restored,next).contains("secret body"))
        assertEquals(2,restored.snapshot().queuedEvents)
    }

    @Test fun 同主体另一个目标必须单独确认且手机手动切换不改变原队列()=runBlocking {
        val other=target.copy(sessionId="another-session")
        val runtime=ready(setOf(target.endpoint(),other.endpoint()));val first=delivery(runtime)
        authorize(runtime,first);read(runtime,first)
        val second=delivery(runtime,other)
        assertTrue(runCatching { read(runtime,second) }.isFailure)
        var confirmations=0;authorize(runtime,second,confirm={confirmations++;true})
        assertEquals(1,confirmations);assertTrue(read(runtime,second).contains("secret body"))
        assertEquals(1,runtime.snapshot().queuedEvents)
    }

    @Test fun 确认期间配置撤销禁止生效而授权范围修改需重新批准()=runBlocking {
        val runtime=ready();val op=delivery(runtime)
        val original=runtime.snapshot().configuration
        assertTrue(runCatching { authorize(runtime,op,confirm={
            runtime.configure(original.copy(notifications=original.notifications.copy(scope=NotificationPushScope(setOf("test.app","other.app")))))
            true
        }) }.isFailure)
        assertTrue(runCatching { read(runtime,op) }.isFailure)
        // 改回相同配置也不能复活旧确认挑战。
        val changed=runtime.snapshot().configuration
        val pending=runtime.recipientApproval(op,reader())!!
        runtime.configure(original);runtime.configure(changed)
        assertTrue(runCatching { runtime.approveRemoteRecipient(pending,{}) }.isFailure)
        authorize(runtime,op);assertTrue(read(runtime,op).contains("secret body"))
        runtime.revokeRemoteRecipients()
        assertTrue(runCatching { runtime.requireRemoteDeliveryContent(op,reader()) }.isFailure)
        assertEquals(1,runtime.snapshot().queuedEvents)
    }

    @Test fun 账号代次主机pin或手机身份变化撤销许可并保留原未ACK归属()=runBlocking {
        val runtime=ready();val op=delivery(runtime);authorize(runtime,op);read(runtime,op)
        runtime.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch+1)
        assertTrue(runCatching { read(runtime,op) }.isFailure)
        runtime.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch)
        assertTrue(runCatching { read(runtime,op) }.isFailure)
        authorize(runtime,op)
        runtime.invalidateRemoteRecipients { it.hostKeyFingerprint != target.hostKeyFingerprint }
        assertTrue(runCatching { read(runtime,op) }.isFailure)
        assertTrue(runCatching { read(runtime,op,reader().copy(hostKeyFingerprint="sha256:"+"9".repeat(64))) }.isFailure)
        assertEquals(target,runtime.snapshot().configuration.notifications.endpoints.single().remoteTarget)
        assertEquals(1,runtime.snapshot().queuedEvents)
    }

    @Test fun 加密提交期间authority失效时并发读看不到中间许可且重启仍需确认()=runBlocking {
        val backing=InMemoryOutboxPersistence()
        val armed=java.util.concurrent.atomic.AtomicBoolean(false)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val persistence=object: EncryptedOutboxPersistence by backing {
            override fun write(ciphertext: ByteArray) {
                if (armed.compareAndSet(true,false)) {
                    entered.countDown()
                    check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
                }
                backing.write(ciphertext)
            }
        }
        val runtime=runtime(persistence)
        runtime.configure(PushConfiguration(notifications=NotificationPushSettings(true,NotificationPushScope(setOf("test.app")),setOf(target.endpoint()))))
        runtime.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch)
        val op=delivery(runtime);val approval=runtime.recipientApproval(op,reader())!!
        val current=java.util.concurrent.atomic.AtomicBoolean(true)
        val workers=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            armed.set(true)
            val approving=workers.submit<Boolean> { runCatching { runtime.approveRemoteRecipient(approval) { check(current.get()) } }.isSuccess }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val reading=workers.submit<Boolean> { runCatching { read(runtime,op) }.isSuccess }
            current.set(false);release.countDown()
            assertFalse(approving.get(5,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(reading.get(5,java.util.concurrent.TimeUnit.SECONDS))
            val restored=runtime(persistence);restored.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch)
            assertTrue(runCatching { read(restored,op) }.isFailure)
            assertEquals(1,restored.snapshot().queuedEvents)
        } finally { release.countDown();workers.shutdownNow() }
    }

    @Test fun 批准未完成时无需回滚写入且写失败或进程终止重启不能恢复许可()=runBlocking {
        for (fault in listOf("pending-write-failed","guard-invalid-and-all-writes-failed","process-stopped-after-pending")) {
            val backing=InMemoryOutboxPersistence()
            var armed=false;var writes=0;var rejectWrites=false
            val persistence=object: EncryptedOutboxPersistence by backing {
                override fun write(ciphertext: ByteArray) {
                    if (rejectWrites) throw java.io.IOException("后续持久化不可用")
                    backing.write(ciphertext)
                    if (armed) {
                        writes++
                        when (fault) {
                            "pending-write-failed" -> { armed=false;throw java.io.IOException("候选写入后报告失败") }
                            "process-stopped-after-pending" -> { armed=false;throw AssertionError("模拟持久候选后进程立即终止") }
                            else -> rejectWrites=true
                        }
                    }
                }
            }
            val runtime=runtime(persistence)
            runtime.configure(PushConfiguration(notifications=NotificationPushSettings(true,NotificationPushScope(setOf("test.app")),setOf(target.endpoint()))))
            runtime.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch)
            val op=delivery(runtime);val approval=runtime.recipientApproval(op,reader())!!
            armed=true
            assertTrue(fault,runCatching { runtime.approveRemoteRecipient(approval) { check(!rejectWrites) } }.isFailure)
            assertEquals("失败批准只留下不授信候选，不应依赖再次写入回滚",1,writes)
            val persisted=EncryptedPushStateStore("recipient-test",backing,SecretKeySpec(ByteArray(32){19},"AES")).load()
            assertFalse(persisted.remoteRecipients.getValue(target.endpoint()).active)
            assertEquals(1,persisted.events.size)
            val restored=runtime(backing);restored.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch)
            assertTrue(fault,runCatching { read(restored,op) }.isFailure)
            var confirmations=0
            authorize(restored,op,confirm={confirmations++;true})
            assertEquals(1,confirmations);assertTrue(read(restored,op).contains("secret body"))
            assertEquals(1,restored.snapshot().queuedEvents)
        }
    }

    @Test fun pending复核时本机撤销不能被随后active提交覆盖()=runBlocking {
        val runtime=ready();val op=delivery(runtime);val approval=runtime.recipientApproval(op,reader())!!
        var checks=0
        assertTrue(runCatching { runtime.approveRemoteRecipient(approval) {
            if (++checks == 2) runtime.revokeRemoteRecipients()
        } }.isFailure)
        assertTrue(runCatching { read(runtime,op) }.isFailure)
        val restored=runtime();restored.onRemoteAccountChanged(target.origin,target.accountId,target.accountEpoch)
        assertTrue(runCatching { read(restored,op) }.isFailure)
        authorize(restored,op);assertTrue(read(restored,op).contains("secret body"))
    }

    @Test fun 读取后撤销白名单不能ACK或从历史检查放行正文()=runBlocking {
        val runtime=ready();val op=delivery(runtime);authorize(runtime,op);read(runtime,op)
        runtime.configure(PushConfiguration())
        assertTrue(runCatching { ack(runtime,op) }.isFailure)
        assertTrue(runCatching { runtime.requireRemoteDeliveryContent(op,reader()) }.isFailure)
    }
}
