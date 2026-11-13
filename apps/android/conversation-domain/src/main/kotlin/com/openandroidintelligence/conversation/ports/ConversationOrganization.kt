package com.openandroidintelligence.conversation.ports

enum class ConversationSortMode(val wireValue: String) {
    UPDATED_DESC("updated-desc"), CUSTOM("custom");
}

data class ConversationFolder(val folderId: String, val name: String)

/** Revisioned account preference shared by conversation surfaces; native Agents store it on the phone. */
data class ConversationOrganization(
    val revision: Long = 0,
    val sortMode: ConversationSortMode = ConversationSortMode.UPDATED_DESC,
    val customOrder: List<String> = emptyList(),
    val folders: List<ConversationFolder> = emptyList(),
    val assignments: Map<String, String> = emptyMap(),
)

/** New conversations stay visible even when an older custom order does not name them yet. */
fun ConversationOrganization.sort(conversations: List<ConversationSummary>): List<ConversationSummary> {
    val recent = compareByDescending<ConversationSummary> { it.updatedAt }.thenBy { it.id.value }
    if (sortMode == ConversationSortMode.UPDATED_DESC) return conversations.sortedWith(recent)
    val positions = customOrder.withIndex().associate { it.value to it.index }
    return conversations.sortedWith(compareBy<ConversationSummary> { positions[it.id.value] ?: Int.MAX_VALUE }.then(recent))
}
