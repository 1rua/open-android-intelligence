package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.mobile.screen.temporaryApprovalParameterSummary
import org.junit.Assert.*
import org.junit.Test

class TemporaryApprovalCopyTest {
    @Test fun displaySummaryDoesNotRetainQueryPhoneTextOrUnknownSecrets() {
        val parameters = Json.parse("""{"senders":["+8613800000000"],"query":"private query","text":"secret text","password":"dont retain","packageIds":["com.example.app"]}""")
        val summary = temporaryApprovalParameterSummary(parameters).joinToString("\n")
        listOf("+8613800000000", "private query", "secret text", "dont retain", "com.example.app").forEach { assertFalse(summary.contains(it)) }
        assertTrue(summary.contains("电话号码"))
        assertTrue(summary.contains("应用来源：1"))
    }
}
