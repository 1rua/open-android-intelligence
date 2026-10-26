package com.openandroidintelligence.tailnet.core

import com.openandroidintelligence.core.model.TransportPath
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding

/**
 * Narrow userspace-core seam. The only application operation is opening the
 * enrollment-ticket-bound Bridge; there is no generic node networking API.
 */
interface TailscaleUserspaceCore {
    suspend fun start(nodeIdentity: String, stateStore: NoBackupTailnetStateStore)
    suspend fun openPairedBridge(binding: VerifiedPairingTransportBinding): UserspaceBridgeChannel
    suspend fun path(binding: VerifiedPairingTransportBinding): TransportPath =
        throw UnsupportedOperationException("native path is unavailable")
    suspend fun stop()
}

interface UserspaceBridgeChannel {
    suspend fun sendControl(canonicalWire: ByteArray)
    suspend fun receiveControl(): ByteArray
    suspend fun close()
}

/** Minimal shape implemented by the pinned gomobile/libtailscale AAR spike. */
interface LibTailscaleBinding {
    fun startNode(nodeIdentity: String, state: ByteArray?): Any
    fun openPairedBridge(node: Any, binding: VerifiedPairingTransportBinding): UserspaceBridgeChannel
    fun stopNode(node: Any)
}

/**
 * Integration spike adapter for a future pinned tsnet/libtailscale AAR. It
 * owns one node handle and exposes only the bound Bridge operation above.
 */
class TsnetLibTailscaleCore(
    private val binding: LibTailscaleBinding,
    private val persistentState: NoBackupTailnetStateStore,
) : TailscaleUserspaceCore {
    private var node: Any? = null

    override suspend fun start(nodeIdentity: String, _stateStore: NoBackupTailnetStateStore) {
        check(node == null) { "userspace core already started" }
        node = binding.startNode(nodeIdentity, persistentState.restoreNodeState())
        persistentState.clearAfterEnrollment()
    }

    override suspend fun openPairedBridge(
        pairing: VerifiedPairingTransportBinding,
    ): UserspaceBridgeChannel {
        val activeNode = checkNotNull(node) { "userspace core is not started" }
        return binding.openPairedBridge(activeNode, pairing)
    }

    override suspend fun stop() {
        node?.let(binding::stopNode)
        node = null
    }

    /** State is persisted before a session is returned to the caller. */
    fun persistNodeStateBeforeSession(nodeState: ByteArray) = persistentState.persistNodeState(nodeState)
}
