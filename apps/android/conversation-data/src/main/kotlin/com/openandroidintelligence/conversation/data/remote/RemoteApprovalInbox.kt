package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOption
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.model.ApprovalSubmissionOutcome
import com.openandroidintelligence.conversation.model.ApprovalSubmissionResult
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.asChoice
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 只有实时请求能创建答复权限。历史记录与其他订阅的同名句柄都不能复用此权限。 */
internal class RemoteApprovalInbox(private val clock: () -> Long) {
    private data class Key(val conversationId: String, val source: RemoteApprovalSource, val handle: String)
    private data class CallKey(val conversationId: String, val callId: String)
    private data class Waterfall(val call: CallKey?)
    private class Entry(val key: Key, val choices: Set<ApprovalChoice>, val waterfall: Waterfall?) {
        val id = ApprovalId(UUID.randomUUID().toString())
        var submitting = false
        var deliveryClosed = false
        var awaitingJournal = false
        var receipt: ApprovalSubmissionResult? = null
        var terminal: ApprovalSubmissionResult? = null
    }

    private val byKey = mutableMapOf<Key, Entry>()
    private val byId = mutableMapOf<ApprovalId, Entry>()
    private val byCall = mutableMapOf<CallKey, MutableSet<Entry>>()
    private val journals = mutableMapOf<String, RemoteApprovalJournal>()

    @Synchronized
    fun accept(conversationId: String, sessionId: String, frame: RemoteFollowFrame): List<VerifiedConversationEvent> {
        val value = frame.value
        if (frame.approvalJournal) {
            val call = journals.getOrPut(conversationId, ::RemoteApprovalJournal).accept(value) ?: return emptyList()
            return reconcile(CallKey(conversationId, call))
        }
        val source = frame.approvalSource ?: return emptyList()
        when (value.text("type")) {
            "waterfall" -> {
                if (value.text("event") != "approval/request" || value.text("agentId") != sessionId) return emptyList()
                val handle = value.text("eventId") ?: return emptyList()
                val body = value.at("request") ?: return emptyList()
                val call = body.text("callId")?.takeIf { it.isNotBlank() }?.let { CallKey(conversationId, it) }
                return request(Key(conversationId, source, handle), body, standardOptions, Waterfall(call))
            }
            "cancel" -> {
                if (!matchesSession(value, sessionId)) return emptyList()
                val handle = value.text("eventId") ?: return emptyList()
                val entry = byKey[Key(conversationId, source, handle)] ?: return emptyList()
                if (entry.deliveryClosed || entry.terminal != null) return emptyList()
                // 清理会通知未胜出的订阅，也可能来自宿主取消。两种情况无法从这一帧区分。
                entry.deliveryClosed = true
                entry.awaitingJournal = false
                return listOf(resolution(entry, ApprovalOutcome.UNKNOWN, decidedAt = null))
            }
        }
        val event = value.at("event") ?: value
        val type = event.text("type") ?: value.text("event")
        val body = event.at("data") ?: value.at("payload") ?: event
        if (type in resolutionTypes) {
            if (!matchesSession(body, sessionId)) return emptyList()
            val handle = body.text("approvalId") ?: body.text("requestHandle") ?: return emptyList()
            val outcome = approvalOutcome(body.text("outcome") ?: body.text("decision")) ?: return emptyList()
            val entry = byKey[Key(conversationId, source, handle)] ?: return emptyList()
            return listOfNotNull(resolve(entry, outcome, approvalDecisionTime(body)))
        }
        val method = value.text("method")
        // 问答需要答案内容。当前答复接口的二选一对问答都会结算为取消。
        if (method == "cursor/ask_question") return emptyList()
        val request = when {
            type == "approval/asked" -> body
            method in approvalMethods -> value.at("params") ?: return emptyList()
            else -> return emptyList()
        }
        if (!matchesSession(request, sessionId)) return emptyList()
        val handle = request.text("requestHandle") ?: return emptyList()
        val options = if (method == "session/request_permission") permissionOptions(request) else standardOptions
        return request(Key(conversationId, source, handle), request, options)
    }

