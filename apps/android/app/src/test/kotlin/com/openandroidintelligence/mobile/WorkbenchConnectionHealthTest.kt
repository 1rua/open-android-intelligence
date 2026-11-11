package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.gateway.http.TransportSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkbenchConnectionHealthTest {
    private val connected = ConnectionPhase.Connected(
        "http://core", "合成账号", null, null, TransportSecurity.PLAINTEXT,
    )

    @Test fun coreLiveProvidesAConnectedDisplayFactForIdleNativeSessions() {
        assertEquals(StreamHealth.LIVE, workbenchConnectionHealth(connected, EventStreamStatus.LIVE))
    }

    @Test fun coreConnectionTransitionsRemainVisible() {
        listOf(
            EventStreamStatus.CONNECTING to StreamHealth.CONNECTING,
            EventStreamStatus.RECONNECTING to StreamHealth.RECONNECTING,
            EventStreamStatus.FAILED to StreamHealth.FAILED,
        ).forEach { (core, expected) ->
            assertEquals("Core 状态 $core 必须可见", expected, workbenchConnectionHealth(connected, core))
        }
        assertNull(workbenchConnectionHealth(connected, EventStreamStatus.IDLE))
    }

    @Test fun loginRecoveryAndOfflineMirrorKeepTheirExistingDisplayStates() {
        listOf(ConnectionPhase.Initializing, ConnectionPhase.Negotiating, ConnectionPhase.Authenticating).forEach { phase ->
            assertEquals(StreamHealth.CONNECTING, workbenchConnectionHealth(phase, EventStreamStatus.LIVE))
        }
        assertEquals(StreamHealth.RECONNECTING, workbenchConnectionHealth(ConnectionPhase.Reconnecting, EventStreamStatus.LIVE))
        assertEquals(StreamHealth.FAILED, workbenchConnectionHealth(ConnectionPhase.OfflineMirror("http://core", "合成账号"), EventStreamStatus.LIVE))
        assertNull(workbenchConnectionHealth(ConnectionPhase.Disconnected, EventStreamStatus.IDLE))
    }
}
