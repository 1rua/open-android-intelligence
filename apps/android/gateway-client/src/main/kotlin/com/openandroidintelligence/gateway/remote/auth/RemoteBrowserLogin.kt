package com.openandroidintelligence.gateway.remote.auth

import com.openandroidintelligence.gateway.remote.protocol.RemoteBase64Url
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

enum class RemoteOAuthProvider(val id: String) { GITHUB("github"), ZHIHU("zhihu"), GOOGLE("google") }

/** 固定官方回调协议；随机 state 绑定 App 发起的尝试，不替代上游没有提供的 PKCE。 */
class RemoteBrowserAttempt(val provider: RemoteOAuthProvider, val state: String, val expiresAt: Long, val epoch: Long) {
    val returnTo: String get() = "dshremote://oauth?state=$state&provider=${provider.id}"
    override fun toString() = "RemoteBrowserAttempt(秘密=已隐藏)"
}

class RemoteBrowserResult(val token: String?, val error: String?) {
    override fun toString() = "RemoteBrowserResult(凭据=已隐藏)"
}

/** 只保存短时发起票据，不落盘账号 token；消费先持久化清空，重复回调不能再注册设备。 */
class RemoteBrowserLoginStore(private val persistence: RemoteIdentityPersistence, private val now: () -> Long = System::currentTimeMillis) {
    fun begin(provider: RemoteOAuthProvider, epoch: Long): RemoteBrowserAttempt = synchronized(lock) {
        val state = RemoteBase64Url.encode(ByteArray(32).also(SecureRandom()::nextBytes))
        RemoteBrowserAttempt(provider, state, now() + 600_000L, epoch).also { attempt ->
            persistence.writeAtomic(KEY, Json.canonical(Json.of(mapOf("provider" to provider.id, "state" to state,
                "expiresAt" to attempt.expiresAt, "epoch" to epoch))).encodeToByteArray())
        }
    }
    fun pending(): RemoteBrowserAttempt? = synchronized(lock) { load() }
    fun cancel() = synchronized(lock) { persistence.clear(KEY) }
    fun consume(raw: String, epoch: Long): RemoteBrowserResult = synchronized(lock) {
        val pending = load() ?: error("REMOTE_OAUTH_ATTEMPT_MISSING")
        val result = parse(raw, pending)
        check(pending.epoch == epoch) { "REMOTE_AUTHORIZATION_SUPERSEDED" }
        persistence.clear(KEY)
        result
    }
    private fun load(): RemoteBrowserAttempt? {
        val bytes = persistence.read(KEY) ?: return null
        return try {
            val row = RemoteWireJson.decode(bytes).objectValue()
            val provider = RemoteOAuthProvider.entries.first { it.id == row.requiredText("provider") }
            val state = row.requiredText("state")
            check(Regex("[A-Za-z0-9_-]{43}").matches(state))
            val expiry = row.longValue("expiresAt")
            if (expiry <= now() || expiry > now() + 600_000L) { persistence.clear(KEY); null }
            else RemoteBrowserAttempt(provider, state, expiry, row.longValue("epoch"))
        } finally { bytes.fill(0) }
    }
    companion object {
        private const val KEY = "pending-browser-login-v1"
        private val lock = Any()
        fun authorizeUrl(origin: String, entryPath: String, attempt: RemoteBrowserAttempt): String {
            check(origin == "https://dsh.r2049.cn") { "REMOTE_OAUTH_ORIGIN_INVALID" }
            val entry = URI(entryPath)
            check(!entry.isAbsolute && entry.rawAuthority == null && entryPath.startsWith("/api/v1/auth/") &&
                entry.rawFragment == null && entry.normalize().rawPath == entry.rawPath && !entryPath.contains('%') &&
                !entryPath.contains('\\') && entryPath.none { it.code < 32 || it.code == 127 }) { "INVALID_MESSAGE" }
            val separator = if (entry.rawQuery == null) "?" else "&"
            check(entry.rawQuery?.split('&')?.none { it.substringBefore('=') == "return_to" } != false) { "INVALID_MESSAGE" }
            return origin + entryPath + separator + "return_to=" + URLEncoder.encode(attempt.returnTo, "UTF-8")
        }
        fun parse(raw: String, attempt: RemoteBrowserAttempt): RemoteBrowserResult {
            check(raw.length <= 16_384) { "REMOTE_OAUTH_CALLBACK_INVALID" }
            val uri = URI(raw)
            check(uri.scheme == "dshremote" && uri.host == "oauth" && uri.rawPath.orEmpty().isEmpty() &&
                uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null) { "REMOTE_OAUTH_CALLBACK_INVALID" }
            val entries = uri.rawQuery.orEmpty().split('&').map { pair ->
                URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            check(entries.map { it.first }.distinct().size == entries.size &&
                entries.all { it.first in setOf("state", "provider", "token", "error") }) { "REMOTE_OAUTH_CALLBACK_INVALID" }
            val query = entries.toMap()
            check(MessageDigest.isEqual(query["state"].orEmpty().encodeToByteArray(), attempt.state.encodeToByteArray()) &&
                query["provider"] == attempt.provider.id) { "REMOTE_OAUTH_CALLBACK_INVALID" }
            val token = query["token"]
            val error = query["error"]
            check((token == null) != (error == null)) { "REMOTE_OAUTH_CALLBACK_INVALID" }
            check(token == null || token.length in 16..12_288 && token.none { it.isWhitespace() || it.code < 32 }) { "REMOTE_OAUTH_CALLBACK_INVALID" }
            check(error == null || Regex("[a-zA-Z0-9_]{1,128}").matches(error)) { "REMOTE_OAUTH_CALLBACK_INVALID" }
            return RemoteBrowserResult(token, error)
        }
    }
}