    private fun request(key: Key, value: JsonValue, options: List<ApprovalOption>, waterfall: Waterfall? = null): List<VerifiedConversationEvent> {
        if (!key.source.isOpen || key.handle.isBlank() || key in byKey || value !is JsonValue.JObject) return emptyList()
        val entry = Entry(key, options.map { it.choice }.toSet(), waterfall)
        byKey[key] = entry
        byId[entry.id] = entry
        val call = waterfall?.call
        if (call != null) byCall.getOrPut(call) { mutableSetOf() }.add(entry)
        val now = clock()
        val conversationId = ConversationId(key.conversationId)
        val command = value.text("command") ?: value.text("toolName")
            ?: value.at("toolCall")?.text("title") ?: value.text("title") ?: Json.canonical(value)
        val reason = value.text("reason") ?: ""
        val eventId = "approval:${entry.id.value}"
        if (options.isEmpty()) return listOf(VerifiedConversationEvent.ApprovalTextFallback(eventId, now, conversationId,
            entry.id, command, reason, diagnosticCode = "APPROVAL_OPTIONS_UNSUPPORTED"))
        val request = ApprovalRequest(entry.id, conversationId, command, reason, options = options, timeoutSeconds = 0,
            requestedAt = now, expiresAt = Long.MAX_VALUE)
        return listOf(VerifiedConversationEvent.ApprovalRequested(eventId, now, request, conversationId)) + call?.let(::reconcile).orEmpty()
    }

    private fun reconcile(call: CallKey): List<VerifiedConversationEvent.ApprovalResolved> {
        val candidates = byCall[call] ?: return emptyList()
        val journal = journals[call.conversationId] ?: return emptyList()
        val entry = candidates.singleOrNull()
        if (entry != null) {
            journal.decision(call.callId)?.let { return listOfNotNull(resolve(entry, it.outcome, it.decidedAt)) }
            if (journal.hasPendingAsk(call.callId)) return emptyList()
        }
        return candidates.filter { it.awaitingJournal }.map {
            it.awaitingJournal = false
            it.receipt = uncorrelatedEmptyAck()
            resolution(it, ApprovalOutcome.UNKNOWN, decidedAt = null)
        }
    }

    private fun resolve(entry: Entry, outcome: ApprovalOutcome, decidedAt: Long?): VerifiedConversationEvent.ApprovalResolved? {
        if (entry.terminal != null) return null
        entry.awaitingJournal = false
        entry.terminal = ApprovalSubmissionResult(
            if (outcome == ApprovalOutcome.TIMED_OUT) ApprovalSubmissionOutcome.EXPIRED else ApprovalSubmissionOutcome.ALREADY_RESOLVED,
            outcome.asChoice, settledOutcome = outcome, decidedAt = decidedAt,
        )
        return resolution(entry, outcome, decidedAt)
    }

    private fun resolution(entry: Entry, outcome: ApprovalOutcome, decidedAt: Long?) = VerifiedConversationEvent.ApprovalResolved(
        "approval-resolved:${entry.id.value}:${outcome.wireValue}", decidedAt ?: clock(), entry.id, outcome, decidedAt,
        ConversationId(entry.key.conversationId),
    )

