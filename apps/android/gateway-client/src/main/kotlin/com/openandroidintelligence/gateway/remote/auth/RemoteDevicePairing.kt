package com.openandroidintelligence.gateway.remote.auth

import com.openandroidintelligence.gateway.remote.api.RemoteAuthCodec
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.network.RemoteServerApi
import com.openandroidintelligence.gateway.remote.protocol.RemoteBase64Url
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import com.southernstorm.noise.protocol.Noise
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RemotePairingQr(val ticket: String?, val session: RemotePairingSession?) {
    override fun toString() = "RemotePairingQr(秘密=已隐藏)" // i18n-ignore: 仅内部诊断脱敏文本，不作为界面文案
}
class RemotePairingSession(val value: JsonValue.JObject) {
    val id = value.requiredText("sessionId")
    val expiresAt = value.longValue("expiresAt")
    val host = value.value("host").objectValue()
    val hostId = host.requiredText("deviceId")
    val hostName = host.requiredText("name")
    val hostKey = pairingPublicKey(host.requiredText("identityKey"))
    val serverKey = pairingPublicKey(value.requiredText("serverPublicKey"))
    val scanSecret = value.requiredText("scanSecret")
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256").digest(hostKey).hex()
    init {
        check(value.fields.all { it.first in setOf("v", "sessionId", "expiresAt", "host", "serverPublicKey", "scanSecret", "type", "serverUrl") }) { "INVALID_MESSAGE" }
        check(host.fields.all { it.first in setOf("deviceId", "name", "role", "platform", "identityKey", "clientVersion", "harnessVersion") }) { "INVALID_MESSAGE" }
        check(value.longValue("v") == 1L && UUID.fromString(id).toString() == id && host.requiredText("role") == "host") { "INVALID_MESSAGE" }
        RemoteDeviceId(hostId)
        check(hostName.length <= 128 && expiresAt > 0)
        pairingKey(scanSecret)
    }
    override fun toString() = "RemotePairingSession(秘密=已隐藏)" // i18n-ignore: 仅内部诊断脱敏文本，不作为界面文案
}
class RemotePairingImport(val sessionId: String, val accountId: String, val credential: RemoteRoleCredential, val epoch: Long, val loginAttemptId: String)
class RemotePairingStatus(val state: String, val verificationCode: String? = null, val imported: RemotePairingImport? = null,
    val failureReason: String? = null)

