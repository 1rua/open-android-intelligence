package com.openandroidintelligence.plugin.pkg

/** Thrown for any package that must not be installed. The code is the message prefix. */
class PackageRejected(code: String) : IllegalArgumentException(code)

data class PackageLimits(
    val maxEntries: Int,
    val maxSingleEntryBytes: Long,
    val maxTotalUncompressedBytes: Long,
)

data class PluginIdentity(
    val pluginId: String,
    val authorKeyFingerprint: String,
    val version: String,
)

data class SemVer(val major: Int, val minor: Int, val patch: Int) {
    companion object {
        private val PATTERN = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$")

        fun parse(value: String): SemVer? {
            val match = PATTERN.matchEntire(value) ?: return null
            return SemVer(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }
    }
}

data class RuntimeDeclaration(
    val type: String,
    val abiVersion: String?,
    val entrypoint: String?,
    val payload: String?,
    val entrypointClass: String? = null,
)

data class CapabilityDeclaration(
    val providedIds: Set<String>,
    val kernelPrimitives: Set<String>,
)

data class SecurityDeclaration(
    val surface: SecuritySurface,
)

data class VerifiedFile(val size: Long, val sha256: String)

class VerifiedPluginPackage internal constructor(
    val identity: PluginIdentity,
    val version: SemVer,
    val runtime: RuntimeDeclaration,
    val capabilities: CapabilityDeclaration,
    val security: SecurityDeclaration,
    val stagedDirectory: java.io.File,
    internal val verifiedFiles: Map<String, VerifiedFile>,
)
