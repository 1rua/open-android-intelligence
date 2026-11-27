package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalSubmissionOutcome
import com.openandroidintelligence.conversation.model.ApprovalSubmissionResult
import com.openandroidintelligence.conversation.model.asChoice
import com.openandroidintelligence.conversation.model.toOutcome
import com.openandroidintelligence.gateway.remote.rpc.RemoteDeliveryOutcome
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.schema.JsonValue

class RemoteFollowFrame internal constructor(
    val value: JsonValue,
    internal val approvalSource: RemoteApprovalSource? = null,
    internal val approvalJournal: Boolean = false,
)

/** 每条订阅使用独立答复目标，关闭或改换身份后旧请求仍绑定原订阅。 */
internal class RemoteApprovalSource(
    private val send: suspend (String, Boolean) -> JsonValue,
) {
    @Volatile
    var isOpen = true
        private set

    fun close() { isOpen = false }

    /** null 表示空回执，是否继续等待由调用方根据事件证据判断。 */
    suspend fun submit(handle: String, sessionId: String, choice: ApprovalChoice): ApprovalSubmissionResult? {
        if (!isOpen) return ApprovalSubmissionResult(ApprovalSubmissionOutcome.FAILED, errorCode = "APPROVAL_STREAM_CLOSED")
        return decodeApprovalReceipt(send(handle, choice == ApprovalChoice.ONCE), handle, sessionId, choice)
    }
}

internal fun approvalOutcome(token: String?): ApprovalOutcome? = when (token) {
    "allowed-once", "allow-once", "accept" -> ApprovalOutcome.ALLOWED_ONCE
    "rejected", "denied", "reject-once", "decline" -> ApprovalOutcome.DENIED
    "expired" -> ApprovalOutcome.TIMED_OUT
    "cancelled", "cancel", "unavailable" -> ApprovalOutcome.WITHDRAWN
    else -> ApprovalOutcome.of(token).takeUnless { it == ApprovalOutcome.UNKNOWN }
}

internal fun approvalDecisionTime(value: JsonValue, field: String = "decidedAt"): Long? = try {
    value.number(field)?.takeIf { it > 0 }
} catch (_: ArithmeticException) {
    null
}

internal fun unknownApprovalResult(code: String) =
    ApprovalSubmissionResult(ApprovalSubmissionOutcome.OUTCOME_UNKNOWN, errorCode = code)

private fun decodeApprovalReceipt(
    receipt: JsonValue,
    handle: String,
    sessionId: String,
    choice: ApprovalChoice,
): ApprovalSubmissionResult? {
    // RPC 桥和业务端各自可能包一层 ok/value。显式否定的回执不能被折叠为空 ACK。
    var value = receipt
    repeat(2) {
        if (value.flag("ok") == true && value.at("error") == null && value.flag("success") != false && value.at("data") == null) {
            value = value.at("value") ?: if ((value as JsonValue.JObject).fields.size == 1) JsonValue.JNull else value
        }
    }
    val body = value.at("data") ?: value
    val error = value.at("error")
    val details = error?.at("details")
    val decision = details?.at("approval") ?: details ?: body.at("approval") ?: body
    if (listOf("approvalId" to handle, "conversationId" to sessionId, "sessionId" to sessionId).any { (key, expected) ->
            decision.at(key) != null && decision.text(key) != expected
        }
    ) return unknownApprovalResult("APPROVAL_RECEIPT_SCOPE_MISMATCH")

    val token = decision.at("decision") ?: decision.at("outcome")
    val outcome = approvalOutcome(token?.stringValue())
    val decidedAt = approvalDecisionTime(decision)
    if ((error != null && error != JsonValue.JNull) || value.flag("ok") == false || value.flag("success") == false || body.flag("accepted") == false) {
        return approvalRejection(error?.text("code") ?: value.text("code"), outcome, decidedAt)
    }
    if (token != null && outcome == null) return unknownApprovalResult("APPROVAL_INVALID_RECEIPT")
    if (outcome != null) return settledApproval(outcome, decidedAt)
    if (body.flag("resolved") == true) return settledApproval(choice.toOutcome(), decidedAt)
    if (body.flag("accepted") == true || body.flag("resolved") == false) {
        return ApprovalSubmissionResult(ApprovalSubmissionOutcome.SUBMITTED)
    }
    if (value == JsonValue.JNull || (value is JsonValue.JObject && value.fields.isEmpty())) {
        // 已结算的事件也会得到空 ACK，不能由此知道哪次决策生效。
        return null
    }
    return unknownApprovalResult("APPROVAL_INVALID_RECEIPT")
}

