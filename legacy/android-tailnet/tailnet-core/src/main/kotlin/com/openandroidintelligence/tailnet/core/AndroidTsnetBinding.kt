package com.openandroidintelligence.tailnet.core

import com.openandroidintelligence.core.model.TransportFailure
import com.openandroidintelligence.core.model.TransportPath
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import tsnetbridge.Channel as NativeChannel
import tsnetbridge.Node as NativeNode
import tsnetbridge.StateSink as NativeStateSink
import tsnetbridge.Tsnetbridge

/**
 * The only Kotlin file that imports the generated tsnetbridge API. It owns
 * exactly one native node and one native channel, serializes lifecycle calls,
 * copies bytes at every boundary, and maps stable native error codes without
 * leaking upstream text.
 */
class AndroidTsnetBinding(
    private val enrollmentSource: NativeEnrollmentSource,
) : TailscaleUserspaceCore {
    init {
        // 锁定 AAR 的 Go 运行时在部分 Android 16+ 设备（如 SM-X710，系统未生成
        // /etc/resolv.conf）默认落入纯 Go 解析器（[::1]:53 refused）导致解析失败。
        // 强制 netdns=cgo -> bionic getaddrinfo -> netd 解析；已在真机探针验证可用。
        // 只改运行时 DNS 模式，不触碰 AAR/工具链/权限面。
        runCatching { android.system.Os.setenv("GODEBUG", "netdns=cgo", true) }
    }

    private val lock = Any()
    private var node: NativeNode? = null
    private var channel: UserspaceBridgeChannel? = null

    override suspend fun start(
        nodeIdentity: String,
        stateStore: NoBackupTailnetStateStore,
    ) = synchronized(lock) {
        check(node == null) { "userspace core already started" }
        val restored = stateStore.restoreNodeState()
        var started: NativeNode? = null
        val sink = object : NativeStateSink {
            override fun persistState(state: ByteArray) {
                if (state.isNotEmpty()) {
                    stateStore.persistNodeState(state.copyOf())
                }
            }
        }
        try {
            started = Tsnetbridge.start(
                enrollmentSource.bootstrapBytes(),
                restored ?: ByteArray(0),
                sink,
            )
        } finally {
            stateStore.clearAfterEnrollment()
        }
        checkNotNull(started)
        node = started
    }

    override suspend fun openPairedBridge(
        binding: VerifiedPairingTransportBinding,
    ): UserspaceBridgeChannel = synchronized(lock) {
        val activeNode = checkNotNull(node) { "userspace core is not started" }
        check(channel == null) { "userspace core already owns a Bridge channel" }
        val nativeChannel = try {
            activeNode.openPairedBridge(NativeBindingCodec.encode(binding))
        } catch (failure: Throwable) {
            throw NativeBridgeException(
                failure = NativeErrorMapper.map(failure),
                code = NativeErrorMapper.code(failure),
                cause = failure,
            )
        }
        NativeChannelHandle(nativeChannel).also { channel = it }
    }

    override suspend fun path(
        binding: VerifiedPairingTransportBinding,
    ): TransportPath = synchronized(lock) {
        val activeNode = checkNotNull(node) { "userspace core is not started" }
        try {
            when (activeNode.path(NativeBindingCodec.encode(binding))) {
                "DIRECT" -> TransportPath.DIRECT
                "RELAY" -> TransportPath.RELAY
                "OFFLINE" -> TransportPath.OFFLINE
                else -> TransportPath.CONTROL_UNREACHABLE
            }
        } catch (failure: Throwable) {
            throw NativeBridgeException(
                failure = NativeErrorMapper.map(failure),
                code = NativeErrorMapper.code(failure),
                cause = failure,
            )
        }
    }

    override suspend fun stop() {
        val activeChannel: UserspaceBridgeChannel?
        val activeNode: NativeNode?
        synchronized(lock) {
            activeChannel = channel
            channel = null
            activeNode = node
            node = null
        }
        activeChannel?.close()
        activeNode?.stop()
    }

    private class NativeChannelHandle(
        private val native: NativeChannel,
    ) : UserspaceBridgeChannel {
        override suspend fun sendControl(canonicalWire: ByteArray) {
            require(canonicalWire.isNotEmpty()) { "control wire must not be empty" }
            try {
                native.send(canonicalWire.copyOf())
            } catch (failure: Throwable) {
                throw NativeBridgeException(
                    failure = NativeErrorMapper.map(failure),
                    code = NativeErrorMapper.code(failure),
                    cause = failure,
                )
            }
        }

        override suspend fun receiveControl(): ByteArray = try {
            native.receive().copyOf()
        } catch (failure: Throwable) {
            throw NativeBridgeException(
                failure = NativeErrorMapper.map(failure),
                code = NativeErrorMapper.code(failure),
                cause = failure,
            )
        }

        override suspend fun close() {
            closeNative()
        }

        private fun closeNative() {
            try {
                native.close()
            } catch (failure: Throwable) {
                throw NativeBridgeException(
                    failure = NativeErrorMapper.map(failure),
                    code = NativeErrorMapper.code(failure),
                    cause = failure,
                )
            }
        }
    }
}

/** Stable native failure raised by the seam after mapping. */
class NativeBridgeException(
    val failure: TransportFailure,
    val code: String,
    cause: Throwable,
) : Exception(code, cause)

internal object NativeErrorMapper {
    fun map(failure: Throwable): TransportFailure {
        val code = code(failure)
        return when (code) {
            Tsnetbridge.ErrCodeInvalidBinding -> TransportFailure.INVALID_BINDING
            Tsnetbridge.ErrCodeStaleGeneration -> TransportFailure.STALE_GENERATION
            Tsnetbridge.ErrCodeApprovalRequired -> TransportFailure.APPROVAL_REQUIRED
            Tsnetbridge.ErrCodeControlUnreachable -> TransportFailure.CONTROL_UNREACHABLE
            Tsnetbridge.ErrCodeNetworkBlocked -> TransportFailure.NETWORK_BLOCKED
            Tsnetbridge.ErrCodeChannelClosed -> TransportFailure.NETWORK_BLOCKED
            Tsnetbridge.ErrCodeFrameInvalid -> TransportFailure.NETWORK_BLOCKED
            else -> TransportFailure.CONTROL_UNREACHABLE
        }
    }

    fun code(failure: Throwable): String = failure.message
        ?.lineSequence()
        ?.firstOrNull()
        ?.substringBefore(':')
        ?.trim()
        ?.takeIf { it.startsWith("ERR_") || it.matches(Regex("[A-Z_]+")) }
        ?: ""
}
