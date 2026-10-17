package com.openandroidintelligence.mobile

import android.content.Context
import android.content.SharedPreferences
import com.openandroidintelligence.conversation.data.GatewayEventDecoder
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.events.GatewayEvent
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
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, DISCONNECTED;
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
) {
    private val _sessions = MutableStateFlow<List<NativeConversationSession>>(emptyList())
    val sessions = _sessions.asStateFlow()
    private val pending = mutableMapOf<ReplyNotificationTarget, Job>()
    private val lastPublished = mutableMapOf<ReplyNotificationTarget, Long>()
    private val observers = mutableMapOf<String, ReplyCompletionObserver>()
    private val activeRounds = mutableMapOf<ReplyNotificationTarget, LinkedHashMap<String, NativeConversationStatus>>()
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
        val interrupted = _sessions.value.filter { it.isRunning && it.status != NativeConversationStatus.DISCONNECTED }
        _sessions.value = _sessions.value.map { if (it.isRunning) it.copy(status = NativeConversationStatus.DISCONNECTED) else it }
        interrupted.forEach { publish(it.copy(status = NativeConversationStatus.DISCONNECTED), false) }
    }

    /** Logout/account switch cannot leave another account's ongoing bubble behind. */
    @Synchronized fun leave() {
        pending.values.forEach(Job::cancel)
        pending.clear()
        metadata.keys.toList().forEach(::flushMetadata)
        _sessions.value.filter { it.isRunning }.forEach { cancel(it.target) }
        _sessions.value = emptyList()
        activeRounds.clear()
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
        val key = "$profileId\u0000$accountId"
        observers.remove(key)
        metadata.remove(key)?.flushJob?.cancel()
        lastPrunedAt.remove(key)
    }

    /** Permission/preference recovery revives only active work, never old finals. */
    @Synchronized fun refreshActive() {
        pending.values.forEach(Job::cancel)
        pending.clear()
        _sessions.value.filter { it.isRunning && it.target.profileId == activeProfile && it.target.accountId == activeAccount }.forEach {
            publishNow(it, false)
        }
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
        val alreadyFinished = durableKey != null && preferences.contains("done.$durableKey")
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
        val olderPreview = message != null && previous != null && occurredAt < previous.updatedAt
        val staleUnknownRound = previous != null && occurredAt < previous.updatedAt && round != previous.generationId && activeRounds[target]?.containsKey(round) != true
        if (decoded is VerifiedConversationEvent.MessageStatus && staleRevision || staleUnknownRound) return
        if (message != null && previousRevision != null && revision != null && previousRevision > revision) return
        if (!seen && !alreadyFinished && !staleRevision && !olderPreview) {
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
            titleFor(target)?.trim()?.takeIf { it.isNotBlank() }?.let { next = next.copy(title = it.take(80)) }
            when (event.event) {
                "conversation.message.delta", "conversation.message.completed" -> {
                    val unambiguous = generation != null || previous?.generationId == null || previous.generationId.startsWith("legacy:")
                    if (message != null && unambiguous && isPublicAssistantPayload(payload) && (next.isRunning || !sameGeneration || next.status == NativeConversationStatus.COMPLETED)) {
                        val text = message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.value }.take(MAX_PREVIEW)
                            .ifBlank { if (message.parts.any { it is MessagePart.Attachment }) "[附件回复]" else "" }
                        if (text.isNotBlank()) {
                            if (round != null) activeRounds.getOrPut(target) { linkedMapOf() }[round] = NativeConversationStatus.RUNNING
                            next = next.copy(generationId = round, status = NativeConversationStatus.RUNNING, latestReply = text, lastOutcome = null, updatedAt = occurredAt)
                            update(next, terminal = false)
                        }
                    }
                }
                "conversation.message.status" -> {
                    val status = decoded as? VerifiedConversationEvent.MessageStatus
                    if (status != null) when (status.status) {
                        AgentMessageStatus.QUEUED, AgentMessageStatus.DELIVERED -> {
                            if (!sameGeneration || next.isRunning) {
                                val startsNewLegacy = generation == null && previous?.generationId != legacyStatusRound
                                val rounds = activeRounds.getOrPut(target) { linkedMapOf() }
                                if (startsNewLegacy) rounds.clear()
                                rounds[round!!] = if (status.status == AgentMessageStatus.QUEUED) NativeConversationStatus.QUEUED else NativeConversationStatus.RUNNING
                                val active = rounds.entries.firstOrNull { it.value == NativeConversationStatus.RUNNING } ?: rounds.entries.first()
                                next = next.copy(generationId = active.key, status = active.value,
                                    latestReply = if (startsNewLegacy) "" else next.latestReply, lastOutcome = null, updatedAt = occurredAt)
                                update(next, terminal = false)
                            }
                        }
                        AgentMessageStatus.FAILED -> finish(next.copy(generationId = round, status = NativeConversationStatus.FAILED, updatedAt = occurredAt), durableKey)
                        AgentMessageStatus.COMPLETED -> {
                            // Show the authoritative task outcome immediately; only
                            // the observer's completed-body fence rings the final alert.
                            update(withRemainingRounds(next.copy(generationId = round, status = NativeConversationStatus.COMPLETED,
                                lastOutcome = NativeConversationStatus.COMPLETED, updatedAt = occurredAt)), terminal = false)
                        }
                    }
                }
                "conversation.generation.cancelled" -> finish(next.copy(generationId = round, status = NativeConversationStatus.CANCELLED, updatedAt = occurredAt), durableKey)
            }
        }
        if (event.event != "conversation.message.delta") flushMetadata(observerKey)
        deliveryOccurredAt = occurredAt
        observer.accept(event)
    }

    @Synchronized private fun complete(target: ReplyNotificationTarget, generationId: String) {
        if (activeProfile != target.profileId || activeAccount != target.accountId) return
        val previous = _sessions.value.firstOrNull { it.target == target }
        val terminalAt = deliveryOccurredAt.takeIf { it > 0L } ?: now()
        val next = withRemainingRounds((previous ?: NativeConversationSession(target, generationId, sessionTitle(target), NativeConversationStatus.COMPLETED, updatedAt = terminalAt))
            .copy(generationId = generationId, status = NativeConversationStatus.COMPLETED, lastOutcome = NativeConversationStatus.COMPLETED, updatedAt = terminalAt))
        update(next, terminal = true)
    }

    private fun finish(session: NativeConversationSession, durableKey: String?) {
        val preferences = preferencesFor(session.target.profileId, session.target.accountId)
        val key = durableKey ?: session.generationId?.let(::replyDigest)
        if (key != null && preferences.contains("realtime.terminal.$key")) return
        // Publish only after the terminal commit also covers preceding buffered
        // event/revision guards; a crash in publish must not revive those deltas.
        flushMetadata("${session.target.profileId}\u0000${session.target.accountId}")
        if (key != null) check(preferences.edit().putLong("realtime.terminal.$key", session.updatedAt).commit())
        update(withRemainingRounds(session.copy(lastOutcome = session.status)), terminal = true)
    }

    private fun withRemainingRounds(terminal: NativeConversationSession): NativeConversationSession {
        val rounds = activeRounds[terminal.target] ?: return terminal
        terminal.generationId?.let(rounds::remove)
        if (rounds.isEmpty()) { activeRounds.remove(terminal.target); return terminal }
        val active = rounds.entries.firstOrNull { it.value == NativeConversationStatus.RUNNING } ?: rounds.entries.first()
        return terminal.copy(generationId = active.key, status = active.value)
    }

    private fun update(session: NativeConversationSession, terminal: Boolean) {
        val ordered = (_sessions.value.filterNot { it.target == session.target } + session).sortedByDescending { it.updatedAt }
        val retainedTerminals = ordered.filterNot { it.isRunning }.take(32).toSet()
        _sessions.value = ordered.filter { it.isRunning || it in retainedTerminals }
        pending.remove(session.target)?.cancel()
        val elapsed = now() - (lastPublished[session.target] ?: 0L)
        if (terminal || elapsed >= throttleMillis) publishNow(session, terminal)
        else pending[session.target] = scope.launch {
            delay((throttleMillis - elapsed).coerceAtLeast(1))
            synchronized(this@NativeConversationNotifications) {
                pending.remove(session.target)
                if (activeProfile == session.target.profileId && activeAccount == session.target.accountId) {
                    _sessions.value.firstOrNull { it.target == session.target }?.let { publishNow(it, false) }
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
        private const val MAX_PREVIEW = 4_000
        private const val RETENTION = 7L * 24 * 60 * 60 * 1000
        private const val MAINTENANCE_INTERVAL = 30L * 60 * 1000
        private val EVENTS = setOf("conversation.message.delta", "conversation.message.completed", "conversation.message.status", "conversation.generation.cancelled", "conversation.title.updated")
    }
}
