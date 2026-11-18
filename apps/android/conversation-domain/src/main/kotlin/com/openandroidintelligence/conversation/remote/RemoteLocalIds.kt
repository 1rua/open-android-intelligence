package com.openandroidintelligence.conversation.remote

import java.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** 旧界面的本地 ID 使用安全字母表；原生 ID 只做可逆编码，不改写或猜测其中的前缀。 */
object RemoteLocalIds {
    fun encode(nativeId: String): String {
        require(nativeId.isNotEmpty()) { "原生标识不能为空" }
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(nativeId))
            ByteArray(encoded.remaining()).also(encoded::get)
        } catch (_: Exception) { throw IllegalArgumentException("原生标识包含无效字符") }
        return "remote_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decode(localId: String): String {
        require(localId.startsWith("remote_")) { "远端本地标识无效" }
        val bytes = try { Base64.getUrlDecoder().decode(localId.removePrefix("remote_")) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("远端本地标识无效") }
        val decoded = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw IllegalArgumentException("远端本地标识包含无效字符") }
        require(decoded.isNotEmpty() && encode(decoded) == localId) { "远端本地标识无效" }
        return decoded
    }
}
