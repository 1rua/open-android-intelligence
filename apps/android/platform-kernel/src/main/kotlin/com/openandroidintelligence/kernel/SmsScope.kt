package com.openandroidintelligence.kernel

/** Saved scope is independent from body disclosure and from passive/push authorization. */
enum class SmsScopeMode { ALL, KEYWORDS, PHONE_NUMBERS }

object SmsScopeMatcher {
    fun hasConditions(mode: SmsScopeMode, phoneNumbers: Set<String>, keywords: Set<String>): Boolean = when (mode) {
        SmsScopeMode.ALL -> true
        SmsScopeMode.KEYWORDS -> keywords.any { it.isNotBlank() }
        SmsScopeMode.PHONE_NUMBERS -> phoneNumbers.any { it.isNotBlank() }
    }

    fun matchingKeywords(body: String?, keywords: Set<String>): Set<String> =
        if (body == null) emptySet() else keywords.filterTo(linkedSetOf()) { it.isNotBlank() && body.contains(it, ignoreCase = true) }

    /** Exact sender equality preserves legacy address restrictions; no country-code guessing. */
    fun matches(mode: SmsScopeMode, phoneNumbers: Set<String>, keywords: Set<String>, sender: String?,
        body: String?, matchedKeywords: Set<String> = emptySet()): Boolean = when (mode) {
        SmsScopeMode.ALL -> true
        SmsScopeMode.PHONE_NUMBERS -> sender != null && sender.isNotBlank() && sender in phoneNumbers
        SmsScopeMode.KEYWORDS -> matchingKeywords(body, keywords).isNotEmpty() ||
            keywords.any { keyword -> keyword.isNotBlank() && matchedKeywords.any { it.equals(keyword, ignoreCase = true) } }
    }
}
