package com.openandroidintelligence.encrypted.store

import com.openandroidintelligence.control.remote.*
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class ControlMetadataStoreTest {
    @Test fun restartKeepsConfirmedFactsAndConservativelyEndsUnfinishedRequests() {
        val persistence = InMemoryOutboxPersistence()
        fun store() = ControlMetadataStore(AesGcmEncryptedBlobStore(persistence, SecretKeySpec(ByteArray(32) { 3 }, "AES")))
        val first = store()
        val pending = ControlRequestMetadata("owner", "request-pending-0001", "task", "ui.tap", "sha256:" + "a".repeat(64),
            ControlRequestState.AWAITING_CONFIRMATION, ControlExecutionStatus.NOT_STARTED)
        first.transaction { rows ->
            rows[pending.key] = pending
            rows["owner:fenced"] = pending.copy(requestId = "fenced", state = ControlRequestState.RUNNING, executionFence = true)
            rows["owner:completed"] = pending.copy(requestId = "completed", state = ControlRequestState.COMPLETED, execution = ControlExecutionStatus.SUCCEEDED, executionFence = true)
        }
        val recovered = store().transaction { it.toMap() }
        assertEquals(ControlRequestState.CANCELLED, recovered.getValue(pending.key).state)
        assertEquals(ControlRequestState.OUTCOME_UNKNOWN, recovered.getValue("owner:fenced").state)
        assertEquals(ControlExecutionStatus.UNKNOWN, recovered.getValue("owner:fenced").execution)
        assertEquals(ControlRequestState.COMPLETED, recovered.getValue("owner:completed").state)
        assertFalse(persistence.bytes!!.decodeToString().contains("ui.tap"))
    }
}
