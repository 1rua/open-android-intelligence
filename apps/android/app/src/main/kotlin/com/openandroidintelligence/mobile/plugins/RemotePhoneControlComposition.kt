package com.openandroidintelligence.mobile.plugins

import android.content.Context
import com.openandroidintelligence.control.*
import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.encrypted.store.*
import com.openandroidintelligence.gateway.remote.facade.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/** 真实手机装配：安装策略、已验证 WASM 和 Android 提供者仍由既有内核执行。 */
class RemotePhoneControlComposition(
    private val context: Context,
    scope: CoroutineScope,
    private val pluginHost: ProductionPluginHost,
    private val grants: PairingGrantStateHolder,
    private val confirmLocally: suspend (String, String, String) -> Boolean = { requestId, capabilityId, localParameters ->
        PhoneLocalControlUiRegistry.current?.confirm(requestId, capabilityId, localParameters) ?: false
    },
) : PhoneControlExecutor, AutoCloseable {
    private data class Connection(val binding: RemoteCapabilityBinding, val registration: AutoCloseable)
    private val connections = ConcurrentHashMap<ControlAccessBinding, Connection>()
    private val activeRequests = ConcurrentHashMap<ControlRequestScope, Pair<String, Connection>>()
    private val nativeContexts = ConcurrentHashMap<ControlRequestScope, PhoneExecutionContext>()
    @Volatile private var stopped = false
    private val nativeKernel = NativePhoneControlKernel(grants, grants.authorization, { pluginHost.hostCapabilities },
        { pluginHost.phoneCapabilities }, pluginHost::nativePhonePolicy, { stopped || pluginHost.remoteEmergencyStopped() })
    private val metadataFile = File(context.noBackupFilesDir, "remote-control-metadata-v1.enc")
    private val metadataKeys = AndroidKeystoreOutboxKeyProvider("oai_remote_control_metadata_v1")
    private val ledger = ControlMetadataStore(AesGcmEncryptedBlobStore(FileEncryptedOutboxPersistence(metadataFile),
        if (metadataFile.exists()) metadataKeys.existing() ?: error("CONTROL_LEDGER_KEY_UNAVAILABLE") else metadataKeys.getOrCreate()))
    val bus = PhoneControlBus(scope, ledger, this)
    val router = PhoneVirtualHostRouter(bus)
    private val stopObserver = PhoneLocalControlUiRegistry.observeEmergencyStop {
        stopPhone()
        pluginHost.emergencyStopFromPhone()
    }
    private val kernelStopObserver = pluginHost.observeEmergencyStop { if (it) stopPhone() }

    private fun stopPhone() {
        stopped = true
        grants.revokeTemporaryApprovals()
        NativePhoneScreenRegistry.current?.stop()
        bus.emergencyStop()
    }

    /** 只能由认证网络持有者注册；失去授权、断线和代际变更通过 current 即时拒绝。 */
    fun register(access: ControlAccessBinding, current: () -> Boolean): AutoCloseable {
        val binding = RemoteCapabilityBinding(access, current)
        val registration = grants.attachRemoteConnection(binding)
        val connection = Connection(binding, registration)
        check(connections.putIfAbsent(access, connection) == null) { "REMOTE_CONNECTION_ALREADY_REGISTERED" }
        return AutoCloseable {
            if (connections.remove(access, connection)) {
                registration.close()
                activeRequests.filterValues { it.second === connection }.keys.forEach(::cancel)
                nativeContexts.values.filter { it.access == access }.forEach { nativeKernel.cancel(it, connection.binding) }
                bus.connectionClosed(access)
            }
        }
    }

    override fun capabilities(access: ControlAccessBinding): ControlValue.Object {
        connection(access)
        return value(Json.of(mapOf("inventory" to pluginHost.capabilitySnapshot().inventory,
            "inventoryComplete" to pluginHost.capabilitySnapshot().inventoryComplete,
            "nativeScreen" to mapOf("available" to (NativePhoneScreenRegistry.current != null && NativePhoneScreenRegistry.current?.availability() == null),
                "reason" to (NativePhoneScreenRegistry.current?.availability() ?: if (NativePhoneScreenRegistry.current == null) "SCREEN_PROVIDER_UNAVAILABLE" else null))))) as ControlValue.Object
    }

    override fun requireCurrentAccess(access: ControlAccessBinding) {
        if (connections[access]?.binding?.isCurrent() != true) fail("REMOTE_AUTHORIZATION_CHANGED")
    }

    override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
        val connection = connection(context.access)
        if (operation is ControlOperation.DumpTree || operation is ControlOperation.Screen || operation == ControlOperation.CaptureSom)
            return executeNative(context, connection, operation)
        val id = "remote-${UUID.randomUUID()}"
        activeRequests[context.scope] = id to connection
        try {
            val (entry, parameters) = resolve(operation)
            when (val decision = pluginHost.requestRemoteAuthorization(id, entry, parameters, connection.binding, context.parameterDigest)) {
                is CapabilityAuthorization.Denied -> fail(decision.code)
                is CapabilityAuthorization.Allowed -> Unit
                CapabilityAuthorization.AwaitingConfirmation -> {
                    context.awaitingConfirmation(true)
                    val accepted = confirmLocally(context.scope.key.storageKey, entry.id, Json.canonical(parameters))
                    if (!accepted) fail("LOCAL_USER_REJECTED")
                    connection(context.access)
                    pluginHost.approveRemoteRequest(id, entry, parameters, connection.binding, context.parameterDigest)
                    context.awaitingConfirmation(false)
                }
            }
            currentCoroutineContext().ensureActive()
            fun revalidate() {
                connection(context.access)
                if (pluginHost.requestRemoteAuthorization(id, entry, parameters, connection.binding, context.parameterDigest) !is CapabilityAuthorization.Allowed)
                    fail("LOCAL_AUTHORIZATION_CHANGED")
                if (isBackground() && grants.currentKernelGrant(connection.binding.id)?.backgroundSync != true) fail("BACKGROUND_PERMISSION_REQUIRED")
            }
            revalidate()
            if (entry.id != "org.openandroidintelligence.sms.send") context.beforeDispatch()
            val output = runInterruptible(Dispatchers.IO) { pluginHost.invokeRemote(entry, parameters, id, connection.binding,
                context.parameterDigest, isBackground(), beforeSystemEffect = context.consumeSmsSendEffect) }
            revalidate()
            val result = Json.parse(output.decodeToString())
            return phonePluginResult(entry, result)
        } finally {
            activeRequests.remove(context.scope)
            PhoneLocalControlUiRegistry.current?.finish(context.scope.key.storageKey)
            pluginHost.finishRemoteRequest(id, connection.binding)
        }
    }

    private fun resolve(operation: ControlOperation): Pair<VerifiedCapability, JsonValue> {
        val call = operation as? ControlOperation.PluginInvoke ?: fail("CONTROL_OPERATION_UNSUPPORTED")
        if (call.method != call.capabilityId.substringBefore('@').substringAfterLast('.')) fail("CONTROL_METHOD_MISMATCH")
        val parameters = controlJsonLocal(call.params)
        val entry = pluginHost.entries(call.pluginId).singleOrNull { it.key == call.capabilityId } ?: fail("CAPABILITY_UNAVAILABLE")
        if (entry.primitives.any { it.startsWith("kernel.screen.") }) fail("SCREEN_NATIVE_OPERATION_REQUIRED")
        if (!pluginHost.isEnabled(call.pluginId)) fail("PLUGIN_DISABLED")
        if (!CapabilitySchemaValidator.accepts(entry.schema, parameters)) fail("SCHEMA_INVALID")
        return entry to parameters
    }

    override fun cancel(scope: ControlRequestScope) {
        nativeContexts[scope]?.let { context -> connections[context.access]?.let { nativeKernel.cancel(context, it.binding) } }
        activeRequests[scope]?.let { (id, connection) ->
            pluginHost.cancelRemoteRequest(id, connection.binding)
        }
    }
    private suspend fun executeNative(context: PhoneExecutionContext, connection: Connection, operation: ControlOperation): PhoneControlResult {
        nativeContexts[context.scope] = context
        try {
            when (val decision = nativeKernel.evaluate(context, connection.binding, operation)) {
                is CapabilityAuthorization.Denied -> fail(decision.code)
                is CapabilityAuthorization.Allowed -> Unit
                CapabilityAuthorization.AwaitingConfirmation -> {
                    context.awaitingConfirmation(true)
                    if (!confirmLocally(context.scope.key.storageKey, operation.wireName, "")) fail("LOCAL_USER_REJECTED")
                    nativeKernel.approve(context, connection.binding, operation)
                    context.awaitingConfirmation(false)
                }
            }
            return nativeKernel.execute(context, connection.binding, operation)
        } finally {
            nativeContexts.remove(context.scope)
            nativeKernel.finish(context, connection.binding)
            PhoneLocalControlUiRegistry.current?.finish(context.scope.key.storageKey)
        }
    }
    private fun connection(access: ControlAccessBinding) = connections[access]?.takeIf { !stopped && it.binding.isCurrent() } ?: fail("REMOTE_AUTHORIZATION_CHANGED")
    private fun isBackground(): Boolean = context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != false ||
        !androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    override fun close() { stopObserver.close(); kernelStopObserver.close(); connections.values.forEach { it.registration.close() }; connections.clear() }
    private fun fail(code: String): Nothing = throw ControlRequestException(code)
}

