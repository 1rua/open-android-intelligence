package com.openandroidintelligence.mobile.screen

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.ui.resources.R as UiR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceAuthorizationCopyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun queryConfirmationNeverCallsSmsOrNotificationsASendOperation() {
        assertEquals(context.getString(UiR.string.perm_auth_cap_sms_read), deviceRequestAction("org.openandroidintelligence.sms.query@1.0.0", "query", "{}", context))
        assertEquals(context.getString(UiR.string.perm_auth_cap_notifications_read), deviceRequestAction("org.openandroidintelligence.notifications.query@1.0.0", "query", "{}", context))
        assertEquals(context.getString(UiR.string.perm_auth_cap_call_log_read), deviceRequestAction("org.openandroidintelligence.call-log.query@1.0.0", "query", "{}", context))
    }

    @Test fun parametersShowTheActualSenderAndTimeScopeWithoutInventingFilters() {
        val lines = deviceRequestParameterLines("""{"senders":["TEST-SENDER"],"receivedAfter":"2026-11-01T00:00:00Z","limit":2}""", context)
        val expected = listOf(
            "${context.getString(UiR.string.perm_auth_field_sms_sender)}：[\"TEST-SENDER\"]",
            "${context.getString(UiR.string.perm_auth_field_start_time)}：2026-11-01T00:00:00Z",
            "${context.getString(UiR.string.perm_auth_field_limit)}：2",
        )
        assertEquals(expected, lines)
        assertTrue(deviceRequestParameterLines("{}", context).single().contains(context.getString(UiR.string.perm_auth_params_empty)))
    }

    @Test fun defaultDetailsDoNotEchoInputTextOrInternalScreenReferences() {
        val lines = deviceRequestParameterLines("""{"nodeId":"internal-node","expectedRevision":"revision-secret","text":"TEST-TEXT"}""", context)
        val rendered = lines.joinToString("\n")
        assertFalse(rendered.contains("internal-node"))
        assertFalse(rendered.contains("revision-secret"))
        assertFalse(rendered.contains("TEST-TEXT"))
        assertTrue(rendered.contains(context.getString(UiR.string.perm_auth_param_chars_format, 9)))
    }

    @Test fun unsupportedOrOversizedParametersAreExplicitAndBounded() {
        assertTrue(deviceRequestParameterLines("not-json", context).single().contains(context.getString(UiR.string.perm_auth_params_unparseable)))
        assertTrue(deviceRequestParameterLines("""{"query":"${"x".repeat(200)}"}""", context).single().endsWith(context.getString(UiR.string.perm_auth_param_ellipsis)))
    }
}