/** 官方 dsh-pairing-v1 手机侧；只调用 claim/mobile 接口，从不替电脑确认验证码。 */
class RemoteDevicePairing(
    private val persistence: RemoteIdentityPersistence,
    private val api: RemoteServerApi = RemoteServerApi(RemoteServerApi.DEFAULT_ORIGIN),
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Pending(val session: RemotePairingSession, val identity: RemoteStaticIdentity, val rawClaim: String, val epoch: Long, val loginAttemptId: String) {
        override fun toString() = "Pending(配对秘密=已隐藏)" // i18n-ignore: 仅内部诊断脱敏文本，不作为界面文案
    }
    fun parseQr(raw: String): RemotePairingQr {
        check(raw.toByteArray().size <= 8192) { "INVALID_MESSAGE" }
        if (raw.startsWith("{")) {
            val row = RemoteWireJson.decode(raw.encodeToByteArray()).objectValue()
            check(row.requiredText("type") == "dsh.remote.pairing" && row.requiredText("serverUrl") == api.origin) { "REMOTE_ORIGIN_MISMATCH" }
            return RemotePairingQr(null, validSession(row))
        }
        val uri = URI(raw)
        check(uri.scheme == "https" && uri.host == "dsh.r2049.cn" && uri.port == -1 && uri.rawPath == "/pair" &&
            uri.rawUserInfo == null && uri.rawQuery == null && Regex("v1\\.[A-Za-z0-9_-]{43}").matches(uri.rawFragment.orEmpty())) { "INVALID_MESSAGE" }
        val ticket = uri.rawFragment.removePrefix("v1.")
        pairingKey(ticket)
        return RemotePairingQr(ticket, null)
    }
    suspend fun resolve(qr: RemotePairingQr): RemotePairingSession {
        qr.session?.let { return validSession(it.value) }
        val ticket = requireNotNull(qr.ticket)
        val row = api.pairingRequest("/api/v1/device-pairing/resolve", Json.canonical(Json.of(mapOf("ticket" to ticket)))).objectValue()
        check(row.requiredText("scanSecret") == ticket) { "PAIRING_SCAN_INVALID" }
        return validSession(row)
    }
    private fun validSession(row: JsonValue.JObject): RemotePairingSession = RemotePairingSession(row).also {
        check(it.expiresAt > now() && it.expiresAt <= now() + 240_000L) { "PAIRING_CLOSED" }
    }
    fun prepareClaim(session: RemotePairingSession, epoch: Long, loginAttemptId: String): Pending {
        check(session.expiresAt > now()) { "PAIRING_CLOSED" }
        val identity = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        check(identity.deviceId.value != session.hostId && !identity.publicKey.contentEquals(session.hostKey)) { "PAIRING_DEVICE_CONFLICT" }
        val raw = Json.canonical(Json.of(mapOf("scanSecret" to session.scanSecret, "device" to mapOf(
            "deviceId" to identity.deviceId.value, "name" to androidDeviceName(), "role" to "client", "platform" to "android",
            "identityKey" to RemoteBase64Url.encode(identity.publicKey)))))
        return Pending(session, identity, raw, epoch, loginAttemptId).also(::save)
    }
    private fun androidDeviceName(): String = "OAI Android"
    suspend fun claim(pending: Pending): RemotePairingStatus {
        requireRecoverable(pending)
        val row = send(pending, "claim", pending.rawClaim)
        check(row.requiredText("state") == "claimed" && row.longValue("expiresAt") == pending.session.expiresAt) { "INVALID_MESSAGE" }
        val code = row.requiredText("verificationCode")
        check(Regex("[0-9]{8}").matches(code)) { "INVALID_MESSAGE" }
        return RemotePairingStatus("claimed", verificationCode = code)
    }
    suspend fun status(pending: Pending): RemotePairingStatus {
        requireRecoverable(pending)
        val row = send(pending, "mobile/status", "{}")
        check(row.longValue("v") == 1L && row.requiredText("sessionId") == pending.session.id &&
            row.longValue("expiresAt") == pending.session.expiresAt) { "INVALID_MESSAGE" }
        assertDevice(row.value("host").objectValue(), pending.session.hostId, "host", pending.session.hostKey)
        val mobile = row.value("mobile")
        if (mobile != JsonValue.JNull) assertDevice(mobile.objectValue(), pending.identity.deviceId.value, "client", pending.identity.publicKey)
        val state = row.requiredText("state")
        check(state in setOf("created", "claimed", "approved", "completed", "failed", "cancelled", "expired")) { "INVALID_MESSAGE" }
        if (state != "completed") return RemotePairingStatus(state, failureReason = row.optionalText("failureReason"))
        val credential = row.value("credentials").objectValue()
        assertDevice(credential.value("device").objectValue(), pending.identity.deviceId.value, "client", pending.identity.publicKey)
        check(mobile != JsonValue.JNull) { "INVALID_MESSAGE" }
        check(credential.value("inboundOnly") == JsonValue.JBool(false) &&
            credential.value("pairingRestricted") is JsonValue.JBool &&
            credential.requiredText("accountKind") in setOf("standard", "device_only")) { "INVALID_MESSAGE" }
        val tokens = RemoteAuthCodec.parseTokenPair(credential.value("tokens"))
        check(tokens.accessTokenExpiresAt > now() && tokens.refreshTokenExpiresAt > now()) { "TOKEN_EXPIRED" }
        val imported = RemoteRoleCredential(pending.identity, tokens, listOf(RemotePeerPin(RemoteRole.HOST,
            RemoteDeviceId(pending.session.hostId), pending.session.hostKey)))
        return RemotePairingStatus(state, imported = RemotePairingImport(pending.session.id, credential.requiredText("account"), imported, pending.epoch, pending.loginAttemptId))
    }
    suspend fun cancel(pending: Pending) { send(pending, "mobile/cancel", "{}") }
    private suspend fun send(pending: Pending, action: String, raw: String): JsonValue.JObject {
        val path = "/api/v1/device-pairing/sessions/${pending.session.id}/$action"
        val secret = pending.identity.privateKeyCopy()
        return try { api.pairingRequest(path, raw, proof(secret, pending.session.serverKey, pending.session.id, path, raw)).objectValue() }
        finally { secret.fill(0) }
    }
    private fun requireRecoverable(pending: Pending) { check(pending.session.expiresAt + 300_000L > now()) { "PAIRING_CLOSED" } }
    fun restore(): Pending? {
        val raw = persistence.read(KEY) ?: return null
        return try {
            val row = RemoteWireJson.decode(raw).objectValue()
            check(row.requiredText("origin") == api.origin) { "REMOTE_ORIGIN_MISMATCH" }
            val session = RemotePairingSession(row.value("session").objectValue())
            val privateKey = pairingKey(row.requiredText("privateKey"))
            val identity = try { RemoteStaticIdentity(RemoteDeviceId(row.requiredText("deviceId")), privateKey,
                pairingKey(row.requiredText("publicKey")), 1) } finally { privateKey.fill(0) }
            Pending(session, identity, row.requiredText("claim"), row.longValue("epoch"), row.requiredText("loginAttemptId")).also(::requireRecoverable)
        } finally { raw.fill(0) }
    }
    private fun save(pending: Pending) {
        val key = pending.identity.privateKeyCopy()
        try { persistence.writeAtomic(KEY, Json.canonical(Json.of(mapOf("origin" to api.origin, "session" to pending.session.value,
            "deviceId" to pending.identity.deviceId.value, "privateKey" to RemoteBase64Url.encode(key),
            "publicKey" to RemoteBase64Url.encode(pending.identity.publicKey), "claim" to pending.rawClaim, "epoch" to pending.epoch, "loginAttemptId" to pending.loginAttemptId))).encodeToByteArray()) }
        finally { key.fill(0) }
    }
    fun clear() = persistence.clear(KEY)
    companion object {
        private const val KEY = "pending-device-pairing-v1"
        fun proof(privateKey: ByteArray, serverKey: ByteArray, sessionId: String, path: String, raw: String): String {
            val local = Noise.createDH("25519"); val server = Noise.createDH("25519"); val shared = ByteArray(32)
            var derived = ByteArray(0); var extracted = ByteArray(0)
            return try {
                local.setPrivateKey(privateKey, 0); server.setPublicKey(serverKey, 0); local.calculate(shared, 0, server)
                check(shared.any { it != 0.toByte() }) { "PAIRING_IDENTITY_INVALID" }
                extracted = hmac(sessionId.encodeToByteArray(), shared)
                derived = hmac(extracted, "dsh-pairing-v1/mobile".encodeToByteArray() + byteArrayOf(1))
                RemoteBase64Url.encode(hmac(derived, ("POST\n$path\n" + MessageDigest.getInstance("SHA-256").digest(raw.encodeToByteArray()).hex()).encodeToByteArray()))
            } finally { shared.fill(0); derived.fill(0); extracted.fill(0); local.destroy(); server.destroy() }
        }
        private fun hmac(key: ByteArray, body: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(body) }
        private fun assertDevice(value: JsonValue.JObject, id: String, role: String, key: ByteArray) {
            check(value.requiredText("deviceId") == id && value.requiredText("role") == role &&
                MessageDigest.isEqual(pairingKey(value.requiredText("identityKey")), key)) { "PAIRING_DEVICE_CONFLICT" }
        }
    }
}

private fun pairingKey(value: String): ByteArray = RemoteBase64Url.decode(value).also {
    check(it.size == 32 && RemoteBase64Url.encode(it) == value) { "PAIRING_IDENTITY_INVALID" }
}
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

/** 用既有 X25519 提供者拒绝不能产生共享秘密的低阶公钥。 */
private fun pairingPublicKey(value: String): ByteArray {
    val bytes = pairingKey(value)
    val local = Noise.createDH("25519"); val peer = Noise.createDH("25519"); val shared = ByteArray(32)
    try {
        local.setPrivateKey(ByteArray(32) { 9 }, 0); peer.setPublicKey(bytes, 0); local.calculate(shared, 0, peer)
        check(shared.any { it != 0.toByte() }) { "PAIRING_IDENTITY_INVALID" }
        return bytes
    } finally { shared.fill(0); local.destroy(); peer.destroy() }
}
