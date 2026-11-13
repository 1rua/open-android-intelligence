package com.openandroidintelligence.mobile

/** A real operation outcome, not a timer or an animation completion. */
sealed interface LocalOperationState {
    data object Idle : LocalOperationState
    data object Running : LocalOperationState
    data object Completed : LocalOperationState
    data class Failed(val message: String, val remoteCompleted: Boolean = false) : LocalOperationState
}
