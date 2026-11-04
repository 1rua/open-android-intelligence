package com.openandroidintelligence.mobile.settings.capabilities

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityPolicy
import com.openandroidintelligence.kernel.CapabilityRequestScope
import com.openandroidintelligence.kernel.DeviceOperation
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.mobile.plugins.SmsPassiveScopeSettings
import com.openandroidintelligence.mobile.plugins.VerifiedCapability
import com.openandroidintelligence.plugin.pkg.PluginIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CapabilitySettingsTest {
    @get:Rule val compose = createComposeRule()

    private fun verified(plugin: String, id: String, version: String = "1.0.0") = VerifiedCapability(
        PluginIdentity(plugin, "a".repeat(64), version), id, version, Json.parse("{}"), "read", setOf("kernel.sms.read"),
    )

    @Test fun catalogIncludesEveryBuiltInAndKeepsThirdPartyProvidersSeparate() {
        val id = "org.openandroidintelligence.sms.query"
        val official = verified("org.openandroidintelligence.sms", id)
        val external = verified("org.example.provider", id)
        val custom = verified("org.example.other", "org.example.other.custom")
        val catalog = capabilityCatalog(listOf(official, external, custom))
        assertEquals(9, builtInCapabilities.size)
        assertEquals(setOf("org.openandroidintelligence.sms.query", "org.openandroidintelligence.sms.schedule",
            "org.openandroidintelligence.sms.job-status", "org.openandroidintelligence.sms.cancel"),
            catalog.filter { it.pluginId == "org.openandroidintelligence.sms" }.map { it.capability.substringBefore('@') }.toSet())
        assertEquals(2, catalog.count { it.capability.substringBefore('@') == id })
        assertEquals(official, catalog.single { it.pluginId == official.identity.pluginId && it.capability == official.key }.verified)
        assertEquals(external, catalog.single { it.pluginId == external.identity.pluginId }.verified)
        assertEquals(custom.id, catalog.single { it.pluginId == custom.identity.pluginId }.title)
        assertTrue(catalog.any { it.capability == "org.openandroidintelligence.screen-control.control" && it.verified == null })
        assertNotEquals(catalog.single { it.pluginId == external.identity.pluginId }.pluginId,
            catalog.single { it.verified == official }.pluginId)
    }

    @Test fun singleRevocationIsConfirmedAndDoesNotRevokeOtherApprovals() {
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val controller = grants.authorization
        val revision = grants.state.value!!.revision
        val firstScope = CapabilityRequestScope("first", DeviceOperation.READ, "a".repeat(64))
        val secondScope = CapabilityRequestScope("second", DeviceOperation.WRITE, "b".repeat(64))
        val first = controller.approve("first", firstScope, 5, revision, 300_000)
        val second = controller.approve("second", secondScope, 5, revision, 300_000)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { TemporaryApprovalsSection(grants, null) } } }
        compose.onNodeWithTag("revoke-approval:${first.id}").performScrollTo().performClick()
        assertTrue(controller.isCurrent(first.id, "first", firstScope, revision))
        compose.onNodeWithTag("confirm-revoke-approval").performClick()
        assertFalse(controller.isCurrent(first.id, "first", firstScope, revision))
        assertTrue(controller.isCurrent(second.id, "second", secondScope, revision))
    }

    @Test fun smsDraftSurvivesSavedStateAndDoesNotSaveUntilConfirmed() {
        val identity = "sms:query@1.0.0"
        var saved = CapabilityPolicy()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            SmsPassiveScopeSettings(identity, CapabilityPolicy(), { saved = it })
        } } }
        compose.onNodeWithTag("sms-passive-mode-keywords:$identity").performClick()
        compose.onNodeWithTag("sms-passive-keywords:$identity").performTextReplacement("快递")
        assertEquals(CapabilityPolicy(), saved)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("sms-passive-save:$identity").performScrollTo().performClick()
        assertEquals(SmsScopeMode.KEYWORDS, saved.smsScopeMode)
        assertEquals(setOf("快递"), saved.smsKeywords)
    }

    @Test fun emptySmsScopeCannotBeSavedAfterRestoration() {
        val identity = "sms:query@1.0.0"
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { SmsPassiveScopeSettings(identity, CapabilityPolicy(), {}) } }
        compose.onNodeWithTag("sms-passive-mode-keywords:$identity").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("sms-passive-save:$identity").assertIsNotEnabled()
    }

    @Test fun remainingSecondsNeverClaimsAnExpiredApprovalHasTime() {
        assertEquals(0L, remainingSeconds(-1L))
        assertEquals(0L, remainingSeconds(0L))
        assertEquals(1L, remainingSeconds(1L))
        assertEquals(2L, remainingSeconds(1001L))
    }
}
