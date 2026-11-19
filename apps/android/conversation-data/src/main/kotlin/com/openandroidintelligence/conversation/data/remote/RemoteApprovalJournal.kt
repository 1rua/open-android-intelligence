package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.gateway.schema.JsonValue

/** Harness 的日志 ID 只关联日志本身，不能拿来答复 $events。 */
internal class RemoteApprovalJournal {
    data class Decision(val outcome: ApprovalOutcome, val decidedAt: Long?)
    private class Ask(val callId: String) {
        var settled = false
        var decision: Decision? = null
    }

    private val asks = mutableMapOf<String, Ask>()
    private val idsByCall = mutableMapOf<String, MutableSet<String>>()

    fun accept(value: JsonValue): String? {
        if (value.text("type") == "snapshot") {
            value.array("records").orEmpty().forEach { record(it, live = false) }
            return null
        }
        return record(value, live = true)
    }

    private fun record(value: JsonValue, live: Boolean): String? {
        val event = value.at("event") ?: value
        val data = event.at("data") ?: return null
        val id = data.text("id")?.takeIf { it.isNotBlank() } ?: return null
        when (event.text("type")) {
            "approval/asked" -> {
                val callId = data.text("callId")?.takeIf { it.isNotBlank() } ?: return null
                idsByCall.getOrPut(callId) { mutableSetOf() }.add(id)
                asks.putIfAbsent(id, Ask(callId))
            }
            "approval/decided" -> {
                val ask = asks[id]?.takeUnless { it.settled } ?: return null
                ask.settled = true
                // 快照中的已结算记录不能证明其后收到的同 callId 实时请求也已结算。
                if (!live) return null
                val outcome = approvalOutcome(data.text("outcome")) ?: return null
                ask.decision = Decision(outcome, approvalDecisionTime(data) ?: approvalDecisionTime(event, "time"))
                return ask.callId
            }
        }
        return null
    }

    fun decision(callId: String): Decision? {
        val id = idsByCall[callId]?.singleOrNull() ?: return null
        return asks[id]?.takeIf { it.callId == callId }?.decision
    }
}
