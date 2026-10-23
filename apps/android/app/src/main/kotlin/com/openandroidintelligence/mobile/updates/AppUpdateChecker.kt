package com.openandroidintelligence.mobile.updates

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.time.DateTimeException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

enum class AppUpdateChannel { STABLE, PREVIEW }

data class InstalledAppBuild(
    val versionName: String,
    val versionCode: Long,
    val commit: String,
    val channel: String,
    val applicationId: String,
    val distribution: String,
    val signingCertificateSha256: String? = null,
)

data class AppUpdateRelease(
    val versionName: String,
    val versionCode: Long,
    val commit: String,
    val channel: String,
    val releaseName: String,
    val summary: String,
    val releaseUrl: String,
    val apkUrl: String,
    val signingMatches: Boolean,
)

enum class AppUpdateUnsupportedReason { DISTRIBUTION, METADATA_MISSING }
enum class AppUpdateFailure { NETWORK, RATE_LIMITED, SERVICE, INVALID_RESPONSE }

sealed interface AppUpdateResult {
    data class Available(val release: AppUpdateRelease) : AppUpdateResult
    data class UpToDate(val latestVersionName: String) : AppUpdateResult
    data object NotPublished : AppUpdateResult
    data class Unsupported(val reason: AppUpdateUnsupportedReason) : AppUpdateResult
    data class Unavailable(val reason: AppUpdateFailure) : AppUpdateResult
}

