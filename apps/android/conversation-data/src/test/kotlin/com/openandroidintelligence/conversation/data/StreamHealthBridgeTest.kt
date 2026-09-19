package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamHealthBridgeTest {

    @Test fun `every transport state has exactly one domain meaning`() {
        assertEquals(StreamHealth.IDLE, StreamHealthBridge.toDomain(EventStreamStatus.IDLE))
        assertEquals(StreamHealth.CONNECTING, StreamHealthBridge.toDomain(EventStreamStatus.CONNECTING))
        assertEquals(StreamHealth.LIVE, StreamHealthBridge.toDomain(EventStreamStatus.LIVE))
        assertEquals(StreamHealth.RECONNECTING, StreamHealthBridge.toDomain(EventStreamStatus.RECONNECTING))
        assertEquals(StreamHealth.FAILED, StreamHealthBridge.toDomain(EventStreamStatus.FAILED))
    }

    /**
     * Without a sink there is nothing to observe, and "idle" is the honest
     * answer instead of a guess that the channel is fine.
     */
    @Test fun `no sink reports idle rather than guessing`() = runTest {
        assertEquals(StreamHealth.IDLE, StreamHealthBridge.of(null).first())
    }

    @Test fun `the sink status is mirrored as it changes`() = runTest {
        val sink = EventStreamStatusSink()
        val health = StreamHealthBridge.of(sink)

        assertEquals(StreamHealth.IDLE, health.first())
        sink.report(EventStreamStatus.LIVE)
        assertEquals(StreamHealth.LIVE, health.first())
        sink.report(EventStreamStatus.RECONNECTING)
        assertEquals(StreamHealth.RECONNECTING, health.first())
        sink.report(EventStreamStatus.FAILED)
        assertEquals(StreamHealth.FAILED, health.first())
    }
}
