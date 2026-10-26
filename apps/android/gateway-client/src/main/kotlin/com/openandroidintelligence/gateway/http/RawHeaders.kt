package com.openandroidintelligence.gateway.http

data class RawHeader(val name: String, val value: String)

/**
 * Header validation from the raw list rather than a parsed map.
 *
 * A map collapses two conflicting values into one silently. For the headers the
 * protocol treats as conditional singletons that collapse is an ambiguity with a
 * security meaning — two `Content-Length`s or two `Digest`s are exactly how a
 * request smuggling or digest-substitution attack is smuggled past a parser.
 */
object RawHeaders {

    /**
     * Headers that must appear at most once. Everything else (for example
     * `Set-Cookie`) is legitimately repeatable.
     */
    private val SINGLETONS = setOf(
        "content-length",
        "digest",
        "content-type",
        "authorization",
        "host",
        "if-none-match",
        "if-match",
        "content-encoding",
        "transfer-encoding",
        "x-open-android-intelligence-media-format",
        "x-open-android-intelligence-refresh-replay",
        "x-oai-agent-sessions",
    )

    fun validate(headers: List<RawHeader>): List<RawHeader> {
        val seen = mutableMapOf<String, String>()
        for (header in headers) {
            if (header.name.isEmpty() || header.name.any { it.isWhitespace() }) {
                throw IllegalArgumentException("MALFORMED_HEADER:${header.name}")
            }
            // An obs-fold continuation arrives as a value starting with space or
            // tab; treating it as an independent header would let it add a second
            // meaning to a singleton.
            if (header.value.startsWith(" ") || header.value.startsWith("\t")) {
                val foldedName = header.name.lowercase()
                if (foldedName in SINGLETONS) throw IllegalArgumentException("DUPLICATE_HEADER:${header.name}")
            }
            val key = header.name.lowercase()
            if (key in SINGLETONS && seen.containsKey(key)) {
                throw IllegalArgumentException("DUPLICATE_HEADER:${header.name}")
            }
            seen[key] = header.value
        }
        return headers
    }

    /** Returns the only value for a singleton header, or null when absent. */
    fun single(headers: List<RawHeader>, name: String): String? {
        val key = name.lowercase()
        return headers.lastOrNull { it.name.lowercase() == key }?.value
    }
}
