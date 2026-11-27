package com.openandroidintelligence.mobile.settings.capabilities

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityRequestScope
import com.openandroidintelligence.kernel.DeviceOperation
import com.openandroidintelligence.kernel.InMemoryPairingGrantStore
import com.openandroidintelligence.kernel.PairingGrantBinding
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.plugin.pkg.PluginIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「临时授权随能力走」这件事要用真实的键格式验证。
 *
 * 这里刻意构造与 `ProductionPluginHost.approveRequest()` 相同形态的授权作用域键
 * （`policyKey`：含插件 ID、作者指纹、能力 ID、Schema 摘要、风险与原语摘要），
 * 而不是 `id@version`。两种键永不相等，用错一种就会让真实批准全部消失、撤销入口随之不见。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CapabilityTemporaryApprovalsTest {

    @get:Rule val compose = createComposeRule()

    private val authorFingerprint = "a".repeat(64)

    /** 与 `ProductionPluginHost.policyKey` 同构：身份 + capability:<id>:<schema>:<risk>:<primitives>。 */
    private fun scopeKey(pluginId: String, capabilityId: String, risk: String, primitives: List<String>): String {
        val identity = PluginIdentity(pluginId, authorFingerprint, "1.0.0")
        return "plugin:${identity.pluginId}:${identity.authorKeyFingerprint}:" +
            "capability:$capabilityId:${Json.sha256(Json.parse("{}"))}:$risk:" +
            Json.sha256(Json.of(primitives.sorted()))
    }

    private fun grantsWithBinding(): PairingGrantStateHolder =
        PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore()).apply {
            bind(PairingGrantBinding("https://core.example", "account", "install"))
        }

    @Test
    fun anIdentityScopedApprovalIsShownAndRevocable() {
        val grants = grantsWithBinding()
        val controller = grants.authorization
        val revision = grants.state.value!!.revision
        val key = scopeKey(
            "org.openandroidintelligence.sms",
            "org.openandroidintelligence.sms.query",
            "read",
            listOf("kernel.sms.read"),
        )
        val approval = controller.approve("request-1", CapabilityRequestScope(key, DeviceOperation.READ, "b".repeat(64)),
            durationMinutes = 5, policyRevision = revision, remainingRequestMillis = 300_000L)

        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CapabilityTemporaryApprovals(grants, setOf(key))
                }
            }
        }

        // 关键回归：真实的授权作用域键必须能匹配到这条批准。
        compose.onNodeWithText("生效中的临时授权").performScrollTo().assertExists()
        compose.onNodeWithTag("revoke-capability-approval:${approval.id}").performScrollTo().performClick()
        assertFalse(controller.isCurrent(approval.id, "request-1",
            CapabilityRequestScope(key, DeviceOperation.READ, "b".repeat(64)), revision))
    }

    @Test
    fun aVersionKeyNeverMatchesAnIdentityScopedApproval() {
        // 修复前的实现把 `id@version` 传进这个过滤集合：真实批准永远被隐藏。
        val grants = grantsWithBinding()
        val controller = grants.authorization
        val revision = grants.state.value!!.revision
        val key = scopeKey(
            "org.openandroidintelligence.sms", "org.openandroidintelligence.sms.query", "read", listOf("kernel.sms.read"),
        )
        val approval = controller.approve("request-2", CapabilityRequestScope(key, DeviceOperation.READ, "c".repeat(64)),
            durationMinutes = 5, policyRevision = revision, remainingRequestMillis = 300_000L)

        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CapabilityTemporaryApprovals(grants, setOf("org.openandroidintelligence.sms.query@1.0.0"))
                }
            }
        }

        compose.onNodeWithTag("revoke-capability-approval:${approval.id}").assertDoesNotExist()
        compose.onNodeWithText("生效中的临时授权").assertDoesNotExist()
    }

    @Test
    fun aSiblingCapabilityApprovalIsVisibleFromTheConsolidatedSmsDetail() {
        // 短信详情统一管理四项能力，兄弟能力的批准也必须在这里能看到并撤销。
        val grants = grantsWithBinding()
        val controller = grants.authorization
        val revision = grants.state.value!!.revision
        val queryKey = scopeKey("org.openandroidintelligence.sms", "org.openandroidintelligence.sms.query", "read", listOf("kernel.sms.read"))
        val scheduleKey = scopeKey("org.openandroidintelligence.sms", "org.openandroidintelligence.sms.schedule", "write",
            listOf("kernel.scheduler.set", "kernel.background.run"))
        val scheduleApproval = controller.approve("request-3",
            CapabilityRequestScope(scheduleKey, DeviceOperation.WRITE, "d".repeat(64)),
            durationMinutes = 5, policyRevision = revision, remainingRequestMillis = 300_000L)

        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CapabilityTemporaryApprovals(grants, setOf(queryKey, scheduleKey))
                }
            }
        }

        compose.onNodeWithTag("revoke-capability-approval:${scheduleApproval.id}").performScrollTo().assertExists()
    }

    @Test
    fun revokingHereDoesNotTouchAnotherCapabilitiesApproval() {
        val grants = grantsWithBinding()
        val controller = grants.authorization
        val revision = grants.state.value!!.revision
        val mineKey = scopeKey("org.openandroidintelligence.sms", "org.openandroidintelligence.sms.query", "read", listOf("kernel.sms.read"))
        val otherKey = scopeKey("org.openandroidintelligence.call-log", "org.openandroidintelligence.call-log.query", "read", listOf("kernel.call-log.read"))
        val mine = controller.approve("mine", CapabilityRequestScope(mineKey, DeviceOperation.READ, "e".repeat(64)),
            durationMinutes = 5, policyRevision = revision, remainingRequestMillis = 300_000L)
        val other = controller.approve("other", CapabilityRequestScope(otherKey, DeviceOperation.READ, "f".repeat(64)),
            durationMinutes = 5, policyRevision = revision, remainingRequestMillis = 300_000L)

        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CapabilityTemporaryApprovals(grants, setOf(mineKey))
                }
            }
        }

        compose.onNodeWithTag("revoke-capability-approval:${other.id}").assertDoesNotExist()
        compose.onNodeWithTag("revoke-capability-approvals").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-revoke-capability-approvals").performClick()
        assertFalse(controller.isCurrent(mine.id, "mine", CapabilityRequestScope(mineKey, DeviceOperation.READ, "e".repeat(64)), revision))
        assertTrue(controller.isCurrent(other.id, "other", CapabilityRequestScope(otherKey, DeviceOperation.READ, "f".repeat(64)), revision))
    }

    @Test
    fun remainingSecondsNeverClaimsAnExpiredApprovalHasTime() {
        assertEquals(0L, remainingSeconds(-1L))
        assertEquals(0L, remainingSeconds(0L))
        assertEquals(1L, remainingSeconds(1L))
        assertEquals(2L, remainingSeconds(1001L))
    }

    @Test
    fun aCapabilityWithoutApprovalsShowsNothing() {
        val grants = grantsWithBinding()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CapabilityTemporaryApprovals(grants, setOf("unused"))
                }
            }
        }
        compose.onNodeWithText("生效中的临时授权").assertDoesNotExist()
    }
}
