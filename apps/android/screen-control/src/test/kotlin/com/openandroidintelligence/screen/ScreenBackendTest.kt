package com.openandroidintelligence.screen

import com.openandroidintelligence.control.*
import com.openandroidintelligence.gateway.schema.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScreenBackendTest {
    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun restoreDispatcher() { Dispatchers.resetMain() }

    @Test fun multiWindowSearchPreservesRevisionAndRedactsPassword() {
        val runtime = FakeRuntime().apply {
            roots = listOf(Spec(text = "应用", children = listOf(Spec(text = "secret", password = true))),
                Spec(text = "权限弹窗", resourceId = "android:id/button1"))
        }
        val backend = AndroidScreenBackend(runtime)
        val all = backend.read()
        val search = backend.read(ScreenReadQuery(operation = "search", text = "弹窗"))
        assertEquals(2, all.windows.size)
        assertEquals(1, search.nodes.size)
        assertEquals("w2:0", search.nodes.single().nodeId)
        assertEquals(all.revision, search.revision)
        assertNull(all.nodes.single { it.password }.text)
        assertNull(all.nodes.single { it.password }.description)
        assertEquals(runtime.created, runtime.closed)
    }

    @Test fun boundedByteBudgetIncludesUnicodeEscapesAndMetadata() {
        val runtime = FakeRuntime().apply { roots = listOf(Spec(children = List(512) { Spec(text = "中文\n\"".repeat(100)) })) }
        val result = AndroidScreenBackend(runtime).read(ScreenReadQuery(maxNodes = 512, maxBytes = 4096))
        val bytes = Json.canonical(Json.of(result.toMap())).toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= 4096)
        assertTrue(result.truncated)
        assertTrue(result.nodes.isNotEmpty())
        assertEquals(runtime.created, runtime.closed)
    }

    @Test fun deeplyNestedAndInvisibleContainersHaveHardVisitAndDepthLimits() {
        var deep = Spec(text = "deep")
        repeat(200) { deep = Spec(children = listOf(deep), visible = false) }
        val runtime = FakeRuntime().apply { roots = listOf(deep) }
        val snapshot = AndroidScreenBackend(runtime).read(ScreenReadQuery(maxDepth = 32))
        assertTrue(snapshot.truncated)
        assertTrue(runtime.created <= 33)
        assertEquals(runtime.created, runtime.closed)
        runtime.created = 0; runtime.closed = 0
        runtime.roots = listOf(Spec(children = List(10_000) { Spec(visible = false) }))
        assertTrue(AndroidScreenBackend(runtime).read().truncated)
        assertTrue(runtime.created <= 2048)
        assertEquals(runtime.created, runtime.closed)
    }

    @Test fun windowAndRotationChangesInvalidateReferencesEvenWhenNodeIdsMatch() = runTest {
        val runtime = FakeRuntime()
        val backend = AndroidScreenBackend(runtime)
        val original = backend.read()
        runtime.currentDisplay = ScreenDisplay(1920, 1080, 1)
        fails("SCREEN_STALE_REFERENCE") { backend.execute(original.revision, ScreenAction.Click("w1:0")) }
        val rotated = backend.read()
        runtime.sequence++
        fails("SCREEN_STALE_REFERENCE") { backend.execute(rotated.revision, ScreenAction.Global("back")) }
        assertTrue(runtime.effects.isEmpty())
    }

    @Test fun semanticClickInputScrollLongPressAndGlobalUseNativeActions() = runTest {
        val runtime = FakeRuntime()
        val backend = AndroidScreenBackend(runtime)
        val revision = backend.read().revision
        assertTrue(backend.execute(revision, ScreenAction.Click("w1:0")).performed)
        backend.execute(revision, ScreenAction.SetText("w1:0", "真实输入"))
        backend.execute(revision, ScreenAction.Scroll("w1:0", "down"))
        backend.execute(revision, ScreenAction.LongPress("w1:0"))
        backend.execute(revision, ScreenAction.Global("back"))
        assertEquals(listOf("node:CLICK", "node:SET_TEXT:真实输入", "node:SCROLL_DOWN", "node:LONG_CLICK", "global:back"), runtime.effects)
        assertEquals(runtime.created, runtime.closed)
    }

    @Test fun coordinatesSwipeDragAndLaunchAreValidatedBeforeDispatch() = runTest {
        val runtime = FakeRuntime()
        val backend = AndroidScreenBackend(runtime)
        val revision = backend.read().revision
        fails("SCREEN_COORDINATES_INVALID") { backend.execute(revision, ScreenAction.Click(x = 1080, y = 10)) }
        fails("SCHEMA_INVALID") { backend.execute(revision, ScreenAction.Click("w1:0", 20, 20)) }
        fails("SCHEMA_INVALID") { backend.execute(revision, ScreenAction.Swipe(0, 0, 50, 50, 5000)) }
        assertTrue(runtime.effects.isEmpty())
        backend.execute(revision, ScreenAction.Click(x = 20, y = 30))
        backend.execute(revision, ScreenAction.Swipe(0, 1, 50, 60))
        backend.execute(revision, ScreenAction.Drag(20, 30, 60, 70))
        backend.execute(revision, ScreenAction.LaunchApp("org.example.app"))
        assertEquals(listOf("gesture:false:100", "gesture:false:400", "gesture:true:800", "launch:org.example.app"), runtime.effects)
    }

    @Test fun localAuthorizationIsRecheckedImmediatelyBeforeEffectAndBeforeResult() = runTest {
        val runtime = FakeRuntime()
        val backend = AndroidScreenBackend(runtime)
        val revision = backend.read().revision
        fails("SCREEN_SESSION_EXPIRED") {
            backend.execute(revision, ScreenAction.Click("w1:0")) { throw ScreenOperationException("SCREEN_SESSION_EXPIRED") }
        }
        assertTrue(runtime.effects.isEmpty())
        val result = backend.execute(revision, ScreenAction.Click("w1:0")) {
            if (runtime.effects.isNotEmpty()) throw ScreenAuthorizationException("SCREEN_SESSION_EXPIRED")
        }
        assertTrue(result.performed)
        assertNull(result.screen)
        assertEquals("SCREEN_SESSION_EXPIRED", result.stateError)
    }

    @Test fun permissionLossAndUnavailableNodeFailWithoutAnyEffect() = runTest {
        val runtime = FakeRuntime()
        val backend = AndroidScreenBackend(runtime)
        val revision = backend.read().revision
        fails("SCREEN_NODE_NOT_FOUND") { backend.execute(revision, ScreenAction.Click("w1:9")) }
        runtime.denial = "ACCESSIBILITY_SERVICE_UNAVAILABLE"
        fails("ACCESSIBILITY_SERVICE_UNAVAILABLE") { backend.execute(revision, ScreenAction.Global("back")) }
        runtime.denial = "SCREEN_DEVICE_LOCKED"
        fails("SCREEN_DEVICE_LOCKED") { backend.execute(revision, ScreenAction.Global("home")) }
        assertTrue(runtime.effects.isEmpty())
    }

    @Test fun cancellationStopsWaitingAndNeverDispatchesTheQueuedOperation() = runTest {
        val runtime = FakeRuntime().apply { gestureCompletion = CompletableDeferred() }
        val backend = AndroidScreenBackend(runtime)
        val revision = backend.read().revision
        val first = launch { backend.execute(revision, ScreenAction.Swipe(0, 0, 50, 50)) }
        val queued = launch { backend.execute(revision, ScreenAction.Global("back")) }
        queued.cancelAndJoin()
        first.cancelAndJoin()
        assertEquals(listOf("gesture:false:400"), runtime.effects)
    }

    @Test fun stateHashIsStableAcrossQueriesAndChangesOnContentOrGeometry() {
        val runtime = FakeRuntime()
        val backend = AndroidScreenBackend(runtime)
        val original = backend.read()
        val state = backend.read(ScreenReadQuery(operation = "state", previousHash = original.hash))
        assertEquals(original.hash, state.hash)
        assertEquals(false, state.changed)
        assertTrue(state.nodes.isEmpty())
        runtime.roots = listOf(Spec(text = "changed"))
        assertEquals(true, backend.read(ScreenReadQuery(previousHash = original.hash)).changed)
    }

    private suspend fun fails(code: String, action: suspend () -> Unit) {
        try { action(); fail("expected $code") }
        catch (failure: ScreenOperationException) { assertEquals(code, failure.code) }
    }
}

