package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.agents.AgentSessionException
import com.openandroidintelligence.gateway.agents.CoreAgent
import com.openandroidintelligence.gateway.agents.NativeAgentStreamEvent
import com.openandroidintelligence.gateway.agents.NativeAgentMessage
import com.openandroidintelligence.gateway.agents.NativeAgentSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class NativeAgentsState(
    /** Null while probing; false means this is an older Gateway deployment. */
    val supported: Boolean? = null,
    val agents: List<CoreAgent> = emptyList(), val selectedAgentId: String? = null,
    /** 当前 selectedAgentId 是否已在一次成功的列表刷新中被服务端确认。 */
    val selectionConfirmed: Boolean = false,
    val sessions: List<NativeAgentSession> = emptyList(), val selectedSessionId: String? = null,
    val messages: List<NativeAgentMessage> = emptyList(), val draft: String = "",
    val streamingText: String = "", val streamCompleted: Boolean = false,
    val busy: Boolean = false, val sending: Boolean = false,
    val generationId: String? = null, val cancelRequested: Boolean = false, val cancelled: Boolean = false,
    val loadingSessions: Boolean = false, val loadingHistory: Boolean = false,
    val error: String? = null,
) {
    val agent: CoreAgent? get() = agents.firstOrNull { it.id == selectedAgentId }
    val session: NativeAgentSession? get() = sessions.firstOrNull { it.id == selectedSessionId }
}

