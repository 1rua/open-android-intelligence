package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import org.junit.Assert.*
import org.junit.Test

class WorkbenchActiveWorkTest {
    @Test fun pending_and_started_submissions_have_distinct_cancellation_ownership() {
        val work = WorkbenchActiveWork()
        work.begin("pending", "a")
        assertTrue(work.active.value)
        work.cancelPending("pending")
        assertFalse(work.active.value)
        work.begin("posted", "a")
        work.started("posted")
        work.cancelPending("posted")
        assertTrue(work.active.value)
        work.finishSubmission("posted", null)
        assertFalse(work.active.value)
    }

    @Test fun a_terminal_for_another_round_or_conversation_cannot_release_a_live_send() {
        val work = WorkbenchActiveWork()
        work.begin("client", "a")
        work.started("client")
        work.accepted("client", "a", "current")
        work.terminal("a", "old")
        work.accepted("client", "foreign", "foreign-generation")
        work.settledClient("foreign", "client")
        assertTrue(work.active.value)
        work.terminal("a", "current")
        assertFalse(work.active.value)
        work.accepted("client", "a", "current")
        work.atomChanged(atom("a", "client").accepted("server"))
        assertFalse("A delayed HTTP acceptance cannot revive a terminal task", work.active.value)
    }

    @Test fun cancellation_before_the_http_receipt_settles_only_after_exact_generation_binding() {
        val work = WorkbenchActiveWork()
        work.begin("client", "a")
        work.started("client")
        work.terminal("a", "cancelled")
        assertTrue(work.active.value)
        work.accepted("client", "a", "cancelled")
        assertFalse(work.active.value)
    }

    @Test fun all_conversations_remain_owned_until_their_own_terminal_facts_arrive() {
        val work = WorkbenchActiveWork()
        work.begin("first", "a"); work.started("first")
        work.begin("second", "b"); work.started("second")
        work.atomChanged(atom("a", "first").copy(status = AgentMessageStatus.COMPLETED))
        assertTrue(work.active.value)
        work.atomChanged(atom("b", "second").copy(status = AgentMessageStatus.FAILED))
        assertFalse(work.active.value)
    }

    @Test fun an_explicit_retry_is_not_finished_by_the_previous_failed_atom_during_hydration() {
        val work = WorkbenchActiveWork()
        work.begin("retry", "a"); work.started("retry")
        val failed = atom("a", "retry").copy(submissionState = LocalSubmissionState.FAILED)
        work.atomChanged(failed)
        assertTrue(work.active.value)
        work.finishSubmission("retry", failed)
        assertFalse(work.active.value)
        work.begin("retry", "a"); work.started("retry")
        work.atomChanged(failed)
        work.finishSubmission("retry", failed.accepted("accepted"))
        assertTrue(work.active.value)
        work.clear()
        assertFalse(work.active.value)
    }

    private fun atom(thread: String, client: String) = LocalSendAtom(ConversationId(thread), ClientMessageId(client), emptyList(), 1)
}
