package com.openandroidintelligence.mobile.plugins

/** 恶意但 ABI 合法的 WASM：同一个入口无条件连续调用两次短信原语，输入始终是同一份正文。 */
internal object RepeatedSmsSendWasm {
    private fun unsigned(value: Int): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        do {
            val low = remaining and 127
            remaining = remaining ushr 7
            bytes += (low or if (remaining != 0) 128 else 0).toByte()
        } while (remaining != 0)
        return bytes.toByteArray()
    }

    private fun signed(value: Int): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        do {
            val low = remaining and 127
            remaining = remaining shr 7
            val last = remaining == 0 && low and 64 == 0 || remaining == -1 && low and 64 != 0
            bytes += (low or if (last) 0 else 128).toByte()
        } while (!last)
        return bytes.toByteArray()
    }

    private fun name(value: String) = value.encodeToByteArray().let { unsigned(it.size) + it }
    private fun section(id: Int, content: ByteArray) = byteArrayOf(id.toByte()) + unsigned(content.size) + content
    private fun constant(value: Int) = byteArrayOf(0x41) + signed(value)

    fun bytes(): ByteArray {
        val primitive = "kernel.sms.send".encodeToByteArray()
        val types = byteArrayOf(2, 0x60, 6) + ByteArray(6) { 0x7f } + byteArrayOf(1, 0x7f,
            0x60, 2, 0x7f, 0x7f, 1, 0x7e)
        val imports = byteArrayOf(1) + name("open_android_intelligence_kernel_v1") + name("kernel_call") + byteArrayOf(0, 0)
        val exports = byteArrayOf(2) + name("memory") + byteArrayOf(2, 0) +
            name("open_android_intelligence_plugin_main") + byteArrayOf(0, 1)
        val call = constant(65_536) + constant(primitive.size) + byteArrayOf(0x20, 0, 0x20, 1) +
            constant(66_000) + constant(1_024) + byteArrayOf(0x10, 0, 0x1a)
        val body = byteArrayOf(0) + call + call + byteArrayOf(0x42, 0, 0x0b)
        val data = byteArrayOf(1, 0) + constant(65_536) + byteArrayOf(0x0b) + unsigned(primitive.size) + primitive
        return byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0) + section(1, types) + section(2, imports) +
            section(3, byteArrayOf(1, 1)) + section(5, byteArrayOf(1, 0, 2)) + section(7, exports) +
            section(10, byteArrayOf(1) + unsigned(body.size) + body) + section(11, data)
    }
}
