package com.openandroidintelligence.gateway.negotiation

import com.openandroidintelligence.gateway.http.RawHeader
import org.junit.Assert.*
import org.junit.Test

class AgentDisplayNameTest {
    @Test fun namesComeFromAdvertisedMetadataAndRemainOptionalForExistingGateways() {
        assertEquals("DSH", advertisedAgentName(listOf(RawHeader(AGENT_NAME_HEADER.lowercase(), "DSH"))))
        assertEquals("Hermes", advertisedAgentName(listOf(RawHeader(AGENT_NAME_HEADER, "Hermes"))))
        assertNull(advertisedAgentName(emptyList()))
    }

    @Test fun ambiguousEmptyOrInvalidDisplayNamesAreIgnored() {
        for (name in listOf("", " ", "Hermes\nOnline", "a".repeat(129))) {
            assertNull(advertisedAgentName(listOf(RawHeader(AGENT_NAME_HEADER, name))))
        }
        assertNull(advertisedAgentName(listOf(RawHeader(AGENT_NAME_HEADER, "DSH"), RawHeader(AGENT_NAME_HEADER, "Hermes"))))
    }
}