/** 只请求公开发布信息，在 IO 调度器执行并跟随调用方取消，不传入任何 Gateway 或账号凭据。 */
class AppUpdateChecker(
    private val installed: InstalledAppBuild,
    private val http: AppUpdateHttp = GitHubAppUpdateHttp(),
) {
    suspend fun check(channel: AppUpdateChannel): AppUpdateResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (installed.distribution != "full") {
            return@withContext AppUpdateResult.Unsupported(AppUpdateUnsupportedReason.DISTRIBUTION)
        }
        try {
            checkPublished(channel, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))
        } catch (failure: UpdateRequestFailure) {
            AppUpdateResult.Unavailable(failure.reason)
        } catch (_: IOException) {
            AppUpdateResult.Unavailable(AppUpdateFailure.NETWORK)
        } catch (_: IllegalArgumentException) {
            AppUpdateResult.Unavailable(AppUpdateFailure.INVALID_RESPONSE)
        } catch (_: DateTimeException) {
            AppUpdateResult.Unavailable(AppUpdateFailure.INVALID_RESPONSE)
        }
    }

    private suspend fun checkPublished(channel: AppUpdateChannel, deadline: Long): AppUpdateResult {
        val latestReleaseByChannel = mutableMapOf<String, PublishedRelease>()
        if (channel == AppUpdateChannel.PREVIEW) {
            currentCoroutineContext().ensureActive()
            val values = Json.parse(fetch("$API_RELEASES?per_page=$PAGE_SIZE&page=1", MAX_RELEASE_BYTES, deadline)).array()
            require(values.size <= PAGE_SIZE)
            values.forEach { value ->
                currentCoroutineContext().ensureActive()
                val release = publishedRelease(value, channel) ?: return@forEach
                if (release.expectedChannel == "stable") return@forEach
                val current = latestReleaseByChannel[release.expectedChannel]
                if (current == null || release.publishedAt.isAfter(current.publishedAt)) {
                    latestReleaseByChannel[release.expectedChannel] = release
                }
            }
        }

        // GitHub 提供正式版直达接口，预览检查只需额外读取第一页，避免历史发布量增加请求数。
        fetchOptional(API_LATEST_RELEASE, MAX_RELEASE_BYTES, deadline)?.let { body ->
            val release = publishedRelease(Json.parse(body), AppUpdateChannel.STABLE)
                ?: throw IllegalArgumentException("GitHub latest release is not a valid stable release")
            latestReleaseByChannel[release.expectedChannel] = release
        }

        val releases = latestReleaseByChannel.values
        if (releases.isEmpty()) return AppUpdateResult.NotPublished

        var incomplete = false
        val candidates = releases.mapNotNull { release ->
            currentCoroutineContext().ensureActive()
            val metadataAssets = release.assets.filter { it.name == MANIFEST_NAME && it.uploaded }
            if (metadataAssets.isEmpty()) {
                incomplete = true
                return@mapNotNull null
            }
            require(metadataAssets.size == 1)
            val metadataAsset = metadataAssets.single()
            require(OfficialUpdateUrls.releaseAsset(metadataAsset.url, release.tag, MANIFEST_NAME))
            val metadata = Json.parse(fetch(metadataAsset.url, MAX_MANIFEST_BYTES, deadline)).fields()
            require(metadata.long("schemaVersion") == 1L)
            val versionName = metadata.string("versionName")
            val versionCode = metadata.long("versionCode")
            val commit = metadata.string("commit")
            val releaseChannel = metadata.string("channel")
            val assetName = metadata.string("assetName")
            val certificate = metadata.string("signingCertificateSha256")
            require(versionName.isNotBlank() && versionName.length <= MAX_NAME_CHARS)
            require(versionCode in 1..2_100_000_000L)
            require(commit.matches(Regex("[0-9a-f]{40}")))
            require(certificate.matches(Regex("[0-9a-f]{64}")))
            require(releaseChannel == release.expectedChannel)
            if (release.tag != "nightly") require(versionName == release.tag.removePrefix("v"))
            require(APK_NAME.matches(assetName))
            if (metadata.string("applicationId") != installed.applicationId || metadata.string("distribution") != installed.distribution) {
                incomplete = true
                return@mapNotNull null
            }
            val packages = release.assets.filter { it.name == assetName && it.uploaded }
            if (packages.isEmpty()) {
                incomplete = true
                return@mapNotNull null
            }
            require(packages.size == 1)
            val apk = packages.single()
            require(OfficialUpdateUrls.releaseAsset(apk.url, release.tag, assetName))
            AppUpdateRelease(
                versionName, versionCode, commit, releaseChannel, release.name, release.summary,
                release.releaseUrl, apk.url, installed.signingCertificateSha256 == certificate,
            )
        }
        val latest = candidates.maxWithOrNull(compareBy<AppUpdateRelease> { it.versionCode }.thenBy { it.versionName })
        if (latest != null && latest.versionCode > installed.versionCode) return AppUpdateResult.Available(latest)
        // 最近渠道发布缺少元数据时，不能把尚未校验的 APK 当成最新版本。
        if (incomplete) return AppUpdateResult.Unsupported(AppUpdateUnsupportedReason.METADATA_MISSING)
        return if (latest == null) AppUpdateResult.Unsupported(AppUpdateUnsupportedReason.METADATA_MISSING)
        else AppUpdateResult.UpToDate(latest.versionName)
    }

    private fun publishedRelease(value: JsonValue, channel: AppUpdateChannel): PublishedRelease? {
        val fields = value.fields()
        if (fields.boolean("draft")) return null
        val prerelease = fields.boolean("prerelease")
        val tag = fields.string("tag_name")
        val development = tag == "nightly"
        if (!development && !VERSION_TAG.matches(tag)) return null
        val versionIsPreview = tag.removePrefix("v").substringBefore('+').contains('-')
        if (!development && prerelease != versionIsPreview) return null
        if (development && !prerelease) return null

        val expectedChannel = if (development) "development" else if (prerelease) "preview" else "stable"
        if (channel == AppUpdateChannel.STABLE && expectedChannel != "stable") return null
        val publishedAt = fields.string("published_at")
        require(publishedAt.isNotBlank())
        val releaseUrl = fields.string("html_url")
        require(OfficialUpdateUrls.releasePage(releaseUrl, tag))
        val assets = fields.required("assets").array().map { it.fields() }.map { asset ->
            ReleaseAsset(
                asset.string("name"),
                asset.string("browser_download_url"),
                asset.string("state") == "uploaded" && asset.long("size") > 0,
            )
        }
        return PublishedRelease(
            tag = tag,
            expectedChannel = expectedChannel,
            publishedAt = Instant.parse(publishedAt),
            name = fields.optionalString("name").ifBlank { tag }.take(MAX_NAME_CHARS),
            summary = fields.optionalString("body").take(MAX_SUMMARY_CHARS),
            releaseUrl = releaseUrl,
            assets = assets,
        )
    }

    private suspend fun fetch(url: String, maxBytes: Int, deadline: Long): String {
        val response = request(url, maxBytes, deadline)
        when (response.status) {
            200 -> {
                require(response.body.toByteArray(Charsets.UTF_8).size <= maxBytes)
                return response.body
            }
            403, 429 -> throw UpdateRequestFailure(AppUpdateFailure.RATE_LIMITED)
            else -> throw UpdateRequestFailure(AppUpdateFailure.SERVICE)
        }
    }

    private suspend fun fetchOptional(url: String, maxBytes: Int, deadline: Long): String? {
        val response = request(url, maxBytes, deadline)
        if (response.status == 404) return null
        if (response.status != 200) {
            when (response.status) {
                403, 429 -> throw UpdateRequestFailure(AppUpdateFailure.RATE_LIMITED)
                else -> throw UpdateRequestFailure(AppUpdateFailure.SERVICE)
            }
        }
        require(response.body.toByteArray(Charsets.UTF_8).size <= maxBytes)
        return response.body
    }

    private suspend fun request(url: String, maxBytes: Int, deadline: Long): AppUpdateHttpResponse {
        currentCoroutineContext().ensureActive()
        if (System.nanoTime() >= deadline) throw UpdateRequestFailure(AppUpdateFailure.NETWORK)
        val response = http.get(url, maxBytes, deadline)
        currentCoroutineContext().ensureActive()
        return response
    }

    private data class ReleaseAsset(val name: String, val url: String, val uploaded: Boolean)
    private data class PublishedRelease(
        val tag: String,
        val expectedChannel: String,
        val publishedAt: Instant,
        val name: String,
        val summary: String,
        val releaseUrl: String,
        val assets: List<ReleaseAsset>,
    )

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_RELEASE_BYTES = 2 * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 32 * 1024
        private const val MAX_NAME_CHARS = 160
        private const val MAX_SUMMARY_CHARS = 4_000
        private const val MANIFEST_NAME = "oai-update.json"
        private const val API_RELEASES = "https://api.github.com/repos/1rua/open-android-intelligence/releases"
        private const val API_LATEST_RELEASE = "$API_RELEASES/latest"
        private val VERSION_TAG = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")
        private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
    }
}

