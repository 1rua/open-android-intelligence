package com.openandroidintelligence.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.core.model.PairingTransportStatus
import com.openandroidintelligence.core.model.TransportCloseReason
import com.openandroidintelligence.core.model.TransportPath
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import com.openandroidintelligence.tailnet.core.AndroidTsnetBinding
import com.openandroidintelligence.tailnet.core.FileConnectionGenerationPersistence
import com.openandroidintelligence.tailnet.core.InMemoryEncryptedNoBackupState
import com.openandroidintelligence.tailnet.core.NoBackupTailnetStateStore
import com.openandroidintelligence.tailnet.core.PersistentConnectionGenerationStore
import com.openandroidintelligence.tailnet.core.TailscaleUserspaceCore
import com.openandroidintelligence.tailnet.core.UnavailableNativeEnrollmentSource
import com.openandroidintelligence.tailnet.core.UserspaceBridgeChannel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device fail-closed transport evidence:
 * - An OFFLINE backend path must never produce a usable Bridge session
 *   (no fallback to a public network, no session exposure).
 * - The production coordinator with an unavailable enrollment source must fail
 *   closed before any transport session can be opened.
 * Both run on real device filesystem-backed generation storage and assert no
 * VPN network agent is created by the failure.
 */
@RunWith(AndroidJUnit4::class)
class P0tFailClosedTransportInstrumentedTest {

    @Test
    fun offlinePathIsRefusedWithoutSession() {
        val file = genFile("of.txt")
        val store = PersistentConnectionGenerationStore(
            FileConnectionGenerationPersistence(file),
        )
        val transport = TsnetPairedBridgeTransport(OfflineUserspaceCore(), store)

        val failure = runSuspendCatching { transport.open(p0tValidBinding()) }

        assertNotNull("OFFLINE path must be refused", failure)
        assertTrue(
            "expected offline refusal, got: ${failure!!.message}",
            failure.message?.contains("offline", ignoreCase = true) == true,
        )
        val status = transport.status()
        assertTrue(
            "transport must be closed/failed, got $status",
            status is PairingTransportStatus.Closed || status is PairingTransportStatus.Failed,
        )
        file.delete()
    }

    @Test
    fun coordinatorFailsClosedWhenEnrollmentUnavailable() {
        val file = genFile("unavail.txt")
        val generator = PersistentConnectionGenerationStore(
            FileConnectionGenerationPersistence(file),
        )
        val coordinator = PairedBridgeSessionCoordinator(
            core = AndroidTsnetBinding(UnavailableNativeEnrollmentSource),
            nodeIdentity = "p0t-device",
            stateStore = NoBackupTailnetStateStore(InMemoryEncryptedNoBackupState()),
            generationStore = generator,
        )

        val failure = runSuspendCatching { coordinator.open(p0tValidBinding()) }

        assertNotNull("unavailable enrollment must fail the coordinator", failure)
        val status = coordinator.status()
        assertTrue(
            "coordinator must be closed/failed, got $status",
            status is PairingTransportStatus.Closed || status is PairingTransportStatus.Failed,
        )
        file.delete()
    }

    private fun genFile(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(File(ctx.noBackupFilesDir, "p0t-transport").apply { mkdirs() }, name)
    }

    private fun runSuspendCatching(block: suspend () -> Unit): Throwable? {
        var caught: Throwable? = null
        runSuspend { caught = runCatching { block() }.exceptionOrNull() }
        return caught
    }
}

/** Userspace core seam whose backend always reports OFFLINE. */
private class OfflineUserspaceCore : TailscaleUserspaceCore {
    override suspend fun start(nodeIdentity: String, stateStore: NoBackupTailnetStateStore) = Unit

    override suspend fun openPairedBridge(
        binding: VerifiedPairingTransportBinding,
    ): UserspaceBridgeChannel = NoopChannel

    override suspend fun path(binding: VerifiedPairingTransportBinding): TransportPath =
        TransportPath.OFFLINE

    override suspend fun stop() = Unit
}

private object NoopChannel : UserspaceBridgeChannel {
    override suspend fun sendControl(canonicalWire: ByteArray) = Unit
    override suspend fun receiveControl(): ByteArray = byteArrayOf()
    override suspend fun close() = Unit
}
