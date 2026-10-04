package com.openandroidintelligence.mobile.plugins

/** A capability may call every primitive declared by its verified package. */
internal object PluginCapabilityRisk {
    private val readOnly = setOf(
        "kernel.sms.read", "kernel.call-log.read", "kernel.notifications.read",
        "kernel.store.read", "kernel.store.keys", "kernel.scheduler.read",
        "kernel.background.run",
    )

    fun classify(runtime: String, primitives: Set<String>): String = when {
        runtime != "protected-wasm" -> "high-privilege-ephemeral"
        primitives.any { it !in readOnly } -> "write"
        else -> "read"
    }
}
