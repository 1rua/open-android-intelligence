package com.openandroidintelligence.plugin.pkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * 契约 §5「manifest 必须符合本节形状」的宿主侧校验测试。
 *
 * §5:156 规定所有对象默认拒绝未知字段；§4:85 规定验证顺序固定为
 * 容器 → 路径 → 数量/大小 → 摘要 → 作者公钥 → 签名 → Schema → 宿主兼容。
 * 因此每个用例都必须是签名真实、摘要正确、能一路走到 Schema 检查的包，
 * 只有 manifest 形状不同。旧形状（manifestVersion、author 嵌套、字符串数组
 * provides、顶层 kernelPrimitives）一律明确拒绝，不做兼容回退。
 */
class AlpManifestSchemaTest {
    private val limits = PackageLimits(
        maxEntries = 64,
        maxSingleEntryBytes = 8 * 1024 * 1024,
        maxTotalUncompressedBytes = 32 * 1024 * 1024,
    )

    private fun verifier() = AlpVerifier(limits = limits)

    // 固定测试种子，保证 fixture 可重复。rawPublicKey 是它经 Ed25519 推导的
    // 真实配对公钥（79b5562e…9664），一次性离线派生后固化于此。
    private val seed = ByteArray(32) { (it + 1).toByte() }

    private val publicKeyBase64url: String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            hexToBytes("79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"),
        )

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            ((Character.digit(hex[index * 2], 16) shl 4) +
                Character.digit(hex[index * 2 + 1], 16)).toByte()
        }

    private fun sign(data: ByteArray): ByteArray {
        val pkcs8Prefix = byteArrayOf(
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70,
            0x04, 0x22, 0x04, 0x20,
        )
        val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
            PKCS8EncodedKeySpec(pkcs8Prefix + seed),
        )
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 构建签名真实、摘要正确的最小合法包；manifestJson 决定测试的形状变量。 */
    private fun signedPackage(manifestJson: String): ByteArray {
        val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(0, 0x61, 0x73, 0x6d)
        val filesJson =
            """[{"path":"payload/plugin.wasm","sha256":"${sha256Hex(payload)}","size":4}]"""
        val filesBytes = filesJson.toByteArray(Charsets.UTF_8)
        val signatureInput =
            "OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1\n".toByteArray(Charsets.UTF_8) +
                manifestBytes + byteArrayOf('\n'.code.toByte()) + filesBytes
        val signatureText =
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign(signatureInput)) + "\n"
        return AlpTestZip.build(
            listOf(
                "manifest.json" to manifestBytes,
                "files.json" to filesBytes,
                "signature.ed25519" to signatureText.toByteArray(Charsets.UTF_8),
                "payload/plugin.wasm" to payload,
            ),
        )
    }

    /** 契约 §5 最小 manifest 的紧凑 JSON（写序即签名输入的字节序）。 */
    private fun validManifest(): String =
        """{"schemaVersion":"1.0",""" +
            """"plugin":{"description":"d","id":"org.example.notifications","name":"n","version":"1.0.0"},""" +
            """"author":{"algorithm":"Ed25519","publicKey":"$publicKeyBase64url"},""" +
            """"runtime":{"abiVersion":"1.0","entrypoint":"open_android_intelligence_plugin_main","payload":"payload/plugin.wasm","type":"protected-wasm"},""" +
            """"compatibility":{"androidHost":">=2.0.0 <3.0.0","gatewayProtocol":">=2.0 <3.0"},""" +
            """"capabilities":{"depends":[],"kernelPrimitives":[],"provides":[]},""" +
            """"security":{"background":{"minimumIntervalSeconds":null,"requested":false},"network":[],"resources":{"maxConcurrentInvocations":1,"maxDailyNetworkBytes":0,"maxInvocationMillis":5000,"maxMemoryBytes":16777216,"maxStorageBytes":10485760}},""" +
            """"ui":{"cards":[],"settings":[]},"state":{"portableExport":false,"schemaVersion":1}}"""

    private fun assertRejected(zip: ByteArray, messageMatches: (String) -> Boolean) {
        val failure = runCatching { verifier().verify(zip.inputStream()) }.exceptionOrNull()
        assertTrue("expected rejection but verification succeeded", failure != null)
        val code = failure!!.message ?: ""
        assertTrue("unexpected rejection code: $code", messageMatches(code))
    }

    @Test
    fun verifiesWellFormedSignedPackage() {
        // 守护用例：强化后的 Schema 校验不得误杀合法 §5 manifest。
        val verified = verifier().verify(signedPackage(validManifest()).inputStream())
        assertEquals("org.example.notifications", verified.identity.pluginId)
        assertEquals("1.0.0", verified.identity.version)
    }

    @Test
    fun rejectsManifestWithoutSchemaVersion() {
        val manifest = validManifest().replace(""""schemaVersion":"1.0",""", "")
        assertRejected(signedPackage(manifest)) { it.contains("SCHEMA_INVALID:schemaVersion") }
    }

    @Test
    fun rejectsUnknownSchemaVersion() {
        val manifest = validManifest().replace(""""schemaVersion":"1.0"""", """"schemaVersion":"2.0"""")
        assertRejected(signedPackage(manifest)) { it.contains("SCHEMA_INVALID:schemaVersion") }
    }

    @Test
    fun rejectsLegacyManifestVersionRootField() {
        // 旧形状在根级携带 manifestVersion；§5:156 要求未知字段直接拒绝。
        val manifest = validManifest()
            .replace("{", """{"manifestVersion":"1.0",""")
        assertRejected(signedPackage(manifest)) { it.contains("manifestVersion") }
    }

    @Test
    fun rejectsLegacyTopLevelKernelPrimitives() {
        // 旧形状把 kernelPrimitives 放在顶层；新契约只认 capabilities 内的对象数组。
        val manifest = validManifest()
            .replace("{", """{"kernelPrimitives":[{"id":"kernel.x.read","purpose":"p","version":"1.0.0"}],""")
        assertRejected(signedPackage(manifest)) { it.contains("kernelPrimitives") }
    }

    @Test
    fun rejectsLegacyAuthorNestedInsidePlugin() {
        // 旧形状的 author 是 plugin 的子对象；§5 的 author 只能是根级对象。
        val manifest = validManifest()
            .replace(
                """"plugin":{"description"""",
                """"plugin":{"author":{"algorithm":"Ed25519","publicKey":"$publicKeyBase64url"},"description"""",
            )
        assertRejected(signedPackage(manifest)) { it.contains("SCHEMA_INVALID") }
    }

    @Test
    fun rejectsLegacyRequiresFieldInsideCapabilities() {
        // 旧形状的依赖字段名是 requires；契约 §6 的字段名是 depends。
        val manifest = validManifest()
            .replace(
                """"capabilities":{"depends"""",
                """"capabilities":{"requires":[],"depends"""",
            )
        assertRejected(signedPackage(manifest)) { it.contains("requires") }
    }

    @Test
    fun rejectsLegacyStringArrayProvides() {
        // 旧形状的 provides 是 "id@version" 字符串数组；§5 要求对象数组。
        val manifest = validManifest()
            .replace(
                """"provides":[]""",
                """"provides":["org.example.notifications.query@1.0.0"]""",
            )
        assertRejected(signedPackage(manifest)) { it.contains("SCHEMA_INVALID:provides") }
    }

    @Test
    fun rejectsCapabilityEntryWithoutVersionAndSchema() {
        val manifest = validManifest()
            .replace(
                """"provides":[]""",
                """"provides":[{"id":"org.example.notifications.query"}]""",
            )
        assertRejected(signedPackage(manifest)) { it.contains("SCHEMA_INVALID:provides") }
    }

    @Test
    fun rejectsKernelPrimitiveEntryWithoutVersionAndPurpose() {
        val manifest = validManifest()
            .replace(
                """"kernelPrimitives":[]""",
                """"kernelPrimitives":[{"id":"kernel.notifications.read"}]""",
            )
        assertRejected(signedPackage(manifest)) { it.contains("SCHEMA_INVALID:kernelPrimitives") }
    }

    @Test
    fun rejectsDependencyEntryWithoutRequiredFlag() {
        val manifest = validManifest()
            .replace(
                """"depends":[]""",
                """"depends":[{"capability":"org.example.contacts.lookup","version":">=1.0.0 <2.0.0"}]""",
            )
        assertRejected(signedPackage(manifest)) { it.contains("SCHEMA_INVALID:depends") }
    }

    @Test
    fun rejectsManifestWithoutUiSection() {
        val manifest = validManifest().replace(""""ui":{"cards":[],"settings":[]},""", "")
        assertRejected(signedPackage(manifest)) { it.contains("SCHEMA_INVALID:ui") }
    }

    @Test
    fun rejectsManifestWithoutStateSection() {
        val manifest = validManifest().replace(""","state":{"portableExport":false,"schemaVersion":1}""", "")
        assertRejected(signedPackage(manifest)) { it.contains("SCHEMA_INVALID:state") }
    }

    @Test
    fun rejectsRuntimeTypeOutsideContractVocabulary() {
        // 旧形状写作 "wasm"；§5.1 的受控词表只有 protected-wasm/developer-native/companion。
        val manifest = validManifest().replace(""""type":"protected-wasm"""", """"type":"wasm"""")
        assertRejected(signedPackage(manifest)) { it.contains("runtimeType") }
    }
}
