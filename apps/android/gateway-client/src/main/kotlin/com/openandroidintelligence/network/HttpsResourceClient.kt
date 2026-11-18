package com.openandroidintelligence.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CertificatePinner
import okhttp3.CookieJar
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class HttpsResourceResponse(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray)

/** 通用 HTTPS 字节入口：没有账号凭据、签名或会话，TLS/主机名验证交给 OkHttp。 */
class HttpsResourceClient internal constructor(private val base: OkHttpClient) {
    constructor() : this(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build())

    suspend fun exchange(url: String, method: String = "GET", headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0), pins: Set<String> = emptySet(), maximumBytes: Int = 32 * 1024 * 1024): HttpsResourceResponse {
        require(maximumBytes >= 0) { "HTTPS_RESPONSE_LIMIT_INVALID" }
        val target = url.toHttpUrl()
        require(target.isHttps && target.username.isEmpty() && target.password.isEmpty() && target.fragment == null) { "SOURCE_NOT_HTTPS" }
        val pinner = CertificatePinner.Builder()
        pins.forEach { pin ->
            require(pin.matches(Regex("sha256:[a-f0-9]{64}"))) { "HTTPS_PIN_INVALID" }
            val bytes = pin.removePrefix("sha256:").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            pinner.add(target.host, "sha256/" + Base64.getEncoder().encodeToString(bytes))
        }
        val client = base.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .certificatePinner(pinner.build()).build()
        val request = Request.Builder().url(target)
        val seen = mutableSetOf<String>()
        headers.forEach { (name, value) ->
            val normalized = name.lowercase()
            require(normalized !in setOf("host", "content-length", "transfer-encoding", "connection", "proxy-authorization") &&
                seen.add(normalized) && '\r' !in value && '\n' !in value) { "HTTPS_HEADER_INVALID" }
            request.addHeader(name, value)
        }
        request.method(method, if (method in setOf("GET", "HEAD")) null else body.toRequestBody())
        val call = client.newCall(request.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val responseHeaders = it.headers.toList()
                            for (name in listOf("Content-Length", "Content-Type", "Content-Encoding", "Digest", "Location")) {
                                if (responseHeaders.count { header -> header.first.equals(name, true) } > 1) throw IOException("HTTPS_RESPONSE_HEADERS_INVALID")
                            }
                            if (it.header("Content-Length") != null && it.header("Transfer-Encoding") != null) throw IOException("HTTPS_RESPONSE_HEADERS_INVALID")
                            val content = it.body
                            if ((content?.contentLength() ?: 0) > maximumBytes) throw IOException("HTTPS_RESPONSE_TOO_LARGE")
                            val output = ByteArrayOutputStream()
                            content?.byteStream()?.use { input ->
                                val buffer = ByteArray(16 * 1024)
                                try {
                                    while (true) {
                                        if (!continuation.isActive) throw IOException("HTTPS_REQUEST_CANCELLED")
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (output.size().toLong() + count > maximumBytes) throw IOException("HTTPS_RESPONSE_TOO_LARGE")
                                        output.write(buffer, 0, count)
                                    }
                                } finally { buffer.fill(0) }
                            }
                            val result = HttpsResourceResponse(it.code, it.headers.toList(), output.toByteArray())
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }
}
