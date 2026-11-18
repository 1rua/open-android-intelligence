package com.openandroidintelligence.gateway.schema

import org.junit.Assert.*
import org.junit.Test

/** 中性 JSON、规范摘要与能力校验仍独立于任何服务契约保持真实回归。 */
class NeutralJsonTest {
    @Test fun `排序和Unicode正文规范摘要稳定`() {
        val first = Json.parse("""{"z":2,"a":"中文","nested":{"b":false,"a":null}}""")
        val second = Json.parse("""{"nested":{"a":null,"b":false},"a":"中文","z":2}""")
        assertEquals(CanonicalJson.canonical(first), CanonicalJson.canonical(second))
        assertEquals(CanonicalJson.sha256(first), CanonicalJson.sha256(second))
        assertEquals("中文", JsonFields.string(JsonFields.obj(first), "a"))
    }
    @Test fun `畸形JSON不会当成有效能力参数`() {
        for (text in listOf("{", "[1,]", "{\"value\":NaN}", "{\"value\":Infinity}")) assertTrue(runCatching { Json.parse(text) }.isFailure)
        val schema = Json.parse("""{"type":"object","properties":{"limit":{"type":"integer","minimum":1,"maximum":5}},"required":["limit"],"additionalProperties":false}""")
        assertTrue(CapabilitySchemaValidator.accepts(schema, Json.parse("""{"limit":3}""")))
        for (text in listOf("""{"limit":0}""", """{"limit":1.5}""", """{"limit":3,"private":true}"""))
            assertFalse(CapabilitySchemaValidator.accepts(schema, Json.parse(text)))
    }
}
