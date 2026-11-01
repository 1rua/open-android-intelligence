package com.openandroidintelligence.device.primitives

import android.content.Intent
import com.openandroidintelligence.capability.*
import com.openandroidintelligence.control.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.LocalGrantContext
import java.util.UUID
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Fake backend/source tests exercise the real Runtime; they are not device acceptance. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoteScreenRuntimeTest {
    private val controlInput = """{"action":"global","globalAction":"back","expectedRevision":"revision"}"""

    @Test fun readyProvidersAreDiscoverableWithoutLegacySessionButInvocationRequiresDriverDecision() = runTest {
        val fixture = Fixture()
        assertNull(fixture.provider(ScreenPermission.READ).availability())
        assertNull(fixture.provider(ScreenPermission.CAPTURE).availability())
        assertNull(fixture.provider(ScreenPermission.CONTROL).availability())
        for (permission in ScreenPermission.entries) {
            assertEquals("SCREEN_REQUEST_AUTHORIZATION_REQUIRED", code { fixture.invoke(permission) })
        }
        assertEquals(0, fixture.backend.reads)
        assertEquals(0, fixture.backend.actions)
        assertEquals(0, fixture.source.captures)
    }

    @Test fun legacySessionAloneCannotAuthorizeAnyRemotePrimitive() = runTest {
        val fixture = Fixture()
        fixture.runtime.authorization.start(fixture.runtime.binding()!!, setOf(ScreenPermission.READ), ttlMillis = 60_000)
        assertEquals("SCREEN_REQUEST_AUTHORIZATION_REQUIRED", code { fixture.invoke(ScreenPermission.READ) })
        assertEquals(0, fixture.backend.reads)
    }

    @Test fun globalReadDecisionWorksWithoutOpeningAnOaiScreenSession() = runTest {
        val fixture = Fixture()
        val result = fixture.call(ScreenPermission.READ, """{"operation":"snapshot","maxNodes":5}""")
        assertEquals("revision", JsonFields.string(result, "revision"))
        assertEquals(5, fixture.backend.query!!.maxNodes)
        assertNull(fixture.runtime.authorization.status(fixture.runtime.binding()!!).session)
    }

    @Test fun eachRequestPermissionIsIndependentAndNeverExpandsToOtherScreenCapabilities() = runTest {
        val fixture = Fixture()
        for (allowed in ScreenPermission.entries) {
            val id = "request-$allowed"
            fixture.grant(id, allowed, controlInput)
            for (denied in ScreenPermission.entries.filter { it != allowed }) {
                assertEquals("SCREEN_PERMISSION_REQUIRED", code { fixture.invoke(denied, controlInput, id) })
            }
            fixture.runtime.finishRequest(id, fixture.runtime.binding()!!)
        }
        assertEquals(0, fixture.backend.reads)
        assertEquals(0, fixture.backend.actions)
        assertEquals(0, fixture.source.captures)
    }

    @Test fun controlCompatibilityApiUsesOnlyControlAndDoesNotRequireLegacyConfirmation() = runTest {
        val fixture = Fixture()
        fixture.runtime.installControlAuthorization("request", Json.sha256(Json.parse(controlInput)), 61_000) {}
        val result = fixture.invoke(ScreenPermission.CONTROL, controlInput)
        assertEquals(true, JsonFields.bool(result, "performed"))
        assertEquals(JsonValue.JNull, JsonFields.field(result, "screen"))
        assertEquals("SCREEN_READ_PERMISSION_REQUIRED", JsonFields.string(result, "stateError"))
        assertTrue(fixture.backend.sawLease)
        assertEquals(1, fixture.backend.actions)
        assertNull(fixture.runtime.authorization.status(fixture.runtime.binding()!!).session)
        fixture.runtime.finishRequest("request", fixture.runtime.binding()!!)
        assertEquals("SCREEN_REQUEST_AUTHORIZATION_REQUIRED", code { fixture.invoke(ScreenPermission.CONTROL, controlInput) })
    }

    @Test fun oldSessionExpiryAndReplacementCannotVetoCurrentGlobalPolicyDecision() = runTest {
        val fixture = Fixture()
        fixture.runtime.authorization.start(fixture.runtime.binding()!!, setOf(ScreenPermission.READ), ttlMillis = 1)
        fixture.grant("request", ScreenPermission.CONTROL, controlInput)
        fixture.now += 2
        assertNull(fixture.runtime.status().local!!.session)
        fixture.runtime.authorization.start(fixture.runtime.binding()!!, setOf(ScreenPermission.CAPTURE), ttlMillis = 60_000)
        assertEquals(true, JsonFields.bool(fixture.invoke(ScreenPermission.CONTROL, controlInput), "performed"))
        assertEquals(0, fixture.released)
    }

    @Test fun switchingChatPreservesOtherAuthenticatedConnectionRequestsWithoutNewOaiSession() = runTest {
        val fixture = Fixture()
        fixture.grant("old", ScreenPermission.READ)
        fixture.runtime.connect("other-account", "other-core-pairing", 2) { fixture.revision }
        fixture.runtime.connectionChanged(true)
        assertEquals("revision", JsonFields.string(fixture.invoke(ScreenPermission.READ, id = "old"), "revision"))
        fixture.grant("new", ScreenPermission.READ)
        val result = fixture.invoke(ScreenPermission.READ, id = "new", accountId = "other-account", pairingId = "other-core-pairing")
        assertEquals("revision", JsonFields.string(result, "revision"))
        assertNull(fixture.runtime.authorization.status(fixture.runtime.binding()!!).session)
        assertEquals(true, JsonFields.bool(fixture.call(ScreenPermission.CONTROL, controlInput,
            accountId = "other-account", pairingId = "other-core-pairing"), "performed"))
    }

    @Test fun identityAndParametersAreCheckedAgainstTheTrustedRequestDecision() = runTest {
        val fixture = Fixture()
        fixture.grant("request", ScreenPermission.CONTROL, controlInput)
        assertEquals("SCREEN_SESSION_BINDING_CHANGED", code { fixture.invoke(ScreenPermission.CONTROL, controlInput, accountId = "other") })
        assertEquals("SCREEN_SESSION_BINDING_CHANGED", code { fixture.invoke(ScreenPermission.CONTROL, controlInput, pairingId = "other") })
        assertEquals("SCREEN_CONFIRMATION_MISMATCH", code {
            fixture.invoke(ScreenPermission.CONTROL, controlInput.replace("back", "home"))
        })
        assertEquals(0, fixture.backend.actions)
    }

    @Test fun currentGlobalCallbackIsCheckedBeforeReadAndBeforeReturningData() = runTest {
        val fixture = Fixture()
        var allowed = true
        fixture.grant("request", ScreenPermission.READ) {
            if (!allowed) throw ScreenAuthorizationException("LOCAL_AUTHORIZATION_CHANGED")
        }
        fixture.backend.onRead = { allowed = false }
        assertEquals("LOCAL_AUTHORIZATION_CHANGED", code { fixture.invoke(ScreenPermission.READ) })
        assertEquals(1, fixture.backend.reads)
    }

    @Test fun policyRevisionExpiryAndDisconnectPreventExecutingOldRequest() = runTest {
        val fixture = Fixture()
        fixture.grant("request", ScreenPermission.CONTROL, controlInput)
        fixture.revision++
        assertEquals("SCREEN_SESSION_BINDING_CHANGED", code { fixture.invoke(ScreenPermission.CONTROL, controlInput) })
        fixture.runtime.finishRequest("request", fixture.runtime.binding()!!)
        fixture.grant("expired", ScreenPermission.CONTROL, controlInput, expiresAt = fixture.now + 1)
        fixture.now++
        assertEquals("SCREEN_REQUEST_EXPIRED", code { fixture.invoke(ScreenPermission.CONTROL, controlInput, "expired") })
        fixture.runtime.connectionChanged(false)
        assertEquals("SCREEN_CONNECTION_REQUIRED", fixture.provider(ScreenPermission.CONTROL).availability())
        assertEquals(0, fixture.backend.actions)
    }

    @Test fun globalPermissionNeverReplacesAccessibilityLockScreenOrMediaProjectionChecks() = runTest {
        val fixture = Fixture()
        fixture.backend.denial = "ACCESSIBILITY_SERVICE_UNAVAILABLE"
        assertEquals("ACCESSIBILITY_SERVICE_UNAVAILABLE", code { fixture.call(ScreenPermission.READ) })
        assertEquals("ACCESSIBILITY_SERVICE_UNAVAILABLE", code { fixture.call(ScreenPermission.CONTROL, controlInput) })
        fixture.backend.denial = "SCREEN_DEVICE_LOCKED"
        assertEquals("SCREEN_DEVICE_LOCKED", code { fixture.call(ScreenPermission.CONTROL, controlInput) })
        fixture.captureError = "SCREEN_DEVICE_LOCKED"
        assertEquals("SCREEN_DEVICE_LOCKED", code { fixture.call(ScreenPermission.CAPTURE) })
        fixture.captureError = null
        fixture.source.active = false
        assertEquals("SCREEN_CAPTURE_AUTHORIZATION_REQUIRED", code { fixture.call(ScreenPermission.CAPTURE) })
        assertEquals(0, fixture.backend.actions)
        assertEquals(0, fixture.source.captures)
    }

    @Test fun screenshotChunksUseFreshCaptureDecisionsAndSurviveFinishingCaptureRequest() = runTest {
        val fixture = Fixture()
        val image = fixture.call(ScreenPermission.CAPTURE)
        val id = JsonFields.string(image, "imageId")!!
        val first = fixture.call(ScreenPermission.CAPTURE, """{"operation":"read","imageId":"$id","offset":0}""")
        assertEquals(32_768, JsonFields.string(first, "data")!!.length)
        assertEquals(false, JsonFields.bool(first, "eof"))
        val second = fixture.call(ScreenPermission.CAPTURE, """{"operation":"read","imageId":"$id","offset":24576}""")
        assertEquals(true, JsonFields.bool(second, "eof"))
        val released = fixture.call(ScreenPermission.CAPTURE, """{"operation":"release","imageId":"$id"}""")
        assertEquals(true, JsonFields.bool(released, "released"))
        assertEquals("SCREEN_IMAGE_UNAVAILABLE", code {
            fixture.call(ScreenPermission.CAPTURE, """{"operation":"read","imageId":"$id","offset":0}""")
        })
        assertEquals(1, fixture.source.captures)
    }

    @Test fun readOnlyDecisionCannotDownloadAnExistingScreenshot() = runTest {
        val fixture = Fixture()
        val id = JsonFields.string(fixture.call(ScreenPermission.CAPTURE), "imageId")!!
        val input = """{"operation":"read","imageId":"$id","offset":0}"""
        fixture.grant("reader", ScreenPermission.READ, input)
        assertEquals("SCREEN_PERMISSION_REQUIRED", code { fixture.invoke(ScreenPermission.CAPTURE, input, "reader") })
    }

    @Test fun connectionSwitchCannotReadOldCaptureHandlesEvenWithNewCapturePermission() = runTest {
        val fixture = Fixture()
        val id = JsonFields.string(fixture.call(ScreenPermission.CAPTURE), "imageId")!!
        fixture.runtime.disconnectConnection(fixture.runtime.binding()!!)
        fixture.runtime.connect("account", "pairing", 2) { fixture.revision }
        fixture.runtime.connectionChanged(true)
        fixture.source.active = true // Simulate an independently granted system projection.
        assertEquals("SCREEN_IMAGE_UNAVAILABLE", code {
            fixture.call(ScreenPermission.CAPTURE, """{"operation":"read","imageId":"$id","offset":0}""")
        })
    }

    @Test fun systemProjectionRevocationClearsCapturedBytesBeforeAnotherRequestCanReadThem() = runTest {
        val fixture = Fixture()
        val id = JsonFields.string(fixture.call(ScreenPermission.CAPTURE), "imageId")!!
        fixture.runtime.captureStopped()
        fixture.source.active = true
        assertEquals("SCREEN_IMAGE_UNAVAILABLE", code {
            fixture.call(ScreenPermission.CAPTURE, """{"operation":"read","imageId":"$id","offset":0}""")
        })
    }

    @Test fun screenshotPolicyRevocationDuringCapturePreventsPublishingAnImage() = runTest {
        val fixture = Fixture()
        var allowed = true
        fixture.source.onCapture = { allowed = false }
        assertEquals("LOCAL_AUTHORIZATION_CHANGED", code {
            fixture.call(ScreenPermission.CAPTURE, revalidate = {
                if (!allowed) throw ScreenAuthorizationException("LOCAL_AUTHORIZATION_CHANGED")
            })
        })
        assertEquals(1, fixture.source.captures)
    }

    @Test fun controlFifoUsesFreshGlobalDecisionsAndReadsDoNotAcquireControlLock() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val blocked = CompletableDeferred<Unit>()
        fixture.backend.actionBlock = { if (fixture.backend.actions == 0) blocked.await() }
        val order = mutableListOf<String>()
        for (id in listOf("first", "second", "third")) fixture.grant(id, ScreenPermission.CONTROL, controlInput)
        val first = async { fixture.invoke(ScreenPermission.CONTROL, controlInput, "first"); order += "first" }
        runCurrent()
        val second = async { fixture.invoke(ScreenPermission.CONTROL, controlInput, "second"); order += "second" }
        runCurrent()
        val third = async { fixture.invoke(ScreenPermission.CONTROL, controlInput, "third"); order += "third" }
        runCurrent()
        assertEquals("revision", JsonFields.string(fixture.call(ScreenPermission.READ), "revision"))
        assertFalse(second.isCompleted)
        blocked.complete(Unit)
        first.await(); second.await(); third.await()
        assertEquals(listOf("first", "second", "third"), order)
        assertEquals(3, fixture.backend.actions)
    }

    @Test fun queuedControlRechecksGlobalPolicyBeforeItsFirstSystemEffect() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val blocked = CompletableDeferred<Unit>()
        fixture.backend.actionBlock = { if (fixture.backend.actions == 0) blocked.await() }
        fixture.grant("first", ScreenPermission.CONTROL, controlInput)
        var allowed = true
        fixture.grant("second", ScreenPermission.CONTROL, controlInput) {
            if (!allowed) throw ScreenAuthorizationException("LOCAL_AUTHORIZATION_CHANGED")
        }
        val first = async { fixture.invoke(ScreenPermission.CONTROL, controlInput, "first") }
        runCurrent()
        val second = async { code { fixture.invoke(ScreenPermission.CONTROL, controlInput, "second") } }
        runCurrent()
        allowed = false
        blocked.complete(Unit); first.await()
        assertEquals("LOCAL_AUTHORIZATION_CHANGED", second.await())
        assertEquals(1, fixture.backend.actions)
    }

    @Test fun finalNativeGuardRejectsRevokedGlobalPolicyWithoutLegacySession() = runTest {
        val fixture = Fixture()
        var allowed = true
        fixture.backend.onDispatch = { allowed = false }
        assertEquals("LOCAL_AUTHORIZATION_CHANGED", code {
            fixture.call(ScreenPermission.CONTROL, controlInput, revalidate = {
                if (!allowed) throw ScreenAuthorizationException("LOCAL_AUTHORIZATION_CHANGED")
            })
        })
        assertEquals(0, fixture.backend.actions)
    }

    @Test fun controlAlreadyDispatchedNeverReportsDenialWhenResultAuthorizationChanges() = runTest {
        val fixture = Fixture()
        var allowed = true
        fixture.backend.onResult = { allowed = false }
        assertEquals("SCREEN_ACTION_OUTCOME_UNKNOWN", code {
            fixture.call(ScreenPermission.CONTROL, controlInput, revalidate = {
                if (!allowed) throw ScreenAuthorizationException("LOCAL_AUTHORIZATION_CHANGED")
            })
        })
        assertEquals(1, fixture.backend.actions)
    }

    @Test fun emergencyStopAndConnectionLossCancelOwnerAndAllPendingControlRequests() = runTest {
        for (connectionLoss in listOf(false, true)) {
            val fixture = Fixture(testScheduler.timeSource)
            fixture.backend.actionBlock = { awaitCancellation() }
            fixture.grant("first", ScreenPermission.CONTROL, controlInput)
            fixture.grant("second", ScreenPermission.CONTROL, controlInput)
            val first = async { code { fixture.invoke(ScreenPermission.CONTROL, controlInput, "first") } }
            runCurrent()
            val second = async { code { fixture.invoke(ScreenPermission.CONTROL, controlInput, "second") } }
            runCurrent()
            if (connectionLoss) fixture.runtime.connectionChanged(false) else fixture.runtime.revoke()
            val expected = if (connectionLoss) "SCREEN_CONNECTION_TIMED_OUT" else "SCREEN_CONTROL_REVOKED"
            assertEquals(expected, first.await()); assertEquals(expected, second.await())
            assertEquals(0, fixture.backend.actions)
        }
    }

    @Test fun finishedQueuedControlAndExpiredControlNeverExecute() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val blocked = CompletableDeferred<Unit>()
        fixture.backend.actionBlock = { if (fixture.backend.actions == 0) blocked.await() }
        fixture.grant("first", ScreenPermission.CONTROL, controlInput)
        fixture.grant("second", ScreenPermission.CONTROL, controlInput)
        fixture.grant("expired", ScreenPermission.CONTROL, controlInput, expiresAt = 1_100)
        val first = async { fixture.invoke(ScreenPermission.CONTROL, controlInput, "first") }
        runCurrent()
        val second = async { code { fixture.invoke(ScreenPermission.CONTROL, controlInput, "second") } }
        runCurrent()
        val expired = async { code { fixture.invoke(ScreenPermission.CONTROL, controlInput, "expired") } }
        runCurrent()
        fixture.runtime.finishRequest("second", fixture.runtime.binding()!!)
        assertEquals("SCREEN_CONTROL_CANCELLED", second.await())
        advanceTimeBy(100); fixture.now += 100; runCurrent()
        assertEquals("SCREEN_REQUEST_EXPIRED", expired.await())
        blocked.complete(Unit); first.await()
        assertEquals(1, fixture.backend.actions)
    }

    @Test fun explicitHeartbeatRenewsOnlyExistingLeaseAndDoesNotRunAutomatically() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        fixture.backend.actionBlock = { awaitCancellation() }
        fixture.grant("request", ScreenPermission.CONTROL, controlInput, expiresAt = 601_000)
        val request = async { code { fixture.invoke(ScreenPermission.CONTROL, controlInput) } }
        runCurrent()
        advanceTimeBy(29_000); fixture.now += 29_000; runCurrent()
        assertEquals(30_000L, fixture.runtime.heartbeatControl("request", fixture.runtime.binding()!!))
        advanceTimeBy(2_000); fixture.now += 2_000; runCurrent()
        assertFalse(request.isCompleted)
        fixture.runtime.finishRequest("request", fixture.runtime.binding()!!)
        assertEquals("SCREEN_CONTROL_CANCELLED", request.await())
        assertEquals("SCREEN_CONTROL_LEASE_INACTIVE", code { fixture.runtime.heartbeatControl("request", fixture.runtime.binding()!!) })
    }

    @Test fun revocationDuringPolicyAdmissionCannotPublishALateAllowedRequest() = runTest {
        val fixture = Fixture()
        assertEquals("SCREEN_REQUEST_AUTHORIZATION_REVOKED", code {
            fixture.grant("request", ScreenPermission.READ) { fixture.runtime.revoke() }
        })
        assertEquals("SCREEN_REQUEST_AUTHORIZATION_REQUIRED", code { fixture.invoke(ScreenPermission.READ) })
        assertEquals(0, fixture.backend.reads)
    }

    @Test fun cancellingBlockedCaptureNeverProducesASecondCapture() = runTest {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        fixture.source.onCaptureSuspend = { entered.complete(Unit); awaitCancellation() }
        fixture.grant("request", ScreenPermission.CAPTURE)
        val request = launch { fixture.invoke(ScreenPermission.CAPTURE) }
        entered.await()
        fixture.runtime.cancelRequest("request", fixture.runtime.binding()!!)
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(1, fixture.source.captures)
    }

    @Test fun explicitBackgroundBindingDoesNotDependOnSelectedChatAccount() = runTest {
        val fixture = Fixture()
        val foreground = fixture.runtime.binding()!!
        val background = fixture.runtime.registerConnection("background", "core-b-pairing", 3) { 7L }
        fixture.grant("background-read", ScreenPermission.READ, binding = background)
        fixture.runtime.selectConnection(foreground)
        assertEquals("revision", JsonFields.string(fixture.invoke(ScreenPermission.READ, id = "background-read",
            accountId = "background", pairingId = "core-b-pairing"), "revision"))
        assertEquals(foreground, fixture.runtime.binding())
        assertNull(fixture.runtime.authorization.status(foreground).session)
    }

    @Test fun multipleConnectionsRejectImplicitBindingInsteadOfGuessingSelectedChat() = runTest {
        val fixture = Fixture()
        fixture.runtime.registerConnection("background", "core-b-pairing", 3) { 1L }
        assertEquals("SCREEN_CONNECTION_BINDING_REQUIRED", code {
            fixture.runtime.installRequestAuthorization("ambiguous", setOf(ScreenPermission.READ), 61_000,
                Json.sha256(Json.parse("{}"))) {}
        })
        assertEquals(0, fixture.backend.reads)
    }

    @Test fun scopedDisconnectCancelsOnlyItsOwnerAndLetsOtherConnectionLeaveFifoQueue() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val foreground = fixture.runtime.binding()!!
        val background = fixture.runtime.registerConnection("background", "core-b-pairing", 3) { 1L }
        var starts = 0
        fixture.backend.actionBlock = { if (++starts == 1) awaitCancellation() }
        fixture.grant("foreground", ScreenPermission.CONTROL, controlInput, binding = foreground)
        fixture.grant("background", ScreenPermission.CONTROL, controlInput, binding = background)
        val first = async { code { fixture.invoke(ScreenPermission.CONTROL, controlInput, "foreground") } }
        runCurrent()
        val queued = async { fixture.invoke(ScreenPermission.CONTROL, controlInput, "background", "background", "core-b-pairing") }
        runCurrent()
        assertFalse(queued.isCompleted)
        fixture.runtime.disconnectConnection(foreground)
        assertEquals("SCREEN_CONNECTION_TIMED_OUT", first.await())
        assertEquals(true, JsonFields.bool(queued.await(), "performed"))
        assertEquals(1, fixture.backend.actions)
        assertTrue(fixture.source.active)
        assertEquals(0, fixture.released)
    }

    @Test fun selectedConnectionDisconnectCannotCancelBackgroundControlOrItsQueuedRead() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val foreground = fixture.runtime.binding()!!
        val background = fixture.runtime.registerConnection("background", "core-b-pairing", 3) { 1L }
        val release = CompletableDeferred<Unit>()
        fixture.backend.actionBlock = { release.await() }
        fixture.grant("background-control", ScreenPermission.CONTROL, controlInput, binding = background)
        val control = async { fixture.invoke(ScreenPermission.CONTROL, controlInput, "background-control", "background", "core-b-pairing") }
        runCurrent()
        fixture.runtime.selectConnection(foreground)
        fixture.runtime.disconnect()
        fixture.grant("background-read", ScreenPermission.READ, binding = background)
        assertEquals("revision", JsonFields.string(fixture.invoke(ScreenPermission.READ, id = "background-read",
            accountId = "background", pairingId = "core-b-pairing"), "revision"))
        assertFalse(control.isCompleted)
        release.complete(Unit)
        assertEquals(true, JsonFields.bool(control.await(), "performed"))
        assertNull(fixture.runtime.binding())
        assertNull(fixture.provider(ScreenPermission.CONTROL).availability())
    }

    @Test fun sameAccountAndPairingOnDifferentCoresHaveIndependentOpaqueBindingsAndPixels() = runTest {
        val fixture = Fixture()
        val coreA = fixture.runtime.binding()!!
        val coreB = fixture.runtime.registerConnection("account", "pairing", 1) { fixture.revision }
        assertNotEquals(coreA.revision.connectionGeneration, coreB.revision.connectionGeneration)
        fixture.grant("capture-a", ScreenPermission.CAPTURE, binding = coreA)
        val imageA = JsonFields.string(fixture.invoke(ScreenPermission.CAPTURE, id = "capture-a"), "imageId")!!
        fixture.runtime.finishRequest("capture-a", coreA)
        val readA = """{"operation":"read","imageId":"$imageA","offset":0}"""
        fixture.grant("read-a-as-b", ScreenPermission.CAPTURE, readA, binding = coreB)
        assertEquals("SCREEN_IMAGE_UNAVAILABLE", code { fixture.invoke(ScreenPermission.CAPTURE, readA, "read-a-as-b") })
        fixture.runtime.finishRequest("read-a-as-b", coreB)
        fixture.grant("capture-b", ScreenPermission.CAPTURE, binding = coreB)
        val imageB = JsonFields.string(fixture.invoke(ScreenPermission.CAPTURE, id = "capture-b"), "imageId")!!
        fixture.runtime.finishRequest("capture-b", coreB)
        fixture.runtime.disconnectConnection(coreA)
        val readB = """{"operation":"read","imageId":"$imageB","offset":0}"""
        fixture.grant("read-b", ScreenPermission.CAPTURE, readB, binding = coreB)
        assertEquals(32_768, JsonFields.string(fixture.invoke(ScreenPermission.CAPTURE, readB, "read-b"), "data")!!.length)
        assertEquals(0, fixture.released)
    }

    @Test fun policyRevisionChangesInvalidateOldRequestsButSameConnectionCanInstallFreshDecision() = runTest {
        val fixture = Fixture()
        val registered = fixture.runtime.binding()!!
        fixture.grant("old", ScreenPermission.READ, binding = registered)
        fixture.revision++
        assertEquals("SCREEN_SESSION_BINDING_CHANGED", code { fixture.invoke(ScreenPermission.READ, id = "old") })
        fixture.grant("fresh", ScreenPermission.READ, binding = registered)
        assertEquals("revision", JsonFields.string(fixture.invoke(ScreenPermission.READ, id = "fresh"), "revision"))
        fixture.runtime.disconnectConnection(registered)
        assertEquals("SCREEN_CONNECTION_REQUIRED", fixture.provider(ScreenPermission.READ).availability())
    }

    @Test fun globalEmergencyStopAndDisconnectAllInvalidateEveryRegisteredConnectionRequest() = runTest {
        for (logout in listOf(false, true)) {
            val fixture = Fixture(testScheduler.timeSource)
            val foreground = fixture.runtime.binding()!!
            val background = fixture.runtime.registerConnection("background", "core-b-pairing", 3) { 1L }
            fixture.backend.actionBlock = { awaitCancellation() }
            fixture.grant("first", ScreenPermission.CONTROL, controlInput, binding = foreground)
            fixture.grant("second", ScreenPermission.CONTROL, controlInput, binding = background)
            val first = async { code { fixture.invoke(ScreenPermission.CONTROL, controlInput, "first") } }
            runCurrent()
            val second = async { code { fixture.invoke(ScreenPermission.CONTROL, controlInput, "second", "background", "core-b-pairing") } }
            runCurrent()
            if (logout) fixture.runtime.disconnectAll() else fixture.runtime.revoke()
            val expected = if (logout) "SCREEN_CONNECTION_TIMED_OUT" else "SCREEN_CONTROL_REVOKED"
            assertEquals(expected, first.await()); assertEquals(expected, second.await())
            assertEquals(0, fixture.backend.actions)
            if (logout) assertEquals("SCREEN_CONNECTION_REQUIRED", fixture.provider(ScreenPermission.READ).availability())
        }
    }

    @Test fun disconnectingOneConnectionDuringAdmissionDoesNotInvalidateOthersDecision() = runTest {
        val fixture = Fixture()
        val foreground = fixture.runtime.binding()!!
        val background = fixture.runtime.registerConnection("background", "core-b-pairing", 3) { 1L }
        assertEquals("SCREEN_SESSION_BINDING_CHANGED", code {
            fixture.grant("revoked", ScreenPermission.READ, binding = foreground) { fixture.runtime.disconnectConnection(foreground) }
        })
        fixture.grant("valid", ScreenPermission.READ, binding = background)
        assertEquals("revision", JsonFields.string(fixture.invoke(ScreenPermission.READ, id = "valid",
            accountId = "background", pairingId = "core-b-pairing"), "revision"))
    }

    @Test fun cancellingOneRequestCannotClearAnotherConnectionsCapture() = runTest {
        val fixture = Fixture()
        val foreground = fixture.runtime.binding()!!
        val background = fixture.runtime.registerConnection("background", "core-b-pairing", 3) { 1L }
        fixture.grant("capture-background", ScreenPermission.CAPTURE, binding = background)
        val image = JsonFields.string(fixture.invoke(ScreenPermission.CAPTURE, id = "capture-background",
            accountId = "background", pairingId = "core-b-pairing"), "imageId")!!
        fixture.runtime.finishRequest("capture-background", background)
        fixture.grant("cancel-foreground", ScreenPermission.READ, binding = foreground)
        fixture.runtime.cancelRequest("cancel-foreground", foreground)
        val input = """{"operation":"read","imageId":"$image","offset":0}"""
        fixture.grant("read-background", ScreenPermission.CAPTURE, input, binding = background)
        assertEquals(32_768, JsonFields.string(fixture.invoke(ScreenPermission.CAPTURE, input, "read-background",
            "background", "core-b-pairing"), "data")!!.length)
    }

    @Test fun sameRawIdFromAnotherCoreIsRejectedAtomicallyWithoutReplacingOriginalApproval() = runTest {
        for (sameIdentity in listOf(false, true)) {
            val fixture = Fixture()
            val coreA = fixture.runtime.binding()!!
            val coreB = fixture.runtime.registerConnection(if (sameIdentity) "account" else "other",
                if (sameIdentity) "pairing" else "other-pairing", 1) { fixture.revision }
            fixture.grant("shared-id", ScreenPermission.READ, binding = coreA)
            assertEquals("SCREEN_REQUEST_ID_COLLISION", code {
                fixture.grant("shared-id", ScreenPermission.READ, binding = coreB)
            })
            fixture.runtime.finishRequest("shared-id", coreB)
            fixture.runtime.cancelRequest("shared-id", coreB)
            assertEquals("revision", JsonFields.string(fixture.invoke(ScreenPermission.READ, id = "shared-id"), "revision"))
            fixture.runtime.finishRequest("shared-id", coreA)
            fixture.grant("shared-id", ScreenPermission.READ, binding = coreB)
            assertEquals("revision", JsonFields.string(fixture.invoke(ScreenPermission.READ, id = "shared-id",
                accountId = coreB.accountId, pairingId = coreB.pairingId), "revision"))
        }
    }

    @Test fun lateOldOwnerCleanupCannotCancelNewOwnersSameRawIdOrRenewItsLease() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val coreA = fixture.runtime.binding()!!
        val coreB = fixture.runtime.registerConnection("account", "pairing", 1) { fixture.revision }
        fixture.grant("reused-id", ScreenPermission.READ, binding = coreA)
        fixture.invoke(ScreenPermission.READ, id = "reused-id")
        fixture.runtime.finishRequest("reused-id", coreA)
        fixture.runtime.disconnectConnection(coreA)
        fixture.backend.actionBlock = { awaitCancellation() }
        fixture.grant("reused-id", ScreenPermission.CONTROL, controlInput, binding = coreB)
        val executing = async { code { fixture.invoke(ScreenPermission.CONTROL, controlInput, "reused-id") } }
        runCurrent()
        fixture.runtime.finishRequest("reused-id", coreA)
        fixture.runtime.cancelRequest("reused-id", coreA)
        assertEquals("SCREEN_CONTROL_LEASE_INACTIVE", code { fixture.runtime.heartbeatControl("reused-id", coreA) })
        // Even copying the current generation cannot override a mismatched owner tuple.
        fixture.runtime.cancelRequest("reused-id", coreB.copy(accountId = "wrong-account"))
        assertFalse(executing.isCompleted)
        fixture.runtime.finishRequest("reused-id", coreB)
        assertEquals("SCREEN_CONTROL_CANCELLED", executing.await())
        assertEquals(0, fixture.backend.actions)
    }

    @Test fun rawLifecycleEntryPointsAlwaysRejectInsteadOfGuessingRequestOwner() = runTest {
        val fixture = Fixture()
        fixture.grant("request", ScreenPermission.READ)
        assertEquals("SCREEN_CONNECTION_BINDING_REQUIRED", code { fixture.runtime.finishRequest("request") })
        assertEquals("SCREEN_CONNECTION_BINDING_REQUIRED", code { fixture.runtime.cancelRequest("request") })
        assertEquals("SCREEN_CONNECTION_BINDING_REQUIRED", code { fixture.runtime.heartbeatControl("request") })
        assertEquals("revision", JsonFields.string(fixture.invoke(ScreenPermission.READ), "revision"))
    }

    @Test fun rawIdReservationSurvivesCancellationUntilTheOldExecutionActuallyExits() = runTest {
        val fixture = Fixture(testScheduler.timeSource)
        val coreA = fixture.runtime.binding()!!
        val coreB = fixture.runtime.registerConnection("other", "core-b", 1) { fixture.revision }
        val cleanup = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        fixture.backend.actionBlock = {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); cleanup.await() } }
        }
        fixture.grant("same", ScreenPermission.CONTROL, controlInput, binding = coreA)
        val executing = async { code { fixture.invoke(ScreenPermission.CONTROL, controlInput, "same") } }
        runCurrent()
        fixture.runtime.finishRequest("same", coreA)
        runCurrent()
        cleanupEntered.await()
        assertEquals("SCREEN_REQUEST_ID_COLLISION", code { fixture.grant("same", ScreenPermission.READ, binding = coreB) })
        cleanup.complete(Unit)
        assertEquals("SCREEN_CONTROL_CANCELLED", executing.await())
        fixture.grant("same", ScreenPermission.READ, binding = coreB)
        assertEquals("revision", JsonFields.string(fixture.invoke(ScreenPermission.READ, id = "same",
            accountId = "other", pairingId = "core-b"), "revision"))
    }

    @Test fun simultaneousCrossCoreAdmissionHasOneOwnerAndOneTypedCollision() = runTest {
        val fixture = Fixture()
        val coreA = fixture.runtime.binding()!!
        val coreB = fixture.runtime.registerConnection("account", "pairing", 1) { fixture.revision }
        val results = listOf(coreA, coreB).map { owner ->
            async(Dispatchers.Default) { code { fixture.grant("concurrent", ScreenPermission.READ, binding = owner) } }
        }.awaitAll()
        assertEquals(1, results.count { it == null })
        assertEquals(1, results.count { it == "SCREEN_REQUEST_ID_COLLISION" })
        assertEquals("revision", JsonFields.string(fixture.invoke(ScreenPermission.READ, id = "concurrent"), "revision"))
    }

    private suspend fun code(action: suspend () -> Unit): String? = try {
        action(); null
    } catch (failure: ScreenAuthorizationException) { failure.code }
    catch (failure: ScreenOperationException) { failure.code }

    private class Fixture(timeSource: TimeSource = TimeSource.Monotonic) {
        var now = 1_000L
        var revision = 1L
        var released = 0
        var captureError: String? = null
        val backend = FakeBackend()
        val source = FakeCapture()
        val runtime = RemoteScreenRuntime(backend, source, stopProjection = { released++; source.release() },
            captureReadiness = { captureError }, clock = { now },
            controlLeases = ScreenControlLeaseManager({ now }, timeSource))
        init {
            runtime.connect("account", "pairing", 1) { revision }
            runtime.connectionChanged(true)
            released = 0
            source.active = true
        }
        fun provider(permission: ScreenPermission) = runtime.providers.single {
            it.primitiveId == "kernel.screen.${permission.name.lowercase()}"
        }
        fun grant(id: String, permission: ScreenPermission, json: String = "{}", expiresAt: Long = now + 60_000,
            binding: ScreenAuthorizationBinding = runtime.binding()!!,
            revalidate: () -> Unit = {}) = runtime.installRequestAuthorization(id, setOf(permission), expiresAt,
                Json.sha256(Json.parse(json)), binding, revalidate)
        suspend fun invoke(permission: ScreenPermission, json: String = "{}", id: String = "request",
            accountId: String = "account", pairingId: String = "pairing"): JsonValue.JObject = supervisorScope {
            async {
                JsonFields.obj(Json.parse(provider(permission).invoke(
                    LocalGrantContext("verified.plugin", accountId, pairingId, id), json.encodeToByteArray()).decodeToString()))!!
            }.await()
        }
        suspend fun call(permission: ScreenPermission, json: String = "{}", id: String = UUID.randomUUID().toString(),
            accountId: String = "account", pairingId: String = "pairing", revalidate: () -> Unit = {}): JsonValue.JObject {
            val binding = runtime.binding()!!
            grant(id, permission, json, binding = binding, revalidate = revalidate)
            return try { invoke(permission, json, id, accountId, pairingId) }
            finally { runtime.finishRequest(id, binding) }
        }
    }

    private class FakeBackend : ScreenBackend {
        var reads = 0; var actions = 0; var query: ScreenReadQuery? = null
        var onRead: () -> Unit = {}; var onDispatch: () -> Unit = {}; var onResult: () -> Unit = {}
        var actionBlock: suspend () -> Unit = {}
        var denial: String? = null
        var sawLease = false
        override fun availability(): String? = denial
        override fun read(query: ScreenReadQuery): ScreenSnapshot {
            reads++; this.query = query; onRead()
            return ScreenSnapshot("revision", "hash", null, ScreenDisplay(100, 200, 0), "com.example.app", 1,
                listOf(ScreenWindow(1, 1, 1, true, true, ScreenBounds(0, 0, 100, 200))), emptyList(), false)
        }
        override suspend fun execute(expectedRevision: String, action: ScreenAction, beforeDispatch: () -> Unit): ScreenActionResult {
            sawLease = currentCoroutineContext()[ScreenControlLease] != null
            actionBlock()
            onDispatch(); beforeDispatch()
            currentCoroutineContext()[ScreenControlLease]?.beforeSystemEffect()
            actions++
            val result = ScreenActionResult(true, read(ScreenReadQuery()))
            onResult()
            return result
        }
    }

    private class FakeCapture : ScreenCaptureSource {
        var active = true; var captures = 0
        var onCapture: () -> Unit = {}
        var onCaptureSuspend: (suspend () -> Unit)? = null
        override val isAvailable get() = active
        override fun createAuthorizationIntent(): Intent? = null
        override fun onAuthorizationResult(resultCode: Int, resultData: Intent?): Boolean = false
        override suspend fun capture(): ScreenCapture {
            captures++; onCapture(); onCaptureSuspend?.invoke()
            return ScreenCapture.copyOf(ByteArray(30_000) { 42 }, 100, 200)
        }
        override fun release() { active = false }
    }
}
