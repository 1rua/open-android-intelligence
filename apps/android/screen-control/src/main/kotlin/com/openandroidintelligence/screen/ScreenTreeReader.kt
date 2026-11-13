/*
 * Adapted from Hermes Relay ScreenReader.kt and ScreenHasher.kt,
 * commit da782a8d74eb6631ea2f097b3cc69c6452ec33de.
 * Copyright (c) 2026 Axiom Labs. MIT; see LICENSES/hermes-relay-MIT.txt.
 */
package com.openandroidintelligence.screen

import com.openandroidintelligence.control.*
import com.openandroidintelligence.gateway.schema.Json

/** Nodes are owned references: each child and every window root is closed exactly once. */
internal interface ScreenTreeNode : AutoCloseable {
    val text: String?
    val description: String?
    val resourceId: String?
    val className: String?
    val bounds: ScreenBounds
    val visible: Boolean
    val clickable: Boolean
    val longClickable: Boolean
    val scrollable: Boolean
    val editable: Boolean
    val focused: Boolean
    val selected: Boolean
    val enabled: Boolean
    val password: Boolean
    val childCount: Int
    fun child(index: Int): ScreenTreeNode?
}

internal data class ScreenTreeWindow(val window: ScreenWindow, val root: ScreenTreeNode)

/**
 * Relay's flat multi-window representation and per-child finally ownership are retained.
 * OAI additionally bounds visited containers, recursion, text bytes and response bytes.
 */
internal class ScreenTreeReader {
    companion object {
        const val MAX_WINDOWS = 16
        const val MAX_NODES = 512
        const val MAX_VISITED = 2048
        const val MAX_DEPTH = 32
        const val MAX_TEXT = 256
    }

    fun capture(
        windows: List<ScreenTreeWindow>, display: ScreenDisplay, generation: Long,
        foregroundPackage: String?, activeWindowId: Int?,
    ): ScreenSnapshot {
        val out = ArrayList<ScreenNode>()
        var visited = 0
        var truncated = windows.size > MAX_WINDOWS
        fun walk(node: ScreenTreeNode, windowId: Int, path: String, depth: Int, parent: String?) {
            if (visited >= MAX_VISITED || out.size >= MAX_NODES) { truncated = true; return }
            visited++
            val nodeId = "w$windowId:$path"
            val record = describe(node, nodeId, windowId, parent, depth)
            if (node.visible && record.bounds.right > record.bounds.left && record.bounds.bottom > record.bounds.top &&
                (record.text != null || record.description != null || record.resourceId != null ||
                    record.clickable || record.longClickable || record.scrollable || record.editable)) {
                out += record
            }
            if (depth >= MAX_DEPTH) { if (node.childCount > 0) truncated = true; return }
            // childCount itself can be enormous even when getChild returns null.
            val children = node.childCount.coerceAtMost(MAX_VISITED - visited)
            if (children < node.childCount) truncated = true
            for (index in 0 until children) {
                if (visited >= MAX_VISITED || out.size >= MAX_NODES) { truncated = true; return }
                val child = node.child(index)
                if (child == null) { visited++; continue }
                try { walk(child, windowId, "$path.$index", depth + 1, nodeId) }
                finally { child.close() }
            }
        }
        for (window in windows.take(MAX_WINDOWS)) {
            if (visited >= MAX_VISITED || out.size >= MAX_NODES) { truncated = true; break }
            walk(window.root, window.window.id, "0", 0, null)
        }
        val metadata = mapOf("display" to display.toMap(), "foregroundPackage" to foregroundPackage?.take(MAX_TEXT),
            "activeWindowId" to activeWindowId, "windows" to windows.take(MAX_WINDOWS).map { it.window.toMap() },
            "nodes" to out.map { it.toMap() }, "truncated" to truncated)
        // Canonical JSON prevents text containing separators from creating fingerprint collisions.
        val hash = Json.sha256(Json.of(metadata))
        val revision = Json.sha256(Json.of(mapOf("hash" to hash, "generation" to generation)))
        return ScreenSnapshot(revision, hash, null, display, foregroundPackage?.take(MAX_TEXT), activeWindowId,
            windows.take(MAX_WINDOWS).map { it.window }, out, truncated)
    }

    fun select(snapshot: ScreenSnapshot, query: ScreenReadQuery): ScreenSnapshot {
        val text = query.text
        val description = query.description
        val candidates = if (query.operation == "state") emptyList() else snapshot.nodes.filter { node ->
            node.depth <= query.maxDepth &&
                (text == null || node.text?.contains(text, ignoreCase = true) == true ||
                    node.description?.contains(text, ignoreCase = true) == true) &&
                (query.resourceId == null || node.resourceId == query.resourceId) &&
                (description == null || node.description?.contains(description, ignoreCase = true) == true)
        }
        val selected = ArrayList<ScreenNode>()
        var result = snapshot.copy(nodes = emptyList(), changed = query.previousHash?.let { it != snapshot.hash })
        for (node in candidates.take(query.maxNodes)) {
            selected += node
            val next = result.copy(nodes = selected.toList())
            if (encodedSize(next) > query.maxBytes) { selected.removeAt(selected.lastIndex); break }
            result = next
        }
        result = result.copy(truncated = snapshot.truncated ||
            (query.operation != "state" && (selected.size < candidates.size || snapshot.nodes.any { it.depth > query.maxDepth })))
        if (encodedSize(result) > query.maxBytes) throw ScreenOperationException("SCREEN_RESULT_TOO_LARGE")
        return result
    }

    private fun encodedSize(snapshot: ScreenSnapshot) = Json.canonical(Json.of(snapshot.toMap())).toByteArray(Charsets.UTF_8).size

    internal fun describe(node: ScreenTreeNode, id: String, window: Int, parent: String?, depth: Int): ScreenNode =
        ScreenNode(id, window, parent, depth,
            if (node.password) null else node.text?.take(MAX_TEXT)?.takeIf { it.isNotBlank() },
            if (node.password) null else node.description?.take(MAX_TEXT)?.takeIf { it.isNotBlank() },
            node.resourceId?.take(MAX_TEXT), node.className?.take(MAX_TEXT), node.bounds,
            node.clickable, node.longClickable, node.scrollable, node.editable, node.focused, node.selected,
            node.enabled, node.password)
}
