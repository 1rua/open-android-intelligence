package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import org.junit.Assert.*
import org.junit.Test

/** 固定上游的合法事件顺序：历史只能补正文，实时执行与下一轮内容各自隔离。 */
class RemoteTimelineProjectionTest {
    private val native = "session:原始"
    private val local = RemoteLocalIds.encode(native)
    private fun acp(kind: String, content: String? = null, extra: List<Pair<String, Any?>> = emptyList()): JsonValue =
        obj("method" to "session/update", "params" to obj("sessionId" to native,
            "update" to obj(*(listOf("sessionUpdate" to kind) + listOfNotNull(content?.let { "content" to obj("type" to "text", "text" to it) }) + extra).toTypedArray())))
    private fun event(seq: Int, kind: String, data: JsonValue): JsonValue = obj("type" to "event", "event" to obj("seq" to seq,
        "type" to kind, "time" to 100, "data" to data))
    private fun text(projection: RemoteTimelineProjection): String = projection.rows.values.joinToString("\n") {
        it.parts.filterIsInstance<MessagePart.Text>().joinToString("") { part -> part.value }
    }

    @Test fun `Codex实时轮次之后补旧历史不改变当前执行身份或状态`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("method" to "turn/started", "params" to obj("threadId" to native, "turn" to obj("id" to "T2"))))
        projection.accept(event(1, "turn/start", obj("turn" to 1)), history = true)
        projection.accept(event(2, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "completed"))), history = true)
        assertEquals("T2", projection.generationId)
        assertEquals(GenerationState.RUNNING, projection.generationState)
        assertNull(projection.terminalGeneration)
    }

    @Test fun `新一轮真实turn不能继承上一轮终态`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("method" to "turn/started", "params" to obj("threadId" to native,
            "turn" to obj("id" to "T1"))))
        projection.accept(obj("method" to "turn/completed", "params" to obj("threadId" to native,
            "turn" to obj("id" to "T1", "status" to "completed"))))
        projection.accept(obj("method" to "turn/started", "params" to obj("threadId" to native,
            "turn" to obj("id" to "T2"))))
        assertEquals("T2", projection.generationId)
        assertEquals(GenerationState.RUNNING, projection.generationState)
    }

    @Test fun `ACP第二轮仅终态追赶内容仍显示且不带入上一轮正文`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request:first")
        projection.accept(acp("agent_message_chunk", "第一轮"))
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn")))
        projection.beginTurn("request:second")
        val completed = projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn", "catchUp" to listOf(acp("agent_message_chunk", "第二轮")))))
        assertEquals(listOf("第二轮"), completed.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().map { it.message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { part -> part.value } }.distinct())
        assertEquals("第一轮\n第二轮", text(projection))
    }

    @Test fun `ACP同轮已有实时正文时终态追赶副本不能重复追加`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request:first")
        projection.accept(acp("agent_message_chunk", "正文"))
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn", "catchUp" to listOf(acp("agent_message_chunk", "正文")))))
        assertEquals("正文", text(projection))
    }

    @Test fun `ACP同一用户消息的分片更新同一气泡并保留完整正文`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request")
        val first = projection.accept(acp("user_message_chunk", "问题前半", listOf("messageId" to "user-message")))
            .filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single()
        val second = projection.accept(acp("user_message_chunk", "问题后半", listOf("messageId" to "user-message")))
            .filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single()

        assertEquals(first.message.id, second.message.id)
        assertEquals(1, projection.rows.size)
        assertEquals("问题前半问题后半", text(projection))
        assertEquals("user", second.message.sender)
        assertTrue(second.revision > first.revision)
    }

    @Test fun `ACP不同用户消息标识保持独立且后续分片回到原消息`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(acp("user_message_chunk", "甲前", listOf("messageId" to "user-a")))
        projection.accept(acp("user_message_chunk", "乙", listOf("messageId" to "user-b")))
        projection.accept(acp("user_message_chunk", "甲后", listOf("messageId" to "user-a")))

        assertEquals(2, projection.rows.size)
        assertEquals(listOf("user", "user"), projection.rows.values.map { it.sender })
        assertEquals("甲前甲后\n乙", text(projection))
    }

    @Test fun `ACP交错的用户和助手分片分别累加到各自消息`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request")
        projection.accept(acp("user_message_chunk", "用户前", listOf("messageId" to "user-message")))
        projection.accept(acp("agent_message_chunk", "助手前", listOf("messageId" to "assistant-message")))
        projection.accept(acp("user_message_chunk", "用户后", listOf("messageId" to "user-message")))
        projection.accept(acp("agent_message_chunk", "助手后", listOf("messageId" to "assistant-message")))

        assertEquals(2, projection.rows.size)
        assertEquals(listOf("user", "assistant"), projection.rows.values.map { it.sender })
        assertEquals("用户前用户后\n助手前助手后", text(projection))
    }

    @Test fun `ACP缺少或空消息标识时保持独立用户消息`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(acp("user_message_chunk", "无标识"))
        projection.accept(acp("user_message_chunk", "空标识", listOf("messageId" to null)))
        projection.accept(acp("user_message_chunk", "空串甲", listOf("messageId" to "")))
        projection.accept(acp("user_message_chunk", "空串乙", listOf("messageId" to "")))
        projection.accept(acp("user_message_chunk", "空白甲", listOf("messageId" to " \t ")))
        projection.accept(acp("user_message_chunk", "空白乙", listOf("messageId" to " \t ")))

        assertEquals(6, projection.rows.size)
        assertEquals("无标识\n空标识\n空串甲\n空串乙\n空白甲\n空白乙", text(projection))
    }

    @Test fun `ACP交错用户分片保留消息首次出现时间与显示顺序`() {
        var time = 1_000L
        val projection = RemoteTimelineProjection(local, clock = { time })
        projection.accept(acp("user_message_chunk", "甲前", listOf("messageId" to "user-a")))
        time = 2_000L
        projection.accept(acp("user_message_chunk", "乙", listOf("messageId" to "user-b")))
        time = 3_000L
        val updated = projection.accept(acp("user_message_chunk", "甲后", listOf("messageId" to "user-a")))
            .filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single()

        assertEquals(listOf(1_000L, 2_000L), projection.rows.values.map { it.timestamp })
        assertEquals("更新事件仍保留本次接收时间", 3_000L, updated.occurredAt)
        assertEquals(listOf("甲前甲后", "乙"), projection.rows.values.sortedBy { it.timestamp }.map {
            it.parts.filterIsInstance<MessagePart.Text>().joinToString("") { part -> part.value }
        })
    }

    @Test fun `ACP终态补帧不将已有用户消息移动到助手回复之后`() {
        var time = 1_000L
        val projection = RemoteTimelineProjection(local, clock = { time })
        projection.beginTurn("request")
        val user = acp("user_message_chunk", "问题", listOf("messageId" to "user-message"))
        val answer = acp("agent_message_chunk", "回答")
        projection.accept(user)
        time = 2_000L
        projection.accept(answer)
        time = 3_000L
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn", "catchUp" to listOf(user, answer))))

        assertEquals(listOf("user", "assistant"), projection.rows.values.sortedBy { it.timestamp }.map { it.sender })
        assertEquals(1_000L, projection.rows.values.single { it.sender == "user" }.timestamp)
    }

    @Test fun `ACP终态追赶不重复同标识用户正文且保留助手补帧`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request")
        val user = acp("user_message_chunk", "问题", listOf("messageId" to "user-message"))
        projection.accept(user)
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn", "catchUp" to listOf(
            user, acp("agent_message_chunk", "回答"),
        ))))

        assertEquals(2, projection.rows.size)
        assertEquals("问题\n回答", text(projection))
        assertEquals(listOf("user", "assistant"), projection.rows.values.map { it.sender })
    }

    @Test fun `ACP已有助手实时正文时仍补齐同标识用户缺失分片`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request")
        val userStart = acp("user_message_chunk", "问题前半", listOf("messageId" to "user-message"))
        val answer = acp("agent_message_chunk", "回答")
        projection.accept(userStart)
        projection.accept(answer)
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn", "catchUp" to listOf(
            userStart, acp("user_message_chunk", "问题后半", listOf("messageId" to "user-message")), answer,
        ))))

        assertEquals(2, projection.rows.size)
        assertEquals("问题前半问题后半\n回答", text(projection))
    }

    @Test fun `ACP追赶补齐同标识用户先前漏收的前后分片`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request")
        val middle = acp("user_message_chunk", "中段", listOf("messageId" to "user-message"))
        val answer = acp("agent_message_chunk", "回答")
        projection.accept(middle)
        projection.accept(answer)
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn", "catchUp" to listOf(
            acp("user_message_chunk", "前缀", listOf("messageId" to "user-message")),
            middle,
            acp("user_message_chunk", "后缀", listOf("messageId" to "user-message")), answer,
        ))))

        assertEquals(2, projection.rows.size)
        assertEquals("前缀中段后缀\n回答", text(projection))
    }

    @Test fun `ACP追赶中交错的不同用户消息分别恢复完整正文`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request")
        val userStart = acp("user_message_chunk", "甲前", listOf("messageId" to "user-a"))
        projection.accept(userStart)
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn", "catchUp" to listOf(
            userStart,
            acp("user_message_chunk", "乙", listOf("messageId" to "user-b")),
            acp("user_message_chunk", "甲后", listOf("messageId" to "user-a")),
            acp("agent_message_chunk", "回答"),
        ))))

        assertEquals(3, projection.rows.size)
        assertEquals("甲前甲后\n乙\n回答", text(projection))
    }

    @Test fun `ACP追赶中无标识用户消息不凭相同正文合并`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request")
        val user = acp("user_message_chunk", "相同正文")
        projection.accept(user)
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn", "catchUp" to listOf(
            user, acp("agent_message_chunk", "回答"),
        ))))

        assertEquals(3, projection.rows.size)
        assertEquals("相同正文\n相同正文\n回答", text(projection))
    }

    @Test fun `ACP追赶被截断时保留已收到的同标识用户前缀`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request")
        projection.accept(acp("user_message_chunk", "已知前缀", listOf("messageId" to "user-message")))
        val middle = acp("user_message_chunk", "已知中段", listOf("messageId" to "user-message"))
        projection.accept(middle)
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn", "catchUp" to listOf(
            middle,
            acp("user_message_chunk", "晚到尾段", listOf("messageId" to "user-message")),
            acp("agent_message_chunk", "回答"),
        ))))

        assertEquals(2, projection.rows.size)
        assertEquals("已知前缀已知中段\n回答", text(projection))
    }

    @Test fun `Harness思考流与历史思考块使用既有思考卡片结构`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "attempt", "turn" to 1)))
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "attempt",
            "chunk" to obj("type" to "reasoning-delta", "index" to 0, "text" to "正在推理"))))
        assertEquals("正在推理", projection.rows.values.single().reasoning.single().text)
        assertFalse(projection.rows.values.single().reasoning.single().complete)
        assertEquals("", text(projection))
        projection.accept(event(1, "assistant/message", obj("message" to obj("id" to "history", "content" to listOf(
            obj("type" to "reasoning", "text" to "历史推理"), obj("type" to "text", "text" to "答案"))))), history = true)
        assertEquals("历史推理", projection.rows.getValue("history").reasoning.single().text)
        assertTrue(projection.rows.getValue("history").reasoning.single().complete)
        assertEquals("答案", projection.rows.getValue("history").parts.filterIsInstance<MessagePart.Text>().single().value)
    }

    @Test fun `ACP和Codex原生思考增量保留在现有思考卡片`() {
        val acpProjection = RemoteTimelineProjection(local)
        acpProjection.beginTurn("request")
        acpProjection.accept(acp("agent_thought_chunk", "先想"))
        acpProjection.accept(acp("agent_message_chunk", "回答"))
        assertEquals("先想", acpProjection.rows.values.single().reasoning.single().text)
        assertTrue(acpProjection.rows.values.single().reasoning.single().complete)
        assertEquals("回答", text(acpProjection))
        val codex = RemoteTimelineProjection(local)
        codex.accept(obj("method" to "turn/started", "params" to obj("threadId" to native, "turn" to obj("id" to "T"))))
        codex.accept(obj("method" to "item/reasoning/summaryTextDelta", "params" to obj("threadId" to native, "itemId" to "reasoning", "summaryIndex" to 0, "delta" to "摘要")))
        codex.accept(obj("method" to "item/reasoning/textDelta", "params" to obj("threadId" to native, "itemId" to "reasoning", "contentIndex" to 0, "delta" to "细节")))
        assertEquals(listOf("摘要", "细节"), codex.rows.values.single().reasoning.map { it.text })
        assertEquals("", text(codex))
    }

    @Test fun `历史正文保留原发生时间并明确排除实时提醒`() {
        val projection = RemoteTimelineProjection(local)
        val historical = projection.accept(event(1, "assistant/message", obj("message" to obj("id" to "old", "content" to listOf(obj("type" to "text", "text" to "旧回答"))))), history = true)
            .filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single()
        assertEquals(100L, historical.occurredAt)
        assertTrue(historical.eventId.startsWith("history:"))
    }

    @Test fun `取消或失败的思考保持未闭合而成功终态明确闭合`() {
        listOf("cancelled" to false, "failed" to false, "completed" to true).forEach { (status, complete) ->
            val projection = RemoteTimelineProjection(local)
            projection.accept(obj("method" to "turn/started", "params" to obj("threadId" to native, "turn" to obj("id" to "T"))))
            projection.accept(obj("method" to "item/reasoning/textDelta", "params" to obj("threadId" to native, "itemId" to "reasoning", "delta" to "尚在推理")))
            projection.accept(obj("method" to "turn/completed", "params" to obj("threadId" to native, "turn" to obj("id" to "T", "status" to status))))
            assertEquals(status, complete, projection.rows.values.single().reasoning.single().complete)
            assertEquals("CONFIRMED", projection.rows.values.single().state)
        }
    }

    // ==========================================
    // Feature 1: 通用工具调用反序列化 (Tier 1 & Tier 2)
    // ==========================================

    @Test
    fun t1_F1_01_ToolName_Extraction() {
        val candidates = listOf(
            obj("toolName" to "search_engine") to "search_engine",
            obj("name" to "bash_runner") to "bash_runner",
            obj("tool" to "calc") to "calc",
            obj("title" to "Fetch User Info") to "Fetch User Info",
            obj("function" to obj("name" to "get_weather")) to "get_weather",
            obj("type" to "web_fetch") to "web_fetch",
            obj("unrelated" to "value") to "tool",
        )
        for ((data, expectedToolName) in candidates) {
            val projection = RemoteTimelineProjection(local)
            val events = projection.accept(event(1, "tool/call", data))
            val stepEvent = events.filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single()
            assertEquals("工具名称必须准确按候选键顺序提取", expectedToolName, stepEvent.steps.single().toolName)
        }
    }

    @Test
    fun t1_F1_02_Input_Args_Object_And_Array() {
        val projection = RemoteTimelineProjection(local)

        // 1. input 为 JObject
        val objCall = event(1, "tool/call", obj("name" to "api_post", "input" to obj("query" to "android", "limit" to 10)))
        val objStep = projection.accept(objCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("""{"limit":10,"query":"android"}""", objStep.arguments)

        // 2. args 为 JArray
        val arrCall = event(2, "tool/call", obj("name" to "exec", "args" to listOf("ls", "-la")))
        val arrStep = projection.accept(arrCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("""["ls","-la"]""", arrStep.arguments)

        // 3. command 为字符串
        val cmdCall = event(3, "tool/call", obj("name" to "shell", "command" to "cat /etc/hosts"))
        val cmdStep = projection.accept(cmdCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("cat /etc/hosts", cmdStep.arguments)

        // 4. parameters 为 JObject
        val paramCall = event(4, "tool/call", obj("name" to "sql", "parameters" to obj("table" to "users")))
        val paramStep = projection.accept(paramCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("""{"table":"users"}""", paramStep.arguments)
    }

    @Test
    fun t1_F1_03_Output_Result_Extraction() {
        val projection = RemoteTimelineProjection(local)

        // 1. result 为 JObject 结构化对象
        val resultCall = event(1, "tool/result", obj("name" to "api", "result" to obj("code" to 200, "status" to "success")))
        val resultStep = projection.accept(resultCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("""{"code":200,"status":"success"}""", resultStep.output)
        assertEquals(NativeExecutionStatus.COMPLETED, resultStep.status)

        // 2. output 为字符串
        val outputCall = event(2, "tool/result", obj("name" to "ping", "output" to "64 bytes from 127.0.0.1"))
        val outputStep = projection.accept(outputCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("64 bytes from 127.0.0.1", outputStep.output)

        // 3. content 为字符串
        val contentCall = event(3, "tool/result", obj("name" to "read", "content" to "file payload data"))
        val contentStep = projection.accept(contentCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("file payload data", contentStep.output)

        // 4. response 为字符串
        val responseCall = event(4, "tool/result", obj("name" to "query", "response" to "OK"))
        val responseStep = projection.accept(responseCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("OK", responseStep.output)
    }

    @Test
    fun t1_F1_04_No_Params_Hide_Label() {
        val projection = RemoteTimelineProjection(local)
        val noParamCall = event(1, "tool/call", obj("name" to "get_device_id"))
        val step = projection.accept(noParamCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("无参数调用时arguments必须为空字符串而非null字符串", "", step.arguments)
    }

    @Test
    fun t2_F1_01_Execution_Status_Variants() {
        val projection = RemoteTimelineProjection(local)

        // 未完成: RUNNING
        val runningCall = event(1, "tool/call", obj("name" to "long_task"))
        val runningStep = projection.accept(runningCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals(NativeExecutionStatus.RUNNING, runningStep.status)

        // isError 标记为 true: FAILED
        val errorCall = event(2, "tool/result", obj("name" to "task", "isError" to true, "output" to "disk full"))
        val errorStep = projection.accept(errorCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals(NativeExecutionStatus.FAILED, errorStep.status)

        // status 为 failed: FAILED
        val failedCall = event(3, "tool/result", obj("name" to "task", "status" to "failed", "output" to "timed out"))
        val failedStep = projection.accept(failedCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals(NativeExecutionStatus.FAILED, failedStep.status)

        // 成功完成: COMPLETED
        val completedCall = event(4, "tool/result", obj("name" to "task", "status" to "completed", "output" to "all done"))
        val completedStep = projection.accept(completedCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals(NativeExecutionStatus.COMPLETED, completedStep.status)
    }

    @Test
    fun t2_F1_02_Deeply_Nested_Arguments() {
        val projection = RemoteTimelineProjection(local)
        val deeplyNested = obj("config" to obj("database" to obj("host" to "localhost", "port" to 5432)))
        val call = event(1, "tool/call", obj("name" to "db_connect", "input" to deeplyNested))
        val step = projection.accept(call).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("""{"config":{"database":{"host":"localhost","port":5432}}}""", step.arguments)
    }

    // ==========================================
    // Feature 2: 思维链流式增量打通 (Tier 1 & Tier 2)
    // ==========================================

    @Test
    fun t1_F2_01_ACP_Thought_Chunk_Streaming() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("req-thought-1")

        projection.accept(acp("agent_thought_chunk", "思考片段A-"))
        val row1 = projection.rows.values.single()
        assertEquals("思考片段A-", row1.reasoning.single().text)
        assertFalse("流式推流中未完成思考complete必须为false", row1.reasoning.single().complete)

        projection.accept(acp("agent_thought_chunk", "思考片段B"))
        val row2 = projection.rows.values.single()
        assertEquals("思考片段A-思考片段B", row2.reasoning.single().text)
        assertFalse(row2.reasoning.single().complete)
    }

    @Test
    fun t1_F2_02_Interleaved_Thought_And_Text_Body_Offset() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("req-interleave")

        // Block 0: Text (长度 5)
        projection.accept(acp("agent_message_chunk", "Hello"))
        // Block 1: Thought (offset 应为 5)
        projection.accept(acp("agent_thought_chunk", "Thinking..."))
        // Block 2: Text (追加正文)
        projection.accept(acp("agent_message_chunk", " World"))

        val row = projection.rows.values.single()
        val reasoning = row.reasoning.single()
        assertEquals("Thinking...", reasoning.text)
        assertEquals(5, reasoning.bodyOffset)
    }

    @Test
    fun t1_F2_03_Live_Stream_Presentation_Origin() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "att-origin", "turn" to 1)))
        val upserts = projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "att-origin",
            "chunk" to obj("type" to "reasoning-delta", "index" to 0, "text" to "正在进行深度思考"))))
        val event = upserts.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single()
        assertEquals("实时思考流式推送必须标记为 LIVE", StreamPresentationOrigin.LIVE, event.presentationOrigin)
    }

    // ==========================================
    // Feature 3: 数据流综合脱敏与拦截 (Tier 1 & Tier 2)
    // ==========================================

    @Test
    fun t1_F6_01_Live_Stream_System_Reminder_Stripped() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "att-strip", "turn" to 1)))
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "att-strip",
            "chunk" to obj("type" to "text-delta", "index" to 0, "text" to "正常回答前缀<system-reminder>网关注入未闭合"))))

        val textValue = projection.rows.values.single().parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
        assertEquals("实时流中的未闭合系统提示词必须即时剥离", "正常回答前缀", textValue)
    }

    @Test
    fun t1_F6_02_Pure_Reminder_Message_Dropped() {
        val projection = RemoteTimelineProjection(local)
        val events = projection.accept(event(1, "assistant/message", obj("message" to obj("id" to "msg-reminder-only", "content" to listOf(
            obj("type" to "text", "text" to "<system-reminder>Only Prompt Context</system-reminder>"),
        )))))
        assertTrue("纯提示词消息脱敏后为空，rows必须为空", projection.rows.isEmpty())
        assertTrue("纯提示词消息绝不能派发TimelineUpsert事件", events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().isEmpty())
    }

    @Test
    fun t1_F6_03_Meta_Message_Dropped() {
        val projection = RemoteTimelineProjection(local)
        val events = projection.accept(event(1, "assistant/message", obj("message" to obj("id" to "meta-dropped", "isMeta" to true, "content" to listOf(
            obj("type" to "text", "text" to "元数据状态通知"),
        )))))
        assertTrue("isMeta消息无论有无正文均直接丢弃不上屏", projection.rows.isEmpty())
        assertTrue("isMeta消息绝不能派发TimelineUpsert事件", events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().isEmpty())
    }

    @Test
    fun `OpenAI风格带type为function时function_name优先于type提取`() {
        val projection = RemoteTimelineProjection(local)
        val openAiCall = event(1, "tool/call", obj(
            "type" to "function",
            "function" to obj("name" to "read_file", "arguments" to obj("path" to "src/Main.kt", "line" to 42))
        ))
        val step = projection.accept(openAiCall).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("read_file", step.toolName)
        assertEquals("""{"line":42,"path":"src/Main.kt"}""", step.arguments)
        assertEquals(NativeExecutionStatus.RUNNING, step.status)
    }

    @Test
    fun `Codex跨chunk未闭合提示词累加脱敏`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("method" to "turn/started", "params" to obj("threadId" to native, "turn" to obj("id" to "T_codex"))))

        // Chunk 1: 正常前缀 + 未闭合开标签前缀（流式态暂缓展示半截注入标签，杜绝闪烁）
        projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native, "itemId" to "msg1", "delta" to "你好世界<system-")))
        val text1 = projection.rows.getValue("msg1").parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
        assertEquals("你好世界", text1)

        // Chunk 2: 闭合标签后缀及内部指令 + 正常后续
        projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native, "itemId" to "msg1", "delta" to "reminder>机密注入</system-reminder>，有什么可以帮助你？")))
        val text2 = projection.rows.getValue("msg1").parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
        assertEquals("你好世界，有什么可以帮助你？", text2)
    }

    @Test
    fun `thinking与thought别名流式增量进入思考模型`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "att_thinking", "turn" to 1)))
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "att_thinking",
            "chunk" to obj("type" to "block-start", "index" to 0, "blockType" to "thinking"))))
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "att_thinking",
            "chunk" to obj("type" to "thinking-delta", "index" to 0, "text" to "正在深度推理中"))))

        val row = projection.rows.values.single()
        assertEquals(1, row.reasoning.size)
        assertEquals("正在深度推理中", row.reasoning.single().text)
        assertFalse(row.reasoning.single().complete)
    }

    @Test
    fun `纯思考阶段消息虽然正文为空但绝不丢弃`() {
        val projection = RemoteTimelineProjection(local)
        val events = projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "att_pure_reasoning", "turn" to 1)))
        val upserts = projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "att_pure_reasoning",
            "chunk" to obj("type" to "reasoning-delta", "index" to 0, "text" to "纯思考内容"))))

        assertEquals(1, projection.rows.size)
        assertTrue(projection.rows.values.single().reasoning.isNotEmpty())
        assertEquals(1, upserts.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().size)
    }

    @Test
    fun t2_F6_01_Assistant_Message_Outer_Data_IsMeta_Dropped() {
        val projection = RemoteTimelineProjection(local)
        val events = projection.accept(
            event(
                1,
                "assistant/message",
                obj(
                    "isMeta" to true,
                    "message" to obj(
                        "id" to "a_outer_meta",
                        "content" to listOf(obj("type" to "text", "text" to "外层 data.isMeta=true 的助手消息不应上屏")),
                    ),
                ),
            ),
        )
        assertTrue("assistant/message 外层 data.isMeta=true 时 rows 必须为空", projection.rows.isEmpty())
        assertTrue(
            "assistant/message 外层 data.isMeta=true 时绝不能派发 TimelineUpsert",
            events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().isEmpty(),
        )
    }

    @Test
    fun t2_F6_02_Assistant_Message_Existing_Row_Flipped_To_Outer_Data_IsMeta_Emits_Tombstone() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(
            event(
                1,
                "assistant/message",
                obj(
                    "message" to obj(
                        "id" to "a_flip_meta",
                        "content" to listOf(obj("type" to "text", "text" to "初始可见消息")),
                    ),
                ),
            ),
        )
        assertEquals(1, projection.rows.size)

        val flipEvents = projection.accept(
            event(
                2,
                "assistant/message",
                obj(
                    "isMeta" to true,
                    "message" to obj(
                        "id" to "a_flip_meta",
                        "content" to listOf(obj("type" to "text", "text" to "初始可见消息")),
                    ),
                ),
            ),
        )
        assertTrue("外层 data.isMeta 翻转为 true 后必须移除旧行", projection.rows.isEmpty())
        val tombstone = flipEvents.filterIsInstance<VerifiedConversationEvent.TimelineTombstoned>().single()
        assertEquals("a_flip_meta", tombstone.messageId)
    }
}
