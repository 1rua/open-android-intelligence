package com.openandroidintelligence.mobile.plugins

import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.plugin.pkg.*
import java.net.URI
import kotlinx.coroutines.runBlocking

/** Release files, organization manifests and optional indexes all finish at the same signature verifier. */
object RemotePluginSource {
    private fun fetch(url: String,pins: Set<String>): ByteArray = runBlocking {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443)) { "SOURCE_NOT_HTTPS" }
        val transport = GatewayTransport(GatewayProfile("package","package","package","https://${uri.host}",pins))
        val result = transport.execute(WireRequest("GET",(uri.rawPath.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")))
        require(result.status == 200 && result.body.size <= 32*1024*1024) { "SOURCE_FETCH_FAILED" }
        result.body
    }
    fun resolve(url: String,pin: String,digest: String,indexPluginId: String? = null): ByteArray {
        val pins = pin.takeIf { it.isNotBlank() }?.let { require(it.matches(Regex("sha256:[a-f0-9]{64}"))); setOf(it) }.orEmpty()
        val resolver = PluginSourceResolver(PackageFetcher { address, expectedPins -> fetch(address,expectedPins).inputStream() },32L*1024*1024)
        fun source(address: String,hash: String): PluginSource = if (hash.isNotBlank()) {
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "SOURCE_DIGEST_INVALID" }; PluginSource.FixedRelease(address,hash)
        } else PluginSource.HttpsUrl(address,pins)
        if (indexPluginId.isNullOrBlank()) return resolver.resolve(source(url,digest)).bytes
        val index = JsonFields.obj(Json.parse(resolver.resolve(source(url,digest)).bytes.decodeToString())) ?: error("INDEX_INVALID")
        val packages = (JsonFields.field(index,"packages") as? JsonValue.JArray)?.items ?: error("INDEX_INVALID")
        require(packages.size <= 512) { "INDEX_TOO_LARGE" }
        val candidates = packages.mapNotNull { it as? JsonValue.JObject }.filter { JsonFields.string(it,"pluginId") == indexPluginId }
            .sortedWith(compareByDescending<JsonValue.JObject> { SemVer.parse(JsonFields.string(it,"version") ?: "")?.major ?: -1 }
                .thenByDescending { SemVer.parse(JsonFields.string(it,"version") ?: "")?.minor ?: -1 }
                .thenByDescending { SemVer.parse(JsonFields.string(it,"version") ?: "")?.patch ?: -1 })
        val selected = candidates.firstOrNull() ?: error("INDEX_PLUGIN_NOT_FOUND")
        val bytes=resolver.resolve(source(JsonFields.string(selected,"url") ?: error("INDEX_INVALID"),JsonFields.string(selected,"sha256") ?: error("INDEX_INVALID"))).bytes
        val verified=AlpVerifier(PackageLimits(64,8L*1024*1024,32L*1024*1024)).verify(bytes)
        try { check(verified.identity.pluginId==indexPluginId && verified.version==SemVer.parse(JsonFields.string(selected,"version").orEmpty())) { "INDEX_PACKAGE_MISMATCH" } }
        finally{verified.stagedDirectory.deleteRecursively()}
        return bytes
    }
}
