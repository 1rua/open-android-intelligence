package com.openandroidintelligence.mobile.plugins

import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 构造真实可安装的 `.alp` 测试包：真实 Ed25519 密钥、真实签名、§5 形状的
 * manifest，与 `plugin-tooling` 产物同构。供插件管理区域的安装链路测试使用。
 */
internal object TestAlpPackages {

    /** §8 声明式设置贡献：一个带 set-setting 动作的 toggle。 */
    const val SETTINGS_CONTRIBUTION_JSON =
        """{"id":"settings-main","title":"示例插件设置","root":{""" +
            """"type":"section","id":"s1","label":"行为","children":[""" +
            """{"type":"toggle","id":"t1","label":"合并同类通知","value":false,"action":"set-setting"}]}}"""

    fun signedPackage(
        id: String = "org.example.notifications",
        version: String = "1.0.0",
        name: String = "示例通知插件",
    ): ByteArray {
        val parts = buildParts(id, version, name)
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
    )

    private fun buildParts(
        id: String,
        version: String,
        name: String,
    ): Parts {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        // X509 编码 = 12 字节固定前缀 + 32 字节裸密钥；校验器按同一规则还原。
        val rawPublicKey = keyPair.public.encoded.copyOfRange(12, 44)
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(rawPublicKey)

        val manifest = manifestJson(id, version, name, publicKey)
        val payload = byteArrayOf(0, 0x61, 0x73, 0x6d) // \0asm
        val uiFile = SETTINGS_CONTRIBUTION_JSON.toByteArray(Charsets.UTF_8)

        val filesIndex = filesJson(
            listOf(
                "payload/plugin.wasm" to payload,
                "ui/settings-main.json" to uiFile,
            ),
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
        return Parts(manifest, filesIndex, payload, uiFile, signatureText)
    }

    private fun assemble(parts: Parts, manifestOverride: ByteArray? = null): ByteArray =
        zipOf(
            listOf(
                "files.json" to parts.filesIndex,
                "manifest.json" to (manifestOverride ?: parts.manifest),
                "payload/plugin.wasm" to parts.payload,
                "signature.ed25519" to parts.signatureText.toByteArray(Charsets.UTF_8),
                "ui/settings-main.json" to parts.uiFile,
            ),
        )

    private fun manifestJson(id: String, version: String, name: String, publicKey: String): ByteArray =
        ("""{"schemaVersion":"1.0","plugin":{"id":"$id","version":"$version","name":"$name","description":"测试用示例插件"},""" +
            """"author":{"algorithm":"Ed25519","publicKey":"$publicKey"},""" +
            """"runtime":{"type":"protected-wasm","abiVersion":"1.0","entrypoint":"open_android_intelligence_plugin_main","payload":"payload/plugin.wasm"},""" +
            """"compatibility":{"androidHost":">=2.0.0 <3.0.0","gatewayProtocol":">=2.0 <3.0"},""" +
            """"capabilities":{"provides":[],"depends":[],"kernelPrimitives":[]},""" +
            """"security":{"network":[],"background":{"requested":false,"minimumIntervalSeconds":null},""" +
            """"resources":{"maxInvocationMillis":5000,"maxMemoryBytes":16777216,"maxStorageBytes":10485760,"maxConcurrentInvocations":1,"maxDailyNetworkBytes":0}},""" +
            """"ui":{"settings":["ui/settings-main.json"],"cards":[]},""" +
            """"state":{"schemaVersion":1,"portableExport":false}}""").toByteArray(Charsets.UTF_8)

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
