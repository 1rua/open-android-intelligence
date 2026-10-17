package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.LocalSendAtom
import com.openandroidintelligence.conversation.model.LocalSubmissionState
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live submissions belong to the controller, independently of its selected conversation. */
internal class WorkbenchActiveWork {
    private data class Submission(val conversationId: String?, val generationId: String? = null,
        val started: Boolean = false, val inFlight: Boolean = false)
    private val submissions = linkedMapOf<String, Submission>()
    private val finishedGenerations = linkedSetOf<Pair<String, String>>()
    private val _active = MutableStateFlow(false)
    val active = _active.asStateFlow()

    @Synchronized fun begin(clientId: String, conversationId: String?) {
        submissions.putIfAbsent(clientId, Submission(conversationId))
        publish()
    }

    @Synchronized fun started(clientId: String) {
        submissions[clientId]?.let { submissions[clientId] = it.copy(started = true, inFlight = true) }
    }

    @Synchronized fun cancelPending(clientId: String) {
        if (submissions[clientId]?.started == false) submissions.remove(clientId)
        publish()
    }

    @Synchronized fun accepted(clientId: String, conversationId: String, generationId: String?) {
        val previous = submissions[clientId] ?: return
        if (previous.conversationId != null && previous.conversationId != conversationId) return
        val generation = generationId ?: previous.generationId
        if (generation != null && (conversationId to generation) in finishedGenerations) submissions.remove(clientId)
        else submissions[clientId] = previous.copy(conversationId = conversationId, generationId = generation)
        publish()
    }

    @Synchronized fun atomChanged(atom: LocalSendAtom) {
        val key = atom.clientMessageId.value
        val previous = submissions[key] ?: return
        if (previous.conversationId != null && previous.conversationId != atom.conversationId.value) return
        if (atom.isDeleted || (atom.submissionState == LocalSubmissionState.FAILED && !previous.inFlight) ||
            atom.status in setOf(AgentMessageStatus.COMPLETED, AgentMessageStatus.FAILED)) submissions.remove(key)
        else submissions[key] = previous.copy(conversationId = atom.conversationId.value)
        publish()
    }

    @Synchronized fun finishSubmission(clientId: String, atom: LocalSendAtom?) {
        submissions[clientId]?.let { submissions[clientId] = it.copy(inFlight = false) }
        if (atom == null) submissions.remove(clientId) else atomChanged(atom)
        publish()
    }

    @Synchronized fun terminal(conversationId: String, generationId: String) {
        finishedGenerations += conversationId to generationId
        while (finishedGenerations.size > 4096) finishedGenerations.remove(finishedGenerations.first())
        submissions.entries.removeAll { it.value.conversationId == conversationId && it.value.generationId == generationId }
        publish()
    }

    @Synchronized fun settledClient(conversationId: String, clientId: String) {
        if (submissions[clientId]?.conversationId == conversationId) submissions.remove(clientId)
        publish()
    }

    @Synchronized fun clear() {
        submissions.clear()
        finishedGenerations.clear()
        publish()
    }

    private fun publish() { _active.value = submissions.isNotEmpty() }
}
