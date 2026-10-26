package com.openandroidintelligence.mobile.plugins

import com.openandroidintelligence.control.*
import com.openandroidintelligence.gateway.device.DeviceRequestResult
import org.junit.Assert.*
import org.junit.Test

class ScreenExecutionErrorsTest {
    @Test fun typed_errors_survive_wrappers_and_sensitive_exception_messages_never_escape() {
        val denied = ScreenExecutionErrors.result(IllegalStateException("private message", ScreenAuthorizationException("SCREEN_SESSION_EXPIRED")))
        assertEquals(mapOf("code" to "SCREEN_SESSION_EXPIRED"), (denied as DeviceRequestResult.Denied).payload)
        val failed = ScreenExecutionErrors.result(ScreenOperationException("SCREEN_STALE_REFERENCE"))
        assertEquals(mapOf("code" to "SCREEN_STALE_REFERENCE"), (failed as DeviceRequestResult.Failed).payload)
        assertNull(ScreenExecutionErrors.result(IllegalStateException("secret app contents")))
        assertNull(ScreenExecutionErrors.result(ScreenOperationException("private text must not be exposed")))
        assertSame(DeviceRequestResult.OutcomeUnknown, ScreenExecutionErrors.result(ScreenOperationException("SCREEN_ACTION_OUTCOME_UNKNOWN")))
    }

    @Test fun read_capture_and_control_have_separate_risk_classes() {
        assertEquals("read", PluginCapabilityRisk.classify("protected-wasm", setOf("kernel.screen.read")))
        assertEquals("read", PluginCapabilityRisk.classify("protected-wasm", setOf("kernel.screen.capture")))
        assertEquals("write", PluginCapabilityRisk.classify("protected-wasm", setOf("kernel.screen.control")))
        assertEquals("write", PluginCapabilityRisk.classify("protected-wasm", setOf("kernel.screen.read", "kernel.screen.control")))
    }
}
