package com.openandroidintelligence.mobile

import java.net.URI

internal object ServiceAddress {
    fun parse(value: String): URI? = runCatching {
        val candidate = value.trim().removeSuffix("/")
        val uri = URI(candidate)
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank())
        require(!candidate.substringAfter("://").contains("://"))
        uri
    }.getOrNull()
}
