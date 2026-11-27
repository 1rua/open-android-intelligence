package com.openandroidintelligence.kernel

import org.junit.Assert.*
import org.junit.Test

class SmsScopeTest {
    @Test fun defaultsAndLegacySenderConstructorsPreserveTheirMeaning() {
        assertEquals(SmsScopeMode.ALL, CapabilityPolicy().smsScopeMode)
        val old = CapabilityPolicy(allowedSources = setOf("BANK-ID", "+86 13800000000"), includeContent = false)
        assertEquals(SmsScopeMode.PHONE_NUMBERS, old.smsScopeMode)
        assertEquals(old.allowedSources, old.copy(mode = CapabilityAuthorizationMode.READ_ONLY).allowedSources)
        assertTrue(SmsScopeMatcher.matches(old.smsScopeMode, old.allowedSources, emptySet(), "BANK-ID", null))
        assertFalse(SmsScopeMatcher.matches(old.smsScopeMode, old.allowedSources, emptySet(), "bank-id", null))
        assertFalse(SmsScopeMatcher.matches(old.smsScopeMode, old.allowedSources, emptySet(), "+8613800000000", null))
    }

    @Test fun literalKeywordsAreOrAndIgnoreCaseWithoutSqlWildcards() {
        val keywords = setOf("验证码", "CAFÉ", "%", "_", "'")
        for (body in listOf("验证码42", "café", "10%", "a_b", "o'neal"))
            assertTrue(body, SmsScopeMatcher.matches(SmsScopeMode.KEYWORDS, emptySet(), keywords, "other", body))
        assertFalse(SmsScopeMatcher.matches(SmsScopeMode.KEYWORDS, emptySet(), setOf("%", "_", "'"), "other", "ordinary text"))
        for (mode in listOf(SmsScopeMode.KEYWORDS, SmsScopeMode.PHONE_NUMBERS)) {
            assertFalse(SmsScopeMatcher.hasConditions(mode, emptySet(), emptySet()))
            assertFalse(SmsScopeMatcher.matches(mode, emptySet(), emptySet(), "sender", "body"))
        }
    }

    @Test fun policyUpdatesFreezeBothConditionSets() {
        val holder = PairingGrantStateHolder(InMemoryPairingGrantStore(), AndroidAuditStore())
        val senders = mutableSetOf("BANK")
        val keywords = mutableSetOf("CODE")
        holder.updateCapabilityPolicy("sms", CapabilityPolicy(allowedSources = senders,
            smsScopeMode = SmsScopeMode.KEYWORDS, smsKeywords = keywords))
        senders += "NEW"
        keywords += "OTHER"
        assertEquals(setOf("BANK"), holder.capabilityPolicy("sms").allowedSources)
        assertEquals(setOf("CODE"), holder.capabilityPolicy("sms").smsKeywords)
        val changed = holder.capabilityPolicy("sms").copy(allowedSources = setOf("changed"),
            smsScopeMode = SmsScopeMode.PHONE_NUMBERS)
        holder.updateCapabilityPolicy("sms", changed)
        assertEquals(SmsScopeMode.PHONE_NUMBERS, holder.capabilityPolicy("sms").smsScopeMode)
    }
}
