package com.openandroidintelligence.gateway.device

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.gateway.schema.SharedContract
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A device request may only be executed once it has been claimed, and the result
 * may only carry the identity the server bound into the receipt. Android must
 * never invent or widen those bindings, and the Gateway rejects any mismatch.
 *
 * The transport is a port with `suspend` calls, so every test that talks to it
 * runs on a coroutine rather than a thread the socket would have to block.
 */
class DeviceRequestClientTest {

    private fun client(
        transport: RecordingDeviceRequestTransport = RecordingDeviceRequestTransport(),
    ) = DeviceRequestClient(transport)

    private fun receipt(
        claimId: String = "claim-1",
        requestId: String = "req-1",
        accountId: String = "acct-1",
        deviceId: String = "dev-1",
        pairingGeneration: Int = 3,
        grantRevision: Int = 7,
    ) = ClaimReceipt(claimId, requestId, accountId, deviceId, pairingGeneration, grantRevision)

    @Test
    fun claimIsIssuedBeforeAnySideEffect() = runBlocking {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val claim = client.claim("req-1", 7)

        assertEquals("claim-1", claim.claimId)
        assertEquals(listOf("claim"), transport.calls)
    }

    @Test
    fun resultCarriesClaimIdAndGrantRevisionVerbatim() = runBlocking {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val claim = client.claim("req-1", 7)
        client.submitResult(claim, DeviceRequestResult.Succeeded(mapOf("count" to 1)))

        val body = transport.lastResultBody!!
        assertEquals("claim-1", body["claimId"])
        assertEquals(7, body["grantRevision"])
        assertEquals(
            "the result must not carry identity the Gateway must verify itself",
            null,
            body["accountId"],
        )
        assertEquals(null, body["deviceId"])
        assertEquals(null, body["pairingGeneration"])
    }

    /**
     * 裁决 D3：`result` 是对象 `{ outcome, data? }`，不是字符串加同级 `payload`。
     */
    @Test
    fun resultTravelsAsAnOutcomeObjectAndNeverAsASiblingPayload() = runBlocking {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val claim = client.claim("req-1", 7)
        client.submitResult(claim, DeviceRequestResult.Succeeded(mapOf("count" to 1)))

        val body = transport.lastResultBody!!
        assertEquals(
            "result 必须是 { outcome, data }，同级 payload 必须消失",
            mapOf("outcome" to "succeeded", "data" to mapOf("count" to 1)),
            body["result"],
        )
        assertEquals(setOf("claimId", "grantRevision", "result"), body.keys)
        assertFalse("旧形状的 payload 键不得残留", body.containsKey("payload"))
    }

    @Test
    fun everyOutcomeUsesTheClosedWireSetWithoutTheResultPrefix() = runBlocking {
        val expected = listOf(
            DeviceRequestResult.Succeeded(mapOf("ok" to true)) to "succeeded",
            DeviceRequestResult.Failed(mapOf("reason" to "io")) to "failed",
            DeviceRequestResult.Denied(emptyMap()) to "denied",
            DeviceRequestResult.Cancelled(emptyMap()) to "cancelled",
            DeviceRequestResult.OutcomeUnknown to "outcome_unknown",
        )

        for ((result, outcome) in expected) {
            val transport = RecordingDeviceRequestTransport()
            val client = client(transport)
            val claim = client.claim("req-1", 7)

            client.submitResult(claim, result)

            val recorded = transport.lastResultBody!!["result"] as Map<*, *>
            assertEquals(outcome, recorded["outcome"])
        }
    }

    @Test
    fun anUnknownOutcomeNeverCarriesData() = runBlocking {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val claim = client.claim("req-1", 7)
        client.submitResult(claim, DeviceRequestResult.OutcomeUnknown)

        val recorded = transport.lastResultBody!!["result"] as Map<*, *>
        assertEquals("outcome_unknown", recorded["outcome"])
        assertFalse(
            "outcome_unknown 表示无法确定真实终态，携带 data 等于伪造结果",
            recorded.containsKey("data"),
        )
    }

