package com.openandroidintelligence.conversation.remote

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteFileRefTest {
    private val session = remoteOwner().session

    @Test
    fun committedWorkspacePathsPreserveHostReferencesAndRemainSessionScoped() {
        val file = RemoteFileRef(session, "./照片 1.png", sizeBytes = 0)
        val otherSession = session.copy(nativeSessionId = "hermes:other")
        val otherHost = session.copy(endpoint = session.endpoint.copy(hostId = "host-b"))

        assertEquals("./照片 1.png", file.path)
        assertTrue(file.belongsTo(session))
        assertFalse(file.belongsTo(otherSession))
        assertFalse(file.belongsTo(otherHost))
        assertNotEquals(file, file.copy(session = otherHost))
        assertNull(file.sha256)
        assertNull(file.contentVersion)
    }

    @Test
    fun workspaceReferencesRejectAbsolutePathsTraversalAndAmbiguousSegments() {
        listOf("", "/tmp/file.png", "C:/file.png", "\\\\host\\share", "../file", "./../file",
            "dir/../../file", "dir//file", "dir/./file", "./", "file\u0000.png").forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) { RemoteFileRef(session, path) }
        }
        assertEquals("images/photo.png", RemoteFileRef(session, "images/photo.png").path)
    }

    @Test
    fun digestAndVersionAreOptionalHostEvidenceRatherThanUploadIdentifiers() {
        val digest = "sha256:" + "a".repeat(64)
        val file = RemoteFileRef(session, "./photo.png", 42, digest, "host-revision-7")
        assertEquals(digest, file.sha256)
        assertEquals("host-revision-7", file.contentVersion)
        assertFailsWith<IllegalArgumentException> { file.copy(sizeBytes = -1) }
        assertFailsWith<IllegalArgumentException> { file.copy(sha256 = "upload-a") }
    }
}
