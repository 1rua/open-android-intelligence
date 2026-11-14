package com.openandroidintelligence.mobile.plugins

import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.File
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/**
 * 构造真实可安装的 `.alp` 测试包：真实 Ed25519 密钥、真实签名、§5 形状的
 * manifest，与 `plugin-tooling` 产物同构。供插件管理区域的安装链路测试使用。
 */
internal object TestAlpPackages {

    /** The shipped notifications manifest/schema and compiled Rust payload, signed with an isolated test author. */
    fun compiledNotificationsPackage(pluginId: String = "org.openandroidintelligence.notifications"): ByteArray {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it,"plugins/notifications/manifest.json").isFile }
        val author = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(author.public.encoded.copyOfRange(12,44))
        val template = Json.parse(File(root,"plugins/notifications/manifest.json").readText()) as JsonValue.JObject
        val manifest = Json.canonical(template.copy(fields=template.fields.map { (name,value) ->
            name to when (name) {
                "author" -> Json.of(mapOf("algorithm" to "Ed25519","publicKey" to publicKey))
                "plugin" -> (value as JsonValue.JObject).copy(fields = value.fields.map { (key, item) ->
                    key to if (key == "id") Json.of(pluginId) else item
                })
                else -> value
            }
        })).encodeToByteArray()
        val content = listOf("payload/notifications.wasm" to File(root,"plugins/target/wasm32-unknown-unknown/release/notifications.wasm").readBytes(),
            "schemas/notifications.json" to File(root,"plugins/schemas/notifications.json").readBytes())
        val index = filesJson(content)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(author.private);update("OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1\n".encodeToByteArray()+manifest+byteArrayOf(10)+index);sign()
        }
        return zipOf(content+listOf("manifest.json" to manifest,"files.json" to index,
            "signature.ed25519" to Base64.getUrlEncoder().withoutPadding().encode(signature)))
    }

    /** Real compiled SMS code and its checked-in schema, signed with a fresh test key. */
    fun compiledSmsPackage(queryCapabilityId: String = "org.openandroidintelligence.sms.query", queryVersion: String = "1.0.0",
        author: java.security.KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), version: String = "1.0.0",
        capabilityDeclaration: JsonValue.JObject? = null, pluginId: String = "org.openandroidintelligence.sms"): ByteArray {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (!File(root, "plugins/sms/manifest.json").isFile) {
            root = root.parentFile ?: error("Repository root unavailable")
        }
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(author.public.encoded.copyOfRange(12, 44))
        val template = Json.parse(File(root, "plugins/sms/manifest.json").readText()) as JsonValue.JObject
        val manifest = Json.canonical(template.copy(fields = template.fields.map { (name, value) ->
            name to when (name) {
                "author" -> Json.of(mapOf("algorithm" to "Ed25519", "publicKey" to publicKey))
                "plugin" -> (value as JsonValue.JObject).copy(fields = value.fields.map { (key,item) ->
                    key to when (key) {
                        "id" -> Json.of(pluginId)
                        "version" -> Json.of(version)
                        else -> item
                    }
                })
                "capabilities" -> capabilityDeclaration ?: (value as JsonValue.JObject).copy(fields = value.fields.map { (field,caps) ->
                    field to if(field == "provides") JsonValue.JArray((caps as JsonValue.JArray).items.map { entry ->
                        val cap=entry as JsonValue.JObject
                        if ((cap.fields.toMap()["id"] as? JsonValue.JString)?.value != "org.openandroidintelligence.sms.query") cap
                        else cap.copy(fields=cap.fields.map { (key,item) -> key to when(key) {
                            "id" -> Json.of(queryCapabilityId); "version" -> Json.of(queryVersion); else -> item
                        } })
                    }) else caps
                })
                else -> value
            }
        })).toByteArray()
        val content = listOf("payload/sms.wasm" to File(root, "plugins/target/wasm32-unknown-unknown/release/sms.wasm").readBytes()) +
            listOf("sms", "sms-schedule", "sms-job-status", "sms-cancel", "sms-send").map { name ->
                "schemas/$name.json" to File(root, "plugins/schemas/$name.json").readBytes()
            }
        val index = filesJson(content)
        val preimage = "OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1\n".toByteArray() + manifest + byteArrayOf(10) + index
        val signature = Signature.getInstance("Ed25519").run { initSign(author.private); update(preimage); sign() }
        return zipOf(content + listOf("manifest.json" to manifest, "files.json" to index,
            "signature.ed25519" to Base64.getUrlEncoder().withoutPadding().encode(signature)))
    }

    fun compiledScreenReaderPackage(): ByteArray = compiledReferencePackage("screen-reader")
    fun compiledScreenCapturePackage(): ByteArray = compiledReferencePackage("screen-capture")
    fun compiledScreenControlPackage(): ByteArray = compiledReferencePackage("screen-control")
    fun compiledCallLogPackage(): ByteArray = compiledReferencePackage("call-log")

    /** 每项都使用仓库真实 manifest、Schema 和编译的 Rust WASM，仅作者换成隔离测试密钥。 */
    fun compiledBundledPackage(plugin: BundledPlugin): ByteArray = when (plugin) {
        BundledPlugin.NOTIFICATIONS -> compiledNotificationsPackage()
        BundledPlugin.SMS -> compiledSmsPackage()
        BundledPlugin.CALL_LOG -> compiledCallLogPackage()
        BundledPlugin.SCREEN_READER -> compiledScreenReaderPackage()
        BundledPlugin.SCREEN_CAPTURE -> compiledScreenCapturePackage()
        BundledPlugin.SCREEN_CONTROL -> compiledScreenControlPackage()
    }

    private fun compiledReferencePackage(name: String): ByteArray {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
            .first { File(it, "plugins/$name/manifest.json").isFile }
        val author = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(author.public.encoded.copyOfRange(12, 44))
        val template = Json.parse(File(root, "plugins/$name/manifest.json").readText()) as JsonValue.JObject
        val manifest = Json.canonical(template.copy(fields = template.fields.map { (key, value) ->
            key to if (key == "author") Json.of(mapOf("algorithm" to "Ed25519", "publicKey" to publicKey)) else value
        })).encodeToByteArray()
        val runtime = template.fields.toMap().getValue("runtime") as JsonValue.JObject
        val payload = (runtime.fields.toMap().getValue("payload") as JsonValue.JString).value
        val capabilities = template.fields.toMap().getValue("capabilities") as JsonValue.JObject
        val provides = capabilities.fields.toMap().getValue("provides") as JsonValue.JArray
        val schemas = provides.items.map { entry ->
            ((entry as JsonValue.JObject).fields.toMap().getValue("schema") as JsonValue.JString).value
        }.distinct()
        val content = listOf(payload to File(root, "plugins/target/wasm32-unknown-unknown/release/${payload.substringAfterLast('/')}").readBytes()) +
            schemas.map { path -> path to File(root, "plugins/$path").readBytes() }
        val index = filesJson(content)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(author.private)
            update("OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1\n".encodeToByteArray() + manifest + byteArrayOf(10) + index)
            sign()
        }
        return zipOf(content + listOf("manifest.json" to manifest, "files.json" to index,
            "signature.ed25519" to Base64.getUrlEncoder().withoutPadding().encode(signature)))
    }

    /** §8 声明式设置贡献：一个带 set-setting 动作的 toggle。 */
    const val SETTINGS_CONTRIBUTION_JSON =
        """{"id":"settings-main","title":"示例插件设置","root":{""" +
            """"type":"section","id":"s1","label":"行为","children":[""" +
            """{"type":"toggle","id":"t1","label":"合并同类通知","value":false,"action":"set-setting"}]}}"""

    fun signedPackage(
        id: String = "org.example.notifications",
        version: String = "1.0.0",
        name: String = "示例通知插件",
        author: java.security.KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
        /**
         * 覆盖 capabilities 段（provides / kernelPrimitives）。
         *
         * 设备能力授权的界面必须按插件**实际声明**的原语推导入口，
         * 因此测试需要能构造只声明某一种能力的包，而默认包不带任何原语。
         */
        capabilities: JsonValue.JObject? = null,
        settingsContributionJson: String = SETTINGS_CONTRIBUTION_JSON,
    ): ByteArray {
        val parts = buildParts(id, version, name, author, capabilities, settingsContributionJson)
        return assemble(parts)
    }

    /** 签名用 manifest_A，装入 manifest_B：必然被拒绝为 SIGNATURE_INVALID。 */
    fun tamperedManifestPackage(
        id: String = "org.example.notifications",
        version: String = "1.0.0",
        name: String = "示例通知插件",
    ): ByteArray {
        val parts = buildParts(id, version, name)
        val otherManifest = String(parts.manifest, Charsets.UTF_8)
            .replace(name, name + "改")
            .toByteArray(Charsets.UTF_8)
        return assemble(parts, manifestOverride = otherManifest)
    }

    private class Parts(
        val manifest: ByteArray,
        val filesIndex: ByteArray,
        val payload: ByteArray,
        val uiFile: ByteArray,
        val signatureText: String,
        /** 声明式能力所需的 schema 文件；宿主载入时会按 manifest 里的路径读取。 */
        val schemaFiles: List<Pair<String, ByteArray>> = emptyList(),
    )

    private fun buildParts(
        id: String,
        version: String,
        name: String,
        keyPair: java.security.KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
        capabilities: JsonValue.JObject? = null,
        settingsContributionJson: String = SETTINGS_CONTRIBUTION_JSON,
    ): Parts {
        // X509 编码 = 12 字节固定前缀 + 32 字节裸密钥；校验器按同一规则还原。
        val rawPublicKey = keyPair.public.encoded.copyOfRange(12, 44)
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(rawPublicKey)

        val manifest = manifestJson(id, version, name, publicKey, capabilities)
        val payload = byteArrayOf(0, 0x61, 0x73, 0x6d) // \0asm
        val uiFile = settingsContributionJson.toByteArray(Charsets.UTF_8)
        // 每个声明都需要包内存在对应 schema 文件，否则宿主载入会失败。
        val schemaFiles = declaredSchemaPaths(capabilities).map { path -> path to MINIMAL_SCHEMA }

        val filesIndex = filesJson(
            listOf(
                "payload/plugin.wasm" to payload,
                "ui/settings-main.json" to uiFile,
            ) + schemaFiles,
        )

        // 契约 §4：preimage = 域分隔串 + manifest + '\n' + files.json。
        val preimage = "OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1\n".toByteArray(Charsets.UTF_8) +
            manifest + byteArrayOf('\n'.code.toByte()) + filesIndex
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(preimage)
            sign()
        }
        val signatureText = Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        return Parts(manifest, filesIndex, payload, uiFile, signatureText, schemaFiles)
    }

    private fun assemble(parts: Parts, manifestOverride: ByteArray? = null): ByteArray =
        zipOf(
            listOf(
                "files.json" to parts.filesIndex,
                "manifest.json" to (manifestOverride ?: parts.manifest),
                "payload/plugin.wasm" to parts.payload,
                "signature.ed25519" to parts.signatureText.toByteArray(Charsets.UTF_8),
                "ui/settings-main.json" to parts.uiFile,
            ) + parts.schemaFiles,
        )

    private fun manifestJson(
        id: String,
        version: String,
        name: String,
        publicKey: String,
        capabilities: JsonValue.JObject? = null,
    ): ByteArray {
        val capabilitiesSegment = capabilities?.let { Json.canonical(it) }
            ?: """{"provides":[],"depends":[],"kernelPrimitives":[]}"""
        return ("""{"schemaVersion":"1.0","plugin":{"id":"$id","version":"$version","name":"$name","description":"测试用示例插件"},""" +
            """"author":{"algorithm":"Ed25519","publicKey":"$publicKey"},""" +
            """"runtime":{"type":"protected-wasm","abiVersion":"1.0","entrypoint":"open_android_intelligence_plugin_main","payload":"payload/plugin.wasm"},""" +
            """"compatibility":{"androidHost":">=2.0.0 <3.0.0","gatewayProtocol":">=2.0 <3.0"},""" +
            """"capabilities":$capabilitiesSegment,""" +
            """"security":{"network":[],"background":{"requested":false,"minimumIntervalSeconds":null},""" +
            """"resources":{"maxInvocationMillis":5000,"maxMemoryBytes":16777216,"maxStorageBytes":10485760,"maxConcurrentInvocations":1,"maxDailyNetworkBytes":0}},""" +
            """"ui":{"settings":["ui/settings-main.json"],"cards":[]},""" +
            """"state":{"schemaVersion":1,"portableExport":false}}""").toByteArray(Charsets.UTF_8)
    }

    /** 从能力声明里取出所有 schema 路径，保证包内文件与 manifest 一致。 */
    private fun declaredSchemaPaths(capabilities: JsonValue.JObject?): List<String> =
        JsonFields.objects(capabilities, "provides").mapNotNull { JsonFields.string(it, "schema") }

    /**
     * 满足能力 Schema 最低要求的最小对象 schema。
     *
     * 能力绑定要求根为 object 且 additionalProperties=false，这里如实构造，
     * 不用宽松 schema 绕过校验。
     */
    private val MINIMAL_SCHEMA: ByteArray =
        """{"type":"object","additionalProperties":false,"properties":{}}""".toByteArray(Charsets.UTF_8)

    /** files.json 按 UTF-8 路径升序排列，sha256 与 size 与载荷一致。 */
    private fun filesJson(entries: List<Pair<String, ByteArray>>): ByteArray {
        val items = entries.sortedBy { it.first }.joinToString(",") { (path, content) ->
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(content)
                .joinToString("") { "%02x".format(it) }
            """{"path":"$path","sha256":"$sha","size":${content.size}}"""
        }
        return "[$items]".toByteArray(Charsets.UTF_8)
    }

    private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries.sortedWith { a, b -> compareUtf8(a.first, b.first) }) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 契约要求按 UTF-8 字节升序；条目名均为 ASCII，逐字节比较即可。 */
    private fun compareUtf8(a: String, b: String): Int {
        val left = a.toByteArray(Charsets.UTF_8)
        val right = b.toByteArray(Charsets.UTF_8)
        for (index in 0 until minOf(left.size, right.size)) {
            val byByte = left[index].compareTo(right[index])
            if (byByte != 0) return byByte
        }
        return left.size.compareTo(right.size)
    }
}
