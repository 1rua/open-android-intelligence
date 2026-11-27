package com.openandroidintelligence.control

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLocalAuthorizationTest {
    private var now = 1_000L
    private val revision = ControlRevision(1u, 2u, 3u, 4u)
    private val binding = ScreenAuthorizationBinding("account-a", "pairing-a", revision)
    private val target = ScreenTargetIdentity("example.app", "42", 7u)
    private val digest = "sha256:request-parameters"
    private val events = mutableListOf<ScreenSessionEndReason>()
    private val authority = ScreenLocalAuthorization({ now }, events::add)

    private fun open(permissions: Set<ScreenPermission> = ScreenPermission.entries.toSet()): ScreenLocalSession =
        authority.start(binding, permissions, if (ScreenPermission.CONTROL in permissions) target else null, 10_000L)

    private fun confirmation(parameterDigest: String = digest) = UserConfirmation("local-decision", parameterDigest, now, now + 5_000)

    private fun request(
        requestId: String = "request-1",
        suppliedConfirmation: UserConfirmation? = null,
        requestRevision: ControlRevision = revision,
    ) = ControlAuthorizationRequest(
        requestId, ScreenSemanticAction.Back(digest), requestRevision,
        suppliedConfirmation, now + 1_000,
        ScreenControlSession("remote-supplied", target, now, now + 5_000, target.windowGeneration),
    )

    @Test
    fun newProcessHasNoLocalSessionForAnyPermission() {
        ScreenPermission.entries.forEach {
            val error = assertThrows(ScreenAuthorizationException::class.java) { authority.requirePermission(binding, it) }
            assertEquals("SCREEN_SESSION_REQUIRED", error.code)
        }
    }

    @Test
    fun readPermissionDoesNotImplyScreenshotOrControl() {
        open(setOf(ScreenPermission.READ))
        assertEquals(setOf(ScreenPermission.READ), authority.requirePermission(binding, ScreenPermission.READ).permissions)
        listOf(ScreenPermission.CAPTURE, ScreenPermission.CONTROL).forEach {
            val error = assertThrows(ScreenAuthorizationException::class.java) { authority.requirePermission(binding, it) }
            assertEquals("SCREEN_PERMISSION_REQUIRED", error.code)
        }
    }

    @Test
    fun grantCopiesPermissionSetAndRequiresControlTarget() {
        val mutable = mutableSetOf(ScreenPermission.READ)
        authority.start(binding, mutable, ttlMillis = 1_000)
        mutable += ScreenPermission.CAPTURE
        assertFalse(ScreenPermission.CAPTURE in checkNotNull(authority.status(binding).session).permissions)
        assertThrows(IllegalArgumentException::class.java) {
            authority.start(binding, setOf(ScreenPermission.CONTROL), ttlMillis = 1_000)
        }
    }

    @Test
    fun zeroAndOversizedLifetimeAreRejected() {
        listOf(0L, -1L, MAX_SCREEN_SESSION_TTL_MS + 1).forEach { ttl ->
            assertThrows(IllegalArgumentException::class.java) {
                authority.start(binding, setOf(ScreenPermission.READ), ttlMillis = ttl)
            }
        }
    }

    @Test
    fun expiryRevokesSessionAndCallsCleanupOnce() {
        val session = open()
        now = session.expiresAtEpochMs
        assertEquals("SCREEN_SESSION_EXPIRED", authority.status(binding).denialCode)
        assertNull(authority.status(binding).session)
        assertEquals(listOf(ScreenSessionEndReason.EXPIRED), events)
    }

    @Test
    fun wallClockRollbackFailsClosed() {
        open()
        now--
        assertEquals("SCREEN_SESSION_EXPIRED", authority.status(binding).denialCode)
    }

    @Test
    fun accountPairingAndEveryRevisionFieldInvalidateGrant() {
        val changed = listOf(
            binding.copy(accountId = "other-account"),
            binding.copy(pairingId = "other-pairing"),
            binding.copy(revision = revision.copy(pairingGeneration = 2u)),
            binding.copy(revision = revision.copy(connectionGeneration = 3u)),
            binding.copy(revision = revision.copy(authorizationEpoch = 4u)),
            binding.copy(revision = revision.copy(policyRevision = 5u)),
        )
        changed.forEach {
            open()
            assertEquals("SCREEN_SESSION_BINDING_CHANGED", authority.status(it).denialCode)
            assertNull(authority.status(binding).session)
        }
    }

    @Test
    fun replacingOrRevokingSessionClearsLocalConfirmations() {
        val first = open()
        authority.installConfirmation(binding, "request-1", confirmation())
        val second = open()
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(listOf(ScreenSessionEndReason.REPLACED), events)
        assertEquals(ControlAuthorization.Denied(ControlDenialReason.CONFIRMATION_REQUIRED),
            authority.authorizeControl(binding, request(), target))
        authority.revoke(ScreenSessionEndReason.DISCONNECTED)
        authority.revoke(ScreenSessionEndReason.DISCONNECTED)
        assertNull(authority.status(binding).session)
        assertEquals(listOf(ScreenSessionEndReason.REPLACED, ScreenSessionEndReason.DISCONNECTED), events)
    }

    @Test
    fun remoteConfirmationAndSessionCannotAuthorizeControl() {
        open()
        assertEquals(ControlAuthorization.Denied(ControlDenialReason.CONFIRMATION_REQUIRED),
            authority.authorizeControl(binding, request(suppliedConfirmation = confirmation()), target))
    }

    @Test
    fun locallyConfirmedRequestCanBeRecheckedUntilExplicitlyCleared() {
        open()
        authority.installConfirmation(binding, "request-1", confirmation())
        repeat(2) { assertTrue(authority.authorizeControl(binding, request(), target) is ControlAuthorization.Allowed) }
        assertEquals(ControlAuthorization.Denied(ControlDenialReason.CONFIRMATION_REQUIRED),
            authority.authorizeControl(binding, request(requestId = "different-request"), target))
        authority.clearConfirmation("request-1")
        assertEquals(ControlAuthorization.Denied(ControlDenialReason.CONFIRMATION_REQUIRED),
            authority.authorizeControl(binding, request(), target))
    }

    @Test
    fun localConfirmationUsesExistingDigestRevisionAndWindowChecks() {
        open()
        authority.installConfirmation(binding, "request-1", confirmation("wrong-digest"))
        assertEquals(ControlAuthorization.Denied(ControlDenialReason.CONFIRMATION_MISMATCH),
            authority.authorizeControl(binding, request(), target))
        authority.installConfirmation(binding, "request-1", confirmation())
        assertEquals(ControlAuthorization.Denied(ControlDenialReason.PAIRING_GENERATION_STALE),
            authority.authorizeControl(binding, request(requestRevision = revision.copy(pairingGeneration = 9u)), target))
        assertEquals(ControlAuthorization.Denied(ControlDenialReason.WINDOW_CHANGED),
            authority.authorizeControl(binding, request(), target.copy(windowGeneration = 8u)))
    }

    @Test
    fun confirmationCannotOutliveLocalControlSession() {
        open()
        assertThrows(IllegalArgumentException::class.java) {
            authority.installConfirmation(binding, "request-1", UserConfirmation("c", digest, now, now + 20_000))
        }
    }

    @Test
    fun expiryTimerCleansUpWithoutAnotherRemoteRequest() {
        val cleaned = CountDownLatch(1)
        ScreenLocalAuthorization(onSessionEnded = { if (it == ScreenSessionEndReason.EXPIRED) cleaned.countDown() }).use {
            it.start(binding, setOf(ScreenPermission.READ), ttlMillis = 10)
            assertTrue(cleaned.await(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun wallClockAdjustmentsCannotExtendTheOriginalElapsedTimeBudget() {
        val cleaned = CountDownLatch(1)
        ScreenLocalAuthorization(clock = { 1_000L }, onSessionEnded = {
            if (it == ScreenSessionEndReason.EXPIRED) cleaned.countDown()
        }).use {
            it.start(binding, setOf(ScreenPermission.READ), ttlMillis = 10)
            assertTrue(cleaned.await(3, TimeUnit.SECONDS))
            assertNull(it.status(binding).session)
        }
    }
}
