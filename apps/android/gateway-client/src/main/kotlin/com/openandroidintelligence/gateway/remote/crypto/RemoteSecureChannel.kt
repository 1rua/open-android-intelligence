package com.openandroidintelligence.gateway.remote.crypto

import com.openandroidintelligence.gateway.remote.protocol.RemoteBase64Url
import com.openandroidintelligence.gateway.remote.protocol.RemoteConnectionBinding
import com.openandroidintelligence.gateway.remote.protocol.RemoteControlCodec
import com.openandroidintelligence.gateway.remote.protocol.RemoteControlFrame
import com.openandroidintelligence.gateway.remote.protocol.RemoteProtocolException
import com.openandroidintelligence.gateway.remote.protocol.SecureMessageCodec
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/** 将帧归属、Noise 和 DSHF 生命周期放在一起，错误后整体关闭而不继续消费旧计数器。 */
class RemoteSecureChannel(private val noise: NoiseIkChannel) : AutoCloseable {
    private val codec = SecureMessageCodec()
    private var closed = false

    /** 仅暴露不可变归属，供传输适配核对连接及双方设备，不能替换底层密码状态。 */
    val binding: RemoteConnectionBinding get() = noise.binding

    val complete: Boolean @Synchronized get() = !closed && noise.complete

    @Synchronized
    fun writeHandshakeFrame(id: String, timestamp: Long, payload: ByteArray = ByteArray(0)): RemoteControlFrame = guarded {
        val step = if (noise.role == NoiseRole.CLIENT_INITIATOR) 1 else 2
        RemoteControlCodec.create("secure.handshake", Json.of(mapOf(
            "connectionId" to noise.binding.connectionId,
            "targetDeviceId" to remoteDeviceId(),
            "step" to step,
            "data" to RemoteBase64Url.encode(noise.writeHandshake(payload)),
        )) as JsonValue.JObject, id, timestamp)
    }

    @Synchronized
    fun readHandshakeFrame(frame: RemoteControlFrame): ByteArray = guarded {
        val checked = RemoteControlCodec.parse(frame.toJson())
        if (checked.type != "secure.handshake" || JsonFields.string(checked.payload, "connectionId") != noise.binding.connectionId ||
            JsonFields.string(checked.payload, "targetDeviceId") != localDeviceId() ||
            JsonFields.int(checked.payload, "step") != if (noise.role == NoiseRole.CLIENT_INITIATOR) 2 else 1) {
            throw RemoteProtocolException("HANDSHAKE_BINDING_MISMATCH")
        }
        noise.readHandshake(RemoteBase64Url.decode(JsonFields.string(checked.payload, "data")!!))
    }

    /** 一次调用的各片必须按列表顺序发送，不能与同方向的下一消息交叉写入。 */
    @Synchronized
    fun encryptMessage(message: ByteArray): List<ByteArray> = guarded {
        if (!noise.complete) throw NoiseChannelException("NOISE_NOT_READY")
        codec.encode(message).map(noise::encrypt)
    }

    @Synchronized
    fun decryptMessage(ciphertext: ByteArray): ByteArray? = guarded {
        codec.decode(noise.decrypt(ciphertext))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        codec.reset()
        noise.close()
    }

    private fun localDeviceId(): String = if (noise.role == NoiseRole.CLIENT_INITIATOR) noise.binding.clientDeviceId else noise.binding.hostDeviceId
    private fun remoteDeviceId(): String = if (noise.role == NoiseRole.CLIENT_INITIATOR) noise.binding.hostDeviceId else noise.binding.clientDeviceId

    private inline fun <T> guarded(block: () -> T): T {
        if (closed) throw NoiseChannelException("NOISE_CLOSED")
        return try { block() } catch (failure: Exception) {
            close()
            throw failure
        }
    }
}
