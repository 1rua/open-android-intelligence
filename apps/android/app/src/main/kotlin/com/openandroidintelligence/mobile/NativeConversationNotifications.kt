package com.openandroidintelligence.mobile

import android.content.Context
import android.content.SharedPreferences
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.ports.TimelineMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.openandroidintelligence.ui.resources.R as UiR

internal enum class NativeConversationStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, ENDED, DISCONNECTED;
    val isRunning: Boolean get() = this == QUEUED || this == RUNNING || this == DISCONNECTED
}

internal data class NativeConversationSession(
    val target: ReplyNotificationTarget,
    val generationId: String?,
    val title: String = "",
    val status: NativeConversationStatus,
    val latestReply: String = "",
    val lastOutcome: NativeConversationStatus? = null,
    val updatedAt: Long,
) {
    val isRunning: Boolean get() = status.isRunning
}

/** Identity fence captured before reading a remote conversation snapshot. */
internal data class NotificationReconciliationRequest(
    val session: NativeConversationSession,
    val revision: Long,
)

internal data class NotificationPresenceReconciliationRequest(
    val profileId: String,
    val accountId: String,
    val revisions: Map<ReplyNotificationTarget, Long>,
)

/** Process-owned projection of public assistant events, shared with native bubbles. */
internal class NativeConversationNotifications(
    private val context: Context,
    private val scope: CoroutineScope,
    private val publish: (NativeConversationSession, Boolean) -> Unit,
    private val titleFor: (ReplyNotificationTarget) -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val throttleMillis: Long = 750L,
    private val cancel: (ReplyNotificationTarget) -> Unit = {},
    private val preferencesFor: (String, String) -> SharedPreferences = { profile, account -> replyCompletionPreferences(context, profile, account) },
    private val metadataFlushMillis: Long = 750L,
    private val forget: (ReplyNotificationTarget) -> Unit = cancel,
) {
    private val _sessions = MutableStateFlow<List<NativeConversationSession>>(emptyList())
    val sessions = _sessions.asStateFlow()
    private val pending = mutableMapOf<ReplyNotificationTarget, Job>()
    private val lastPublished = mutableMapOf<ReplyNotificationTarget, Long>()
    private val observers = mutableMapOf<String, ReplyCompletionObserver>()
    private val activeRounds = mutableMapOf<ReplyNotificationTarget, LinkedHashMap<String, NativeConversationStatus>>()
    private data class RoundKey(val target: ReplyNotificationTarget, val generationId: String?)
    private data class ReplySnapshot(val messageId: String, val text: String, val revision: Long, val occurredAt: Long, val completed: Boolean)
    private val replies = mutableMapOf<RoundKey, ReplySnapshot>()
    private val terminalRounds = mutableMapOf<RoundKey, NativeConversationStatus>()
    private val recoveredRounds = mutableSetOf<RoundKey>()
    private val userMessages = mutableMapOf<RoundKey, String>()
    private val clientMessages = mutableMapOf<RoundKey, String>()
    private val roundAliases = mutableMapOf<RoundKey, String>()
    private val startedAt = mutableMapOf<RoundKey, Long>()
    private val reconciliationPending = mutableSetOf<ReplyNotificationTarget>()
    private val reconciliationDeadlines = mutableMapOf<ReplyNotificationTarget, Long>()
    private val reconciliationAttempts = mutableMapOf<ReplyNotificationTarget, Long>()
    private val reconciliationAttemptCounts = mutableMapOf<ReplyNotificationTarget, Int>()
    private val reconciliationDueAt = mutableMapOf<ReplyNotificationTarget, Long>()
    private val lastObservedAt = mutableMapOf<ReplyNotificationTarget, Long>()
    private val forcedReconciliation = mutableSetOf<ReplyNotificationTarget>()
    private val sessionRevisions = mutableMapOf<ReplyNotificationTarget, Long>()
    private val pendingRevisions = mutableMapOf<ReplyNotificationTarget, Long>()
    private val deletedTargets = mutableSetOf<ReplyNotificationTarget>()
    private var revisionSequence = 0L
    private class MetadataBatch(val preferences: SharedPreferences) {
        val changes = linkedMapOf<String, Long>()
        var flushJob: Job? = null
    }
    private val metadata = mutableMapOf<String, MetadataBatch>()
    private val lastPrunedAt = mutableMapOf<String, Long>()
    private var activeProfile: String? = null
    private var activeAccount: String? = null
    private var deliveryOccurredAt: Long = 0L
    private var activeAcceptTarget: ReplyNotificationTarget? = null
    private var activeAcceptCompletedRound: String? = null

    @Synchronized fun bind(profileId: String, accountId: String) {
        if (activeProfile == profileId && activeAccount == accountId) return
        leave()
        activeProfile = profileId
        activeAccount = accountId
    }

    /** Recoverable stream interruption: retain identity so resumed frames apply. */
    @Synchronized fun disconnect() {
        disconnectMatching { true }
    }

    /** 仅将指定端点的运行中通知标记为断连，避免切换工作区或掉线时 Live Update 卡在虚假进行中。 */
    @Synchronized fun disconnectEndpoint(profileId: String, accountId: String, agentId: String?) {
        disconnectMatching { it.profileId == profileId && it.accountId == accountId && it.agentId == agentId }
    }

    private fun disconnectMatching(matches: (ReplyNotificationTarget) -> Boolean) {
        pending.entries.filter { matches(it.key) }.toList().forEach { (target, job) ->
            job.cancel()
            pending.remove(target)
            pendingRevisions.remove(target)
        }
        val interrupted = _sessions.value.filter {
            it.isRunning && it.status != NativeConversationStatus.DISCONNECTED && matches(it.target)
        }
        if (interrupted.isEmpty()) return
        val interruptedTargets = interrupted.map { it.target }.toSet()
        _sessions.value = _sessions.value.map {
            if (it.target in interruptedTargets) it.copy(status = NativeConversationStatus.DISCONNECTED) else it
        }
        interrupted.forEach {
            sessionRevisions[it.target] = ++revisionSequence
            reconciliationPending += it.target
            forcedReconciliation += it.target
            reconciliationDueAt[it.target] = now()
            publishNow(it.copy(status = NativeConversationStatus.DISCONNECTED), false)
        }
    }

    /** Logout/account switch cannot leave another account's ongoing bubble behind. */
    @Synchronized fun leave() {
        pending.values.forEach(Job::cancel)
        pending.clear()
        pendingRevisions.clear()
        deletedTargets.clear()
        metadata.keys.toList().forEach(::flushMetadata)
        _sessions.value.filter { it.isRunning }.forEach { cancel(it.target) }
        _sessions.value = emptyList()
        activeRounds.clear()
        replies.clear()
        terminalRounds.clear()
        recoveredRounds.clear()
        userMessages.clear()
        clientMessages.clear()
        roundAliases.clear()
        startedAt.clear()
        reconciliationPending.clear()
        reconciliationDeadlines.clear()
        reconciliationAttempts.clear()
        reconciliationAttemptCounts.clear()
        reconciliationDueAt.clear()
        lastObservedAt.clear()
        forcedReconciliation.clear()
        sessionRevisions.clear()
        lastPublished.clear()
        activeProfile = null
        activeAccount = null
    }

    @Synchronized fun eraseAccount(profileId: String, accountId: String) {
        _sessions.value.filter { it.target.profileId == profileId && it.target.accountId == accountId }.forEach {
            pending.remove(it.target)?.cancel()
            cancel(it.target)
        }
        _sessions.value = _sessions.value.filterNot { it.target.profileId == profileId && it.target.accountId == accountId }
        activeRounds.keys.removeAll { it.profileId == profileId && it.accountId == accountId }
        replies.keys.removeAll { it.target.profileId == profileId && it.target.accountId == accountId }
        terminalRounds.keys.removeAll { it.target.profileId == profileId && it.target.accountId == accountId }
        recoveredRounds.removeAll { it.target.profileId == profileId && it.target.accountId == accountId }
        userMessages.keys.removeAll { it.target.profileId == profileId && it.target.accountId == accountId }
        clientMessages.keys.removeAll { it.target.profileId == profileId && it.target.accountId == accountId }
        roundAliases.keys.removeAll { it.target.profileId == profileId && it.target.accountId == accountId }
        startedAt.keys.removeAll { it.target.profileId == profileId && it.target.accountId == accountId }
        reconciliationPending.removeAll { it.profileId == profileId && it.accountId == accountId }
        reconciliationDeadlines.keys.removeAll { it.profileId == profileId && it.accountId == accountId }
        reconciliationAttempts.keys.removeAll { it.profileId == profileId && it.accountId == accountId }
        reconciliationAttemptCounts.keys.removeAll { it.profileId == profileId && it.accountId == accountId }
        reconciliationDueAt.keys.removeAll { it.profileId == profileId && it.accountId == accountId }
        lastObservedAt.keys.removeAll { it.profileId == profileId && it.accountId == accountId }
        forcedReconciliation.removeAll { it.profileId == profileId && it.accountId == accountId }
        sessionRevisions.keys.removeAll { it.profileId == profileId && it.accountId == accountId }
        pendingRevisions.keys.removeAll { it.profileId == profileId && it.accountId == accountId }
        deletedTargets.removeAll { it.profileId == profileId && it.accountId == accountId }
        val key = "$profileId\u0000$accountId"
        observers.remove(key)
        metadata.remove(key)?.flushJob?.cancel()
        lastPrunedAt.remove(key)
    }

    /** Permission/preference recovery revives only active work, never old finals. */
    @Synchronized fun refreshActive() {
        pending.values.forEach(Job::cancel)
        pending.clear()
        pendingRevisions.clear()
        _sessions.value.filter { it.isRunning && it.target.profileId == activeProfile && it.target.accountId == activeAccount }.forEach {
            publishNow(it, false)
        }
    }

    @Synchronized fun hasPendingReconciliation(): Boolean = _sessions.value.any { eligibleForReconciliation(it, now()) }

    @Synchronized fun requestReconciliation() {
        _sessions.value.filter { it.isRunning || now() <= (reconciliationDeadlines[it.target] ?: 0L) }.forEach {
            forcedReconciliation += it.target
            reconciliationDueAt[it.target] = now()
        }
    }

    @Synchronized fun capturePresenceReconciliation(): NotificationPresenceReconciliationRequest? {
        val profile = activeProfile ?: return null
        val account = activeAccount ?: return null
        return NotificationPresenceReconciliationRequest(profile, account,
            _sessions.value.associate { it.target to (sessionRevisions[it.target] ?: 0L) })
    }

    /** Only a complete, unfiltered, authenticated conversation listing may retire missing targets. */
    @Synchronized fun retireMissingTargets(expected: NotificationPresenceReconciliationRequest?, page: ConversationPage) {
        if (!page.authoritative || page.nextCursor != null || expected == null ||
            activeProfile != expected.profileId || activeAccount != expected.accountId) return
        val allConversationIds = page.conversations.map { it.id.value }.toSet()
        expected.revisions.forEach { (target, revision) ->
            // 原生 Agent 会话的列表来自另一个 Agent，本轮列表不能为它们作证。
            if (target.agentId != null) return@forEach
            if (target.conversationId !in allConversationIds && sessionRevisions[target] == revision) retireTarget(target, now())
        }
    }

    private fun retireTarget(target: ReplyNotificationTarget, occurredAt: Long) {
        val preferences = preferencesFor(target.profileId, target.accountId)
        preferences.edit().putLong("realtime.deleted." + replyDigest(target.identityKey()), occurredAt)
            .commitOrLog("NativeConversationNotifications")
        deletedTargets += target
        pending.remove(target)?.cancel()
        pendingRevisions.remove(target)
        _sessions.value = _sessions.value.filterNot { it.target == target }
        activeRounds.remove(target)
        replies.keys.removeAll { it.target == target }
        terminalRounds.keys.removeAll { it.target == target }
        recoveredRounds.removeAll { it.target == target }
        userMessages.keys.removeAll { it.target == target }
        clientMessages.keys.removeAll { it.target == target }
        roundAliases.keys.removeAll { it.target == target }
        startedAt.keys.removeAll { it.target == target }
        reconciliationPending.remove(target)
        reconciliationDeadlines.remove(target)
        reconciliationAttempts.remove(target)
        reconciliationAttemptCounts.remove(target)
        reconciliationDueAt.remove(target)
        lastObservedAt.remove(target)
        forcedReconciliation.remove(target)
        sessionRevisions.remove(target)
        lastPublished.remove(target)
        forget(target)
    }

    private fun eligibleForReconciliation(session: NativeConversationSession, time: Long): Boolean {
        if (time < (reconciliationDueAt[session.target] ?: 0L)) return false
        return if (session.isRunning) session.target in forcedReconciliation ||
            time - (lastObservedAt[session.target] ?: session.updatedAt) >= ACTIVE_RECONCILIATION_INTERVAL
        else session.target in reconciliationPending && time <= (reconciliationDeadlines[session.target] ?: 0L)
    }

    /** Bound each network pass and rotate busy conversations rather than starve them. */
    @Synchronized fun reconciliationRequests(agentId: String? = null): List<NotificationReconciliationRequest> {
        val currentTime = now()
        val candidates = _sessions.value
            .filter { (agentId == null || it.target.agentId == agentId) && eligibleForReconciliation(it, currentTime) }
            .sortedBy { reconciliationAttempts[it.target] ?: Long.MIN_VALUE }.take(8)
        return candidates.map { createReconciliationRequest(it, currentTime) }
    }

    /** 针对单个会话生成快照核对请求，避免影响其它待核对会话的重试计时。 */
    @Synchronized fun reconciliationRequestFor(target: ReplyNotificationTarget): NotificationReconciliationRequest? {
        val currentTime = now()
        val session = _sessions.value.firstOrNull { it.target == target } ?: return null
        if (!eligibleForReconciliation(session, currentTime)) return null
        return createReconciliationRequest(session, currentTime)
    }

    private fun createReconciliationRequest(session: NativeConversationSession, currentTime: Long): NotificationReconciliationRequest {
        reconciliationAttempts[session.target] = currentTime
        val attempt = reconciliationAttemptCounts[session.target] ?: 0
        reconciliationAttemptCounts[session.target] = (attempt + 1).coerceAtMost(4)
        reconciliationDueAt[session.target] = currentTime + if (session.isRunning) ACTIVE_RECONCILIATION_INTERVAL
            else (5_000L shl attempt.coerceAtMost(3)).coerceAtMost(30_000L)
        forcedReconciliation.remove(session.target)
        return NotificationReconciliationRequest(session, sessionRevisions[session.target] ?: 0L)
    }

    /** A failed/incomplete fetch never calls this method and cannot end a task. */
    @Synchronized fun reconcile(
        expected: NotificationReconciliationRequest,
        currentGeneration: Pair<String, String>?,
        messages: List<TimelineMessage>,
        activityKnown: Boolean = true,
    ): Boolean {
        val before = expected.session
        val target = before.target
        if (activeProfile != target.profileId || activeAccount != target.accountId || target in deletedTargets || sessionRevisions[target] != expected.revision) return false
        val current = _sessions.value.firstOrNull { it.target == target } ?: return false
        if (current != before) return false
        val key = RoundKey(target, before.generationId)
        val observed = replies[key]
        val userIndex = userMessages[key]?.let { id -> messages.indexOfLast { it.id == id && it.sender == "user" } } ?: -1
        val replyIndex = observed?.let { reply -> messages.indexOfLast { it.id == reply.messageId && it.sender == "assistant" } } ?: -1
        val first = when {
            userIndex >= 0 -> userIndex + 1
            replyIndex >= 0 -> replyIndex
            else -> messages.indexOfFirst { it.timestamp >= (startedAt[key] ?: Long.MAX_VALUE) }
        }
        val finalReply = if (first < 0) null else messages.drop(first).takeWhile { it.sender != "user" }
            .lastOrNull { it.sender == "assistant" && it.state.uppercase() in setOf("CONFIRMED", "COMPLETED", "STREAMING") }
        val snapshotCompleted = finalReply?.state?.uppercase() in setOf("CONFIRMED", "COMPLETED")
        val snapshotText = finalReply?.parts?.filterIsInstance<MessagePart.Text>()?.joinToString("\n") { it.value }
            ?.ifBlank { if (finalReply.parts.any { it is MessagePart.Attachment }) context.getString(UiR.string.notif_reply_attachment_placeholder) else "" }
        val snapshotRevision = finalReply?.contentRevision
        val canApplyBody = finalReply != null && !snapshotText.isNullOrBlank() && when {
            observed == null -> true
            finalReply.id != observed.messageId -> replyIndex >= 0 && messages.indexOf(finalReply) >= replyIndex ||
                finalReply.timestamp > observed.occurredAt
            snapshotRevision != null && snapshotRevision != observed.revision -> snapshotRevision > observed.revision
            else -> snapshotCompleted && !observed.completed || snapshotText.length >= observed.text.length
        }
        if (canApplyBody) {
            replies[key] = ReplySnapshot(finalReply!!.id, snapshotText!!, finalReply.contentRevision ?: observed?.revision ?: 0L,
                maxOf(finalReply.timestamp, observed?.occurredAt ?: 0L), completed = snapshotCompleted)
        }
        if (currentGeneration == null) {
            if (!activityKnown) {
                if (canApplyBody) {
                    val updated = before.copy(latestReply = replies[key]?.text ?: before.latestReply)
                    if (updated != before) update(updated, terminal = false, force = true)
                }
                return true
            }
            // Idle attests only that work ended. It cannot distinguish a
            // successful answer from cancellation or a remote failure.
            val outcome = terminalRounds[key] ?: before.status.takeUnless { it.isRunning } ?: NativeConversationStatus.ENDED
            if (outcome == NativeConversationStatus.ENDED) recoveredRounds += key
            terminalRounds[key] = outcome
            activeRounds.remove(target)
            // A terminal receipt may precede the final timeline write. Keep a
            // short retry window even when this read already contained a body.
            reconciliationPending += target
            if (reconciliationDeadlines.putIfAbsent(target, now() + FINAL_RECONCILIATION_WINDOW) == null) {
                reconciliationAttemptCounts[target] = 1
                reconciliationDueAt[target] = now() + 5_000L
            }
            val preferences = preferencesFor(target.profileId, target.accountId)
            before.generationId?.let { generation ->
                val durable = if (generation.startsWith("legacy:")) generation.removePrefix("legacy:")
                    else replyDigest("${target.identityKey()}\u0000$generation")
                flushMetadata("${target.profileId}\u0000${target.accountId}")
                // A later replay cannot promote this ended round back to active.
                if (!preferences.contains("realtime.terminal.$durable")) {
                    check(preferences.edit().putLong("realtime.terminal.$durable", now()).commit())
                }
            }
            val next = before.copy(status = outcome, latestReply = replies[key]?.text ?: before.latestReply, lastOutcome = outcome)
            if (next != before) update(next, terminal = false, force = true)
        } else {
            val (rawGeneration, remoteState) = currentGeneration
            val generation = canonicalRound(target, rawGeneration) ?: rawGeneration
            val remoteKey = RoundKey(target, generation)
            val preferences = preferencesFor(target.profileId, target.accountId)
            val remoteDurable = durableKeyFor(target, generation)
            val rawDurable = durableKeyFor(target, rawGeneration)
            val remoteAlreadyTerminal = terminalRounds.containsKey(remoteKey) ||
                terminalRounds.containsKey(RoundKey(target, rawGeneration)) ||
                (remoteDurable != null && preferences.contains("realtime.terminal.$remoteDurable")) ||
                (rawDurable != null && preferences.contains("realtime.terminal.$rawDurable"))
            // 终态屏障：已观察到终态或持久化终态围栏的代次绝不能被滞后快照重新拉回进行中。
            if (remoteAlreadyTerminal) {
                if (canApplyBody) update(before.copy(latestReply = replies[key]?.text ?: before.latestReply), terminal = false, force = true)
                return true
            }
            val anchorIndex = if (userIndex >= 0) userIndex else replyIndex
            val newerUsers = if (anchorIndex >= 0) messages.drop(anchorIndex + 1).filter { it.sender == "user" } else emptyList()
            // 单调终态屏障：会话已退回非运行态时，滞后快照里的不同/未知 key 若无严格晚于已终结轮次的新用户消息作证，
            // 严禁把已退出通知重新覆写回 RUNNING 或清空回复正文。
            if (!before.isRunning) {
                val provenNewTurn = generation != before.generationId && (
                    (userMessages[remoteKey] != null && (startedAt[remoteKey] ?: 0L) > before.updatedAt) ||
                        (newerUsers.size == 1 && newerUsers.single().id != userMessages[key] &&
                            (newerUsers.single().timestamp == 0L || newerUsers.single().timestamp >= (startedAt[key] ?: 0L)))
                    )
                if (!provenNewTurn) {
                    if (canApplyBody) update(before.copy(latestReply = replies[key]?.text ?: before.latestReply), terminal = false, force = true)
                    return true
                }
            }
            val status = when (remoteState) {
                "queued" -> NativeConversationStatus.QUEUED
                "running" -> NativeConversationStatus.RUNNING
                else -> NativeConversationStatus.DISCONNECTED
            }
            if (generation != before.generationId && newerUsers.isEmpty() && before.isRunning &&
                before.generationId != null && replies[remoteKey] == null
            ) {
                aliasRound(target, before.generationId, generation)
            }
            activeRounds[target] = linkedMapOf(generation to status)
            reconciliationPending.remove(target)
            reconciliationDeadlines.remove(target)
            if (generation != before.generationId && userMessages[remoteKey] == null) {
                // The new task may already have written its final body before
                // this HTTP pass. Local adoption time is not its start time.
                // One new user after an exact known round anchor has a safe
                // owner; several unseen users cannot be assigned to generations.
                if (newerUsers.size == 1) {
                    val user = newerUsers.single()
                    userMessages[remoteKey] = user.id
                    user.timestamp.takeIf { it > 0L }?.let { startedAt.putIfAbsent(remoteKey, it) }
                    val body = messages.drop(messages.indexOf(user) + 1).takeWhile { it.sender != "user" }
                        .lastOrNull { it.sender == "assistant" && it.state.uppercase() in setOf("CONFIRMED", "COMPLETED", "STREAMING") }
                    val text = body?.parts?.filterIsInstance<MessagePart.Text>()?.joinToString("\n") { it.value }
                        ?.ifBlank { if (body.parts.any { it is MessagePart.Attachment }) context.getString(UiR.string.notif_reply_attachment_placeholder) else "" }
                    if (body != null && !text.isNullOrBlank()) {
                        replies[remoteKey] = ReplySnapshot(body.id, text, body.contentRevision ?: 0L, body.timestamp,
                            completed = body.state.uppercase() in setOf("CONFIRMED", "COMPLETED"))
                    }
                }
            }
            val text = replies[remoteKey]?.text
                ?: if (generation == before.generationId || newerUsers.isEmpty()) replies[key]?.text ?: before.latestReply else ""
            if (generation != before.generationId || before.status != status || text != before.latestReply) {
                update(before.copy(generationId = generation, status = status, latestReply = text, lastOutcome = null,
                    updatedAt = maxOf(before.updatedAt, now())), terminal = false)
            }
        }
        return true
    }

    @Synchronized fun accept(input: NativeNotificationInput) {
        val target = input.target
        val profileId = target.profileId
        val accountId = target.accountId
        if (activeProfile != profileId || activeAccount != accountId) return
        val occurredAt = input.occurredAt
        val decoded = input.event
        val preferences = preferencesFor(profileId, accountId)
        val observerKey = "$profileId\u0000$accountId"
        // Construction persists the first-use cutoff before even the first frame
        // can publish a historical queued/delta/terminal projection.
        val observer = observers.getOrPut(observerKey) {
            ReplyCompletionObserver(preferences, profileId, accountId, ::complete, now)
        }
        val firstEnabled = preferences.getLong("first-enabled-at", 0L)
        if (occurredAt < now() - RETENTION || firstEnabled > 0L && occurredAt < firstEnabled - 300_000L) return
        val deletedKey = "realtime.deleted." + replyDigest(target.identityKey())
        if (input.kind == NativeNotificationKind.CONVERSATION_DELETED) {
            retireTarget(target, occurredAt)
            return
        }
        if (target in deletedTargets || preferences.contains(deletedKey)) return
        val generation = input.generationId
        if (decoded is VerifiedConversationEvent.TitleUpdated) {
            _sessions.value.firstOrNull { it.target == target }?.let {
                val title = decoded.newTitle.trim().take(80).ifBlank { sessionTitle(target) }
                if (it.isRunning) update(it.copy(title = title), terminal = false)
                else _sessions.value = _sessions.value.map { session -> if (session.target == target) session.copy(title = title) else session }
            }
            return
        }
        pruneRealtimeMetadata(preferences, observerKey)
        val previous = _sessions.value.firstOrNull { it.target == target }
        val statusEvent = decoded as? VerifiedConversationEvent.MessageStatus
        val legacyStatusRound = statusEvent?.let { "legacy:" + legacyStatusDigest(target.identityKey(), it) }
        val message = (decoded as? VerifiedConversationEvent.TimelineUpsert)?.message
        val round = when {
            statusEvent != null -> resolveRoundForStatus(target, statusEvent, generation, legacyStatusRound!!, previous)
            input.kind == NativeNotificationKind.GENERATION_CANCELLED -> resolveRoundForCancelled(target, generation, previous)
            message != null -> resolveRoundForMessage(target, input.kind, generation, previous, message.id)
            else -> canonicalRound(target, generation) ?: previous?.generationId
        }
        val durableKey = durableKeyFor(target, round)
        val roundKey = RoundKey(target, round)
        val knownRound = startedAt.containsKey(roundKey)
        val terminal = terminalRounds[roundKey]
        val refinesRecoveredOutcome = knownRound && terminal == NativeConversationStatus.ENDED &&
            input.kind in setOf(NativeNotificationKind.MESSAGE_STATUS, NativeNotificationKind.GENERATION_CANCELLED)
        val legacyOldKeys = if (statusEvent != null && generation == null) {
            legacyCompatibleStatusDigests(target.identityKey(), statusEvent).drop(1)
        } else emptyList()
        val upgradedLegacyFinished = legacyOldKeys.any {
            preferences.contains("done.$it") || preferences.contains("realtime.terminal.$it")
        }
        val alreadyFinished = upgradedLegacyFinished || (durableKey != null &&
            (preferences.contains("done.$durableKey") || preferences.contains("realtime.terminal.$durableKey")))
        if (upgradedLegacyFinished && durableKey != null && !preferences.contains("realtime.terminal.$durableKey")) {
            preferences.edit().putLong("realtime.terminal.$durableKey", occurredAt).commitOrLog("NativeConversationNotifications")
        }
        // 不同端点可以使用完全相同的原生事件 ID，去重也必须包含端点归属。
        val seenKey = input.eventId.takeIf { it.isNotBlank() }?.let {
            "realtime.event." + replyDigest(if (input.endpointId == null) it else "${target.identityKey()}\u0000$it")
        }
        val seen = seenKey != null && (metadata[observerKey]?.changes?.containsKey(seenKey) == true || preferences.contains(seenKey))
        // 修订围栏同样要带 Agent：两个 Agent 的同名会话可能有相同的消息 ID，
        // 只按会话比较会让后一家更高的修订被判成过期，正文与终态都被丢掉。
        // 平台旧帧没有 Agent，identityKey() 退化成纯会话 ID，摘要不变。
        val revisionIdentity = when (decoded) {
            is VerifiedConversationEvent.TimelineUpsert -> "body\u0000${target.identityKey()}\u0000${decoded.message.id}"
            is VerifiedConversationEvent.MessageStatus -> "status\u0000${target.identityKey()}\u0000${decoded.clientMessageId.value.ifBlank { decoded.messageId }}"
            else -> null
        }
        val revisionKey = revisionIdentity?.let { "realtime.revision." + replyDigest(it) }
        val revision = when (decoded) {
            is VerifiedConversationEvent.TimelineUpsert -> decoded.revision
            is VerifiedConversationEvent.MessageStatus -> decoded.revision
            else -> null
        }
        // Body events are snapshots: the main timeline accepts an equal-revision
        // completion or later chunk. Status revisions, in contrast, must advance.
        val previousRevision = revisionKey?.let { metadata[observerKey]?.changes?.get(it) ?: preferences.getLong(it, -1L) }
        val staleRevision = previousRevision != null && revision != null &&
            if (decoded is VerifiedConversationEvent.MessageStatus) previousRevision >= revision else previousRevision > revision
        val previousReply = replies[roundKey]
        // A terminal status can reach the device before a final body whose
        // server timestamp is earlier. Compare body snapshots to bodies only.
        val staleDeltaAfterCompletion = input.kind == NativeNotificationKind.REPLY_DELTA && (
            terminal != null || alreadyFinished || previousReply?.completed == true ||
                (previous != null && !previous.isRunning && (round == null || round == previous.generationId || roundAliases.containsKey(roundKey) || occurredAt < previous.updatedAt))
            )
        val olderPreview = message != null && (staleDeltaAfterCompletion || (previousReply != null && when {
            message.id != previousReply.messageId -> occurredAt < previousReply.occurredAt
            revision != null && revision > previousReply.revision -> false
            input.kind == NativeNotificationKind.REPLY_COMPLETED && !previousReply.completed -> false
            else -> occurredAt < previousReply.occurredAt
        }))
        val startsNewActiveRoundAfterTerminal = previous != null && !previous.isRunning && !alreadyFinished && terminal == null &&
            statusEvent?.status in setOf(AgentMessageStatus.QUEUED, AgentMessageStatus.DELIVERED)
        val staleUnknownRound = previous != null && occurredAt < previous.updatedAt &&
            round != previous.generationId && !knownRound && !startsNewActiveRoundAfterTerminal
        if (decoded is VerifiedConversationEvent.MessageStatus && staleRevision || staleUnknownRound) return
        if (message != null && previousRevision != null && revision != null && previousRevision > revision) return
        var deferredTerminalSession: NativeConversationSession? = null
        // Durable fences block replay after process death. In this process an
        // observed round may still receive a newer authoritative final body.
        if (!seen && (!alreadyFinished || knownRound && message != null || refinesRecoveredOutcome) && !staleRevision && !olderPreview) {
            if (statusEvent != null || input.kind == NativeNotificationKind.GENERATION_CANCELLED || message != null) {
                lastObservedAt[target] = now()
                forcedReconciliation.remove(target)
                reconciliationDueAt.remove(target)
                reconciliationAttemptCounts.remove(target)
            }
            if (seenKey != null || revisionKey != null) {
                val batch = metadata.getOrPut(observerKey) { MetadataBatch(preferences) }
                if (seenKey != null) batch.changes[seenKey] = occurredAt
                if (revisionKey != null && revision != null) {
                    batch.changes[revisionKey] = revision
                    batch.changes[revisionKey.replace("realtime.revision.", "realtime.revisionTime.")] = occurredAt
                }
                if (batch.flushJob == null) batch.flushJob = scope.launch {
                    delay(metadataFlushMillis)
                    synchronized(this@NativeConversationNotifications) { flushMetadata(observerKey) }
                }
            }
            if (generation != null) activeRounds[target]?.keys?.removeAll { it.startsWith("legacy:") && replies[RoundKey(target, it)] == null }
            val sameGeneration = previous != null && (round == null || previous.generationId == round)
            var next = previous ?: NativeConversationSession(target, round, sessionTitle(target), NativeConversationStatus.RUNNING, updatedAt = occurredAt)
            startedAt.putIfAbsent(roundKey, occurredAt)
            titleFor(target)?.trim()?.takeIf { it.isNotBlank() }?.let { next = next.copy(title = it.take(80)) }
            when (input.kind) {
                NativeNotificationKind.REPLY_DELTA, NativeNotificationKind.REPLY_COMPLETED -> {
                    val unambiguous = round != null || previous?.generationId == null || previous.generationId.startsWith("legacy:")
                    if (message != null && unambiguous) {
                        val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.value }
                            .ifBlank { if (message.parts.any { it is MessagePart.Attachment }) context.getString(UiR.string.notif_reply_attachment_placeholder) else "" }
                        // 发送流与事件页可用不同 eventId 转发同一终稿；终态正文没有
                        // 变化时不能重发用户已经处理的通知，完成观察器仍接收原事实。
                        val repeatedFinal = terminal != null && input.kind == NativeNotificationKind.REPLY_COMPLETED &&
                            previousReply?.completed == true && previousReply.messageId == message.id &&
                            previousReply.revision == (revision ?: 0L) && previousReply.text == text
                        if (text.isNotBlank() && !repeatedFinal) {
                            replies[roundKey] = ReplySnapshot(message.id, text, revision ?: 0L, occurredAt, input.kind == NativeNotificationKind.REPLY_COMPLETED)
                            val supersededOlderRound = terminal != null && previous != null && round != previous.generationId &&
                                (startedAt[roundKey] ?: occurredAt) < previous.updatedAt && previous.latestReply.isNotBlank()
                            if (!supersededOlderRound) {
                                if (terminal == null && round != null) activeRounds.getOrPut(target) { linkedMapOf() }[round] = NativeConversationStatus.RUNNING
                                next = next.copy(generationId = round, status = terminal ?: NativeConversationStatus.RUNNING, latestReply = text,
                                    lastOutcome = terminal, updatedAt = maxOf(next.updatedAt, occurredAt))
                                if (terminal != null) next = withRemainingRounds(next, next.updatedAt)
                                update(next, terminal = false, force = terminal != null || input.kind == NativeNotificationKind.REPLY_COMPLETED)
                            }
                        }
                    }
                }
                NativeNotificationKind.MESSAGE_STATUS -> {
                    val status = decoded as? VerifiedConversationEvent.MessageStatus
                    if (status != null) {
                        userMessages[roundKey] = status.messageId
                        if (status.clientMessageId.value.isNotBlank()) clientMessages[roundKey] = status.clientMessageId.value
                        // Unidentified legacy deltas can acquire their safe owner
                        // when the first user-message status arrives.
                        if (generation == null && replies[roundKey] == null && previous?.generationId == null) {
                            replies[RoundKey(target, null)]?.let { replies[roundKey] = it }
                        }
                        when (status.status) {
                        AgentMessageStatus.QUEUED, AgentMessageStatus.DELIVERED -> {
                            val staleReopen = previous != null && !previous.isRunning &&
                                (round == previous.generationId || (knownRound && occurredAt < previous.updatedAt))
                            if (terminal == null && !alreadyFinished && !staleReopen && (!sameGeneration || next.isRunning)) {
                                val startsNewLegacy = generation == null && previous?.generationId != legacyStatusRound
                                val rounds = activeRounds.getOrPut(target) { linkedMapOf() }
                                if (startsNewLegacy) rounds.keys.removeAll { it.startsWith("legacy:") && it != round }
                                rounds[round!!] = if (status.status == AgentMessageStatus.QUEUED) NativeConversationStatus.QUEUED else NativeConversationStatus.RUNNING
                                val active = rounds.entries.firstOrNull { it.value == NativeConversationStatus.RUNNING } ?: rounds.entries.first()
                                next = next.copy(generationId = active.key, status = active.value,
                                    latestReply = replies[RoundKey(target, active.key)]?.text.orEmpty(), lastOutcome = null, updatedAt = maxOf(next.updatedAt, occurredAt))
                                update(next, terminal = false)
                            }
                        }
                        AgentMessageStatus.FAILED -> finish(next.copy(generationId = round, status = NativeConversationStatus.FAILED,
                            latestReply = replies[roundKey]?.text.orEmpty(), updatedAt = maxOf(next.updatedAt, occurredAt)), durableKey)
                        AgentMessageStatus.COMPLETED -> {
                            // 先落盘终态围栏与活跃轮次清理；若本次观察器立即触发完整响铃通知，
                            // 则合成为单次终态发布；否则立即退出 Live Update 避免短暂残留。
                            val completedSession = next.copy(generationId = round, status = NativeConversationStatus.COMPLETED,
                                latestReply = replies[roundKey]?.text.orEmpty(), lastOutcome = NativeConversationStatus.COMPLETED,
                                updatedAt = maxOf(next.updatedAt, occurredAt))
                            if (persistTerminal(completedSession, durableKey, alert = false)) {
                                deferredTerminalSession = completedSession
                            }
                        }
                    }
                    }
                }
                NativeNotificationKind.GENERATION_CANCELLED -> finish(next.copy(generationId = round, status = NativeConversationStatus.CANCELLED,
                    latestReply = replies[roundKey]?.text.orEmpty(), updatedAt = maxOf(next.updatedAt, occurredAt)), durableKey)
                else -> Unit
            }
        }
        if (input.kind != NativeNotificationKind.REPLY_DELTA) flushMetadata(observerKey)
        deliveryOccurredAt = occurredAt
        val observerInput = when {
            round != null && round.startsWith("legacy:") && generation != null -> input.copy(resolvedGenerationId = null, forceLegacy = true)
            round != null && !round.startsWith("legacy:") && round != generation -> input.copy(resolvedGenerationId = round)
            else -> input
        }
        activeAcceptTarget = target
        activeAcceptCompletedRound = null
        try {
            observer.accept(observerInput)
            if (deferredTerminalSession != null && activeAcceptCompletedRound == null) {
                publishTerminalSession(deferredTerminalSession!!)
            }
        } finally {
            activeAcceptTarget = null
            activeAcceptCompletedRound = null
        }
    }

    private fun canonicalRound(target: ReplyNotificationTarget, round: String?): String? {
        var current = round ?: return null
        repeat(4) {
            current = roundAliases[RoundKey(target, current)] ?: return current
        }
        return current
    }

    private fun durableKeyFor(target: ReplyNotificationTarget, round: String?): String? =
        round?.let { if (it.startsWith("legacy:")) it.removePrefix("legacy:") else replyDigest("${target.identityKey()}\u0000$it") }

    /** 将状态占位轮次或临时标识归并到规范轮次，清除活跃集合中的空轮次残留。 */
    private fun aliasRound(target: ReplyNotificationTarget, fromRound: String, toRound: String) {
        if (fromRound == toRound) return
        val fromKey = RoundKey(target, fromRound)
        val toKey = RoundKey(target, toRound)
        roundAliases[fromKey] = toRound
        userMessages.remove(fromKey)?.let { userMessages.putIfAbsent(toKey, it) }
        clientMessages.remove(fromKey)?.let { clientMessages.putIfAbsent(toKey, it) }
        startedAt.remove(fromKey)?.let { startedAt.putIfAbsent(toKey, it) }
        replies.remove(fromKey)?.let { replies.putIfAbsent(toKey, it) }
        if (recoveredRounds.remove(fromKey)) recoveredRounds += toKey
        activeRounds[target]?.let { rounds ->
            val previousStatus = rounds.remove(fromRound)
            if (previousStatus != null && toRound !in rounds && toKey !in terminalRounds) {
                rounds[toRound] = previousStatus
            }
        }
    }

    private fun isPlaceholderRound(round: String): Boolean =
        round.startsWith("legacy:") || round.startsWith("request:")

    private fun findRoundByMessageIdentifiers(
        rounds: Map<String, NativeConversationStatus>?,
        target: ReplyNotificationTarget,
        messageId: String,
        clientMessageId: String,
    ): String? = rounds?.keys?.firstOrNull { candidate ->
        val candidateKey = RoundKey(target, candidate)
        userMessages[candidateKey] == messageId ||
            (clientMessageId.isNotBlank() &&
                (clientMessages[candidateKey] == clientMessageId || candidate == "request:$clientMessageId"))
    }

    private fun resolveRoundForMessage(
        target: ReplyNotificationTarget,
        kind: NativeNotificationKind,
        rawGeneration: String?,
        previous: NativeConversationSession?,
        messageId: String? = null,
    ): String? {
        if (rawGeneration != null) {
            val resolved = canonicalRound(target, rawGeneration)!!
            val rounds = activeRounds[target]
            if (rounds != null && resolved !in rounds && RoundKey(target, resolved) !in terminalRounds) {
                if (rounds.size == 1) {
                    val sole = rounds.keys.single()
                    val soleReply = replies[RoundKey(target, sole)]
                    val samePendingMessage = messageId != null && soleReply != null && !soleReply.completed && soleReply.messageId == messageId
                    if (soleReply == null || samePendingMessage) aliasRound(target, sole, resolved)
                }
            }
            val previousRound = previous?.generationId
            val previousSnapshot = previousRound?.let { replies[RoundKey(target, it)] }
            val provenSamePendingMessage = previousSnapshot != null && !previousSnapshot.completed &&
                messageId != null && previousSnapshot.messageId == messageId
            if (rounds.isNullOrEmpty() && previous != null && !previous.isRunning &&
                previous.status == NativeConversationStatus.COMPLETED && kind == NativeNotificationKind.REPLY_COMPLETED &&
                previousRound != null && provenSamePendingMessage && RoundKey(target, resolved) !in terminalRounds
            ) {
                roundAliases[RoundKey(target, resolved)] = previousRound
                return previousRound
            }
            return resolved
        }
        val rounds = activeRounds[target]
        if (rounds != null) {
            if (rounds.size == 1) return rounds.keys.single()
            return null
        }
        if (previous != null && !previous.isRunning && previous.status == NativeConversationStatus.COMPLETED &&
            kind == NativeNotificationKind.REPLY_COMPLETED && previous.generationId != null &&
            replies[RoundKey(target, previous.generationId)]?.completed != true
        ) {
            return previous.generationId
        }
        return if (previous?.isRunning == true) previous.generationId else null
    }

    private fun resolveRoundForStatus(
        target: ReplyNotificationTarget,
        status: VerifiedConversationEvent.MessageStatus,
        rawGeneration: String?,
        legacyStatusRound: String,
        previous: NativeConversationSession?,
    ): String {
        val isTerminalStatus = status.status == AgentMessageStatus.COMPLETED || status.status == AgentMessageStatus.FAILED
        if (rawGeneration != null) {
            val resolved = canonicalRound(target, rawGeneration)!!
            if (isTerminalStatus) {
                val rounds = activeRounds[target]
                if (rounds != null && resolved !in rounds && RoundKey(target, resolved) !in terminalRounds &&
                    RoundKey(target, resolved) !in replies
                ) {
                    val matched = findRoundByMessageIdentifiers(rounds, target, status.messageId, status.clientMessageId.value)
                    if (matched != null) {
                        roundAliases[RoundKey(target, resolved)] = matched
                        return matched
                    }
                    if (rounds.size == 1) {
                        val sole = rounds.keys.single()
                        if (isPlaceholderRound(sole)) {
                            aliasRound(target, sole, resolved)
                            return resolved
                        }
                    }
                }
            } else if (rawGeneration.startsWith("request:")) {
                val rounds = activeRounds[target]
                if (rounds != null && rawGeneration !in rounds && rounds.size == 1) {
                    val sole = rounds.keys.single()
                    val soleKey = RoundKey(target, sole)
                    if (userMessages[soleKey] == null && clientMessages[soleKey] == null) {
                        roundAliases[RoundKey(target, rawGeneration)] = sole
                        return sole
                    }
                }
            }
            return resolved
        }
        val rounds = activeRounds[target]
        val matched = findRoundByMessageIdentifiers(rounds, target, status.messageId, status.clientMessageId.value)
        if (matched != null) return matched
        val canonicalLegacy = canonicalRound(target, legacyStatusRound)!!
        if (canonicalLegacy != legacyStatusRound) return canonicalLegacy
        return legacyStatusRound
    }

    private fun resolveRoundForCancelled(
        target: ReplyNotificationTarget,
        rawGeneration: String?,
        previous: NativeConversationSession?,
    ): String? {
        if (rawGeneration != null) {
            val resolved = canonicalRound(target, rawGeneration)!!
            val rounds = activeRounds[target]
            if (rounds != null && resolved !in rounds && RoundKey(target, resolved) !in terminalRounds && rounds.size == 1) {
                val sole = rounds.keys.single()
                if (isPlaceholderRound(sole) || replies[RoundKey(target, sole)] == null) {
                    aliasRound(target, sole, resolved)
                }
            }
            return resolved
        }
        return activeRounds[target]?.keys?.singleOrNull() ?: previous?.generationId
    }

    @Synchronized private fun complete(target: ReplyNotificationTarget, generationId: String) {
        if (activeProfile != target.profileId || activeAccount != target.accountId) return
        if (activeAcceptTarget == target) activeAcceptCompletedRound = generationId
        val recovered = RoundKey(target, generationId) in recoveredRounds
        val previous = _sessions.value.firstOrNull { it.target == target }
        val terminalAt = deliveryOccurredAt.takeIf { it > 0L } ?: now()
        terminalRounds[RoundKey(target, generationId)] = NativeConversationStatus.COMPLETED
        val currentTitle = titleFor(target)?.trim()?.take(80)?.takeIf { it.isNotBlank() }
            ?: previous?.title?.takeIf { it.isNotBlank() && it != context.getString(UiR.string.notif_conv_default_title) }
            ?: sessionTitle(target)
        val updatedAt = maxOf(previous?.updatedAt ?: 0L, terminalAt)
        val next = withRemainingRounds((previous ?: NativeConversationSession(target, generationId, currentTitle, NativeConversationStatus.COMPLETED, updatedAt = terminalAt))
            .copy(generationId = generationId, title = currentTitle, status = NativeConversationStatus.COMPLETED,
                latestReply = replies[RoundKey(target, generationId)]?.text.orEmpty(), lastOutcome = NativeConversationStatus.COMPLETED,
                updatedAt = updatedAt), updatedAt)
        update(next, terminal = !recovered, force = true)
    }

    private fun finish(session: NativeConversationSession, durableKey: String?, alert: Boolean = true) {
        if (!persistTerminal(session, durableKey, alert)) return
        publishTerminalSession(session, alert)
    }

    private fun persistTerminal(session: NativeConversationSession, durableKey: String?, alert: Boolean): Boolean {
        val preferences = preferencesFor(session.target.profileId, session.target.accountId)
        val key = durableKey ?: session.generationId?.let(::replyDigest)
        val recovered = terminalRounds[RoundKey(session.target, session.generationId)] == NativeConversationStatus.ENDED
        if (key != null && preferences.contains("realtime.terminal.$key") && !recovered) return false
        // Publish only after the terminal commit also covers preceding buffered
        // event/revision guards; a crash in publish must not revive those deltas.
        flushMetadata("${session.target.profileId}\u0000${session.target.accountId}")
        val aliasKeys = roundAliases.entries
            .filter { it.key.target == session.target && it.value == session.generationId }
            .mapNotNull { it.key.generationId }
        if (key != null || aliasKeys.isNotEmpty()) {
            val editor = preferences.edit()
            if (key != null) {
                editor.putLong("realtime.terminal.$key", session.updatedAt)
                if (alert) editor.putLong("done.$key", session.updatedAt)
            }
            aliasKeys.forEach { alias ->
                terminalRounds[RoundKey(session.target, alias)] = session.status
                durableKeyFor(session.target, alias)?.let { aliasDurable ->
                    editor.putLong("realtime.terminal.$aliasDurable", session.updatedAt)
                }
            }
            editor.commitOrLog("NativeConversationNotifications")
        }
        terminalRounds[RoundKey(session.target, session.generationId)] = session.status
        reconciliationPending += session.target
        reconciliationDeadlines[session.target] = now() + FINAL_RECONCILIATION_WINDOW
        reconciliationDueAt[session.target] = now()
        reconciliationAttemptCounts[session.target] = 0
        return true
    }

    private fun publishTerminalSession(session: NativeConversationSession, alert: Boolean = false) {
        val finalTitle = titleFor(session.target)?.trim()?.take(80)?.takeIf { it.isNotBlank() }
            ?: session.title.takeIf { it.isNotBlank() && it != context.getString(UiR.string.notif_conv_default_title) }
            ?: sessionTitle(session.target)
        update(withRemainingRounds(session.copy(title = finalTitle, lastOutcome = session.status), session.updatedAt),
            terminal = alert && RoundKey(session.target, session.generationId) !in recoveredRounds, force = true)
    }

    private fun withRemainingRounds(terminal: NativeConversationSession, finishedAt: Long = terminal.updatedAt): NativeConversationSession {
        val rounds = activeRounds[terminal.target] ?: return terminal
        terminal.generationId?.let(rounds::remove)
        val terminalKey = terminal.generationId?.let { RoundKey(terminal.target, it) }
        val terminalStart = terminalKey?.let { startedAt[it] } ?: finishedAt
        val terminalClientId = terminalKey?.let { clientMessages[it] }
        val terminalUserId = terminalKey?.let { userMessages[it] }
        // 清理已终结、别名归并、同/早先起始的空占位及更早无主空轮次，严禁残留空轮次把已完成通知拉回“任务进行中”。
        rounds.keys.removeAll { candidate ->
            val candidateKey = RoundKey(terminal.target, candidate)
            val isAlias = roundAliases.containsKey(candidateKey)
            val isTerminal = candidateKey in terminalRounds
            val candidateClientId = clientMessages[candidateKey]
            val candidateUserId = userMessages[candidateKey]
            val sameOrUnownedOwner = (terminalClientId != null && candidateClientId == terminalClientId) ||
                (terminalUserId != null && candidateUserId == terminalUserId) ||
                (candidateClientId == null && candidateUserId == null)
            val emptyPlaceholder = isPlaceholderRound(candidate) &&
                replies[candidateKey] == null &&
                (startedAt[candidateKey] ?: 0L) <= terminalStart &&
                sameOrUnownedOwner
            val unownedOlder = !userMessages.containsKey(candidateKey) && !clientMessages.containsKey(candidateKey) &&
                replies[candidateKey] == null && (startedAt[candidateKey] ?: 0L) < terminalStart
            isAlias || isTerminal || emptyPlaceholder || unownedOlder
        }
        if (rounds.isEmpty()) { activeRounds.remove(terminal.target); return terminal }
        val active = rounds.entries.firstOrNull { it.value == NativeConversationStatus.RUNNING } ?: rounds.entries.first()
        return terminal.copy(generationId = active.key, status = active.value,
            latestReply = replies[RoundKey(terminal.target, active.key)]?.text.orEmpty())
    }

    private fun update(session: NativeConversationSession, terminal: Boolean, force: Boolean = false) {
        val ordered = (_sessions.value.filterNot { it.target == session.target } + session).sortedByDescending { it.updatedAt }
        val retainedTerminals = ordered.filterNot { it.isRunning }.take(32).toSet()
        _sessions.value = ordered.filter { it.isRunning || it in retainedTerminals }
        val retainedTargets = _sessions.value.map { it.target }.toSet()
        replies.keys.removeAll { it.target !in retainedTargets }
        terminalRounds.keys.removeAll { it.target !in retainedTargets }
        recoveredRounds.removeAll { it.target !in retainedTargets }
        userMessages.keys.removeAll { it.target !in retainedTargets }
        clientMessages.keys.removeAll { it.target !in retainedTargets }
        roundAliases.keys.removeAll { it.target !in retainedTargets }
        startedAt.keys.removeAll { it.target !in retainedTargets }
        reconciliationPending.retainAll(retainedTargets)
        reconciliationDeadlines.keys.retainAll(retainedTargets)
        reconciliationAttempts.keys.retainAll(retainedTargets)
        reconciliationAttemptCounts.keys.retainAll(retainedTargets)
        reconciliationDueAt.keys.retainAll(retainedTargets)
        lastObservedAt.keys.retainAll(retainedTargets)
        forcedReconciliation.retainAll(retainedTargets)
        sessionRevisions.keys.retainAll(retainedTargets)
        val revision = ++revisionSequence
        sessionRevisions[session.target] = revision
        pending.remove(session.target)?.cancel()
        pendingRevisions.remove(session.target)
        val elapsed = (now() - (lastPublished[session.target] ?: 0L)).coerceAtLeast(0L)
        if (terminal || force || elapsed >= throttleMillis) publishNow(session, terminal)
        else {
            pendingRevisions[session.target] = revision
            pending[session.target] = scope.launch {
            delay((throttleMillis - elapsed).coerceAtLeast(1))
            synchronized(this@NativeConversationNotifications) {
                // Cancellation may race a coroutine that already left delay().
                if (pendingRevisions[session.target] != revision) return@synchronized
                pending.remove(session.target)
                pendingRevisions.remove(session.target)
                if (activeProfile == session.target.profileId && activeAccount == session.target.accountId) {
                    _sessions.value.firstOrNull { it.target == session.target }?.let { publishNow(it, false) }
                }
            }
        }
        }
    }

    private fun publishNow(session: NativeConversationSession, alert: Boolean) {
        lastPublished[session.target] = now()
        publish(session, alert)
    }

    private fun flushMetadata(key: String) {
        val batch = metadata[key] ?: return
        batch.flushJob?.cancel()
        batch.flushJob = null
        if (batch.changes.isEmpty()) return
        val edit = batch.preferences.edit()
        batch.changes.forEach { (name, value) -> edit.putLong(name, value) }
        // One asynchronous write per streaming interval, not per token. A terminal
        // observer commit flushes this metadata together with its durable fence.
        edit.apply()
        batch.changes.clear()
    }

    private fun pruneRealtimeMetadata(preferences: SharedPreferences, key: String) {
        val currentTime = now()
        val previous = lastPrunedAt[key]
        if (previous != null && currentTime - previous < MAINTENANCE_INTERVAL) return
        lastPrunedAt[key] = currentTime
        val expired = preferences.all.filter { (name, value) ->
            (name.startsWith("realtime.event.") || name.startsWith("realtime.terminal.") || name.startsWith("realtime.revisionTime.")) &&
                value is Long && value < currentTime - RETENTION
        }.keys
        if (expired.isEmpty()) return
        val edit = preferences.edit()
        expired.forEach { name ->
            edit.remove(name)
            if (name.startsWith("realtime.revisionTime.")) edit.remove(name.replace("realtime.revisionTime.", "realtime.revision."))
        }
        edit.apply()
    }

    private fun sessionTitle(target: ReplyNotificationTarget): String = titleFor(target)?.trim()?.take(80)?.takeIf { it.isNotBlank() }
        ?: context.getString(UiR.string.notif_conv_default_title)

    companion object {
        private const val FINAL_RECONCILIATION_WINDOW = 120_000L
        private const val ACTIVE_RECONCILIATION_INTERVAL = 15_000L
        private const val RETENTION = 7L * 24 * 60 * 60 * 1000
        private const val MAINTENANCE_INTERVAL = 30L * 60 * 1000

    }
}
