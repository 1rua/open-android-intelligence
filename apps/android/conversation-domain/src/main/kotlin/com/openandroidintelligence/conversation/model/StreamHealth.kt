package com.openandroidintelligence.conversation.model

import kotlinx.coroutines.flow.Flow

/**
 * How healthy the inbound event stream is, in the domain's own vocabulary.
 *
 * A reply only ever arrives as an event, so "the stream is dead" is the one
 * fact that explains a send with no answer. Naming it here keeps the screen
 * honest about it without depending on a transport module.
 */
enum class StreamHealth {
    /** Nothing has been attempted yet. */
    IDLE,

    /** A channel is being opened. */
    CONNECTING,

    /** A channel is open and has delivered at least one event. */
    LIVE,

    /** The last attempt failed; another is already scheduled. */
    RECONNECTING,

    /** The stream stopped retrying and needs the user to act. */
    FAILED,
}

/**
 * Implemented by the repository that owns the stream, so a screen can show
 * "reconnecting" instead of a spinner that never resolves.
 */
interface StreamHealthSource {
    val streamHealth: Flow<StreamHealth>
}
