package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.gateway.remote.api.RemoteAuthCodec
import com.openandroidintelligence.gateway.remote.api.RemoteAuthRequest
import com.openandroidintelligence.gateway.remote.auth.RemoteRoleRegistrar
import com.openandroidintelligence.gateway.remote.auth.safeRoleErrorCode
import com.openandroidintelligence.gateway.remote.auth.RemoteTokenRefresher
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.protocol.RemoteBase64Url
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class RemoteAccountLogin(val accountId: String, val token: RemoteAccountAuthorizationToken, val expiresAt: Long) {
    override fun toString() = "RemoteAccountLogin(accountId=$accountId)"
}
data class RemoteHostDevice(val deviceId: String, val name: String, val platform: String, val membershipId: String,
    val online: Boolean, val clientVersion: String? = null, val harnessVersion: String? = null)
class RemoteAuthorizedPeer(val device: RemoteHostDevice, val role: RemoteRole, val publicKey: ByteArray)
data class RemoteIceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

/** 固定上游 Server API 的真实 HTTP 实现；写请求不自动重试，不跟随可能泄露 bearer 的重定向。 */
class RemoteServerApi(origin: String, internal val http: OkHttpClient = defaultHttpClient(),
    private val deviceNameProvider: () -> String = { "OAI Android" },
) : RemoteRoleRegistrar, RemoteTokenRefresher {
    val origin: String = normalizeRemoteOrigin(origin)
    // 只读查询允许 OkHttp 在复用的闲置连接被服务端关闭时重新建连。写请求仍使用单次传输。
    private val reads = http.newBuilder().retryOnConnectionFailure(true).build()
    suspend fun loginProviders(): JsonValue.JObject = request("GET", "/api/v1/auth/oauth/providers").objectValue()
    suspend fun accountFromToken(token: String): RemoteAccountLogin {
        require(token.length >= 16) { "REMOTE_AUTHORIZATION_TOKEN_INVALID" }
        val value = request("GET", "/api/v1/auth/me", token).objectValue()
        return RemoteAccountLogin(value.requiredText("account"), RemoteAccountAuthorizationToken(token), Long.MAX_VALUE)
    }
    suspend fun pairingRequest(path: String, body: String, proof: String? = null): JsonValue =
        request("POST", path, rawBody = body, proof = proof)
    suspend fun login(email: String, password: String): RemoteAccountLogin {
        val value = auth(RemoteAuthCodec.accountLogin(email, password.toCharArray())).objectValue()
        val token = RemoteAccountAuthorizationToken(value.requiredText("token"))
        val expiry = value.longValue("expiresAt")
        if (expiry <= System.currentTimeMillis()) throw RemoteRpcException("TOKEN_EXPIRED")
        return RemoteAccountLogin(value.requiredText("account"), token, expiry)
    }
    override suspend fun registerAccountRole(scope: RemoteIdentityScope, identity: RemoteStaticIdentity,
        accountToken: RemoteAccountAuthorizationToken): RemoteRoleCredential {
        check(scope.origin == origin) { "REMOTE_ORIGIN_MISMATCH" }
        return RemoteRoleCredential(identity, RemoteAuthCodec.parseTokenPair(auth(RemoteAuthCodec.registerAccountRole(identity, scope.role, accountToken, deviceNameProvider()))))
    }
    override suspend fun refresh(scope: RemoteIdentityScope, deviceId: String, refreshToken: String): RemoteTokenPair {
        check(scope.origin == origin) { "REMOTE_ORIGIN_MISMATCH" }
        return RemoteAuthCodec.parseTokenPair(auth(RemoteAuthCodec.refresh(RemoteDeviceId(deviceId), refreshToken)))
    }
    suspend fun listHosts(accessToken: String): List<RemoteHostDevice> {
        val items = mutableListOf<RemoteHostDevice>(); val cursors = mutableSetOf<String>(); var cursor: String? = null
        do {
            val suffix = if (cursor == null) "" else "&cursor=${encode(cursor)}"
            val page = request("GET", "/api/v1/devices?limit=50$suffix", accessToken).objectValue()
            items += page.arrayValue("items").map { host(it.objectValue()) }
            if (items.map { it.deviceId }.distinct().size != items.size) throw RemoteRpcException("INVALID_MESSAGE")
            cursor = page.optionalText("nextCursor")
            if (cursor != null && !cursors.add(cursor)) throw RemoteRpcException("INVALID_MESSAGE")
        } while (cursor != null)
        // 固定上游插件 devices() 单独查询 presence；目录允许不带 online，不能据此判为离线。
        val concurrency = Semaphore(8)
        // presence 的目标撤销与调用者撤销共用同一错误码，整批完成后只复核一次调用者。
        val callerCheckRequired = AtomicBoolean(false)
        val devices = coroutineScope {
            items.map { device -> async {
                concurrency.withPermit {
                    val online = try { presence(accessToken, device.deviceId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        when ((failure as? RemoteRpcException)?.code) {
                            // 目录和 presence 同用 Client 票据。票据被拒时交给账号层续期。
                            "TOKEN_EXPIRED", "AUTH_INVALID", "TOKEN_REUSED" -> throw failure
                            "DEVICE_REVOKED" -> {
                                callerCheckRequired.set(true)
                                false
                            }
                            else -> {
                                java.util.logging.Logger.getLogger("RemoteServerApi").warning("PRESENCE_LOOKUP_FAILED:${safeRoleErrorCode((failure as? RemoteRpcException)?.code.orEmpty(), "CONNECTION_FAILED")}")
                                false
                            }
                        }
                    }
                    device.copy(online = online)
                }
            } }.awaitAll()
        }
        if (callerCheckRequired.get()) request("GET", "/api/v1/me", accessToken).objectValue()
        return devices
    }
    suspend fun presence(accessToken: String, deviceId: String): Boolean {
        val value = request("GET", "/api/v1/devices/${encode(deviceId)}/presence", accessToken).objectValue()
        return value.booleanValue("online")
    }
    suspend fun peer(accessToken: String, deviceId: String): RemoteAuthorizedPeer {
        val value = request("GET", "/api/v1/devices/${encode(deviceId)}", accessToken).objectValue()
        val role = RemoteRole.entries.firstOrNull { it.wireValue == value.requiredText("role") } ?: throw RemoteRpcException("INVALID_MESSAGE")
        val key = RemoteBase64Url.decode(value.requiredText("identityKey"))
        if (key.size != 32) throw RemoteRpcException("INVALID_MESSAGE")
        return RemoteAuthorizedPeer(host(value), role, key)
    }
    suspend fun iceServers(accessToken: String, connectionId: String): List<RemoteIceServer> {
        val value = request("GET", "/api/v1/turn/credentials?connection_id=${encode(connectionId)}", accessToken).objectValue()
        return value.arrayValue("iceServers").map { row -> row.objectValue().let { item ->
            val urls = when (val raw = item.value("urls")) {
                is JsonValue.JString -> listOf(raw.value)
                is JsonValue.JArray -> raw.items.map { (it as? JsonValue.JString)?.value ?: throw RemoteRpcException("INVALID_MESSAGE") }
                else -> throw RemoteRpcException("INVALID_MESSAGE")
            }
            if (urls.isEmpty() || urls.any { !it.startsWith("stun:") && !it.startsWith("turn:") && !it.startsWith("turns:") }) throw RemoteRpcException("INVALID_MESSAGE")
            RemoteIceServer(urls, item.optionalText("username"), item.optionalText("credential"))
        } }
    }
    suspend fun revoke(accessToken: String) { request("DELETE", "/api/v1/devices/self", accessToken) }
    private suspend fun auth(value: RemoteAuthRequest) = request("POST", value.path, value.bearerToken, value.body)
    private fun host(value: JsonValue.JObject) = RemoteHostDevice(value.requiredText("deviceId"), value.requiredText("name"),
        value.requiredText("platform"), value.requiredText("membershipId"),
        (value.fields.firstOrNull { it.first == "online" }?.second as? JsonValue.JBool)?.value ?: false,
        value.optionalText("clientVersion"), value.optionalText("harnessVersion"))
    private suspend fun request(method: String, path: String, bearer: String? = null, body: JsonValue.JObject? = null, rawBody: String? = null, proof: String? = null): JsonValue {
        val request = Request.Builder().url(origin + path).header("Accept", "application/json").apply {
            if (bearer != null) header("Authorization", "Bearer $bearer")
            if (proof != null) header("X-DSH-Pairing-Proof", proof)
            method(method, (rawBody ?: body?.let(Json::canonical))?.toRequestBody("application/json".toMediaType()))
        }.build()
        val transport = if (method == "GET") reads else http
        val response = transport.newCall(request).await()
        return response.use {
            if (it.code == 204 && method == "DELETE") return@use JsonValue.JNull
            if (!it.isSuccessful) {
                val code = when {
                    it.code == 429 -> "RATE_LIMITED"
                    it.code in 500..599 -> "INTERNAL_ERROR"
                    it.code in 400..499 -> safeErrorCode(it) ?: "CONNECTION_FAILED"
                    else -> "CONNECTION_FAILED"
                }
                throw RemoteRpcException(code)
            }
            val bytes = readBody(it) ?: throw RemoteRpcException("INVALID_MESSAGE")
            try { RemoteWireJson.decode(bytes) } catch (_: Exception) { throw RemoteRpcException("INVALID_MESSAGE") }
        }
    }
    private fun safeErrorCode(response: Response): String? {
        val bytes = readBody(response) ?: return null
        val parsed = runCatching { RemoteWireJson.decode(bytes) }.getOrNull() ?: return null
        val code = (parsed as? JsonValue.JObject)?.fields?.firstOrNull { row -> row.first == "error" }?.second
            ?.let { row -> (row as? JsonValue.JObject)?.optionalText("code") }
        return code?.takeIf { value -> Regex("[A-Z0-9_]{1,128}").matches(value) }
    }
    private fun readBody(response: Response): ByteArray? = response.body?.byteStream()?.use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            if (output.size() + count > 4 * 1024 * 1024) throw RemoteRpcException("HTTP_RESPONSE_TOO_LARGE")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    companion object {
        const val DEFAULT_ORIGIN = "https://dsh.r2049.cn"
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isActive) return
            // 只记录固定故障类别，不带 URL、票据或系统返回的异常正文。
            val failure = when (e) {
                is java.net.SocketTimeoutException -> "SOCKET_TIMEOUT"
                is java.io.InterruptedIOException -> "CALL_TIMEOUT"
                is javax.net.ssl.SSLException -> "TLS_FAILED"
                is java.net.ConnectException -> "CONNECT_FAILED"
                is java.net.UnknownHostException -> "DNS_FAILED"
                is java.net.SocketException -> "SOCKET_FAILED"
                is java.io.EOFException -> "PEER_CLOSED"
                else -> "IO_FAILED"
            }
            java.util.logging.Logger.getLogger("RemoteServerApi").warning("HTTP_${call.request().method} $failure")
            continuation.resumeWithException(RemoteRpcException("CONNECTION_FAILED"))
        }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response, onCancellation = { _, value, _ -> value.close() }) else response.close()
        }
    })
}

fun normalizeRemoteOrigin(value: String): String {
    val url = try { value.trim().toHttpUrl() } catch (_: Exception) { throw RemoteRpcException("REMOTE_ORIGIN_INVALID") }
    val host = url.host
    val ipv4 = host.split('.').mapNotNull { part -> part.toIntOrNull()?.takeIf { it in 0..255 } }
    val privateIpv4 = ipv4.size == 4 && (ipv4[0] == 10 || ipv4[0] == 127 || ipv4[0] == 192 && ipv4[1] == 168 ||
        ipv4[0] == 172 && ipv4[1] in 16..31)
    val privateIpv6 = ':' in host && (host == "::1" || host.startsWith("fc", true) || host.startsWith("fd", true))
    require(url.isHttps || host == "localhost" || privateIpv4 || privateIpv6) { "REMOTE_HTTPS_REQUIRED" }
    require(url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null)
    return url.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
}