/** Session selection owns no pairing, permission, plugin or device-connection state. */
class NativeAgentsController(
    private val client: AgentSessionClient, parentScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 用户上次选择的原生 Agent；重连/重建后据此恢复，避免总是回落到列表第一项。 */
    private val lastSelectedAgentId: String? = null,
    /** 上次选择在列表中已不存在时的回调，宿主可用于提示。 */
    private val onSelectionLost: ((String) -> Unit)? = null,
) {
    private val owned = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + owned)
    private val _state = MutableStateFlow(NativeAgentsState(selectedAgentId = lastSelectedAgentId))
    private val revision = AtomicLong()
    val state = _state.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                refreshNow()
                if (_state.value.supported == false) break
                delay(10_000)
            }
        }
    }

    fun refresh() { scope.launch { refreshNow() } }
    fun editDraft(value: String) { _state.update { it.copy(draft = value) } }
    fun dismissError() { _state.update { it.copy(error = null) } }
    fun close() { revision.incrementAndGet(); owned.cancel(); _state.value = NativeAgentsState() }

    private suspend fun refreshNow() {
        if (_state.value.busy) return
        val expectedRevision = revision.get()
        val agents = try { withContext(ioDispatcher) { client.agents() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (revision.get() != expectedRevision) return
            if (failure is AgentSessionException && (failure.status == 404 || failure.code == "UNSUPPORTED")) {
                _state.update { it.copy(supported = false) }
            } else _state.update { it.copy(supported = true, error = errorCode(failure)) }
            return
        }
        try {
            if (_state.value.busy || revision.get() != expectedRevision) return
            // 选择判定放进 update 内部并以 revision 复核：任何并发 selectAgent
            // 都会先推进 revision，本次旧结果就不能再覆盖用户刚做的选择。
            var lostSelection: String? = null
            _state.update { current ->
                if (current.busy || revision.get() != expectedRevision) return@update current
                // 列表不含当前选择时保持选择不动（Agent 可能只是暂时离线/列表波动），
                // 绝不静默回落到列表第一项；仅当当前选择是"恢复的旧值且从未被本会话
                // 确认过"时，才清除并通知宿主。
                val requested = current.selectedAgentId
                val known = requested != null && agents.any { it.id == requested }
                if (requested != null && !known && !current.selectionConfirmed) {
                    lostSelection = requested
                    return@update current.copy(supported = true, agents = agents, selectedAgentId = null, error = null)
                }
                if (requested == null) {
                    // 首次发现列表且用户尚无任何选择：以列表第一项作为默认值。
                    // 一旦选定即视为已确认，后续列表波动不再改写它。
                    return@update current.copy(supported = true, agents = agents,
                        selectedAgentId = agents.firstOrNull()?.id, selectionConfirmed = true, error = null)
                }
                current.copy(supported = true, agents = agents,
                    selectionConfirmed = current.selectionConfirmed || known, error = null)
            }
            lostSelection?.let { onSelectionLost?.invoke(it) }
            refreshSessions()
            refreshHistory()
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) {
            if (revision.get() == expectedRevision) _state.update { it.copy(error = errorCode(failure)) }
        }
    }

    fun selectAgent(id: String) {
        val current = _state.value
        if (current.busy || current.selectedAgentId == id || current.agents.none { it.id == id }) return
        revision.incrementAndGet()
        _state.update { it.copy(selectedAgentId = id, selectionConfirmed = true, sessions = emptyList(), selectedSessionId = null,
            messages = emptyList(), draft = "", streamingText = "", loadingSessions = false, loadingHistory = false, error = null) }
        scope.launch { safely { refreshSessions() } }
    }

    fun selectSession(id: String) {
        val current = _state.value
        if (current.busy || current.selectedSessionId == id || current.sessions.none { it.id == id }) return
        revision.incrementAndGet()
        _state.update { it.copy(selectedSessionId = id, messages = emptyList(), draft = "", streamingText = "", loadingSessions = false, loadingHistory = false, error = null) }
        scope.launch { safely { refreshHistory() } }
    }

    fun leaveSession() {
        if (_state.value.busy) return
        revision.incrementAndGet()
        _state.update { it.copy(selectedSessionId = null, messages = emptyList(), draft = "", streamingText = "", loadingSessions = false, loadingHistory = false, error = null) }
    }

    private suspend fun refreshSessions() {
        val agent = _state.value.agent ?: return
        if (!agent.capabilities.list) return
        val expectedRevision = revision.get()
        _state.update { it.copy(loadingSessions = true) }
        try {
            val sessions = withContext(ioDispatcher) { client.sessions(agent) }
            _state.update { current ->
                if (current.selectedAgentId != agent.id || revision.get() != expectedRevision) current
                else {
                    val selected = current.selectedSessionId?.takeIf { id -> sessions.any { it.id == id } }
                    current.copy(sessions = sessions, selectedSessionId = selected,
                        messages = if (selected == null) emptyList() else current.messages)
                }
            }
        } finally {
            if (revision.get() == expectedRevision) _state.update { it.copy(loadingSessions = false) }
        }
    }

    private suspend fun refreshHistory() {
        val current = _state.value
        val agent = current.agent ?: return
        val sessionId = current.selectedSessionId ?: return
        if (!agent.capabilities.history) return
        val expectedRevision = revision.get()
        _state.update { it.copy(loadingHistory = true) }
        try {
            val messages = withContext(ioDispatcher) { client.history(agent, sessionId) }
            _state.update { if (it.selectedAgentId == agent.id && it.selectedSessionId == sessionId && !it.sending && revision.get() == expectedRevision)
                it.copy(messages = messages, streamingText = "") else it }
        } finally {
            if (revision.get() == expectedRevision) _state.update { it.copy(loadingHistory = false) }
        }
    }

    fun createSession() = operate { agent, _ ->
        val created = withContext(ioDispatcher) { client.create(agent) }
        refreshSessions()
        _state.update { it.copy(selectedSessionId = created.id, messages = emptyList(), draft = "") }
        refreshHistory()
    }

    fun renameSession(title: String) = operate(requireSession = true) { agent, id ->
        withContext(ioDispatcher) { client.rename(agent, requireNotNull(id), title) }
        refreshSessions()
    }

    fun archiveSession() = operate(requireSession = true) { agent, id ->
        withContext(ioDispatcher) { client.archive(agent, requireNotNull(id)) }
        refreshSessions()
    }

    fun deleteSession() = operate(requireSession = true) { agent, id ->
        withContext(ioDispatcher) { client.delete(agent, requireNotNull(id)) }
        _state.update { it.copy(selectedSessionId = null, messages = emptyList(), draft = "") }
        refreshSessions()
    }

    fun cancelGeneration() {
        val current = _state.value
        val agent = current.agent ?: return
        val id = current.selectedSessionId ?: return
        val generationId = current.generationId ?: return
        if (current.cancelRequested) return
        _state.update { it.copy(cancelRequested = true) }
        scope.launch {
            try { safely {
                val receipt = withContext(ioDispatcher) { client.cancel(agent, id, generationId) }
                _state.update { if (it.generationId == generationId) it.copy(
                    cancelled = receipt.outcome == "cancelled",
                    error = if (receipt.outcome == "outcome_unknown") "OUTCOME_UNKNOWN" else it.error) else it }
            } } finally { _state.update { it.copy(cancelRequested = false) } }
        }
    }

    fun send() {
        val current = _state.value
        if (current.draft.isBlank() || current.busy || current.selectedSessionId == null) return
        val text = current.draft
        operate(requireSession = true, sending = true) { agent, id ->
            if (agent.kind != "hermes") _state.update { it.copy(generationId = "local_${java.util.UUID.randomUUID()}") }
            withContext(ioDispatcher) {
                val result = client.send(agent, requireNotNull(id), text, onEvent = { event ->
                    if (event is NativeAgentStreamEvent.Generation) _state.update { it.copy(generationId = event.generationId) }
                }) { delta ->
                    _state.update { if (it.cancelled) it else it.copy(streamingText = it.streamingText + delta) }
                }
                _state.update { it.copy(cancelled = it.cancelled || result.outcome == "cancelled") }
            }
            _state.update { it.copy(draft = "", sending = false, streamCompleted = !it.cancelled) }
            refreshHistory()
            refreshSessions()
        }
    }

    private fun operate(requireSession: Boolean = false, sending: Boolean = false,
        action: suspend (CoreAgent, String?) -> Unit) {
        val current = _state.value
        val agent = current.agent ?: return
        if (current.busy || (requireSession && current.selectedSessionId == null)) return
        revision.incrementAndGet()
        _state.update { it.copy(busy = true, sending = sending, streamingText = "", streamCompleted = false,
            generationId = null, cancelRequested = false, cancelled = false,
            loadingSessions = false, loadingHistory = false, error = null) }
        scope.launch {
            try { safely { action(agent, current.selectedSessionId) } }
            finally { _state.update { it.copy(busy = false, sending = false) } }
        }
    }

    private suspend fun safely(action: suspend () -> Unit) {
        val expectedRevision = revision.get()
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (owned.isActive && revision.get() == expectedRevision) _state.update { it.copy(error = errorCode(failure)) }
        }
    }

    private fun errorCode(failure: Exception): String =
        (failure as? AgentSessionException)?.code?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) }
            ?: "AGENT_REQUEST_FAILED"
}
