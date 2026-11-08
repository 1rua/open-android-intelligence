package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.conversation.batch.DebounceMode
import com.openandroidintelligence.conversation.batch.DebouncePolicy
import kotlin.time.Duration.Companion.milliseconds

/** Global defaults with an explicit per-Gateway override; a value of zero disables collection. */
class DebouncePreferences(context:Context) {
    private val preferences=context.getSharedPreferences("conversation-debounce-v1",Context.MODE_PRIVATE)
    private fun key(gateway:String?)=gateway?.let { "gateway:"+java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b->"%02x".format(b) } } ?: "global"
    fun hasOverride(gateway:String)=preferences.contains(key(gateway)+":delay")
    fun read(gateway:String?):DebouncePolicy {
        val k=if (gateway!=null && !hasOverride(gateway)) key(null) else key(gateway)
        return DebouncePolicy(delay=preferences.getInt("$k:delay",1500).coerceIn(0,10000).milliseconds,
            mode=if (preferences.getBoolean("$k:extend",true)) DebounceMode.EXTEND_WINDOW else DebounceMode.FIXED_WINDOW)
    }
    fun write(gateway:String?,delayMillis:Int,extend:Boolean) {
        require(delayMillis in 0..10000)
        val k=key(gateway)
        check(preferences.edit().putInt("$k:delay",delayMillis).putBoolean("$k:extend",extend).commit())
    }
    fun useGlobal(gateway:String) { val k=key(gateway);check(preferences.edit().remove("$k:delay").remove("$k:extend").commit()) }

    /** 设置导入的事务快照：全部键值的只读副本，回滚时逐键写回。 */
    fun snapshot():Map<String,Any?> = preferences.all.toMap()

    /**
     * 用快照覆盖当前内容。
     *
     * 先清空再写入：快照里缺席的键（例如导入期间新建的 Core 覆盖）必须一并消失，
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
}
