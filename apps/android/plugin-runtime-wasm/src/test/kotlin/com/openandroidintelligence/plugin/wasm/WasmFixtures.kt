package com.openandroidintelligence.plugin.wasm

/**
 * Builds the WASM modules the runtime tests need, byte by byte.
 *
 * These are assembled in test code rather than shipped as checked-in binaries
 * because each one exists to be *rejected*: the bytes have to state a specific
 * illegal fact (a WASI import, an unknown kernel call, no entrypoint), and a
 * hand-built module states exactly that with nothing else in it.
 */
internal object WasmFixtures {

    private val MAGIC = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

    private const val I32 = 0x7F.toByte()
    private const val I64 = 0x7E.toByte()

    private const val SEC_TYPE = 1
    private const val SEC_IMPORT = 2
    private const val SEC_FUNCTION = 3
    private const val SEC_MEMORY = 5
    private const val SEC_EXPORT = 7
    private const val SEC_CODE = 10

    // Opcodes used by the bodies below.
    private const val OP_BLOCK = 0x02.toByte()
    private const val OP_LOOP = 0x03.toByte()
    private const val OP_BR = 0x0C.toByte()
    private const val OP_END = 0x0B.toByte()
    private const val OP_DROP = 0x1A.toByte()
    private const val OP_LOCAL_GET = 0x20.toByte()
    private const val OP_I32_CONST = 0x41.toByte()
    private const val OP_I64_CONST = 0x42.toByte()
    private const val OP_I64_OR = 0x84.toByte()
    private const val OP_I64_SHL = 0x86.toByte()
    // 0xAC is extend_i32_s; 0xAD is the unsigned form used here. 0xAE is
    // `i64.trunc_f32_s`, which is why a wrong constant surfaces as a confusing
    // "instruction requires [f32]" type error rather than a bad opcode.
    private const val OP_I64_EXTEND_I32_U = 0xAD.toByte()
    private const val OP_MEMORY_GROW = 0x40.toByte()

    private const val FUNC = 0x00.toByte()
    private const val EXPORT_FUNC = 0x00.toByte()

