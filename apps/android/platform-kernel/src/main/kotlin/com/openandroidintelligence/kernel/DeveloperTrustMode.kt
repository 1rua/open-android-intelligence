package com.openandroidintelligence.kernel

/**
 * 信任开关的持久化后端。
 *
 * 它只负责「上次进程退出时开关处于什么状态」这一个问题：恢复的永远是状态
 * 本身，不是开启该状态所需的确认。宿主用自己选的存储实现它（如
 * SharedPreferences/DataStore）；不提供时信任模式维持纯内存行为。
 */
interface TrustModePersistence {
    /**
     * 读取上次落盘的开关状态。
     *
     * 任何读取失败或数据损坏都以抛异常表达，由 [DeveloperTrustMode] 统一按
     * 「未开启」处理——损坏的记录绝不会被解释成已开启。
     */
    fun load(): Boolean

    /** 开关状态变化后写入。写入失败由实现方决定是否抛出；调用方不会因落盘失败崩溃。 */
    fun save(enabled: Boolean)
}

/**
 * Developer trust mode is the only door to native plugins.
 *
 * A native plugin runs in the host process and shares its UID, so it can read
 * every permission the host holds and every byte the host can reach. That is
 * why enabling this mode requires an explicit acknowledgement, and why leaving
 * it stops every native plugin immediately rather than at the next restart.
 *
 * 提供持久化后端时，开关状态跨进程重启保留：构造时读取恢复，enable/disable
 * 成功后写入。但持久化恢复的只是 enabled 布尔——每次 enable() 的确认文本
 * 校验必须来自当次真实交互，「上次开过」不构成对本次确认的豁免。
 */
class DeveloperTrustMode(
    private val persistence: TrustModePersistence? = null,
) {
    /** The acknowledgement the host must collect before the mode can be turned on. */
    data class Acknowledgement(val text: String) {
        companion object {
            /**
             * The host must show native code shares the process and UID: this
             * string is the proof it did, not a licence agreement the user
             * scrolls past.
             */
            const val REQUIRED_TEXT =
                "Native plugins run inside this app and can read every permission and all data this app can reach."
        }
    }

    // 构造即恢复：读取失败或数据损坏一律按「未开启」处理，绝不让坏数据把
    // 原生插件的后门重新打开。没有后端时 load 不被调用，保持纯内存行为。
    @Volatile
    private var enabled = runCatching { persistence?.load() }.getOrDefault(false) ?: false

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun isEnabled(): Boolean = enabled

    /** Returns false when the acknowledgement does not match what the host had to show. */
    fun enable(acknowledgement: Acknowledgement): Boolean {
        if (acknowledgement.text != Acknowledgement.REQUIRED_TEXT) return false
        if (enabled) return true
        enabled = true
        notifyListeners(true)
        persist(true)
        return true
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        // Order matters: listeners unload native code before anything else can
        // observe the new state and try to start a plugin again.
        notifyListeners(false)
        persist(false)
    }

    /**
     * Registers the hook that tears native plugins down.
     *
     * The hook is invoked immediately on registration when the mode is already
     * off, so a component created after the mode was disabled cannot miss the
     * transition.
     */
    fun onChange(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(enabled)
    }

    private fun notifyListeners(value: Boolean) {
        listeners.toList().forEach { it(value) }
    }

    /**
     * 状态已在内存生效并通知监听器之后再落盘：落盘失败只影响下次启动的恢复，
     * 不回滚本次已经发生的开关转换，也不会让调用方看到虚假的失败。
     */
    private fun persist(value: Boolean) {
        val backend = persistence ?: return
        runCatching { backend.save(value) }
    }
}
