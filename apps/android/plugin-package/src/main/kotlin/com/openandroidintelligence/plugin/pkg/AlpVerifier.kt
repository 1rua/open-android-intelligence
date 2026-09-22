package com.openandroidintelligence.plugin.pkg

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val MANIFEST_ENTRY = "manifest.json"
private const val FILES_ENTRY = "files.json"
private const val SIGNATURE_ENTRY = "signature.ed25519"
private val RESERVED_ENTRIES = setOf(MANIFEST_ENTRY, FILES_ENTRY, SIGNATURE_ENTRY)
private val SIGNATURE_DOMAIN = "OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1\n".toByteArray(Charsets.UTF_8)
private val PLUGIN_ID_PATTERN = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$")

/**
 * Verifies an `.alp` package before anything is trusted or installed.
 *
 * Order is fixed by the contract: container bounds → paths → count/size →
 * digests → author key → signature → schema → host compatibility. Security
 * changes are not decided here; [PluginUpdatePolicy] does that.
 */
class AlpVerifier(
    private val limits: PackageLimits,
    private val stagingRoot: File? = null,
    private val hostVersion: String = "2.0.0",
) {
    /** Maximum compressed bytes accepted from the wire; a real package is far smaller. */
    private val maxInputBytes = limits.maxTotalUncompressedBytes

    fun verify(input: InputStream): VerifiedPluginPackage {
        val archive = spoolToTempFile(input)
        return try {
            verifyArchive(archive)
        } finally {
            archive.delete()
        }
    }

    fun verify(bytes: ByteArray): VerifiedPluginPackage {
        val archive = java.nio.file.Files.createTempFile("alp-", ".zip").toFile()
        archive.writeBytes(bytes)
        return try {
            verifyArchive(archive)
        } finally {
            archive.delete()
        }
    }

    /**
     * The platform ZIP reader rejects some malformed containers itself. Its
     * exception is translated so every rejection leaves this class as a
     * [PackageRejected] carrying the contract's own code.
     */
    private fun verifyArchive(archive: File): VerifiedPluginPackage = try {
        verifyArchiveInner(archive)
    } catch (cause: java.util.zip.ZipException) {
        val message = cause.message.orEmpty()
        if (message.contains("duplicate", ignoreCase = true)) {
            throw PackageRejected("DUPLICATE_ENTRY")
        }
        // Android's ZIP reader screens entry names when the archive is opened,
        // so on a device it rejects `..` segments before [validateEntryName]
        // ever runs. The JVM reader does not, which is why this branch has a
        // device test and no unit test. The contract still demands that a
        // traversal surfaces as TRAVERSAL, not as a generic container error.
        if (message.contains("invalid zip entry path", ignoreCase = true)) {
            throw PackageRejected("TRAVERSAL:${cause.message}")
        }
        throw PackageRejected("CONTAINER_INVALID:${cause.message}")
    }

    private fun spoolToTempFile(input: InputStream): File {
        val file = java.nio.file.Files.createTempFile("alp-", ".zip").toFile()
        file.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                // Bounds the compressed stream itself: a hostile archive cannot
                // make us buffer unboundedly before size checks even run.
                if (total > maxInputBytes) {
                    file.delete()
                    throw PackageRejected("SIZE_LIMIT:input")
                }
                out.write(buffer, 0, read)
            }
        }
        return file
    }

    /**
     * Verification reads the central directory, not a streaming scan, so every
     * declared size and name is known before any payload byte is inflated.
     */
    private fun verifyArchiveInner(archive: File): VerifiedPluginPackage {
        val entries = LinkedHashMap<String, ByteArray>()
        var previousName: String? = null
        ZipFile(archive).use { zip ->
            var totalUncompressed = 0L
            for (entry in zip.entries()) {
                val name = validateEntryName(entry)
                if (entries.containsKey(name)) throw PackageRejected("DUPLICATE_ENTRY:$name")
                if (entries.size >= limits.maxEntries) throw PackageRejected("TOO_MANY_ENTRIES")

                // Declared sizes are checked before a single payload byte is read.
                val declared = declaredSize(entry)
                if (declared > limits.maxSingleEntryBytes) {
                    throw PackageRejected("SIZE_LIMIT:entry:$name")
                }
                totalUncompressed += declared
                if (totalUncompressed > limits.maxTotalUncompressedBytes) {
                    throw PackageRejected("SIZE_LIMIT:total")
                }
                // Container determinism: entries must ascend by UTF-8 path bytes.
                if (previousName != null && compareUtf8(previousName, name) >= 0) {
                    throw PackageRejected("UNSORTED_ENTRY:$name")
                }
                previousName = name

                entries[name] = readBounded(zip.getInputStream(entry), declared, name)
            }
        }

        val manifestBytes = entries[MANIFEST_ENTRY] ?: throw PackageRejected("MISSING_MANIFEST")
        val filesBytes = entries[FILES_ENTRY] ?: throw PackageRejected("MISSING_FILES_INDEX")
        val signatureText = entries[SIGNATURE_ENTRY]?.toString(Charsets.UTF_8)
            ?: throw PackageRejected("MISSING_SIGNATURE")

        val declared = parseFilesIndex(filesBytes)
        val presented = entries.keys - RESERVED_ENTRIES
        val undeclared = presented - declared.keys
        if (undeclared.isNotEmpty()) {
            throw PackageRejected("UNDECLARED_ENTRY:${undeclared.first()}")
        }
        val missing = declared.keys - presented
        if (missing.isNotEmpty()) {
            throw PackageRejected("MISSING_ENTRY:${missing.first()}")
        }

        for ((path, expected) in declared) {
            val actual = sha256Hex(entries[path]!!)
            if (!actual.equals(expected.sha256, ignoreCase = true)) {
                throw PackageRejected("DIGEST_MISMATCH:$path")
            }
            if (entries[path]!!.size.toLong() != expected.size) {
                throw PackageRejected("SIZE_MISMATCH:$path")
            }
        }

        val manifest = parseManifest(manifestBytes)
        val authorKey = decodeAuthorKey(manifest)

        verifySignature(manifestBytes, filesBytes, signatureText, authorKey)

        val staged = stagingRoot ?: createTempStaging()
        staged.mkdirs()
        for ((path, content) in entries) {
            if (path in RESERVED_ENTRIES) continue
            val target = File(staged, path)
            target.parentFile?.mkdirs()
            target.writeBytes(content)
        }

        return VerifiedPluginPackage(
            identity = PluginIdentity(
                pluginId = manifest.pluginId,
                authorKeyFingerprint = sha256Hex(authorKey),
                version = manifest.version,
            ),
            version = SemVer.parse(manifest.version) ?: throw PackageRejected("SCHEMA_INVALID:version"),
            runtime = manifest.runtime,
            capabilities = CapabilityDeclaration(manifest.providedCapabilities, manifest.kernelPrimitives),
            security = SecurityDeclaration(manifest.surface),
            stagedDirectory = staged,
        )
    }

    private fun validateEntryName(entry: ZipEntry): String {
        val name = entry.name
        if (name.isEmpty()) throw PackageRejected("EMPTY_ENTRY_NAME")
        if (name.startsWith("/")) throw PackageRejected("ABSOLUTE_PATH:$name")
        if (name.contains("\\")) throw PackageRejected("BACKSLASH_PATH:$name")
        for (segment in name.split("/")) {
            when (segment) {
                "" -> throw PackageRejected("EMPTY_SEGMENT:$name")
                "." -> throw PackageRejected("DOT_SEGMENT:$name")
                ".." -> throw PackageRejected("TRAVERSAL:$name")
            }
        }
        // NFC is required so the same path cannot be presented in two forms.
        if (name != java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)) {
            throw PackageRejected("NON_NFC_PATH:$name")
        }
        return name
    }

    private fun declaredSize(entry: ZipEntry): Long {
        val size = entry.size
        // An unknown size cannot be bounded, so it is rejected rather than trusted.
        if (size < 0) throw PackageRejected("UNKNOWN_ENTRY_SIZE:${entry.name}")
        return size
    }

    private fun readBounded(zip: InputStream, declared: Long, name: String): ByteArray {
        val cap = minOf(declared, limits.maxSingleEntryBytes).toInt()
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            // The declared size can lie; the byte count is the authority.
            if (total > cap) throw PackageRejected("SIZE_LIMIT:actual:$name")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun parseFilesIndex(bytes: ByteArray): Map<String, FileIndexEntry> {
        val root = Json.parse(bytes.toString(Charsets.UTF_8)) as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:filesIndex")
        val result = LinkedHashMap<String, FileIndexEntry>()
        var previous: String? = null
        for (item in root.items) {
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:filesEntry")
            val path = (obj.get("path") as? JsonValue.JString)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:filesPath")
            val sha = (obj.get("sha256") as? JsonValue.JString)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:filesSha256")
            val size = (obj.get("size") as? JsonValue.JNumber)?.asLong()
                ?: throw PackageRejected("SCHEMA_INVALID:filesSize")
            if (!sha.matches(Regex("^[0-9a-f]{64}$"))) {
                throw PackageRejected("SCHEMA_INVALID:filesSha256Format:$path")
            }
            // The index must be sorted and duplicate-free.
            if (previous != null && previous >= path) {
                throw PackageRejected("FILES_INDEX_UNSORTED:$path")
            }
            previous = path
            result[path] = FileIndexEntry(sha256 = sha, size = size)
        }
        return result
    }

    private data class FileIndexEntry(val sha256: String, val size: Long)

    private data class ParsedManifest(
        val pluginId: String,
        val version: String,
        val runtime: RuntimeDeclaration,
        val providedCapabilities: Set<String>,
        val kernelPrimitives: Set<String>,
        val surface: SecuritySurface,
        val authorPublicKey: String,
    )

    private fun parseManifest(bytes: ByteArray): ParsedManifest {
        val root = Json.parse(bytes.toString(Charsets.UTF_8)) as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:manifest")

        // §5: 所有对象默认拒绝未知字段（§5:156）。旧形状（manifestVersion、
        // 顶层 kernelPrimitives 等）因此被明确拒绝，不做兼容回退：契约 §1
        // 规定"必须"即"不满足即拒绝安装或运行"。
        val schemaVersion = (root.get("schemaVersion") as? JsonValue.JString)?.value
            ?: throw PackageRejected("SCHEMA_INVALID:schemaVersion")
        if (schemaVersion != "1.0") throw PackageRejected("SCHEMA_INVALID:schemaVersion:$schemaVersion")
        rejectUnknownFields(
            root,
            setOf(
                "schemaVersion", "plugin", "author", "runtime", "compatibility",
                "capabilities", "security", "ui", "state",
            ),
            "manifestField",
        )

        val plugin = root.get("plugin") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:plugin")
        rejectUnknownFields(plugin, setOf("id", "version", "name", "description"), "pluginField")
        val pluginId = (plugin.get("id") as? JsonValue.JString)?.value
            ?: throw PackageRejected("SCHEMA_INVALID:pluginId")
        if (!PLUGIN_ID_PATTERN.matches(pluginId)) throw PackageRejected("SCHEMA_INVALID:pluginIdFormat")

        val version = (plugin.get("version") as? JsonValue.JString)?.value
            ?: throw PackageRejected("SCHEMA_INVALID:pluginVersion")
        if (SemVer.parse(version) == null) throw PackageRejected("SCHEMA_INVALID:pluginVersionFormat")
        if ((plugin.get("name") as? JsonValue.JString)?.value == null) {
            throw PackageRejected("SCHEMA_INVALID:pluginName")
        }
        if ((plugin.get("description") as? JsonValue.JString)?.value == null) {
            throw PackageRejected("SCHEMA_INVALID:pluginDescription")
        }

        val author = root.get("author") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:author")
        rejectUnknownFields(author, setOf("algorithm", "publicKey"), "authorField")
        val algorithm = (author.get("algorithm") as? JsonValue.JString)?.value
        if (algorithm != "Ed25519") throw PackageRejected("SCHEMA_INVALID:authorAlgorithm")
        val publicKey = (author.get("publicKey") as? JsonValue.JString)?.value
            ?: throw PackageRejected("SCHEMA_INVALID:authorPublicKey")

        val runtimeObj = root.get("runtime") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:runtime")
        val runtimeType = (runtimeObj.get("type") as? JsonValue.JString)?.value
            ?: throw PackageRejected("SCHEMA_INVALID:runtimeType")
        if (runtimeType !in setOf("protected-wasm", "developer-native", "companion")) {
            throw PackageRejected("SCHEMA_INVALID:runtimeTypeUnknown:$runtimeType")
        }
        // 每种运行类型允许的字段集（§5.1）；未知字段按运行类型拒绝。
        val runtimeFields = when (runtimeType) {
            "protected-wasm" -> setOf("type", "abiVersion", "entrypoint", "payload")
            "developer-native" -> setOf("type", "entrypointClass", "payload")
            else -> setOf(
                "type", "payload", "packageName", "certificateSha256",
                "minVersionCode", "ipcContract",
            )
        }
        rejectUnknownFields(runtimeObj, runtimeFields, "runtimeField")
        val runtime = RuntimeDeclaration(
            type = runtimeType,
            abiVersion = (runtimeObj.get("abiVersion") as? JsonValue.JString)?.value,
            entrypoint = (runtimeObj.get("entrypoint") as? JsonValue.JString)?.value,
            payload = (runtimeObj.get("payload") as? JsonValue.JString)?.value,
        )

        val capabilities = root.get("capabilities") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:capabilities")
        rejectUnknownFields(capabilities, setOf("provides", "depends", "kernelPrimitives"), "capabilitiesField")
        val provided = parseCapabilityEntries(capabilities)
        val primitives = parseKernelPrimitiveEntries(capabilities)
        parseDependencyEntries(capabilities)

        val security = root.get("security") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:security")
        rejectUnknownFields(security, setOf("network", "background", "resources"), "securityField")
        val surface = parseSurface(security, runtimeType)

        val compatibility = root.get("compatibility") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:compatibility")
        rejectUnknownFields(compatibility, setOf("androidHost", "gatewayProtocol"), "compatibilityField")
        val androidHost = (compatibility.get("androidHost") as? JsonValue.JString)?.value
            ?: throw PackageRejected("SCHEMA_INVALID:androidHost")
        if ((compatibility.get("gatewayProtocol") as? JsonValue.JString)?.value == null) {
            throw PackageRejected("SCHEMA_INVALID:gatewayProtocol")
        }
        if (!hostSatisfies(hostVersion, androidHost)) {
            throw PackageRejected("INCOMPATIBLE_HOST:$androidHost")
        }

        // §5: ui 与 state 是必选节；缺失或类型不符即拒绝。
        val ui = root.get("ui") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:ui")
        rejectUnknownFields(ui, setOf("settings", "cards"), "uiField")
        if (ui.get("settings") !is JsonValue.JArray) throw PackageRejected("SCHEMA_INVALID:uiSettings")
        if (ui.get("cards") !is JsonValue.JArray) throw PackageRejected("SCHEMA_INVALID:uiCards")

        val state = root.get("state") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:state")
        rejectUnknownFields(state, setOf("schemaVersion", "portableExport"), "stateField")
        if (state.get("schemaVersion") !is JsonValue.JNumber) {
            throw PackageRejected("SCHEMA_INVALID:stateSchemaVersion")
        }
        if (state.get("portableExport") !is JsonValue.JBoolean) {
            throw PackageRejected("SCHEMA_INVALID:statePortableExport")
        }

        return ParsedManifest(
            pluginId = pluginId,
            version = version,
            runtime = runtime,
            providedCapabilities = provided,
            kernelPrimitives = primitives,
            surface = surface,
            authorPublicKey = publicKey,
        )
    }

    /** §5:156 的未知字段拒绝；拒绝码携带首个未知字段名，便于审计定位。 */
    private fun rejectUnknownFields(
        obj: JsonValue.JObject,
        allowed: Set<String>,
        code: String,
    ) {
        val unknown = obj.fields.map { it.first }.firstOrNull { it !in allowed }
        if (unknown != null) throw PackageRejected("SCHEMA_INVALID:$code:$unknown")
    }

    /** §5: capabilities.provides 必须是 {id, version, schema} 对象数组。 */
    private fun parseCapabilityEntries(capabilities: JsonValue.JObject): Set<String> {
        val array = capabilities.get("provides") as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:provides")
        val ids = LinkedHashSet<String>()
        for (item in array.items) {
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:provides")
            rejectUnknownFields(obj, setOf("id", "version", "schema"), "provides")
            val id = (obj.get("id") as? JsonValue.JString)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:provides")
            if ((obj.get("version") as? JsonValue.JString)?.value == null ||
                (obj.get("schema") as? JsonValue.JString)?.value == null
            ) {
                throw PackageRejected("SCHEMA_INVALID:provides")
            }
            ids += id
        }
        return ids
    }

    /** §5: capabilities.kernelPrimitives 必须是 {id, version, purpose} 对象数组。 */
    private fun parseKernelPrimitiveEntries(capabilities: JsonValue.JObject): Set<String> {
        val array = capabilities.get("kernelPrimitives") as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:kernelPrimitives")
        val ids = LinkedHashSet<String>()
        for (item in array.items) {
            val obj = item as? JsonValue.JObject
                ?: throw PackageRejected("SCHEMA_INVALID:kernelPrimitives")
            rejectUnknownFields(obj, setOf("id", "version", "purpose"), "kernelPrimitives")
            val id = (obj.get("id") as? JsonValue.JString)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:kernelPrimitives")
            if ((obj.get("version") as? JsonValue.JString)?.value == null ||
                (obj.get("purpose") as? JsonValue.JString)?.value == null
            ) {
                throw PackageRejected("SCHEMA_INVALID:kernelPrimitives")
            }
            ids += id
        }
        return ids
    }

    /** §6: capabilities.depends 必须是 {capability, version, required} 对象数组。 */
    private fun parseDependencyEntries(capabilities: JsonValue.JObject) {
        val array = capabilities.get("depends") as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:depends")
        for (item in array.items) {
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:depends")
            rejectUnknownFields(obj, setOf("capability", "version", "required"), "depends")
            if ((obj.get("capability") as? JsonValue.JString)?.value == null ||
                (obj.get("version") as? JsonValue.JString)?.value == null ||
                obj.get("required") !is JsonValue.JBoolean
            ) {
                throw PackageRejected("SCHEMA_INVALID:depends")
            }
        }
    }

    private fun parseSurface(security: JsonValue.JObject, runtimeType: String): SecuritySurface {
        val network = security.get("network") as? JsonValue.JArray
            ?: throw PackageRejected("SCHEMA_INVALID:network")
        val hosts = network.items.mapNotNullTo(LinkedHashSet()) { item ->
            val obj = item as? JsonValue.JObject ?: throw PackageRejected("SCHEMA_INVALID:networkRule")
            (obj.get("host") as? JsonValue.JString)?.value
        }

        val background = security.get("background") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:background")
        rejectUnknownFields(background, setOf("requested", "minimumIntervalSeconds"), "backgroundField")
        val backgroundRequested =
            (background.get("requested") as? JsonValue.JBoolean)?.value
                ?: throw PackageRejected("SCHEMA_INVALID:backgroundRequested")
        // §5: requested 为 false 时契约示例写作 null；允许数字（间隔）或 null。
        when (background.get("minimumIntervalSeconds")) {
            is JsonValue.JNull, is JsonValue.JNumber -> {}
            else -> throw PackageRejected("SCHEMA_INVALID:minimumIntervalSeconds")
        }

        val resources = security.get("resources") as? JsonValue.JObject
            ?: throw PackageRejected("SCHEMA_INVALID:resources")
        rejectUnknownFields(
            resources,
            setOf(
                "maxInvocationMillis", "maxMemoryBytes", "maxStorageBytes",
                "maxConcurrentInvocations", "maxDailyNetworkBytes",
            ),
            "resourcesField",
        )

        fun longField(name: String): Long =
            (resources.get(name) as? JsonValue.JNumber)?.asLong()
                ?: throw PackageRejected("SCHEMA_INVALID:$name")

        val companionPackageName = if (runtimeType == "companion") {
            val runtime = security // companion package name lives on the runtime block
            (runtime.get("packageName") as? JsonValue.JString)?.value
        } else {
            null
        }

        return SecuritySurface(
            kernelPrimitives = emptySet(),
            networkHosts = hosts,
            maxStorageBytes = longField("maxStorageBytes"),
            maxMemoryBytes = longField("maxMemoryBytes"),
            maxInvocationMillis = longField("maxInvocationMillis"),
            maxConcurrentInvocations = longField("maxConcurrentInvocations").toInt(),
            maxDailyNetworkBytes = longField("maxDailyNetworkBytes"),
            backgroundRequested = backgroundRequested,
            companionPackageName = companionPackageName,
            nativeAbis = emptySet(),
        )
    }

    private fun decodeAuthorKey(manifest: ParsedManifest): ByteArray {
        val raw = runCatching {
            Base64.getUrlDecoder().decode(manifest.authorPublicKey)
        }.getOrNull() ?: throw PackageRejected("SCHEMA_INVALID:authorPublicKeyEncoding")
        if (raw.size != 32) throw PackageRejected("SCHEMA_INVALID:authorPublicKeyLength")
        return raw
    }

    private fun verifySignature(
        manifestBytes: ByteArray,
        filesBytes: ByteArray,
        signatureText: String,
        authorKey: ByteArray,
    ) {
        val signature = runCatching {
            Base64.getUrlDecoder().decode(signatureText.trim())
        }.getOrNull() ?: throw PackageRejected("SIGNATURE_INVALID:encoding")
        if (signature.size != 64) throw PackageRejected("SIGNATURE_INVALID:length")

        val preimage = SIGNATURE_DOMAIN + manifestBytes + byteArrayOf('\n'.code.toByte()) + filesBytes
        val publicKey = runCatching { ed25519PublicKey(authorKey) }.getOrNull()
            ?: throw PackageRejected("SIGNATURE_INVALID:unsupportedKey")
        val verifier = runCatching {
            java.security.Signature.getInstance("Ed25519")
        }.getOrNull() ?: throw PackageRejected("SIGNATURE_INVALID:unsupportedAlgorithm")

        verifier.initVerify(publicKey)
        verifier.update(preimage)
        if (!verifier.verify(signature)) throw PackageRejected("SIGNATURE_INVALID")
    }

    /**
     * A raw 32-byte Ed25519 key is not an X.509 SubjectPublicKeyInfo; the
     * standard 12-byte prefix is prepended so the platform key factory accepts it.
     */
    private fun ed25519PublicKey(raw: ByteArray): java.security.PublicKey {
        val prefix = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
        val encoded = prefix + raw
        return java.security.KeyFactory.getInstance("Ed25519")
            .generatePublic(java.security.spec.X509EncodedKeySpec(encoded))
    }

    internal fun hostSatisfies(host: String, range: String): Boolean {
        val hostSemver = SemVer.parse(host) ?: return false
        // Supports the ">=a.b.c <d.e.f" form used by the contract.
        val bounds = range.trim().split("\\s+".toRegex())
        for (bound in bounds) {
            if (bound.startsWith(">=")) {
                val other = SemVer.parse(bound.removePrefix(">=")) ?: return false
                if (compare(hostSemver, other) < 0) return false
            } else if (bound.startsWith("<")) {
                val other = SemVer.parse(bound.removePrefix("<")) ?: return false
                if (compare(hostSemver, other) >= 0) return false
            } else {
                return false
            }
        }
        return true
    }

    private fun compareUtf8(a: String, b: String): Int {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        val len = minOf(ab.size, bb.size)
        for (i in 0 until len) {
            if (ab[i] != bb[i]) return ab[i].compareTo(bb[i])
        }
        return ab.size.compareTo(bb.size)
    }

    private fun compare(a: SemVer, b: SemVer): Int {
        if (a.major != b.major) return a.major.compareTo(b.major)
        if (a.minor != b.minor) return a.minor.compareTo(b.minor)
        return a.patch.compareTo(b.patch)
    }

    private fun createTempStaging(): File =
        java.nio.file.Files.createTempDirectory("alp-staging").toFile()

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
