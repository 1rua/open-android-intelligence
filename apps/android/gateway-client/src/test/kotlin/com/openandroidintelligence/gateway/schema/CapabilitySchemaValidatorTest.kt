package com.openandroidintelligence.gateway.schema

import org.junit.Assert.*
import org.junit.Test

class CapabilitySchemaValidatorTest {
    private val recursive = Json.parse("""{
        "type":"object","additionalProperties":false,"required":["tree"],
        "properties":{"tree":{"${'$'}ref":"#/${'$'}defs/node"}},
        "${'$'}defs":{"node":{"type":"object","additionalProperties":false,"required":["value"],
            "properties":{"value":{"type":"integer"},"children":{"type":"array","items":{"${'$'}ref":"#/${'$'}defs/node"}}}}}
    }""")

    private fun tree(levels: Int, leaf: Any = 1): JsonValue {
        var node = Json.of(mapOf("value" to leaf))
        repeat(levels) { node = Json.of(mapOf("value" to 1,"children" to listOf(node))) }
        return Json.of(mapOf("tree" to node))
    }

    @Test fun finiteRecursiveTreesBeyondTheOldTraversalLimitRemainValid() {
        for (levels in listOf(17,32,64,128)) {
            // Parse the actual serialized input as the production invocation does.
            assertTrue("valid tree at $levels levels",CapabilitySchemaValidator.accepts(recursive,Json.parse(Json.canonical(tree(levels)))))
        }
    }
    @Test fun deepInvalidLeavesStillFailAndSiblingValidationDoesNotShareAnActiveReference() {
        assertFalse(CapabilitySchemaValidator.accepts(recursive,tree(64,"wrong")))
        val sibling = Json.of(mapOf("value" to 2))
        assertTrue(CapabilitySchemaValidator.accepts(recursive,Json.of(mapOf("tree" to mapOf("value" to 1,"children" to listOf(sibling,sibling))))))
    }
    @Test fun localPointersDecodeEscapedTokensAndNestedDefinitions() {
        val schema = Json.parse("""{"type":"object","additionalProperties":false,
            "properties":{"value":{"${'$'}ref":"#/${'$'}defs/a~1b/${'$'}defs/c~0d"}},
            "${'$'}defs":{"a/b":{"${'$'}defs":{"c~d":{"type":"integer"}}}}}""")
        assertTrue(CapabilitySchemaValidator.accepts(schema,Json.parse("""{"value":1}""")))
        assertFalse(CapabilitySchemaValidator.accepts(schema,Json.parse("""{"value":"bad"}""")))
    }
    @Test fun referencesThatNeverConsumeDataFailClosedWithoutAnUnboundedRecursion() {
        val schema = Json.parse("""{"type":"object","additionalProperties":false,
            "properties":{"value":{"${'$'}ref":"#/${'$'}defs/a"}},
            "${'$'}defs":{"a":{"${'$'}ref":"#/${'$'}defs/b"},"b":{"${'$'}ref":"#/${'$'}defs/a"}}}""")
        assertFalse(CapabilitySchemaValidator.accepts(schema,Json.parse("""{"value":1}""")))
    }
}
