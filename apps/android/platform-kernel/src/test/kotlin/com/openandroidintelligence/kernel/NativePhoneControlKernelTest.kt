package com.openandroidintelligence.kernel

import com.openandroidintelligence.control.ScreenAction
import com.openandroidintelligence.control.ScreenReadQuery
import com.openandroidintelligence.control.remote.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativePhoneControlKernelTest {
    private fun access(device: String = "controller") = ControlAccessBinding(RemoteControlPrincipal(ControlOrigin.fromCanonical("https://remote.example"),
        "account", device, "phone", "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64)), 1, "connection", 1)
    private fun request(access: ControlAccessBinding) = PhoneExecutionContext(ControlRequestScope(
        ControlTaskScope(access.principal, "task", 1, "process"), "request-native-00001"), access, "sha256:" + "c".repeat(64), {}, {})
    @Test fun 三档本机策略分别裁决屏幕读图和操控() {
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val binding = RemoteCapabilityBinding(access()) { true }; holder.attachRemoteConnection(binding)
        val primitives = setOf("kernel.screen.read", "kernel.screen.capture", "kernel.screen.control")
        var mode = CapabilityAuthorizationMode.MANUAL
        val kernel = NativePhoneControlKernel(holder, holder.authorization, { primitives }, { primitives },
            { CapabilityPolicy(mode = mode) }, { false })
        val read = ControlOperation.DumpTree(ScreenReadQuery())
        val control = ControlOperation.Screen("snapshot", ScreenAction.Global("back"))
        val context = request(binding.access)
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, kernel.evaluate(context, binding, read))
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, kernel.evaluate(context, binding, ControlOperation.CaptureSom))
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, kernel.evaluate(context, binding, control))

        for (readMode in listOf(CapabilityAuthorizationMode.READ_ONLY, CapabilityAuthorizationMode.AUTOMATIC)) {
            mode = readMode
            assertEquals(CapabilityAuthorization.Allowed(), kernel.evaluate(context, binding, read))
            assertEquals(CapabilityAuthorization.Allowed(), kernel.evaluate(context, binding, ControlOperation.CaptureSom))
            assertEquals(CapabilityAuthorization.AwaitingConfirmation, kernel.evaluate(context, binding, control))
        }
    }

    @Test fun 读取自动允许不授予独立的截图权限() {
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val binding = RemoteCapabilityBinding(access()) { true }; holder.attachRemoteConnection(binding)
        val primitives = setOf("kernel.screen.read", "kernel.screen.capture")
        var capturePolicy = CapabilityPolicy()
        val kernel = NativePhoneControlKernel(holder, holder.authorization, { primitives }, { primitives },
            { if (it == "kernel.screen.read") CapabilityPolicy(mode = CapabilityAuthorizationMode.AUTOMATIC) else capturePolicy },
            { false })
        val context = request(binding.access)
        assertEquals(CapabilityAuthorization.Allowed(), kernel.evaluate(context, binding, ControlOperation.DumpTree(ScreenReadQuery())))
        assertEquals(CapabilityAuthorization.AwaitingConfirmation, kernel.evaluate(context, binding, ControlOperation.CaptureSom))
        capturePolicy = CapabilityPolicy(enabled = false, mode = CapabilityAuthorizationMode.AUTOMATIC)
        assertEquals(CapabilityAuthorization.Denied("LOCAL_CAPABILITY_DISABLED"), kernel.evaluate(context, binding, ControlOperation.CaptureSom))
    }

    @Test fun 屏幕正文回读复核当前连接锁屏和读取许可(): Unit = runBlocking {
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val access = access()
        var connected = true
        val original = RemoteCapabilityBinding(access) { connected }
        holder.attachRemoteConnection(original)
        val reconnect = RemoteCapabilityBinding(access.copy(connectionId = "second", connectionGeneration = 2)) { true }
        val primitives = setOf("kernel.screen.read", "kernel.screen.capture", "kernel.screen.control")
        var captureEnabled = true
        var readMode = CapabilityAuthorizationMode.AUTOMATIC
        val kernel = NativePhoneControlKernel(holder, holder.authorization, { primitives }, { primitives }, {
            when (it) {
                "kernel.screen.capture" -> CapabilityPolicy(enabled = captureEnabled, mode = CapabilityAuthorizationMode.AUTOMATIC)
                "kernel.screen.read" -> CapabilityPolicy(mode = readMode)
                else -> CapabilityPolicy()
            }
        }, { false })
        var locked = false
        val oldScreen = NativePhoneScreenRegistry.current
        val oldUi = PhoneLocalControlUiRegistry.current
        val screen = object : NativePhoneScreenPort {
            override fun availability() = "SCREEN_DEVICE_LOCKED".takeIf { locked }
            override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation, revalidate: () -> Unit,
                mayRead: () -> Boolean): PhoneControlResult {
                revalidate()
                return PhoneControlResult(ControlValue.Object(emptyMap()),
                    ui = if (operation is ControlOperation.Screen && mayRead())
                        ControlValue.Object(mapOf("snapshotId" to ControlValue.Text("observed"))) else null)
            }
            override fun stop() = Unit
        }
        NativePhoneScreenRegistry.current = screen
        PhoneLocalControlUiRegistry.current = object : PhoneLocalControlUi {
            override suspend fun confirm(requestId: String, capability: String, localParameters: String?) = error("本用例独立签发操控批准")
            override suspend fun executing(requestId: String) = Unit
            override suspend fun finish(requestId: String) = Unit
            override fun emergencyStop() = Unit
            override val protectedBounds = null
        }
        try {
            val screenshot = kernel.execute(request(access), original, ControlOperation.CaptureSom) { reconnect }
            val control = ControlOperation.Screen("snapshot", ScreenAction.Global("back"))
            val action = request(access)
            kernel.approve(action, original, control)
            val observation = kernel.execute(action, original, control) { reconnect }
            assertNotNull(observation.ui)
            kernel.finish(action, original)
            connected = false
            holder.attachRemoteConnection(reconnect)
            val checkScreenshot = requireNotNull(screenshot.requireContentAccess)
            val checkObservation = requireNotNull(observation.requireContentAccess)
            checkScreenshot(reconnect.access)
            checkObservation(reconnect.access)
            locked = true
            assertEquals("SCREEN_DEVICE_LOCKED", assertThrows(ControlRequestException::class.java) { checkScreenshot(reconnect.access) }.code)
            locked = false
            captureEnabled = false
            assertThrows(ControlRequestException::class.java) { checkScreenshot(reconnect.access) }
            readMode = CapabilityAuthorizationMode.MANUAL
            assertThrows(ControlRequestException::class.java) { checkObservation(reconnect.access) }
        } finally {
            NativePhoneScreenRegistry.current = oldScreen
            PhoneLocalControlUiRegistry.current = oldUi
        }
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
