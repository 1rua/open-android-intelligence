package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Translates the transport's connection status into the domain's vocabulary.
 *
 * The mapping is total and closed: every transport state has exactly one
 * domain meaning, so a screen never has to guess what an unknown state implies.
 */
internal object StreamHealthBridge {

    fun of(sink: EventStreamStatusSink?): Flow<StreamHealth> =
        sink?.status?.map(::toDomain) ?: flowOf(StreamHealth.IDLE)

    fun toDomain(status: EventStreamStatus): StreamHealth = when (status) {
        EventStreamStatus.IDLE -> StreamHealth.IDLE
        EventStreamStatus.CONNECTING -> StreamHealth.CONNECTING
        EventStreamStatus.LIVE -> StreamHealth.LIVE
        EventStreamStatus.RECONNECTING -> StreamHealth.RECONNECTING
        EventStreamStatus.FAILED -> StreamHealth.FAILED
    }
}
