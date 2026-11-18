package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.GenerationSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 取同帧事实与发布必须串行，旧读不能越过较新的发布；锁序为发布器到投影。 */
internal class RemoteGenerationState {
    private val lock = Any()
    private var closed = false
    private val current = MutableStateFlow(GenerationSnapshot(null, GenerationState.IDLE))
    val snapshot = current.asStateFlow()
    private val currentId = MutableStateFlow<String?>(null)
    val generationId = currentId.asStateFlow()
    private val currentState = MutableStateFlow(GenerationState.IDLE)
    val generationState = currentState.asStateFlow()

    fun publish(read: () -> GenerationSnapshot) = synchronized(lock) {
        if (!closed) update(read())
    }
    fun close() = synchronized(lock) {
        closed = true
        update(GenerationSnapshot(null, GenerationState.IDLE))
    }
    private fun update(value: GenerationSnapshot) {
        currentId.value = value.generationId
        currentState.value = value.state
        current.value = value
    }
}
