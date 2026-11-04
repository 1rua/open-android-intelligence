package com.openandroidintelligence.mobile.settings

/** Pure same-depth return history; the NavController remains the only active navigation stack. */
internal data class SettingsShortcutHistory(
    val sources: List<String> = emptyList(),
    val targets: List<String> = emptyList(),
) {
    fun open(source: String, target: String): SettingsShortcutHistory =
        if (sources.lastOrNull() == target) pop() else copy(sources = sources + source, targets = targets + target)

    fun sourceFor(target: String): String? = sources.lastOrNull().takeIf { targets.lastOrNull() == target }
    fun pop(): SettingsShortcutHistory = copy(sources = sources.dropLast(1), targets = targets.dropLast(1))
}
