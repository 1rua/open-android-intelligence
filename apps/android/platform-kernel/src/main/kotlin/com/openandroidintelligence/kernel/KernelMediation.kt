package com.openandroidintelligence.kernel

import com.openandroidintelligence.plugin.pkg.PluginIdentity
import java.util.concurrent.ConcurrentHashMap

/** Created by the host, never decoded from untrusted WASM input. */
data class KernelCallContext(val identity: PluginIdentity, val accountId: String, val pairingId: String,
    val session: SessionConstraints, val budget: ResourceBudget, val grantRevision:Long = 0L)

object KernelPrimitiveRegistry {
    private val providers = ConcurrentHashMap<String, KernelPrimitiveProvider>()
    fun register(provider: KernelPrimitiveProvider) { providers[provider.primitiveId] = provider }
    fun unregister(provider: KernelPrimitiveProvider) { providers.remove(provider.primitiveId, provider) }
    fun provider(id: String): KernelPrimitiveProvider? = providers[id]
    fun ids(): Set<String> = providers.keys.toSet()
}
