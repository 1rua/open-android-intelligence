package com.openandroidintelligence.gateway.auth

import com.openandroidintelligence.gateway.http.GatewayProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GatewaySessionRenewalTest {
    private fun profile() = GatewayProfile("account", "device", "session_old", "https://gateway.example", accessToken = "old")

    @Test fun concurrentRefreshAndLateRejectionShareOneRotation() = runTest {
        val initial = profile()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            calls++
            release.await()
            it.copy(sessionId = "session_new", accessToken = "new")
        }
        val first = async { renewal.refresh(initial) }
        val second = async { renewal.refresh(initial) }
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals("new", first.await().accessToken)
        assertEquals(first.await(), second.await())
        assertEquals(first.await(), renewal.refresh(initial))
        assertEquals(1, calls)
    }

    @Test fun cancellationOfOneWaiterDoesNotCancelSharedRotation() = runTest {
        val release = CompletableDeferred<Unit>()
        val initial = profile()
        var calls = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            calls++
            release.await()
            it.copy(accessToken = "new", sessionId = "session_new")
        }
        val first = async { renewal.refresh(initial) }
        runCurrent()
        first.cancel()
        val second = async { renewal.refresh(initial) }
        release.complete(Unit)
        assertEquals("new", second.await().accessToken)
        assertEquals(1, calls)
    }

    @Test fun concurrentWaitersShareFailureAndOwnerCanRetryTheSameSnapshot() = runTest {
        val initial = profile()
        val release = CompletableDeferred<Unit>()
        val failure = java.io.IOException("response lost")
        var calls = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            calls++
            if (calls == 1) { release.await(); throw failure }
            it.copy(accessToken = "new", sessionId = "session_new")
        }
        val first = async { runCatching { renewal.refresh(initial) } }
        val second = async { runCatching { renewal.refresh(initial) } }
        runCurrent()
        release.complete(Unit)
        assertEquals(failure.message, first.await().exceptionOrNull()?.message)
        assertEquals(failure.message, second.await().exceptionOrNull()?.message)
        assertEquals(1, calls)
        assertEquals(initial, renewal.current)
        assertEquals("new", renewal.refresh(initial).accessToken)
        assertEquals(2, calls)
    }

    @Test fun oldStreamActivityDuringRotationCannotRechargeTheReplacementSession() = runTest {
        val initial = profile()
        val client = Any()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            calls++
            entered.complete(Unit)
            release.await()
            it.copy(accessToken = "new", sessionId = "session_new")
        }
        val recovering = async { renewal.recoverEventSession(initial, client, allowRefresh = true) {} }
        entered.await()
        assertEquals(1, calls)
        assertFalse(renewal.recordEventActivity(initial, client))
        release.complete(Unit)
        assertTrue(recovering.await())
        assertFalse(renewal.recoverEventSession(renewal.current, client, allowRefresh = true) {})
        assertEquals(1, calls)
    }

    @Test fun onlyTheClientThatConsumedTheEpisodeCanRestoreTheSharedBudget() = runTest {
        val recoveringClient = Any()
        val sibling = Any()
        var rotations = 0
        val renewal = GatewaySessionRenewal(profile(), backgroundScope) {
            rotations++
            it.copy(sessionId = "session_$rotations", accessToken = "token_$rotations")
        }
        assertTrue(renewal.recoverEventSession(renewal.current, recoveringClient, allowRefresh = true) {})
        assertTrue("A sibling is healthy, but has not proven the failed client recovered", renewal.recordEventActivity(renewal.current, sibling))
        assertFalse(renewal.recoverEventSession(renewal.current, sibling, allowRefresh = true) {})
        assertTrue(renewal.recordEventActivity(renewal.current, recoveringClient))
        assertTrue(renewal.recoverEventSession(renewal.current, sibling, allowRefresh = true) {})
        assertEquals(2, rotations)
    }

    @Test fun rotationCannotChangeIdentityEndpointOrTrust() = runTest {
        val initial = profile()
        val mutations: List<(GatewayProfile) -> GatewayProfile> = listOf(
            { it.copy(accountId = "other") }, { it.copy(deviceId = "other") },
            { it.copy(gatewayBaseUrl = "https://other.example") },
            { it.copy(pinnedSpkiSha256 = setOf("sha256:" + "0".repeat(64))) },
        )
        for (change in mutations) {
            val renewal = GatewaySessionRenewal(initial, backgroundScope) { change(it) }
            assertNotNull(runCatching { renewal.refresh(initial) }.exceptionOrNull())
            assertEquals(initial, renewal.current)
        }
    }

    @Test fun closingPreventsPendingRotationAndFutureRefresh() = runTest {
        val initial = profile()
        val release = CompletableDeferred<Unit>()
        val renewal = GatewaySessionRenewal(initial, backgroundScope) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
            it.copy(accessToken = "new")
        }
        val waiting = async { runCatching { renewal.refresh(initial) } }
        runCurrent()
        renewal.close()
        release.complete(Unit)
        runCurrent()
        assertTrue(waiting.await().isFailure)
        assertEquals(initial, renewal.current)
        assertNotNull(runCatching { renewal.refresh() }.exceptionOrNull())
    }
}
