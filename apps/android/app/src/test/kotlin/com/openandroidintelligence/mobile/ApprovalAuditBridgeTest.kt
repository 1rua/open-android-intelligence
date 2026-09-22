package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.AuditOutcome
import com.openandroidintelligence.kernel.InMemoryAuditSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 审批决策「用户确认」的审计桥（条目 2-9）。
 *
 * 只有被 Gateway 接受的决策（SUBMITTED）才进审计；decision/outcome 编码进
 * action 的封闭分段，approvalId 进 correlationId，事件落入既有审计链。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApprovalAuditBridgeTest {

    @Test
    fun aSubmittedDecisionIsRecordedIntoTheAuditChain() {
        val sink = InMemoryAuditSink()
        val store = AndroidAuditStore(sink)
        val bridge = ApprovalAuditBridge(
            audit = store,
            accountId = { "acc_stub" },
            pairingId = { "pairing_abc" },
        )

        bridge.onDecisionSubmitted("appr_1", "once", "SUBMITTED")

        val events = sink.events()
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("approval.user.confirmed.once.submitted", event.action)
        assertEquals("appr_1", event.correlationId)
        assertEquals("acc_stub", event.accountId)
        assertEquals("pairing_abc", event.pairingId)
        assertEquals(AuditOutcome.ALLOWED, event.outcome)
        // 落入既有审计链后必须可被渲染（防篡改链的可见出口）。
        assertTrue(store.render(event).contains("approval.user.confirmed.once.submitted"))
    }

    @Test
    fun aDecisionWithUnknownTierVocabularyIsRedactedNotSmuggledThrough() {
        val sink = InMemoryAuditSink()
        val store = AndroidAuditStore(sink)
        val bridge = ApprovalAuditBridge(audit = store, accountId = { "a" }, pairingId = { "p" })

        bridge.onDecisionSubmitted("appr_2", "DROP TABLE approvals", "SUBMITTED")

        val rendered = sink.events().map { store.render(it) }
        assertEquals(1, rendered.size)
        // 审计存证的净化规则兜底：不合法的动作词不会被原样写进流水。
        assertTrue(rendered[0].contains("action=redacted"))
    }
}
