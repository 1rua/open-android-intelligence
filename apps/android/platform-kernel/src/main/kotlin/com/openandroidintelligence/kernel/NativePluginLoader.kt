package com.openandroidintelligence.kernel

/** Raised when native code would run outside developer trust mode. */
class NativePluginRejected(code: String) :
    IllegalArgumentException("NATIVE_PLUGIN_REJECTED:$code")

data class NativePluginPackage(
    val pluginId: String,
    val authorKeyFingerprint: String,
    val entrypointClass: String,
    val abis: Set<String>,
)

interface NativePlugin {
    val pluginId: String
    fun invoke(input: ByteArray): ByteArray
    fun stop()
}

/**
 * Loads native plugins in the host process.
 *
 * The loader is only reachable while developer trust mode is on, and it tears
 * everything down the moment that mode is switched off. Native code shares the
 * host UID, so there is no meaningful isolation to promise here and none is
 * advertised.
 */
class NativePluginLoader(
    trustMode: DeveloperTrustMode,
) {
    private val loaded = LinkedHashMap<String, NativePlugin>()

    init {
        // Registration also fires immediately, so a loader built after the mode
        // was disabled starts in the unloaded state rather than inheriting a
        // stale allowance.
        trustMode.onChange { enabled -> if (!enabled) unloadAll() }
    }

    /**
     * Loads one native plugin.
     *
     * The entrypoint must name a class inside the plugin's own namespace. A
     * plugin may otherwise name a host class as its entrypoint and have the
     * loader hand it host internals under the appearance of a normal load.
     */
    fun load(
        packageInfo: NativePluginPackage,
        trustEnabled: Boolean,
        factory: (NativePluginPackage) -> NativePlugin,
    ): NativePlugin {
        if (!trustEnabled) throw NativePluginRejected("TRUST_MODE_DISABLED")
        if (!ENTRYPOINT_PATTERN.matches(packageInfo.entrypointClass)) {
            throw NativePluginRejected("BAD_ENTRYPOINT")
        }
        if (isHostClass(packageInfo.entrypointClass)) {
            throw NativePluginRejected("HOST_CLASS_ENTRYPOINT")
        }
        if (!packageInfo.entrypointClass.startsWith(packageInfo.pluginId.replace('-', '_') + ".")) {
            throw NativePluginRejected("FOREIGN_NAMESPACE_ENTRYPOINT")
        }
        val plugin = factory(packageInfo)
        if (plugin.pluginId != packageInfo.pluginId) throw NativePluginRejected("PLUGIN_ID_MISMATCH")
        unload(packageInfo.pluginId)
        loaded[packageInfo.pluginId] = plugin
        return plugin
    }

    fun loaded(): Set<String> = loaded.keys.toSet()

    fun isLoaded(pluginId: String): Boolean = loaded.containsKey(pluginId)

    fun plugin(pluginId: String): NativePlugin? = loaded[pluginId]
    fun unload(pluginId: String) { loaded.remove(pluginId)?.let { runCatching { it.stop() } } }

    /** Stops and forgets every native plugin. Used when trust mode is switched off. */
    fun unloadAll() {
        val plugins = loaded.values.toList()
        loaded.clear()
        plugins.forEach { runCatching { it.stop() } }
    }

    private fun isHostClass(entrypoint: String): Boolean =
        HOST_PACKAGE_PREFIXES.any { entrypoint.startsWith(it) }

    private companion object {
        val ENTRYPOINT_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        val HOST_PACKAGE_PREFIXES = listOf(
            "com.openandroidintelligence.",
            "android.",
            "androidx.",
            "java.",
            "javax.",
            "kotlin.",
            "dalvik.",
        )
    }
}
