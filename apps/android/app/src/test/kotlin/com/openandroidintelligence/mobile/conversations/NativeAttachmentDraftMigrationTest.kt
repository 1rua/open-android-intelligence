package com.openandroidintelligence.mobile.conversations

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.ports.AttachmentDraftRecoveryStore
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.RecoveredAttachmentDraft
import com.openandroidintelligence.conversation.ports.StagedAttachmentContent
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.encrypted.store.EncryptedAttachmentStagingStore
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeAttachmentDraftMigrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val secret = SecretKeySpec(ByteArray(32) { 37 }, "AES")
    private val keys = object : AesGcmKeyProvider {
        override fun getOrCreate() = secret
        override fun delete() = Unit
    }

    @Test
    fun migratesOnlyCheckpointSelectedDraftsAndKeepsTheBaseCopyReadable() {
        val stores = stores()
        val selectedBytes = "selected native attachment".encodeToByteArray()
        val unselectedBytes = "another account draft".encodeToByteArray()
        val existingChildBytes = "existing child draft".encodeToByteArray()
        val selected = record(stores.sourceStaging, "draft_selected", selectedBytes, "att_selected", "client_selected")
        val unselected = record(stores.sourceStaging, "draft_unselected", unselectedBytes, "att_unselected", "client_unselected")
        val existingChild = record(
            stores.targetStaging,
            "draft_child_existing",
            existingChildBytes,
            "att_child_existing",
            "client_child_existing",
        )
        stores.sourceRecovery.save(listOf(selected, unselected))
        stores.targetRecovery.save(listOf(existingChild))

        val migrated = NativeAttachmentDraftMigration.migrate(
            trustedDraftIds = setOf("draft_selected"),
            sourceStaging = stores.sourceStaging,
            targetStaging = stores.targetStaging,
            sourceRecovery = stores.sourceRecovery,
            targetRecovery = stores.targetRecovery,
        )

        assertEquals(1, migrated)
        val reopenedSourceStaging = EncryptedAttachmentStagingStore(
            stores.sourceDirectory, secret, "base-${stores.suffix}", persistent = true,
        )
        val reopenedTargetStaging = EncryptedAttachmentStagingStore(
            stores.targetDirectory, secret, "child-${stores.suffix}", persistent = true,
        )
        val reopenedSourceRecovery = EncryptedAttachmentRecovery(context, stores.sourceScope, keys)
        val reopenedTargetRecovery = EncryptedAttachmentRecovery(context, stores.targetScope, keys)
        val targetRecords = reopenedTargetRecovery.load().associateBy { it.draft.id.value }
        assertEquals(setOf("draft_selected", "draft_child_existing"), targetRecords.keys)
        val target = targetRecords.getValue("draft_selected")
        assertEquals("draft_selected", target.draft.id.value)
        assertEquals("att_selected", target.remoteId)
        assertEquals("client_selected", target.clientAttachmentId)
        assertArrayEquals(selectedBytes, reopenedTargetStaging.openStream(target.content.id).use { it.readBytes() })
        val existingAfterMigration = targetRecords.getValue("draft_child_existing")
        assertEquals(existingChild.content.id, existingAfterMigration.content.id)
        assertEquals(existingChild.remoteId, existingAfterMigration.remoteId)
        assertEquals(existingChild.clientAttachmentId, existingAfterMigration.clientAttachmentId)
        assertArrayEquals(
            existingChildBytes,
            reopenedTargetStaging.openStream(existingAfterMigration.content.id).use { it.readBytes() },
        )
        assertEquals(0, NativeAttachmentDraftMigration.migrate(
            trustedDraftIds = setOf("draft_selected"),
            sourceStaging = reopenedSourceStaging,
            targetStaging = reopenedTargetStaging,
            sourceRecovery = reopenedSourceRecovery,
            targetRecovery = reopenedTargetRecovery,
        ))
        assertEquals(
            listOf(selected.content.id, unselected.content.id),
            reopenedSourceRecovery.load().map { it.content.id },
        )
        assertArrayEquals(selectedBytes, reopenedSourceStaging.openStream(selected.content.id).use { it.readBytes() })
        assertArrayEquals(unselectedBytes, reopenedSourceStaging.openStream(unselected.content.id).use { it.readBytes() })
    }

    private data class Stores(
        val suffix: String,
        val sourceScope: ConversationScope,
        val targetScope: ConversationScope,
        val sourceDirectory: File,
        val targetDirectory: File,
        val sourceStaging: EncryptedAttachmentStagingStore,
        val targetStaging: EncryptedAttachmentStagingStore,
        val sourceRecovery: AttachmentDraftRecoveryStore,
        val targetRecovery: AttachmentDraftRecoveryStore,
    )

    private fun stores(): Stores {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val sourceScope = ConversationScope("migration", "https://gateway.example", "base-$suffix", "install")
        val targetScope = sourceScope.copy(accountId = "child-$suffix")
        val sourceDirectory = temporaryFolder.newFolder("source-$suffix")
        val targetDirectory = temporaryFolder.newFolder("target-$suffix")
        return Stores(
            suffix,
            sourceScope,
            targetScope,
            sourceDirectory,
            targetDirectory,
            EncryptedAttachmentStagingStore(sourceDirectory, secret, "base-$suffix"),
            EncryptedAttachmentStagingStore(targetDirectory, secret, "child-$suffix"),
            EncryptedAttachmentRecovery(context, sourceScope, keys),
            EncryptedAttachmentRecovery(context, targetScope, keys),
        )
    }

    private fun record(
        staging: EncryptedAttachmentStagingStore,
        draftId: String,
        bytes: ByteArray,
        remoteId: String,
        clientAttachmentId: String,
    ): RecoveredAttachmentDraft {
        val staged = staging.stage(ByteArrayInputStream(bytes))
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return RecoveredAttachmentDraft(
            draft = AttachmentDraft(
                AttachmentDraftId(draftId), "$draftId.txt", "text/plain", bytes.size.toLong(), sha256,
                AttachmentState.RETRYABLE_FAILURE,
            ),
            content = StagedAttachmentContent(staged.id, staged.sizeBytes, staged.sha256Hex),
            remoteId = remoteId,
            clientAttachmentId = clientAttachmentId,
        )
    }
}
