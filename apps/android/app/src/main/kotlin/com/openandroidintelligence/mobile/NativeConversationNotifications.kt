package com.openandroidintelligence.mobile

import android.content.Context
import android.content.SharedPreferences
import com.openandroidintelligence.conversation.data.GatewayEventDecoder
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.conversations.GatewayTimelineMessage
import com.openandroidintelligence.gateway.conversations.MessagePart as WireMessagePart
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

internal enum class NativeConversationStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, ENDED, DISCONNECTED;
    val isRunning: Boolean get() = this == QUEUED || this == RUNNING || this == DISCONNECTED
}

internal data class NativeConversationSession(
    val target: ReplyNotificationTarget,
    val generationId: String?,
    val title: String = "Agent 会话",
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

    @Synchronized fun bind(profileId: String, accountId: String) {
        if (activeProfile == profileId && activeAccount == accountId) return
        leave()
        activeProfile = profileId
        activeAccount = accountId
    }

    /** Recoverable stream interruption: retain identity so resumed frames apply. */
    @Synchronized fun disconnect() {
        pending.values.forEach(Job::cancel)
        pending.clear()
        pendingRevisions.clear()
        val interrupted = _sessions.value.filter { it.isRunning && it.status != NativeConversationStatus.DISCONNECTED }
        _sessions.value = _sessions.value.map { if (it.isRunning) it.copy(status = NativeConversationStatus.DISCONNECTED) else it }
        interrupted.forEach {
            sessionRevisions[it.target] = ++revisionSequence
            reconciliationPending += it.target
            forcedReconciliation += it.target
            reconciliationDueAt[it.target] = now()
            publish(it.copy(status = NativeConversationStatus.DISCONNECTED), false)
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
    @Synchronized fun retireMissingTargets(expected: NotificationPresenceReconciliationRequest?, allConversationIds: Set<String>) {
        if (expected == null || activeProfile != expected.profileId || activeAccount != expected.accountId) return
        expected.revisions.forEach { (target, revision) ->
            if (target.conversationId !in allConversationIds && sessionRevisions[target] == revision) retireTarget(target, now())
        }
    }

    private fun retireTarget(target: ReplyNotificationTarget, occurredAt: Long) {
        val preferences = preferencesFor(target.profileId, target.accountId)
        check(preferences.edit().putLong("realtime.deleted." + replyDigest(target.conversationId), occurredAt).commit())
        deletedTargets += target
        pending.remove(target)?.cancel()
        pendingRevisions.remove(target)
        _sessions.value = _sessions.value.filterNot { it.target == target }
        activeRounds.remove(target)
        replies.keys.removeAll { it.target == target }
        terminalRounds.keys.removeAll { it.target == target }
        recoveredRounds.removeAll { it.target == target }
        userMessages.keys.removeAll { it.target == target }
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
    @Synchronized fun reconciliationRequests(): List<NotificationReconciliationRequest> {
        val currentTime = now()
        val candidates = _sessions.value.filter { eligibleForReconciliation(it, currentTime) }
            .sortedBy { reconciliationAttempts[it.target] ?: Long.MIN_VALUE }.take(8)
        return candidates.map {
            reconciliationAttempts[it.target] = currentTime
            val attempt = reconciliationAttemptCounts[it.target] ?: 0
            reconciliationAttemptCounts[it.target] = (attempt + 1).coerceAtMost(4)
            reconciliationDueAt[it.target] = currentTime + if (it.isRunning) ACTIVE_RECONCILIATION_INTERVAL
                else (5_000L shl attempt.coerceAtMost(3)).coerceAtMost(30_000L)
            forcedReconciliation.remove(it.target)
            NotificationReconciliationRequest(it, sessionRevisions[it.target] ?: 0L)
        }
    }

    /** A failed/incomplete fetch never calls this method and cannot end a task. */
    @Synchronized fun reconcile(
        expected: NotificationReconciliationRequest,
        currentGeneration: Pair<String, String>?,
        messages: List<GatewayTimelineMessage>,
    ): Boolean {
        val before = expected.session
        val target = before.target
        if (activeProfile != target.profileId || activeAccount != target.accountId || target in deletedTargets || sessionRevisions[target] != expected.revision) return false
        val current = _sessions.value.firstOrNull { it.target == target } ?: return false
        if (current != before) return false
        val key = RoundKey(target, before.generationId)
        val observed = replies[key]
        val userIndex = userMessages[key]?.let { id -> messages.indexOfLast { it.messageId == id && it.sender == "user" } } ?: -1
        val replyIndex = observed?.let { reply -> messages.indexOfLast { it.messageId == reply.messageId && it.sender == "assistant" } } ?: -1
        val first = when {
            userIndex >= 0 -> userIndex + 1
            replyIndex >= 0 -> replyIndex
            else -> messages.indexOfFirst { (it.timestamp ?: 0L) >= (startedAt[key] ?: Long.MAX_VALUE) }
        }
        val finalReply = if (first < 0) null else messages.drop(first).takeWhile { it.sender != "user" }
            .lastOrNull { it.sender == "assistant" && it.state.uppercase() in setOf("CONFIRMED", "COMPLETED", "STREAMING") }
        val snapshotCompleted = finalReply?.state?.uppercase() in setOf("CONFIRMED", "COMPLETED")
        val snapshotText = finalReply?.parts?.filterIsInstance<WireMessagePart.Text>()?.joinToString("\n") { it.text }
            ?.ifBlank { if (finalReply.parts.any { it is WireMessagePart.AttachmentRef }) "[附件回复]" else "" }
        val snapshotRevision = finalReply?.contentRevision
        val canApplyBody = finalReply != null && !snapshotText.isNullOrBlank() && when {
            observed == null -> true
            finalReply.messageId != observed.messageId -> replyIndex >= 0 && messages.indexOf(finalReply) >= replyIndex ||
                (finalReply.timestamp ?: 0L) > observed.occurredAt
            snapshotRevision != null && snapshotRevision != observed.revision -> snapshotRevision > observed.revision
            else -> snapshotCompleted && !observed.completed || snapshotText.length >= observed.text.length
        }
        if (canApplyBody) {
            replies[key] = ReplySnapshot(finalReply!!.messageId, snapshotText!!, finalReply.contentRevision ?: observed?.revision ?: 0L,
                maxOf(finalReply.timestamp ?: 0L, observed?.occurredAt ?: 0L), completed = snapshotCompleted)
        }
        if (currentGeneration == null) {
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
                    else replyDigest("${target.conversationId}\u0000$generation")
                flushMetadata("${target.profileId}\u0000${target.accountId}")
                // A later replay cannot promote this ended round back to active.
                if (!preferences.contains("realtime.terminal.$durable")) {
                    check(preferences.edit().putLong("realtime.terminal.$durable", now()).commit())
                }
            }
            val next = before.copy(status = outcome, latestReply = replies[key]?.text ?: before.latestReply, lastOutcome = outcome)
            if (next != before) update(next, terminal = false, force = true)
        } else {
            val (generation, remoteState) = currentGeneration
            val remoteKey = RoundKey(target, generation)
            // A lagging generation endpoint cannot reopen an observed terminal.
            if (terminalRounds.containsKey(remoteKey)) {
                if (canApplyBody) update(before.copy(latestReply = replies[key]?.text ?: before.latestReply), terminal = false, force = true)
                return true
            }
            val status = when (remoteState) {
                "queued" -> NativeConversationStatus.QUEUED
                "running" -> NativeConversationStatus.RUNNING
                else -> NativeConversationStatus.DISCONNECTED
            }
            activeRounds[target] = linkedMapOf(generation to status)
            reconciliationPending.remove(target)
            reconciliationDeadlines.remove(target)
            if (generation != before.generationId && userMessages[remoteKey] == null) {
                // The new task may already have written its final body before
                // this HTTP pass. Local adoption time is not its start time.
                // One new user after an exact known round anchor has a safe
                // owner; several unseen users cannot be assigned to generations.
                val anchorIndex = if (userIndex >= 0) userIndex else replyIndex
                val newerUsers = if (anchorIndex >= 0) messages.drop(anchorIndex + 1).filter { it.sender == "user" } else emptyList()
                if (newerUsers.size == 1) {
                    val user = newerUsers.single()
                    userMessages[remoteKey] = user.messageId
                    user.timestamp?.takeIf { it > 0L }?.let { startedAt.putIfAbsent(remoteKey, it) }
                    val body = messages.drop(messages.indexOf(user) + 1).takeWhile { it.sender != "user" }
                        .lastOrNull { it.sender == "assistant" && it.state.uppercase() in setOf("CONFIRMED", "COMPLETED", "STREAMING") }
                    val text = body?.parts?.filterIsInstance<WireMessagePart.Text>()?.joinToString("\n") { it.text }
                        ?.ifBlank { if (body.parts.any { it is WireMessagePart.AttachmentRef }) "[附件回复]" else "" }
                    if (body != null && !text.isNullOrBlank()) {
                        replies[remoteKey] = ReplySnapshot(body.messageId, text, body.contentRevision ?: 0L, body.timestamp ?: 0L,
                            completed = body.state.uppercase() in setOf("CONFIRMED", "COMPLETED"))
                    }
                }
            }
            val text = if (generation == before.generationId) replies[key]?.text ?: before.latestReply else replies[remoteKey]?.text.orEmpty()
            if (generation != before.generationId || before.status != status || text != before.latestReply) {
                update(before.copy(generationId = generation, status = status, latestReply = text, lastOutcome = null,
                    updatedAt = maxOf(before.updatedAt, now())), terminal = false)
            }
        }
        return true
    }

    @Synchronized fun accept(profileId: String, accountId: String, event: GatewayEvent) {
        if (activeProfile != profileId || activeAccount != accountId) return
        if (event.event !in EVENTS) return
        val body = runCatching { JsonFields.obj(Json.parse(event.data)) }.getOrNull() ?: return
        if (JsonFields.string(body, "accountId")?.let { it != accountId } == true) return
        val payload = JsonFields.obj(JsonFields.field(body, "payload")) ?: return
        val conversationId = JsonFields.string(payload, "conversationId")?.takeIf { it.isNotBlank() } ?: return
        val occurredAt = JsonFields.string(body, "occurredAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        val preferences = preferencesFor(profileId, accountId)
        val observerKey = "$profileId\u0000$accountId"
        // Construction persists the first-use cutoff before even the first frame
        // can publish a historical queued/delta/terminal projection.
        val observer = observers.getOrPut(observerKey) {
            ReplyCompletionObserver(preferences, profileId, accountId, ::complete, now)
        }
        val firstEnabled = preferences.getLong("first-enabled-at", 0L)
        if (occurredAt < now() - RETENTION || firstEnabled > 0L && occurredAt < firstEnabled - 300_000L) return
        val target = ReplyNotificationTarget(profileId, accountId, conversationId)
        val deletedKey = "realtime.deleted." + replyDigest(conversationId)
        if (event.event == "conversation.deleted" || event.event == "conversation.updated" && JsonFields.bool(payload, "deleted") == true) {
            retireTarget(target, occurredAt)
            return
        }
        if (event.event == "conversation.updated") return
        if (target in deletedTargets || preferences.contains(deletedKey)) return
        val generation = JsonFields.string(payload, "generationId")?.takeIf { it.isNotBlank() }
        val decoded = runCatching { GatewayEventDecoder.decode(event) }.getOrNull() ?: return
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
        val legacyStatusRound = statusEvent?.let { "legacy:" + replyDigest("legacy\u0000$conversationId\u0000${it.messageId}\u0000${it.clientMessageId.value}") }
        val round = generation ?: legacyStatusRound ?: previous?.generationId
        val durableKey = if (generation != null) replyDigest("$conversationId\u0000$generation")
            else round?.removePrefix("legacy:")
        val roundKey = RoundKey(target, round)
        val knownRound = startedAt.containsKey(roundKey)
        val terminal = terminalRounds[roundKey]
        val refinesRecoveredOutcome = knownRound && terminal == NativeConversationStatus.ENDED &&
            event.event in setOf("conversation.message.status", "conversation.generation.cancelled")
        val alreadyFinished = durableKey != null && (preferences.contains("done.$durableKey") || preferences.contains("realtime.terminal.$durableKey"))
        val seenKey = event.id?.takeIf { it.isNotBlank() }?.let { "realtime.event." + replyDigest(it) }
        val seen = seenKey != null && (metadata[observerKey]?.changes?.containsKey(seenKey) == true || preferences.contains(seenKey))
        val message = (decoded as? VerifiedConversationEvent.TimelineUpsert)?.message
        val revisionIdentity = when (decoded) {
            is VerifiedConversationEvent.TimelineUpsert -> "body\u0000$conversationId\u0000${decoded.message.id}"
            is VerifiedConversationEvent.MessageStatus -> "status\u0000$conversationId\u0000${decoded.messageId}"
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
        val olderPreview = message != null && previousReply != null && when {
            message.id != previousReply.messageId -> occurredAt < previousReply.occurredAt
            revision != null && revision > previousReply.revision -> false
            event.event == "conversation.message.completed" && !previousReply.completed -> false
            previousReply.completed && terminal != null && event.event == "conversation.message.delta" && revision == previousReply.revision -> true
            else -> occurredAt < previousReply.occurredAt
        }
        val staleUnknownRound = previous != null && occurredAt < previous.updatedAt && round != previous.generationId && !knownRound
        if (decoded is VerifiedConversationEvent.MessageStatus && staleRevision || staleUnknownRound) return
        if (message != null && previousRevision != null && revision != null && previousRevision > revision) return
        // Durable fences block replay after process death. In this process an
        // observed round may still receive a newer authoritative final body.
        if (!seen && (!alreadyFinished || knownRound && message != null || refinesRecoveredOutcome) && !staleRevision && !olderPreview) {
            if (statusEvent != null || event.event == "conversation.generation.cancelled" || message != null && isPublicAssistantPayload(payload)) {
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
            if (generation != null) activeRounds[target]?.keys?.removeAll { it.startsWith("legacy:") }
            val sameGeneration = previous != null && (round == null || previous.generationId == round)
            var next = previous ?: NativeConversationSession(target, round, sessionTitle(target), NativeConversationStatus.RUNNING, updatedAt = occurredAt)
            startedAt.putIfAbsent(roundKey, occurredAt)
            titleFor(target)?.trim()?.takeIf { it.isNotBlank() }?.let { next = next.copy(title = it.take(80)) }
            when (event.event) {
                "conversation.message.delta", "conversation.message.completed" -> {
                    val unambiguous = generation != null || previous?.generationId == null || previous.generationId.startsWith("legacy:")
                    if (message != null && unambiguous && isPublicAssistantPayload(payload)) {
                        val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.value }
                            .ifBlank { if (message.parts.any { it is MessagePart.Attachment }) "[附件回复]" else "" }
                        if (text.isNotBlank()) {
                            replies[roundKey] = ReplySnapshot(message.id, text, revision ?: 0L, occurredAt, event.event == "conversation.message.completed")
                            if (terminal == null && round != null) activeRounds.getOrPut(target) { linkedMapOf() }[round] = NativeConversationStatus.RUNNING
                            next = next.copy(generationId = round, status = terminal ?: NativeConversationStatus.RUNNING, latestReply = text,
                                lastOutcome = terminal, updatedAt = maxOf(next.updatedAt, occurredAt))
                            if (terminal != null) next = withRemainingRounds(next)
                            update(next, terminal = false, force = terminal != null || event.event == "conversation.message.completed")
                        }
                    }
                }
                "conversation.message.status" -> {
                    val status = decoded as? VerifiedConversationEvent.MessageStatus
                    if (status != null) {
                        userMessages[roundKey] = status.messageId
                        // Unidentified legacy deltas can acquire their safe owner
                        // when the first user-message status arrives.
                        if (generation == null && replies[roundKey] == null && previous?.generationId == null) {
                            replies[RoundKey(target, null)]?.let { replies[roundKey] = it }
                        }
                        when (status.status) {
                        AgentMessageStatus.QUEUED, AgentMessageStatus.DELIVERED -> {
                            if (terminal == null && (!sameGeneration || next.isRunning)) {
                                val startsNewLegacy = generation == null && previous?.generationId != legacyStatusRound
                                val rounds = activeRounds.getOrPut(target) { linkedMapOf() }
                                if (startsNewLegacy) rounds.clear()
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
                            // Show the authoritative task outcome immediately; only
                            // the observer's completed-body fence rings the final alert.
                            finish(next.copy(generationId = round, status = NativeConversationStatus.COMPLETED,
                                latestReply = replies[roundKey]?.text.orEmpty(), lastOutcome = NativeConversationStatus.COMPLETED,
                                updatedAt = maxOf(next.updatedAt, occurredAt)), durableKey, alert = false)
                        }
                    }
                    }
                }
                "conversation.generation.cancelled" -> finish(next.copy(generationId = round, status = NativeConversationStatus.CANCELLED,
                    latestReply = replies[roundKey]?.text.orEmpty(), updatedAt = maxOf(next.updatedAt, occurredAt)), durableKey)
            }
        }
        if (event.event != "conversation.message.delta") flushMetadata(observerKey)
        deliveryOccurredAt = occurredAt
        observer.accept(event)
    }

    @Synchronized private fun complete(target: ReplyNotificationTarget, generationId: String) {
        if (activeProfile != target.profileId || activeAccount != target.accountId) return
        val recovered = RoundKey(target, generationId) in recoveredRounds
        val previous = _sessions.value.firstOrNull { it.target == target }
        val terminalAt = deliveryOccurredAt.takeIf { it > 0L } ?: now()
        terminalRounds[RoundKey(target, generationId)] = NativeConversationStatus.COMPLETED
        val next = withRemainingRounds((previous ?: NativeConversationSession(target, generationId, sessionTitle(target), NativeConversationStatus.COMPLETED, updatedAt = terminalAt))
            .copy(generationId = generationId, status = NativeConversationStatus.COMPLETED,
                latestReply = replies[RoundKey(target, generationId)]?.text.orEmpty(), lastOutcome = NativeConversationStatus.COMPLETED,
                updatedAt = maxOf(previous?.updatedAt ?: 0L, terminalAt)))
        update(next, terminal = !recovered, force = true)
    }

    private fun finish(session: NativeConversationSession, durableKey: String?, alert: Boolean = true) {
        val preferences = preferencesFor(session.target.profileId, session.target.accountId)
        val key = durableKey ?: session.generationId?.let(::replyDigest)
        val recovered = terminalRounds[RoundKey(session.target, session.generationId)] == NativeConversationStatus.ENDED
        if (key != null && preferences.contains("realtime.terminal.$key") && !recovered) return
        // Publish only after the terminal commit also covers preceding buffered
        // event/revision guards; a crash in publish must not revive those deltas.
        flushMetadata("${session.target.profileId}\u0000${session.target.accountId}")
        if (key != null) check(preferences.edit().putLong("realtime.terminal.$key", session.updatedAt).commit())
        terminalRounds[RoundKey(session.target, session.generationId)] = session.status
        reconciliationPending += session.target
        reconciliationDeadlines[session.target] = now() + FINAL_RECONCILIATION_WINDOW
        reconciliationDueAt[session.target] = now()
        reconciliationAttemptCounts[session.target] = 0
        update(withRemainingRounds(session.copy(lastOutcome = session.status)),
            terminal = alert && RoundKey(session.target, session.generationId) !in recoveredRounds, force = true)
    }

    private fun withRemainingRounds(terminal: NativeConversationSession): NativeConversationSession {
        val rounds = activeRounds[terminal.target] ?: return terminal
        terminal.generationId?.let(rounds::remove)
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
        ?: "会话 ${target.conversationId.takeLast(12)}"

    companion object {
        private const val FINAL_RECONCILIATION_WINDOW = 120_000L
        private const val ACTIVE_RECONCILIATION_INTERVAL = 15_000L
        private const val RETENTION = 7L * 24 * 60 * 60 * 1000
        private const val MAINTENANCE_INTERVAL = 30L * 60 * 1000
        private val EVENTS = setOf("conversation.message.delta", "conversation.message.completed", "conversation.message.status", "conversation.generation.cancelled", "conversation.title.updated", "conversation.updated", "conversation.deleted")
    }
}
