package com.openandroidintelligence.assistant

/**
 * Closed source choices for a user-selected attachment.  The holder keeps
 * this boundary independent from Android provider locations; the mobile app
 * later maps the opaque grant to the artifact-ports digest/ticket flow.
 */
const val MAX_ASSISTANT_ATTACHMENTS: Int = 4

enum class AssistantAttachmentSource {
    PHOTO_PICKER,
    SAF,
    ;

    companion object {
        /** Only explicit picker actions may establish a source claim. */
        fun fromIntentAction(action: String?): AssistantAttachmentSource? = when (action) {
            "android.provider.action.PICK_IMAGES" -> PHOTO_PICKER
            "android.intent.action.OPEN_DOCUMENT",
            "android.intent.action.GET_CONTENT" -> SAF
            else -> null
        }
    }
}

/** The holder has a grant, but deliberately does not expose its provider handle. */
data class AssistantReadGrant internal constructor(
    val grantId: String,
) {
    init {
        require(grantId.isNotBlank()) { "read grant must not be blank" }
    }
}

/** MIME hints are advisory; the main app must still digest and validate bytes. */
enum class AssistantAttachmentMediaType {
    JPEG,
    PNG,
    WEBP,
    PDF,
    TEXT_PLAIN,
}

/**
 * Typed hand-off record.  It contains no Android Uri, filesystem location,
 * URL, or cleartext bytes.  `selectionId` and `readGrant` are opaque to this
 * APK and are not sufficient to perform a read by themselves.
 */
data class AssistantAttachmentSelection internal constructor(
    val selectionId: String,
    val source: AssistantAttachmentSource,
    val readGrant: AssistantReadGrant,
    val mediaTypeHint: AssistantAttachmentMediaType?,
) {
    init {
        require(selectionId.isNotBlank()) { "selection ID must not be blank" }
    }
}

/**
 * The holder cannot mint a provider grant.  A signed/local adapter must bind
 * the private provider handle and return a real opaque selection, or return
 * null so the holder fails closed.
 */
interface AssistantAttachmentGrantIssuer {
    fun issue(
        source: AssistantAttachmentSource,
        providerSelection: AssistantProviderSelection,
    ): AssistantAttachmentSelection?
}

fun validateAssistantAttachmentSelections(
    selections: List<AssistantAttachmentSelection>,
) {
    require(selections.size <= MAX_ASSISTANT_ATTACHMENTS) {
        "too many assistant attachments"
    }
    require(selections.map { it.selectionId }.distinct().size == selections.size) {
        "assistant attachment selection IDs must be unique"
    }
}

/** Provider handles remain local and are never serialized into bridge requests. */
class AssistantProviderSelection internal constructor(internal val contentUri: android.net.Uri)
