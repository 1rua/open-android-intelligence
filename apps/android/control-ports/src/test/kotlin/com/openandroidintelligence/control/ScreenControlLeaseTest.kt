package com.openandroidintelligence.control

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScreenControlLeaseTest {
    private val epoch = 1_000_000L
    private fun TestScope.manager(clock: () -> Long = { epoch + testScheduler.currentTime }) =
        ScreenControlLeaseManager(clock, testScheduler.timeSource)

    @Test fun contendersExecuteInFifoOrderAndCompletionReleasesOwnership() = runTest {
        val manager = manager()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = async {
            manager.withLease("first", epoch + 60_000, {}) { order += "first"; release.await() }
        }
        runCurrent()
        val next = listOf("second", "third", "fourth").map { id ->
            async { manager.withLease(id, epoch + 60_000, {}) { order += id } }.also { runCurrent() }
        }
        assertEquals(listOf("first"), order)
        release.complete(Unit)
        first.await()
        next.awaitAll()
        assertEquals(listOf("first", "second", "third", "fourth"), order)
        manager.withLease("after", epoch + 60_000, {}) { order += "after" }
        assertEquals("after", order.last())
    }

    @Test fun parallelThreadsNeverHaveTwoOwners() = runTest {
        val manager = ScreenControlLeaseManager()
        val owners = AtomicInteger()
        val maximum = AtomicInteger()
        val deadline = System.currentTimeMillis() + 60_000
        (1..48).map { index ->
            async(Dispatchers.Default) {
                manager.withLease("thread-$index", deadline, {}) { lease ->
                    val count = owners.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, count) }
                    try { repeat(3) { yield(); lease.ensureCurrent() } }
                    finally { owners.decrementAndGet() }
                }
            }
        }.awaitAll()
        assertEquals(1, maximum.get())
        assertEquals(0, owners.get())
    }

    @Test fun cancelledWaiterNeverRunsAndDoesNotBlockFollowingWaiter() = runTest {
        val manager = manager()
        val release = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val first = async { manager.withLease("first", epoch + 60_000, {}) { release.await() } }
        runCurrent()
        val cancelled = launch { manager.withLease("cancelled", epoch + 60_000, {}) { effects += "cancelled" } }
        runCurrent()
        val next = async { manager.withLease("next", epoch + 60_000, {}) { effects += "next" } }
        runCurrent()
        cancelled.cancelAndJoin()
        release.complete(Unit)
        first.await(); next.await()
        assertEquals(listOf("next"), effects)
    }

    @Test fun cancellingOwnerReleasesToNextOnlyAfterCleanupFinishes() = runTest {
        val manager = manager()
        val cleanup = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val effects = mutableListOf<String>()
        val first = async {
            failureCode {
                manager.withLease("first", epoch + 600_000, {}) {
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanupStarted.complete(Unit); cleanup.await() } }
                }
            }
        }
        runCurrent()
        val next = async { manager.withLease("next", epoch + 600_000, {}) { effects += "next" } }
        runCurrent()
        manager.cancel("first")
        runCurrent()
        cleanupStarted.await()
        assertTrue(effects.isEmpty())
        cleanup.complete(Unit)
        assertEquals("SCREEN_CONTROL_CANCELLED", first.await())
        next.await()
        assertEquals(listOf("next"), effects)
    }

    @Test fun expiredQueuedRequestNeverEntersAction() = runTest {
        val manager = manager()
        val release = CompletableDeferred<Unit>()
        var performed = false
        val first = async { manager.withLease("first", epoch + 60_000, {}) { release.await() } }
        runCurrent()
        val queued = async { failureCode { manager.withLease("expired", epoch + 1_000, {}) { performed = true } } }
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals("SCREEN_REQUEST_EXPIRED", queued.await())
        release.complete(Unit); first.await()
        assertFalse(performed)
    }

    @Test fun queueHasFiniteWaitEvenWhenCancelledOwnerIsStillCleaningUp() = runTest {
        val manager = manager()
        val cleanup = CompletableDeferred<Unit>()
        val first = async {
            failureCode {
                manager.withLease("first", epoch + 900_000, {}) {
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanup.await() } }
                }
            }
        }
        runCurrent()
        var performed = false
        val queued = async { failureCode { manager.withLease("queued", epoch + 900_000, {}) { performed = true } } }
        runCurrent()
        advanceTimeBy(ScreenControlLeaseManager.MAX_QUEUE_WAIT_MS); runCurrent()
        assertEquals("SCREEN_CONTROL_QUEUE_TIMEOUT", queued.await())
        assertFalse(performed)
        cleanup.complete(Unit)
        assertEquals("SCREEN_CONTROL_LEASE_EXPIRED", first.await())
    }

    @Test fun initialLeaseExpiresAfterThirtySecondsAndReleasesOwnership() = runTest {
        val manager = manager()
        val first = async { failureCode { manager.withLease("first", epoch + 600_000, {}) { awaitCancellation() } } }
        runCurrent()
        val next = async { manager.withLease("next", epoch + 600_000, {}) { "next" } }
        runCurrent()
        advanceTimeBy(29_999); runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(next.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals("SCREEN_CONTROL_LEASE_EXPIRED", first.await())
        assertEquals("next", next.await())
    }

    @Test fun heartbeatExtendsOnlyCurrentOwnerAndNeverBeyondFiveMinutes() = runTest {
        val manager = manager()
        val first = async { failureCode { manager.withLease("first", epoch + 600_000, {}) { awaitCancellation() } } }
        runCurrent()
        repeat(10) {
            advanceTimeBy(29_000); runCurrent()
            val remaining = manager.heartbeat("first")
            assertEquals(minOf(30_000L, 300_000L - testScheduler.currentTime), remaining)
        }
        advanceTimeBy(9_999); runCurrent()
        assertFalse(first.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals("SCREEN_CONTROL_LEASE_EXPIRED", first.await())
        assertEquals("SCREEN_CONTROL_LEASE_INACTIVE", failureCode { manager.heartbeat("first") })
    }

    @Test fun heartbeatAtExpiryCannotReviveLeaseBeforeWatchdogRuns() = runTest {
        val manager = manager()
        val first = async { failureCode { manager.withLease("first", epoch + 600_000, {}) { awaitCancellation() } } }
        runCurrent()
        advanceTimeBy(30_000)
        assertEquals("SCREEN_CONTROL_LEASE_EXPIRED", failureCode { manager.heartbeat("first") })
        runCurrent()
        assertEquals("SCREEN_CONTROL_LEASE_EXPIRED", first.await())
    }

    @Test fun queuedRequestCannotHeartbeatAnotherOwnersLease() = runTest {
        val manager = manager()
        val first = async { failureCode { manager.withLease("first", epoch + 600_000, {}) { awaitCancellation() } } }
        runCurrent()
        val queued = async { failureCode { manager.withLease("queued", epoch + 600_000, {}) {} } }
        runCurrent()
        assertEquals("SCREEN_CONTROL_LEASE_INACTIVE", failureCode { manager.heartbeat("queued") })
        manager.revokeAll()
        assertEquals("SCREEN_CONTROL_REVOKED", first.await())
        assertEquals("SCREEN_CONTROL_REVOKED", queued.await())
    }

    @Test fun emergencyStopRevokesOwnerAndEveryExistingWaiter() = runTest {
        val manager = manager()
        var oldLease: ScreenControlLease? = null
        val first = async { failureCode { manager.withLease("first", epoch + 600_000, {}) { oldLease = it; awaitCancellation() } } }
        runCurrent()
        var performed = false
        val queued = async { failureCode { manager.withLease("queued", epoch + 600_000, {}) { performed = true } } }
        runCurrent()
        manager.revokeAll()
        assertEquals("SCREEN_CONTROL_REVOKED", first.await())
        assertEquals("SCREEN_CONTROL_REVOKED", queued.await())
        assertFalse(performed)
        assertEquals("SCREEN_CONTROL_REVOKED", failureCode { checkNotNull(oldLease).ensureCurrent() })
        assertEquals("fresh", manager.withLease("fresh", epoch + 600_000, {}) { "fresh" })
    }

    @Test fun connectionTimeoutCancelsOwnerAndPendingRequests() = runTest {
        val manager = manager()
        val first = async { failureCode { manager.withLease("first", epoch + 600_000, {}) { awaitCancellation() } } }
        runCurrent()
        val queued = async { failureCode { manager.withLease("queued", epoch + 600_000, {}) { fail("must not run") } } }
        runCurrent()
        manager.connectionTimedOut()
        assertEquals("SCREEN_CONNECTION_TIMED_OUT", first.await())
        assertEquals("SCREEN_CONNECTION_TIMED_OUT", queued.await())
    }

    @Test fun localAuthorizationIsRecheckedAfterWaiting() = runTest {
        val manager = manager()
        val release = CompletableDeferred<Unit>()
        val first = async { manager.withLease("first", epoch + 60_000, {}) { release.await() } }
        runCurrent()
        var allowed = true
        var performed = false
        var checks = 0
        val queued = async {
            failureCode {
                manager.withLease("queued", epoch + 60_000, {
                    checks++
                    if (!allowed) throw ScreenAuthorizationException("SCREEN_PERMISSION_REQUIRED")
                }) { performed = true }
            }
        }
        runCurrent()
        assertEquals(1, checks)
        allowed = false
        release.complete(Unit); first.await()
        assertEquals("SCREEN_PERMISSION_REQUIRED", queued.await())
        assertEquals(2, checks)
        assertFalse(performed)
    }

    @Test fun revokeDuringRevalidationIsObservedBeforeAction() = runTest {
        val manager = manager()
        var checks = 0
        var performed = false
        assertEquals("SCREEN_CONTROL_REVOKED", failureCode {
            manager.withLease("race", epoch + 60_000, {
                checks++
                if (checks == 2) manager.revokeAll()
            }) { performed = true }
        })
        assertFalse(performed)
    }

    @Test fun wallClockRollbackCannotExtendRequestDeadline() = runTest {
        var wall = epoch
        val manager = manager { wall }
        val first = async { failureCode { manager.withLease("first", epoch + 10_000, {}) { awaitCancellation() } } }
        runCurrent()
        advanceTimeBy(9_000); runCurrent()
        wall -= 60_000
        assertEquals(1_000L, manager.heartbeat("first"))
        advanceTimeBy(1_000); runCurrent()
        assertEquals("SCREEN_REQUEST_EXPIRED", first.await())
    }

    @Test fun forwardWallClockJumpCannotExtendSleepingLease() = runTest {
        var wall = epoch
        val manager = manager { wall }
        val first = async { failureCode { manager.withLease("first", epoch + 600_000, {}) { awaitCancellation() } } }
        runCurrent()
        wall += 30_000
        assertEquals("SCREEN_CONTROL_LEASE_EXPIRED", failureCode { manager.heartbeat("first") })
        manager.cancel("first")
        first.await()
    }

    @Test fun duplicateIdsAndNestedOwnershipAreRejectedWithoutLeakingLock() = runTest {
        val manager = manager()
        val release = CompletableDeferred<Unit>()
        val first = async { manager.withLease("same", epoch + 60_000, {}) { release.await() } }
        runCurrent()
        assertEquals("SCREEN_REQUEST_IN_PROGRESS", failureCode { manager.withLease("same", epoch + 60_000, {}) {} })
        release.complete(Unit); first.await()
        manager.withLease("outer", epoch + 60_000, {}) {
            assertEquals("SCREEN_CONTROL_LEASE_NESTED", failureCode { manager.withLease("inner", epoch + 60_000, {}) {} })
        }
        assertEquals("ok", manager.withLease("after", epoch + 60_000, {}) { "ok" })
    }

    @Test fun emergencyStopDuringAdmissionPreventsLateQueueRegistration() = runTest {
        val manager = manager()
        var performed = false
        assertEquals("SCREEN_CONTROL_REVOKED", failureCode {
            manager.withLease("admitting", epoch + 60_000, { manager.revokeAll() }) { performed = true }
        })
        assertFalse(performed)
    }

    @Test fun callerTimeoutWhileQueuedRemainsCancellationAndRemovesWaiter() = runTest {
        val manager = manager()
        val release = CompletableDeferred<Unit>()
        val first = async { manager.withLease("first", epoch + 60_000, {}) { release.await() } }
        runCurrent()
        var performed = false
        val queued = async {
            try {
                withTimeout(1_000) {
                    manager.withLease("queued", epoch + 60_000, {}) { performed = true }
                }
                false
            } catch (_: TimeoutCancellationException) { true }
        }
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertTrue(queued.await())
        release.complete(Unit); first.await()
        assertFalse(performed)
        assertEquals("fresh", manager.withLease("queued", epoch + 60_000, {}) { "fresh" })
    }

    @Test fun queueIsBoundedAndRejectsOverflowBeforeItsAction() = runTest {
        val manager = manager()
        val first = async { failureCode { manager.withLease("first", epoch + 600_000, {}) { awaitCancellation() } } }
        runCurrent()
        val queued = (1 until ScreenControlLeaseManager.MAX_REQUESTS).map { index ->
            async { failureCode { manager.withLease("queued-$index", epoch + 600_000, {}) { fail("must not run") } } }
                .also { runCurrent() }
        }
        assertEquals("SCREEN_CONTROL_QUEUE_FULL", failureCode {
            manager.withLease("overflow", epoch + 600_000, {}) { fail("must not run") }
        })
        manager.revokeAll()
        assertEquals("SCREEN_CONTROL_REVOKED", first.await())
        assertTrue(queued.awaitAll().all { it == "SCREEN_CONTROL_REVOKED" })
    }

    @Test fun leaseExpiryAfterNativeDispatchReportsUnknownAndNeverRetries() = runTest {
        val manager = manager()
        var effects = 0
        val first = async {
            failureCode {
                manager.withLease("first", epoch + 600_000, {}) { lease ->
                    lease.beforeSystemEffect()
                    effects++
                    awaitCancellation()
                }
            }
        }
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals("SCREEN_ACTION_OUTCOME_UNKNOWN", first.await())
        assertEquals(1, effects)
        assertEquals("fresh", manager.withLease("fresh", epoch + 600_000, {}) { "fresh" })
    }

    @Test fun emergencyStopAfterNativeDispatchNeverClaimsTheActionWasDenied() = runTest {
        val manager = manager()
        val first = async {
            failureCode {
                manager.withLease("first", epoch + 600_000, {}) { lease ->
                    lease.beforeSystemEffect()
                    awaitCancellation()
                }
            }
        }
        runCurrent()
        manager.revokeAll()
        assertEquals("SCREEN_ACTION_OUTCOME_UNKNOWN", first.await())
    }

    @Test fun cancellationAtFinalGuardAfterAnEarlierEffectKeepsOutcomeUnknown() = runTest {
        val manager = manager()
        assertEquals("SCREEN_ACTION_OUTCOME_UNKNOWN", failureCode {
            manager.withLease("request", epoch + 60_000, {}) { lease ->
                lease.beforeSystemEffect()
                manager.cancel("request")
                lease.ensureCurrent()
            }
        })
    }

    @Test fun throwingActionAlwaysReleasesOwnership() = runTest {
        val manager = manager()
        assertEquals("TEST_FAILURE", failureCode {
            manager.withLease("failed", epoch + 60_000, {}) { throw ScreenOperationException("TEST_FAILURE") }
        })
        assertEquals("ok", manager.withLease("next", epoch + 60_000, {}) { "ok" })
    }

    private suspend fun failureCode(action: suspend () -> Unit): String? = try {
        action()
        null
    } catch (failure: ScreenOperationException) { failure.code }
    catch (failure: ScreenAuthorizationException) { failure.code }
}
