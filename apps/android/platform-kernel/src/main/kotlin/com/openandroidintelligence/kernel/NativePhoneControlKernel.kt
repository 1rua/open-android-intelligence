package com.openandroidintelligence.kernel

import com.openandroidintelligence.control.remote.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 已编译进手机的原生目录；屏幕高频操作不经过插件 ABI 或 WASM。 */
class NativePhoneControlKernel(
    private val grants: PairingGrantStateHolder,
    private val authorization: CapabilityAuthorizationController,
    private val hostEnvelope: () -> Set<String>,
    private val phoneLimits: () -> Set<String>,
    private val policy: (String) -> CapabilityPolicy,
    private val emergencyStopped: () -> Boolean,
) {
    fun primitives(operation: ControlOperation): Set<String> = when (operation) {
        is ControlOperation.DumpTree -> setOf("kernel.screen.read")
        ControlOperation.CaptureSom -> setOf("kernel.screen.read", "kernel.screen.capture")
        is ControlOperation.Screen -> setOf("kernel.screen.control")
        else -> throw ControlRequestException("CONTROL_OPERATION_UNSUPPORTED")
    }
    private fun scope(primitive: String, digest: String) = CapabilityRequestScope("native.remote.$primitive",
        if (primitive == "kernel.screen.control") DeviceOperation.CONTROL else DeviceOperation.READ, digest)
    private fun effectivePolicy(primitive: String): CapabilityPolicy = policy(primitive).let { local ->
        if (primitive != "kernel.screen.control" && local.mode == CapabilityAuthorizationMode.READ_ONLY) local
        else local.copy(mode = CapabilityAuthorizationMode.MANUAL)
    }
    fun evaluate(context: PhoneExecutionContext, binding: RemoteCapabilityBinding, operation: ControlOperation): CapabilityAuthorization {
        checkBinding(binding, operation)
        var awaiting = false
        for (primitive in primitives(operation)) when (val decision = authorization.evaluate(binding.requestKey(context.scope.requestId),
            scope(primitive, context.parameterDigest), effectivePolicy(primitive), grants.state.value?.revision ?: -1)) {
            is CapabilityAuthorization.Denied -> return decision
            CapabilityAuthorization.AwaitingConfirmation -> awaiting = true
            is CapabilityAuthorization.Allowed -> Unit
        }
        return if (awaiting) CapabilityAuthorization.AwaitingConfirmation else CapabilityAuthorization.Allowed()
    }
    fun approve(context: PhoneExecutionContext, binding: RemoteCapabilityBinding, operation: ControlOperation) {
        checkBinding(binding, operation)
        primitives(operation).forEach { primitive -> authorization.approve(binding.requestKey(context.scope.requestId),
            scope(primitive, context.parameterDigest), 0, grants.state.value?.revision ?: error("LOCAL_POLICY_UNAVAILABLE"), 5 * 60_000) }
    }
    suspend fun execute(context: PhoneExecutionContext, binding: RemoteCapabilityBinding, operation: ControlOperation): PhoneControlResult {
        fun revalidate() { if (evaluate(context, binding, operation) !is CapabilityAuthorization.Allowed) throw ControlRequestException("LOCAL_AUTHORIZATION_CHANGED") }
        currentCoroutineContext().ensureActive(); revalidate()
        val provider = NativePhoneScreenRegistry.current ?: throw ControlRequestException("SCREEN_PROVIDER_UNAVAILABLE")
        provider.availability()?.let { throw ControlRequestException(it) }
        val ui = PhoneLocalControlUiRegistry.current ?: throw ControlRequestException("LOCAL_CONFIRMATION_UI_UNAVAILABLE")
        ui.executing(context.scope.key.storageKey)
        return try {
            val result = provider.execute(context, operation, ::revalidate) {
                // 控制批准只含 CONTROL。动作后的自动观测必须另有明确的只读免确认策略。
                runCatching {
                    checkBinding(binding, ControlOperation.DumpTree(com.openandroidintelligence.control.ScreenReadQuery()))
                    policy("kernel.screen.read").let { it.enabled && it.mode == CapabilityAuthorizationMode.READ_ONLY }
                }.getOrDefault(false)
            }
            currentCoroutineContext().ensureActive(); revalidate(); result
        } finally { ui.finish(context.scope.key.storageKey) }
    }
    fun cancel(context: PhoneExecutionContext, binding: RemoteCapabilityBinding) = authorization.cancel(binding.requestKey(context.scope.requestId))
    fun finish(context: PhoneExecutionContext, binding: RemoteCapabilityBinding) = authorization.finish(binding.requestKey(context.scope.requestId))
    private fun checkBinding(binding: RemoteCapabilityBinding, operation: ControlOperation) {
        if (!binding.isCurrent() || emergencyStopped()) throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
        val grant = grants.currentKernelGrant(binding.id) ?: throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
        primitives(operation).forEach { primitive ->
            if (primitive !in hostEnvelope() || primitive !in phoneLimits() || !grant.permits(primitive)) throw ControlRequestException("CAPABILITY_DENIED")
        }
    }
}
