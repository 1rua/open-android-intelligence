package com.openandroidintelligence.mobile.plugins

/** A capability may call every primitive declared by its verified package. */
internal object PluginCapabilityRisk {
    private val readOnly = setOf(
        "kernel.sms.read", "kernel.call-log.read", "kernel.notifications.read",
        "kernel.screen.read", "kernel.screen.capture",
        "kernel.store.read", "kernel.store.keys", "kernel.scheduler.read",
        "kernel.background.run",
    )

    /** Built-in contracts narrow the package ceiling; a query can never call its sibling scheduler mutation. */
    fun primitivesFor(capabilityId: String, declared: Set<String>): Set<String> {
        val reads = setOf("kernel.store.read", "kernel.store.keys", "kernel.background.run")
        val bounded = when (capabilityId) {
            "org.openandroidintelligence.sms.query" -> reads + "kernel.sms.read"
            "org.openandroidintelligence.sms.schedule" -> setOf("kernel.scheduler.set", "kernel.background.run")
            "org.openandroidintelligence.sms.job-status" -> setOf("kernel.scheduler.read")
            "org.openandroidintelligence.sms.cancel" -> setOf("kernel.scheduler.cancel")
            "org.openandroidintelligence.notifications.query" -> reads + "kernel.notifications.read"
            "org.openandroidintelligence.call-log.query" -> reads + "kernel.call-log.read"
            else -> return declared
        }
        return declared.intersect(bounded)
    }

    fun classify(runtime: String, primitives: Set<String>): String = when {
        runtime != "protected-wasm" -> "high-privilege-ephemeral"
        primitives.any { it !in readOnly } -> "write"
        else -> "read"
    }
}
