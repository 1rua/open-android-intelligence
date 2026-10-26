package com.openandroidintelligence.device.primitives

import android.content.Intent
import com.openandroidintelligence.capability.*
import com.openandroidintelligence.control.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.LocalGrantContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemoteScreenRuntimeTest {
    private val context = LocalGrantContext("verified.plugin", "account", "pairing", "request")

    @Test fun default_denial_and_permission_isolation_never_read_or_operate_the_device() = runBlocking {
        val fixture = Fixture()
        assertEquals("SCREEN_SESSION_REQUIRED", fixture.provider("read").availability())
        denied { fixture.invoke("read") }
        fixture.start(setOf(ScreenPermission.CAPTURE))
        denied { fixture.invoke("read") }
        denied { fixture.invoke("control", """{"action":"global","globalAction":"back","expectedRevision":"revision"}""") }
        assertEquals(0, fixture.backend.reads)
        assertEquals(0, fixture.backend.actions)
    }

    @Test fun read_rechecks_authorization_and_keeps_non_screen_pairing_grants_outside_the_runtime() = runBlocking {
        val fixture = Fixture()
        fixture.start(setOf(ScreenPermission.READ))
        val result = fixture.invoke("read", """{"operation":"snapshot","maxNodes":5}""")
        assertEquals("revision", JsonFields.string(result, "revision"))
        assertEquals(5, fixture.backend.query!!.maxNodes)
        fixture.backend.onRead = { fixture.revision++ }
        denied { fixture.invoke("read") }
        assertEquals(1, fixture.released)
    }

    @Test fun account_pairing_revision_expiry_and_connection_changes_invalidate_the_local_session() = runBlocking {
        val fixture = Fixture()
        fixture.start(setOf(ScreenPermission.READ))
        denied { fixture.invoke("read", context = context.copy(accountId = "other")) }
        denied { fixture.invoke("read", context = context.copy(pairingId = "other")) }
        fixture.now = 2_001
        denied { fixture.invoke("read") }
        fixture.now = 1_000
        fixture.start(setOf(ScreenPermission.READ))
        fixture.runtime.connectionChanged(false)
        fixture.runtime.connectionChanged(true)
        denied { fixture.invoke("read") }
        fixture.start(setOf(ScreenPermission.READ))
        fixture.runtime.disconnect()
        denied { fixture.invoke("read") }
    }

    @Test fun control_uses_only_a_real_local_confirmation_and_returns_state_only_with_read_permission() = runBlocking {
        val fixture = Fixture()
        fixture.start(setOf(ScreenPermission.CONTROL))
        val input = """{"action":"global","globalAction":"back","expectedRevision":"revision"}"""
        denied { fixture.invoke("control", input) }
        fixture.runtime.installConfirmation("request", Json.sha256(Json.parse(input)), 1_900)
        val result = fixture.invoke("control", input)
        assertEquals(true, JsonFields.bool(result, "performed"))
        assertEquals(JsonValue.JNull, JsonFields.field(result, "screen"))
        assertEquals("SCREEN_READ_PERMISSION_REQUIRED", JsonFields.string(result, "stateError"))
        assertEquals(1, fixture.backend.actions)
        fixture.runtime.finishRequest("request")
        denied { fixture.invoke("control", input) }
        assertEquals(1, fixture.backend.actions)
    }

    @Test fun changing_confirmed_parameters_and_revoking_before_dispatch_never_execute_an_action() = runBlocking {
        val fixture = Fixture()
        fixture.start(setOf(ScreenPermission.CONTROL, ScreenPermission.READ))
        val input = """{"action":"click","nodeId":"1/0","expectedRevision":"revision"}"""
        fixture.runtime.installConfirmation("request", Json.sha256(Json.parse(input)), 1_900)
        denied { fixture.invoke("control", input.replace("1/0", "1/1")) }
        fixture.backend.onDispatch = { fixture.revision++ }
        denied { fixture.invoke("control", input) }
        assertEquals(0, fixture.backend.actions)
    }

    @Test fun screenshot_chunks_are_bounded_owner_bound_and_released_with_the_session() = runBlocking {
        val fixture = Fixture()
        fixture.start(setOf(ScreenPermission.CAPTURE))
        val image = fixture.invoke("capture")
        val id = JsonFields.string(image, "imageId")!!
        assertEquals(ScreenImageStore.CHUNK_BYTES, JsonFields.int(image, "chunkSize"))
        val first = fixture.invoke("capture", """{"operation":"read","imageId":"$id","offset":0}""")
        assertEquals(32_768, JsonFields.string(first, "data")!!.length)
        assertEquals(false, JsonFields.bool(first, "eof"))
        val second = fixture.invoke("capture", """{"operation":"read","imageId":"$id","offset":24576}""")
        assertEquals(true, JsonFields.bool(second, "eof"))
        fixture.runtime.revoke()
        fixture.start(setOf(ScreenPermission.CAPTURE))
        try { fixture.invoke("capture", """{"operation":"read","imageId":"$id","offset":0}"""); fail() }
        catch (expected: ScreenOperationException) { assertEquals("SCREEN_IMAGE_UNAVAILABLE", expected.code) }
        assertTrue(fixture.released >= 1)
    }

    @Test fun a_capture_revoked_while_grabbing_a_frame_never_publishes_pixels() = runBlocking {
        val fixture = Fixture()
        fixture.start(setOf(ScreenPermission.CAPTURE))
        fixture.source.onCapture = { fixture.revision++ }
        denied { fixture.invoke("capture") }
    }

    @Test fun stopping_projection_clears_its_images_without_inheriting_or_revoking_read_permission() = runBlocking {
        val fixture = Fixture()
        fixture.start(setOf(ScreenPermission.READ, ScreenPermission.CAPTURE))
        val image = fixture.invoke("capture")
        val id = JsonFields.string(image, "imageId")!!
        fixture.runtime.captureStopped()
        assertEquals("SCREEN_CAPTURE_AUTHORIZATION_REQUIRED", fixture.runtime.status().captureError)
        assertNull(fixture.runtime.status().capabilityErrors[ScreenPermission.READ])
        assertEquals("revision", JsonFields.string(fixture.invoke("read"), "revision"))
        fixture.source.active = true // A fresh, explicit system authorization in the same valid local lease.
        try { fixture.invoke("capture", """{"operation":"read","imageId":"$id","offset":0}"""); fail() }
        catch (expected: ScreenOperationException) { assertEquals("SCREEN_IMAGE_UNAVAILABLE", expected.code) }
    }

    @Test fun revocation_after_dispatch_never_describes_an_executed_action_as_denied() = runBlocking {
        val fixture = Fixture()
        fixture.start(setOf(ScreenPermission.CONTROL, ScreenPermission.READ))
        val input = """{"action":"global","globalAction":"back","expectedRevision":"revision"}"""
        fixture.runtime.installConfirmation("request", Json.sha256(Json.parse(input)), 1_900)
        fixture.backend.onResult = { fixture.revision++ }
        val error = runCatching { fixture.invoke("control", input) }.exceptionOrNull()
        assertEquals(1, fixture.backend.actions)
        assertFalse(error is ScreenAuthorizationException)
        assertTrue(error is CancellationException || (error is ScreenOperationException && error.code == "SCREEN_ACTION_OUTCOME_UNKNOWN"))
    }

    @Test fun an_expired_confirmation_is_a_typed_local_denial() {
        val fixture = Fixture()
        fixture.start(setOf(ScreenPermission.CONTROL))
        val error = assertThrows(ScreenAuthorizationException::class.java) {
            fixture.runtime.installConfirmation("request", "digest", fixture.now)
        }
        assertEquals("SCREEN_REQUEST_EXPIRED", error.code)
    }

    @Test fun cancellation_reaches_a_blocked_capture_without_creating_a_second_request() = runBlocking {
        val fixture = Fixture()
        fixture.start(setOf(ScreenPermission.CAPTURE))
        val entered = CompletableDeferred<Unit>()
        fixture.source.onCaptureSuspend = { entered.complete(Unit); awaitCancellation() }
        val request = launch { fixture.invoke("capture") }
        entered.await()
        fixture.runtime.cancelRequest("request")
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(1, fixture.source.captures)
    }

    private suspend fun denied(block: suspend () -> Unit) {
        try { block(); fail("missing local authorization must deny") }
        catch (_: ScreenAuthorizationException) { }
    }

    private inner class Fixture {
        var now = 1_000L
        var revision = 1L
        var released = 0
        val backend = FakeBackend()
        val source = FakeCapture()
        val runtime = RemoteScreenRuntime(backend, source, stopProjection = { released++; source.release() }, clock = { now })
        init { runtime.connect("account", "pairing", 1) { revision }; runtime.connectionChanged(true); released = 0 }
        fun provider(name: String) = runtime.providers.single { it.primitiveId == "kernel.screen.$name" }
        fun start(permissions: Set<ScreenPermission>) {
            source.active = true
            runtime.authorization.start(runtime.binding()!!, permissions,
                if (ScreenPermission.CONTROL in permissions) runtime.localTarget() else null, ttlMillis = 1_000)
        }
        suspend fun invoke(name: String, json: String = "{}", context: LocalGrantContext = this@RemoteScreenRuntimeTest.context): JsonValue.JObject =
            supervisorScope { async {
                JsonFields.obj(Json.parse(provider(name).invoke(context, json.encodeToByteArray()).decodeToString()))!!
            }.await() }
    }

    private class FakeBackend : ScreenBackend {
        var reads = 0; var actions = 0; var query: ScreenReadQuery? = null
        var onRead: () -> Unit = {}; var onDispatch: () -> Unit = {}; var onResult: () -> Unit = {}
        override fun availability(): String? = null
        override fun read(query: ScreenReadQuery): ScreenSnapshot {
            reads++; this.query = query; onRead()
            return ScreenSnapshot("revision", "hash", null, ScreenDisplay(100, 200, 0), "com.example.app", 1,
                listOf(ScreenWindow(1, 1, 1, true, true, ScreenBounds(0, 0, 100, 200))), emptyList(), false)
        }
        override suspend fun execute(expectedRevision: String, action: ScreenAction, beforeDispatch: () -> Unit): ScreenActionResult {
            onDispatch(); beforeDispatch(); actions++
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
