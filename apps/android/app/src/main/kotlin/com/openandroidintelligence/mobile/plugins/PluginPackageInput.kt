package com.openandroidintelligence.mobile.plugins

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val MAX_PLUGIN_PACKAGE_BYTES = 32 * 1024 * 1024

internal fun readPluginPackageBytes(
    input: InputStream,
    maximumBytes: Int = MAX_PLUGIN_PACKAGE_BYTES,
): ByteArray {
    require(maximumBytes in 1 until Int.MAX_VALUE) { "INVALID_PACKAGE_LIMIT" }
    val output = ByteArrayOutputStream(minOf(8192, maximumBytes))
    val buffer = ByteArray(8192)
    var totalBytes = 0
    while (true) {
        val available = minOf(buffer.size, maximumBytes + 1 - totalBytes)
        val count = input.read(buffer, 0, available)
        if (count < 0) return output.toByteArray()
        if (count == 0) {
            val nextByte = input.read()
            if (nextByte < 0) return output.toByteArray()
            require(totalBytes < maximumBytes) { "PACKAGE_TOO_LARGE" }
            output.write(nextByte)
            totalBytes++
            continue
        }
        require(count <= maximumBytes - totalBytes) { "PACKAGE_TOO_LARGE" }
        output.write(buffer, 0, count)
        totalBytes += count
    }
}
