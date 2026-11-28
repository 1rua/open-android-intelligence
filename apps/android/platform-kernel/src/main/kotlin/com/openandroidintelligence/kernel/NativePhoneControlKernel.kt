package com.openandroidintelligence.kernel

import com.openandroidintelligence.control.ScreenReadQuery
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
        if (primitive == "kernel.screen.control") local.copy(mode = CapabilityAuthorizationMode.MANUAL) else local
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
    suspend fun execute(context: PhoneExecutionContext, binding: RemoteCapabilityBinding, operation: ControlOperation,
        resolveDeliveryBinding: (ControlAccessBinding) -> RemoteCapabilityBinding): PhoneControlResult {
        fun revalidate() { if (evaluate(context, binding, operation) !is CapabilityAuthorization.Allowed) throw ControlRequestException("LOCAL_AUTHORIZATION_CHANGED") }
        currentCoroutineContext().ensureActive(); revalidate()
        val revision = checkBinding(binding, operation).revision
        val provider = NativePhoneScreenRegistry.current ?: throw ControlRequestException("SCREEN_PROVIDER_UNAVAILABLE")
        provider.availability()?.let { throw ControlRequestException(it) }
        val ui = PhoneLocalControlUiRegistry.current ?: throw ControlRequestException("LOCAL_CONFIRMATION_UI_UNAVAILABLE")
        ui.executing(context.scope.key.storageKey)
        return try {
            val result = provider.execute(context, operation, ::revalidate) { mayObserve(binding) }
            currentCoroutineContext().ensureActive(); revalidate()
            val observedOperation = when (operation) {
                is ControlOperation.DumpTree, ControlOperation.CaptureSom -> operation
                is ControlOperation.Screen -> readTree.takeIf { result.ui?.values?.get("snapshotId") is ControlValue.Text }
                else -> null
            }
            if (observedOperation == null) result else result.copy(requireContentAccess = { access ->
                if (access.principal != binding.access.principal || access.authorizationEpoch != binding.access.authorizationEpoch ||
                    NativePhoneScreenRegistry.current !== provider) throw ControlRequestException("LOCAL_AUTHORIZATION_CHANGED")
                val current = resolveDeliveryBinding(access)
                val grant = checkBinding(current, observedOperation)
                if (grant.revision != revision || primitives(observedOperation).any { !policy(it).enabled } ||
                    operation is ControlOperation.Screen && !mayObserve(current)) throw ControlRequestException("LOCAL_AUTHORIZATION_CHANGED")
                provider.availability()?.let { throw ControlRequestException(it) }
            })
        } finally { ui.finish(context.scope.key.storageKey) }
    }

    // 操控批准只覆盖操作本身，随后读树需要独立免确认许可。
    private fun mayObserve(binding: RemoteCapabilityBinding): Boolean = runCatching {
        checkBinding(binding, readTree)
        policy("kernel.screen.read").let { it.enabled && it.mode != CapabilityAuthorizationMode.MANUAL }
    }.getOrDefault(false)

    fun cancel(context: PhoneExecutionContext, binding: RemoteCapabilityBinding) = authorization.cancel(binding.requestKey(context.scope.requestId))
    fun finish(context: PhoneExecutionContext, binding: RemoteCapabilityBinding) = authorization.finish(binding.requestKey(context.scope.requestId))
    private fun checkBinding(binding: RemoteCapabilityBinding, operation: ControlOperation): PairingGrant {
        if (!binding.isCurrent() || emergencyStopped()) throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
        val grant = grants.currentKernelGrant(binding.id) ?: throw ControlRequestException("REMOTE_AUTHORIZATION_CHANGED")
        primitives(operation).forEach { primitive ->
            if (primitive !in hostEnvelope() || primitive !in phoneLimits() || !grant.permits(primitive)) throw ControlRequestException("CAPABILITY_DENIED")
        }
        return grant
    }

    private val readTree = ControlOperation.DumpTree(ScreenReadQuery())
}
