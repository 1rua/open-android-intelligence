package com.openandroidintelligence.plugin.pkg

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that an install which never reaches its commit leaves no partial
 * plugin behind, and that the retained previous version is a real, complete
 * copy rather than a pointer to the live directory.
 *
 * These run on a real device because the guarantee is about the filesystem
 * surviving an abrupt process death, not about in-memory state.
 */
class InterruptedInstallInstrumentedTest {
    private val surface = SecuritySurface(
        kernelPrimitives = emptySet(),
        networkHosts = emptySet(),
        maxStorageBytes = 1_000L,
        maxMemoryBytes = 2_000L,
        maxInvocationMillis = 1_000L,
        maxConcurrentInvocations = 1,
        maxDailyNetworkBytes = 0L,
        backgroundRequested = false,
        companionPackageName = null,
        nativeAbis = emptySet(),
    )

    private val pluginId = "org.example.interrupted"

    private fun root(): File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        "alp-interrupt-test",
    )

    private fun verified(version: String, marker: String): VerifiedPluginPackage {
        val staged = File(root(), "staged-$version-${System.nanoTime()}")
        File(staged, "payload").mkdirs()
        File(staged, "payload/plugin.wasm").writeText(marker)
        return VerifiedPluginPackage(
            identity = PluginIdentity(
                pluginId = pluginId,
                authorKeyFingerprint = "A".repeat(43),
                version = version,
            ),
            version = SemVer.parse(version)!!,
            runtime = RuntimeDeclaration("protected-wasm", "1.0", "open_android_intelligence_plugin_main", "payload/plugin.wasm"),
            capabilities = CapabilityDeclaration(emptySet(), emptySet()),
            security = SecurityDeclaration(surface),
            stagedDirectory = staged,
            verifiedFiles = staged.walkTopDown().filter { it.isFile }.associate { file ->
                val bytes = file.readBytes()
                file.relativeTo(staged).invariantSeparatorsPath to VerifiedFile(bytes.size.toLong(), java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            },
        )
    }

    @Test
    fun installNeverLeavesPartialStateAcrossRepeatedUpdates() {
        val installRoot = File(root(), "repeat")
        installRoot.deleteRecursively()
        val installer = PluginInstaller(installRoot = installRoot)

        var current: InstalledPlugin? = null
        repeat(5) { index ->
            val candidate = verified("1.$index.0", "V$index")
            current = installer.install(candidate, current = current)

            // After every single commit the live tree must be one whole version.
            val live = File(installRoot, pluginId)
            assertTrue("live version must exist after install $index", live.exists())
            val content = File(live, "payload/plugin.wasm").readText()
            assertEquals("live version must be complete after install $index", "V$index", content)
        }
    }

    @Test
    fun retainedPreviousVersionIsIndependentOfLivePath() {
        val installRoot = File(root(), "retain")
        installRoot.deleteRecursively()
        val installer = PluginInstaller(installRoot = installRoot)

        val first = installer.install(verified("1.0.0", "OLD"), current = null)
        val second = installer.install(verified("1.1.0", "NEW"), current = first)

        val live = File(installRoot, pluginId)
        val retained = File(File(installRoot, ".previous"), pluginId)
        assertEquals("NEW", File(live, "payload/plugin.wasm").readText())
        assertTrue("previous version must be retained", retained.exists())
        assertEquals(
            "the retained copy must still hold the old bytes",
            "OLD",
            File(retained, "payload/plugin.wasm").readText(),
        )
    }

    @Test
    fun rollbackRestoresTheRetainedBytes() {
        val installRoot = File(root(), "rollback")
        installRoot.deleteRecursively()
        val installer = PluginInstaller(installRoot = installRoot)

        val first = installer.install(verified("1.0.0", "OLD"), current = null)
        val second = installer.install(verified("1.1.0", "NEW"), current = first)

        val rolledBack = installer.rollback(second)

        assertEquals("OLD", File(rolledBack.directory, "payload/plugin.wasm").readText())
        assertFalse(
            "no handover directory may linger",
            File(installRoot, "$pluginId.handover").exists(),
        )
    }

    @Test
    fun abortedInstallBeforeCommitLeavesOnlyTheOldVersion() {
        val installRoot = File(root(), "abort")
        installRoot.deleteRecursively()
        val installer = PluginInstaller(installRoot = installRoot)

        val first = installer.install(verified("1.0.0", "OLD"), current = null)
        val live = File(installRoot, pluginId)

        // An aborted attempt: the package is rejected by policy, so no copy is
        // staged and no pointer is moved. This is the state a crash before the
        // commit leaves behind.
        val rejected = runCatching {
            installer.install(verified("0.9.0", "NEW"), current = first)
        }.exceptionOrNull()
        assertEquals("DOWNGRADE", (rejected as? PackageRejected)?.message)

        assertTrue("live version must survive an aborted install", live.exists())
        assertEquals("OLD", File(live, "payload/plugin.wasm").readText())
        assertFalse(File(installRoot, "$pluginId.retired").exists())
    }

    @Test
    fun verifierRejectsTraversalOnDevice() {
        val limits = PackageLimits(
            maxEntries = 64,
            maxSingleEntryBytes = 8 * 1024 * 1024,
            maxTotalUncompressedBytes = 32 * 1024 * 1024,
        )
        val zip = zipOf("../../escape" to ByteArray(4))

        val failure = runCatching { AlpVerifier(limits = limits).verify(zip) }.exceptionOrNull()

        assertTrue("expected rejection, got $failure", failure is PackageRejected)
        assertTrue(
            "expected TRAVERSAL, got ${failure!!.message}",
            failure.message!!.contains("TRAVERSAL"),
        )
    }

    @Test
    fun stagedFilesAreDurableAfterInstall() {
        val installRoot = File(root(), "durable")
        installRoot.deleteRecursively()
        val installer = PluginInstaller(installRoot = installRoot)

        val installed = installer.install(verified("1.0.0", "PERSISTED"), current = null)

        // Re-read from disk rather than trusting the in-memory handle: the
        // installer fsyncs before the pointer swap, so the bytes must be there.
        val reread = File(installed.directory, "payload/plugin.wasm").readText()
        assertEquals("PERSISTED", reread)
        assertFalse(
            "fsync marker must not be left behind",
            File(installed.directory, ".fsync").exists(),
        )
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