data class AppUpdateHttpResponse(val status: Int, val body: String)

fun interface AppUpdateHttp {
    suspend fun get(url: String, maxBytes: Int): AppUpdateHttpResponse

    /** 请求夹具可沿用双参数方法；生产实现把整次检查的期限传入每次读取。 */
    suspend fun get(url: String, maxBytes: Int, deadlineNanos: Long): AppUpdateHttpResponse = get(url, maxBytes)
}

/** 独立的 HTTPS 请求，沿用系统证书校验；只允许官方仓库，不复用账号网络栈。 */
class GitHubAppUpdateHttp internal constructor(private val requestUrl: (URI) -> URI) : AppUpdateHttp {
    constructor() : this({ it })

    override suspend fun get(url: String, maxBytes: Int): AppUpdateHttpResponse =
        get(url, maxBytes, System.nanoTime() + TimeUnit.SECONDS.toNanos(45))

    override suspend fun get(url: String, maxBytes: Int, deadlineNanos: Long): AppUpdateHttpResponse = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val activeCall = AtomicReference<Call?>()
            continuation.invokeOnCancellation { activeCall.getAndSet(null)?.cancel() }
            try {
                continuation.resume(execute(url, maxBytes, deadlineNanos, continuation.context, activeCall))
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
    }

    private fun execute(
        url: String,
        maxBytes: Int,
        deadlineNanos: Long,
        context: CoroutineContext,
        activeCall: AtomicReference<Call?>,
    ): AppUpdateHttpResponse {
        require(maxBytes > 0)
        val original = URI(url)
        val assetRequest = OfficialUpdateUrls.manifestRequest(original)
        require(assetRequest || OfficialUpdateUrls.apiRequest(original))
        var target = original
        repeat(MAX_REDIRECTS + 1) { redirects ->
            context.ensureActive()
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) throw IOException()
            val request = Request.Builder()
                .url(requestUrl(target).toASCIIString())
                .header("User-Agent", "OpenAndroidIntelligence-UpdateChecker")
                .header("Accept", if (assetRequest) "application/octet-stream" else "application/vnd.github+json")
                .apply { if (!assetRequest) header("X-GitHub-Api-Version", "2022-11-28") }
                .build()
            val call = CLIENT.newCall(request)
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            activeCall.set(call)
            try {
                context.ensureActive()
                call.execute().use { response ->
                    val status = response.code
                    if (status in REDIRECT_STATUSES) {
                        require(redirects < MAX_REDIRECTS)
                        val location = response.header("Location") ?: throw IllegalArgumentException()
                        val next = target.resolve(location)
                        require(if (assetRequest) OfficialUpdateUrls.assetRedirect(original, next) else next == original)
                        target = next
                    } else {
                        if (status != 200) return AppUpdateHttpResponse(status, "")
                        val body = response.body ?: throw IllegalArgumentException()
                        require(body.contentLength() <= maxBytes)
                        val bytes = body.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8_192)
                            while (true) {
                                context.ensureActive()
                                if (System.nanoTime() >= deadlineNanos) throw IOException()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= maxBytes)
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        return AppUpdateHttpResponse(status, bytes.toString(Charsets.UTF_8))
                    }
                }
            } finally {
                activeCall.compareAndSet(call, null)
                call.cancel()
            }
        }
        throw IllegalArgumentException()
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val CLIENT = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

