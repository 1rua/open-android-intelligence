package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.http.requireData
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/** The platform collector owns its cursor independently of conversation rendering. */
internal suspend fun recoverPlatformSnapshot(
    http: GatewayHttpClient,
    syncCapabilities: suspend () -> Unit,
    acceptRequest: suspend (String) -> Unit,
    recoverLocalRequests: suspend () -> Unit,
): String {
    val response = http.execute(SignedGatewayRequest("GET","/open-android-intelligence/v2/sync/snapshot"))
    val data = response.requireData("SNAPSHOT_FAILED")
    val baseline = JsonFields.string(data,"baselineCursor") ?: error("SNAPSHOT_INVALID")
    check(baseline.matches(Regex("[A-Za-z0-9._~-]{1,128}"))) { "SNAPSHOT_INVALID" }
    val pending = JsonFields.field(data,"pendingDeviceRequests") as? JsonValue.JArray ?: error("SNAPSHOT_INVALID")
    val ids = pending.items.map { (it as? JsonValue.JString)?.value ?: error("SNAPSHOT_INVALID") }
    check(ids.all { it.matches(Regex("[A-Za-z0-9._~-]{1,128}")) }) { "SNAPSHOT_INVALID" }
    syncCapabilities()
    ids.distinct().forEach { acceptRequest(it) }
    recoverLocalRequests()
    // GatewayHttpClient may commit this cursor only after requests are journaled.
    return baseline
}
