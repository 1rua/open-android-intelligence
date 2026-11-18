package com.openandroidintelligence.mobile

import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.mobile.push.*
import kotlinx.coroutines.flow.emptyFlow
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class IndependentConnectionDiscoveryTest {
    @Test fun healthyLastAccountDeliversDuringBothForegroundAndBackgroundBudgets() = runBlocking {
        for (cycleBudget in listOf(45_000L, 120_000L)) {
            val endpoint = PushEndpoint("healthy-core", "healthy-account", "receiver", 1)
            val local = DevicePushRuntime(EncryptedPushStateStore("discovery-$cycleBudget",
                InMemoryOutboxPersistence(), SecretKeySpec(ByteArray(32) { 7 }, "AES")), PushSystemAccess { true }, TestPushClock())
            local.configure(PushConfiguration(sms = SmsPushSettings(true, SmsPushScope(allSenders = true), setOf(endpoint))))
            local.onLoginStateChanged(true)
            local.onSms(PushSms(1, TestPushClock().epoch, "sender", "synthetic body"))
            val started = mutableSetOf<String>()
            val completed = CompletableDeferred<Unit>()
            val transport = TestPushTransport()
            val job = launch {
                withTimeout(cycleBudget) {
                    independentConnectionDiscovery(listOf("slow-a", "slow-b", "healthy"),
                        discover = { id ->
                            started += id
                            if (id != "healthy") awaitCancellation()
                            endpoint
                        }, unavailable = { error("The healthy result must arrive before sibling timeouts") })
                        .collect { ready ->
                            runDevicePushCycle(local, transport, revoke = {}, captureActiveNotifications = {},
                                captureSmsPage = { false }, onRevocationFailure = {}, endpointAvailable = { it == ready })
                            completed.complete(Unit)
                        }
                }
            }
            try {
                withTimeout(2_000) { completed.await() }
                assertEquals(setOf("slow-a", "slow-b", "healthy"), started)
                assertEquals("Healthy delivery must complete while both slow discoveries are still suspended", 0, local.snapshot().pendingDeliveries)
                assertTrue("Discovery still owns the independent slow accounts", job.isActive)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun oneAccountTimeoutOrFailureDoesNotDiscardOtherSuccessfulDiscoveries() = runBlocking {
        val unavailable = mutableSetOf<String>()
        val found = independentConnectionDiscovery(listOf("slow", "failed", "replaced", "healthy"), timeoutMillis = 40,
            discover = { id -> when (id) {
                "slow" -> awaitCancellation()
                "failed" -> error("synthetic network failure")
                "replaced" -> throw CancellationException("profile replaced")
                else -> id
            } }, unavailable = { unavailable += it }).toList()
        assertEquals(listOf("healthy"), found)
        assertEquals(setOf("slow", "failed", "replaced"), unavailable)
    }

    @Test fun oneReadyConnectionCannotHoldAnotherConnectionsDeliveryLock() = runBlocking {
        val slowEndpoint = PushEndpoint("slow-core", "slow-account", "slow-receiver", 1)
        val healthyEndpoint = PushEndpoint("healthy-core", "healthy-account", "healthy-receiver", 1)
        val local = DevicePushRuntime(EncryptedPushStateStore("independent-drains", InMemoryOutboxPersistence(),
            SecretKeySpec(ByteArray(32) { 7 }, "AES")), PushSystemAccess { true }, TestPushClock())
        local.configure(PushConfiguration(sms = SmsPushSettings(true, SmsPushScope(allSenders = true), setOf(slowEndpoint, healthyEndpoint))))
        local.onLoginStateChanged(true)
        local.onSms(PushSms(1, TestPushClock().epoch, "sender", "synthetic body"))
        val slowStarted = CompletableDeferred<Unit>()
        val transport = object : PushTransport {
            override suspend fun verifyEndpoint(endpoint: PushEndpoint): EndpointVerification {
                if (endpoint == slowEndpoint) {
                    slowStarted.complete(Unit)
                    awaitCancellation()
                }
                return EndpointVerification.VALID
            }
            override suspend fun send(delivery: PushDelivery, stillAuthorized: () -> Boolean) =
                PushReceipt(delivery.endpoint, delivery.event.eventId, "delivery-healthy", PushReceiptState.CONSUMED)
            override suspend fun poll(delivery: PushDelivery, deliveryId: String, stillAuthorized: () -> Boolean): PushReceipt = error("No pending receipt")
        }
        val slow = async { local.deliver(transport, connectionScope = "slow-core" to "slow-account") }
        try {
            withTimeout(2_000) { slowStarted.await() }
            val healthy = withTimeout(2_000) { local.deliver(transport, connectionScope = "healthy-core" to "healthy-account") }
            assertEquals(1, healthy.acknowledged)
            assertEquals("The slow route remains queued independently", 1, local.snapshot().pendingDeliveries)
            assertTrue(slow.isActive)
        } finally { slow.cancelAndJoin() }
    }

    @Test fun parentCancellationStopsEveryPendingDiscovery() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var stopped = 0
        var unavailable = 0
        val job = launch {
            independentConnectionDiscovery(listOf("a", "b"), discover = { id ->
                try {
                    if (id == "b") started.complete(Unit)
                    awaitCancellation()
                } finally { stopped++ }
            }, unavailable = { unavailable++ }).collect { _: Nothing -> error("No account completed") }
        }
        withTimeout(2_000) { started.await() }
        job.cancelAndJoin()
        assertEquals(2, stopped)
        assertEquals("Parent cancellation must not become an individual connection failure", 0, unavailable)
    }
}
