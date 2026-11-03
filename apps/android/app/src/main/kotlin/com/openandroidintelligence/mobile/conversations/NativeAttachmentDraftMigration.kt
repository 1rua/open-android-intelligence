package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.AttachmentDraftRecoveryStore
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.ports.LocalAttachmentStagingStore
import com.openandroidintelligence.conversation.ports.RecoveredAttachmentDraft
import java.util.concurrent.CancellationException

object NativeAttachmentDraftMigration {
    fun migrate(
        trustedDraftIds: Set<String>,
        sourceStaging: LocalAttachmentStagingStore,
        targetStaging: LocalAttachmentStagingStore,
        sourceRecovery: AttachmentDraftRecoveryStore,
        targetRecovery: AttachmentDraftRecoveryStore,
        onBytesStaged: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int {
        if (trustedDraftIds.isEmpty()) return 0

        val targetRecords = targetRecovery.load().toMutableList()
        val targetDraftIds = targetRecords.mapTo(mutableSetOf()) { it.draft.id.value }
        val selectedSourceRecords = sourceRecovery.load().filter { it.draft.id.value in trustedDraftIds }
        var migratedCount = 0

        selectedSourceRecords.forEach { sourceRecord ->
            val draftId = sourceRecord.draft.id.value
            if (draftId in targetDraftIds) return@forEach
            if (isCancelled()) throw CancellationException("ATTACHMENT_MIGRATION_CANCELLED")

            val sourceContent = sourceRecord.content
            check(
                sourceRecord.draft.sizeBytes == sourceContent.sizeBytes &&
                    sourceRecord.draft.sha256.removePrefix("sha256:") == sourceContent.sha256Hex,
            ) { "ATTACHMENT_MIGRATION_SOURCE_METADATA_MISMATCH" }

            val copiedContent = targetStaging.stage(
                LocalAttachmentSelection(
                    filename = sourceRecord.draft.filename,
                    mediaType = sourceRecord.draft.mediaType,
                    contentSource = AttachmentContentSource { sourceStaging.openStream(sourceContent.id) },
                    recoverAfterRestart = true,
                ),
                onBytesStaged = onBytesStaged,
                isCancelled = isCancelled,
            )
            check(
                copiedContent.sizeBytes == sourceContent.sizeBytes &&
                    copiedContent.sha256Hex == sourceContent.sha256Hex,
            ) { "ATTACHMENT_MIGRATION_INTEGRITY_FAILED" }

            val migratedRecord = sourceRecord.copy(content = copiedContent)
            targetRecovery.save(targetRecords + migratedRecord)
            targetRecords += migratedRecord
            targetDraftIds += draftId
            migratedCount++
        }

        return migratedCount
    }
}