private data class Spec(
    val text: String? = "target", val resourceId: String? = null, val password: Boolean = false,
    val visible: Boolean = true, val children: List<Spec> = emptyList(),
)

private class FakeRuntime : ScreenRuntime {
    var roots = listOf(Spec())
    var created = 0
    var closed = 0
    var denial: String? = null
    var sequence = 1L
    var currentDisplay = ScreenDisplay(1080, 1920, 0)
    val effects = mutableListOf<String>()
    var gestureCompletion: CompletableDeferred<Boolean>? = null
    override fun availability() = denial
    override fun generation() = sequence
    override fun display() = currentDisplay
    override fun foregroundPackage() = "org.example.app"
    override fun activeWindowId() = 1
    override fun windows() = roots.mapIndexed { index, spec ->
        ScreenTreeWindow(ScreenWindow(index + 1, 1, index, index == 0, index == 0, ScreenBounds(0, 0, 1080, 1920)), tree(spec))
    }
    private fun tree(spec: Spec): ScreenTreeNode {
        created++
        return object : ScreenTreeNode {
            override val text = spec.text
            override val description = if (spec.password) "secret description" else null
            override val resourceId = spec.resourceId
            override val className = "android.widget.Button"
            override val bounds = ScreenBounds(0, 0, 50, 50)
            override val visible = spec.visible
            override val clickable = true
            override val longClickable = true
            override val scrollable = true
            override val editable = true
            override val focused = false
            override val selected = false
            override val enabled = true
            override val password = spec.password
            override val childCount = spec.children.size
            override fun child(index: Int) = spec.children.getOrNull(index)?.let(::tree)
            override fun close() { closed++ }
        }
    }
    override fun nodeAction(expected: ScreenNode, action: NativeNodeAction, text: String?, generation: Long, beforeDispatch: () -> Unit): Boolean {
        beforeDispatch()
        effects += "node:$action" + (text?.let { ":$it" } ?: "")
        return true
    }
    override suspend fun gesture(points: List<Pair<Int, Int>>, durationMs: Long, generation: Long,
        beforeDispatch: () -> Unit, drag: Boolean): Boolean {
        beforeDispatch()
        effects += "gesture:$drag:$durationMs"
        return gestureCompletion?.await() ?: true
    }
    override fun global(action: String, generation: Long, beforeDispatch: () -> Unit): Boolean { beforeDispatch(); effects += "global:$action"; return true }
    override fun launch(packageName: String, generation: Long, beforeDispatch: () -> Unit): Boolean { beforeDispatch(); effects += "launch:$packageName"; return true }
}
