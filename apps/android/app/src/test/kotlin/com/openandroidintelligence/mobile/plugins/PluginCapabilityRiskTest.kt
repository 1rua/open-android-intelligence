package com.openandroidintelligence.mobile.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginCapabilityRiskTest {
    @Test fun everyMutatingAndUnknownPrimitiveRequiresConfirmationRegardlessOfCapabilityName() {
        for (primitive in setOf("kernel.scheduler.set","kernel.scheduler.cancel","kernel.store.delete",
            "kernel.store.write","kernel.sms.send","kernel.network.request","kernel.future.mutate")) {
            assertEquals(primitive,"write",PluginCapabilityRisk.classify("protected-wasm",setOf("kernel.sms.read",primitive)))
        }
        assertEquals("read",PluginCapabilityRisk.classify("protected-wasm",setOf("kernel.sms.read","kernel.store.keys","kernel.scheduler.read")))
        assertEquals("high-privilege-ephemeral",PluginCapabilityRisk.classify("developer-native",emptySet()))
    }
}
