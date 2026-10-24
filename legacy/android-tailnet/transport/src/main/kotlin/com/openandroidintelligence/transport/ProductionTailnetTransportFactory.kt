package com.openandroidintelligence.transport

import com.openandroidintelligence.core.model.PairedBridgeTransport
import com.openandroidintelligence.core.model.PairingTransportStatus
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import com.openandroidintelligence.tailnet.core.AndroidTsnetBinding
import com.openandroidintelligence.tailnet.core.ConnectionGenerationPersistence
import com.openandroidintelligence.tailnet.core.EncryptedNoBackupState
import com.openandroidintelligence.tailnet.core.NativeEnrollmentSource
import com.openandroidintelligence.tailnet.core.NoBackupTailnetStateStore
import com.openandroidintelligence.tailnet.core.PersistentConnectionGenerationStore

/**
 * Opaque production transport returned by [ProductionTailnetTransportFactory].
 * The app can observe status through this interface but cannot construct an
 * alternative implementation or inject an endpoint/fake.
 */
sealed interface ProductionPairedBridgeTransport : PairedBridgeTransport {
    fun status(): PairingTransportStatus
}

/**
 * Sealed production composition. All concrete state paths and key aliases are
 * resolved here; callers only supply the verified enrollment source and the
 * app-private state primitives.
 */
class ProductionTailnetTransportFactory(
    private val enrollmentSource: NativeEnrollmentSource,
    private val nodeIdentity: String,
    private val nodeState: EncryptedNoBackupState,
    private val generationPersistence: ConnectionGenerationPersistence,
) {
    fun create(): ProductionPairedBridgeTransport {
        val stateStore = NoBackupTailnetStateStore(nodeState)
        val core = AndroidTsnetBinding(enrollmentSource)
        val generations = PersistentConnectionGenerationStore(generationPersistence)
        val coordinator = PairedBridgeSessionCoordinator(
            core = core,
            nodeIdentity = nodeIdentity,
            stateStore = stateStore,
            generationStore = generations,
        )
        return SealedProductionPairedBridgeTransport(coordinator)
    }

    private class SealedProductionPairedBridgeTransport(
        private val delegate: PairedBridgeSessionCoordinator,
    ) : ProductionPairedBridgeTransport {
        override fun status(): PairingTransportStatus = delegate.status()

        override suspend fun open(binding: VerifiedPairingTransportBinding) = delegate.open(binding)

        override suspend fun close(reason: com.openandroidintelligence.core.model.TransportCloseReason) = delegate.close(reason)
    }
}
