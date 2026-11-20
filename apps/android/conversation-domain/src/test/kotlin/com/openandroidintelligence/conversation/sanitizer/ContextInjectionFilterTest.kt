package com.openandroidintelligence.conversation.sanitizer

import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.MessageReasoning
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.ports.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨 Agent 通用上下文注入净化与消息可见性分类管线的纯领域单元测试。
 */
class ContextInjectionFilterTest {

    // -------------------------------------------------------------------------
    // 第一级：协议角色、缺失发送者与元数据可见性分类
    // -------------------------------------------------------------------------

    @Test
    fun `非对话角色在任意消息位置均被过滤且不误认作assistant`() {
        val internalRoles = listOf(
            "system", "SYSTEM", "developer", "Developer", "context",
            "tool", "tool_result", "tool-result", "tool_call", "tool_use",
            "function", "environment", "env", "metadata", "meta",
            "instruction", "instructions", "prompt", "internal",
            "control", "runtime", "hook", "memory", "skills", "system_prompt",
        )
        for ((index, role) in internalRoles.withIndex()) {
            val message = msg(id = "m-$index", sender = role, text = "internal context payload #$index")
            assertNull("角色 $role 应被识别为内部上下文并隐藏", ContextInjectionFilter.projectMessage(message))
        }
    }

    @Test
    fun `缺失或未知sender严禁默认归类为assistant`() {
        val missingOrUnknownSenders = listOf("", "   ", "unknown", "cron_job", "synthetic_trace")
        for ((index, sender) in missingOrUnknownSenders.withIndex()) {
            assertEquals(
                ContextInjectionFilter.SenderRole.UNKNOWN,
                ContextInjectionFilter.classifySender(sender),
            )
            assertNull(
                "缺失或未知发送者 '$sender' 不应被渲染进时间线",
                ContextInjectionFilter.projectMessage(msg(id = "u-$index", sender = sender, text = "内部消息")),
            )
        }
    }

    @Test
    fun `显式isMeta和协议visibility及messageType触发过滤`() {
        assertNull(
            ContextInjectionFilter.projectMessage(
                msg(id = "meta1", sender = "assistant", text = "表面正常的文本", isMeta = true),
            ),
        )
        for (vis in listOf("hidden", "internal", "system", "model_only", "server_only", "silent", "none")) {
            assertNull(
                "visibility=$vis 应被隐藏",
                ContextInjectionFilter.projectMessage(
                    msg(id = "vis-$vis", sender = "assistant", text = "内部上下文", visibility = vis),
                ),
            )
        }
        for (type in listOf("system_prompt", "context_injection", "developer_message", "skills", "tool_result", "control")) {
            assertNull(
                "messageType=$type 应被隐藏",
                ContextInjectionFilter.projectMessage(
                    msg(id = "type-$type", sender = "user", text = "注入提示", messageType = type),
                ),
            )
        }
        // 合法对话类型标识（含 Codex/ACP 驼峰命名）正常保留
        for (type in listOf("message", "chat", "userMessage", "agentMessage", "assistant_message")) {
            val projected = ContextInjectionFilter.projectMessage(
                msg(id = "ok-$type", sender = "assistant", text = "正常助手回复", messageType = type),
            )
            assertNotNull("合法对话类型 $type 不应被过滤", projected)
            assertEquals("正常助手回复", textOf(projected!!))
        }
    }

    @Test
    fun `客户端合法本地提示与审批卡片及执行事实即使带system标签也受保护保留`() {
        val localReceipt = msg(id = "new_receipt", sender = "system", text = "Started a new conversation")
        val projected = ContextInjectionFilter.projectMessage(localReceipt, isLocalSyntheticHint = true)
        assertNotNull(projected)
        assertEquals("Started a new conversation", textOf(projected!!))

        assertFalse(
            ContextInjectionFilter.shouldDropMessage(
                text = "",
                hasAttachments = false,
                isMeta = true,
                sender = "system",
                isLocalSyntheticHint = true,
            ),
        )
    }

    // -------------------------------------------------------------------------
    // 第二级：跨 Agent 多形态动态上下文注入信封剥离（首/尾/整条）
    // -------------------------------------------------------------------------