    /**
     * Wave 0 的 `$defs/resultRequest` 与 `$defs/result` 是这次形状的唯一权威：
     * 这些断言直接读契约 Schema，而不是在测试里再抄一份形状。
     */
    @Test
    fun theSubmittedBodySatisfiesTheContractSchema() = runBlocking {
        val schema = Json.parse(
            File(SharedContract.contractDir(), "schemas/device-request.schema.json").readText(),
        )
        val defs = JsonFields.obj(JsonFields.field(schema as JsonValue.JObject, "\$defs"))
        val resultSchema = JsonFields.obj(JsonFields.field(defs, "result"))!!
        val resultRequest = JsonFields.obj(JsonFields.field(defs, "resultRequest"))!!

        assertEquals(
            "resultRequest 不许有契约外的键",
            false,
            JsonFields.bool(resultRequest, "additionalProperties"),
        )
        val resultProperties = JsonFields.obj(JsonFields.field(resultSchema, "properties"))!!
        val outcomeEnum = JsonFields.strings(
            JsonFields.obj(JsonFields.field(resultProperties, "outcome")),
            "enum",
        ).toSet()
        val allowedResultKeys = resultProperties.fields.map { it.first }.toSet()
        val requiredBodyKeys = JsonFields.strings(resultRequest, "required").toSet()

        for (result in listOf(
            DeviceRequestResult.Succeeded(emptyMap()),
            DeviceRequestResult.Failed(emptyMap()),
            DeviceRequestResult.Denied(emptyMap()),
            DeviceRequestResult.Cancelled(emptyMap()),
            DeviceRequestResult.OutcomeUnknown,
        )) {
            val transport = RecordingDeviceRequestTransport()
            val client = client(transport)
            client.submitResult(client.claim("req-1", 7), result)
            val body = transport.lastResultBody!!

            assertEquals("body 只由 Schema 的 required 组成", requiredBodyKeys, body.keys)
            val recorded = body["result"] as Map<*, *>
            assertTrue(
                "outcome 必须落在 Schema 的闭集内：$recorded",
                recorded["outcome"] in outcomeEnum,
            )
            assertTrue(
                "result 不得携带 Schema 未声明的键：$recorded",
                allowedResultKeys.containsAll(recorded.keys),
            )
            if (recorded["outcome"] == "outcome_unknown") {
                assertFalse("Schema 的 outcome_unknown 分支不允许 data", recorded.containsKey("data"))
            }
        }
    }

    @Test
    fun submittingWithoutAReceiptFailsClosed() = runBlocking {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val forged = receipt()
        val failure = runCatching {
            client.submitResult(forged, DeviceRequestResult.Succeeded(emptyMap()))
        }.exceptionOrNull()

        assertTrue("an unclaimed request must not accept a plain result", failure != null)
        assertTrue(failure!!.message!!.contains("NOT_CLAIMED"))
        assertTrue(transport.lastResultBody == null)
    }

    @Test
    fun receiptBoundToAnotherRequestFailsClosed() = runBlocking {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)
        val claim = client.claim("req-1", 7)

