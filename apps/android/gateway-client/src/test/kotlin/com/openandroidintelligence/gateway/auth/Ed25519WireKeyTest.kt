package com.openandroidintelligence.gateway.auth

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class Ed25519WireKeyTest {
    @Test fun encodesTheExactRfc8032PublicKeyWithoutItsSpkiHeader() {
        val rawHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        val encoded = ("302a300506032b6570032100" + rawHex).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(encoded))
        val wire = ed25519WirePublicKey(key)
        assertEquals(43, wire.length)
        assertEquals(rawHex, Base64.getUrlDecoder().decode(wire).joinToString("") { "%02x".format(it) })
    }

    @Test fun rejectsAKeyFromADifferentAlgorithm() {
        val key = KeyPairGenerator.getInstance("EC").generateKeyPair().public
        assertThrows(IllegalArgumentException::class.java) { ed25519WirePublicKey(key) }
    }
}
