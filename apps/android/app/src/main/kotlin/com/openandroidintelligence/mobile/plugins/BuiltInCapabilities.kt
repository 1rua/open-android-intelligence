package com.openandroidintelligence.mobile.plugins

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.plugin.pkg.AlpVerifier
import com.openandroidintelligence.plugin.pkg.PackageLimits
import com.openandroidintelligence.plugin.pkg.PackageRejected
import com.openandroidintelligence.plugin.pkg.PluginIdentity
import com.openandroidintelligence.plugin.pkg.SecuritySurface
import com.openandroidintelligence.plugin.pkg.VerifiedPluginPackage
import java.io.File

/**
 * 一项内建能力的已校验负载。
 *
 * 故意不携带任何磁盘目录：内建能力**不从应用私有目录加载**，每次启动都从 APK 资源
 * 重新校验一遍，于是升级后不可能继续使用旧版本残留文件，也不需要“已安装但未启用”
 * 这类中间状态。
 */
data class VerifiedBuiltInCapability(
    val pluginId: String,
    val identity: PluginIdentity,
    val runtimeType: String,
    val abiVersion: String?,
    val entrypoint: String?,
    /** 本包实际声明的能力，按能力 ID 收窄到它自己的内核原语。 */
    val capabilities: List<VerifiedCapability>,
    val declaredPrimitives: Set<String>,
    val surface: SecuritySurface,
    /** 是否声明了后台任务；与能力授权分开，仍由用户单独决定。 */
    val backgroundRequested: Boolean,
    /** 后台任务的最小间隔（秒）；未声明后台时保留既有缺省 60 秒。 */
    val backgroundMinimumIntervalSeconds: Long,
    /** WASM 载荷；校验通过后即读入内存，暂存目录随后删除，磁盘上不留副本。 */
    val payload: ByteArray,
)

/** 内建能力本次启动的加载结论。失败必须如实给出稳定原因码，绝不退回“看起来可用”。 */
sealed interface BuiltInLoadResult {
    val pluginId: String

    data class Loaded(val capability: VerifiedBuiltInCapability) : BuiltInLoadResult {
        override val pluginId: String get() = capability.pluginId
    }

    data class Failed(override val pluginId: String, val code: String) : BuiltInLoadResult
}

/** 让内建能力的校验逻辑可测：真实实现读 APK 资产，测试注入字节或直接抛错。 */
fun interface BuiltInPackageReader {
    fun read(plugin: BundledPlugin): ByteArray
}

internal val BUILT_IN_PACKAGE_LIMITS = PackageLimits(
    maxEntries = 64,
    maxSingleEntryBytes = 8L * 1024 * 1024,
    maxTotalUncompressedBytes = 32L * 1024 * 1024,
)

/**
 * 从 APK 资源一次性校验并装配全部内建能力。
 *
 * 校验强度与普通插件包完全一致：清单形状、Ed25519 签名、容器限额、ABI 与入口点、
 * 能力声明收窄都走同一个 [AlpVerifier]。区别只在于**产物不落盘**——
 * 校验完成即读出所需字段并删除暂存目录，没有可被扫描、卸载或版本残留的文件。
 *
 * 单项失败不牵连其他项：每个包各自给出 [BuiltInLoadResult.Failed]。
 */
