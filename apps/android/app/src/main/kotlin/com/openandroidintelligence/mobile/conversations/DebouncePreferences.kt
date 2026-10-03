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
}