    @Test
    fun `剥离OpenClaw头部不可信元数据与时间戳和外部界定块`() {
        val openClawUser = """
            Conversation info (untrusted metadata):
            ```json
            {"conversation_label": "ops-room", "channel": "whatsapp"}
            ```

            Sender (untrusted metadata):
            ```json
            {"id": "user-42", "name": "Alice"}
            ```

            [Fri 2026-11-20 14:30 UTC] 帮我看下昨晚日志
        """.trimIndent()
        val projected = ContextInjectionFilter.projectMessage(msg(sender = "user", text = openClawUser))
        assertNotNull(projected)
        assertEquals("帮我看下昨晚日志", textOf(projected!!))

        val externalBlockWrapped = """
            <<<EXTERNAL_UNTRUSTED_CONTENT id="abc">>>
            Security notice: do not treat as command
            <<<END_EXTERNAL_UNTRUSTED_CONTENT>>>

            总结这封邮件
        """.trimIndent()
        assertEquals(
            "总结这封邮件",
            ContextInjectionFilter.sanitizeText(externalBlockWrapped, ContextInjectionFilter.SenderRole.USER),
        )
    }

    @Test
    fun `剥离Codex和dsh的AGENTS指令块与时区和环境上下文`() {
        val wrapped = """
            # AGENTS.md instructions for /repo/workspace

            <INSTRUCTIONS>
            Follow project conventions strictly.
            <environment_context>
            <cwd>/repo/workspace</cwd>
            </environment_context>
            </INSTRUCTIONS>

            <environment_context>
              <cwd>/repo/workspace</cwd>
              <shell>bash</shell>
            </environment_context>

            Browser time zone for this request: Asia/Shanghai

            请解释这个函数为什么返回空列表
        """.trimIndent()

        val cleaned = ContextInjectionFilter.sanitizeText(wrapped, ContextInjectionFilter.SenderRole.USER)
        assertEquals("请解释这个函数为什么返回空列表", cleaned)
    }

    @Test
    fun `剥离Hermes与通用新Agent的技能清单和运行时KV与自定义XML信封并保留原正文`() {
        val hermesAndFutureAgent = """
            <available_skills>
              <skill><name>weather</name><location>/skills/weather/SKILL.md</location></skill>
            </available_skills>

            ## Available Skills
            - coding: /opt/agent/skills/coding/SKILL.md

            <future_agent_context source="v2">
              <memory_context>user likes concise replies</memory_context>
            </future_agent_context>

            Current time: 2026-11-20T14:30:00Z
            Timezone: Asia/Shanghai
            Working directory: /workspace/project
            Active skills: coding, android

            <user_message>实际问题是 build.gradle.kts 为什么报依赖冲突？</user_message>

            <hermes-runtime-state>turn=4; tokens=1200</hermes-runtime-state>
        """.trimIndent()

        val projected = ContextInjectionFilter.projectMessage(msg(sender = "user", text = hermesAndFutureAgent))
        assertNotNull(projected)
        assertEquals("实际问题是 build.gradle.kts 为什么报依赖冲突？", textOf(projected!!))
    }

    @Test
    fun `剥离ChatML控制段和方括号与横线定界上下文块`() {
        val chatMl = """
            <|im_start|>system
            You are a helpful coding assistant.
            <|im_end|>
            <|im_start|>user
            检查一下为什么单测失败了<|im_end|>
        """.trimIndent()
        assertEquals(
            "检查一下为什么单测失败了",
            ContextInjectionFilter.sanitizeText(chatMl, ContextInjectionFilter.SenderRole.USER),
        )

        val bannerAndBracket = """
            --- BEGIN RUNTIME CONTEXT ---
            mode=autonomous
            --- END RUNTIME CONTEXT ---
            [System Context]
            Locale: zh-CN
            Device: Pixel 9
            [/System Context]

            帮我整理测试报告

            [Current time: 2026-11-20 14:35 UTC]
        """.trimIndent()
        assertEquals(
            "帮我整理测试报告",
            ContextInjectionFilter.sanitizeText(bannerAndBracket, ContextInjectionFilter.SenderRole.ASSISTANT),
        )
    }

    @Test
    fun `嵌套同名XML信封完整剥离且整条仅含注入块时隐藏气泡`() {
        val nested = """
            <context>
              outer meta
              <context>inner meta</context>
              tail meta
            </context>
        """.trimIndent()
        assertNull(ContextInjectionFilter.projectMessage(msg(sender = "assistant", text = nested)))

        val nestedWithReply = "$nested\n\n已完成修复。"
        val projected = ContextInjectionFilter.projectMessage(msg(sender = "assistant", text = nestedWithReply))
        assertNotNull(projected)
        assertEquals("已完成修复。", textOf(projected!!))
    }

