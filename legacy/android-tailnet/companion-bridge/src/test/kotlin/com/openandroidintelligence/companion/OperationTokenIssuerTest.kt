package com.openandroidintelligence.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OperationTokenIssuerTest {

    private val binding = CompanionBinding(
        hostUid = 10001,
        companionUid = 10002,
        pluginId = "org.openandroidintelligence.transport.tailscale",
        accountId = "account-1",
        pairingId = "pairing-1",
        grantRevision = 1L,
    )

    private val destination = Destination(host = "gw.example.com", port = 443)

    @Test
    fun tokenIsSingleUseAndBoundToDestination() {
        val issuer = OperationTokenIssuer(clock = { 1_000_000L })
        val token = issuer.issue(
            binding = binding,
            operation = "connect",
            destination = destination,
            ttlMillis = 30_000L,
        )

        // 第一次消费成功
        val consumed = issuer.consume(token.serializedToken, destination, nowMillis = 1_005_000L)
        assertNotNull("第一次消费应当成功", consumed)
        assertEquals("connect", consumed!!.operation)
        assertEquals(destination, consumed.destination)
        assertEquals(binding, consumed.binding)

        // 第二次消费必须失败（防重放）
        val secondTry = issuer.consume(token.serializedToken, destination, nowMillis = 1_006_000L)
        assertNull("同一令牌二次消费必须失败", secondTry)
    }

    @Test
    fun tokenRejectsMismatchedDestinationHost() {
        val issuer = OperationTokenIssuer(clock = { 1_000_000L })
        val token = issuer.issue(
            binding = binding,
            operation = "connect",
            destination = destination,
            ttlMillis = 30_000L,
        )

        val wrongHost = Destination(host = "evil.example.com", port = 443)
        val consumed = issuer.consume(token.serializedToken, wrongHost, nowMillis = 1_005_000L)
        assertNull("目标 Host 不匹配时必须拒绝消费", consumed)
    }

    @Test
    fun tokenRejectsMismatchedDestinationPort() {
        val issuer = OperationTokenIssuer(clock = { 1_000_000L })
        val token = issuer.issue(
            binding = binding,
            operation = "connect",
            destination = destination,
            ttlMillis = 30_000L,
        )

        val wrongPort = Destination(host = "gw.example.com", port = 8443)
        val consumed = issuer.consume(token.serializedToken, wrongPort, nowMillis = 1_005_000L)
        assertNull("目标 Port 不匹配时必须拒绝消费", consumed)
    }

    @Test
    fun tokenExpiresAfterTtl() {
        val issuer = OperationTokenIssuer(clock = { 1_000_000L })
        val token = issuer.issue(
            binding = binding,
            operation = "connect",
            destination = destination,
            ttlMillis = 30_000L,
        )

        // 在 TTL 之后消费失败
        val expired = issuer.consume(token.serializedToken, destination, nowMillis = 1_030_001L)
        assertNull("超过 TTL 的令牌必须失效", expired)
    }

    @Test
    fun tokenGeneratesUniqueNonces() {
        val issuer = OperationTokenIssuer(clock = { 1_000_000L })
        val token1 = issuer.issue(binding, "connect", destination, 30_000L)
        val token2 = issuer.issue(binding, "connect", destination, 30_000L)

        assertFalse("连续签发的令牌其 Nonce 或序列化形式必须唯一", token1.nonce == token2.nonce)
        assertFalse(token1.serializedToken == token2.serializedToken)
    }

    @Test
    fun verifierAcceptsMatchingCompanion() {
        val declaration = CompanionDeclaration(
            packageName = "org.openandroidintelligence.companion.tailscale",
            certificateSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            minVersionCode = 12L,
            ipcContract = "open-android-intelligence-companion-v1",
        )

        val provider = FakePackageInfoProvider(
            installedPackages = mapOf(
                "org.openandroidintelligence.companion.tailscale" to InstalledPackageInfo(
                    packageName = "org.openandroidintelligence.companion.tailscale",
                    versionCode = 15L,
                    certificateSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    uid = 10002,
                )
            )
        )

        val verifier = CompanionBindingVerifier(provider)
        val verified = verifier.verify(declaration)
        assertEquals("org.openandroidintelligence.companion.tailscale", verified.packageName)
        assertEquals(15L, verified.versionCode)
        assertEquals(10002, verified.uid)
    }

    @Test
    fun verifierRejectsMissingPackage() {
        val declaration = CompanionDeclaration(
            packageName = "org.openandroidintelligence.companion.missing",
            certificateSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            minVersionCode = 1L,
        )
        val verifier = CompanionBindingVerifier(FakePackageInfoProvider(emptyMap()))
        try {
            verifier.verify(declaration)
            fail("未安装的应用必须拒绝")
        } catch (e: CompanionRejected) {
            assertTrue(e.message!!.contains("PACKAGE_NOT_FOUND"))
        }
    }

    @Test
    fun verifierRejectsCertificateMismatch() {
        val declaration = CompanionDeclaration(
            packageName = "org.openandroidintelligence.companion.tailscale",
            certificateSha256 = "1111111111111111111111111111111111111111111111111111111111111111",
            minVersionCode = 10L,
        )

        val provider = FakePackageInfoProvider(
            installedPackages = mapOf(
                "org.openandroidintelligence.companion.tailscale" to InstalledPackageInfo(
                    packageName = "org.openandroidintelligence.companion.tailscale",
                    versionCode = 12L,
                    certificateSha256 = "2222222222222222222222222222222222222222222222222222222222222222",
                    uid = 10002,
                )
            )
        )

        val verifier = CompanionBindingVerifier(provider)
        try {
            verifier.verify(declaration)
            fail("证书不匹配时必须拒绝")
        } catch (e: CompanionRejected) {
            assertTrue(e.message!!.contains("CERTIFICATE_MISMATCH"))
        }
    }

    @Test
    fun verifierRejectsLowerVersionCode() {
        val declaration = CompanionDeclaration(
            packageName = "org.openandroidintelligence.companion.tailscale",
            certificateSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            minVersionCode = 20L,
        )

        val provider = FakePackageInfoProvider(
            installedPackages = mapOf(
                "org.openandroidintelligence.companion.tailscale" to InstalledPackageInfo(
                    packageName = "org.openandroidintelligence.companion.tailscale",
                    versionCode = 19L,
                    certificateSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    uid = 10002,
                )
            )
        )

        val verifier = CompanionBindingVerifier(provider)
        try {
            verifier.verify(declaration)
            fail("版本过低必须拒绝")
        } catch (e: CompanionRejected) {
            assertTrue(e.message!!.contains("VERSION_TOO_LOW"))
        }
    }
}

