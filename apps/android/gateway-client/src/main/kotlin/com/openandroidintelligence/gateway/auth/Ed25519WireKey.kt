package com.openandroidintelligence.gateway.auth

import java.security.PublicKey
import java.util.Base64

/** RFC 8410 SubjectPublicKeyInfo → the 32-byte key required by session.schema.json. */
fun ed25519WirePublicKey(key: PublicKey): String {
    val spki = key.encoded
    val prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    require(spki.size == prefix.size + 32 && spki.copyOfRange(0, prefix.size).contentEquals(prefix)) {
        "DEVICE_PUBLIC_KEY_NOT_ED25519"
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(spki.copyOfRange(prefix.size, spki.size))
}
