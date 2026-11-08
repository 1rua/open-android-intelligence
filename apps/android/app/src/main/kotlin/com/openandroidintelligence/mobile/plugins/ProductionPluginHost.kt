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

data class CapabilitySnapshot(val bindings: List<Map<String,Any?>>, val inventory: List<Map<String,Any?>>,
    val inventoryComplete: Boolean = true)

/** Installed code is reverified once before registration; invocations use immutable verified bytes. */
class ProductionPluginHost(private val context: Context, private val grants: PairingGrantStateHolder,
    private val trustMode: DeveloperTrustMode, val nativeLoader: NativePluginLoader, private val installationId: String,
    /** 官方能力包的来源；生产读 APK 资源，测试可注入字节。缺包时如实报告而不是假装可用。 */
    private val builtInPackages: BuiltInPackageReader = BuiltInPackageReader { plugin -> readBundledPluginPackage(context, plugin) },
) {
    val installStore = PluginInstallStore(context)
    val runtimes = ConcurrentHashMap<String,PluginRuntime>()
    val hostCapabilities: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    val phoneCapabilities: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
    private val defaults = ConcurrentHashMap<String,PluginIdentity>()
    val selector = CapabilityProviderSelector(defaults)
    val revision = MutableStateFlow(0L)
    /** Local installation diagnostics only; unverified manifests never declare Agent capabilities. */
    @Volatile var loadFailures: Map<String,String> = emptyMap(); private set

    /**
     * 内建能力本次启动的加载失败原因（稳定原因码）。
     *
     * 内建能力由 APK 提供，没有“安装”这一步，因此也不能用 [loadFailures] 的
     * “已安装但损坏”语义；界面必须能区分“加载失败”和“尚未验证”。
     */
    @Volatile var builtInFailures: Map<String,String> = emptyMap(); private set
    /** 内建能力的安全声明，供私有存储与网络代理沿用与普通插件相同的限额来源。 */
    private val builtInSurfaces = ConcurrentHashMap<String,VerifiedBuiltInCapability>()
    private val modules = ConcurrentHashMap<PluginIdentity,ByteArray>()
    private val capabilities = ConcurrentHashMap<String,List<VerifiedCapability>>()
    private val backgroundRequests = ConcurrentHashMap<String,Boolean>()
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
        if (!com.openandroidintelligence.mobile.BuildConfig.ALLOW_RUNTIME_PLUGINS) {
            // 这个渠道不装配插件运行时，也不带内置能力包。逐项记下原因，
            // 让能力页显示「不可用 + 可执行说明」，而不是把六项能力当成“找不到提供者”。
            builtInFailures = BundledPlugin.entries.associate { it.pluginId to "BUILT_IN_CHANNEL_UNSUPPORTED" }
            return
        }
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

    /**
     * 把 APK 内置的官方能力直接注册进内核。
     *
     * 与普通插件走同一套校验（签名、清单、容器限额、ABI），但**不落盘、不写启用标记**：
     * 校验通过即注册为已启用，于是用户装完 App 就能用，不需要“安装 + 启用”两步。
     * 这不扩大任何设备能力授权——能力开关、授权模式与系统权限仍各自独立裁决。
     */
    private fun registerBuiltInCapabilities() {
        val results = loadBuiltInCapabilities(BundledPlugin.entries, builtInPackages, File(context.cacheDir, "built-in-staging"))
        val failures = linkedMapOf<String,String>()
        for (result in results) {
            when (result) {
                is BuiltInLoadResult.Failed -> failures[result.pluginId] = result.code
                is BuiltInLoadResult.Loaded -> {
                    val capability = result.capability
                    runCatching {
                        kernel.register(PluginRegistration(capability.identity, capability.runtimeType, capability.declaredPrimitives,
                            ResourceBudget(capability.surface.maxInvocationMillis, capability.surface.maxMemoryBytes, 65_536,
                                capability.surface.maxConcurrentInvocations, capability.surface.maxDailyNetworkBytes),
                            identityScopedGrants = true))
                        kernel.enable(capability.pluginId)
                        capabilities[capability.pluginId] = capability.capabilities
                        builtInSurfaces[capability.pluginId] = capability
                        backgroundRequests[capability.pluginId] = capability.backgroundRequested
                        modules[capability.identity] = capability.payload
                        hostCapabilities.addAll(capability.capabilities.map { it.key })
                        phoneCapabilities.addAll(capability.capabilities.map { it.key })
                    }.onFailure {
                        // 注册失败按加载失败如实呈现；绝不留下半注册状态。
                        kernel.unregister(capability.pluginId)
                        capabilities.remove(capability.pluginId); builtInSurfaces.remove(capability.pluginId)
                        backgroundRequests.remove(capability.pluginId); modules.remove(capability.identity)
                        failures[capability.pluginId] = "BUILT_IN_REGISTRATION_FAILED"
                    }
                }
            }
        }
        builtInFailures = failures.toMap()
    }

    /**
     * 内建能力的安全声明随注册一并保存在内存里；第三方插件仍从磁盘上的已校验目录读取。
     * 内建能力不落盘，因此不能走 [PluginInstallStore.openVerified]。
     */
    private fun securitySurfaceFor(pluginId: String): SecuritySurface =
        builtInSurfaces[pluginId]?.surface ?: installStore.openVerified(pluginId).let { verified ->
            try { verified.security.surface } finally { verified.stagedDirectory.deleteRecursively() }
        }

    fun isBuiltIn(pluginId: String): Boolean = builtInSurfaces.containsKey(pluginId)

    @Synchronized fun reload() {
        capabilities.keys.toList().forEach { kernel.unregister(it) }
        nativeLoader.unloadAll(); modules.clear(); capabilities.clear(); backgroundRequests.clear(); defaults.clear(); stores.clear(); proxies.clear(); uiDeclarations.clear();cardIds.clear(); builtInSurfaces.clear()
        val failures = linkedMapOf<String,String>()
        registerBuiltInCapabilities()
        val installed = runCatching { installStore.listInstalled() }.getOrElse {
            failures["*"] = "PLUGIN_SCAN_FAILED"
            emptyList()
        }
            // 官方能力已从 APK 校验并注册：磁盘上同名目录不再参与加载，升级后也不会用到旧版本残留。
            // 目录本身保持原样，用户此前保存的插件数据仍留在原位置，只是不再被当成插件加载。
            .filterNot { view -> view.pluginId?.let { builtInSurfaces.containsKey(it) } == true }
        for (view in installed) {
            val id = view.pluginId
            if (id == null || id != view.directory.name) {
                failures[view.directory.name] = "PLUGIN_MANIFEST_INVALID"
                continue
            }
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
                        val operationPrimitives = PluginCapabilityRisk.primitivesFor(capabilityId, primitives)
                        VerifiedCapability(verified.identity,capabilityId,JsonFields.string(cap,"version") ?: error("MANIFEST_INVALID"),
                            Json.parse(File(verified.stagedDirectory,schemaPath).readText()),
                            PluginCapabilityRisk.classify(verified.runtime.type,operationPrimitives),operationPrimitives)
                    }
                    val declared = entries.map { it.key }.toSet() + primitives
                    val surface = verified.security.surface
                    val security = JsonFields.obj(JsonFields.field(manifest, "security"))
                    backgroundRequests[id] = JsonFields.bool(JsonFields.obj(JsonFields.field(security, "background")), "requested") == true
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
                    if (preferences.getString(id,null) == verified.identity.authorKeyFingerprint && !isQuarantined(id)) enableVerified(verified)
                } finally { verified.stagedDirectory.deleteRecursively() }
            }.onFailure {
                kernel.unregister(id); capabilities.remove(id); backgroundRequests.remove(id); preferences.edit().remove(id).commit()
                failures[view.directory.name] = if (it is PackageRejected) "PLUGIN_PACKAGE_INVALID" else "PLUGIN_LOAD_FAILED"
            }
        }
        val providers = capabilities.values.flatten().groupBy { it.key }.mapValues { (_, entries) -> entries.map { it.identity } }
        // Declaration order is not a provider choice. Only a unique verified
        // provider can be selected by default; multiple providers need a local choice.
        providers.filterValues { it.size == 1 }.forEach { (key, values) -> defaults[key] = values.single() }
        selector.refreshOverrides(providers)
        loadFailures = failures.toMap()
        revision.value++
    }

    fun contributions(pluginId: String): List<UiContribution> {
        val identity = kernel.registrationFor(pluginId)?.identity ?: return emptyList()
        val binding = grants.currentBinding() ?: return uiDeclarations[pluginId].orEmpty()
        val store = stores.getOrPut(identity) {
            PluginPrivateStore(installationId, EncryptedPluginStateBackend(context), securitySurfaceFor(pluginId).maxStorageBytes)
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

    /** Read-only view of verified declarations; listing never selects a provider. */
    @Synchronized fun verifiedCapabilities(): List<VerifiedCapability> =
        capabilities.values.flatten().sortedWith(compareBy({ it.id }, { it.version }, { it.identity.pluginId }))

    /** Match an in-memory approval to its exact author/schema/risk/primitive policy key. */
    @Synchronized fun capabilityForAuthorizationScope(scope: CapabilityRequestScope): VerifiedCapability? =
        capabilities.values.asSequence().flatten().firstOrNull { policyKey(it) == scope.capability }

    fun minimumBackgroundInterval(pluginId:String):Long {
        builtInSurfaces[pluginId]?.takeIf { it.backgroundRequested }?.let { return it.backgroundMinimumIntervalSeconds }
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
    private data class ActiveAuthorization(val requestId: String, val binding: PairingGrantBinding,
        val scope: CapabilityRequestScope)
    private val activeAuthorization = ThreadLocal<ActiveAuthorization>()

    private fun authorizationRequestKey(requestId: String, binding: PairingGrantBinding? = null): String {
        val owner = binding ?: grants.currentBinding() ?: throw CapabilityDenied("AUTHENTICATED_DEVICE_BINDING_REQUIRED")
        return digestName(owner.storageKey + "\u0000" + requestId)
    }

    private fun requireBinding(binding: PairingGrantBinding?): PairingGrantBinding {
        val owner = binding ?: grants.currentBinding() ?: throw CapabilityDenied("AUTHENTICATED_DEVICE_BINDING_REQUIRED")
        if (!grants.isConnectionCurrent(owner)) throw CapabilityDenied("AUTHENTICATED_DEVICE_BINDING_REQUIRED")
        return owner
    }

    private fun policyKey(entry: VerifiedCapability): String = identityGrantKey(entry.identity,
        "capability:${entry.id}:${Json.sha256(entry.schema)}:${entry.risk}:${Json.sha256(Json.of(entry.primitives.sorted()))}")

    private fun capabilityEntry(pluginId: String, capability: String): VerifiedCapability =
        entries(pluginId).firstOrNull { it.key == capability } ?: throw CapabilityDenied(capability)

    fun capabilityPolicy(pluginId: String, capability: String): com.openandroidintelligence.kernel.CapabilityPolicy {
        val entry = capabilityEntry(pluginId, capability)
        val state = grants.state.value
        val policy = grants.capabilityPolicy(policyKey(entry))
        if (state?.localError != null) return policy.copy(enabled = false)
        val legacyAllows = state != null && (entry.primitives.filterNot { it == "kernel.background.run" } + entry.key).all {
            state.permits(it) && state.permits(identityGrantKey(entry.identity, it))
        }
        return if (legacyAllows) policy else policy.copy(enabled = false)
    }

    fun setCapabilityPolicy(pluginId: String, capability: String, policy: com.openandroidintelligence.kernel.CapabilityPolicy) {
        val entry = capabilityEntry(pluginId, capability)
        if (policy.enabled && !capabilityPolicy(pluginId, capability).enabled) {
            // Convert the old group denial to independent choices before reopening this capability.
            val siblings = entries(pluginId).filter { it.key != entry.key && !capabilityPolicy(pluginId, it.key).enabled }
                .associate { policyKey(it) to grants.capabilityPolicy(policyKey(it)).copy(enabled = false) }
            if (siblings.isNotEmpty()) grants.updateCapabilityPolicies(siblings)
            val registration = kernel.registrationFor(pluginId) ?: error("PLUGIN_NOT_REGISTERED")
            grants.updatePluginCapabilities(registration.identity, registration.declaredPrimitives, true)
            val target = entry.primitives.filterNot { it == "kernel.background.run" }.toSet() + entry.key
            grants.updatePrimitives(target + target.map { identityGrantKey(entry.identity, it) }, true)
        }
        grants.updateCapabilityPolicy(policyKey(entry), policy)
        if (policy.enabled) selector.setOverride(entry.key, grants.state.value?.pairingId ?: error("LOCAL_POLICY_UNAVAILABLE"), entry.identity)
        revision.value++
    }

    fun authorizationScope(entry: VerifiedCapability, parameters: JsonValue): com.openandroidintelligence.kernel.CapabilityRequestScope =
        com.openandroidintelligence.kernel.CapabilityRequestScope(policyKey(entry), when {
            "kernel.screen.control" in entry.primitives -> com.openandroidintelligence.kernel.DeviceOperation.CONTROL
            entry.risk == "read" -> com.openandroidintelligence.kernel.DeviceOperation.READ
            else -> com.openandroidintelligence.kernel.DeviceOperation.WRITE
        }, Json.sha256(parameters))

    fun requestAuthorization(requestId: String, entry: VerifiedCapability, parameters: JsonValue,
        binding: PairingGrantBinding? = null): CapabilityAuthorization = grants.authorization.evaluate(
            authorizationRequestKey(requestId, requireBinding(binding)), authorizationScope(entry, parameters),
            capabilityPolicy(entry.identity.pluginId, entry.key), grants.state.value?.revision ?: error("LOCAL_POLICY_UNAVAILABLE"))

    fun approveRequest(requestId: String, entry: VerifiedCapability, parameters: JsonValue, durationMinutes: Int,
        remainingRequestMillis: Long, binding: PairingGrantBinding? = null): LocalCapabilityApproval =
        grants.authorization.approve(authorizationRequestKey(requestId, requireBinding(binding)),
            authorizationScope(entry, parameters), durationMinutes,
            grants.state.value?.revision ?: error("LOCAL_POLICY_UNAVAILABLE"), remainingRequestMillis,
            displaySummary = com.openandroidintelligence.mobile.screen.temporaryApprovalParameterSummary(parameters))

    fun authorizationIsCurrent(requestId: String, entry: VerifiedCapability, parameters: JsonValue,
        binding: PairingGrantBinding? = null): Boolean = runCatching {
            requestAuthorization(requestId, entry, parameters, binding) is CapabilityAuthorization.Allowed
        }.getOrDefault(false)

    fun authorizationScopeIsCurrent(requestId: String, scope: CapabilityRequestScope,
        approvalId: String?, binding: PairingGrantBinding? = null): Boolean = runCatching {
        val local = grants.state.value ?: return false
        val policy = grants.capabilityPolicy(scope.capability)
        if (!policy.enabled || local.localError != null) return false
        val key = authorizationRequestKey(requestId, requireBinding(binding))
        if (approvalId != null) grants.authorization.isCurrent(approvalId, key, scope, local.revision)
        else grants.authorization.evaluate(key, scope, policy, local.revision) is CapabilityAuthorization.Allowed
    }.getOrDefault(false)

    fun auditRequestDecision(requestId: String, accountId: String, binding: PairingGrantBinding?, allowed: Boolean,
        action: String = "capability.request.confirmed") {
        val owner = binding ?: grants.currentBinding() ?: throw CapabilityDenied("AUTHENTICATED_DEVICE_BINDING_REQUIRED")
        check(owner.accountId == accountId && grants.isConnectionCurrent(owner)) { "DEVICE_ACCOUNT_BINDING_MISMATCH" }
        grants.recordRequestDecision(requestId, accountId, owner.pairingId, action, allowed)
    }

    fun cancelRequestAuthorization(requestId: String, binding: PairingGrantBinding? = null) {
        runCatching { authorizationRequestKey(requestId, binding) }.getOrNull()?.let(grants.authorization::cancel)
    }
    fun finishRequestAuthorization(requestId: String, binding: PairingGrantBinding? = null) {
        runCatching { authorizationRequestKey(requestId, binding) }.getOrNull()?.let(grants.authorization::finish)
    }
    val authorizationChanges get() = grants.authorization.changes
    fun revokeTemporaryApprovals() { grants.revokeTemporaryApprovals(); revision.value++ }

    fun authorizeKernelInvocation(identity: PluginIdentity, capability: String, input: ByteArray, session: SessionConstraints) {
        val entry = capabilityEntry(identity.pluginId, capability)
        require(entry.identity == identity) { "PLUGIN_IDENTITY_MISMATCH" }
        val active = activeAuthorization.get() ?: throw CapabilityDenied("LOCAL_AUTHORIZATION_CONTEXT_REQUIRED")
        if (active.requestId != session.correlationId || active.scope != authorizationScope(entry, Json.parse(input.decodeToString())))
            throw CapabilityDenied("LOCAL_AUTHORIZATION_CONTEXT_REQUIRED")
        requireActiveAuthorization(session.correlationId, entry, Json.parse(input.decodeToString()), active.binding)
    }

    private fun requireActiveAuthorization(requestId: String, entry: VerifiedCapability, parameters: JsonValue,
        binding: PairingGrantBinding? = null) {
        when (val decision = requestAuthorization(requestId, entry, parameters, binding)) {
            is com.openandroidintelligence.kernel.CapabilityAuthorization.Allowed -> Unit
            is com.openandroidintelligence.kernel.CapabilityAuthorization.Denied -> throw CapabilityDenied(decision.code)
            com.openandroidintelligence.kernel.CapabilityAuthorization.AwaitingConfirmation -> throw CapabilityDenied("LOCAL_CONFIRMATION_REQUIRED")
        }
    }

    /** Local ability switch; enabling it never grants background, system or screen permissions. */
    fun setCapabilityEnabled(pluginId: String, enabled: Boolean) {
        val registration = kernel.registrationFor(pluginId) ?: error("PLUGIN_NOT_REGISTERED")
        grants.updatePluginCapabilities(registration.identity, registration.declaredPrimitives, enabled)
        if (enabled) entries(pluginId).forEach {
            selector.setOverride(it.key, grants.state.value?.pairingId ?: error("PAIRING_REQUIRED"), it.identity)
        }
        revision.value++
    }

    fun isCapabilityEnabled(pluginId: String): Boolean {
        val state = grants.state.value ?: return false
        val registration = kernel.registrationFor(pluginId) ?: return false
        return registration.declaredPrimitives.filterNot { it == "kernel.background.run" }.all {
            state.permits(it) && state.permits(identityGrantKey(registration.identity, it))
        }
    }

    fun supportsBackground(pluginId: String): Boolean =
        backgroundRequests[pluginId] == true && kernel.registrationFor(pluginId)?.declaredPrimitives?.contains("kernel.background.run") == true

    fun isBackgroundEnabled(pluginId: String): Boolean {
        if (!supportsBackground(pluginId)) return false
        val identity = kernel.registrationFor(pluginId)?.identity ?: return false
        val state = grants.state.value ?: return false
        return state.permits("kernel.background.run") && state.permits(identityGrantKey(identity, "kernel.background.run"))
    }

    /** A separate, explicit Android-local background decision; ordinary defaults never change here. */
    fun setBackgroundEnabled(pluginId: String, enabled: Boolean) {
        check(supportsBackground(pluginId)) { "BACKGROUND_NOT_DECLARED" }
        val identity = kernel.registrationFor(pluginId)?.identity ?: error("PLUGIN_NOT_REGISTERED")
        grants.updatePluginBackground(identity, enabled)
        revision.value++
    }

    /** Compatibility for explicit Android consent, including its separate background choice. */
    fun grant(pluginId: String, enabled: Boolean) {
        val registration = kernel.registrationFor(pluginId) ?: error("PLUGIN_NOT_REGISTERED")
        val ids = registration.declaredPrimitives
        val scoped = ids.map { identityGrantKey(registration.identity,it) }.toSet()
        grants.updatePluginCapabilities(registration.identity, ids, enabled)
        grants.updatePrimitives(if (enabled) ids + scoped else scoped, enabled)
        if (enabled) entries(pluginId).forEach { selector.setOverride(it.key,grants.state.value?.pairingId ?: error("PAIRING_REQUIRED"),it.identity) }
        revision.value++
    }
    fun authorizedBindings(): List<Map<String,Any?>> = capabilitySnapshot().bindings

    /**
     * Inventory describes a callable foreground binding, not a promise that
     * every parameter or background mode has consent. v1 kernelPrimitives is
     * the whole package's request ceiling, not per-capability dependencies:
     * the SMS package includes both foreground queries and scheduled work.
     * Background consent is independently enforced by the kernel/scheduler.
     */
    @Synchronized fun capabilitySnapshot(): CapabilitySnapshot {
        val state = grants.state.value
        val bindings = mutableListOf<Map<String,Any?>>()
        val inventory = capabilities.values.flatten().sortedBy { it.key }.map { entry ->
            var status = "READY"
            var reason = ""
            when {
                state?.localError != null -> { status = "LOCAL_GRANT_REQUIRED"; reason = requireNotNull(state.localError) }
                !isEnabled(entry.identity.pluginId) -> { status = "PLUGIN_DISABLED"; reason = "PLUGIN_DISABLED" }
                !capabilityPolicy(entry.identity.pluginId, entry.key).enabled || state == null || !(entry.primitives.filterNot { it == "kernel.background.run" } + entry.key).all {
                    state.permits(identityGrantKey(entry.identity,it)) && state.permits(it)
                } -> {
                    status = "LOCAL_GRANT_REQUIRED"
                    reason = "LOCAL_CAPABILITY_DISABLED"
                }
                !runCatching { selector.select(entry.key,state.pairingId).identity == entry.identity }.getOrDefault(false) -> {
                    status = "PROVIDER_NOT_SELECTED"; reason = "PROVIDER_NOT_SELECTED"
                }
                else -> {
                    reason = entry.primitives.sorted().firstNotNullOfOrNull { primitive ->
                        val provider = KernelPrimitiveRegistry.provider(primitive)
                        if (primitive in serviceIds) null else if (provider == null) "PROVIDER_UNAVAILABLE"
                        else runCatching { provider.availability() }.getOrElse { "PROVIDER_UNAVAILABLE" }
                    }.orEmpty()
                    status = when (reason) {
                        "" -> "READY"
                        // 授权类：都能由用户操作恢复。区分具体原因是为了让远端与界面
                        // 知道该引导用户去「申请权限」还是「检查服务是否连接」。
                        "READ_SMS_REQUIRED", "READ_CALL_LOG_REQUIRED", "NOTIFICATION_ACCESS_REQUIRED",
                        "ACCESSIBILITY_SERVICE_UNAVAILABLE", "SCREEN_CAPTURE_AUTHORIZATION_REQUIRED",
                        "SCREEN_CAPTURE_SYSTEM_REJECTED", "SCREEN_DEVICE_LOCKED" -> "ANDROID_PERMISSION_REQUIRED"
                        "LOCAL_GRANT_REQUIRED", "LOCAL_POLICY_CORRUPTED", "SCREEN_SESSION_REQUIRED",
                        "SCREEN_SESSION_EXPIRED", "SCREEN_SESSION_BINDING_CHANGED", "SCREEN_PERMISSION_REQUIRED" -> "LOCAL_GRANT_REQUIRED"
                        // 其余（含频率限制、受保护窗口、取帧失败、系统不支持）如实报
                        // 提供者未就绪，具体原因由 reason 携带，不在这里升级成授权问题。
                        else -> "PROVIDER_UNAVAILABLE"
                    }
                }
            }
            if (status == "READY") bindings += entry.binding()
            mapOf("pluginId" to entry.identity.pluginId,"capabilityId" to entry.id,"capabilityVersion" to entry.version,
                "status" to status,"reason" to reason)
        }
        return CapabilitySnapshot(bindings, inventory, inventoryComplete = loadFailures.isEmpty())
    }
    fun invoke(identity: PluginIdentity, accountId: String, capability: String, parameters: JsonValue, correlationId: String,
        background: Boolean = false, authenticatedBinding: PairingGrantBinding? = null): ByteArray {
        val entry = entries(identity.pluginId).firstOrNull { it.identity == identity && it.key == capability } ?: throw CapabilityDenied(capability)
        check(CapabilitySchemaValidator.accepts(entry.schema,parameters)) { "SCHEMA_INVALID" }
        val binding = authenticatedBinding ?: grants.currentBinding() ?: error("AUTHENTICATED_DEVICE_BINDING_REQUIRED")
        check(grants.isConnectionCurrent(binding)) { "AUTHENTICATED_DEVICE_BINDING_REQUIRED" }
        check(binding.accountId == accountId) { "DEVICE_ACCOUNT_BINDING_MISMATCH" }
        val pairingId = binding.pairingId
        requireActiveAuthorization(correlationId, entry, parameters, binding)
        val previousAuthorization = activeAuthorization.get()
        activeAuthorization.set(ActiveAuthorization(correlationId, binding, authorizationScope(entry, parameters)))
        try {
            val output = kernel.invoke(identity,accountId,pairingId,capability,Json.canonical(parameters).toByteArray(),
                SessionConstraints(entry.primitives + entry.key,background,correlationId)).output
            requireActiveAuthorization(correlationId, entry, parameters, binding)
            return output
        } finally {
            if (previousAuthorization == null) activeAuthorization.remove() else activeAuthorization.set(previousAuthorization)
        }
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
        val invocation = activeAuthorization.get() ?: throw CapabilityDenied("LOCAL_AUTHORIZATION_CONTEXT_REQUIRED")
        if (invocation.requestId != call.session.correlationId || invocation.binding.accountId != call.accountId ||
            invocation.binding.pairingId != call.pairingId || !authorizationScopeIsCurrent(call.session.correlationId,
                invocation.scope, null, invocation.binding)) {
            throw CapabilityDenied("LOCAL_AUTHORIZATION_CHANGED")
        }
        KernelPrimitiveRegistry.provider(primitive)?.let { provider ->
            val expectedRevision = grants.state.value?.revision ?: throw CapabilityDenied("LOCAL_POLICY_UNAVAILABLE")
            val scope = invocation.scope
            val policy = grants.capabilityPolicy(scope.capability)
            val localInput = if (primitive == "kernel.sms.read") {
                val query = JsonFields.obj(Json.parse(input.decodeToString())) ?: error("SCHEMA_INVALID")
                val fields = query.fields.associate { it.first to it.second }.toMutableMap<String, Any?>()
                val requested = JsonFields.strings(query, "senders").toSet()
                if (!policy.includeContent && JsonFields.string(query, "query").orEmpty().isNotBlank())
                    throw CapabilityDenied("LOCAL_DATA_SCOPE_DENIED")
                if (!SmsScopeMatcher.hasConditions(policy.smsScopeMode, policy.allowedSources, policy.smsKeywords))
                    throw CapabilityDenied("LOCAL_DATA_SCOPE_DENIED")
                if (policy.smsScopeMode == SmsScopeMode.PHONE_NUMBERS) {
                    if (requested.any { it !in policy.allowedSources }) throw CapabilityDenied("LOCAL_DATA_SCOPE_DENIED")
                    fields["senders"] = (requested.ifEmpty { policy.allowedSources }).sorted()
                }
                fields["smsScopeMode"] = policy.smsScopeMode.name
                fields["smsPhoneNumbers"] = policy.allowedSources.sorted()
                fields["smsKeywords"] = policy.smsKeywords.sorted()
                fields["includeContent"] = policy.includeContent && JsonFields.bool(query, "includeContent") != false
                Json.canonical(Json.of(fields)).toByteArray()
            } else input
            val result = runBlocking {
                withTimeout(call.budget.maxInvocationMillis) {
                    if (expectedRevision != grants.state.value?.revision || kernel.isEmergencyStopped() ||
                        !isEnabled(call.identity.pluginId) || !authorizationScopeIsCurrent(call.session.correlationId,
                            invocation.scope, null, invocation.binding)) throw CapabilityDenied("LOCAL_AUTHORIZATION_CHANGED")
                    provider.invoke(LocalGrantContext(call.identity.pluginId,call.accountId,call.pairingId,call.session.correlationId),localInput)
                }
            }
            if (expectedRevision != grants.state.value?.revision || kernel.isEmergencyStopped() ||
                !isEnabled(call.identity.pluginId) ||
                !authorizationScopeIsCurrent(call.session.correlationId, invocation.scope, null, invocation.binding))
                throw CapabilityDenied("LOCAL_AUTHORIZATION_CHANGED")
            return result
        }
        val args = JsonFields.obj(Json.parse(input.decodeToString())) ?: error("SCHEMA_INVALID")
        val store = stores.getOrPut(call.identity) {
            PluginPrivateStore(installationId, EncryptedPluginStateBackend(context), securitySurfaceFor(call.identity.pluginId).maxStorageBytes)
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
        val proxy = proxies.getOrPut("${call.identity}:${call.accountId}:${call.pairingId}") { builtInNetworkProxy(call) ?: installedNetworkProxy(call) }
        val response = synchronized(proxy) { proxy.exchange(MediatedRequest(uri.scheme,uri.host,if (uri.port == -1) 443 else uri.port,
            JsonFields.string(args,"method") ?: "GET", (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: ""),
            JsonFields.obj(JsonFields.field(args,"headers"))?.fields.orEmpty().associate { it.first to ((it.second as? JsonValue.JString)?.value ?: error("SCHEMA_INVALID")) },
            JsonFields.string(args,"body")?.let { Base64.getDecoder().decode(it) })) }
        return mapOf("status" to response.status,"headers" to response.headers,"body" to Base64.getEncoder().encodeToString(response.body))
    }

    /**
     * 内建能力不落盘，网络规则直接来自本次启动校验出的安全声明。
     *
     * 官方能力包都不声明网络主机，因此这里实际是 deny-all：既不因为“内建”而放宽，
     * 也不需要为它保留一份磁盘清单。
     */
    private fun builtInNetworkProxy(call: KernelCallContext): MediatedNetworkProxy? {
        val builtIn = builtInSurfaces[call.identity.pluginId] ?: return null
        val usage = EncryptedDocuments(context,"plugin-network-usage:${call.identity}:${call.accountId}:${call.pairingId}")
        return MediatedNetworkProxy(NetworkAllowlist(builtIn.surface.networkHosts, emptySet(), emptyMap()),
            gatewayMediatedTransport(call), call.budget.maxDailyNetworkBytes, usageStore = encryptedNetworkUsageStore(usage))
    }

    private fun installedNetworkProxy(call: KernelCallContext): MediatedNetworkProxy {
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
            return MediatedNetworkProxy(NetworkAllowlist(verified.security.surface.networkHosts,methods,byHost),
                gatewayMediatedTransport(call), call.budget.maxDailyNetworkBytes, usageStore = encryptedNetworkUsageStore(usage))
        } finally { verified.stagedDirectory.deleteRecursively() }
    }

    private fun gatewayMediatedTransport(call: KernelCallContext): MediatedTransport = object : MediatedTransport {
        override fun exchange(request: MediatedRequest): MediatedResponse = runBlocking {
            val transport = GatewayTransport(GatewayProfile("plugin","plugin","plugin","https://${request.host}"),maximumResponseBytes=minOf(request.maximumResponseBytes.toLong(),call.budget.maxOutputBytes/2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val result = withTimeout(call.budget.maxInvocationMillis) { transport.execute(WireRequest(request.method,request.pathAndQuery,
                request.headers.map { RawHeader(it.key,it.value) },request.body ?: ByteArray(0))) }
            MediatedResponse(result.status,result.headers.associate { it.name to it.value },result.body)
        }
    }

    private fun encryptedNetworkUsageStore(usage: EncryptedDocuments): NetworkUsageStore = object : NetworkUsageStore {
        override fun read():NetworkUsage?=usage.read("daily")?.let { bytes -> val o=JsonFields.obj(Json.parse(bytes.decodeToString())) ?: error("NETWORK_USAGE_INVALID");NetworkUsage(JsonFields.long(o,"start") ?: error("NETWORK_USAGE_INVALID"),JsonFields.long(o,"spent") ?: error("NETWORK_USAGE_INVALID")) }
        override fun write(value:NetworkUsage)=usage.write("daily",Json.canonical(Json.of(mapOf("start" to value.windowStartMillis,"spent" to value.spentBytes))).toByteArray())
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
        val store = stores.getOrPut(identity) {
            PluginPrivateStore(installationId, EncryptedPluginStateBackend(context), securitySurfaceFor(pluginId).maxStorageBytes)
        }
        store.write(store.open(identity,accountId,pairingId),accountId,"ui:$componentId",Json.canonical(Json.of(value)).toByteArray())
        revision.value++
    }
    fun uninstall(pluginId: String) {
        // 内建能力由 APK 提供，不存在“卸载”：调用方只能改能力开关。
        check(!isBuiltIn(pluginId)) { "BUILT_IN_CAPABILITY_NOT_REMOVABLE" }
        require(Regex("[A-Za-z0-9][A-Za-z0-9.-]*").matches(pluginId)) { "INVALID_PLUGIN_DIRECTORY" }
        val identity = kernel.registrationFor(pluginId)?.identity
        // Damaged packages have no verified registration; revoke their scoped grants by the actual directory ID.
        val scopedGrants = grants.state.value?.granted.orEmpty().filter { it.startsWith("plugin:$pluginId:") }.toSet()
        if (scopedGrants.isNotEmpty()) grants.updatePrimitives(scopedGrants,false)
        if (identity != null) {
            EncryptedPluginStateBackend(context).deletePluginPartitions(Base64.getUrlEncoder().withoutPadding().encodeToString(identity.pluginId.toByteArray()),
                Base64.getUrlEncoder().withoutPadding().encodeToString(identity.authorKeyFingerprint.toByteArray()))
        }
        val pairingPreferences = context.getSharedPreferences("open_android_intelligence_pairing_grants",Context.MODE_PRIVATE)
        val edit = pairingPreferences.edit()
        pairingPreferences.all.filterKeys { it.endsWith(".granted") }.forEach { (key,value) ->
            val set = (value as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
            edit.putStringSet(key,set.filterNot { it.startsWith("plugin:$pluginId:") }.toSet())
        }; check(edit.commit())
        PluginJobScheduler.erasePlugin(context,pluginId)
        enable(pluginId,false); installStore.uninstall(pluginId); reload()
    }
    fun eraseAccount(accountId: String,pairingId:String) { PluginPrivateStore(installationId,EncryptedPluginStateBackend(context),1L).erasePairing(accountId,pairingId); stores.values.forEach { it.erasePairing(accountId,pairingId) }; PluginJobScheduler.eraseAccount(context,accountId,pairingId) }
}
