package com.openandroidintelligence.gateway.remote.crypto

import com.openandroidintelligence.gateway.remote.protocol.RemoteConnectionBinding
import com.openandroidintelligence.gateway.remote.protocol.RemoteProtocol
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import java.security.MessageDigest

enum class NoiseRole { CLIENT_INITIATOR, HOST_RESPONDER }

/** 不附带密码提供者异常，避免调用方误将秘密或正文写入日志。 */
class NoiseChannelException(val code: String) : IllegalStateException(code)

/**
 * 固定 IK 套件的窄封装；状态机和所有密码运算均交给 noise-java。
 * 每个实例只属于一条已授权连接，所有操作串行化，失败后销毁，不能恢复旧 nonce。
 */
class NoiseIkChannel private constructor(
    val role: NoiseRole,
    val binding: RemoteConnectionBinding,
    localPrivateKey: ByteArray,
    remotePublicKey: ByteArray,
    fixedEphemeralPrivateKey: ByteArray?,
) : AutoCloseable {
    private val expectedRemoteStatic = requireKey(remotePublicKey)
    private var handshake: HandshakeState? = null
    private var transport: CipherStatePair? = null
    private var closed = false
    private var sent = 0L
    private var received = 0L

    init {
        val privateCopy = requireKey(localPrivateKey)
        val ephemeralCopy = fixedEphemeralPrivateKey?.let(::requireKey)
        try {
            val state = HandshakeState(
                RemoteProtocol.NOISE_SUITE,
                if (role == NoiseRole.CLIENT_INITIATOR) HandshakeState.INITIATOR else HandshakeState.RESPONDER,
            )
            handshake = state
            val prologue = binding.prologue()
            state.setPrologue(prologue, 0, prologue.size)
            state.localKeyPair.setPrivateKey(privateCopy, 0)
            if (role == NoiseRole.CLIENT_INITIATOR) state.remotePublicKey.setPublicKey(expectedRemoteStatic, 0)
            ephemeralCopy?.let { state.fixedEphemeralKey.setPrivateKey(it, 0) }
            state.start()
        } catch (_: Exception) {
            close()
            throw NoiseChannelException("NOISE_INITIALIZATION_FAILED")
        } finally {
            privateCopy.fill(0)
            ephemeralCopy?.fill(0)
        }
    }

    val complete: Boolean @Synchronized get() = !closed && transport != null
    val canWriteHandshake: Boolean @Synchronized get() {
        assertLive()
        return handshake?.action == HandshakeState.WRITE_MESSAGE
    }

    @Synchronized
    fun writeHandshake(payload: ByteArray = ByteArray(0)): ByteArray {
        val state = requireHandshake(HandshakeState.WRITE_MESSAGE)
        // IK 第一条消息的开销为 96 字节；两个角色均采用保守的相同上限。
        if (payload.size > MAX_NOISE_MESSAGE_BYTES - 96) fail("NOISE_FRAME_TOO_LARGE")
        val output = ByteArray(MAX_NOISE_MESSAGE_BYTES)
        return provider("NOISE_HANDSHAKE_FAILED") {
            val size = state.writeMessage(output, 0, payload, 0, payload.size)
            finishIfReady(state)
            output.copyOf(size)
        }
    }

    @Synchronized
    fun readHandshake(message: ByteArray): ByteArray {
        val state = requireHandshake(HandshakeState.READ_MESSAGE)
        if (message.isEmpty() || message.size > MAX_NOISE_MESSAGE_BYTES) fail("NOISE_FRAME_INVALID")
        val output = ByteArray(message.size)
        return provider("NOISE_HANDSHAKE_FAILED") {
            val size = state.readMessage(message, 0, message.size, output, 0)
            verifyRemoteStatic(state)
            finishIfReady(state)
            output.copyOf(size)
        }
    }

    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        val pair = requireTransport()
        if (plaintext.size > MAX_NOISE_MESSAGE_BYTES - TAG_BYTES) fail("NOISE_FRAME_TOO_LARGE")
        if (sent == Long.MAX_VALUE) fail("NOISE_COUNTER_EXHAUSTED")
        val output = ByteArray(plaintext.size + TAG_BYTES)
        return provider("NOISE_ENCRYPT_FAILED") {
            val size = pair.sender.encryptWithAd(null, plaintext, 0, output, 0, plaintext.size)
            sent += 1
            output.copyOf(size)
        }
    }

    @Synchronized
    fun decrypt(ciphertext: ByteArray): ByteArray {
        val pair = requireTransport()
        if (ciphertext.size !in TAG_BYTES..MAX_NOISE_MESSAGE_BYTES) fail("NOISE_FRAME_INVALID")
        if (received == Long.MAX_VALUE) fail("NOISE_COUNTER_EXHAUSTED")
        val output = ByteArray(ciphertext.size - TAG_BYTES)
        return provider("NOISE_AUTHENTICATION_FAILED") {
            val size = pair.receiver.decryptWithAd(null, ciphertext, 0, output, 0, ciphertext.size)
            received += 1
            output.copyOf(size)
        }
    }

    @Synchronized fun sendingCounter(): Long = sent
    @Synchronized fun receivingCounter(): Long = received

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        expectedRemoteStatic.fill(0)
        transport?.destroy()
        transport = null
        handshake?.destroy()
        handshake = null
    }

    private fun requireHandshake(action: Int): HandshakeState {
        assertLive()
        if (transport != null) fail("NOISE_ALREADY_READY")
        val state = handshake ?: fail("NOISE_NOT_READY")
        if (state.action != action) fail("NOISE_HANDSHAKE_ORDER")
        return state
    }

    private fun requireTransport(): CipherStatePair {
        assertLive()
        return transport ?: throw NoiseChannelException("NOISE_NOT_READY")
    }

    private fun assertLive() {
        if (closed) throw NoiseChannelException("NOISE_CLOSED")
    }

    private fun verifyRemoteStatic(state: HandshakeState) {
        val peer = state.remotePublicKey
        if (peer == null || !peer.hasPublicKey()) fail("PEER_IDENTITY_MISMATCH")
        val actual = ByteArray(KEY_BYTES)
        try {
            peer.getPublicKey(actual, 0)
            if (!MessageDigest.isEqual(actual, expectedRemoteStatic)) fail("PEER_IDENTITY_MISMATCH")
        } finally {
            actual.fill(0)
        }
    }

    private fun finishIfReady(state: HandshakeState) {
        if (state.action != HandshakeState.SPLIT) return
        verifyRemoteStatic(state)
        transport = state.split()
        state.destroy()
        handshake = null
    }

    private inline fun <T> provider(code: String, block: () -> T): T = try { block() }
    catch (failure: NoiseChannelException) { throw failure }
    catch (_: Exception) { fail(code) }

    private fun fail(code: String): Nothing {
        close()
        throw NoiseChannelException(code)
    }

    companion object {
        const val KEY_BYTES = 32
        const val TAG_BYTES = 16
        const val MAX_NOISE_MESSAGE_BYTES = 65_535

        /** 生产入口不接受 RNG 或固定临时键；临时键由成熟库生成。 */
        fun create(
            role: NoiseRole,
            binding: RemoteConnectionBinding,
            localPrivateKey: ByteArray,
            expectedRemotePublicKey: ByteArray,
        ): NoiseIkChannel = NoiseIkChannel(role, binding, localPrivateKey, expectedRemotePublicKey, null)

        /** 仅限模块内部的固定向量测试，不向网络配置或用户配置暴露。 */
        internal fun forFixture(
            role: NoiseRole,
            binding: RemoteConnectionBinding,
            localPrivateKey: ByteArray,
            expectedRemotePublicKey: ByteArray,
            ephemeralPrivateKey: ByteArray,
        ): NoiseIkChannel = NoiseIkChannel(role, binding, localPrivateKey, expectedRemotePublicKey, ephemeralPrivateKey)

        fun publicKeyFor(privateKey: ByteArray): ByteArray {
            val privateCopy = requireKey(privateKey)
            val state = Noise.createDH("25519")
            try {
                state.setPrivateKey(privateCopy, 0)
                return ByteArray(KEY_BYTES).also { state.getPublicKey(it, 0) }
            } finally {
                privateCopy.fill(0)
                state.destroy()
            }
        }

        private fun requireKey(key: ByteArray): ByteArray {
            if (key.size != KEY_BYTES) throw NoiseChannelException("INVALID_KEY")
            return key.copyOf()
        }
    }
}