/** 仅已验证短信发送能力的未知结果可升级为控制未知终态；读取和其他插件保持原语义。 */
internal fun phonePluginResult(entry: VerifiedCapability, result: JsonValue): PhoneControlResult {
    val smsUnknown = entry.identity.pluginId == SMS_PLUGIN_ID &&
        entry.id == "org.openandroidintelligence.sms.send" && entry.primitives == setOf("kernel.sms.send") &&
        JsonFields.string(JsonFields.obj(result), "status") == "outcome_unknown"
    return PhoneControlResult(
        (value(result) as? ControlValue.Object) ?: ControlValue.Object(mapOf("value" to value(result))),
        completion = if (smsUnknown) PhoneControlCompletion.SMS_SEND_OUTCOME_UNKNOWN else PhoneControlCompletion.SUCCEEDED,
    )
}

private fun value(value: JsonValue): ControlValue = when (value) {
    JsonValue.JNull -> ControlValue.Null
    is JsonValue.JBool -> ControlValue.Boolean(value.value)
    is JsonValue.JNumber -> ControlValue.Number(value.raw)
    is JsonValue.JString -> ControlValue.Text(value.value)
    is JsonValue.JArray -> ControlValue.Array(value.items.map(::value))
    is JsonValue.JObject -> ControlValue.Object(value.fields.associate { it.first to value(it.second) })
}
private fun controlJsonLocal(value: ControlValue): JsonValue = when (value) {
    ControlValue.Null -> JsonValue.JNull
    is ControlValue.Boolean -> JsonValue.JBool(value.value)
    is ControlValue.Number -> JsonValue.JNumber(value.value)
    is ControlValue.Text -> JsonValue.JString(value.value)
    is ControlValue.Array -> JsonValue.JArray(value.values.map(::controlJsonLocal))
    is ControlValue.Object -> JsonValue.JObject(value.values.map { it.key to controlJsonLocal(it.value) })
}
