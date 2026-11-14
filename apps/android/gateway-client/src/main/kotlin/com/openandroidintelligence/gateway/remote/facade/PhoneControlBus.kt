package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 当前进程唯一手机执行总线。Session 门面与 MCP 最终进入同一账本和内核执行器。 */
class PhoneControlBus(
    private val coroutineScope: CoroutineScope,
    private val ledger: ControlMetadataLedger,
    private val executor: PhoneControlExecutor,
    val processEpoch: String = UUID.randomUUID().toString(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private class Task(val scope: ControlTaskScope, val createdAt: Long) {
        val queue = Mutex()
        var frozen = false
        var turn = 0
        var activeRequestId: String? = null
        val records = mutableListOf<JsonValue>()
        val results = mutableMapOf<String, JsonValue>()
        val jobs = mutableMapOf<String, Job>()
        val access = mutableMapOf<String, ControlAccessBinding>()
        val artifacts = mutableMapOf<String, Pair<String, ControlArtifact>>()
        var revision = 0L
    }
    private val lock = Any()
    private val tasks = linkedMapOf<String, Task>()
    private var stopped = false
    private var deliveryEpoch = 0L
    private val version = MutableStateFlow(0L)
    val changes: StateFlow<Long> get() = version

    fun create(access: ControlAccessBinding, taskId: String = UUID.randomUUID().toString()): String = synchronized(lock) {
        executor.requireCurrentAccess(access)
        if (stopped) fail("LOCAL_EMERGENCY_STOP")
        require(taskId != "control-bus") { "CONTROL_TASK_INVALID" }
        val task = Task(ControlTaskScope(access.principal, taskId, access.authorizationEpoch, processEpoch), currentTimeMillis())
        // 持久墓碑只绑定拥有者与任务标识；进程重启不能以旧 ID 重新建立可执行任务。
        ledger.transaction { rows ->
            val tombstone = ControlRequestMetadata(access.principal.storageKey, "task:$taskId", taskId,
                "session.create", "sha256:" + "0".repeat(64), ControlRequestState.COMPLETED, ControlExecutionStatus.SUCCEEDED)
            if (rows.containsKey(tombstone.key)) fail("CONTROL_TASK_CONFLICT")
            rows[tombstone.key] = tombstone
        }
        check(tasks.putIfAbsent(key(access, taskId), task) == null) { "CONTROL_TASK_CONFLICT" }
        changed(); taskId
    }

    fun sessions(access: ControlAccessBinding): List<JsonValue> = synchronized(lock) {
        executor.requireCurrentAccess(access)
        bus(access)
        tasks.values.filter { it.scope.permits(access, processEpoch) }.map { task ->
            val metadata = ledger.transaction { rows -> rows.values.filter { it.ownerKey == access.principal.storageKey && it.taskSessionId == task.scope.taskSessionId } }
            Json.of(mapOf("sessionId" to task.scope.taskSessionId, "title" to if (task.scope.taskSessionId == "control-bus") "手机控制总线" else "手机控制任务",
                "running" to metadata.any { !it.state.terminal }, "blank" to task.records.isEmpty(), "updatedAt" to task.createdAt,
                "projections" to mapOf("values" to projections(metadata))))
        }
    }

    fun capabilities(access: ControlAccessBinding): JsonValue = synchronized(lock) {
        requireOutput(access, deliveryEpoch)
        controlJson(executor.capabilities(access))
    }

    /** 排队的输出绑定本机交付代次；急停前已排队的数据不能转移到急停后的观察流。 */
    fun outputEpoch(access: ControlAccessBinding): Long = synchronized(lock) { executor.requireCurrentAccess(access); deliveryEpoch }

    fun requireOutput(access: ControlAccessBinding, expectedEpoch: Long, metadataOnly: Boolean = false) = synchronized(lock) {
        executor.requireCurrentAccess(access)
        if (deliveryEpoch != expectedEpoch || stopped && !metadataOnly) fail("LOCAL_EMERGENCY_STOP")
    }

    fun submit(access: ControlAccessBinding, taskId: String, request: ParsedControlRequest): JsonValue = synchronized(lock) {
        executor.requireCurrentAccess(access)
        val task = task(access, taskId)
        if (request.operation == ControlOperation.Capabilities) {
            val result = Json.of(mapOf("request_id" to request.requestId, "taskSessionId" to taskId, "state" to "completed",
                "execution" to mapOf("status" to "succeeded"), "result" to capabilities(access)))
            event(task, "assistant/message", mapOf("request_id" to request.requestId,
                "message" to mapOf("id" to request.requestId, "role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to Json.canonical(result)))),
                "stream" to listOf(mapOf("type" to "chunk", "time" to currentTimeMillis(), "chunk" to mapOf("type" to "text-delta", "index" to 0, "text" to Json.canonical(result))))))
            return@synchronized result
        }
        if (request.operation is ControlOperation.Status) return@synchronized status(access, task, request)
        // 管理取消绕过动作队列和手机审批；原子校验拥有者、任务及原请求。
        if (request.operation is ControlOperation.Cancel) return@synchronized cancelRequest(access, task, request)
        if (stopped) fail("LOCAL_EMERGENCY_STOP")
        if (task.frozen) fail("CONTROL_TASK_CANCELLED")
        val row = metadata(access, taskId, request)
        val accepted = ledger.transaction { rows ->
            val old = rows[row.key]
            if (old != null) { conflict(old, row); false } else { rows[row.key] = row; true }
        }
        if (!accepted) return@synchronized receipt(access, taskId, request.requestId)
        val job = coroutineScope.launch(start = CoroutineStart.LAZY) {
            task.queue.withLock { execute(access, task, request) }
        }
        task.jobs[request.requestId] = job
        task.access[request.requestId] = access
        job.start()
        receipt(access, taskId, request.requestId)
    }

    private suspend fun execute(access: ControlAccessBinding, task: Task, request: ParsedControlRequest) {
        val requestScope = ControlRequestScope(task.scope, request.requestId)
        try {
            synchronized(lock) {
                requireOutput(access, deliveryEpoch)
                val old = find(access, task.scope.taskSessionId, request.requestId)
                if (old.state.terminal) return
                task.activeRequestId = request.requestId
                event(task, "turn/start", mapOf("turn" to ++task.turn, "request_id" to request.requestId))
            }
            val result = executor.execute(PhoneExecutionContext(requestScope, access, request.parameterDigest,
                beforeDispatch = {
                    synchronized(lock) {
                        requireOutput(access, deliveryEpoch)
                        if (task.frozen) fail("CONTROL_TASK_CANCELLED")
                        ledger.transaction { rows ->
                            val old = rows.getValue("${access.principal.storageKey}:${request.requestId}")
                            if (old.state.terminal) fail("CONTROL_REQUEST_CANCELLED")
                            rows[old.key] = old.copy(state = ControlRequestState.RUNNING, executionFence = true)
                        }
                        changed()
                    }
                }, awaitingConfirmation = { waiting -> synchronized(lock) {
                    update(access, task.scope.taskSessionId, request.requestId) { old ->
                        old.copy(state = if (waiting) ControlRequestState.AWAITING_CONFIRMATION else ControlRequestState.ACCEPTED)
                    }
                    event(task, if (waiting) "approval/asked" else "approval/decided", mapOf("id" to "local-${request.requestId}",
                        "request_id" to request.requestId, "localOnly" to true))
                } }, consumeSmsSendEffect = {
                    synchronized(lock) {
                        requireOutput(access, deliveryEpoch)
                        if (task.frozen) fail("CONTROL_TASK_CANCELLED")
                        val operation = request.operation as? ControlOperation.PluginInvoke
                        if (operation?.pluginId != "org.openandroidintelligence.sms" ||
                            operation.capabilityId != "org.openandroidintelligence.sms.send@1.0.0") {
                            fail("SMS_EXECUTION_SCOPE_REQUIRED")
                        }
                        ledger.transaction { rows ->
                            val old = rows.getValue("${access.principal.storageKey}:${request.requestId}")
                            if (old.state.terminal) fail("CONTROL_REQUEST_CANCELLED")
                            // 栅栏本身就是持久消费事实；第二次回调不能把 RUNNING 再解释成新许可。
                            if (old.executionFence) fail("SMS_EFFECT_ALREADY_CONSUMED")
                            rows[old.key] = old.copy(state = ControlRequestState.RUNNING, executionFence = true)
                        }
                        changed()
                    }
                }), request.operation)
            synchronized(lock) {
                val old = find(access, task.scope.taskSessionId, request.requestId)
                if (old.state.terminal) return
                requireOutput(access, deliveryEpoch)
                if (!old.executionFence) fail("CONTROL_EXECUTION_FENCE_REQUIRED")
                val artifacts = result.artifacts.map { artifact ->
                    require(Regex("[A-Za-z0-9_-]+").matches(artifact.id)) { "CONTROL_ARTIFACT_INVALID" }
                    task.artifacts[artifact.id] = request.requestId to artifact.copy(bytes = artifact.bytes.copyOf())
                    mapOf("artifactId" to artifact.id, "path" to "artifacts/${artifact.id}", "mimeType" to artifact.mimeType,
                        "bytes" to artifact.bytes.size, "version" to artifact.version)
                }
                update(access, task.scope.taskSessionId, request.requestId) { it.copy(state = ControlRequestState.COMPLETED, execution = ControlExecutionStatus.SUCCEEDED) }
                task.results[request.requestId] = Json.of(mapOf("result" to controlJson(result.result), "artifacts" to artifacts) +
                    (result.ui?.let { mapOf("ui" to controlJson(it)) } ?: emptyMap()))
                finish(access, task, request.requestId)
            }
        } catch (failure: Exception) {
            synchronized(lock) {
                val old = find(access, task.scope.taskSessionId, request.requestId)
                if (!old.state.terminal) {
                    val code = (failure as? ControlRequestException)?.code ?: "CONTROL_EXECUTION_FAILED"
                    update(access, task.scope.taskSessionId, request.requestId) { it.copy(
                        state = if (it.executionFence) ControlRequestState.OUTCOME_UNKNOWN else if (failure is CancellationException) ControlRequestState.CANCELLED else ControlRequestState.DENIED,
                        execution = if (it.executionFence) ControlExecutionStatus.UNKNOWN else ControlExecutionStatus.NOT_STARTED, errorCode = code) }
                    finish(access, task, request.requestId)
                }
            }
        } finally { synchronized(lock) { task.jobs.remove(request.requestId); task.access.remove(request.requestId) } }
    }

    private fun cancelRequest(access: ControlAccessBinding, task: Task, request: ParsedControlRequest): JsonValue {
        val op = request.operation as ControlOperation.Cancel
        val row = metadata(access, task.scope.taskSessionId, request).copy(cancellationTarget = op.targetRequestId)
        var cancelled = false
        val target = ledger.transaction { rows ->
            rows[row.key]?.let { conflict(it, row); return@transaction rows["${row.ownerKey}:${op.targetRequestId}"] ?: fail("CONTROL_REQUEST_NOT_FOUND") }
            val target = rows["${row.ownerKey}:${op.targetRequestId}"] ?: fail("CONTROL_REQUEST_NOT_FOUND")
            if (target.taskSessionId != task.scope.taskSessionId) fail("CONTROL_REQUEST_NOT_FOUND")
            val next = if (target.state.terminal) target else target.copy(
                state = if (target.executionFence) ControlRequestState.OUTCOME_UNKNOWN else ControlRequestState.CANCELLED,
                execution = if (target.executionFence) ControlExecutionStatus.UNKNOWN else ControlExecutionStatus.NOT_STARTED,
                errorCode = "CONTROL_REQUEST_CANCELLED")
            cancelled = next != target
            rows[next.key] = next
            rows[row.key] = row.copy(state = ControlRequestState.COMPLETED, execution = ControlExecutionStatus.SUCCEEDED)
            next
        }
        if (cancelled) {
            executor.cancel(ControlRequestScope(task.scope, op.targetRequestId))
            task.jobs[op.targetRequestId]?.cancel()
            finish(access, task, op.targetRequestId)
        }
        task.results[request.requestId] = Json.of(mapOf("result" to minimal(target, task.results.containsKey(target.requestId))))
        finish(access, task, request.requestId)
        return receipt(access, task.scope.taskSessionId, request.requestId)
    }

    private fun status(access: ControlAccessBinding, task: Task, request: ParsedControlRequest): JsonValue {
        if (task.scope.taskSessionId != "control-bus") fail("CONTROL_STATUS_REQUIRES_BUS")
        val op = request.operation as ControlOperation.Status
        val old = find(access, op.targetTaskSessionId, op.targetRequestId)
        val active = tasks[key(access, op.targetTaskSessionId)]
        val result = Json.of(mapOf("request_id" to request.requestId, "taskSessionId" to "control-bus", "state" to "completed",
            "execution" to mapOf("status" to "succeeded"), "result" to minimal(old, !stopped && active?.results?.containsKey(old.requestId) == true)))
        // 查询自身不再递归写持久历史，但须可由原生 Session 读取该结构化答案。
        event(task, "assistant/message", mapOf("request_id" to request.requestId,
            "message" to mapOf("id" to request.requestId, "role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to Json.canonical(result)))),
            "stream" to listOf(mapOf("type" to "text", "text" to Json.canonical(result)))))
        return result
    }

    fun cancelTask(access: ControlAccessBinding, taskId: String): JsonValue = synchronized(lock) {
        executor.requireCurrentAccess(access)
        if (taskId == "control-bus") fail("CONTROL_BUS_CANNOT_CANCEL")
        val task = task(access, taskId)
        task.frozen = true
        val pending = ledger.transaction { rows -> rows.values.filter { it.ownerKey == access.principal.storageKey && it.taskSessionId == taskId && !it.state.terminal }.map { old ->
            old.copy(state = if (old.executionFence) ControlRequestState.OUTCOME_UNKNOWN else ControlRequestState.CANCELLED,
                execution = if (old.executionFence) ControlExecutionStatus.UNKNOWN else ControlExecutionStatus.NOT_STARTED,
                errorCode = "CONTROL_TASK_CANCELLED").also { rows[it.key] = it }
        } }
        pending.forEach { item -> executor.cancel(ControlRequestScope(task.scope, item.requestId)); task.jobs[item.requestId]?.cancel(); finish(access, task, item.requestId) }
        Json.of(mapOf("accepted" to true))
    }

    /** 本机急停不依赖远端连接仍有效，先冻结全部任务并阻止尚未进入端口的请求。 */
    fun emergencyStop() = synchronized(lock) {
        if (stopped) return@synchronized
        stopped = true
        deliveryEpoch++
        val pending = ledger.transaction { rows -> rows.values.filter { !it.state.terminal }.map { old ->
            old.copy(state = if (old.executionFence) ControlRequestState.OUTCOME_UNKNOWN else ControlRequestState.CANCELLED,
                execution = if (old.executionFence) ControlExecutionStatus.UNKNOWN else ControlExecutionStatus.NOT_STARTED,
                errorCode = "LOCAL_EMERGENCY_STOP").also { rows[it.key] = it }
        } }
        tasks.values.forEach { task ->
            task.frozen = true
            task.artifacts.values.forEach { it.second.bytes.fill(0) }
            task.artifacts.clear()
            task.results.clear()
            task.records.clear()
            task.activeRequestId = null
            task.revision++
            pending.filter { it.ownerKey == task.scope.principal.storageKey && it.taskSessionId == task.scope.taskSessionId }.forEach { row ->
                executor.cancel(ControlRequestScope(task.scope, row.requestId)); task.jobs[row.requestId]?.cancel()
            }
        }
        changed()
    }

    /** 断开只结束该连接代际自己的在途请求，不影响重连后新连接提交的动作。 */
    fun connectionClosed(access: ControlAccessBinding) = synchronized(lock) {
        tasks.values.forEach { task ->
            task.access.filterValues { it == access }.keys.toList().forEach { id ->
                update(access, task.scope.taskSessionId, id) { old -> old.copy(
                    state = if (old.executionFence) ControlRequestState.OUTCOME_UNKNOWN else ControlRequestState.CANCELLED,
                    execution = if (old.executionFence) ControlExecutionStatus.UNKNOWN else ControlExecutionStatus.NOT_STARTED,
                    errorCode = "REMOTE_CONNECTION_CLOSED") }
                executor.cancel(ControlRequestScope(task.scope, id)); task.jobs[id]?.cancel()
            }
        }
        changed()
    }

    fun receipt(access: ControlAccessBinding, taskId: String, requestId: String): JsonValue = synchronized(lock) {
        executor.requireCurrentAccess(access)
        val row = find(access, taskId, requestId)
        val task = tasks[key(access, taskId)]
        val fields = minimal(row, !stopped && task?.results?.containsKey(requestId) == true).toMutableMap()
        if (!stopped) (task?.results?.get(requestId) as? JsonValue.JObject)?.fields?.forEach { fields[it.first] = it.second }
        Json.of(fields)
    }

    fun history(access: ControlAccessBinding, taskId: String, beforeSeq: Long? = null, throughSeq: Long? = null, maxMessages: Int = 50): JsonValue = synchronized(lock) {
        // 急停后只有新 control-bus 的只读状态答案可见；旧正文已清除，原任务历史继续拒绝。
        requireOutput(access, deliveryEpoch, metadataOnly = taskId == "control-bus")
        val task = task(access, taskId)
        require(maxMessages in 1..50) { "CONTROL_PAGE_LIMIT_INVALID" }
        val cursor = task.records.size.toLong() - 1
        val through = throughSeq ?: cursor
        require(through in -1..cursor && (beforeSeq == null || beforeSeq in 0..(through + 1))) { "CONTROL_CURSOR_INVALID" }
        val matching = task.records.take((through + 1).toInt()).filter { record ->
            val seq = ((record as JsonValue.JObject).fields.first { it.first == "event" }.second as JsonValue.JObject).fields.first { it.first == "seq" }.second as JsonValue.JNumber
            beforeSeq == null || seq.raw.toLong() < beforeSeq
        }
        Json.of(mapOf("type" to "snapshot", "header" to mapOf("version" to 3, "id" to taskId, "createdAt" to task.createdAt,
            "cwd" to "phone-capabilities", "isSeeded" to false), "cursor" to through, "nextTurn" to task.turn + 1, "records" to matching.takeLast(maxMessages),
            "hasMore" to (matching.size > maxMessages), "assistantStream" to mapOf("revision" to task.revision),
            "projections" to mapOf("asOfSeq" to cursor, "values" to projections(ledger.transaction { rows -> rows.values.filter { it.ownerKey == access.principal.storageKey && it.taskSessionId == taskId } }))))
    }

    fun artifact(access: ControlAccessBinding, taskId: String, artifactId: String): ControlArtifact = synchronized(lock) {
        requireOutput(access, deliveryEpoch)
        val task = task(access, taskId)
        val found = task.artifacts[artifactId]?.second ?: fail("CONTROL_ARTIFACT_UNAVAILABLE")
        found.copy(bytes = found.bytes.copyOf())
    }

    fun eventsSince(access: ControlAccessBinding, taskId: String, seq: Long): List<JsonValue> = synchronized(lock) {
        requireOutput(access, deliveryEpoch, metadataOnly = taskId == "control-bus")
        val task = task(access, taskId)
        if (seq !in -1 until task.records.size.toLong()) fail("CONTROL_CURSOR_INVALID")
        task.records.drop((seq + 1).toInt()).toList()
    }

    private fun task(access: ControlAccessBinding, id: String): Task {
        if (id == "control-bus") return bus(access)
        val task = tasks[key(access, id)] ?: fail("CONTROL_TASK_NOT_FOUND")
        if (!task.scope.permits(access, processEpoch)) fail("CONTROL_TASK_NOT_FOUND")
        return task
    }
    private fun bus(access: ControlAccessBinding): Task {
        val key = key(access, "control-bus")
        val old = tasks[key]
        if (old != null && old.scope.permits(access, processEpoch)) return old
        return Task(ControlTaskScope(access.principal, "control-bus", access.authorizationEpoch, processEpoch), currentTimeMillis()).also { tasks[key] = it }
    }
    private fun key(access: ControlAccessBinding, id: String) = "${access.principal.storageKey}:$id"
    private fun metadata(access: ControlAccessBinding, id: String, request: ParsedControlRequest) = ControlRequestMetadata(
        access.principal.storageKey, request.requestId, id, request.operation.wireName, request.parameterDigest,
        ControlRequestState.ACCEPTED, ControlExecutionStatus.NOT_STARTED)
    private fun conflict(old: ControlRequestMetadata, new: ControlRequestMetadata) {
        if (old.taskSessionId != new.taskSessionId || old.parameterDigest != new.parameterDigest || old.operation != new.operation) fail("REQUEST_ID_CONFLICT")
    }
    private fun find(access: ControlAccessBinding, taskId: String, requestId: String): ControlRequestMetadata = ledger.transaction { rows ->
        val old = rows["${access.principal.storageKey}:$requestId"] ?: fail("CONTROL_REQUEST_NOT_FOUND")
        if (old.taskSessionId != taskId) fail("CONTROL_REQUEST_NOT_FOUND")
        old
    }
    private fun update(access: ControlAccessBinding, taskId: String, requestId: String, change: (ControlRequestMetadata) -> ControlRequestMetadata) {
        ledger.transaction { rows -> val old = rows.getValue("${access.principal.storageKey}:$requestId")
            if (old.taskSessionId != taskId) fail("CONTROL_REQUEST_NOT_FOUND")
            if (!old.state.terminal) rows[old.key] = change(old)
        }; changed()
    }
    private fun finish(access: ControlAccessBinding, task: Task, id: String) {
        if (stopped || runCatching { executor.requireCurrentAccess(access) }.isFailure) return
        val complete = receipt(access, task.scope.taskSessionId, id)
        val fullText = Json.canonical(complete)
        val text = if (fullText.length <= 12_000) fullText else {
            val bytes = fullText.toByteArray()
            val artifactId = "result-$id"
            val version = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            task.artifacts[artifactId] = id to ControlArtifact(artifactId, "application/json", version, bytes)
            Json.canonical(Json.of(minimal(find(access, task.scope.taskSessionId, id), true) + mapOf("resultArtifact" to mapOf(
                "artifactId" to artifactId, "path" to "artifacts/$artifactId", "mimeType" to "application/json", "bytes" to bytes.size, "version" to version))))
        }
        val stream = listOf(mapOf("type" to "block-start", "index" to 0, "blockType" to "text"),
            mapOf("type" to "text-delta", "index" to 0, "text" to text),
            mapOf("type" to "block-end", "index" to 0, "block" to mapOf("type" to "text", "text" to text)),
            mapOf("type" to "finish", "reason" to mapOf("kind" to "stop"))).map { mapOf("type" to "chunk", "time" to currentTimeMillis(), "chunk" to it) }
        event(task, "assistant/message", mapOf("turn" to task.turn, "step" to 1,
            "message" to mapOf("id" to id, "role" to "assistant", "content" to listOf(mapOf("type" to "text", "text" to text)),
                "source" to mapOf("kind" to "tool", "callId" to id)), "stream" to stream, "request_id" to id))
        if (task.activeRequestId == id) {
            event(task, "turn/end", mapOf("turn" to task.turn, "request_id" to id))
            task.activeRequestId = null
        }
    }
    private fun event(task: Task, type: String, data: Map<String, Any?>) {
        task.records += Json.of(mapOf("type" to "event", "event" to mapOf("seq" to task.records.size, "time" to currentTimeMillis(), "type" to type, "data" to data)))
        task.revision++; changed()
    }
    private fun changed() { version.value += 1 }
    private fun executionProjection(rows: List<ControlRequestMetadata>): Map<String, Any> = mapOf(
        "status" to if (rows.any { it.state == ControlRequestState.AWAITING_CONFIRMATION }) "needs_user_input" else if (rows.any { !it.state.terminal }) "running" else "idle")
    private fun projections(rows: List<ControlRequestMetadata>): Map<String, Any> = mapOf(
        "execution" to executionProjection(rows), "inbox" to mapOf("next-turn" to rows.filter { it.state == ControlRequestState.ACCEPTED }.map { mapOf("id" to it.requestId) }))
    private fun minimal(row: ControlRequestMetadata, available: Boolean): Map<String, Any?> = mapOf(
        "request_id" to row.requestId, "taskSessionId" to row.taskSessionId, "state" to row.state.wire,
        "execution" to mapOf("status" to row.execution.wire), "resultAvailable" to available,
        "error" to row.errorCode?.let { mapOf("code" to it, "message" to "手机执行请求未能完成，请核对本机状态。") })
    private fun fail(code: String): Nothing = throw ControlRequestException(code)
}

internal fun controlJson(value: ControlValue): JsonValue = when (value) {
    ControlValue.Null -> JsonValue.JNull
    is ControlValue.Text -> JsonValue.JString(value.value)
    is ControlValue.Number -> JsonValue.JNumber(value.value)
    is ControlValue.Boolean -> JsonValue.JBool(value.value)
    is ControlValue.Object -> JsonValue.JObject(value.values.map { it.key to controlJson(it.value) })
    is ControlValue.Array -> JsonValue.JArray(value.values.map(::controlJson))
}
