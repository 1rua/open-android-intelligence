package com.openandroidintelligence.control

/** Transport-independent screen contracts. Native code never grants remote permission. */
data class ScreenReadQuery(
    val operation: String = "snapshot",
    val maxNodes: Int = 128,
    val maxDepth: Int = 24,
    val maxBytes: Int = 48_000,
    val text: String? = null,
    val resourceId: String? = null,
    val description: String? = null,
    val previousHash: String? = null,
) {
    init {
        require(operation in setOf("snapshot", "search", "state")) { "SCHEMA_INVALID" }
        require(maxNodes in 1..512 && maxDepth in 0..32 && maxBytes in 4096..48_000) { "SCHEMA_INVALID" }
        require(listOf(text, resourceId, description).all { it == null || it.length in 1..256 }) { "SCHEMA_INVALID" }
    }
}

data class ScreenBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun toMap(): Map<String, Any> = mapOf("left" to left, "top" to top, "right" to right, "bottom" to bottom)
}

data class ScreenDisplay(val widthPixels: Int, val heightPixels: Int, val rotation: Int) {
    fun toMap(): Map<String, Any> = mapOf("widthPixels" to widthPixels, "heightPixels" to heightPixels, "rotation" to rotation)
}

data class ScreenWindow(
    val id: Int, val type: Int, val layer: Int, val active: Boolean, val focused: Boolean, val bounds: ScreenBounds,
) {
    fun toMap(): Map<String, Any> = mapOf("id" to id, "type" to type, "layer" to layer,
        "active" to active, "focused" to focused, "bounds" to bounds.toMap())
}

/** Node references are valid only together with their snapshot revision. */
data class ScreenNode(
    val nodeId: String, val windowId: Int, val parentId: String?, val depth: Int,
    val text: String?, val description: String?, val resourceId: String?, val className: String?,
    val bounds: ScreenBounds, val clickable: Boolean, val longClickable: Boolean,
    val scrollable: Boolean, val editable: Boolean, val focused: Boolean, val selected: Boolean,
    val enabled: Boolean, val password: Boolean,
) {
    fun toMap(): Map<String, Any?> = mapOf("nodeId" to nodeId, "windowId" to windowId, "parentId" to parentId,
        "depth" to depth, "text" to text, "description" to description, "resourceId" to resourceId,
        "className" to className, "bounds" to bounds.toMap(), "clickable" to clickable,
        "longClickable" to longClickable, "scrollable" to scrollable, "editable" to editable,
        "focused" to focused, "selected" to selected, "enabled" to enabled, "password" to password)
}

data class ScreenSnapshot(
    val revision: String, val hash: String, val changed: Boolean?, val display: ScreenDisplay,
    val foregroundPackage: String?, val activeWindowId: Int?, val windows: List<ScreenWindow>,
    val nodes: List<ScreenNode>, val truncated: Boolean,
) {
    fun toMap(): Map<String, Any?> = mapOf("revision" to revision, "hash" to hash, "changed" to changed,
        "display" to display.toMap(), "foregroundPackage" to foregroundPackage, "activeWindowId" to activeWindowId,
        "windows" to windows.map { it.toMap() }, "nodes" to nodes.map { it.toMap() }, "truncated" to truncated)
}

sealed interface ScreenAction {
    /** Exactly one of nodeId or the x/y pair must be supplied. */
    data class Click(val nodeId: String? = null, val x: Int? = null, val y: Int? = null) : ScreenAction
    data class LongPress(val nodeId: String? = null, val x: Int? = null, val y: Int? = null, val durationMs: Long = 600) : ScreenAction
    data class SetText(val nodeId: String, val text: String) : ScreenAction
    data class Scroll(val nodeId: String, val direction: String) : ScreenAction
    data class Swipe(val x: Int, val y: Int, val endX: Int, val endY: Int, val durationMs: Long = 400) : ScreenAction
    data class Drag(val x: Int, val y: Int, val endX: Int, val endY: Int, val durationMs: Long = 800) : ScreenAction
    data class Global(val globalAction: String) : ScreenAction
    data class LaunchApp(val packageName: String) : ScreenAction
}

/** Existing control authorizer owns confirmation and revision checks for native actions. */
data class ScreenNativeAction(
    val action: ScreenAction,
    override val parameterDigest: String,
) : ScreenSemanticAction

data class ScreenActionResult(val performed: Boolean, val screen: ScreenSnapshot?, val stateError: String? = null) {
    fun toMap(): Map<String, Any?> = mapOf("performed" to performed, "screen" to screen?.toMap(), "stateError" to stateError)
}

/** Codes contain no application text, input arguments, or exception messages. */
class ScreenOperationException(val code: String) : IllegalStateException(code)

interface ScreenBackend {
    /** Null means the Android service is ready; local grants are checked by the caller. */
    fun availability(): String?
    fun read(query: ScreenReadQuery = ScreenReadQuery()): ScreenSnapshot
    suspend fun execute(expectedRevision: String, action: ScreenAction, beforeDispatch: () -> Unit = {}): ScreenActionResult
}
