package com.openandroidintelligence.screen

import android.app.KeyguardManager
import android.content.Context
import com.openandroidintelligence.control.*
import com.openandroidintelligence.control.remote.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativePhoneScreenTest {
    @Before fun prepare() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun restore() { Dispatchers.resetMain() }
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private fun request(id: String, taskId: String = "task") = ControlAccessBinding(RemoteControlPrincipal(
        ControlOrigin.fromCanonical("https://remote.example"), "account", "controller", "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)),
        1, "connection", 1).let { access -> PhoneExecutionContext(ControlRequestScope(ControlTaskScope(access.principal, taskId, 1, "process"), id),
        access, "sha256:" + "c".repeat(64), {}, {}) }
    private fun snapshotId(result: PhoneControlResult) = (result.result.values.getValue("snapshotId") as ControlValue.Text).value
    private class Backend : ScreenBackend {
        var reads = 0; val effects = mutableListOf<String>()
        override fun availability(): String? = null
        override fun read(query: ScreenReadQuery): ScreenSnapshot {
            reads++
            return ScreenSnapshot("revision", "hash", null, ScreenDisplay(100, 200, 0), "example", 1, emptyList(), emptyList(), false)
        }
        override suspend fun execute(expectedRevision: String, action: ScreenAction, beforeDispatch: () -> Unit): ScreenActionResult {
            beforeDispatch(); effects += (action as ScreenAction.Global).globalAction
            return ScreenActionResult(true, null)
        }
    }
    @Test fun entireActionQuietWaitAndObservationRetainsGlobalLease() = runTest {
        val backend = Backend(); val port = NativePhoneScreen(app, backend)
        val a = request("native-request-00001"); val b = request("native-request-00002")
        val snapshot = snapshotId(port.execute(a, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))
        val first = async { port.execute(a, ControlOperation.Screen(snapshot, ScreenAction.Global("back")), {}, { true }) }
        runCurrent(); val second = async { port.execute(b, ControlOperation.Screen(snapshot, ScreenAction.Global("home")), {}, { true }) }
        runCurrent(); assertEquals(listOf("back"), backend.effects)
        advanceTimeBy(399); runCurrent(); assertEquals(listOf("back"), backend.effects)
        advanceTimeBy(1); runCurrent(); assertTrue(first.isCompleted); assertEquals(listOf("back", "home"), backend.effects)
        advanceTimeBy(400); runCurrent(); assertTrue(second.isCompleted)
    }
    @Test fun controlDoesNotReturnTreeWithoutIndependentReadPermission() = runTest {
        val backend = Backend(); val port = NativePhoneScreen(app, backend); val request = request("native-request-00003")
        val snapshot = snapshotId(port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))
        val action = async { port.execute(request, ControlOperation.Screen(snapshot, ScreenAction.Global("back")), {}, { false }) }
        advanceUntilIdle()
        assertEquals(1, backend.reads)
        assertEquals(ControlValue.Text("SCREEN_READ_PERMISSION_REQUIRED"), action.await().ui!!.values["observationError"])
        assertEquals(ControlValue.Null, action.await().ui!!.values["snapshotId"])
    }
    @Test fun snapshotFromAnotherTaskAndLockScreenRefuseBeforeEffect() = runTest {
        val backend = Backend(); val port = NativePhoneScreen(app, backend); val request = request("native-request-00004")
        val snapshot = snapshotId(port.execute(request, ControlOperation.DumpTree(ScreenReadQuery()), {}, { true }))
        try { port.execute(request("native-request-00005", "other-task"), ControlOperation.Screen(snapshot, ScreenAction.Global("back")), {}, { true }); fail("应拒绝跨任务快照") }
        catch (failure: ControlRequestException) { assertEquals("SNAPSHOT_STALE", failure.code) }
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        try { port.execute(request, ControlOperation.Screen(snapshot, ScreenAction.Global("back")), {}, { true }); fail("应拒绝锁屏") }
        catch (failure: ControlRequestException) { assertEquals("SCREEN_DEVICE_LOCKED", failure.code) }
        assertTrue(backend.effects.isEmpty())
    }
    @Test @Config(sdk = [31]) fun missingLocalProtectionRefusesLegacyCaptureBeforeReadingPixels() = runTest {
        val backend = Backend(); val port = NativePhoneScreen(app, backend)
        try { port.execute(request("native-request-00006"), ControlOperation.CaptureSom, {}, { true }); fail("低版本不能伪称排除胶囊") }
        catch (failure: ControlRequestException) { assertEquals("SCREEN_OVERLAY_EXCLUSION_UNSUPPORTED", failure.code) }
        assertEquals(1, backend.reads)
    }
}
