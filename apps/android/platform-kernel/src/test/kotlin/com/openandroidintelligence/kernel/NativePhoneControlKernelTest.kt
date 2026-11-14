package com.openandroidintelligence.kernel

import com.openandroidintelligence.control.ScreenAction
import com.openandroidintelligence.control.ScreenReadQuery
import com.openandroidintelligence.control.remote.*
import org.junit.Assert.*
import org.junit.Test

class NativePhoneControlKernelTest {
    private fun access(device: String = "controller") = ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical("https://remote.example"),
        "account", device, "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)), 1, "connection", 1)
    private fun request(access: ControlAccessBinding) = PhoneExecutionContext(ControlRequestScope(
        ControlTaskScope(access.principal, "task", 1, "process"), "request-native-00001"), access, "sha256:" + "c".repeat(64), {}, {})
    @Test fun readOnlyDoesNotGrantScreenshotOrControlAndAutomaticCannotBypassManualControl() {
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val binding = RemoteCapabilityBinding(access()) { true }; holder.attachRemoteConnection(binding)
        val primitives = setOf("kernel.screen.read", "kernel.screen.capture", "kernel.screen.control")
        val kernel = NativePhoneControlKernel(holder, holder.authorization, { primitives }, { primitives },
            { if (it == "kernel.screen.read") CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY) else CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC) }, { false })
        val context = request(access())
        assertTrue(kernel.evaluate(context, binding, ControlOperation.DumpTree(ScreenReadQuery())) is CapabilityAuthorization.Allowed)
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, kernel.evaluate(context, binding, ControlOperation.CaptureSom))
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, kernel.evaluate(context, binding, ControlOperation.Screen("snapshot", ScreenAction.Global("back"))))
        kernel.approve(context, binding, ControlOperation.CaptureSom)
        assertTrue(kernel.evaluate(context, binding, ControlOperation.CaptureSom) is CapabilityAuthorization.Allowed)
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, kernel.evaluate(context, binding, ControlOperation.Screen("snapshot", ScreenAction.Global("back"))))
    }
    @Test fun revokedConnectionAndLocalOffDecisionRejectEvenAfterApproval() {
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        var current = true; val access = access(); val binding = RemoteCapabilityBinding(access) { current }; holder.attachRemoteConnection(binding)
        val primitive = setOf("kernel.screen.read")
        val kernel = NativePhoneControlKernel(holder, holder.authorization, { primitive }, { primitive }, { CapabilityPolicy(mode = CapabilityAuthorizationMode.READ_ONLY) }, { false })
        val operation = ControlOperation.DumpTree(ScreenReadQuery())
        assertTrue(kernel.evaluate(request(access), binding, operation) is CapabilityAuthorization.Allowed)
        holder.updatePrimitive("kernel.screen.read", false)
        assertThrows(ControlRequestException::class.java) { kernel.evaluate(request(access), binding, operation) }
        current = false
        assertThrows(ControlRequestException::class.java) { kernel.evaluate(request(access), binding, operation) }
    }
    @Test fun authoritativeEmergencyStateRejectsPreviouslyApprovedAndNewControlRequests() {
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val binding = RemoteCapabilityBinding(access()) { true }; holder.attachRemoteConnection(binding)
        var stopped = false
        val primitives = setOf("kernel.screen.control")
        val kernel = NativePhoneControlKernel(holder, holder.authorization, { primitives }, { primitives }, { CapabilityPolicy() }, { stopped })
        val context = request(access()); val operation = ControlOperation.Screen("snapshot", ScreenAction.Global("back"))
        kernel.approve(context, binding, operation)
        assertTrue(kernel.evaluate(context, binding, operation) is CapabilityAuthorization.Allowed)
        stopped = true
        assertThrows(ControlRequestException::class.java) { kernel.evaluate(context, binding, operation) }
        assertThrows(ControlRequestException::class.java) { kernel.approve(context, binding, operation) }
    }
}
