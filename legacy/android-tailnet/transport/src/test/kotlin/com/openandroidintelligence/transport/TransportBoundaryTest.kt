package com.openandroidintelligence.transport

import com.openandroidintelligence.core.model.BridgeIdentity
import com.openandroidintelligence.core.model.BridgeSession
import com.openandroidintelligence.core.model.EnrollmentTicket
import com.openandroidintelligence.core.model.PolicyAttestation
import com.openandroidintelligence.core.model.TransportCloseReason
import com.openandroidintelligence.core.model.TransportFailure
import com.openandroidintelligence.core.model.TransportPath
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import com.openandroidintelligence.tailnet.core.ConnectionGenerationStore
import com.openandroidintelligence.tailnet.core.InMemoryConnectionGenerationStore
import com.openandroidintelligence.tailnet.core.InMemoryEncryptedNoBackupState
import com.openandroidintelligence.tailnet.core.LibTailscaleBinding
import com.openandroidintelligence.tailnet.core.NoBackupTailnetStateStore
import com.openandroidintelligence.tailnet.core.PersistentConnectionGenerationStore
import com.openandroidintelligence.tailnet.core.TsnetLibTailscaleCore
import com.openandroidintelligence.tailnet.core.TailscaleUserspaceCore
import com.openandroidintelligence.tailnet.core.UserspaceBridgeChannel
import com.openandroidintelligence.tailnet.core.VerifiedPairingTransportBindingFactory
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransportBoundaryTest {
    @Test
    fun fake_transport_round_trips_control_bytes_and_fences_after_disconnect() {
        val transport = FakeUserspaceTransport()
        val binding = validBinding()
        val first = runSuspend { transport.open(binding) }
        transport.enqueueInbound(byteArrayOf(3, 4))
        runSuspend { first.sendControl(byteArrayOf(1, 2)) }
        assertArrayEquals(byteArrayOf(1, 2), transport.sentControl().single())
        assertArrayEquals(byteArrayOf(3, 4), runSuspend { first.receiveControl() })

        transport.disconnect()
        assertThrows(IllegalStateException::class.java) {
            runSuspend { first.sendControl(byteArrayOf(9)) }
        }
        val second = runSuspend { transport.open(binding) }
        assertEquals(first.connectionGeneration + 1u, second.connectionGeneration)
    }

    @Test
    fun binding_rejects_expired_or_wrong_bridge_material() {
        val ticket = EnrollmentTicket("ticket", "device", "bridge-a", 7u, 2u, 100)
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedPairingTransportBindingFactory.mint(
                ticket,
                BridgeIdentity("bridge-b"),
                PolicyAttestation(2u, "digest"),
                expectedPairingGeneration = 7u,
                nowEpochSeconds = 10,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedPairingTransportBindingFactory.mint(
                ticket,
                BridgeIdentity("bridge-a"),
                PolicyAttestation(2u, "digest"),
                expectedPairingGeneration = 7u,
                nowEpochSeconds = 101,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedPairingTransportBindingFactory.mint(
                ticket.copy(used = true),
                BridgeIdentity("bridge-a"),
                PolicyAttestation(2u, "digest"),
                expectedPairingGeneration = 7u,
                nowEpochSeconds = 10,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedPairingTransportBindingFactory.mint(
                ticket,
                BridgeIdentity("bridge-a"),
                PolicyAttestation(1u, "stale"),
                expectedPairingGeneration = 7u,
                nowEpochSeconds = 10,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedPairingTransportBindingFactory.mint(
                ticket,
                BridgeIdentity("bridge-a"),
                PolicyAttestation(2u, "digest"),
                expectedPairingGeneration = 8u,
                nowEpochSeconds = 10,
            )
        }
    }

    @Test
    fun tsnet_adapter_calls_only_bound_bridge_operation() {
        val core = RecordingCore()
        val transport = TsnetPairedBridgeTransport(core)
        val session = runSuspend { transport.open(validBinding()) }
        runSuspend { session.sendControl(byteArrayOf(8)) }
        assertEquals(1, core.openCalls)
        assertArrayEquals(byteArrayOf(8), core.sent.single())
        runSuspend { transport.close(TransportCloseReason.USER_REQUEST) }
    }

    @Test
    fun tsnet_adapter_rejects_and_closes_a_session_when_generation_is_fenced_during_open() {
        val generations = InMemoryConnectionGenerationStore()
        val core = RecordingCore(onOpen = { generations.reserveNext() })
        val transport = TsnetPairedBridgeTransport(core, generations)

        assertThrows(IllegalStateException::class.java) {
            runSuspend { transport.open(validBinding()) }
        }
        assertEquals(1, core.closeCalls)
        assertEquals(
            com.openandroidintelligence.core.model.PairingTransportStatus.Failed(TransportFailure.STALE_GENERATION),
            transport.status(),
        )
    }

    @Test
    fun no_backup_state_is_cleared_after_enrollment() {
        val raw = InMemoryEncryptedNoBackupState()
        val authKeys = RecordingEnrollmentAuthKeyStore()
        val store = NoBackupTailnetStateStore(raw, authKeys)
        store.persistNodeState(byteArrayOf(5, 6))
        assertArrayEquals(byteArrayOf(5, 6), store.restoreNodeState())
        store.clearAfterEnrollment()
        assertArrayEquals(byteArrayOf(5, 6), store.restoreNodeState())
        assertEquals(1, authKeys.clearCalls)
    }

    @Test
    fun stale_generation_is_reported_as_fenced() {
        val generations = InMemoryConnectionGenerationStore()
        val state = com.openandroidintelligence.tailnet.core.PairingReconnectStateMachine(generations)
        val binding = validBinding()
        val first = state.beginOpen(binding)
        generations.reserveNext()
        state.markConnected(first, TransportPath.DIRECT)
        assertEquals(
            com.openandroidintelligence.core.model.PairingTransportStatus.Failed(TransportFailure.STALE_GENERATION),
            state.status,
        )
    }

    @Test
    fun persistent_generation_store_restores_and_advances_across_instances() {
        val persisted = RecordingGenerationPersistence()
        val first = com.openandroidintelligence.tailnet.core.PersistentConnectionGenerationStore(persisted)
        assertEquals(1uL, first.reserveNext())

        val restored = com.openandroidintelligence.tailnet.core.PersistentConnectionGenerationStore(persisted)
        assertEquals(1uL, restored.current())
        assertEquals(2uL, restored.reserveNext())
        assertEquals(2uL, persisted.value)
    }

    @Test
    fun tsnet_core_uses_its_constructor_state_and_clears_enrollment_auth_key() {
        val constructorRaw = InMemoryEncryptedNoBackupState()
        val constructorAuth = RecordingEnrollmentAuthKeyStore()
        val constructorState = NoBackupTailnetStateStore(constructorRaw, constructorAuth)
        constructorState.persistNodeState(byteArrayOf(1, 2))

        val passedRaw = InMemoryEncryptedNoBackupState()
        val passedState = NoBackupTailnetStateStore(passedRaw)
        passedState.persistNodeState(byteArrayOf(9))

        val binding = RecordingLibTailscaleBinding()
        val core = TsnetLibTailscaleCore(binding, constructorState)
        runSuspend { core.start("node-a", passedState) }

        assertArrayEquals(byteArrayOf(1, 2), binding.restoredState)
        assertEquals(1, constructorAuth.clearCalls)
        assertArrayEquals(byteArrayOf(9), passedState.restoreNodeState())
    }

    @Test
    fun coordinator_rejects_invalid_material_before_starting_core() {
        val core = LifecycleRecordingCore()
        val coordinator = coordinator(core)
        val invalid = pairingMaterial(expiresAtEpochSeconds = 9)

        assertThrows(IllegalArgumentException::class.java) {
            runSuspend { coordinator.connect(invalid) }
        }
        assertEquals(0, core.startCalls)
        assertEquals(0, core.openCalls)
    }

    @Test
    fun coordinator_connect_starts_core_once_and_returns_first_generation() {
        val core = LifecycleRecordingCore()
        val coordinator = coordinator(core)

        val session = runSuspend { coordinator.connect(pairingMaterial()) }
        val reconnected = runSuspend { coordinator.reconnect(pairingMaterial("ticket-2")) }

        assertEquals(1uL, session.connectionGeneration)
        assertEquals(2uL, reconnected.connectionGeneration)
        assertEquals(1, core.startCalls)
        assertEquals(2, core.openCalls)
        assertEquals(0, core.stopCalls)
    }

    @Test
    fun coordinator_reconnect_closes_old_channel_persists_new_generation_and_fences_old_session() {
        val persistence = RecordingGenerationPersistence()
        val generations = PersistentConnectionGenerationStore(persistence)
        val core = LifecycleRecordingCore()
        val coordinator = coordinator(core, generations)
        val first = runSuspend { coordinator.connect(pairingMaterial()) }
        runSuspend { first.sendControl(byteArrayOf(1)) }

        val second = runSuspend { coordinator.reconnect(pairingMaterial("ticket-2")) }

        assertEquals(2uL, second.connectionGeneration)
        assertEquals(2uL, persistence.value)
        assertEquals(1, core.channels[0].closeCalls)
        assertThrows(IllegalStateException::class.java) {
            runSuspend { first.sendControl(byteArrayOf(2)) }
        }
        runSuspend { second.sendControl(byteArrayOf(3)) }
        assertArrayEquals(byteArrayOf(3), core.channels[1].sent.single())
    }

    @Test
    fun coordinator_rejects_invalid_reconnect_without_disturbing_active_session() {
        val core = LifecycleRecordingCore()
        val coordinator = coordinator(core)
        val active = runSuspend { coordinator.connect(pairingMaterial()) }

        assertThrows(IllegalArgumentException::class.java) {
            runSuspend { coordinator.reconnect(pairingMaterial("expired", expiresAtEpochSeconds = 9)) }
        }

        runSuspend { active.sendControl(byteArrayOf(6)) }
        assertEquals(1, core.startCalls)
        assertEquals(1, core.openCalls)
        assertEquals(0, core.stopCalls)
        assertArrayEquals(byteArrayOf(6), core.channels.single().sent.single())
    }

    @Test
    fun coordinator_reconnect_failure_stops_core_and_exposes_no_new_session() {
        val core = LifecycleRecordingCore(failOnOpenCall = 2)
        val coordinator = coordinator(core)
        val first = runSuspend { coordinator.connect(pairingMaterial()) }

        assertThrows(IllegalStateException::class.java) {
            runSuspend { coordinator.reconnect(pairingMaterial("ticket-2")) }
        }

        assertEquals(1, core.stopCalls)
        assertEquals(1, core.channels[0].closeCalls)
        assertEquals(1, core.channels.size)
        assertThrows(IllegalStateException::class.java) {
            runSuspend { first.sendControl(byteArrayOf(4)) }
        }
    }

    @Test
    fun coordinator_generation_race_closes_new_channel_and_reports_stale_generation() {
        val generations = InMemoryConnectionGenerationStore()
        val core = LifecycleRecordingCore(onOpen = { generations.reserveNext() })
        val coordinator = coordinator(core, generations)

        assertThrows(IllegalStateException::class.java) {
            runSuspend { coordinator.connect(pairingMaterial()) }
        }

        assertEquals(1, core.openCalls)
        assertEquals(1, core.channels.single().closeCalls)
        assertEquals(
            com.openandroidintelligence.core.model.PairingTransportStatus.Failed(TransportFailure.STALE_GENERATION),
            coordinator.status(),
        )
    }

    @Test
    fun tsnet_reconnect_stops_when_the_old_session_generation_is_already_stale() {
        val generations = InMemoryConnectionGenerationStore()
        val core = LifecycleRecordingCore()
        val transport = TsnetPairedBridgeTransport(core, generations)
        val first = runSuspend { transport.open(validBinding()) }
        generations.reserveNext()

        assertThrows(IllegalStateException::class.java) {
            runSuspend { transport.reconnect(validBinding()) }
        }

        assertEquals(1, core.openCalls)
        assertEquals(1, core.channels.single().closeCalls)
        assertEquals(
            com.openandroidintelligence.core.model.PairingTransportStatus.Failed(TransportFailure.STALE_GENERATION),
            transport.status(),
        )
        assertThrows(IllegalStateException::class.java) {
            runSuspend { first.sendControl(byteArrayOf(5)) }
        }
    }

    @Test
    fun coordinator_close_stops_core_and_is_idempotent() {
        val core = LifecycleRecordingCore()
        val coordinator = coordinator(core)
        runSuspend { coordinator.connect(pairingMaterial()) }

        runSuspend { coordinator.close(TransportCloseReason.USER_REQUEST) }
        runSuspend { coordinator.close(TransportCloseReason.USER_REQUEST) }

        assertEquals(1, core.stopCalls)
        assertEquals(1, core.channels.single().closeCalls)
    }

    @Test
    fun coordinator_restores_generation_after_process_restart() {
        val persistence = RecordingGenerationPersistence()
        val firstCore = LifecycleRecordingCore()
        val first = coordinator(
            firstCore,
            PersistentConnectionGenerationStore(persistence),
        )
        assertEquals(1uL, runSuspend { first.connect(pairingMaterial()) }.connectionGeneration)
        runSuspend { first.close(TransportCloseReason.PROCESS_STOPPED) }

        val secondCore = LifecycleRecordingCore()
        val second = coordinator(
            secondCore,
            PersistentConnectionGenerationStore(persistence),
        )

        assertEquals(2uL, runSuspend { second.connect(pairingMaterial("ticket-2")) }.connectionGeneration)
        assertEquals(2uL, persistence.value)
    }

    private fun validBinding(): VerifiedPairingTransportBinding =
        VerifiedPairingTransportBindingFactory.mint(
            ticket = EnrollmentTicket("ticket", "device", "bridge-a", 7u, 2u, 100),
            bridge = BridgeIdentity("bridge-a"),
            policy = PolicyAttestation(2u, "digest"),
            expectedPairingGeneration = 7u,
            nowEpochSeconds = 10,
        )

    private fun pairingMaterial(
        ticketId: String = "ticket-1",
        expiresAtEpochSeconds: Long = 100,
    ): PairingMaterial = PairingMaterial(
        ticket = EnrollmentTicket(
            id = ticketId,
            deviceId = "device",
            bridgeIdentity = "bridge-a",
            pairingGeneration = 7u,
            minimumPolicyRevision = 2u,
            expiresAtEpochSeconds = expiresAtEpochSeconds,
        ),
        bridge = BridgeIdentity("bridge-a"),
        policy = PolicyAttestation(2u, "digest"),
        expectedPairingGeneration = 7u,
        nowEpochSeconds = 10,
    )

    private fun coordinator(
        core: TailscaleUserspaceCore,
        generations: ConnectionGenerationStore = InMemoryConnectionGenerationStore(),
    ): PairedBridgeSessionCoordinator = PairedBridgeSessionCoordinator(
        core = core,
        nodeIdentity = "node-a",
        stateStore = NoBackupTailnetStateStore(InMemoryEncryptedNoBackupState()),
        generationStore = generations,
    )

    private class RecordingCore(
        private val onOpen: () -> Unit = {},
    ) : TailscaleUserspaceCore {
        var openCalls = 0
        var closeCalls = 0
        val sent = mutableListOf<ByteArray>()
        private val channel = object : UserspaceBridgeChannel {
            override suspend fun sendControl(canonicalWire: ByteArray) { sent += canonicalWire.copyOf() }
            override suspend fun receiveControl(): ByteArray = byteArrayOf(0)
            override suspend fun close() { closeCalls += 1 }
        }

        override suspend fun start(nodeIdentity: String, stateStore: com.openandroidintelligence.tailnet.core.NoBackupTailnetStateStore) = Unit

        override suspend fun openPairedBridge(binding: VerifiedPairingTransportBinding): UserspaceBridgeChannel {
            openCalls += 1
            onOpen()
            return channel
        }

        override suspend fun path(binding: VerifiedPairingTransportBinding): TransportPath = TransportPath.DIRECT

        override suspend fun stop() = Unit
    }

    private class RecordingGenerationPersistence : com.openandroidintelligence.tailnet.core.ConnectionGenerationPersistence {
        var value: ULong? = null

        override fun load(): ULong? = value

        override fun save(generation: ULong) {
            value = generation
        }
    }

    private class RecordingEnrollmentAuthKeyStore : com.openandroidintelligence.tailnet.core.EnrollmentAuthKeyStore {
        var clearCalls = 0

        override fun clear() {
            clearCalls += 1
        }
    }

    private class RecordingLibTailscaleBinding : LibTailscaleBinding {
        var restoredState: ByteArray? = null

        override fun startNode(nodeIdentity: String, state: ByteArray?): Any {
            restoredState = state?.copyOf()
            return Any()
        }

        override fun openPairedBridge(
            node: Any,
            binding: VerifiedPairingTransportBinding,
        ): UserspaceBridgeChannel = error("not used")

        override fun stopNode(node: Any) = Unit
    }

    private class LifecycleRecordingCore(
        private val onOpen: () -> Unit = {},
        private val failOnOpenCall: Int? = null,
    ) : TailscaleUserspaceCore {
        var startCalls = 0
        var stopCalls = 0
        var openCalls = 0
        val channels = mutableListOf<LifecycleRecordingChannel>()

        override suspend fun start(
            nodeIdentity: String,
            stateStore: NoBackupTailnetStateStore,
        ) {
            startCalls += 1
        }

        override suspend fun openPairedBridge(
            binding: VerifiedPairingTransportBinding,
        ): UserspaceBridgeChannel {
            openCalls += 1
            onOpen()
            if (openCalls == failOnOpenCall) throw IllegalStateException("open failed")
            return LifecycleRecordingChannel().also(channels::add)
        }

        override suspend fun path(binding: VerifiedPairingTransportBinding): TransportPath = TransportPath.DIRECT

        override suspend fun stop() {
            stopCalls += 1
        }
    }

    private class LifecycleRecordingChannel : UserspaceBridgeChannel {
        var closeCalls = 0
        val sent = mutableListOf<ByteArray>()

        override suspend fun sendControl(canonicalWire: ByteArray) {
            sent += canonicalWire.copyOf()
        }

        override suspend fun receiveControl(): ByteArray = byteArrayOf(0)

        override suspend fun close() {
            closeCalls += 1
        }
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result = value }
    })
    return checkNotNull(result).getOrThrow()
}
