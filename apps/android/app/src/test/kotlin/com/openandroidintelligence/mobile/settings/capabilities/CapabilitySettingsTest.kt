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
import androidx.compose.ui.test.onNodeWithText
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

    @Test fun catalogIncludesEveryBuiltInAndKeepsThirdPartyProvidersOffTheMainPage() {
        val id = "org.openandroidintelligence.sms.query"
        val official = verified("org.openandroidintelligence.sms", id)
        val external = verified("org.example.provider", id)
        val custom = verified("org.example.other", "org.example.other.custom")
        val catalog = capabilityCatalog(listOf(official, external, custom))
        // 主页面只列六条内建能力；短信的四项能力收在同一条入口的 members 里。
        assertEquals(6, builtInCapabilities.size)
        assertEquals(6, catalog.size)
        assertEquals(setOf("org.openandroidintelligence.sms.query", "org.openandroidintelligence.sms.schedule",
            "org.openandroidintelligence.sms.job-status", "org.openandroidintelligence.sms.cancel"),
            catalog.single { it.pluginId == "org.openandroidintelligence.sms" }.members.map { it.substringBefore('@') }.toSet())
        assertEquals(1, catalog.count { it.capability.substringBefore('@') == id })
        assertEquals(official, catalog.single { it.pluginId == official.identity.pluginId && it.capability == official.key }.verified)
        // 同名能力来自别的插件时不能合并进内建入口：用户会以为官方能力可用而实际调用落到第三方实现。
        assertEquals(0, catalog.count { it.pluginId == external.identity.pluginId })
        assertEquals(0, catalog.count { it.pluginId == custom.identity.pluginId })
        assertTrue(catalog.any { it.capability == "org.openandroidintelligence.screen-control.control" && it.verified == null })
    }

    @Test fun revokingOneCapabilityApprovalLeavesAnotherCapabilityUntouched() {
        // 临时授权随能力呈现：撤销一项不能连带撤销别项能力的批准。
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val controller = grants.authorization
        val revision = grants.state.value!!.revision
        val firstScope = CapabilityRequestScope("capability:first", DeviceOperation.READ, "a".repeat(64))
        val secondScope = CapabilityRequestScope("capability:second", DeviceOperation.WRITE, "b".repeat(64))
        val first = controller.approve("first", firstScope, 5, revision, 300_000)
        val second = controller.approve("second", secondScope, 5, revision, 300_000)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CapabilityTemporaryApprovals(grants, setOf("capability:first"))
                }
            }
        }
        // 只列出这一项能力的批准：另一项能力根本不出现。
        compose.onNodeWithTag("revoke-capability-approval:${second.id}").assertDoesNotExist()
        compose.onNodeWithTag("revoke-capability-approval:${first.id}").performScrollTo().performClick()
        assertFalse(controller.isCurrent(first.id, "first", firstScope, revision))
        assertTrue(controller.isCurrent(second.id, "second", secondScope, revision))
    }

    @Test fun aCapabilityWithoutApprovalsShowsNoApprovalSectionAtAll() {
        val grants = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CapabilityTemporaryApprovals(grants, setOf("capability:first"))
                }
            }
        }
        compose.onNodeWithTag("revoke-capability-approvals").assertDoesNotExist()
        compose.onNodeWithText("生效中的临时授权").assertDoesNotExist()
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
