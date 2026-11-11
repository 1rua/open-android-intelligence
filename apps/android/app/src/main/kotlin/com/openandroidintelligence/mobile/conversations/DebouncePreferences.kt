package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.conversation.batch.DebouncePolicy
import kotlin.time.Duration.Companion.milliseconds

/**
 * 客户端的消息合并偏好。
 *
 * 合并完全发生在手机上，与当前连的是哪台 Core 无关：只有用户主动拖动滑块选择了
 * 大于零的时长，才会在本地暂存连续消息；键不存在时按 0 秒（立即发送）处理，
 * 因此全新安装与旧版本升级都不会意外开启合并。
 *
 * 旧版本按 Gateway 覆盖的键（`gateway:<sha256>:delay`）不再读取也不迁移：
 * 需求明确要求 Core 级配置不变成客户端配置，升级后一切以这里的全局值为准。
 */
class DebouncePreferences(context:Context) {
    private val preferences=context.getSharedPreferences("conversation-debounce-v1",Context.MODE_PRIVATE)

    fun read():DebouncePolicy =
        DebouncePolicy(delay = preferences.getInt(DELAY_KEY, DEFAULT_DELAY_MILLIS).coerceIn(MIN_DELAY_MILLIS, MAX_DELAY_MILLIS).milliseconds)

    fun write(delayMillis:Int) {
        require(delayMillis in MIN_DELAY_MILLIS..MAX_DELAY_MILLIS)
        check(preferences.edit().putInt(DELAY_KEY,delayMillis).commit())
    }

    /** 设置导入的事务快照：全部键值的只读副本，回滚时逐键写回。 */
    fun snapshot():Map<String,Any?> = preferences.all.toMap()

    /**
     * 用快照覆盖当前内容。
     *
     * 先清空再写入：快照里缺席的键（例如旧版本遗留的按 Core 覆盖）必须一并消失，
     * 否则「失败即恢复原状」只是部分恢复。
     */
    fun restore(snapshot:Map<String,Any?>) {
        val edit=preferences.edit().clear()
        snapshot.forEach { (key,value) ->
            when(value) {
                is Int -> edit.putInt(key,value)
                is Long -> edit.putLong(key,value)
                is Boolean -> edit.putBoolean(key,value)
                is Float -> edit.putFloat(key,value)
                is String -> edit.putString(key,value)
                is Set<*> -> edit.putStringSet(key,value.filterIsInstance<String>().toSet())
                else -> Unit
            }
        }
        check(edit.commit())
    }

    companion object {
        const val MIN_DELAY_MILLIS = 0
        const val MAX_DELAY_MILLIS = 30_000

        /** 没有任何显式设置时的行为：不合并，点击即发送。 */
        const val DEFAULT_DELAY_MILLIS = 0

        private const val DELAY_KEY = "global:delay"
    }
}
