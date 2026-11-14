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
    context: Context,
    scope: CoroutineScope,
    private val pluginHost: ProductionPluginHost,
    private val grants: PairingGrantStateHolder,
    private val confirmLocally: suspend (String, String, String) -> Boolean,
) : PhoneControlExecutor, AutoCloseable {
    private data class Connection(val binding: RemoteCapabilityBinding, val registration: AutoCloseable,
        val screen: ScreenAuthorizationBinding?)
    private data class Snapshot(val task: ControlTaskScope, val revision: String)
    private val connections = ConcurrentHashMap<ControlAccessBinding, Connection>()
    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val activeRequests = ConcurrentHashMap<ControlRequestScope, Pair<String, Connection>>()
    private val ledger = ControlMetadataStore(AesGcmEncryptedBlobStore(
        FileEncryptedOutboxPersistence(File(context.noBackupFilesDir, "remote-control-metadata-v1.enc")),
        AndroidKeystoreOutboxKeyProvider("oai_remote_control_metadata_v1").getOrCreate()))
    val bus = PhoneControlBus(scope, ledger, this)
    val router = PhoneVirtualHostRouter(bus)

    /** 只能由认证网络持有者注册；失去授权、断线和代际变更通过 current 即时拒绝。 */
    fun register(access: ControlAccessBinding, current: () -> Boolean): AutoCloseable {
        val binding = RemoteCapabilityBinding(access, current)
        val registration = grants.attachRemoteConnection(binding)
        val screen = ScreenHostRegistry.current?.registerConnection(access.principal.accountId, binding.id,
            access.authorizationEpoch, { if (binding.isCurrent()) grants.currentKernelGrant(binding.id)?.revision ?: -1 else -1 })
        val connection = Connection(binding, registration, screen)
        check(connections.putIfAbsent(access, connection) == null) { "REMOTE_CONNECTION_ALREADY_REGISTERED" }
        return AutoCloseable {
            if (connections.remove(access, connection)) {
                registration.close(); screen?.let { ScreenHostRegistry.current?.disconnectConnection(it) }
                activeRequests.filterValues { it.second === connection }.keys.forEach(::cancel)
                snapshots.entries.removeAll { it.value.task.principal == access.principal }
            }
        }
    }

    override fun capabilities(access: ControlAccessBinding): ControlValue.Object {
        connection(access)
        return value(Json.of(mapOf("inventory" to pluginHost.capabilitySnapshot().inventory,
            "inventoryComplete" to pluginHost.capabilitySnapshot().inventoryComplete,
            "captureSom" to mapOf("available" to false, "reason" to "REMOTE_SOM_NOT_READY")))) as ControlValue.Object
    }

    override suspend fun execute(context: PhoneExecutionContext, operation: ControlOperation): PhoneControlResult {
        val connection = connection(context.access)
        val id = "remote-${UUID.randomUUID()}"
        activeRequests[context.scope] = id to connection
        try {
            val (entry, parameters) = resolve(context, operation)
            if (operation is ControlOperation.Screen) {
                val snapshot = snapshots[operation.snapshotId] ?: fail("SNAPSHOT_STALE")
                if (snapshot.task != context.scope.task) fail("SNAPSHOT_STALE")
            }
            when (val decision = pluginHost.requestRemoteAuthorization(id, entry, parameters, connection.binding)) {
                is CapabilityAuthorization.Denied -> fail(decision.code)
                is CapabilityAuthorization.Allowed -> Unit
                CapabilityAuthorization.AwaitingConfirmation -> {
                    context.awaitingConfirmation(true)
                    val accepted = confirmLocally(context.scope.requestId, entry.id, Json.canonical(parameters))
                    if (!accepted) fail("LOCAL_USER_REJECTED")
                    connection(context.access)
                    pluginHost.approveRemoteRequest(id, entry, parameters, connection.binding)
                    context.awaitingConfirmation(false)
                }
            }
            currentCoroutineContext().ensureActive()
            fun revalidate() {
                connection(context.access)
                if (pluginHost.requestRemoteAuthorization(id, entry, parameters, connection.binding) !is CapabilityAuthorization.Allowed)
                    fail("LOCAL_AUTHORIZATION_CHANGED")
            }
            val screenHost = ScreenHostRegistry.current
            val permissions = entry.primitives.mapNotNull { when (it) {
                "kernel.screen.read" -> ScreenPermission.READ
                "kernel.screen.capture" -> ScreenPermission.CAPTURE
                "kernel.screen.control" -> ScreenPermission.CONTROL
                else -> null
            } }.toSet()
            if (permissions.isNotEmpty()) {
                screenHost?.installRequestAuthorization(id, permissions, System.currentTimeMillis() + 5 * 60_000,
                    Json.sha256(parameters), connection.screen ?: fail("SCREEN_CONNECTION_REQUIRED"), ::revalidate)
                    ?: fail("SCREEN_PROVIDER_UNAVAILABLE")
            }
            revalidate()
            context.beforeDispatch()
            val output = runInterruptible(Dispatchers.IO) { pluginHost.invokeRemote(entry, parameters, id, connection.binding) }
            revalidate()
            var result = Json.parse(output.decodeToString())
            if (operation is ControlOperation.DumpTree) {
                val tree = result as? JsonValue.JObject ?: fail("SCREEN_RESPONSE_INVALID")
                val revision = (tree.fields.firstOrNull { it.first == "revision" }?.second as? JsonValue.JString)?.value ?: fail("SCREEN_RESPONSE_INVALID")
                val snapshotId = UUID.randomUUID().toString()
                snapshots[snapshotId] = Snapshot(context.scope.task, revision)
                result = JsonValue.JObject(tree.fields + ("snapshotId" to JsonValue.JString(snapshotId)))
            }
            return PhoneControlResult((value(result) as? ControlValue.Object) ?: ControlValue.Object(mapOf("value" to value(result))))
        } finally {
            activeRequests.remove(context.scope)
            connection.screen?.let { ScreenHostRegistry.current?.finishRequest(id, it) }
            pluginHost.finishRemoteRequest(id, connection.binding)
        }
    }

    private fun resolve(context: PhoneExecutionContext, operation: ControlOperation): Pair<VerifiedCapability, JsonValue> {
        val (plugin, capability, parameters) = when (operation) {
            is ControlOperation.PluginInvoke -> {
                val params = controlJsonLocal(operation.params)
                Triple(operation.pluginId, operation.capabilityId, params)
            }
            is ControlOperation.DumpTree -> Triple("org.openandroidintelligence.screen-reader", "org.openandroidintelligence.screen-reader.read@1.0.0",
                Json.of(mapOf("operation" to operation.query.operation, "maxNodes" to operation.query.maxNodes, "maxDepth" to operation.query.maxDepth,
                    "maxBytes" to operation.query.maxBytes) + listOfNotNull(operation.query.previousHash?.let { "previousHash" to it }).toMap() +
                    mapOf("query" to listOfNotNull(operation.query.text?.let { "text" to it }, operation.query.resourceId?.let { "resourceId" to it },
                        operation.query.description?.let { "description" to it }).toMap())))
            is ControlOperation.Screen -> {
                val snapshot = snapshots[operation.snapshotId]?.takeIf { it.task == context.scope.task } ?: fail("SNAPSHOT_STALE")
                val args: Map<String, Any?> = when (val action = operation.action) {
                    is ScreenAction.Click -> mapOf("action" to "click", "nodeId" to action.nodeId, "x" to action.x, "y" to action.y)
                    is ScreenAction.LongPress -> mapOf("action" to "longPress", "nodeId" to action.nodeId, "x" to action.x, "y" to action.y, "durationMs" to action.durationMs)
                    is ScreenAction.SetText -> mapOf("action" to "setText", "nodeId" to action.nodeId, "text" to action.text)
                    is ScreenAction.Scroll -> mapOf("action" to "scroll", "nodeId" to action.nodeId, "direction" to action.direction)
                    is ScreenAction.Swipe -> mapOf("action" to "swipe", "x" to action.x, "y" to action.y, "endX" to action.endX, "endY" to action.endY, "durationMs" to action.durationMs)
                    is ScreenAction.Drag -> mapOf("action" to "drag", "x" to action.x, "y" to action.y, "endX" to action.endX, "endY" to action.endY, "durationMs" to action.durationMs)
                    is ScreenAction.Global -> mapOf("action" to "global", "globalAction" to action.globalAction)
                    is ScreenAction.LaunchApp -> mapOf("action" to "launchApp", "packageName" to action.packageName)
                }
                Triple("org.openandroidintelligence.screen-control", "org.openandroidintelligence.screen-control.control@1.0.0",
                    Json.of(args.filterValues { it != null } + ("expectedRevision" to snapshot.revision)))
            }
            ControlOperation.CaptureSom -> fail("REMOTE_SOM_NOT_READY")
            else -> fail("CONTROL_OPERATION_UNSUPPORTED")
        }
        val entry = pluginHost.entries(plugin).singleOrNull { it.key == capability } ?: fail("CAPABILITY_UNAVAILABLE")
        if (!pluginHost.isEnabled(plugin)) fail("PLUGIN_DISABLED")
        if (!CapabilitySchemaValidator.accepts(entry.schema, parameters)) fail("SCHEMA_INVALID")
        return entry to parameters
    }

    override fun cancel(scope: ControlRequestScope) {
        activeRequests[scope]?.let { (id, connection) ->
            pluginHost.cancelRemoteRequest(id, connection.binding)
            connection.screen?.let { ScreenHostRegistry.current?.cancelRequest(id, it) }
        }
    }
    private fun connection(access: ControlAccessBinding) = connections[access]?.takeIf { it.binding.isCurrent() } ?: fail("REMOTE_AUTHORIZATION_CHANGED")
    override fun close() { connections.values.forEach { it.registration.close(); it.screen?.let { screen -> ScreenHostRegistry.current?.disconnectConnection(screen) } }; connections.clear(); snapshots.clear() }
    private fun fail(code: String): Nothing = throw ControlRequestException(code)
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
