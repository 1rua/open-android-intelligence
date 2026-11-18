package com.openandroidintelligence.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import com.openandroidintelligence.control.*
import com.openandroidintelligence.control.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class SomCaptureSafetyTest {
    @Before fun prepare() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun restore() { PhoneLocalControlUiRegistry.current = null; Dispatchers.resetMain() }
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val display = ScreenDisplay(120, 200, 0)
    private fun node(id: String, bounds: ScreenBounds, password: Boolean = false) = ScreenNode(id, 1, null, 0,
        if (password) null else "目标", null, "id:$id", "View", bounds,
        true, false, false, false, false, false, true, password)
    private fun screen() = ScreenSnapshot("snapshot-v1", "hash", null, display, "target.app", 1,
        listOf(ScreenWindow(1, 1, 1, true, true, ScreenBounds(10, 30, 110, 190))),
        listOf(node("covered", ScreenBounds(15, 35, 35, 50)), node("target", ScreenBounds(20, 95, 55, 130)),
            node("password", ScreenBounds(60, 140, 95, 170), true)), false)
    private class Backend(var snapshot: ScreenSnapshot) : ScreenBackend {
        var reads = 0
        override fun availability(): String? = null
        override fun read(query: ScreenReadQuery): ScreenSnapshot { reads++; return snapshot }
        override suspend fun execute(expectedRevision: String, action: ScreenAction, beforeDispatch: () -> Unit) = error("本测试不执行动作")
    }
    private class LocalUi(var protection: LocalUiCaptureProtection) : PhoneLocalControlUi {
        override val protectedBounds: ScreenBounds? = protection.maskedRegions.lastOrNull()
        override fun captureProtection() = protection
        override suspend fun confirm(requestId: String, capability: String, localParameters: String?) = error("不得远端确认")
        override suspend fun executing(requestId: String) = Unit
        override suspend fun finish(requestId: String) = Unit
        override fun emergencyStop() = Unit
    }
    private fun request() = ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical("https://som.example"),
        "account", "controller", "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)),
        1, "connection", 1).let { access -> PhoneExecutionContext(ControlRequestScope(
        ControlTaskScope(access.principal, "task", 1, "process"), "som-capture-request-01"), access, "sha256:" + "c".repeat(64), {}, {}) }
    private fun protection() = LocalUiCaptureProtection(3, display, 0,
        listOf(ScreenBounds(0, 0, 120, 60)), true)
    private fun frame(snapshot: ScreenSnapshot, excluded: Boolean): SomFrame {
        val b = if (excluded) snapshot.windows.single().bounds else ScreenBounds(0, 0, 120, 200)
        return SomFrame(Bitmap.createBitmap(b.right - b.left, b.bottom - b.top, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }, SystemClock.uptimeMillis(), snapshot.display, b, excluded)
    }
    @Test fun actualWebpMatchesSnapshotMappingAndMasksLocalHistoryAndPasswordPixels() = runTest {
        val backend = Backend(screen())
        PhoneLocalControlUiRegistry.current = LocalUi(protection())
        var captured: SomFrame? = null
        val excluded = android.os.Build.VERSION.SDK_INT >= 34
        val port = NativePhoneScreen(app, backend, SomFrameSource { snapshot -> frame(snapshot, excluded).also { captured = it } })
        val result = port.execute(request(), ControlOperation.CaptureSom, {}, { true })
        assertTrue(captured!!.bitmap.isRecycled)
        val artifact = result.artifacts.single()
        assertEquals("image/webp", artifact.mimeType)
        assertEquals("RIFF", artifact.bytes.copyOfRange(0, 4).decodeToString())
        assertEquals("WEBP", artifact.bytes.copyOfRange(8, 12).decodeToString())
        val decoded = BitmapFactory.decodeByteArray(artifact.bytes, 0, artifact.bytes.size)
        assertNotNull(decoded)
        try {
            val dx = if (excluded) 10 else 0; val dy = if (excluded) 30 else 0
            assertEquals(if (excluded) 100 else 120, decoded.width)
            assertEquals(if (excluded) 160 else 200, decoded.height)
            assertEquals(Color.BLACK, decoded.getPixel(75 - dx, 155 - dy))
            if (!excluded) assertEquals(Color.BLACK, decoded.getPixel(25, 45))
            assertEquals(Color.GREEN, decoded.getPixel(90 - dx, 105 - dy))
            val labels = result.result.values["mapping"] as ControlValue.Array
            val ids = labels.values.map { ((it as ControlValue.Object).values["nodeId"] as ControlValue.Text).value }
            assertTrue("target" in ids)
            assertFalse("password" in ids)
            assertEquals(excluded, "covered" in ids)
            assertEquals(ControlValue.Number(dx.toString()), result.result.values["imageOffsetX"])
            assertEquals(ControlValue.Number(dy.toString()), result.result.values["imageOffsetY"])
        } finally { decoded.recycle() }
    }
    @Test fun changedSnapshotRotationWrongGeometryAndOldFrameNeverPublish() = runTest {
        for (fault in listOf("rotation", "geometry", "old-frame")) {
            val backend = Backend(screen())
            PhoneLocalControlUiRegistry.current = LocalUi(protection())
            lateinit var owned: SomFrame
            val port = NativePhoneScreen(app, backend, SomFrameSource { snapshot ->
                val original = frame(snapshot, android.os.Build.VERSION.SDK_INT >= 34)
                owned = when (fault) {
                    "geometry" -> SomFrame(original.bitmap, original.capturedAtUptimeMs, snapshot.display,
                        ScreenBounds(0, 0, 119, 200), false)
                    "old-frame" -> SomFrame(original.bitmap, -1, snapshot.display, original.capturedBounds, original.overlayExcluded)
                    else -> original.also { backend.snapshot = snapshot.copy(display = ScreenDisplay(200, 120, 1)) }
                }
                owned
            })
            val result = runCatching { port.execute(request(), ControlOperation.CaptureSom, {}, { true }) }
            assertTrue(fault, result.isFailure)
            assertTrue(owned.bitmap.isRecycled)
        }
    }
    @Test fun stableRotatedFrameUsesCapturedScreenCoordinatesWithoutDoubleRotation() = runTest {
        val rotated = screen().copy(display = ScreenDisplay(200, 120, 1),
            windows = listOf(ScreenWindow(1, 1, 1, true, true, ScreenBounds(0, 0, 200, 120))),
            nodes = listOf(node("rotated-target", ScreenBounds(90, 50, 130, 85)),
                node("rotated-password", ScreenBounds(140, 80, 185, 110), true)))
        val owned = SomFrame(Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) },
            SystemClock.uptimeMillis(), rotated.display, ScreenBounds(0, 0, 200, 120), false)
        try {
            val rendered = SomImageRenderer.render(rotated, owned, listOf(ScreenBounds(0, 0, 200, 30)))
            val image = BitmapFactory.decodeByteArray(rendered.bytes, 0, rendered.bytes.size)
            try {
                assertEquals(200, image.width); assertEquals(120, image.height)
                assertEquals(Color.BLACK, image.getPixel(160, 95))
                assertEquals(Color.BLACK, image.getPixel(100, 15))
                assertEquals(Color.BLUE, image.getPixel(70, 80))
                assertEquals(listOf(mapOf("label" to 1, "nodeId" to "rotated-target")), rendered.mapping)
            } finally { image.recycle() }
        } finally { owned.close() }
    }
    @Test fun localWindowRelayoutOrUnprovableFullMaskFailsClosed() = runTest {
        if (android.os.Build.VERSION.SDK_INT >= 34) return@runTest
        for (fault in listOf("relayout", "full-mask", "not-ready")) {
            val backend = Backend(screen()); val local = LocalUi(protection())
            if (fault == "full-mask") local.protection = local.protection.copy(maskedRegions = listOf(ScreenBounds(0, 0, 120, 200)))
            if (fault == "not-ready") local.protection = local.protection.copy(ready = false)
            PhoneLocalControlUiRegistry.current = local
            var owned: SomFrame? = null
            val port = NativePhoneScreen(app, backend, SomFrameSource { snapshot ->
                frame(snapshot, false).also {
                    owned = it
                    if (fault == "relayout") local.protection = local.protection.copy(generation = 4, changedAtUptimeMs = SystemClock.uptimeMillis())
                }
            })
            assertTrue(fault, runCatching { port.execute(request(), ControlOperation.CaptureSom, {}, { true }) }.isFailure)
            assertTrue(owned == null || owned!!.bitmap.isRecycled)
        }
    }
    @Test fun cancellationAfterCaptureRecyclesPixelsAndNeverReturnsArtifact() = runTest {
        val backend = Backend(screen()); PhoneLocalControlUiRegistry.current = LocalUi(protection())
        var captured = false
        lateinit var owned: SomFrame
        val port = NativePhoneScreen(app, backend, SomFrameSource { snapshot ->
            frame(snapshot, android.os.Build.VERSION.SDK_INT >= 34).also { owned = it; captured = true }
        })
        var output: PhoneControlResult? = null
        val running = async {
            val executing = currentCoroutineContext()[Job]!!
            port.execute(request(), ControlOperation.CaptureSom, { if (captured) executing.cancel() }, { true }).also { output = it }
        }
        runCatching { running.await() }
        assertNull(output); assertTrue(owned.bitmap.isRecycled)
    }
}
