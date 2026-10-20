package com.openandroidintelligence.mobile.plugins

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginPackageInputTest {
    @Test
    fun accepts_the_limit_and_rejects_the_first_byte_over_it() {
        val accepted = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(accepted, readPluginPackageBytes(ByteArrayInputStream(accepted), maximumBytes = 4))
        assertThrows(IllegalArgumentException::class.java) {
            readPluginPackageBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), maximumBytes = 4)
        }
    }

    @Test
    fun reads_streams_that_return_short_chunks_without_losing_bytes() {
        val expected = byteArrayOf(9, 8, 7, 6)
        val input = object : ByteArrayInputStream(expected) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length.coerceAtMost(1))
        }

        assertArrayEquals(expected, readPluginPackageBytes(input, maximumBytes = expected.size))
    }
}
