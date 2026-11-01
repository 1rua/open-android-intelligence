package com.openandroidintelligence.kernel

import org.junit.Assert.*
import org.junit.Test

class CapabilityAuthorizationTest {
    private val read = CapabilityRequestScope("author:sms.query", DeviceOperation.READ, "a".repeat(64))
    private val write = CapabilityRequestScope("author:sms.send", DeviceOperation.WRITE, "b".repeat(64))

    @Test fun acceptsTheCanonicalGatewaySha256Format() {
        val scope = CapabilityRequestScope("sms", DeviceOperation.READ, "sha256:" + "a".repeat(64))
        val controller = CapabilityAuthorizationController { 1L }
        controller.approve("one", scope, 5, 0, 300_000)
        assertTrue(controller.evaluate("two", scope, CapabilityPolicy(), 0) is CapabilityAuthorization.Allowed)
    }

    @Test fun manualDefaultsAndIndependentReadOnlyMode() {
        val controller = CapabilityAuthorizationController { 1L }
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, controller.evaluate("read", read, CapabilityPolicy(), 0))
        val policy = CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY)
        assertTrue(controller.evaluate("read", read, policy, 0) is CapabilityAuthorization.Allowed)
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, controller.evaluate("write", write, policy, 0))
        assertTrue(controller.evaluate("write", write, CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC), 0)
            is CapabilityAuthorization.Allowed)
        assertEquals(CapabilityAuthorization.Denied("LOCAL_CAPABILITY_DISABLED"),
            controller.evaluate("read", read, policy.copy(enabled = false), 0))
    }

    @Test fun timedApprovalIsSharedOnlyForIdenticalCapabilityOperationAndDataScope() {
        var now = 100L
        val controller = CapabilityAuthorizationController { now }
        val approval = controller.approve("core-a:account-a:agent-a", read, 5, 9, 300_000)
        assertTrue(controller.evaluate("core-b:account-b:agent-b", read, CapabilityPolicy(), 9) is CapabilityAuthorization.Allowed)
        assertEquals(CapabilityAuthorization.AwaitingConfirmation,
            controller.evaluate("other", read.copy(parametersSha256 = "c".repeat(64)), CapabilityPolicy(), 9))
        assertEquals(CapabilityAuthorization.AwaitingConfirmation,
            controller.evaluate("other", read.copy(operation = DeviceOperation.CONTROL), CapabilityPolicy(), 9))
        now += 300_000
        assertFalse(controller.isCurrent(approval.id, "other", read, 9))
    }

    @Test fun singleApprovalCannotAuthorizeAnotherRequestAndDiesOnProcessRestart() {
        val controller = CapabilityAuthorizationController { 5L }
        val approval = controller.approve("one", write, 0, 1, 50_000)
        assertTrue(controller.isCurrent(approval.id, "one", write, 1))
        assertFalse(controller.isCurrent(approval.id, "two", write, 1))
        assertFalse(CapabilityAuthorizationController { 5L }.isCurrent(approval.id, "one", write, 1))
        controller.finish("one")
        assertFalse(controller.isCurrent(approval.id, "one", write, 1))
    }

    @Test fun policyChangeCancellationAndRevokeFenceApprovals() {
        val controller = CapabilityAuthorizationController { 1L }
        var approval = controller.approve("origin", read, 60, 1, 300_000)
        assertFalse(controller.isCurrent(approval.id, "next", read, 2))
        approval = controller.approve("origin", read, 30, 2, 300_000)
        controller.cancel("origin")
        assertFalse(controller.isCurrent(approval.id, "next", read, 2))
        approval = controller.approve("new", read, 5, 2, 300_000)
        controller.revokeAll()
        assertFalse(controller.isCurrent(approval.id, "next", read, 2))
    }

    @Test fun globalPolicySurvivesBindingChangesAndOfflineEdits() {
        val store = InMemoryPairingGrantStore()
        val a = PairingGrantBinding("https://a.example", "alice", "phone")
        val b = PairingGrantBinding("https://b.example", "bob", "phone")
        val holder = PairingGrantStateHolder(store, AndroidAuditStore())
        holder.bind(a)
        holder.updateCapabilityPolicy("sms", CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY))
        val revision = holder.state.value!!.revision
        holder.bind(b)
        assertEquals(revision, holder.state.value!!.revision)
        assertEquals(CapabilityAuthorizationMode.READ_ONLY, holder.capabilityPolicy("sms").mode)
        assertNull(holder.currentKernelGrant(a.pairingId))
        holder.unbind()
        assertNotNull(holder.state.value)
        holder.updateCapabilityPolicy("sms", CapabilityPolicy(enabled = false))
        holder.bind(a)
        assertFalse(holder.capabilityPolicy("sms").enabled)
        assertNotNull(holder.currentKernelGrant(a.pairingId))
    }
    @Test fun multipleTrustedConnectionsSharePolicyButDoNotDetachEachOthersOwnership() {
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val a = PairingGrantBinding("https://a.example", "alice", "phone")
        val b = PairingGrantBinding("https://b.example", "bob", "phone")
        holder.bind(a)
        holder.attachConnection(b)
        holder.updateCapabilityPolicy("sms", CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY))
        assertEquals(holder.currentKernelGrant(a.pairingId)?.revision, holder.currentKernelGrant(b.pairingId)?.revision)
        holder.bind(b)
        holder.detachConnection(b)
        assertNotNull(holder.currentKernelGrant(b.pairingId))
        holder.attachConnection(a)
        holder.unbind()
        assertNotNull(holder.currentKernelGrant(a.pairingId))
        assertNull(holder.currentKernelGrant(b.pairingId))
        holder.detachConnection(a)
        assertNull(holder.currentKernelGrant(a.pairingId))
        assertEquals(CapabilityAuthorizationMode.READ_ONLY, holder.capabilityPolicy("sms").mode)
    }

    @Test fun auditFailureCannotRollBackARevocationOrPersistAnExpansionAcrossRestart() {
        val store = InMemoryPairingGrantStore()
        var failAudit = false
        val sink = object : AuditSink { override fun write(event: AuditEvent) {
            if (failAudit) error("disk-full")
        } }
        val holder = PairingGrantStateHolder(store, AndroidAuditStore(sink))
        val binding = PairingGrantBinding("https://a.example", "alice", "phone")
        holder.bind(binding)
        holder.updateCapabilityPolicy("sms", CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC))
        failAudit = true
        try { holder.updateCapabilityPolicy("sms", CapabilityPolicy(enabled = false)); fail("audit must report failure") }
        catch (expected: IllegalStateException) { assertEquals("LOCAL_AUTHORIZATION_AUDIT_FAILED", expected.message) }
        assertFalse(holder.capabilityPolicy("sms").enabled)
        assertEquals("LOCAL_AUTHORIZATION_UPDATE_INCOMPLETE", holder.state.value?.localError)
        val restarted = PairingGrantStateHolder(store, AndroidAuditStore()).apply { bind(binding) }
        assertFalse(restarted.capabilityPolicy("sms").enabled)
        assertNotNull(restarted.currentKernelGrant(binding.pairingId))
        assertEquals(CapabilityAuthorizationMode.MANUAL, restarted.capabilityPolicy("sms").mode)
        assertNull(restarted.state.value?.localError)
    }

    @Test fun lateOldConnectionCloseCannotDetachANewerOwnerOfTheSameBinding() {
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val binding = PairingGrantBinding("https://a.example", "alice", "phone")
        val old = holder.attachConnection(binding)
        holder.detachConnection(binding, old)
        val replacement = holder.attachConnection(binding)
        holder.detachConnection(binding, old)
        assertTrue(holder.isConnectionCurrent(binding))
        holder.detachConnection(binding, replacement)
        assertFalse(holder.isConnectionCurrent(binding))
    }

}
