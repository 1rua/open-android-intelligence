package com.openandroidintelligence.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionForegroundOwnershipTest {
    @Test fun remoteRevocationKeepsLocalProjectionForegroundOwner() {
        val owners = ProjectionForegroundOwnership()
        owners.acquire("local", null)
        owners.acquire("remote", null)
        assertFalse(owners.release("remote"))
        assertTrue(owners.contains("local"))
        assertFalse(owners.contains("remote"))
        assertTrue(owners.release("local"))
        assertFalse(owners.release("local"))
    }

    @Test fun localRevocationKeepsRemoteProjectionForegroundOwner() {
        val owners = ProjectionForegroundOwnership()
        owners.acquire("remote", null)
        owners.acquire("local", null)
        assertFalse(owners.release("local"))
        assertTrue(owners.contains("remote"))
        assertTrue(owners.release("remote"))
    }

    @Test fun readinessIsScopedToOwnerAndDeliveredAtMostOnce() {
        val owners = ProjectionForegroundOwnership()
        var local = 0
        var remote = 0
        val localGeneration = owners.acquire("local") { local++ }
        val remoteGeneration = owners.acquire("remote") { remote++ }
        owners.ready("remote", remoteGeneration)?.invoke()
        owners.ready("remote", remoteGeneration)?.invoke()
        assertEquals(0, local)
        assertEquals(1, remote)
        owners.ready("local", localGeneration)?.invoke()
        assertEquals(1, local)
    }

    @Test fun revokedQueuedStartHasNoCallbackOrOwnership() {
        val owners = ProjectionForegroundOwnership()
        var called = false
        val generation = owners.acquire("remote") { called = true }
        assertTrue(owners.release("remote"))
        assertNull(owners.ready("remote", generation))
        assertFalse(owners.contains("remote"))
        assertFalse(called)
    }

    @Test fun oldQueuedIntentCannotDeliverNewSameOwnerCallback() {
        val owners = ProjectionForegroundOwnership()
        var oldCalls = 0
        var newCalls = 0
        val old = owners.acquire("remote") { oldCalls++ }
        owners.release("remote")
        val current = owners.acquire("remote") { newCalls++ }
        assertFalse(owners.matches("remote", old))
        assertNull(owners.ready("remote", old))
        assertEquals(0, oldCalls)
        assertEquals(0, newCalls)
        assertTrue(owners.matches("remote", current))
        owners.ready("remote", current)?.invoke()
        assertEquals(1, newCalls)
    }
}
