package com.openandroidintelligence.tailnet.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device process-death persistence evidence: encrypted node state and the
 * monotonic connection generation both live in app-private no-backup files, so
 * a fresh store instance (a new "process") restores exactly what a previous
 * instance wrote.
 *
 * This proves the durable half of "启动节点->断网->重连 的 process-death
 * restore 与 generation 单调递增" on a real device filesystem/Keystore. The
 * canonical native warm-restore of an enrolled node additionally requires a
 * real Tailnet controller and one-time auth key, which stays BLOCKED at the
 * matrix level until that controller input exists.
 */
@RunWith(AndroidJUnit4::class)
class P0tNodeStateProcessDeathInstrumentedTest {

    private fun stateDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.noBackupFilesDir, "p0t-node-state").apply { mkdirs() }
    }

    @Test
    fun keystoreEncryptedNodeStateSurvivesFreshInstance() {
        val dir = stateDir()
        val file = File(dir, "node-state.bin").apply { delete() }
        val alias = "p0t_test_node_state_v1"

        val payload = ByteArray(256) { (it % 251).toByte() }
        // process #1 writes an encrypted, no-backup envelope.
        KeystoreEncryptedNoBackupState(file, alias).write(payload)

        // process #2 (fresh object, fresh keystore handle) restores it.
        val restored = KeystoreEncryptedNoBackupState(file, alias).read()
        assertArrayEquals("node state must survive process death", payload, restored)

        KeystoreEncryptedNoBackupState(file, alias).clear()
        assertNull(KeystoreEncryptedNoBackupState(file, alias).read())
    }

    @Test
    fun generationFileRoundTripsAcrossFreshInstances() {
        val file = File(stateDir(), "generation.bin").apply { delete() }

        FileConnectionGenerationPersistence(file).apply {
            save(41uL)
            assertEquals(41uL, load())
        }
        // A fresh instance (simulated process death) must read the same value.
        val reloaded = FileConnectionGenerationPersistence(file).load()
        assertEquals("generation must survive process death", 41uL, reloaded)

        file.delete()
    }

    @Test
    fun generationStartsBelowFirstReservedAndOnlyIncreases() {
        val file = File(stateDir(), "generation-monotonic.bin").apply { delete() }
        var last = 0uL
        for (i in 1..8) {
            val store = PersistentConnectionGenerationStore(
                FileConnectionGenerationPersistence(file),
            )
            assertEquals("fresh store must continue from persisted value", last, store.current())
            val next = store.reserveNext()
            assertTrue("generation must be monotonic: $next > $last", next > last)
            last = next
        }
        assertEquals(8uL, last)
        file.delete()
    }

    @Test
    fun corruptedGenerationFileFailsClosed() {
        val file = File(stateDir(), "generation-corrupt.bin").apply { delete() }
        file.writeText("not-a-number")
        var thrown: Throwable? = null
        try {
            FileConnectionGenerationPersistence(file).load()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("corrupt generation must fail closed", thrown != null)
        file.delete()
    }
}
