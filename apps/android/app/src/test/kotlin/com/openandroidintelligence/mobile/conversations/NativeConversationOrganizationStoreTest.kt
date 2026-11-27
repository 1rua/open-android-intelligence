package com.openandroidintelligence.mobile.conversations

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeConversationOrganizationStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val keys = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 42 }, "AES")
        override fun delete() = Unit
    }
    private val scope = ConversationScope("profile", "https://gateway.example", "account", "install")
    private fun store(scope: ConversationScope = this.scope) = EncryptedNativeConversationOrganizationStore(context, scope, keys)
    private fun organization() = ConversationOrganization(sortMode = ConversationSortMode.CUSTOM,
        folders = listOf(ConversationFolder("work", "工作")), customOrder = listOf("b", "a"), assignments = mapOf("a" to "work"))

    @Test fun foldersAndCustomSortSurviveRebuildingTheStoreAndStayEncrypted() {
        val committed = store().update("hermes", 0, organization())
        assertEquals(1L, committed.revision)
        assertEquals(committed, store().read("hermes"))
        val bytes = context.noBackupFilesDir.walkTopDown().filter { it.isFile }.flatMap { it.readBytes().asIterable() }.toList().toByteArray()
        assertFalse(bytes.decodeToString().contains("工作"))
    }

    @Test fun preferencesAreIsolatedByAgentAndEveryAccountScopeDimension() {
        store().update("hermes", 0, organization())
        assertEquals(ConversationOrganization(), store().read("codex"))
        listOf(scope.copy(profileId = "other"), scope.copy(gatewayId = "https://other.example"),
            scope.copy(accountId = "other"), scope.copy(installId = "other")).forEach {
            assertEquals(ConversationOrganization(), store(it).read("hermes"))
        }
    }

    @Test fun staleWriterCannotOverwriteAnotherSurfaceAndDeletionKeepsUnrelatedPreferences() {
        val first = store()
        val second = store()
        val committed = first.update("hermes", 0, organization())
        val failure = runCatching { second.update("hermes", 0, ConversationOrganization()) }.exceptionOrNull()
        assertEquals("ORGANIZATION_CONFLICT", failure?.message)
        assertEquals(committed, second.read("hermes"))
        second.removeConversation("hermes", "a")
        val reopened = store().read("hermes")
        assertEquals(2L, reopened.revision)
        assertEquals(listOf("b"), reopened.customOrder)
        assertTrue(reopened.assignments.isEmpty())
        assertEquals(committed.folders, reopened.folders)
        first.removeConversation("hermes", "missing")
        assertEquals(reopened, store().read("hermes"))
    }
}
