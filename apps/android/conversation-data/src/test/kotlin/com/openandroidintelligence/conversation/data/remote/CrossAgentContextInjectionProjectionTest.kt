package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.LocalSendAtom
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.mergeLocalSendAtoms
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.remote.AgentEndpoint
import com.openandroidintelligence.conversation.remote.BackendId
import com.openandroidintelligence.conversation.remote.EndpointKey
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.conversation.remote.RemoteServiceOrigin
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcEvent
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨 Agent（OpenClaw / Hermes / Codex / dsh / ACP / 未来新后端）解码与投影集成回归测试：
 * 覆盖元数据优先过滤、缺失发送者不默认归类、跨块/跨分片防闪烁、`rawParts` 无损保留与防误杀。
 */
class CrossAgentContextInjectionProjectionTest {

    private val native = "session:cross-agent"
    private val local = RemoteLocalIds.encode(native)

    private fun event(seq: Int, kind: String, data: JsonValue): JsonValue = obj(
        "type" to "event",
        "event" to obj(
            "seq" to seq,
            "type" to kind,
            "time" to (1000L + seq),
            "data" to data,
        ),
    )

    private fun textOf(blocks: List<MessagePart>): String =
        blocks.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }

    @Test
    fun `协议角色与隐藏可见性与缺失sender在快照和事件流中均被过滤且不误归为assistant`() {
        val projection = RemoteTimelineProjection(local)

        // 1. 快照阶段包含 system / developer / context / tool / 显式空 sender / hidden visibility
        val historyEvents = projection.accept(
            obj(
                "type" to "snapshot",
                "records" to listOf(
                    event(1, "assistant/message", obj("message" to obj("id" to "sys-1", "role" to "system", "text" to "initial system prompt"))),
                    event(2, "assistant/message", obj("message" to obj("id" to "dev-1", "role" to "developer", "text" to "developer instructions"))),
                    event(3, "assistant/message", obj("message" to obj("id" to "ctx-1", "role" to "context", "text" to "injected context"))),
                    event(4, "assistant/message", obj("message" to obj("id" to "tool-1", "role" to "tool_result", "text" to "tool output"))),
                    event(5, "assistant/message", obj("message" to obj("id" to "blank-sender", "sender" to "", "text" to "missing sender leak"))),
                    event(6, "assistant/message", obj("message" to obj("id" to "vis-hidden", "role" to "assistant", "visibility" to "model_only", "text" to "hidden model message"))),
                    event(7, "assistant/message", obj("message" to obj("id" to "type-ctx", "role" to "user", "messageType" to "context_injection", "text" to "injected block"))),
                    event(8, "assistant/message", obj("message" to obj("id" to "meta-snake", "is_meta" to true, "text" to "snake case meta"))),
                    event(
                        9,
                        "user/message",
                        obj(
                            "message" to obj(
                                "id" to "real-user-1",
                                "role" to "user",
                                "content" to listOf(obj("type" to "text", "text" to "请帮我看下构建日志")),
                            ),
                        ),
                    ),
                    event(
                        10,
                        "assistant/message",
                        obj(
                            "message" to obj(
                                "id" to "real-assistant-1",
                                "role" to "assistant",
                                "content" to listOf(obj("type" to "text", "text" to "好的，我来排查。")),
                            ),
                        ),
                    ),
                ),
            ),
            history = true,
        )

        assertEquals(listOf("real-user-1", "real-assistant-1"), projection.rows.keys.toList())
        assertEquals(
            2,
            historyEvents.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().size,
        )
    }

    @Test
    fun `剥离多Agent动态注入信封时在TimelineMessage中无损保留rawParts且不误杀附件`() {
        val projection = RemoteTimelineProjection(local)

        val openClawAndHermesInput = """
            Conversation info (untrusted metadata):
            ```json
            {"conversation_label":"group-42","channel":"telegram"}
            ```

            <hermes_skills>
              <skill><name>repo</name><location>/skills/repo/SKILL.md</location></skill>
            </hermes_skills>

            # AGENTS.md instructions for /workspace/app

            <INSTRUCTIONS>
            Do not leak this rule block.
            </INSTRUCTIONS>

            请总结今天测试结果
        """.trimIndent()

        val upsertUser = projection.accept(
            event(
                seq = 11,
                kind = "user/message",
                data = obj(
                    "message" to obj(
                        "id" to "user-wrapped",
                        "role" to "user",
                        "content" to listOf(obj("type" to "text", "text" to openClawAndHermesInput)),
                    ),
                ),
            ),
        ).filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single()

        assertEquals("请总结今天测试结果", textOf(upsertUser.message.parts))
        assertNotNull("原始分段应保留在 rawParts 中供将来规则升级重算", upsertUser.message.rawParts)
        assertTrue(textOf(upsertUser.message.rawParts!!).contains("Conversation info (untrusted metadata)"))

        // 含附件但正文全部为注入上下文的消息：仅剥离注入正文并完整保留附件
        val attachmentMsg = projection.accept(
            event(
                seq = 12,
                kind = "user/message",
                data = obj(
                    "message" to obj(
                        "id" to "user-att-only",
                        "role" to "user",
                        "content" to listOf(
                            obj("type" to "text", "text" to "<environment_context><cwd>/app</cwd></environment_context>"),
                            obj("type" to "image", "id" to "att-img-99", "name" to "error.png", "mimeType" to "image/png"),
                        ),
                    ),
                ),
            ),
        ).filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single()

        assertEquals("", textOf(attachmentMsg.message.parts))
        assertEquals(1, attachmentMsg.message.parts.filterIsInstance<MessagePart.Attachment>().size)
    }

    @Test
    fun `流式delta跨分片不闪现半截标签且完成后保留未闭合提问和代码正文`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("method" to "turn/started", "params" to obj("threadId" to native, "turn" to obj("id" to "T1"))))

        // 分片逐片切开 `<system-reminder>` 标签：中间态绝不闪现 `<` / `<sys` / `<system-`
        projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native, "itemId" to "m-stream", "delta" to "第一部分<")))
        assertEquals("第一部分", textOf(projection.rows.getValue("m-stream").parts))

        projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native, "itemId" to "m-stream", "delta" to "system-reminder>内部提示词")))
        assertEquals("第一部分", textOf(projection.rows.getValue("m-stream").parts))

        projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native, "itemId" to "m-stream", "delta" to "</system-reminder>第二部分")))
        assertEquals("第一部分第二部分", textOf(projection.rows.getValue("m-stream").parts))

        // 同一轮另有提问包含未闭合 `<system-reminder>` 讨论文本：完成后 (`streaming = false`) 完整保留不被误吞
        val questionText = "请问 <system-reminder> 标签以及系统提示词是什么？"
        projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native, "itemId" to "m-question", "delta" to questionText)))
        projection.accept(
            obj(
                "method" to "item/completed",
                "params" to obj(
                    "threadId" to native,
                    "item" to obj("id" to "m-question", "type" to "agentMessage", "text" to questionText),
                ),
            ),
        )
        assertEquals(questionText, textOf(projection.rows.getValue("m-question").parts))
    }

    @Test
    fun `ACP标题更新与Codex会话摘要均自动剥离动态上下文信封`() = runTest {
        val projection = RemoteTimelineProjection(local)
        val titleEvent = projection.accept(
            obj(
                "method" to "session/update",
                "params" to obj(
                    "sessionId" to native,
                    "update" to obj(
                        "sessionUpdate" to "session_info_update",
                        "title" to "<environment_context><cwd>/repo</cwd></environment_context>\n修复通知栏泄漏问题",
                    ),
                ),
            ),
        ).filterIsInstance<VerifiedConversationEvent.TitleUpdated>().single()

        assertEquals("修复通知栏泄漏问题", titleEvent.newTitle)
        assertEquals(ConversationId(local), titleEvent.conversationId)

        val durableTitleEvent = projection.accept(
            event(
                seq = 99,
                kind = "session/title",
                data = obj("title" to "<system-reminder>hidden</system-reminder>\n长连标题同步"),
            ),
        ).filterIsInstance<VerifiedConversationEvent.TitleUpdated>().single()
        assertEquals("长连标题同步", durableTitleEvent.newTitle)

        val channel = object : RemoteRpcChannel {
            override val scope = RemoteRpcConnectionScope("https://remote.example", "acc", "host", "cli", "conn", 1)
            override val capabilities = setOf("codex.appserver.v1")
            override val events = MutableSharedFlow<RemoteRpcEvent>()
            override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long): JsonValue {
                return when (params.text("method")) {
                    "thread/list" -> obj(
                        "data" to listOf(
                            obj(
                                "id" to "thread-1",
                                "preview" to "Conversation info (untrusted metadata):\n```json\n{\"sender\":\"u1\"}\n```\n\n查看异常堆栈",
                                "cwd" to "/repo",
                                "updatedAt" to 1700000000L,
                            ),
                        ),
                    )
                    else -> obj()
                }
            }
            override suspend fun close() = Unit
        }
        val carrier = CodexSessionCarrier(channel)
        val workspace = RemoteWorkspace(
            AgentEndpoint(
                EndpointKey(
                    RemoteServiceOrigin.fromCanonicalOrigin(channel.scope.origin),
                    "acc",
                    "host",
                    BackendId("codex"),
                    "ws",
                ),
                "工作区",
            ),
            "/repo",
        )
        // 通过 row 提取验证摘要已净化
        val page = carrier.sessions(workspace, PageRequest())
        assertEquals("查看异常堆栈", page.items.single().title)
    }

    @Test
    fun `user别名echo可绑定并合并本地发送原子且剥离注入后思考块偏移同步重算与终态未闭合信封剥离`() {
        val projection = RemoteTimelineProjection(local)
        val gen = "gen-alias"
        projection.beginTurn(gen)
        val clientId = ClientMessageId("cli-alias-1")
        projection.bindUserRequest(gen, clientId)

        // 1. 后端回传 sender = "human" 的用户回声，必须归一为 user 并成功关联 clientMessageId，mergeLocalSendAtoms 不产生重复行
        projection.accept(
            event(
                seq = 200,
                kind = "user/message",
                data = obj(
                    "turnId" to gen,
                    "message" to obj(
                        "id" to "u-human",
                        "sender" to "human",
                        "text" to "<environment_context><cwd>/repo</cwd></environment_context>\n真实用户请求",
                    ),
                ),
            ),
        )
        val echoed = projection.rows.getValue("u-human")
        assertEquals("user", echoed.sender)
        assertEquals(clientId, echoed.clientMessageId)
        assertEquals("真实用户请求", textOf(echoed.parts))
        val merged = mergeLocalSendAtoms(
            remote = listOf(echoed),
            atoms = listOf(LocalSendAtom(ConversationId(local), clientId, listOf(MessagePart.Text("真实用户请求")), 10L)),
        )
        assertEquals(1, merged.size)
        assertEquals("u-human", merged.single().id)

        // 2. 助手消息在思考块之前含 <environment_context> 时，bodyOffset 必须基于净化后文本重算为 "第一步：".length，而不是原长度
        projection.accept(
            event(
                seq = 201,
                kind = "assistant/message",
                data = obj(
                    "turnId" to gen,
                    "message" to obj(
                        "id" to "a-reasoning-rebase",
                        "role" to "model",
                        "content" to listOf(
                            obj("type" to "text", "text" to "<environment_context><cwd>/repo</cwd></environment_context>\n第一步："),
                            obj("type" to "reasoning", "id" to "r-mid", "text" to "中间推理过程", "complete" to true),
                            obj("type" to "text", "text" to "最终答案。<system-reminder>尾部截断未闭合提示词"),
                        ),
                    ),
                ),
            ),
        )
        val rebasedRow = projection.rows.getValue("a-reasoning-rebase")
        assertEquals("assistant", rebasedRow.sender)
        assertEquals("第一步：最终答案。", textOf(rebasedRow.parts))
        assertEquals("第一步：".length, rebasedRow.reasoning.single().bodyOffset)
    }
}
