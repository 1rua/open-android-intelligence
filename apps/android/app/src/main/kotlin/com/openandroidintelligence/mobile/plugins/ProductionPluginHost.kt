package com.openandroidintelligence.mobile.plugins

import android.content.Context
import com.openandroidintelligence.plugin.ui.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.kernel.*
import com.openandroidintelligence.plugin.pkg.*
import com.openandroidintelligence.plugin.wasm.ChicoryPluginRuntime
import com.openandroidintelligence.plugin.wasm.InvocationBudget
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class VerifiedCapability(val identity: PluginIdentity, val id: String, val version: String, val schema: JsonValue,
    val risk: String, val primitives: Set<String>) {
    val key get() = "$id@$version"
    fun binding(): Map<String,Any?> = mapOf("pluginId" to identity.pluginId,"authorKeyId" to "sha256:${identity.authorKeyFingerprint}",
        "capabilityId" to id,"capabilityVersion" to version,"schemaSha256" to Json.sha256(schema),"schema" to schema,"risk" to risk)
}

/** Installed code is reverified once before registration; invocations use immutable verified bytes. */
class ProductionPluginHost(private val context: Context, private val grants: PairingGrantStateHolder,
    private val trustMode: DeveloperTrustMode, val nativeLoader: NativePluginLoader, private val installationId: String) {
    val installStore = PluginInstallStore(context)
    val runtimes = ConcurrentHashMap<String,PluginRuntime>()
    val hostCapabilities: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    val phoneCapabilities: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private val defaults = ConcurrentHashMap<String,PluginIdentity>()
    val selector = CapabilityProviderSelector(defaults)
    val revision = MutableStateFlow(0L)
    private val modules = ConcurrentHashMap<PluginIdentity,ByteArray>()
    private val capabilities = ConcurrentHashMap<String,List<VerifiedCapability>>()
    private val uiDeclarations = ConcurrentHashMap<String,List<com.openandroidintelligence.plugin.ui.UiContribution>>()
    private val cardIds = ConcurrentHashMap<String,Set<String>>()
    private val stores = ConcurrentHashMap<PluginIdentity,PluginPrivateStore>()
    private val proxies = ConcurrentHashMap<String,MediatedNetworkProxy>()
    private val preferences = context.getSharedPreferences("verified-plugin-enablement-v1",Context.MODE_PRIVATE)
    private lateinit var kernel: PluginKernel
    private val nativeInFlight = ConcurrentHashMap.newKeySet<String>()
    private val crashPreferences = context.getSharedPreferences("native-plugin-recovery-v1",Context.MODE_PRIVATE)
    private val serviceIds = setOf("kernel.store.read","kernel.store.write","kernel.store.delete","kernel.store.keys",
        "kernel.network.request","kernel.scheduler.set","kernel.scheduler.cancel","kernel.scheduler.read","kernel.background.run")

    fun attach(kernel: PluginKernel) {
        this.kernel = kernel
        hostCapabilities.addAll(KernelPrimitiveRegistry.ids() + serviceIds); phoneCapabilities.addAll(hostCapabilities)
        if (!com.openandroidintelligence.mobile.BuildConfig.ALLOW_RUNTIME_PLUGINS) return
        // An interrupted native frame/loading operation survives process death.
        crashPreferences.all.filter { it.key.startsWith("loading:") && it.value == true }.keys.forEach {
            quarantineNative(it.removePrefix("loading:"))
            crashPreferences.edit().remove(it).commit()
        }
        runtimes[PluginKernel.RUNTIME_PROTECTED_WASM] = ChicoryPluginRuntime(InvocationBudget(60_000,64L*1024*1024,65_536),
            moduleSource = { identity -> modules[identity]?.copyOf() ?: error("VERIFIED_MODULE_UNAVAILABLE") },
            mediatedCall = { identity, primitive, input -> kernel.call(identity,primitive,input) })
        runtimes[PluginKernel.RUNTIME_DEVELOPER_NATIVE] = object : PluginRuntime {
            override fun invoke(identity: PluginIdentity,budget: ResourceBudget,input: ByteArray): ByteArray = nativeBoundary(identity.pluginId) {
                val result = nativeLoader.plugin(identity.pluginId)?.invoke(input) ?: error("NATIVE_PLUGIN_NOT_LOADED")
                check(result.size <= budget.maxOutputBytes) { "BUDGET_EXCEEDED:OUTPUT" }; result
            }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, cause ->
            (nativeInFlight + nativeLoader.loaded()).forEach(::quarantineNative)
            previous?.uncaughtException(thread,cause)
        }
        reload()
        trustMode.onChange { enabled ->
            if (!enabled) { capabilities.keys.filter { kernel.registrationFor(it)?.runtimeType == "developer-native" }.forEach { kernel.disable(it) }; revision.value++ }
        }
    }

    @Synchronized fun reload() {
        capabilities.keys.toList().forEach { kernel.unregister(it) }
        nativeLoader.unloadAll(); modules.clear(); capabilities.clear(); defaults.clear(); stores.clear(); proxies.clear(); uiDeclarations.clear();cardIds.clear()
        for (view in installStore.listInstalled()) {
            val id = view.pluginId ?: continue
            runCatching {
                val verified = installStore.openVerified(id)
                try {
                    val manifest = JsonFields.obj(Json.parse(File(verified.stagedDirectory,"manifest.json").readText())) ?: error("MANIFEST_INVALID")
                    val declarations = JsonFields.obj(JsonFields.field(manifest,"capabilities")) ?: error("MANIFEST_INVALID")
                    val primitives = verified.capabilities.kernelPrimitives
                    val entries = (JsonFields.field(declarations,"provides") as? JsonValue.JArray)?.items.orEmpty().map { entry ->
                        val cap = entry as? JsonValue.JObject ?: error("MANIFEST_INVALID")
                        val capabilityId = JsonFields.string(cap,"id") ?: error("MANIFEST_INVALID")
                        val schemaPath = JsonFields.string(cap,"schema") ?: error("MANIFEST_INVALID")
                        VerifiedCapability(verified.identity,capabilityId,JsonFields.string(cap,"version") ?: error("MANIFEST_INVALID"),
                            Json.parse(File(verified.stagedDirectory,schemaPath).readText()),
                            PluginCapabilityRisk.classify(verified.runtime.type,primitives),primitives)
                    }
                    val declared = entries.map { it.key }.toSet() + primitives
                    val surface = verified.security.surface
                    kernel.register(PluginRegistration(verified.identity,verified.runtime.type,declared,
                        ResourceBudget(surface.maxInvocationMillis,surface.maxMemoryBytes,65_536,surface.maxConcurrentInvocations,surface.maxDailyNetworkBytes),
                        identityScopedGrants = true))
                    capabilities[id] = entries
                    val ui = JsonFields.obj(JsonFields.field(manifest,"ui"))
                    uiDeclarations[id] = listOf("settings","cards").flatMap { slot ->
                        (JsonFields.field(ui,slot) as? JsonValue.JArray)?.items.orEmpty().mapNotNull { (it as? JsonValue.JString)?.value }
                    }.mapNotNull { path ->
                        val file = File(verified.stagedDirectory,path)
                        if (!file.canonicalPath.startsWith(verified.stagedDirectory.canonicalPath + File.separator) || !file.isFile || file.length() > 1024*1024) null
                        else runCatching { com.openandroidintelligence.plugin.ui.DeclarativeUiSchema.parse(file.readText()) }.getOrNull()
                    }
                    cardIds[id]=(JsonFields.field(ui,"cards") as? JsonValue.JArray)?.items.orEmpty().mapNotNull { path ->
                        (path as? JsonValue.JString)?.value?.let { name -> runCatching { com.openandroidintelligence.plugin.ui.DeclarativeUiSchema.parse(File(verified.stagedDirectory,name).readText()).id }.getOrNull() }
                    }.toSet()
                    hostCapabilities.addAll(entries.map { it.key }); phoneCapabilities.addAll(entries.map { it.key })
                    if (verified.runtime.type == "protected-wasm") {
                        check(verified.runtime.abiVersion == "1.0" && verified.runtime.entrypoint == "open_android_intelligence_plugin_main") { "UNSUPPORTED_PLUGIN_ABI" }
                        modules[verified.identity] = File(verified.stagedDirectory,verified.runtime.payload ?: error("PAYLOAD_MISSING")).readBytes()
                    }
                    for (entry in entries) defaults.putIfAbsent(entry.key,verified.identity)
                    if (preferences.getString(id,null) == verified.identity.authorKeyFingerprint && !isQuarantined(id)) enableVerified(verified)
                } finally { verified.stagedDirectory.deleteRecursively() }
            }.onFailure { kernel.unregister(id); capabilities.remove(id); preferences.edit().remove(id).commit() }
        }
        selector.refreshOverrides(capabilities.values.flatten().groupBy { it.key }.mapValues { (_, entries) -> entries.map { it.identity } })
        revision.value++
    }

    fun contributions(pluginId: String): List<UiContribution> {
        val identity = kernel.registrationFor(pluginId)?.identity ?: return emptyList()
        val binding = grants.currentBinding() ?: return uiDeclarations[pluginId].orEmpty()
        val store = stores.getOrPut(identity) {
            val pkg=installStore.openVerified(pluginId)
            try { PluginPrivateStore(installationId,EncryptedPluginStateBackend(context),pkg.security.surface.maxStorageBytes) }
            finally { pkg.stagedDirectory.deleteRecursively() }
        }
        val handle=store.open(identity,binding.accountId,binding.pairingId)
        fun component(node: UiComponent): UiComponent {
            if (node is UiComponent.Section) return node.copy(children=node.children.map(::component))
            val value=store.read(handle,binding.accountId,"ui:${node.id}")?.let { Json.parse(it.decodeToString()) }
            return when(node) {
                is UiComponent.Toggle -> (value as? JsonValue.JBool)?.let { node.copy(value=it.value) } ?: node
                is UiComponent.Select -> (value as? JsonValue.JString)?.value?.takeIf { value -> node.options.any { it.id == value } }?.let { node.copy(value=it) } ?: node
                else -> node
            }
        }
        return uiDeclarations[pluginId].orEmpty().map { it.copy(root=component(it.root)) }
    }
    fun selectNativeUi(pluginId: String?) {
        if (pluginId != null) {
            check(trustMode.isEnabled() && isEnabled(pluginId) && !isQuarantined(pluginId)) { "NATIVE_UI_NOT_ALLOWED" }
            check((nativeLoader.plugin(pluginId) as? NativeUiProvider)?.uiApiVersion == 1) { "NATIVE_UI_API_UNSUPPORTED" }
        }
        check(crashPreferences.edit().apply { if (pluginId == null) remove("ui-provider") else putString("ui-provider",pluginId) }.commit())
        revision.value++
    }
    fun nativeUiProvider(): Pair<String,NativeUiProvider>? {
        if (!trustMode.isEnabled()) return null
        val id=crashPreferences.getString("ui-provider",null) ?: return null
        if (!isEnabled(id) || isQuarantined(id)) return null
        return (nativeLoader.plugin(id) as? NativeUiProvider)?.takeIf { it.uiApiVersion == 1 }?.let { id to it }
    }
    fun hasNativeUi(pluginId: String) = nativeLoader.plugin(pluginId) is NativeUiProvider
    fun beginNativeFrame(pluginId: String) { check(trustMode.isEnabled() && !isQuarantined(pluginId)); nativeInFlight.add(pluginId); check(crashPreferences.edit().putBoolean("loading:$pluginId",true).commit()) }
    fun completeNativeFrame(pluginId: String) { nativeInFlight.remove(pluginId); check(crashPreferences.edit().remove("loading:$pluginId").commit()) }

    fun entries(pluginId: String): List<VerifiedCapability> = capabilities[pluginId].orEmpty()
    fun minimumBackgroundInterval(pluginId:String):Long {
        val verified=installStore.openVerified(pluginId)
        try { val manifest=JsonFields.obj(Json.parse(File(verified.stagedDirectory,"manifest.json").readText()))
            val security=JsonFields.obj(JsonFields.field(manifest,"security"));val background=JsonFields.obj(JsonFields.field(security,"background"))
            check(JsonFields.bool(background,"requested")==true) { "BACKGROUND_NOT_DECLARED" }
            return (JsonFields.long(background,"minimumIntervalSeconds") ?: error("BACKGROUND_INTERVAL_REQUIRED")).coerceAtLeast(1)
        }finally{verified.stagedDirectory.deleteRecursively()}
    }
    fun conversationCards():List<Pair<String,UiContribution>> = capabilities.keys.filter(::isEnabled).sorted().flatMap { id ->
        contributions(id).filter { it.id in cardIds[id].orEmpty() }.map { id to it }
    }
    fun isEnabled(pluginId: String) = kernel.registrationFor(pluginId)?.state?.isExecutable() == true
    fun isQuarantined(pluginId: String) = crashPreferences.getBoolean("quarantined:$pluginId",false) || crashPreferences.getBoolean("loading:$pluginId",false)
    fun nativeCrashCount(pluginId:String)=crashPreferences.getInt("crashes:$pluginId",0)
    @Synchronized private fun quarantineNative(pluginId:String) {
        check(crashPreferences.edit().apply {
            if (!crashPreferences.getBoolean("quarantined:$pluginId",false)) putInt("crashes:$pluginId",nativeCrashCount(pluginId)+1)
            putBoolean("quarantined:$pluginId",true)
        }.commit())
    }
    fun recoverNative(pluginId: String) { preferences.edit().remove(pluginId).commit(); crashPreferences.edit().remove("quarantined:$pluginId").remove("loading:$pluginId").commit(); reload() }
    fun enable(pluginId: String, enabled: Boolean) {
        if (!enabled) { preferences.edit().remove(pluginId).commit(); kernel.disable(pluginId); nativeLoader.unload(pluginId) }
        else {
            check(!isQuarantined(pluginId)) { "NATIVE_PLUGIN_SAFE_MODE" }
            val verified = installStore.openVerified(pluginId)
            try { enableVerified(verified); check(preferences.edit().putString(pluginId,verified.identity.authorKeyFingerprint).commit()) }
            finally { verified.stagedDirectory.deleteRecursively() }
        }
        revision.value++
    }
    private fun enableVerified(verified: VerifiedPluginPackage) {
        if (verified.runtime.type == "developer-native") nativeBoundary(verified.identity.pluginId) {
            check(trustMode.isEnabled()) { "TRUST_MODE_DISABLED" }
            val directory = File(context.codeCacheDir,"native-plugins/${digestName(verified.identity.toString())}").apply { mkdirs() }
            val payload = File(directory,"plugin.dex")
            if (payload.exists()) check(payload.delete())
            payload.outputStream().use { output -> check(payload.setReadOnly()); File(verified.stagedDirectory,verified.runtime.payload ?: error("PAYLOAD_MISSING")).inputStream().use { it.copyTo(output) } }
            val libDirectory=File(directory,"lib").apply { mkdirs() }
            libDirectory.listFiles().orEmpty().forEach { check(it.delete()) }
            val supportedAbi=android.os.Build.SUPPORTED_ABIS.firstOrNull { File(verified.stagedDirectory,"payload/lib/$it").isDirectory }
            supportedAbi?.let { abi -> File(verified.stagedDirectory,"payload/lib/$abi").listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("lib[A-Za-z0-9_.-]+\\.so")) }.forEach { source ->
                val target=File(libDirectory,source.name)
                target.outputStream().use { output -> check(target.setReadOnly()); source.inputStream().use { it.copyTo(output) } }
            } }
            val entry = verified.runtime.entrypointClass ?: error("ENTRYPOINT_MISSING")
            nativeLoader.load(NativePluginPackage(verified.identity.pluginId,verified.identity.authorKeyFingerprint,entry,emptySet()),true) {
                val loader = dalvik.system.DexClassLoader(payload.path,directory.path,libDirectory.path,context.classLoader)
                val plugin = loader.loadClass(entry).getDeclaredConstructor().newInstance() as? NativePlugin ?: error("NATIVE_PLUGIN_API_MISMATCH")
                check(plugin.pluginId == verified.identity.pluginId) { "NATIVE_PLUGIN_ID_MISMATCH" }; plugin
            }
        }
        kernel.enable(verified.identity.pluginId)
    }
    private fun <T> nativeBoundary(pluginId: String, action: () -> T): T {
        nativeInFlight.add(pluginId); check(crashPreferences.edit().putBoolean("loading:$pluginId",true).commit())
        try { return action() } catch (cause: Throwable) {
            quarantineNative(pluginId); throw cause
        } finally { nativeInFlight.remove(pluginId); crashPreferences.edit().remove("loading:$pluginId").commit() }
    }
    fun grant(pluginId: String, enabled: Boolean) {
        val registration = kernel.registrationFor(pluginId) ?: error("PLUGIN_NOT_REGISTERED")
        val ids = registration.declaredPrimitives
        grants.updatePrimitives(ids + ids.map { identityGrantKey(registration.identity,it) },enabled)
        if (enabled) entries(pluginId).forEach { selector.setOverride(it.key,grants.state.value?.pairingId ?: error("PAIRING_REQUIRED"),it.identity) }
        revision.value++
    }
    fun authorizedBindings(): List<Map<String,Any?>> {
        val state = grants.state.value ?: return emptyList()
        return capabilities.values.flatten().filter { entry ->
            isEnabled(entry.identity.pluginId) && entry.key in state.granted &&
                (entry.primitives + entry.key).all { identityGrantKey(entry.identity,it) in state.granted && it in state.granted } &&
                runCatching { selector.select(entry.key,state.pairingId).identity == entry.identity }.getOrDefault(false)
        }.sortedBy { it.key }.map { it.binding() }
    }
    fun invoke(identity: PluginIdentity, accountId: String, capability: String, parameters: JsonValue, correlationId: String, background: Boolean = false): ByteArray {
        val entry = entries(identity.pluginId).firstOrNull { it.identity == identity && it.key == capability } ?: throw CapabilityDenied(capability)
        check(CapabilitySchemaValidator.accepts(entry.schema,parameters)) { "SCHEMA_INVALID" }
        val pairingId = grants.state.value?.pairingId ?: error("PAIRING_REQUIRED")
        return kernel.invoke(identity,accountId,pairingId,capability,Json.canonical(parameters).toByteArray(),
            SessionConstraints(entry.primitives + entry.key,background,correlationId)).output
    }
    fun scheduledTarget(call: KernelCallContext, args: JsonValue.JObject): VerifiedCapability {
        val id = JsonFields.string(args,"capabilityId") ?: error("SCHEDULE_TARGET_REQUIRED")
        val version = JsonFields.string(args,"capabilityVersion") ?: error("SCHEDULE_TARGET_REQUIRED")
        val entry = entries(call.identity.pluginId).firstOrNull { it.identity == call.identity && it.id == id && it.version == version }
            ?: throw CapabilityDenied("$id@$version")
        check(authorizedBindings().any { it["capabilityId"] == id && it["capabilityVersion"] == version &&
            it["pluginId"] == call.identity.pluginId }) { "SCHEDULE_TARGET_DENIED" }
        check(CapabilitySchemaValidator.accepts(entry.schema,JsonFields.field(args,"query") ?: error("SCHEMA_INVALID"))) { "SCHEMA_INVALID" }
        return entry
    }
    fun mediate(call: KernelCallContext, primitive: String, input: ByteArray): ByteArray {
        KernelPrimitiveRegistry.provider(primitive)?.let { provider -> return runBlocking {
            withTimeout(call.budget.maxInvocationMillis) { provider.invoke(LocalGrantContext(call.identity.pluginId,call.accountId,call.pairingId,call.session.correlationId),input) }
        } }
        val args = JsonFields.obj(Json.parse(input.decodeToString())) ?: error("SCHEMA_INVALID")
        val store = stores.getOrPut(call.identity) {
            val verified = installStore.openVerified(call.identity.pluginId)
            try { PluginPrivateStore(installationId,EncryptedPluginStateBackend(context),verified.security.surface.maxStorageBytes) }
            finally { verified.stagedDirectory.deleteRecursively() }
        }
        val handle = store.open(call.identity,call.accountId,call.pairingId)
        fun key() = JsonFields.string(args,"key") ?: error("SCHEMA_INVALID")
        val response: Any? = when (primitive) {
            "kernel.store.read" -> mapOf("value" to store.read(handle,call.accountId,key())?.let { Base64.getEncoder().encodeToString(it) })
            "kernel.store.write" -> { val bytes = Base64.getDecoder().decode(JsonFields.string(args,"value") ?: error("SCHEMA_INVALID")); store.write(handle,call.accountId,key(),bytes); mapOf("stored" to true) }
            "kernel.store.delete" -> { store.delete(handle,call.accountId,key()); mapOf("deleted" to true) }
            "kernel.store.keys" -> mapOf("keys" to store.keys(handle,call.accountId).sorted())
            "kernel.network.request" -> exchange(call,args)
            "kernel.scheduler.set" -> PluginJobScheduler.schedule(context,call,args)
            "kernel.scheduler.cancel" -> PluginJobScheduler.cancel(context,call,args)
            "kernel.scheduler.read" -> PluginJobScheduler.read(context,call,args)
            else -> throw CapabilityDenied(primitive)
        }
        return Json.canonical(Json.of(response)).toByteArray()
    }
    private fun exchange(call: KernelCallContext,args: JsonValue.JObject): Map<String,Any?> {
        val uri = URI(JsonFields.string(args,"url") ?: error("SCHEMA_INVALID"))
        check(uri.userInfo == null && uri.fragment == null && uri.host != null) { "NETWORK_TARGET_INVALID" }
        val proxy = proxies.getOrPut("${call.identity}:${call.accountId}:${call.pairingId}") {
            val verified = installStore.openVerified(call.identity.pluginId)
            try {
                val manifest = JsonFields.obj(Json.parse(File(verified.stagedDirectory,"manifest.json").readText()))!!
                val rules = (JsonFields.field(JsonFields.obj(JsonFields.field(manifest,"security")),"network") as? JsonValue.JArray)?.items.orEmpty()
                val methods = rules.flatMap { rule -> (JsonFields.field(rule as? JsonValue.JObject,"methods") as? JsonValue.JArray)?.items.orEmpty().mapNotNull { (it as? JsonValue.JString)?.value } }.toSet()
                val byHost=rules.mapNotNull { rule ->
                    val r=rule as? JsonValue.JObject ?: return@mapNotNull null
                    val host=JsonFields.string(r,"host") ?: return@mapNotNull null
                    host.lowercase().trimEnd('.') to JsonFields.strings(r,"methods").toSet()
                }.toMap()
                val usage=EncryptedDocuments(context,"plugin-network-usage:${call.identity}:${call.accountId}:${call.pairingId}")
                MediatedNetworkProxy(NetworkAllowlist(verified.security.surface.networkHosts,methods,byHost),object : MediatedTransport {
                    override fun exchange(request: MediatedRequest): MediatedResponse = runBlocking {
                        val transport = GatewayTransport(GatewayProfile("plugin","plugin","plugin","https://${request.host}"),maximumResponseBytes=minOf(request.maximumResponseBytes.toLong(),call.budget.maxOutputBytes/2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        val result = withTimeout(call.budget.maxInvocationMillis) { transport.execute(WireRequest(request.method,request.pathAndQuery,
                            request.headers.map { RawHeader(it.key,it.value) },request.body ?: ByteArray(0))) }
                        MediatedResponse(result.status,result.headers.associate { it.name to it.value },result.body)
                    }
                },call.budget.maxDailyNetworkBytes,usageStore=object:NetworkUsageStore {
                    override fun read():NetworkUsage?=usage.read("daily")?.let { bytes -> val o=JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("NETWORK_USAGE_INVALID");NetworkUsage(JsonFields.long(o,"start") ?: error("NETWORK_USAGE_INVALID"),JsonFields.long(o,"spent") ?: error("NETWORK_USAGE_INVALID")) }
                    override fun write(value:NetworkUsage)=usage.write("daily",Json.canonical(Json.of(mapOf("start" to value.windowStartMillis,"spent" to value.spentBytes))).toByteArray())
                })
            } finally { verified.stagedDirectory.deleteRecursively() }
        }
        val response = synchronized(proxy) { proxy.exchange(MediatedRequest(uri.scheme,uri.host,if (uri.port == -1) 443 else uri.port,
            JsonFields.string(args,"method") ?: "GET", (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: ""),
            JsonFields.obj(JsonFields.field(args,"headers"))?.fields.orEmpty().associate { it.first to ((it.second as? JsonValue.JString)?.value ?: error("SCHEMA_INVALID")) },
            JsonFields.string(args,"body")?.let { Base64.getDecoder().decode(it) })) }
        return mapOf("status" to response.status,"headers" to response.headers,"body" to Base64.getEncoder().encodeToString(response.body))
    }
    fun setting(pluginId: String, accountId: String, componentId: String, value: Any?) {
        check(grants.currentBinding()?.accountId == accountId) { "ACCOUNT_MISMATCH" }
        fun find(node: UiComponent): UiComponent? = if (node.id == componentId) node else (node as? UiComponent.Section)?.children?.firstNotNullOfOrNull(::find)
        val component=uiDeclarations[pluginId].orEmpty().firstNotNullOfOrNull { find(it.root) } ?: error("UI_COMPONENT_NOT_VERIFIED")
        check(component.action == UiActionId.SET_SETTING && when(component) {
            is UiComponent.Toggle -> value is Boolean
            is UiComponent.Select -> value is String && component.options.any { it.id == value }
            else -> false
        }) { "UI_ACTION_INVALID" }
        val identity = kernel.registrationFor(pluginId)?.identity ?: error("PLUGIN_NOT_REGISTERED")
        val pairingId = grants.state.value?.pairingId ?: error("PAIRING_REQUIRED")
        val verified = installStore.openVerified(pluginId)
        try {
            val store = stores.getOrPut(identity) { PluginPrivateStore(installationId,EncryptedPluginStateBackend(context),verified.security.surface.maxStorageBytes) }
            store.write(store.open(identity,accountId,pairingId),accountId,"ui:$componentId",Json.canonical(Json.of(value)).toByteArray())
        } finally { verified.stagedDirectory.deleteRecursively() }
        revision.value++
    }
    fun uninstall(pluginId: String) {
        val identity = kernel.registrationFor(pluginId)?.identity
        if (grants.state.value != null && identity != null) grant(pluginId,false)
        if (identity != null) {
            EncryptedPluginStateBackend(context).deletePluginPartitions(Base64.getUrlEncoder().withoutPadding().encodeToString(identity.pluginId.toByteArray()),
                Base64.getUrlEncoder().withoutPadding().encodeToString(identity.authorKeyFingerprint.toByteArray()))
            val pairingPreferences = context.getSharedPreferences("open_android_intelligence_pairing_grants",Context.MODE_PRIVATE)
            val edit = pairingPreferences.edit()
            pairingPreferences.all.filterKeys { it.endsWith(".granted") }.forEach { (key,value) ->
                val set = (value as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
                edit.putStringSet(key,set.filterNot { it.startsWith("plugin:$pluginId:") }.toSet())
            }; check(edit.commit())
        }
        PluginJobScheduler.erasePlugin(context,pluginId)
        enable(pluginId,false); installStore.uninstall(pluginId); reload()
    }
    fun eraseAccount(accountId: String,pairingId:String) { PluginPrivateStore(installationId,EncryptedPluginStateBackend(context),1L).erasePairing(accountId,pairingId); stores.values.forEach { it.erasePairing(accountId,pairingId) }; PluginJobScheduler.eraseAccount(context,accountId,pairingId) }
}