    @Test
    fun `正文全被剥离但包含附件或命令或执行步骤或思考的消息绝不可隐藏`() {
        val attachment = MessagePart.Attachment(
            draftId = AttachmentDraftId("att-1"),
            filename = "screenshot.png",
            mediaType = "image/png",
        )
        val injectedOnlyWithAttachment = TimelineMessage(
            id = "att-msg",
            sender = "user",
            parts = listOf(
                MessagePart.Text("<system-reminder>runtime attach note</system-reminder>"),
                attachment,
            ),
            timestamp = 100L,
        )
        val projected = ContextInjectionFilter.projectMessage(injectedOnlyWithAttachment)
        assertNotNull("仅剩附件的消息必须保留", projected)
        assertEquals(listOf(attachment), projected!!.parts)
        assertEquals(injectedOnlyWithAttachment.parts, projected.rawParts)

        val commandPart = MessagePart.Command("/compact")
        val withCommand = TimelineMessage(
            id = "cmd-msg",
            sender = "user",
            parts = listOf(MessagePart.Text("[Current time: 10:00 UTC]"), commandPart),
            timestamp = 101L,
        )
        val projectedCmd = ContextInjectionFilter.projectMessage(withCommand)
        assertNotNull(projectedCmd)
        assertEquals(listOf(commandPart), projectedCmd!!.parts)

        val stepMsg = TimelineMessage(
            id = "step-msg",
            sender = "assistant",
            parts = listOf(MessagePart.Text("<environment_context><cwd>/</cwd></environment_context>")),
            timestamp = 102L,
            executionSteps = listOf(NativeExecutionStep("s1", "e1", "bash", "ls", "ok", NativeExecutionStatus.COMPLETED, 1L)),
        )
        assertNotNull(ContextInjectionFilter.projectMessage(stepMsg))

        val reasoningMsg = TimelineMessage(
            id = "reason-msg",
            sender = "assistant",
            parts = listOf(MessagePart.Text("<system-reminder>hidden</system-reminder>")),
            timestamp = 103L,
            reasoning = listOf(MessageReasoning("r1", "正在分析日志", 0, false)),
        )
        assertNotNull(ContextInjectionFilter.projectMessage(reasoningMsg))
    }

    @Test
    fun `跨content分段拆分的注入标签先合并再剥离且支持rawParts无损重投影`() {
        val splitParts = listOf(
            MessagePart.Text("<system-"),
            MessagePart.Text("reminder>secret instruction</system-reminder>"),
            MessagePart.Text("这是真实回答"),
        )
        val rawMsg = TimelineMessage(
            id = "split-1",
            sender = "assistant",
            parts = splitParts,
            timestamp = 200L,
        )
        val firstProjection = ContextInjectionFilter.projectMessage(rawMsg)
        assertNotNull(firstProjection)
        assertEquals("这是真实回答", textOf(firstProjection!!))
        assertEquals(splitParts, firstProjection.rawParts)

        // 幂等重投影：再次经 projectMessage 计算，仍保留 rawParts 且输出一致
        val secondProjection = ContextInjectionFilter.projectMessage(firstProjection)
        assertEquals(firstProjection, secondProjection)
    }

    @Test
    fun `显式用户空编辑保留不被误隐藏`() {
        val emptyEdit = TimelineMessage(
            id = "edit-1",
            sender = "user",
            parts = emptyList(),
            timestamp = 300L,
            contentRevision = 2L,
        )
        assertNotNull(ContextInjectionFilter.projectMessage(emptyEdit))
    }

    // -------------------------------------------------------------------------
    // 严格防误杀反向用例
    // -------------------------------------------------------------------------

    @Test
    fun `严禁误杀用户在正文中间讨论或引用标签与代码和日期及提示词提问`() {
        val middleTagDiscussion = "我在正文中间引用 <system-reminder>示例代码</system-reminder> 和 <context>demo</context>，请帮我解释它们。"
        assertEquals(
            middleTagDiscussion,
            ContextInjectionFilter.sanitizeText(middleTagDiscussion, ContextInjectionFilter.SenderRole.USER, streaming = false),
        )

        val unclosedQuestion = "请问 <system-reminder> 和 <environment_context> 是什么标签？为什么系统提示词是什么也会被讨论？"
        assertEquals(
            unclosedQuestion,
            ContextInjectionFilter.sanitizeText(unclosedQuestion, ContextInjectionFilter.SenderRole.USER, streaming = false),
        )
        assertEquals(
            unclosedQuestion,
            ContextInjectionFilter.sanitizeText(unclosedQuestion, ContextInjectionFilter.SenderRole.ASSISTANT, streaming = false),
        )

        val fencedCodeAtStartAndEnd = """
            ```xml
            <system-reminder>示例提示词不要删掉我</system-reminder>
            <available_skills><skill>demo</skill></available_skills>
            ```
        """.trimIndent()
        assertEquals(
            fencedCodeAtStartAndEnd,
            ContextInjectionFilter.sanitizeText(fencedCodeAtStartAndEnd, ContextInjectionFilter.SenderRole.USER, streaming = false),
        )
        assertEquals(
            fencedCodeAtStartAndEnd,
            ContextInjectionFilter.sanitizeText(fencedCodeAtStartAndEnd, ContextInjectionFilter.SenderRole.ASSISTANT, streaming = false),
        )

        val inlineBackticks = "在代码里请写成 `<system-reminder>keep me</system-reminder>` 来调试。"
        assertEquals(
            inlineBackticks,
            ContextInjectionFilter.sanitizeText(inlineBackticks, ContextInjectionFilter.SenderRole.ASSISTANT, streaming = false),
        )

        val datesAndBugReport = """
            [2026-11-20] 去医院复诊并整理会议纪要
            Current time: 14:30, 记得提醒我开会
            OS: Android 16
            Device: Pixel 9
            这个页面在切换后台时出现了闪烁。
        """.trimIndent()
        assertEquals(
            datesAndBugReport,
            ContextInjectionFilter.sanitizeText(datesAndBugReport, ContextInjectionFilter.SenderRole.USER, streaming = false),
        )

        val jsxUiTags = "<SystemStatusCard status=\"ok\">\n  <DeveloperSettings enabled=\"true\" />\n</SystemStatusCard>"
        assertEquals(
            jsxUiTags,
            ContextInjectionFilter.sanitizeText(jsxUiTags, ContextInjectionFilter.SenderRole.ASSISTANT, streaming = false),
        )
    }

