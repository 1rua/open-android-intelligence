package com.openandroidintelligence.kernel

import com.openandroidintelligence.plugin.pkg.PluginIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Test fixture consent only; the kernel must still enforce its independent session and grant fences. */
internal fun testInvocationAuthorizer(
    allowedCapabilities: Map<PluginIdentity, Set<String>>,
    expectedSessionPrimitives: Set<String>,
): (PluginIdentity, String, ByteArray, SessionConstraints) -> Unit = { identity, capability, _, session ->
    assertTrue("Unexpected invocation identity", identity in allowedCapabilities)
    assertTrue("Unexpected invocation capability", capability in allowedCapabilities.getValue(identity))
    assertEquals("Unexpected session primitives", expectedSessionPrimitives, session.primitives)
}
