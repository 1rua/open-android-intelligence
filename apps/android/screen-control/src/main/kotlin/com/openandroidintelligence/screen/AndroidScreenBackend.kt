/* Adapted from ActionExecutor.kt, Hermes Relay da782a8d74eb6631ea2f097b3cc69c6452ec33de.
 * Copyright (c) 2026 Axiom Labs. MIT; see LICENSES/hermes-relay-MIT.txt. */
package com.openandroidintelligence.screen

import android.content.Context
import com.openandroidintelligence.control.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class NativeNodeAction { CLICK, LONG_CLICK, SET_TEXT, SCROLL_FORWARD, SCROLL_BACKWARD, SCROLL_UP, SCROLL_DOWN, SCROLL_LEFT, SCROLL_RIGHT }

internal interface ScreenRuntime {
    fun availability(): String?
    fun generation(): Long
    fun display(): ScreenDisplay
    fun windows(): List<ScreenTreeWindow>
    fun foregroundPackage(): String?
    fun activeWindowId(): Int?
    fun nodeAction(expected: ScreenNode, action: NativeNodeAction, text: String?, generation: Long): Boolean
    suspend fun gesture(points: List<Pair<Int, Int>>, durationMs: Long, generation: Long, beforeDispatch: () -> Unit, drag: Boolean = false): Boolean
    fun global(action: String, generation: Long): Boolean
    fun launch(packageName: String, generation: Long): Boolean
}

/**
 * Native Android implementation. Callers own OAI local grants; beforeDispatch rechecks
 * them immediately before every system effect and before returning screen content.
 */
class AndroidScreenBackend internal constructor(private val runtime: ScreenRuntime) : ScreenBackend {
    constructor(context: Context) : this(AndroidAccessibilityRuntime(context.applicationContext))
    private val reader = ScreenTreeReader()
    private val actionLock = Mutex()

    override fun availability(): String? = runtime.availability()

    override fun read(query: ScreenReadQuery): ScreenSnapshot = reader.select(capture().first, query)

    private fun capture(): Pair<ScreenSnapshot, Long> {
        ready()
        val generation = runtime.generation()
        val display = runtime.display()
        val windows = runtime.windows()
        try {
            if (windows.isEmpty()) throw ScreenOperationException("SCREEN_WINDOW_UNAVAILABLE")
            val snapshot = reader.capture(windows, display, generation, runtime.foregroundPackage(), runtime.activeWindowId())
            if (runtime.generation() != generation || runtime.display() != display) throw ScreenOperationException("SCREEN_CHANGED")
            ready()
            return snapshot to generation
        } catch (failure: ScreenOperationException) { throw failure }
        catch (_: SecurityException) { throw ScreenOperationException("ACCESSIBILITY_SERVICE_UNAVAILABLE") }
        catch (_: RuntimeException) { throw ScreenOperationException("SCREEN_READ_FAILED") }
        finally { windows.forEach { it.root.close() } }
    }