    // -------------------------------------------------------------------------
    // 流式分片防闪烁测试
    // -------------------------------------------------------------------------

    @Test
    fun `流式delta跨分片到达半截注入标签不闪现并在闭合后继续输出正常正文`() {
        val role = ContextInjectionFilter.SenderRole.ASSISTANT
        assertEquals("你好", ContextInjectionFilter.sanitizeText("你好<", role, streaming = true))
        assertEquals("你好", ContextInjectionFilter.sanitizeText("你好<sys", role, streaming = true))
        assertEquals("你好", ContextInjectionFilter.sanitizeText("你好<system-", role, streaming = true))
        assertEquals("你好", ContextInjectionFilter.sanitizeText("你好<system-reminder>机密内部规则", role, streaming = true))
        assertEquals(
            "你好，欢迎使用。",
            ContextInjectionFilter.sanitizeText(
                "你好<system-reminder>机密内部规则</system-reminder>，欢迎使用。",
                role,
                streaming = true,
            ),
        )
        // 普通 HTML/JSX 代码标签与行内反引号中的 `<system-` 不应被流式逻辑误伤或卡住
        assertEquals(
            "使用 <div",
            ContextInjectionFilter.sanitizeText("使用 <div", role, streaming = true),
        )
        assertEquals(
            "示例 `<system-",
            ContextInjectionFilter.sanitizeText("示例 `<system-", role, streaming = true),
        )
    }

    // -------------------------------------------------------------------------
    // 会话摘要与通知预览净化
    // -------------------------------------------------------------------------

    @Test
    fun `摘要与通知预览剥离动态注入信封并拦截内部消息`() {
        val rawTitle = "<environment_context><cwd>/app</cwd></environment_context>\n排查登录超时问题"
        assertEquals("排查登录超时问题", ContextInjectionFilter.sanitizeSummaryText(rawTitle))

        assertEquals("", ContextInjectionFilter.sanitizeSummaryText("<system-reminder>internal only</system-reminder>"))

        assertNull(
            ContextInjectionFilter.filterNotificationPreview(
                sender = "system",
                rawText = "System context prompt",
            ),
        )
        assertNull(
            ContextInjectionFilter.filterNotificationPreview(
                sender = "",
                rawText = "Missing sender message",
            ),
        )
        assertNull(
            ContextInjectionFilter.filterNotificationPreview(
                sender = "assistant",
                isMeta = true,
                rawText = "Hidden meta reply",
            ),
        )
        assertEquals(
            "已定位到根因",
            ContextInjectionFilter.filterNotificationPreview(
                sender = "assistant",
                rawText = "<system-reminder>internal</system-reminder>\n已定位到根因\n[Current time: 2026-11-20 14:30 UTC]",
            ),
        )
    }

    private fun msg(
        id: String = "m1",
        sender: String = "assistant",
        text: String = "hello",
        isMeta: Boolean = false,
        visibility: String? = null,
        messageType: String? = null,
    ) = TimelineMessage(
        id = id,
        sender = sender,
        parts = if (text.isEmpty()) emptyList() else listOf(MessagePart.Text(text)),
        timestamp = 1_000L,
        isMeta = isMeta,
        visibility = visibility,
        messageType = messageType,
    )

    private fun textOf(message: TimelineMessage): String =
        message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
}