internal object OfficialUpdateUrls {
    private const val REPOSITORY = "/1rua/open-android-intelligence"

    fun releasePage(value: String, tag: String): Boolean = validUri(value)?.let {
        it.host == "github.com" && it.path == "$REPOSITORY/releases/tag/$tag" && it.rawQuery == null
    } == true

    fun releaseAsset(value: String, tag: String, name: String): Boolean = validUri(value)?.let {
        it.host == "github.com" && it.path == "$REPOSITORY/releases/download/$tag/$name" && it.rawQuery == null
    } == true

    fun apiRequest(uri: URI): Boolean = basic(uri) && uri.host == "api.github.com" && when {
        uri.path == "/repos$REPOSITORY/releases/latest" -> uri.rawQuery == null
        uri.path == "/repos$REPOSITORY/releases" -> uri.rawQuery == "per_page=100&page=1"
        else -> false
    }

    fun manifestRequest(uri: URI): Boolean = basic(uri) && uri.host == "github.com" &&
        uri.path.matches(Regex("$REPOSITORY/releases/download/[^/]+/oai-update\\.json")) && uri.rawQuery == null

    fun assetRedirect(original: URI, next: URI): Boolean {
        if (!basic(next)) return false
        if (next == original) return true
        return next.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com") &&
            next.path.matches(Regex("/github-production-release-asset(?:-2e65be)?/[0-9]+/[A-Za-z0-9-]+"))
    }

    private fun validUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf(::basic)
    private fun basic(uri: URI): Boolean = uri.scheme == "https" && uri.userInfo == null &&
        uri.port == -1 && uri.rawFragment == null && uri.rawPath?.contains("\\") == false
}

private class UpdateRequestFailure(val reason: AppUpdateFailure) : RuntimeException()

private fun JsonValue.array(): List<JsonValue> = (this as? JsonValue.JArray)?.items ?: throw IllegalArgumentException()
private fun JsonValue.fields(): Map<String, JsonValue> {
    val fields = (this as? JsonValue.JObject)?.fields ?: throw IllegalArgumentException()
    require(fields.map { it.first }.distinct().size == fields.size)
    return fields.toMap()
}
private fun Map<String, JsonValue>.required(name: String): JsonValue = this[name] ?: throw IllegalArgumentException()
private fun Map<String, JsonValue>.string(name: String): String = (required(name) as? JsonValue.JString)?.value ?: throw IllegalArgumentException()
private fun Map<String, JsonValue>.optionalString(name: String): String = when (val value = this[name]) {
    null, JsonValue.JNull -> ""
    is JsonValue.JString -> value.value
    else -> throw IllegalArgumentException()
}
private fun Map<String, JsonValue>.boolean(name: String): Boolean = (required(name) as? JsonValue.JBool)?.value ?: throw IllegalArgumentException()
private fun Map<String, JsonValue>.long(name: String): Long =
    (required(name) as? JsonValue.JNumber)?.raw?.toLongOrNull() ?: throw IllegalArgumentException()