private fun settledApproval(outcome: ApprovalOutcome, decidedAt: Long? = null): ApprovalSubmissionResult {
    val status = when (outcome) {
        ApprovalOutcome.TIMED_OUT -> ApprovalSubmissionOutcome.EXPIRED
        ApprovalOutcome.WITHDRAWN -> ApprovalSubmissionOutcome.ALREADY_RESOLVED
        else -> ApprovalSubmissionOutcome.SUBMITTED
    }
    return ApprovalSubmissionResult(status, outcome.asChoice, settledOutcome = outcome, decidedAt = decidedAt)
}

private val approvalErrorCode = Regex("[A-Za-z][A-Za-z0-9_.:/-]{0,79}")

internal fun approvalRejection(code: String?, outcome: ApprovalOutcome? = null, decidedAt: Long? = null): ApprovalSubmissionResult {
    val (status, terminal) = when (code) {
        "APPROVAL_EXPIRED", "EVENT_EXPIRED" -> ApprovalSubmissionOutcome.EXPIRED to ApprovalOutcome.TIMED_OUT
        "APPROVAL_WITHDRAWN" -> ApprovalSubmissionOutcome.ALREADY_RESOLVED to ApprovalOutcome.WITHDRAWN
        "APPROVAL_ALREADY_RESOLVED", "EVENT_ALREADY_RESOLVED" -> ApprovalSubmissionOutcome.ALREADY_RESOLVED to outcome
        "APPROVAL_NOT_FOUND", "CODEX_APPROVAL_NOT_FOUND", "CURSOR_APPROVAL_NOT_FOUND", "ACP_REQUEST_NOT_FOUND", "EVENT_NOT_FOUND", "REQUEST_NOT_FOUND" ->
            ApprovalSubmissionOutcome.NOT_FOUND to null
        "APPROVAL_UNSUPPORTED", "METHOD_NOT_FOUND", "METHOD_NOT_ALLOWED", "NOT_IMPLEMENTED" -> ApprovalSubmissionOutcome.UNSUPPORTED to null
        "RATE_LIMITED", "INVALID_MESSAGE", "PERMISSION_DENIED" -> ApprovalSubmissionOutcome.FAILED to null
        "OUTCOME_UNKNOWN" -> ApprovalSubmissionOutcome.OUTCOME_UNKNOWN to null
        else -> return unknownApprovalResult(code?.takeIf(approvalErrorCode::matches) ?: "APPROVAL_REMOTE_REJECTED")
    }
    return ApprovalSubmissionResult(status, terminal?.asChoice, code, terminal, decidedAt.takeIf { terminal != null })
}

internal fun RemoteRpcException.toApprovalResult(): ApprovalSubmissionResult = when (deliveryOutcome) {
    RemoteDeliveryOutcome.NOT_SENT -> ApprovalSubmissionResult(ApprovalSubmissionOutcome.FAILED,
        errorCode = code.takeIf(approvalErrorCode::matches) ?: "APPROVAL_NOT_SENT")
    RemoteDeliveryOutcome.UNKNOWN -> unknownApprovalResult(code.takeIf(approvalErrorCode::matches) ?: "APPROVAL_DELIVERY_UNKNOWN")
    RemoteDeliveryOutcome.REJECTED -> approvalRejection(code)
}
