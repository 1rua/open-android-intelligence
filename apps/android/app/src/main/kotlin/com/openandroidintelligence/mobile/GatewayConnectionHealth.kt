package com.openandroidintelligence.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** HTTP observations do not imply Platform or Native event-stream availability. */
enum class CoreHttpReachability { UNKNOWN, REACHABLE, UNREACHABLE, SECURITY_BLOCKED }

data class CoreHttpHealth(
    val reachability: CoreHttpReachability = CoreHttpReachability.UNKNOWN,
    val httpStatus: Int? = null,
    val observedAtMillis: Long = 0L,
    val failureCode: String? = null,
)

internal enum class PlatformStreamWakeReason {
    INITIAL, RECOVERY_SIGNAL, FOREGROUND, NETWORK_CHANGED, CREDENTIAL_CHANGED, USER_RETRY,
}

/** Retain the predecessor even when another route change cancels this successor. */
internal fun CoroutineScope.launchAfterPlatformStream(
    previous: Job?,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(start = CoroutineStart.UNDISPATCHED) {
    withContext(NonCancellable) { previous?.join() }
    ensureActive()
    block()
}