    private fun uleb(value: Int): ByteArray {
        val out = mutableListOf<Byte>()
        var remaining = value
        do {
            var byte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) byte = byte or 0x80
            out += byte.toByte()
        } while (remaining != 0)
        return out.toByteArray()
    }

    private fun sleb(value: Int): ByteArray {
        val out = mutableListOf<Byte>()
        var remaining = value
        var more = true
        while (more) {
            var byte = remaining and 0x7F
            remaining = remaining shr 7
            val signBit = (byte and 0x40) != 0
            if ((remaining == 0 && !signBit) || (remaining == -1 && signBit)) more = false else byte = byte or 0x80
            out += byte.toByte()
        }
        return out.toByteArray()
    }

    private fun name(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return uleb(bytes.size) + bytes
    }

    private fun vector(items: List<ByteArray>): ByteArray =
        uleb(items.size) + items.fold(ByteArray(0)) { acc, item -> acc + item }

    private fun section(id: Int, payload: ByteArray): ByteArray =
        byteArrayOf(id.toByte()) + uleb(payload.size) + payload

    private fun typeSection(types: List<Pair<List<Byte>, List<Byte>>>): ByteArray =
        section(
            SEC_TYPE,
            vector(
                types.map { (params, results) ->
                    byteArrayOf(0x60) + vector(params.map { byteArrayOf(it) }) +
                        vector(results.map { byteArrayOf(it) })
                },
            ),
        )

    private fun importSection(imports: List<Triple<String, String, Int>>): ByteArray =
        section(
            SEC_IMPORT,
            vector(
                imports.map { (module, field, typeIndex) ->
                    name(module) + name(field) + byteArrayOf(FUNC) + uleb(typeIndex)
                },
            ),
        )

    private fun functionSection(typeIndexes: List<Int>): ByteArray =
        section(SEC_FUNCTION, vector(typeIndexes.map { uleb(it) }))

    private fun memorySection(minPages: Int, maxPages: Int? = null): ByteArray {
        val limits = if (maxPages == null) {
            byteArrayOf(0x00) + uleb(minPages)
        } else {
            byteArrayOf(0x01) + uleb(minPages) + uleb(maxPages)
        }
        return section(SEC_MEMORY, vector(listOf(limits)))
    }

    private fun exportSection(exports: List<Triple<String, Int, Byte>>): ByteArray =
        section(
            SEC_EXPORT,
            vector(
                exports.map { (exportName, index, kind) ->
                    name(exportName) + byteArrayOf(kind) + uleb(index)
                },
            ),
        )

    private fun codeSection(bodies: List<ByteArray>): ByteArray =
        section(
            SEC_CODE,
            vector(
                bodies.map { body ->
                    // Body: no local declarations, then instructions, then `end`.
                    val inner = uleb(0) + body + OP_END
                    uleb(inner.size) + inner
                },
            ),
        )

    /**
     * A module that echoes its request back.
     *
     * It imports `kernel_log` to prove a known import is accepted, and returns
     * `(pointer, length)` packed into one 64-bit word as the ABI requires.
     */
    fun echo(minPages: Int = 1, logCalls: Int = 0): ByteArray {
        val logType = listOf(I32, I32, I32) to emptyList<Byte>()
        val mainType = listOf(I32, I32) to listOf(I64)
        val calls = (0 until logCalls).flatMap {
            listOf(OP_I32_CONST, 0x00.toByte(), OP_I32_CONST, 0x00.toByte(), OP_I32_CONST, 0x00.toByte(), 0x10.toByte(), 0x00.toByte())
        }.toByteArray()
        val body = calls + byteArrayOf(
            OP_I32_CONST, 0x00, // response pointer: the exchange region
            OP_I64_EXTEND_I32_U,
            OP_I64_CONST, 0x20, // shift by 32
            OP_I64_SHL,
            OP_LOCAL_GET, 0x01, // request length
            OP_I64_EXTEND_I32_U,
            OP_I64_OR,
        )
        return MAGIC +
            typeSection(listOf(logType, mainType)) +
            importSection(listOf(Triple("open_android_intelligence_kernel_v1", "kernel_log", 0))) +
            functionSection(listOf(1)) +
            memorySection(minPages) +
            exportSection(listOf(Triple("open_android_intelligence_plugin_main", 1, EXPORT_FUNC))) +
            codeSection(listOf(body))
    }

    /** A module that spins forever, to prove the deadline actually fires. */
    fun infiniteLoop(): ByteArray {
        val mainType = listOf(I32, I32) to listOf(I64)
        val body = byteArrayOf(
            OP_LOOP, 0x40,
            OP_BR, 0x00,
            OP_END,
            OP_I64_CONST, 0x00, // unreachable in practice; satisfies the return type
        )
        return MAGIC +
            typeSection(listOf(mainType)) +
            functionSection(listOf(0)) +
            memorySection(1) +
            exportSection(listOf(Triple("open_android_intelligence_plugin_main", 0, EXPORT_FUNC))) +
            codeSection(listOf(body))
    }

    /** A module that grows its memory without bound, to prove the page ceiling holds. */
    fun memoryBomb(): ByteArray {
        val mainType = listOf(I32, I32) to listOf(I64)
        val body = byteArrayOf(
            OP_LOOP, 0x40,
            OP_I32_CONST, 0x01,
            OP_MEMORY_GROW, 0x00,
            OP_DROP,
            OP_BR, 0x00,
            OP_END,
            OP_I64_CONST, 0x00,
        )
        return MAGIC +
            typeSection(listOf(mainType)) +
            functionSection(listOf(0)) +
            memorySection(1) +
            exportSection(listOf(Triple("open_android_intelligence_plugin_main", 0, EXPORT_FUNC))) +
            codeSection(listOf(body))
    }

    /** Returns the previous page count as a response length; grow failure yields BAD_RESULT. */
    fun growOnce(): ByteArray = MAGIC +
        typeSection(listOf(listOf(I32, I32) to listOf(I64))) + functionSection(listOf(0)) +
        memorySection(1) + exportSection(listOf(Triple("open_android_intelligence_plugin_main", 0, EXPORT_FUNC))) +
        codeSection(listOf(byteArrayOf(OP_I32_CONST, 1, OP_MEMORY_GROW, 0, OP_I64_EXTEND_I32_U)))

    /** A module whose declared minimum memory exceeds any sane budget. */
    fun declaresTooMuchMemory(): ByteArray {
        val mainType = listOf(I32, I32) to listOf(I64)
        return MAGIC +
            typeSection(listOf(mainType)) +
            functionSection(listOf(0)) +
            memorySection(minPages = 4_096, maxPages = 4_096) +
            exportSection(listOf(Triple("open_android_intelligence_plugin_main", 0, EXPORT_FUNC))) +
            codeSection(listOf(byteArrayOf(OP_I64_CONST, 0x00)))
    }

    /** Imports a WASI clock call: the exact class of import the ABI forbids. */
    fun importsWasi(): ByteArray {
        val fdWrite = listOf(I32, I32, I32, I32) to listOf(I32)
        val mainType = listOf(I32, I32) to listOf(I64)
        return MAGIC +
            typeSection(listOf(fdWrite, mainType)) +
            importSection(listOf(Triple("wasi_snapshot_preview1", "fd_write", 0))) +
            functionSection(listOf(1)) +
            memorySection(1) +
            exportSection(listOf(Triple("open_android_intelligence_plugin_main", 1, EXPORT_FUNC))) +
            codeSection(listOf(byteArrayOf(OP_I64_CONST, 0x00)))
    }

    /** Imports a plausible name the kernel ABI does not define. */
    fun importsUnknownKernelCall(): ByteArray {
        val unknown = emptyList<Byte>() to emptyList<Byte>()
        val mainType = listOf(I32, I32) to listOf(I64)
        return MAGIC +
            typeSection(listOf(unknown, mainType)) +
            importSection(
                listOf(Triple("open_android_intelligence_kernel_v1", "kernel_open_socket", 0)),
            ) +
            functionSection(listOf(1)) +
            memorySection(1) +
            exportSection(listOf(Triple("open_android_intelligence_plugin_main", 1, EXPORT_FUNC))) +
            codeSection(listOf(byteArrayOf(OP_I64_CONST, 0x00)))
    }

    /** Imports a known name with the wrong signature, so it must fail to link. */
    fun importsKernelWithWrongSignature(): ByteArray {
        // `kernel_now_millis` takes nothing and returns i64; this module
        // declares it as taking nothing and returning i32.
        val wrong = emptyList<Byte>() to listOf(I32)
        val mainType = listOf(I32, I32) to listOf(I64)
        return MAGIC +
            typeSection(listOf(wrong, mainType)) +
            importSection(listOf(Triple("open_android_intelligence_kernel_v1", "kernel_now_millis", 0))) +
            functionSection(listOf(1)) +
            memorySection(1) +
            exportSection(listOf(Triple("open_android_intelligence_plugin_main", 1, EXPORT_FUNC))) +
            codeSection(listOf(byteArrayOf(OP_I64_CONST, 0x00)))
    }

    /** A module with no `open_android_intelligence_plugin_main` export. */
    fun missingEntrypoint(): ByteArray {
        val mainType = listOf(I32, I32) to listOf(I64)
        return MAGIC +
            typeSection(listOf(mainType)) +
            functionSection(listOf(0)) +
            memorySection(1) +
            exportSection(listOf(Triple("not_the_entrypoint", 0, EXPORT_FUNC))) +
            codeSection(listOf(byteArrayOf(OP_I64_CONST, 0x00)))
    }

    /** Not a WASM module at all. */
    fun garbage(): ByteArray = "this is not wasm".toByteArray(Charsets.UTF_8)
}
