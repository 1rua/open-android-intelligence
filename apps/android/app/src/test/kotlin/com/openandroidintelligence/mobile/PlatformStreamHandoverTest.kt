package com.openandroidintelligence.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlatformStreamHandoverTest {
    @Test fun rapidRouteChangesCannotSkipTheActualSocketOwnerWhileItCloses() = runTest {
        val releaseSocket = CompletableDeferred<Unit>()
        var started = 0
        val socketOwner = launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { releaseSocket.await() } }
        }
        runCurrent()
        socketOwner.cancel()
        val firstSuccessor = launchAfterPlatformStream(socketOwner) { started++ }
        firstSuccessor.cancel()
        val finalSuccessor = launchAfterPlatformStream(firstSuccessor) { started++ }
        runCurrent()
        assertFalse(socketOwner.isCompleted)
        assertFalse(firstSuccessor.isCompleted)
        assertFalse(finalSuccessor.isCompleted)
        assertEquals(0, started)
        releaseSocket.complete(Unit)
        runCurrent()
        assertTrue(socketOwner.isCompleted)
        assertTrue(firstSuccessor.isCompleted)
        assertTrue(finalSuccessor.isCompleted)
        assertEquals(1, started)
    }

    @Test fun cancelledSuccessorNeverOpensAnotherStreamAfterTheOldSocketExits() = runTest {
        val release = CompletableDeferred<Unit>()
        val previous = launch { withContext(NonCancellable) { release.await() } }
        runCurrent()
        var opened = false
        val successor = launchAfterPlatformStream(previous) { opened = true }
        successor.cancel()
        release.complete(Unit)
        runCurrent()
        assertTrue(successor.isCompleted)
        assertFalse(opened)
    }
}