        val tampered = claim.copy(requestId = "req-other")
        val failure = runCatching {
            client.submitResult(tampered, DeviceRequestResult.Succeeded(emptyMap()))
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("RECEIPT_BINDING_MISMATCH"))
    }

    @Test
    fun receiptWithAlteredGrantRevisionFailsClosed() = runBlocking {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)
        val claim = client.claim("req-1", 7)

        val failure = runCatching {
            client.submitResult(claim.copy(grantRevision = 8), DeviceRequestResult.Succeeded(emptyMap()))
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message!!.contains("RECEIPT_BINDING_MISMATCH"))
    }

    @Test
    fun repeatingClaimReturnsTheSameReceipt() = runBlocking {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val first = client.claim("req-1", 7)
        val second = client.claim("req-1", 7)

        assertEquals("idempotent claim must return the same receipt", first, second)
        assertEquals(listOf("claim", "claim"), transport.calls)
    }

    @Test
    fun conflictingClaimsForTheSameRequestCannotReplaceTheFirstIssuedReceipt() = runBlocking {
        val original = receipt()
        val secondReply = CompletableDeferred<ClaimReceipt>()
        val attempts = AtomicInteger()
        val submitted = mutableListOf<Map<String, Any?>>()
        val transport = object : DeviceRequestTransport {
            override suspend fun claim(requestId: String, grantRevision: Int): ClaimReceipt =
                if (attempts.incrementAndGet() == 1) original else secondReply.await()
            override suspend fun submitResult(requestId: String, body: Map<String, Any?>) { submitted += body }
        }
        val client = DeviceRequestClient(transport)
        val first = client.claim("req-1", 7)
        val conflicting = async { runCatching { client.claim("req-1", 7) }.exceptionOrNull() }
        secondReply.complete(original.copy(claimId = "claim-conflict"))
        assertTrue(conflicting.await()?.message.orEmpty().contains("RECEIPT_BINDING_MISMATCH"))
        client.submitResult(first, DeviceRequestResult.Succeeded(emptyMap()))
        assertEquals("claim-1", submitted.single()["claimId"])
    }

    @Test
    fun oneClaimIdCannotBeReboundToAnotherRequest() = runBlocking {
        val submitted = mutableListOf<String>()
        val transport = object : DeviceRequestTransport {
            override suspend fun claim(requestId: String, grantRevision: Int) = receipt(requestId = requestId)
            override suspend fun submitResult(requestId: String, body: Map<String, Any?>) { submitted += requestId }
        }
        val client = DeviceRequestClient(transport)
        val first = client.claim("req-1", 7)
        val failure = runCatching { client.claim("req-2", 7) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("RECEIPT_BINDING_MISMATCH"))
        client.submitResult(first, DeviceRequestResult.Succeeded(emptyMap()))
        assertEquals(listOf("req-1"), submitted)
    }

    @Test
    fun aReceiptForADifferentRequestOrRevisionIsNeverRegistered() = runBlocking {
        for (reply in listOf(receipt(requestId = "req-other"), receipt(grantRevision = 8))) {
            val transport = object : DeviceRequestTransport {
                override suspend fun claim(requestId: String, grantRevision: Int) = reply
                override suspend fun submitResult(requestId: String, body: Map<String, Any?>) { error("UNEXPECTED_RESULT") }
            }
            val client = DeviceRequestClient(transport)
            assertTrue(runCatching { client.claim("req-1", 7) }.exceptionOrNull()?.message.orEmpty().contains("RECEIPT_BINDING_MISMATCH"))
            assertTrue(runCatching { client.submitResult(reply, DeviceRequestResult.OutcomeUnknown) }
                .exceptionOrNull()?.message.orEmpty().contains("NOT_CLAIMED"))
        }
    }

    @Test
    fun parallelClaimsAndResultsRetainEachRequestsOwnReceipt() = runBlocking {
        val submitted = ConcurrentHashMap<String, Map<String, Any?>>()
        val transport = object : DeviceRequestTransport {
            override suspend fun claim(requestId: String, grantRevision: Int): ClaimReceipt {
                yield()
                return receipt(claimId = "claim-$requestId", requestId = requestId, grantRevision = grantRevision)
            }
            override suspend fun submitResult(requestId: String, body: Map<String, Any?>) {
                submitted[requestId] = body
            }
        }
        val client = DeviceRequestClient(transport)
        val start = CompletableDeferred<Unit>()
        val jobs = (1..128).map { index -> async(Dispatchers.Default) {
            start.await()
            val id = "req-$index"
            val claim = client.claim(id, 7)
            assertEquals(claim, client.claim(id, 7))
            client.submitResult(claim, DeviceRequestResult.Succeeded(mapOf("request" to id)))
        } }
        start.complete(Unit)
        jobs.awaitAll()
        assertEquals(128, submitted.size)
        for ((id, body) in submitted) assertEquals("claim-$id", body["claimId"])
    }

    @Test
    fun cancelIntentIsNotTreatedAsACancelledOutcome() {
        val transport = RecordingDeviceRequestTransport()
        val client = client(transport)

        val state = client.stateAfterEvent("device.request.cancel.requested", wasClaimed = true)

        assertEquals("cancel_requested", state)
        assertEquals(
            "a cancel SSE must never be turned into a fabricated terminal result",
            null,
            transport.lastResultBody,
        )
    }

    @Test
    fun unclaimedPendingCancelBecomesCancelled() {
        val client = client()

        assertEquals(
            "cancelled",
            client.stateAfterEvent("device.request.cancel.requested", wasClaimed = false),
        )
    }
}