internal fun loadBuiltInCapabilities(
    plugins: List<BundledPlugin> = BundledPlugin.entries,
    readPackage: BuiltInPackageReader,
    stagingRoot: File,
    limits: PackageLimits = BUILT_IN_PACKAGE_LIMITS,
): List<BuiltInLoadResult> = plugins.map { plugin ->
    val bytes = try {
        readPackage.read(plugin)
    } catch (_: BundledPluginUnavailable) {
        return@map BuiltInLoadResult.Failed(plugin.pluginId, "BUILT_IN_PACKAGE_MISSING")
    } catch (_: Exception) {
        return@map BuiltInLoadResult.Failed(plugin.pluginId, "BUILT_IN_PACKAGE_UNREADABLE")
    }
    val verified = try {
        AlpVerifier(limits, stagingRoot = stagingRoot).verify(bytes)
    } catch (_: PackageRejected) {
        return@map BuiltInLoadResult.Failed(plugin.pluginId, "BUILT_IN_PACKAGE_INVALID")
    } catch (_: Exception) {
        return@map BuiltInLoadResult.Failed(plugin.pluginId, "BUILT_IN_PACKAGE_UNREADABLE")
    }
    try {
        if (verified.identity.pluginId != plugin.pluginId) {
            return@map BuiltInLoadResult.Failed(plugin.pluginId, "BUILT_IN_PLUGIN_ID_MISMATCH")
        }
        if (verified.runtime.type != "protected-wasm") {
            return@map BuiltInLoadResult.Failed(plugin.pluginId, "BUILT_IN_RUNTIME_UNSUPPORTED")
        }
        if (verified.runtime.abiVersion != "1.0" ||
            verified.runtime.entrypoint != "open_android_intelligence_plugin_main") {
            return@map BuiltInLoadResult.Failed(plugin.pluginId, "BUILT_IN_ABI_UNSUPPORTED")
        }
        BuiltInLoadResult.Loaded(readVerified(verified))
    } catch (_: PackageRejected) {
        BuiltInLoadResult.Failed(plugin.pluginId, "BUILT_IN_PACKAGE_INVALID")
    } catch (_: Exception) {
        BuiltInLoadResult.Failed(plugin.pluginId, "BUILT_IN_PACKAGE_INVALID")
    } finally {
        // 暂存目录只服务于本次校验；内建能力不保留任何磁盘副本。
        runCatching { verified.stagedDirectory.deleteRecursively() }
    }
}

private fun readVerified(verified: VerifiedPluginPackage): VerifiedBuiltInCapability {
    val manifest = JsonFields.obj(Json.parse(File(verified.stagedDirectory, "manifest.json").readText()))
        ?: error("MANIFEST_INVALID")
    val declarations = JsonFields.obj(JsonFields.field(manifest, "capabilities")) ?: error("MANIFEST_INVALID")
    val primitives = verified.capabilities.kernelPrimitives
    val entries = (JsonFields.field(declarations, "provides") as? JsonValue.JArray)?.items.orEmpty().map { item ->
        val cap = item as? JsonValue.JObject ?: error("MANIFEST_INVALID")
        val capabilityId = JsonFields.string(cap, "id") ?: error("MANIFEST_INVALID")
        val schemaPath = JsonFields.string(cap, "schema") ?: error("MANIFEST_INVALID")
        val operationPrimitives = PluginCapabilityRisk.primitivesFor(capabilityId, primitives)
        VerifiedCapability(
            verified.identity,
            capabilityId,
            JsonFields.string(cap, "version") ?: error("MANIFEST_INVALID"),
            Json.parse(File(verified.stagedDirectory, schemaPath).readText()),
            PluginCapabilityRisk.classify(verified.runtime.type, operationPrimitives),
            operationPrimitives,
        )
    }
    val security = JsonFields.obj(JsonFields.field(manifest, "security"))
    return VerifiedBuiltInCapability(
        pluginId = verified.identity.pluginId,
        identity = verified.identity,
        runtimeType = verified.runtime.type,
        abiVersion = verified.runtime.abiVersion,
        entrypoint = verified.runtime.entrypoint,
        capabilities = entries,
        declaredPrimitives = entries.map { it.key }.toSet() + primitives,
        surface = verified.security.surface,
        backgroundRequested = JsonFields.bool(JsonFields.obj(JsonFields.field(security, "background")), "requested") == true,
        backgroundMinimumIntervalSeconds = (JsonFields.long(JsonFields.obj(JsonFields.field(security, "background")), "minimumIntervalSeconds") ?: 60L).coerceAtLeast(1L),
        payload = File(verified.stagedDirectory, verified.runtime.payload ?: error("PAYLOAD_MISSING")).readBytes(),
    )
}
