package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.mobile.updates.AppUpdateChannel
import com.openandroidintelligence.mobile.updates.AppUpdateChecker
import com.openandroidintelligence.mobile.updates.AppUpdateFailure
import com.openandroidintelligence.mobile.updates.AppUpdateHttp
import com.openandroidintelligence.mobile.updates.AppUpdateHttpResponse
import com.openandroidintelligence.mobile.updates.AppUpdateResult
import com.openandroidintelligence.mobile.updates.AppUpdateUnsupportedReason
import com.openandroidintelligence.mobile.updates.GitHubAppUpdateHttp
import com.openandroidintelligence.mobile.updates.InstalledAppBuild
import com.openandroidintelligence.mobile.updates.OfficialUpdateUrls
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test fun stableChecksOnlyPublishedNumberedStableReleases() = Fixture().use { fixture ->
        fixture.page(1, listOf(
            fixture.release("nightly", 150, "development"),
            fixture.release("v2.2.0-beta.1", 140, "preview"),
            fixture.release("v2.2.0", 130, "stable"),
            fixture.release("v9.0.0", 200, "stable", draft = true),
        ))
        val result = fixture.check(AppUpdateChannel.STABLE).available()
        assertEquals("2.2.0", result.versionName)
        assertEquals(130L, result.versionCode)
        assertEquals("stable", result.channel)
        assertEquals(listOf("$RELEASES?per_page=100&page=1", fixture.metadataUrl("v2.2.0")), fixture.requests)
    }

    @Test fun previewFollowsDevelopmentAndFutureNumberedPreviewsAcrossPages() = Fixture().use { fixture ->
        fixture.page(1, (1..100).map { mapOf("draft" to false, "prerelease" to true, "tag_name" to "unrelated-$it") })
        fixture.page(2, listOf(
            fixture.release("nightly", 120, "development", publishedAt = "2026-10-20T00:00:00Z"),
            fixture.release("v2.2.0-beta.1", 140, "preview", publishedAt = "2026-10-21T00:00:00Z"),
            fixture.release("v2.1.1", 110, "stable"),
        ))
        val result = fixture.check(AppUpdateChannel.PREVIEW).available()
        assertEquals("2.2.0-beta.1", result.versionName)
        assertTrue(fixture.requests.contains("$RELEASES?per_page=100&page=2"))
    }

    @Test fun previewCanAdvanceFromReleaseCandidateToStable() = Fixture().use { fixture ->
        fixture.page(1, listOf(
            fixture.release("v2.2.0-rc.1", 140, "preview"),
            fixture.release("v2.2.0", 149, "stable"),
            fixture.release("nightly", 130, "development"),
        ))
        assertEquals("stable", fixture.check(AppUpdateChannel.PREVIEW, installed.copy(versionCode = 140)).available().channel)
    }

    @Test fun previewPaginationContinuesPastTwentyFullPagesAndFetchesOnlySelectedMetadata() = Fixture().use { fixture ->
        for (page in 1..20) {
            val unrelated = (1..99).map { index ->
                mapOf(
                    "draft" to false,
                    "prerelease" to true,
                    "tag_name" to "unrelated-$page-$index",
                    "published_at" to "2026-10-20T00:00:00Z",
                )
            }
            val releases = if (page == 1) {
                unrelated + fixture.release("v2.3.0-beta.1", 150, "preview", publishedAt = "2026-10-21T00:00:00Z")
            } else {
                unrelated + mapOf(
                    "draft" to false,
                    "prerelease" to true,
                    "tag_name" to "unrelated-$page-100",
                    "published_at" to "2026-10-20T00:00:00Z",
                )
            }
            fixture.page(page, releases)
        }
        fixture.page(21, listOf(
            fixture.release("v2.3.0-beta.1", 160, "preview", publishedAt = "2026-10-23T00:00:00Z"),
        ))

        val result = fixture.check(AppUpdateChannel.PREVIEW).available()

        assertEquals("2.3.0-beta.1", result.versionName)
        assertTrue(fixture.requests.contains("$RELEASES?per_page=100&page=21"))
        assertEquals(listOf(fixture.metadataUrl("v2.3.0-beta.1")), fixture.requests.filter { it.contains("/releases/download/") })
    }

    @Test fun stablePaginationFindsTheLatestStableReleaseBeyondTwentyPages() = Fixture().use { fixture ->
        for (page in 1..20) {
            val unrelated = (1..99).map { index ->
                mapOf(
                    "draft" to false,
                    "prerelease" to true,
                    "tag_name" to "unrelated-$page-$index",
                    "published_at" to "2026-10-20T00:00:00Z",
                )
            }
            val releases = if (page == 1) {
                unrelated + fixture.release("v2.2.0", 150, "stable", publishedAt = "2026-10-21T00:00:00Z")
            } else {
                unrelated + mapOf(
                    "draft" to false,
                    "prerelease" to true,
                    "tag_name" to "unrelated-$page-100",
                    "published_at" to "2026-10-20T00:00:00Z",
                )
            }
            fixture.page(page, releases)
        }
        fixture.page(21, listOf(
            fixture.release("v2.3.0", 160, "stable", publishedAt = "2026-10-23T00:00:00Z"),
        ))

        val result = fixture.check(AppUpdateChannel.STABLE).available()

        assertEquals("2.3.0", result.versionName)
        assertTrue(fixture.requests.contains("$RELEASES?per_page=100&page=21"))
        assertEquals(listOf(fixture.metadataUrl("v2.3.0")), fixture.requests.filter { it.contains("/releases/download/") })
    }

    @Test fun rollingDevelopmentBuildUsesMonotonicVersionCode() = Fixture().use { fixture ->
        fixture.page(1, listOf(fixture.release("nightly", 120, "development", versionName = "2.1.1-dev.20261021+abc1234")))
        assertEquals("2.1.1-dev.20261021+abc1234", fixture.check(AppUpdateChannel.PREVIEW).available().versionName)
    }

    @Test fun inconsistentPrereleaseFlagsAreNotClassifiedAsStableOrPreview() = Fixture().use { fixture ->
        fixture.page(1, listOf(fixture.release("v2.2.0-beta.1", 120, "stable")))
        assertEquals(AppUpdateResult.NotPublished, fixture.check(AppUpdateChannel.STABLE))
        assertEquals(AppUpdateResult.NotPublished, fixture.check(AppUpdateChannel.PREVIEW))
        fixture.page(1, listOf(fixture.release("v2.2.0", 120, "preview")))
        assertEquals(AppUpdateResult.NotPublished, fixture.check(AppUpdateChannel.STABLE))
        assertEquals(AppUpdateResult.NotPublished, fixture.check(AppUpdateChannel.PREVIEW))
    }

    @Test fun identicalOrOlderVersionCodesNeverOfferADowngrade() = Fixture().use { fixture ->
        fixture.page(1, listOf(fixture.release("v2.2.0", 100, "stable")))
        assertEquals(AppUpdateResult.UpToDate("2.2.0"), fixture.check(AppUpdateChannel.STABLE))
        assertEquals(AppUpdateResult.UpToDate("2.2.0"), fixture.check(AppUpdateChannel.STABLE, installed.copy(versionCode = 200)))
    }

    @Test fun missingStableReleaseIsDifferentFromFailedCheck() = Fixture().use { fixture ->
        fixture.page(1, listOf(fixture.release("nightly", 120, "development")))
        assertEquals(AppUpdateResult.NotPublished, fixture.check(AppUpdateChannel.STABLE))
        fixture.responses["$RELEASES?per_page=100&page=1"] = AppUpdateHttpResponse(503, "不可把错误正文当作无更新")
        assertEquals(AppUpdateResult.Unavailable(AppUpdateFailure.SERVICE), fixture.check(AppUpdateChannel.STABLE))
    }

    @Test fun missingMetadataOrApkNeverClaimsLatest() = Fixture().use { fixture ->
        fixture.page(1, listOf(fixture.release("v2.2.0", 120, "stable", metadata = false)))
        assertEquals(AppUpdateResult.Unsupported(AppUpdateUnsupportedReason.METADATA_MISSING), fixture.check(AppUpdateChannel.STABLE))
        fixture.page(1, listOf(fixture.release("v2.2.0", 120, "stable", apk = false)))
        assertEquals(AppUpdateResult.Unsupported(AppUpdateUnsupportedReason.METADATA_MISSING), fixture.check(AppUpdateChannel.STABLE))
    }

    @Test fun invalidPublishedAtIsAnInvalidResponse() = Fixture().use { fixture ->
        fixture.page(1, listOf(
            fixture.release("v2.2.0", 120, "stable", publishedAt = "not-a-timestamp"),
        ))

        assertEquals(
            AppUpdateResult.Unavailable(AppUpdateFailure.INVALID_RESPONSE),
            fixture.check(AppUpdateChannel.STABLE),
        )
    }

    @Test fun historicalMissingMetadataDoesNotBlockAVerifiedLatestBuild() = Fixture().use { fixture ->
        fixture.page(1, listOf(
            fixture.release("v2.1.0", 90, "stable", metadata = false, publishedAt = "2026-10-20T00:00:00Z"),
            fixture.release("v2.2.0", 120, "stable", publishedAt = "2026-10-21T00:00:00Z"),
        ))
        assertEquals("2.2.0", fixture.check(AppUpdateChannel.STABLE).available().versionName)
        assertEquals(AppUpdateResult.UpToDate("2.2.0"),
            fixture.check(AppUpdateChannel.STABLE, installed.copy(versionCode = 120)))
    }

    @Test fun playDistributionDoesNotRequestOrExposeFullApk() = Fixture().use { fixture ->
        assertEquals(AppUpdateResult.Unsupported(AppUpdateUnsupportedReason.DISTRIBUTION),
            fixture.check(AppUpdateChannel.PREVIEW, installed.copy(distribution = "play")))
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun differentPackageOrDistributionIsNotAnInstallableUpdate() = Fixture().use { fixture ->
        fixture.page(1, listOf(fixture.release("v2.2.0", 120, "stable", overrides = mapOf("applicationId" to "another.app"))))
        assertEquals(AppUpdateResult.Unsupported(AppUpdateUnsupportedReason.METADATA_MISSING), fixture.check(AppUpdateChannel.STABLE))
        fixture.page(1, listOf(fixture.release("v2.2.0", 120, "stable", overrides = mapOf("distribution" to "play"))))
        assertEquals(AppUpdateResult.Unsupported(AppUpdateUnsupportedReason.METADATA_MISSING), fixture.check(AppUpdateChannel.STABLE))
    }

    @Test fun signingComparisonIsExplicitAndCheckerDoesNotDownloadTheApk() = Fixture().use { fixture ->
        fixture.page(1, listOf(fixture.release("v2.2.0", 120, "stable")))
        assertTrue(fixture.check(AppUpdateChannel.STABLE).available().signingMatches)
        assertFalse(fixture.check(AppUpdateChannel.STABLE, installed.copy(signingCertificateSha256 = "b".repeat(64))).available().signingMatches)
        assertFalse(fixture.check(AppUpdateChannel.STABLE, installed.copy(signingCertificateSha256 = null)).available().signingMatches)
        assertFalse(fixture.requests.any { it.endsWith(".apk") })
    }

    @Test fun malformedOrMismatchedMetadataIsAnError() = Fixture().use { fixture ->
        listOf(
            mapOf("versionCode" to -1),
            mapOf("versionCode" to 2_100_000_001L),
            mapOf("versionCode" to "999"),
            mapOf("commit" to "short"),
            mapOf("channel" to "development"),
            mapOf("versionName" to "9.9.9"),
            mapOf("schemaVersion" to 2),
            mapOf("signingCertificateSha256" to "unknown"),
            mapOf("assetName" to "../../app.apk"),
        ).forEach { overrides ->
            fixture.page(1, listOf(fixture.release("v2.2.0", 120, "stable", overrides = overrides)))
            assertEquals(overrides.toString(), AppUpdateResult.Unavailable(AppUpdateFailure.INVALID_RESPONSE), fixture.check(AppUpdateChannel.STABLE))
        }
    }

    @Test fun untrustedReleaseAndAssetUrlsAreRejectedBeforeTheyAreOpened() = Fixture().use { fixture ->
        val release = fixture.release("v2.2.0", 120, "stable")
        fixture.page(1, listOf(release + ("html_url" to "https://github.com/another/repo/releases/tag/v2.2.0")))
        assertEquals(AppUpdateResult.Unavailable(AppUpdateFailure.INVALID_RESPONSE), fixture.check(AppUpdateChannel.STABLE))
        val assets = listOf(mapOf("name" to "oai-update.json", "browser_download_url" to "https://example.com/oai-update.json", "state" to "uploaded", "size" to 100))
        fixture.page(1, listOf(release + ("assets" to assets)))
        assertEquals(AppUpdateResult.Unavailable(AppUpdateFailure.INVALID_RESPONSE), fixture.check(AppUpdateChannel.STABLE))
        assertFalse(fixture.requests.any { it.startsWith("https://example.com") })
    }

    @Test fun rateLimitNetworkAndInvalidBodyHaveDifferentFailures() = Fixture().use { fixture ->
        fixture.responses["$RELEASES?per_page=100&page=1"] = AppUpdateHttpResponse(429, "不展示远程正文")
        assertEquals(AppUpdateResult.Unavailable(AppUpdateFailure.RATE_LIMITED), fixture.check(AppUpdateChannel.STABLE))
        fixture.responses["$RELEASES?per_page=100&page=1"] = AppUpdateHttpResponse(200, "{")
        assertEquals(AppUpdateResult.Unavailable(AppUpdateFailure.INVALID_RESPONSE), fixture.check(AppUpdateChannel.STABLE))
        assertEquals(AppUpdateResult.Unavailable(AppUpdateFailure.NETWORK),
            runBlocking { AppUpdateChecker(installed, AppUpdateHttp { _, _ -> throw IOException("不可显示此异常") }).check(AppUpdateChannel.STABLE) })
    }

    @Test fun oversizedMetadataIsRejectedEvenWhenInjectedTransportIgnoresTheLimit() = Fixture().use { fixture ->
        fixture.page(1, listOf(fixture.release("v2.2.0", 120, "stable")))
        fixture.responses[fixture.metadataUrl("v2.2.0")] = AppUpdateHttpResponse(200, " ".repeat(32 * 1024 + 1))
        assertEquals(AppUpdateResult.Unavailable(AppUpdateFailure.INVALID_RESPONSE), fixture.check(AppUpdateChannel.STABLE))
    }

    @Test fun productionHttpRejectsNonHttpsAndForeignSourcesWithoutConnecting() {
        listOf(
            "http://api.github.com/repos/1rua/open-android-intelligence/releases?per_page=100&page=1",
            "https://api.github.com/repos/other/repo/releases?per_page=100&page=1",
            "https://github.com/1rua/open-android-intelligence/releases/download/nightly/app.apk",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc",
        ).forEach { url -> assertThrows(IllegalArgumentException::class.java) { runBlocking { GitHubAppUpdateHttp().get(url, 1024) } } }
    }

    @Test fun onlyOfficialGitHubCdnRedirectsAreAllowed() {
        val original = URI("https://github.com/1rua/open-android-intelligence/releases/download/nightly/oai-update.json")
        assertTrue(OfficialUpdateUrls.assetRedirect(original, URI("https://release-assets.githubusercontent.com/github-production-release-asset/123/abc-123?sig=public-fixture")))
        assertTrue(OfficialUpdateUrls.assetRedirect(original, URI("https://objects.githubusercontent.com/github-production-release-asset-2e65be/123/abc-123")))
        listOf(
            "http://release-assets.githubusercontent.com/github-production-release-asset/123/abc",
            "https://release-assets.githubusercontent.com.evil.example/github-production-release-asset/123/abc",
            "https://user@release-assets.githubusercontent.com/github-production-release-asset/123/abc",
            "https://github.com/other/repo/releases/download/nightly/oai-update.json",
        ).forEach { assertFalse(OfficialUpdateUrls.assetRedirect(original, URI(it))) }
    }

    @Test fun cancellationDisconnectsBlockedPageAndDoesNotRequestFurtherPages() = Fixture().use { fixture ->
        fixture.page(1, (1..100).map { mapOf("draft" to false, "prerelease" to true, "tag_name" to "unrelated-$it") })
        val blocked = "$RELEASES?per_page=100&page=2"
        assertCancellationStopsAt(fixture, blocked, listOf("$RELEASES?per_page=100&page=1", blocked))
    }

    @Test fun cancellationDisconnectsBlockedMetadataAndDoesNotScanRemainingReleases() = Fixture().use { fixture ->
        fixture.page(1, listOf(
            fixture.release("v2.2.0-beta.1", 120, "preview"),
            fixture.release("v2.3.0-beta.1", 130, "preview"),
        ))
        val blocked = fixture.metadataUrl("v2.2.0-beta.1")
        assertCancellationStopsAt(fixture, blocked, listOf("$RELEASES?per_page=100&page=1", blocked))
    }

    private fun assertCancellationStopsAt(fixture: Fixture, blockedUrl: String, expectedRequests: List<String>) {
        BlockingHttpFixture(fixture.responses, blockedUrl).use { network ->
            runBlocking {
                var returned: AppUpdateResult? = null
                val check = launch(Dispatchers.IO) {
                    returned = AppUpdateChecker(installed, network.http).check(AppUpdateChannel.PREVIEW)
                }
                try {
                    assertTrue("生产 HTTP 必须已开始读取尚未完整送达的正文", network.bodyStarted.await(3, TimeUnit.SECONDS))
                    check.cancel()
                    withTimeout(3_000) { check.join() }
                    assertTrue("取消必须关闭实际 TCP 连接", network.clientDisconnected.await(3, TimeUnit.SECONDS))
                    assertTrue(check.isCancelled)
                    assertEquals(null, returned)
                    assertEquals(expectedRequests, network.requests.toList())
                } finally {
                    check.cancel()
                }
            }
        }
    }

    private fun AppUpdateResult.available() = (this as AppUpdateResult.Available).release

    /** 只发送部分正文后等待客户端断开；用真实阻塞读取验证取消，不依赖 JDK 专用 HTTP Server。 */
    private class BlockingHttpFixture(
        private val responses: Map<String, AppUpdateHttpResponse>,
        private val blockedUrl: String,
    ) : AutoCloseable {
        val bodyStarted = CountDownLatch(1)
        val clientDisconnected = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val http = GitHubAppUpdateHttp { uri ->
            val target = URLEncoder.encode(uri.toASCIIString(), Charsets.UTF_8)
            URI("http://127.0.0.1:${server.localPort}/?url=$target")
        }

        init {
            thread(isDaemon = true, name = "app-update-cancellation-fixture") {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    sockets += socket
                    runCatching {
                        socket.use { open ->
                            open.soTimeout = 5_000
                            val reader = open.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val request = reader.readLine() ?: return@use
                            while (!reader.readLine().isNullOrEmpty()) Unit
                            val target = URLDecoder.decode(request.substringBefore(" HTTP/").substringAfter("url="), Charsets.UTF_8)
                            requests += target
                            val output = open.getOutputStream()
                            if (target == blockedUrl) {
                                output.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n[".toByteArray(Charsets.US_ASCII))
                                output.flush()
                                bodyStarted.countDown()
                                if (reader.read() == -1) clientDisconnected.countDown()
                            } else {
                                val response = responses[target] ?: AppUpdateHttpResponse(404, "未注册请求")
                                val bytes = response.body.toByteArray(Charsets.UTF_8)
                                output.write("HTTP/1.1 ${response.status} OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                output.write(bytes)
                                output.flush()
                            }
                        }
                    }
                    sockets -= socket
                }
            }
        }

        override fun close() {
            server.close()
            sockets.forEach { it.close() }
        }
    }

    /** 核心仍请求正式 URL；此请求接缝把公开 JSON 传送到真实的本机 HTTP 夹具。 */
    private class Fixture : AutoCloseable {
        val responses = ConcurrentHashMap<String, AppUpdateHttpResponse>()
        val requests = mutableListOf<String>()
        private val server = LoopbackGatewayStub().apply {
            respond("/") { request ->
                val target = URLDecoder.decode(request.target.substringAfter("url="), Charsets.UTF_8)
                val response = responses[target] ?: AppUpdateHttpResponse(404, "夹具没有该响应")
                response.status to response.body
            }
        }

        fun check(channel: AppUpdateChannel, build: InstalledAppBuild = installed): AppUpdateResult =
            runBlocking {
            AppUpdateChecker(build, AppUpdateHttp { url, _ ->
                requests += url
                val encoded = URLEncoder.encode(url, Charsets.UTF_8)
                val connection = URL("${server.baseUrl}/?url=$encoded").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 2_000
                    connection.readTimeout = 2_000
                    val status = connection.responseCode
                    val body = (if (status == 200) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    AppUpdateHttpResponse(status, body)
                } finally { connection.disconnect() }
            }).check(channel)
            }

        fun page(number: Int, releases: List<Map<String, Any?>>) {
            responses["$RELEASES?per_page=100&page=$number"] = AppUpdateHttpResponse(200, Json.canonical(Json.of(releases)))
        }

        fun metadataUrl(tag: String) = "$REPOSITORY/releases/download/$tag/oai-update.json"

        fun release(
            tag: String,
            code: Long,
            channel: String,
            draft: Boolean = false,
            metadata: Boolean = true,
            apk: Boolean = true,
            versionName: String = if (tag == "nightly") "2.1.1-dev.20261021+abc1234" else tag.removePrefix("v"),
            overrides: Map<String, Any?> = emptyMap(),
            publishedAt: String = "2026-10-21T00:00:00Z",
        ): Map<String, Any?> {
            val assetName = "open-android-intelligence-full.apk"
            val manifest = mapOf(
                "schemaVersion" to 1, "versionName" to versionName, "versionCode" to code,
                "commit" to "c".repeat(40), "channel" to channel, "assetName" to assetName,
                "applicationId" to installed.applicationId, "distribution" to "full", "signingCertificateSha256" to CERTIFICATE,
            ) + overrides
            responses[metadataUrl(tag)] = AppUpdateHttpResponse(200, Json.canonical(Json.of(manifest)))
            val assets = buildList {
                if (metadata) add(mapOf("name" to "oai-update.json", "browser_download_url" to metadataUrl(tag), "state" to "uploaded", "size" to 512))
                if (apk) add(mapOf("name" to assetName, "browser_download_url" to "$REPOSITORY/releases/download/$tag/$assetName", "state" to "uploaded", "size" to 1_000_000))
            }
            return mapOf(
                "tag_name" to tag, "name" to if (channel == "development") "开发预览测试版" else versionName,
                "body" to "公开发布说明", "draft" to draft, "prerelease" to (channel != "stable"),
                "published_at" to publishedAt, "html_url" to "$REPOSITORY/releases/tag/$tag", "assets" to assets,
            )
        }

        override fun close() { server.closed() }
    }

    companion object {
        private const val REPOSITORY = "https://github.com/1rua/open-android-intelligence"
        private const val RELEASES = "https://api.github.com/repos/1rua/open-android-intelligence/releases"
        private val CERTIFICATE = "a".repeat(64)
        private val installed = InstalledAppBuild("2.1.1-dev", 100, "d".repeat(40), "development", "com.openandroidintelligence.mobile", "full", CERTIFICATE)
    }
}
