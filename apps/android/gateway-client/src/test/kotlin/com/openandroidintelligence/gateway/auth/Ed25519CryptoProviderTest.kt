package com.openandroidintelligence.gateway.auth

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34, 35, 36])
class Ed25519CryptoProviderTest {
    @Test
    fun provider_matches_rfc8032_vector_and_rejects_noncanonical_scalar() {
        val provider = Ed25519CryptoProvider
        val curve = requireNotNull(EdDSANamedCurveTable.getByName("Ed25519"))
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val privateSpec = EdDSAPrivateKeySpec(seed, curve)
        val privateKey = provider.keyFactory().generatePrivate(privateSpec)
        val signer = provider.signature().apply { initSign(privateKey) }
        val signature = signer.sign()

        assertArrayEquals(
            hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
            signature,
        )

        val publicKey = provider.keyFactory().generatePublic(EdDSAPublicKeySpec(privateSpec.getA(), curve))
        assertArrayEquals(
            hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
            publicKey.encoded.copyOfRange(publicKey.encoded.size - 32, publicKey.encoded.size),
        )

        val message = "canonical signature check".encodeToByteArray()
        val generated = provider.keyPairGenerator().generateKeyPair()
        val generatedSignature = provider.signature().run {
            initSign(generated.private)
            update(message)
            sign()
        }
        val verifier = provider.signature().apply { initVerify(generated.public) }
        verifier.update(message)
        assertTrue(verifier.verify(generatedSignature))

        val malleable = generatedSignature.copyOf()
        val scalar = BigInteger(1, malleable.copyOfRange(32, 64).reversedArray())
        val groupOrder = BigInteger.ONE.shiftLeft(252)
            .add(BigInteger("27742317777372353535851937790883648493"))
        val nonCanonicalScalar = scalar.add(groupOrder)
        for (index in 0 until 32) {
            malleable[32 + index] = nonCanonicalScalar.shiftRight(index * 8).toByte()
        }
        val strictVerifier = provider.signature().apply { initVerify(generated.public) }
        strictVerifier.update(message)
        assertFalse(strictVerifier.verify(malleable))
    }

    @Test
    fun provider_reads_existing_pkcs8_and_x509_key_frames_and_round_trips_signatures() {
        val privateKeyFrame = hex(
            "302e020100300506032b657004220420" +
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
        )
        val publicKeyFrame = hex(
            "302a300506032b6570032100" +
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
        )
        val providerFactory = Ed25519CryptoProvider.keyFactory()
        val providerPublic = providerFactory.generatePublic(X509EncodedKeySpec(publicKeyFrame))
        val providerPrivate = providerFactory.generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(privateKeyFrame),
        )
        assertArrayEquals(privateKeyFrame, providerPrivate.encoded)
        assertArrayEquals(publicKeyFrame, providerPublic.encoded)

        val message = "existing encrypted key frame".encodeToByteArray()
        val providerSignature = Ed25519CryptoProvider.signature().run {
            initSign(providerPrivate)
            update(message)
            sign()
        }
        val providerVerifier = Ed25519CryptoProvider.signature().apply { initVerify(providerPublic) }
        providerVerifier.update(message)
        assertTrue(providerVerifier.verify(providerSignature))
        assertEquals(43, ed25519WirePublicKey(providerPublic).length)
    }

    @Test
    @Config(sdk = [33, 34, 35, 36])
    fun provider_interoperates_with_platform_ed25519_when_available() {
        val platformPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val providerFactory = Ed25519CryptoProvider.keyFactory()
        val providerPublic = providerFactory.generatePublic(X509EncodedKeySpec(platformPair.public.encoded))
        val providerPrivate = providerFactory.generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(platformPair.private.encoded),
        )
        val message = "existing encrypted key frame".encodeToByteArray()
        val platformSignature = Signature.getInstance("Ed25519").run {
            initSign(platformPair.private)
            update(message)
            sign()
        }

        val providerVerifier = Ed25519CryptoProvider.signature().apply { initVerify(providerPublic) }
        providerVerifier.update(message)
        assertTrue(providerVerifier.verify(platformSignature))

        val providerSignature = Ed25519CryptoProvider.signature().run {
            initSign(providerPrivate)
            update(message)
            sign()
        }
        val platformPublic = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(providerPublic.encoded))
        val platformVerifier = Signature.getInstance("Ed25519").apply { initVerify(platformPublic) }
        platformVerifier.update(message)
        assertTrue(platformVerifier.verify(providerSignature))
        assertEquals(43, ed25519WirePublicKey(providerPublic).length)
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
