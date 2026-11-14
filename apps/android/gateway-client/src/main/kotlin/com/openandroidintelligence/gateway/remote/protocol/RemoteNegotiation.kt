package com.openandroidintelligence.gateway.remote.protocol

/** 选择顺序及旧服务缺省 Relay 行为与固定上游一致。 */
object RemoteNegotiation {
    fun selectProtocolVersion(offered: List<Long>, supported: List<Long> = listOf(1)): Long? =
        offered.filter { it in supported }.maxOrNull()

    fun selectCapabilities(offered: List<String>, supported: List<String>): List<String> =
        supported.distinct().filter { it in offered }

    fun acceptNegotiatedCapabilities(offered: List<String>, negotiated: List<String>?): List<String> {
        val accepted = negotiated ?: listOf("transport.relay")
        if (selectCapabilities(accepted, offered).size != accepted.size) invalid("CAPABILITY_NOT_OFFERED")
        return accepted.toList()
    }
}
