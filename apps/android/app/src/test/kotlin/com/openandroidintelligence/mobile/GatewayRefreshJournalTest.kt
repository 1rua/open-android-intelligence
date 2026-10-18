package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GatewayRefreshJournalTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun aNewProcessReplaysTheSameOperationUntilTheRotatedSecretIsSaved() {
        val original = GatewayRefreshJournal(context).prepare("profile", "old-secret".toByteArray(), true)
        val restarted = GatewayRefreshJournal(context).prepare("profile", "old-secret".toByteArray(), true)
        assertEquals(original.id, restarted.id)
        val next = GatewayRefreshJournal(context).prepare("profile", "new-secret".toByteArray(), true)
        assertNotEquals(original.id, next.id)
        assertEquals(next, GatewayRefreshJournal(context).prepare("profile", "new-secret".toByteArray(), true))
        assertFalse(context.getSharedPreferences("gateway_refresh_operations", 0).all.values.any {
            it.toString().contains("old-secret") || it.toString().contains("new-secret")
        })
    }

    @Test fun aLegacyUnknownOutcomeIsNeverSilentlyResubmittedEvenAfterAnUpgrade() {
        val journal = GatewayRefreshJournal(context)
        journal.prepare("profile", "secret".toByteArray(), false)
        for (replay in listOf(false, true)) {
            assertTrue(runCatching { GatewayRefreshJournal(context).prepare("profile", "secret".toByteArray(), replay) }
                .exceptionOrNull() is RefreshOutcomeUnknownException)
        }
    }

    @Test fun operationsStayPartitionedAndConfirmedCompletionClearsOnlyOneProfile() {
        val journal = GatewayRefreshJournal(context)
        val first = journal.prepare("first", "secret".toByteArray(), true)
        val second = journal.prepare("second", "secret".toByteArray(), true)
        assertNotEquals(first.id, second.id)
        journal.clear("first")
        assertEquals(second, GatewayRefreshJournal(context).prepare("second", "secret".toByteArray(), true))
        assertNotEquals(first, journal.prepare("first", "secret".toByteArray(), true))
    }
}
