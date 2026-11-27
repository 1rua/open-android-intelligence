package com.openandroidintelligence.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Publish each healthy account immediately; a slow account owns only its own discovery budget. */
internal fun <Profile, Connection : Any> independentConnectionDiscovery(
    profiles: List<Profile>,
    timeoutMillis: Long = 15_000L,
    discover: suspend (Profile) -> Connection?,
    unavailable: (Profile) -> Unit,
): Flow<Connection> = channelFlow {
    require(timeoutMillis > 0)
    profiles.forEach { profile ->
        launch {
            val connection = try {
                withTimeoutOrNull(timeoutMillis) { discover(profile) }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                null // One replaced account does not cancel sibling discovery.
            } catch (_: Exception) {
                null
            }
            if (connection != null) send(connection) else unavailable(profile)
        }
    }
}
