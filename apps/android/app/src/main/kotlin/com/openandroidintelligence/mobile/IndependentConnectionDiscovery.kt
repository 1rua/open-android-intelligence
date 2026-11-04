package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.http.requireData
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

/** Preserve a verified push connection when only its independent device-recovery lookup is unavailable. */
internal suspend fun optionalDevicePairing(
    http: com.openandroidintelligence.gateway.http.GatewayHttpClient,
    isCurrent: () -> Boolean,
    timeoutMillis: Long = 5_000L,
): com.openandroidintelligence.gateway.schema.JsonValue.JObject? {
    val response = try {
        withTimeoutOrNull(timeoutMillis) {
            http.execute(com.openandroidintelligence.gateway.http.SignedGatewayRequest(
                "GET", "/open-android-intelligence/v2/pairings/current", beforeSend = {
                    check(isCurrent()) { "PUSH_ACCOUNT_LOGGED_OUT" }
                }))
        }
    } catch (failure: java.io.IOException) {
        if (com.openandroidintelligence.gateway.http.GatewayConnectionSecurity.isSecurityFailure(failure)) throw failure
        null
    }
    if (response == null || response.status in 500..599) return null
    return response.requireData("DEVICE_PAIRING_LOOKUP_FAILED")
}
