package com.openandroidintelligence.kernel

import com.openandroidintelligence.plugin.pkg.PluginIdentity
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Exercise the production invoke -> runtime -> call -> host mediation entry with
 * the versioned public capability ID used by ProductionPluginHost. Only the WASM
 * runtime and native primitive are doubles; consent uses the real controller.
 * Native screen/session/lease guards remain covered by RemoteScreenRuntimeTest
 * and ScreenControlLeaseTest, rather than being replaced by this admission test.
 */
class ScreenControlKernelQueueTest {
    @Test(timeout = 15_000)
    fun versionedScreenCapabilityQueuesAcrossPairingsInFifoOrderWithOneRuntimeSlot() = Fixture().use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val second = f.start("second", pairingId = "pairing-b", hold = true)
        second.awaitQueued()
        val third = f.start("third")
        third.awaitQueued()

        assertEquals(listOf("first"), f.effects.toList())
        first.release()
        first.success()
        second.awaitEffect()
        assertEquals(listOf("first", "second"), f.effects.toList())
        assertFalse(third.completed)
        second.release()
        second.success()
        third.success()

        assertEquals(listOf("first", "second", "third"), f.effects.toList())
        assertEquals(1, f.maximumActiveRuntimes.get())
        assertEquals(1, f.kernel.registrationFor(f.identity.pluginId)!!.budget.maxConcurrentInvocations)
    }

    @Test(timeout = 15_000)
    fun queuedScreenControlRechecksRevokedTemporaryConsentBeforeStartingRuntime() = Fixture().use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val second = f.start("second")
        second.awaitQueued()
        f.authorization.revokeAll()
        first.release()

        assertDenied(first, "LOCAL_CONFIRMATION_REQUIRED")
        assertDenied(second, "LOCAL_CONFIRMATION_REQUIRED")
        assertEquals(listOf("first"), f.effects.toList())
        assertEquals(1, f.runtimeStarts.get())
        // A denial after acquisition must return the permit for a fresh approval.
        f.start("fresh").success()
    }

    @Test(timeout = 15_000)
    fun queuedScreenControlRechecksRequestCancellationBeforeStartingRuntime() = Fixture().use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val second = f.start("second")
        second.awaitQueued()
        f.authorization.cancel("second")
        first.release()

        first.success()
        assertDenied(second, "DEVICE_REQUEST_CANCELLED")
        assertEquals(listOf("first"), f.effects.toList())
        assertEquals(1, f.runtimeStarts.get())
        f.start("fresh").success()
    }

    @Test(timeout = 15_000)
    fun cancellingRunInterruptibleRemovesWaiterAndFollowingControlCanProceed() = Fixture().use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val cancelled = f.start("cancelled")
        cancelled.awaitQueued()
        val following = f.start("following")
        following.awaitQueued()
        cancelled.cancel()

        assertTrue(cancelled.failure() is CancellationException)
        assertEquals(listOf("first"), f.effects.toList())
        first.release()
        first.success()
        following.success()
        assertEquals(listOf("first", "following"), f.effects.toList())
        assertEquals(1, f.maximumActiveRuntimes.get())
    }

    @Test(timeout = 15_000)
    fun queuedScreenControlRechecksExpiredConsentBeforeStartingRuntime() = Fixture().use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val second = f.start("second", approvalMillis = 1_000)
        second.awaitQueued()
        f.elapsedMillis.addAndGet(1_000)
        first.release()

        first.success()
        assertDenied(second, "LOCAL_CONFIRMATION_REQUIRED")
        assertEquals(listOf("first"), f.effects.toList())
        assertEquals(1, f.runtimeStarts.get())
    }

    @Test(timeout = 15_000)
    fun queuedScreenControlCannotReuseAnOldGrantGeneration() = Fixture().use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val second = f.start("second")
        second.awaitQueued()
        f.grantRevision.incrementAndGet()
        first.release()

        assertTrue(first.failure() is CapabilityDenied)
        assertDenied(second, f.capability)
        assertEquals(listOf("first"), f.effects.toList())
        assertEquals(1, f.runtimeStarts.get())
        f.start("fresh").success()
    }

    @Test(timeout = 15_000)
    fun queuedScreenControlRechecksDisabledPluginBeforeStartingRuntime() = Fixture().use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val second = f.start("second")
        second.awaitQueued()
        f.kernel.disable(f.identity.pluginId)
        first.release()

        assertTrue(first.failure() is CapabilityDenied)
        assertDenied(second, f.capability)
        assertEquals(1, f.runtimeStarts.get())
        f.kernel.enable(f.identity.pluginId)
        f.start("fresh").success()
    }

    @Test(timeout = 15_000)
    fun queuedScreenControlRechecksProviderSelectionBeforeStartingRuntime() = Fixture().use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val second = f.start("second")
        second.awaitQueued()
        f.selector.setOverride(f.capability, "pairing-a",
            PluginIdentity("org.example.other-control", "B".repeat(43), "1.0.0"))
        first.release()

        first.success()
        assertTrue(second.failure() is ProviderRejected)
        assertEquals(1, f.runtimeStarts.get())
    }

    @Test(timeout = 15_000)
    fun queuedScreenControlRejectsReplacedRegistrationInsteadOfUsingItsOldPermit() = Fixture().use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val second = f.start("second")
        second.awaitQueued()
        f.kernel.register(f.kernel.registrationFor(f.identity.pluginId)!!.copy())
        first.release()

        first.success()
        assertDenied(second, f.capability)
        assertEquals(1, f.runtimeStarts.get())
        f.start("fresh").success()
    }

    @Test(timeout = 15_000)
    fun queuedScreenControlStillReachesFreshScreenSessionGuardBeforeAnyEffect() = Fixture().use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val second = f.start("second")
        second.awaitQueued()
        f.screenSession.incrementAndGet()
        first.release()

        first.success()
        assertDenied(second, "SCREEN_SESSION_BINDING_CHANGED")
        assertEquals(2, f.runtimeStarts.get())
        assertEquals(listOf("first"), f.effects.toList())
        f.start("fresh").success()
    }

    @Test(timeout = 15_000)
    fun queuedScreenControlStillReachesFreshScreenRevisionGuardBeforeAnyEffect() = Fixture().use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val second = f.start("second")
        second.awaitQueued()
        f.screenRevision.incrementAndGet()
        first.release()

        first.success()
        assertDenied(second, "SCREEN_REVISION_CHANGED")
        assertEquals(2, f.runtimeStarts.get())
        assertEquals(listOf("first"), f.effects.toList())
        f.start("fresh").success()
    }

    @Test(timeout = 15_000)
    fun nonScreenCapabilityRetainsFailFastConcurrencyBudget() = Fixture(control = false).use { f ->
        val first = f.start("first", hold = true)
        first.awaitEffect()
        val second = f.start("second")

        val failure = second.failure()
        assertTrue(failure is BudgetExceeded)
        assertEquals("BUDGET_EXCEEDED:CONCURRENCY", failure?.message)
        assertEquals(listOf("first"), f.effects.toList())
        first.release()
        first.success()
    }

    private fun assertDenied(worker: Worker, primitive: String) {
        val failure = worker.failure()
        assertTrue("Expected CapabilityDenied, got $failure", failure is CapabilityDenied)
        assertEquals(primitive, (failure as CapabilityDenied).primitive)
    }

    private class Request(
        val id: String,
        val pairingId: String,
        val screenSession: Long,
        val screenRevision: Long,
        hold: Boolean,
    ) {
        val input = "$id:$screenSession:$screenRevision".encodeToByteArray()
        val authorized = CountDownLatch(1)
        val effect = CountDownLatch(1)
        val release = CountDownLatch(if (hold) 1 else 0)
        val thread = AtomicReference<Thread>()
    }

    private class Worker(private val request: Request, private val result: Deferred<Result<PluginResult>>) {
        val completed get() = result.isCompleted
        fun release() = request.release.countDown()
        fun cancel() = result.cancel()
        fun awaitEffect() = assertTrue("Host effect did not start for ${request.id}",
            request.effect.await(5, TimeUnit.SECONDS))
        fun awaitQueued() {
            assertTrue("Invocation was not admitted for ${request.id}", request.authorized.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            // Once the first authorization returns, the only blocking operation in
            // this synchronous entry before the runtime is the plugin budget gate.
            while (!completed && request.thread.get().state != Thread.State.WAITING && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            assertFalse("Request was rejected instead of queued: ${request.id}", completed)
            assertEquals(Thread.State.WAITING, request.thread.get().state)
        }
        private fun outcome(): Result<PluginResult> = runBlocking {
            withTimeout(5_000) {
                try { result.await() } catch (cancelled: CancellationException) { Result.failure(cancelled) }
            }
        }
        fun success() {
            val value = outcome().getOrThrow()
            assertEquals(request.id, value.correlationId)
            assertArrayEquals(request.input, value.output)
        }
        fun failure(): Throwable? = outcome().exceptionOrNull()
    }

    private class Fixture(control: Boolean = true) : AutoCloseable {
        val primitive = if (control) "kernel.screen.control" else "kernel.sms.read"
        val identity = PluginIdentity(if (control) "org.openandroidintelligence.screen-control" else
            "org.openandroidintelligence.sms", "A".repeat(43), "1.0.0")
        // Do not accidentally test the primitive ID as the invocation capability:
        // production invokes the versioned manifest capability and carries its primitives in session.
        val capability = "${identity.pluginId}.${if (control) "control" else "read"}@1.0.0"
        private val primitives = setOf(primitive, capability)
        val elapsedMillis = AtomicLong(1_000)
        val authorization = CapabilityAuthorizationController(elapsedMillis::get)
        val grantRevision = AtomicLong(1)
        val screenSession = AtomicLong(1)
        val screenRevision = AtomicLong(1)
        val effects: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val runtimeStarts = AtomicInteger()
        val maximumActiveRuntimes = AtomicInteger()
        private val activeRuntimes = AtomicInteger()
        private val requests = ConcurrentHashMap<String, Request>()
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.IO)
        private val trust = DeveloperTrustMode()
        val selector = CapabilityProviderSelector(mapOf(capability to identity))
        lateinit var kernel: PluginKernel
            private set

        init {
            val runtime = object : PluginRuntime {
                override fun invoke(identity: PluginIdentity, budget: ResourceBudget, input: ByteArray): ByteArray {
                    runtimeStarts.incrementAndGet()
                    val active = activeRuntimes.incrementAndGet()
                    maximumActiveRuntimes.updateAndGet { maxOf(it, active) }
                    return try { kernel.call(identity, primitive, input) }
                    finally { activeRuntimes.decrementAndGet() }
                }
            }
            kernel = PluginKernel(HostEnvelope(primitives), PhoneLimits(primitives),
                mapOf(PluginKernel.RUNTIME_PROTECTED_WASM to runtime), AndroidAuditStore(), trust,
                NativePluginLoader(trust), selector,
                grants = { id -> PairingGrant(id, primitives, grantRevision.get()) },
                authorizeInvocation = { caller, invokedCapability, input, session ->
                    assertEquals(identity, caller)
                    assertEquals(capability, invokedCapability)
                    val request = requests.getValue(session.correlationId)
                    request.thread.set(Thread.currentThread())
                    try {
                        when (val decision = authorization.evaluate(request.id, consentScope(input), CapabilityPolicy(), 1L)) {
                            is CapabilityAuthorization.Allowed -> Unit
                            is CapabilityAuthorization.Denied -> throw CapabilityDenied(decision.code)
                            CapabilityAuthorization.AwaitingConfirmation -> throw CapabilityDenied("LOCAL_CONFIRMATION_REQUIRED")
                        }
                    } finally { request.authorized.countDown() }
                },
                mediate = { context, requestedPrimitive, input ->
                    assertEquals(primitive, requestedPrimitive)
                    val request = requests.getValue(context.session.correlationId)
                    assertEquals(request.pairingId, context.pairingId)
                    assertArrayEquals(request.input, input)
                    // The native backend double preserves the same last-dispatch
                    // fences; the admission queue must never cache/bypass them.
                    if (request.screenSession != screenSession.get()) throw CapabilityDenied("SCREEN_SESSION_BINDING_CHANGED")
                    if (request.screenRevision != screenRevision.get()) throw CapabilityDenied("SCREEN_REVISION_CHANGED")
                    effects += request.id
                    request.effect.countDown()
                    check(request.release.await(5, TimeUnit.SECONDS)) { "TEST_HOST_RELEASE_TIMEOUT" }
                    input
                })
            kernel.register(PluginRegistration(identity, PluginKernel.RUNTIME_PROTECTED_WASM, primitives,
                ResourceBudget(5_000, 16_777_216, 65_536, 1, 0)))
            kernel.enable(identity.pluginId)
        }

        private fun consentScope(input: ByteArray) = CapabilityRequestScope(capability,
            if (primitive == "kernel.screen.control") DeviceOperation.CONTROL else DeviceOperation.READ,
            MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) })

        fun start(id: String, pairingId: String = "pairing-a", hold: Boolean = false, approvalMillis: Long = 60_000): Worker {
            val request = Request(id, pairingId, screenSession.get(), screenRevision.get(), hold)
            check(requests.putIfAbsent(id, request) == null)
            authorization.approve(id, consentScope(request.input), 0, 1L, approvalMillis)
            val result = scope.async {
                runCatching {
                    runInterruptible {
                        kernel.invoke(identity, "account-$pairingId", pairingId, capability, request.input,
                            SessionConstraints(primitives, false, id))
                    }
                }
            }
            return Worker(request, result)
        }

        override fun close() {
            requests.values.forEach { it.release.countDown() }
            scope.cancel()
            runBlocking { withTimeout(5_000) { job.join() } }
        }
    }
}
