package com.openandroidintelligence.mobile.plugins

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.plugin.pkg.PackageRejected
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BundledPluginsTest {
    private lateinit var store: PluginInstallStore
    private val screenPlugins = listOf(BundledPlugin.SCREEN_READER, BundledPlugin.SCREEN_CAPTURE, BundledPlugin.SCREEN_CONTROL)

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val files = File(app.cacheDir, "bundled-plugin-tests/${UUID.randomUUID()}").apply { mkdirs() }
        store = PluginInstallStore(object : ContextWrapper(app) {
            override fun getFilesDir(): File = files
        })
    }

    @Test fun entriesMatchAllSixBuildAssetPathsAndKeepTheExistingEntries() {
        assertEquals(listOf(
            "org.openandroidintelligence.notifications" to "bundled-plugins/notifications.alp",
            "org.openandroidintelligence.sms" to "bundled-plugins/sms.alp",
            "org.openandroidintelligence.call-log" to "bundled-plugins/call-log.alp",
            "org.openandroidintelligence.screen-reader" to "bundled-plugins/screen-reader.alp",
            "org.openandroidintelligence.screen-capture" to "bundled-plugins/screen-capture.alp",
            "org.openandroidintelligence.screen-control" to "bundled-plugins/screen-control.alp",
        ), BundledPlugin.entries.map { it.pluginId to it.assetPath })
        assertEquals(listOf("屏幕读取", "屏幕截图", "屏幕控制"), screenPlugins.map { it.displayName })
        assertEquals(6, BundledPlugin.entries.map { it.installTag }.toSet().size)
    }

    @Test fun allSixCompiledPackagesInstallThroughTheSameSignedPackageVerifier() {
        for (plugin in BundledPlugin.entries) {
            val installed = store.installBundledPlugin(plugin, TestAlpPackages.compiledBundledPackage(plugin))
            assertEquals(plugin.pluginId, installed.pluginId)
            assertEquals(plugin.pluginId, store.openVerified(plugin.pluginId).identity.pluginId)
        }
        assertEquals(BundledPlugin.entries.map { it.pluginId }.toSet(), store.listInstalled().map { it.pluginId }.toSet())
        val expected = mapOf(
            BundledPlugin.SCREEN_READER to "kernel.screen.read",
            BundledPlugin.SCREEN_CAPTURE to "kernel.screen.capture",
            BundledPlugin.SCREEN_CONTROL to "kernel.screen.control",
        )
        for ((plugin, primitive) in expected) {
            assertEquals(setOf(primitive), store.openVerified(plugin.pluginId).capabilities.kernelPrimitives)
        }
    }

    @Test fun screenEntriesRejectAnotherPluginsValidSignedPackage() {
        for (plugin in screenPlugins) {
            val other = if (plugin == BundledPlugin.SCREEN_READER) BundledPlugin.SCREEN_CONTROL else BundledPlugin.SCREEN_READER
            val failure = runCatching {
                store.installBundledPlugin(plugin, TestAlpPackages.compiledBundledPackage(other))
            }.exceptionOrNull()
            assertEquals("BUNDLED_PLUGIN_ID_MISMATCH", failure?.message)
        }
        assertTrue(store.listInstalled().isEmpty())
    }

    @Test fun screenEntriesRejectDamagedSignatures() {
        for (plugin in screenPlugins) {
            val original = TestAlpPackages.compiledBundledPackage(plugin)
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { output ->
                ZipInputStream(ByteArrayInputStream(original)).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val content = input.readBytes()
                        if (entry.name == "signature.ed25519") content[0] = if (content[0] == 'A'.code.toByte()) 'B'.code.toByte() else 'A'.code.toByte()
                        output.putNextEntry(ZipEntry(entry.name))
                        output.write(content)
                        output.closeEntry()
                    }
                }
            }
            val failure = runCatching { store.installBundledPlugin(plugin, bytes.toByteArray()) }.exceptionOrNull()
            assertTrue(failure is PackageRejected)
            assertEquals("SIGNATURE_INVALID", failure?.message)
        }
        assertTrue(store.listInstalled().isEmpty())
    }

    @Test fun screenBundledEntriesNeverReplaceAnInstalledAuthor() {
        for (plugin in screenPlugins) {
            store.installBundledPlugin(plugin, TestAlpPackages.compiledBundledPackage(plugin))
            val manifest = File(store.installRoot, "${plugin.pluginId}/manifest.json")
            val original = manifest.readBytes()
            val failure = runCatching {
                store.installBundledPlugin(plugin, TestAlpPackages.compiledBundledPackage(plugin))
            }.exceptionOrNull()
            assertEquals("BUNDLED_PLUGIN_ALREADY_INSTALLED", failure?.message)
            assertArrayEquals(original, manifest.readBytes())
        }
    }
}
