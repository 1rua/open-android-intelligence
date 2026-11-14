package com.openandroidintelligence.conversation.remote

import java.util.Base64

/** 旧界面的本地 ID 使用安全字母表；原生 ID 只做可逆编码，不改写或猜测其中的前缀。 */
object RemoteLocalIds {
    fun encode(nativeId: String): String {
        require(nativeId.isNotEmpty()) { "原生标识不能为空" }
        return "remote_" + Base64.getUrlEncoder().withoutPadding().encodeToString(nativeId.toByteArray(Charsets.UTF_8))
    }

    fun decode(localId: String): String {
        require(localId.startsWith("remote_")) { "远端本地标识无效" }
        val bytes = try { Base64.getUrlDecoder().decode(localId.removePrefix("remote_")) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("远端本地标识无效") }
        val decoded = bytes.toString(Charsets.UTF_8)
        require(decoded.isNotEmpty() && encode(decoded) == localId) { "远端本地标识无效" }
        return decoded
    }
}
