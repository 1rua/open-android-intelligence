package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.ControlAccessBinding
import com.openandroidintelligence.control.remote.ControlRequestException
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect

/** 桌面连接代际的真实事件源；手机动作的本机确认从不经远端 waterfall 回答。 */
internal class PhoneRemoteEventSource(private val bus: PhoneControlBus) {
    private val clients = ConcurrentHashMap<String, ControlAccessBinding>()

    fun open(access: ControlAccessBinding, args: Map<String, JsonValue>): Flow<JsonValue> = flow {
        if (args.isNotEmpty()) fail("CONTROL_PARAMETERS_INVALID")
        val epoch = bus.outputEpoch(access)
        val clientId = UUID.randomUUID().toString()
        // 先建立订阅归属和当前目录，再发送 ready；后续版本变化来自执行总线，不能丢掉首次变化。
        var previous = catalog(access)
        clients[clientId] = access
        try {
            bus.requireOutput(access, epoch, metadataOnly = true)
            emit(Json.of(mapOf("type" to "ready", "clientId" to clientId, "host" to mapOf("home" to "/phone-capabilities"))))
            bus.changes.collect {
                bus.requireOutput(access, epoch, metadataOnly = true)
                val current = catalog(access)
                for ((id, row) in current) {
                    val old = previous[id]
                    if (old == null) emit(notification("api-session/added", listOf(row)))
                    else if (old != row) {
                        val running = (row.fields.firstOrNull { it.first == "running" }?.second as? JsonValue.JBool)?.value == true
                        val wasRunning = (old.fields.firstOrNull { it.first == "running" }?.second as? JsonValue.JBool)?.value == true
                        if (running != wasRunning) emit(notification("api-session/status", listOf(JsonValue.JString(id), JsonValue.JBool(running))))
                        emit(notification("api-session/added", listOf(row)))
                    }
                }
                previous.keys.filter { it !in current }.forEach { id ->
                    emit(notification("api-session/removed", listOf(JsonValue.JString(id))))
                }
                previous = current
            }
        } finally { clients.remove(clientId, access) }
    }

    fun answer(access: ControlAccessBinding, args: Map<String, JsonValue>): JsonValue {
        if (args.keys != setOf("clientId", "eventId", "outcome")) fail("CONTROL_PARAMETERS_INVALID")
        val clientId = text(args["clientId"])
        text(args["eventId"])
        val outcome = fields(args["outcome"])
        when (text(outcome["kind"])) {
            "next" -> if (outcome.keys != setOf("kind")) fail("CONTROL_PARAMETERS_INVALID")
            "result" -> if (outcome.keys != setOf("kind") && outcome.keys != setOf("kind", "value")) fail("CONTROL_PARAMETERS_INVALID")
            "rejected" -> {
                if (outcome.keys != setOf("kind", "error")) fail("CONTROL_PARAMETERS_INVALID")
                val error = fields(outcome["error"])
                if (!error.keys.containsAll(setOf("name", "message")) || !error.keys.all { it in setOf("name", "message", "code", "details") }) fail("CONTROL_PARAMETERS_INVALID")
                text(error["name"]); string(error["message"])
                error["code"]?.let(::string)
            }
            else -> fail("CONTROL_PARAMETERS_INVALID")
        }
        if (clients[clientId] != access) fail("CONTROL_EVENT_CLIENT_UNAVAILABLE")
        // 与官方 Gateway 一致，未投递到该订阅的 eventId 是幂等空结果；不能据此批准任何手机动作。
        return JsonValue.JNull
    }

    private fun catalog(access: ControlAccessBinding): Map<String, JsonValue.JObject> = bus.sessions(access).associate { value ->
        val row = value as JsonValue.JObject
        text(row.fields.firstOrNull { it.first == "sessionId" }?.second) to row
    }
    private fun notification(event: String, args: List<JsonValue>): JsonValue = Json.of(mapOf("type" to "emit", "event" to event, "args" to args))
    private fun fields(value: JsonValue?): Map<String, JsonValue> = (value as? JsonValue.JObject)?.fields?.toMap() ?: fail("CONTROL_PARAMETERS_INVALID")
    private fun text(value: JsonValue?): String = (value as? JsonValue.JString)?.value?.takeIf { it.isNotEmpty() } ?: fail("CONTROL_PARAMETERS_INVALID")
    private fun string(value: JsonValue?): String = (value as? JsonValue.JString)?.value ?: fail("CONTROL_PARAMETERS_INVALID")
    private fun fail(code: String): Nothing = throw ControlRequestException(code)
}