    override suspend fun execute(expectedRevision: String, action: ScreenAction, beforeDispatch: () -> Unit): ScreenActionResult =
        actionLock.withLock {
            withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                val (snapshot, generation) = capture()
                if (expectedRevision != snapshot.revision) throw ScreenOperationException("SCREEN_STALE_REFERENCE")
                fun guard() {
                    ready()
                    if (runtime.generation() != generation || runtime.display() != snapshot.display) throw ScreenOperationException("SCREEN_STALE_REFERENCE")
                    beforeDispatch()
                }
                fun node(id: String): ScreenNode = snapshot.nodes.singleOrNull { it.nodeId == id }
                    ?.also { if (!it.enabled || it.password) throw ScreenOperationException("SCREEN_TARGET_NOT_INTERACTABLE") }
                    ?: throw ScreenOperationException("SCREEN_NODE_NOT_FOUND")
                fun point(x: Int?, y: Int?): Pair<Int, Int> {
                    if (x == null || y == null || x !in 0 until snapshot.display.widthPixels || y !in 0 until snapshot.display.heightPixels) {
                        throw ScreenOperationException("SCREEN_COORDINATES_INVALID")
                    }
                    return x to y
                }
                fun duration(ms: Long): Long = ms.also {
                    if (it !in 50..3000) throw ScreenOperationException("SCHEMA_INVALID")
                }
                fun semantic(id: String, kind: NativeNodeAction, text: String? = null): Boolean {
                    val target = node(id)
                    when (kind) {
                        NativeNodeAction.CLICK -> if (!target.clickable) throw ScreenOperationException("SCREEN_TARGET_NOT_INTERACTABLE")
                        NativeNodeAction.LONG_CLICK -> if (!target.longClickable) throw ScreenOperationException("SCREEN_TARGET_NOT_INTERACTABLE")
                        NativeNodeAction.SET_TEXT -> if (!target.editable) throw ScreenOperationException("SCREEN_TARGET_NOT_EDITABLE")
                        else -> if (!target.scrollable) throw ScreenOperationException("SCREEN_TARGET_NOT_SCROLLABLE")
                    }
                    guard()
                    return runtime.nodeAction(target, kind, text, generation)
                }
                val performed = when (action) {
                    is ScreenAction.Click -> {
                        val nodeId = action.nodeId
                        if (nodeId != null) {
                            if (action.x != null || action.y != null) throw ScreenOperationException("SCHEMA_INVALID")
                            semantic(nodeId, NativeNodeAction.CLICK)
                        } else {
                            val coordinate = point(action.x, action.y)
                            guard()
                            runtime.gesture(listOf(coordinate), 100, generation, ::guard)
                        }
                    }
                    is ScreenAction.LongPress -> {
                        val nodeId = action.nodeId
                        duration(action.durationMs)
                        if (nodeId != null) {
                            if (action.x != null || action.y != null) throw ScreenOperationException("SCHEMA_INVALID")
                            semantic(nodeId, NativeNodeAction.LONG_CLICK)
                        } else {
                            val coordinate = point(action.x, action.y)
                            guard()
                            runtime.gesture(listOf(coordinate), action.durationMs.coerceAtLeast(500), generation, ::guard)
                        }
                    }
                    is ScreenAction.SetText -> {
                        if (action.text.length > 4096) throw ScreenOperationException("SCHEMA_INVALID")
                        semantic(action.nodeId, NativeNodeAction.SET_TEXT, action.text)
                    }
                    is ScreenAction.Scroll -> semantic(action.nodeId, when (action.direction) {
                        "forward" -> NativeNodeAction.SCROLL_FORWARD
                        "backward" -> NativeNodeAction.SCROLL_BACKWARD
                        "up" -> NativeNodeAction.SCROLL_UP
                        "down" -> NativeNodeAction.SCROLL_DOWN
                        "left" -> NativeNodeAction.SCROLL_LEFT
                        "right" -> NativeNodeAction.SCROLL_RIGHT
                        else -> throw ScreenOperationException("SCHEMA_INVALID")
                    })
                    is ScreenAction.Swipe -> {
                        val points = listOf(point(action.x, action.y), point(action.endX, action.endY))
                        val ms = duration(action.durationMs)
                        guard(); runtime.gesture(points, ms, generation, ::guard)
                    }
                    is ScreenAction.Drag -> {
                        val points = listOf(point(action.x, action.y), point(action.endX, action.endY))
                        val ms = duration(action.durationMs)
                        // The runtime holds the first point before continuing the same stroke.
                        guard(); runtime.gesture(points, ms, generation, ::guard, drag = true)
                    }
                    is ScreenAction.Global -> {
                        if (action.globalAction !in setOf("back", "home", "recents", "notifications", "quickSettings")) throw ScreenOperationException("SCHEMA_INVALID")
                        guard(); runtime.global(action.globalAction, generation)
                    }
                    is ScreenAction.LaunchApp -> {
                        if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(action.packageName) || action.packageName.length > 256) {
                            throw ScreenOperationException("SCHEMA_INVALID")
                        }
                        guard(); runtime.launch(action.packageName, generation)
                    }
                }
                if (!performed) throw ScreenOperationException("SCREEN_ACTION_REJECTED")
                // Android acceptance is reported separately from the observed post-action state.
                // No sleep, retry or invented guarantee that the target app has finished rendering.
                try {
                    beforeDispatch()
                    val observed = read(ScreenReadQuery())
                    beforeDispatch()
                    ScreenActionResult(true, observed)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: ScreenOperationException) { ScreenActionResult(true, null, failure.code) }
                catch (failure: ScreenAuthorizationException) { ScreenActionResult(true, null, failure.code) }
                catch (_: Exception) { ScreenActionResult(true, null, "SCREEN_STATE_UNAVAILABLE") }
            }
        }

    private fun ready() { availability()?.let { throw ScreenOperationException(it) } }
}
