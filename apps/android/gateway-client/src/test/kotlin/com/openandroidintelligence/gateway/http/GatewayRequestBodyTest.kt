package com.openandroidintelligence.gateway.http

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayRequestBodyTest {
    @Test
    fun byteArrayBodyIsReplayableAndCarriesTheExactLengthAndDigest() {
        val bytes = byteArrayOf(0, 0x7b, 0x7d, 0x2b, 0x25, 0xff.toByte(), 0x80.toByte(), 0xc3.toByte(), 0xa9.toByte())

        val body = GatewayRequestBody.fromBytes(bytes)

        assertEquals(9L, body.contentLength)
        assertEquals("0a460faa829b3b31fb086f8dd9a6a480745bb7b5f9055c4728d71f9f235d331b", body.sha256Hex)
        assertArrayEquals(bytes, body.openStream().use { it.readBytes() })
        assertArrayEquals(bytes, body.openStream().use { it.readBytes() })
    }

    @Test
    fun streamBackedBodyDoesNotNeedToBeBufferedBeforeOpening() {
        val body = GatewayRequestBody(
            contentLength = 3,
            sha256Hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            openStream = { ByteArrayInputStream("abc".toByteArray()) },
        )

        assertEquals("abc", body.openStream().use { it.readBytes().decodeToString() })
    }
}
