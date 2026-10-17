package com.openandroidintelligence.mobile

import android.content.Context
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
) {
    private val _sessions = MutableStateFlow<List<NativeConversationSession>>(emptyList())
    val sessions = _sessions.asStateFlow()
    private val pending = mutableMapOf<ReplyNotificationTarget, Job>()
    private val lastPublished = mutableMapOf<ReplyNotificationTarget, Long>()
    private val observers = mutableMapOf<String, ReplyCompletionObserver>()
    private val activeRounds = mutableMapOf<ReplyNotificationTarget, LinkedHashMap<String, NativeConversationStatus>>()
    private var activeProfile: String? = null
    private var activeAccount: String? = null
    private var deliveryOccurredAt: Long = 0L

    @Synchronized fun bind(profileId: String, accountId: String) {
        if (activeProfile == profileId && activeAccount == accountId) return
        pending.values.forEach(Job::cancel)
        pending.clear()
        activeRounds.clear()
        _sessions.value = emptyList()
        activeProfile = profileId
        activeAccount = accountId
    }

    @Synchronized fun disconnect() {
        pending.values.forEach(Job::cancel)
        pending.clear()
        _sessions.value = _sessions.value.map { if (it.isRunning) it.copy(status = NativeConversationStatus.DISCONNECTED) else it }
        _sessions.value.filter { it.isRunning }.forEach { publish(it, false) }
        activeProfile = null
        activeAccount = null
    }

    @Synchronized fun eraseAccount(profileId: String, accountId: String) {
        _sessions.value.filter { it.target.profileId == profileId && it.target.accountId == accountId }.forEach {
            pending.remove(it.target)?.cancel()
        }
        _sessions.value = _sessions.value.filterNot { it.target.profileId == profileId && it.target.accountId == accountId }
        activeRounds.keys.removeAll { it.profileId == profileId && it.accountId == accountId }
        observers.remove("$profileId\u0000$accountId")
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
        val preferences = replyCompletionPreferences(context, profileId, accountId)
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
        val observerKey = "$profileId\u0000$accountId"
        val observer = observers.getOrPut(observerKey) {
            ReplyCompletionObserver(preferences, profileId, accountId, ::complete, now)
        }
        val previous = _sessions.value.firstOrNull { it.target == target }
        val statusEvent = decoded as? VerifiedConversationEvent.MessageStatus
        val legacyStatusRound = statusEvent?.let { "legacy:" + replyDigest("legacy\u0000$conversationId\u0000${it.messageId}\u0000${it.clientMessageId.value}") }
        val round = generation ?: legacyStatusRound ?: previous?.generationId
        val durableKey = if (generation != null) replyDigest("$conversationId\u0000$generation")
            else round?.removePrefix("legacy:")
        val alreadyFinished = durableKey != null && preferences.contains("done.$durableKey")
        val seenKey = event.id?.takeIf { it.isNotBlank() }?.let { "realtime.event." + replyDigest(it) }
        val seen = seenKey != null && preferences.contains(seenKey)
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
        val previousRevision = revisionKey?.let { preferences.getLong(it, -1L) }
        val staleRevision = previousRevision != null && revision != null &&
            if (decoded is VerifiedConversationEvent.MessageStatus) previousRevision >= revision else previousRevision > revision
        val olderPreview = message != null && previous != null && occurredAt < previous.updatedAt
        val staleUnknownRound = previous != null && occurredAt < previous.updatedAt && round != previous.generationId && activeRounds[target]?.containsKey(round) != true
        if (decoded is VerifiedConversationEvent.MessageStatus && staleRevision || staleUnknownRound) return
        if (message != null && revisionKey != null && revision != null && preferences.getLong(revisionKey, -1L) > revision) return
        if (!seen && !alreadyFinished && !staleRevision && !olderPreview) {
            if (seenKey != null || revisionKey != null) {
                val edit = preferences.edit()
                if (seenKey != null) edit.putLong(seenKey, occurredAt)
                if (revisionKey != null && revision != null) edit.putLong(revisionKey, revision).putLong(revisionKey.replace("realtime.revision.", "realtime.revisionTime."), occurredAt)
                preferences.all.filter { (key, value) ->
                    (key.startsWith("realtime.event.") || key.startsWith("realtime.terminal.") || key.startsWith("realtime.revisionTime.")) && value is Long && value < now() - RETENTION
                }.keys.forEach { key ->
                    edit.remove(key)
                    if (key.startsWith("realtime.revisionTime.")) edit.remove(key.replace("realtime.revisionTime.", "realtime.revision."))
                }
                // Progress is replayable and silent; avoid fsync per token on Main.
                // The terminal observer commits the final durable fence.
                edit.apply()
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
        val preferences = replyCompletionPreferences(context, session.target.profileId, session.target.accountId)
        val key = durableKey ?: session.generationId?.let(::replyDigest)
        if (key != null && preferences.contains("realtime.terminal.$key")) return
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

    private fun sessionTitle(target: ReplyNotificationTarget): String = titleFor(target)?.trim()?.take(80)?.takeIf { it.isNotBlank() }
        ?: "会话 ${target.conversationId.takeLast(12)}"

    companion object {
        private const val MAX_PREVIEW = 4_000
        private const val RETENTION = 7L * 24 * 60 * 60 * 1000
        private val EVENTS = setOf("conversation.message.delta", "conversation.message.completed", "conversation.message.status", "conversation.generation.cancelled", "conversation.title.updated")
    }
}