    suspend fun submit(id: ApprovalId, choice: ApprovalChoice, isCurrent: (String) -> Boolean): ApprovalSubmissionResult {
        val entry = synchronized(this) {
            val bound = byId[id] ?: return ApprovalSubmissionResult(ApprovalSubmissionOutcome.NOT_FOUND)
            if (!isCurrent(bound.key.conversationId)) {
                return ApprovalSubmissionResult(ApprovalSubmissionOutcome.NOT_FOUND, errorCode = "APPROVAL_SCOPE_MISMATCH")
            }
            bound.terminal?.let { return it }
            if (bound.deliveryClosed) return unknownApprovalResult("APPROVAL_DELIVERY_CLOSED")
            if (choice !in bound.choices) return ApprovalSubmissionResult(ApprovalSubmissionOutcome.UNSUPPORTED)
            bound.receipt?.let { return it }
            if (bound.submitting) return unknownApprovalResult("APPROVAL_SUBMISSION_IN_FLIGHT")
            bound.submitting = true
            bound
        }
        val key = entry.key
        var attempted = false
        val result = try {
            currentCoroutineContext().ensureActive()
            if (!isCurrent(key.conversationId)) {
                ApprovalSubmissionResult(ApprovalSubmissionOutcome.NOT_FOUND, errorCode = "APPROVAL_SCOPE_MISMATCH")
            } else {
                attempted = true
                key.source.submit(key.handle, RemoteLocalIds.decode(key.conversationId), choice)
            }
        } catch (cancelled: CancellationException) {
            synchronized(this) {
                entry.submitting = false
                if (attempted) entry.receipt = unknownApprovalResult("APPROVAL_DELIVERY_UNKNOWN")
            }
            throw cancelled
        } catch (failure: RemoteRpcException) {
            failure.toApprovalResult()
        } catch (failure: RemoteSessionException) {
            approvalRejection(failure.code)
        } catch (_: Exception) {
            // 缺少传输层证据时，无法安全地判断宿主是否已接到决策。
            unknownApprovalResult("APPROVAL_DELIVERY_UNKNOWN")
        }
        return finish(entry, result)
    }

    @Synchronized
    private fun finish(entry: Entry, result: ApprovalSubmissionResult?): ApprovalSubmissionResult {
        entry.submitting = false
        entry.terminal?.let { return it }
        if (entry.deliveryClosed) return unknownApprovalResult("APPROVAL_DELIVERY_CLOSED")
        val receipt = result ?: emptyAckResult(entry)
        entry.receipt = receipt.takeUnless { it.outcome == ApprovalSubmissionOutcome.FAILED }
        return receipt
    }

    private fun emptyAckResult(entry: Entry): ApprovalSubmissionResult {
        val waterfall = entry.waterfall ?: return ApprovalSubmissionResult(ApprovalSubmissionOutcome.SUBMITTED)
        val call = waterfall.call ?: return uncorrelatedEmptyAck()
        val canWait = byCall[call]?.singleOrNull() == entry && journals[call.conversationId]?.hasPendingAsk(call.callId) == true
        if (!canWait) return uncorrelatedEmptyAck()
        entry.awaitingJournal = true
        return ApprovalSubmissionResult(ApprovalSubmissionOutcome.SUBMITTED)
    }

    private fun uncorrelatedEmptyAck() = unknownApprovalResult("APPROVAL_EMPTY_ACK_UNCORRELATED")

    @Synchronized
    fun clear() {
        byId.clear()
        byKey.clear()
        byCall.clear()
        journals.clear()
    }

    private fun matchesSession(value: JsonValue, sessionId: String): Boolean =
        listOf("agentId", "threadId", "sessionId", "conversationId").all { key ->
            value.at(key) == null || value.text(key) == sessionId
        }

    private fun permissionOptions(request: JsonValue): List<ApprovalOption> {
        val ids = mutableSetOf<String>()
        val choices = mutableSetOf<ApprovalChoice>()
        val options = mutableListOf<ApprovalOption>()
        for (option in request.array("options").orEmpty()) {
            val id = option.text("optionId")?.takeIf { it.isNotEmpty() } ?: return emptyList()
            if (!ids.add(id)) return emptyList()
            val choice = when (option.text("kind")) {
                "allow_once" -> ApprovalChoice.ONCE
                "reject_once" -> ApprovalChoice.DENY
                "allow_always", "reject_always" -> continue
                else -> return emptyList()
            }
            if (!choices.add(choice)) return emptyList()
            options += ApprovalOption(choice, option.text("name"))
        }
        return options
    }

    private companion object {
        val standardOptions = listOf(ApprovalOption(ApprovalChoice.ONCE), ApprovalOption(ApprovalChoice.DENY))
        val resolutionTypes = setOf("approval/resolved", "approval.resolved", "conversation.approval.resolved")
        val approvalMethods = setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval",
            "session/request_permission", "cursor/create_plan")
    }
}
