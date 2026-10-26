package com.openandroidintelligence.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openandroidintelligence.core.model.PairingTransportStatus
import com.openandroidintelligence.core.model.TransportCloseReason
import com.openandroidintelligence.core.model.TransportFailure
import com.openandroidintelligence.core.model.TransportPath
import com.openandroidintelligence.tailnet.core.FileConnectionGenerationPersistence
import com.openandroidintelligence.tailnet.core.PairingReconnectStateMachine
import com.openandroidintelligence.tailnet.core.PersistentConnectionGenerationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device generation-evidence for "断网->重连 generation 单调递增" and the
 * durable half of process-death restore. Connection generations are persisted
 * in app-private no-backup files, so each fresh store instance (simulated
 * process death / app restart) must continue from the last persisted value and
 * must never reset; the reconnect state machine must fence any stale
 * generation callback.
 */
@RunWith(AndroidJUnit4::class)
class P0tProcessDeathGenerationInstrumentedTest {

    private fun generationFile(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(File(ctx.noBackupFilesDir, "p0t-transport").apply { mkdirs() }, name)
    }

    @Test
    fun generationOnlyIncreasesAcrossSimulatedProcessDeath() {
        val file = generationFile("generation.txt").apply { delete() }
        var last = 0uL
        repeat(10) { process ->
            val store = PersistentConnectionGenerationStore(
                FileConnectionGenerationPersistence(file),
            )
            assertEquals(
                "process #$process must not reset the persisted generation",
                last,
                store.current(),
            )
            val next = store.reserveNext()
            assertTrue("generation must be monotonic: $next > $last", next > last)
            last = next
        }
        assertEquals("expected 10 monotonically increasing generations", 10uL, last)
        file.delete()
    }

    @Test
    fun reconnectIncrementsGenerationAndFencesStaleGeneration() {
        val file = generationFile("generation-reconnect.txt").apply { delete() }
        val store = PersistentConnectionGenerationStore(
            FileConnectionGenerationPersistence(file),
        )
        val machine = PairingReconnectStateMachine(store)
        val binding = p0tValidBinding()

        val first = machine.beginOpen(binding)
        assertTrue(machine.markConnected(first, TransportPath.DIRECT))

        // 网络切换 -> disconnected -> coordinated reconnect with the next attempt.
        machine.markDisconnected(first, TransportCloseReason.NETWORK_CHANGED, attempt = 1)
        val second = machine.beginOpen(binding, attempt = 2)
        assertTrue("reconnect must reserve a higher generation", second > first)
        assertTrue(machine.markConnected(second, TransportPath.DIRECT))

        // A stale late callback carrying the fenced generation must be refused.
        assertFalse(machine.markConnected(first, TransportPath.DIRECT))
        assertEquals(
            PairingTransportStatus.Failed(TransportFailure.STALE_GENERATION),
            machine.status,
        )
        // Next open must still be allowed and continue the monotonic sequence.
        val third = machine.beginOpen(binding, attempt = 3)
        assertTrue(third > second)
        file.delete()
    }

    @Test
    fun persistedGenerationSurvivesReaderWriterSeparation() {
        val file = generationFile("generation-separation.txt").apply { delete() }
        val writer = FileConnectionGenerationPersistence(file)
        writer.save(5uL)
        // A completely separate persistence object sees the durable value.
        val reader = FileConnectionGenerationPersistence(file)
        assertEquals(5uL, reader.load())
        file.delete()
    }
}
