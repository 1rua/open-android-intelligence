package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.approvals.ApprovalDecisionAudit
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.AuditOutcome

/**
 * 审批决策的「用户确认」审计桥（条目 2-9）。
 *
 * `gateway-client` 不得依赖 platform-kernel，因此它只暴露
 * [ApprovalDecisionAudit] 回调；宿主在装配处把它桥接到既有 [AndroidAuditStore]
 * （落盘经由 PersistentAuditSink 的防篡改链）。
 *
 * [AuditEvent] 没有承载 decision/outcome 的字段，且 platform-kernel 不可改——
 * 所以把两者编码进 `action`（封闭动作语法允许点分段）：每个上报只会是
 * `approval.user.confirmed.<decision>.<outcome小写>`，例如
 * `approval.user.confirmed.once.submitted`。只有决策被 Gateway 真正接受
 * （SUBMITTED）才会触发回调，因此它总是「用户确认已被网关收下」这一事实。
 */
class ApprovalAuditBridge(
    private val audit: AndroidAuditStore,
    private val accountId: () -> String,
    private val pairingId: () -> String,
) : ApprovalDecisionAudit {

    override fun onDecisionSubmitted(approvalId: String, decision: String, outcome: String) {
        audit.record(
            pluginId = PLUGIN_ID,
            accountId = accountId(),
            pairingId = pairingId(),
            action = "$ACTION.${decision}.${outcome.lowercase()}",
            outcome = AuditOutcome.ALLOWED,
            correlationId = approvalId,
        )
    }

    private companion object {
        /** 审计主体：这不是插件，是网关审批链路上的宿主确认动作。 */
        const val PLUGIN_ID = "host.gateway-approvals"
        const val ACTION = "approval.user.confirmed"
    }
}
