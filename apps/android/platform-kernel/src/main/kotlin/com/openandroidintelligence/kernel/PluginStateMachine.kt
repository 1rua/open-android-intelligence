package com.openandroidintelligence.kernel

/** Raised for a state change the installation state machine does not allow. */
class StateTransitionRejected(val from: String, val to: String) :
    IllegalArgumentException("STATE_TRANSITION_REJECTED:$from->$to")

/**
 * Installation and activation states, per device-plugin-package-v1 §9.
 *
 * `installed` and `enabled` are deliberately different states: a package can
 * exist on the phone without being allowed to run.
 */
enum class PluginState {
    DISCOVERED,
    DOWNLOADING,
    VERIFYING,
    REJECTED,
    INSTALLED_DISABLED,
    ENABLED,
    WAITING_REAUTHORIZATION,
    MISSING_CAPABILITY,
    INCOMPATIBLE_HOST,
    QUARANTINED,
    ROLLBACK_AVAILABLE,
    UNINSTALLED,
}

/**
 * Tracks one plugin through the installation state machine.
 *
 * Only [PluginState.ENABLED] may execute. Every other state is a reason the
 * plugin must not run, which is why the recoverable states keep the plugin
 * installed instead of silently degrading into "enabled but broken".
 */
class PluginStateMachine(initial: PluginState = PluginState.DISCOVERED) {
    @Volatile
    var state: PluginState = initial
        private set

    private val history = mutableListOf(initial)

    /** The ordered states this plugin has occupied; useful for diagnostics and audits. */
    fun history(): List<PluginState> = history.toList()

    fun transition(to: PluginState) {
        val from = state
        if (!canTransition(from, to)) throw StateTransitionRejected(from.name, to.name)
        state = to
        history += to
    }

    /** Whether execution is allowed in the current state. */
    fun isExecutable(): Boolean = state == PluginState.ENABLED

    companion object {
        private val ALLOWED: Map<PluginState, Set<PluginState>> = mapOf(
            PluginState.DISCOVERED to setOf(
                PluginState.DOWNLOADING,
                PluginState.UNINSTALLED,
            ),
            PluginState.DOWNLOADING to setOf(
                PluginState.VERIFYING,
                PluginState.REJECTED,
                PluginState.UNINSTALLED,
            ),
            PluginState.VERIFYING to setOf(
                PluginState.INSTALLED_DISABLED,
                PluginState.REJECTED,
                PluginState.UNINSTALLED,
            ),
            PluginState.REJECTED to setOf(PluginState.UNINSTALLED),
            PluginState.INSTALLED_DISABLED to setOf(
                PluginState.ENABLED,
                PluginState.INCOMPATIBLE_HOST,
                PluginState.UNINSTALLED,
            ),
            PluginState.ENABLED to setOf(
                PluginState.INSTALLED_DISABLED,
                PluginState.WAITING_REAUTHORIZATION,
                PluginState.MISSING_CAPABILITY,
                PluginState.INCOMPATIBLE_HOST,
                PluginState.QUARANTINED,
                PluginState.ROLLBACK_AVAILABLE,
                PluginState.UNINSTALLED,
            ),
            // Recoverable only by an explicit new authorisation: nothing here
            // inherits the previous grant.
            PluginState.WAITING_REAUTHORIZATION to setOf(
                PluginState.ENABLED,
                PluginState.UNINSTALLED,
            ),
            PluginState.MISSING_CAPABILITY to setOf(
                PluginState.ENABLED,
                PluginState.INSTALLED_DISABLED,
                PluginState.UNINSTALLED,
            ),
            PluginState.INCOMPATIBLE_HOST to setOf(
                PluginState.INSTALLED_DISABLED,
                PluginState.UNINSTALLED,
            ),
            PluginState.QUARANTINED to setOf(
                PluginState.INSTALLED_DISABLED,
                PluginState.UNINSTALLED,
            ),
            PluginState.ROLLBACK_AVAILABLE to setOf(
                PluginState.ENABLED,
                PluginState.INSTALLED_DISABLED,
                PluginState.UNINSTALLED,
            ),
            PluginState.UNINSTALLED to emptySet(),
        )

        fun canTransition(from: PluginState, to: PluginState): Boolean =
            to in ALLOWED.getValue(from)
    }
}
