package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSessionFailureDeliveryOutcomeTest {
    @Test fun `发送异常诊断保留受控deliveryOutcome`() {
        val logs = mutableListOf<String>(); val previous = GatewayLog.sink
        GatewayLog.sink = { tag, message -> if (tag == "OaiRemoteSession") logs += message }
        try {
            logRemoteSessionFailure("harness", "prompt", "REMOTE_SUBMISSION_OUTCOME_UNKNOWN", deliveryOutcome = "UNKNOWN")
            assertTrue(logs.single().contains("UNKNOWN"))
        } finally { GatewayLog.sink = previous }
    }
}
