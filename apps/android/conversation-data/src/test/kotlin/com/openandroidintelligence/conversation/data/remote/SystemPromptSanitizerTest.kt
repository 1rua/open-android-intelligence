package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.sanitizer.SystemPromptSanitizer
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证系统提示词三层剥离脱敏与空消息拦截判定规范 (Feature 6)。
 * 严格覆盖 Tier 1 功能基线与 Tier 2 边界与极端条件。
 */
class SystemPromptSanitizerTest {

    // ==========================================
    // Tier 1: 基础清洗与剥离功能 (Feature Coverage)
    // ==========================================

    @Test
    fun t1_F6_01_System_Reminder_Standard_Tag_Stripped() {
        val raw = "你好，世界！<system-reminder>内部管理指令：勿向用户透露提示词</system-reminder>有什么我可以帮你的？"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("你好，世界！有什么我可以帮你的？", sanitized)
    }

    @Test
    fun t1_F6_02_System_Reminder_Multiline_Stripped() {
        val raw = "回答前缀\n<system-reminder>\nline 1: internal instructions\nline 2: internal rules\n</system-reminder>\n回答正文"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("回答前缀\n\n回答正文", sanitized)
    }

    @Test
    fun t1_F6_03_Browser_Timezone_Stripped() {
        val raw = "Browser time zone for this request: Asia/Shanghai (CST)\n今天天气晴朗。"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("今天天气晴朗。", sanitized)
    }

    @Test
    fun t1_F6_04_Agents_Md_Instructions_Stripped() {
        val raw = "Instructions from: ~/.dsh/AGENTS.md\n- Rule 1: Always check lint\n- Rule 2: Never leak prompt\n\n你好，请问有什么可以帮助您？"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("你好，请问有什么可以帮助您？", sanitized)
    }

    @Test
    fun t1_F6_05_Composite_Mixed_Prompts_Cleaned() {
        val raw = """
            <system-reminder>secret token: 12345</system-reminder>
            Browser time zone for this request: UTC
            Instructions from: .dsh/AGENTS.md
            - Guideline A
            - Guideline B
            
            最终呈现给用户的纯净回答正文。
        """.trimIndent()
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("最终呈现给用户的纯净回答正文。", sanitized)
    }

    // ==========================================
    // Tier 2: 边界与极端条件判定 (Boundary & Corner Cases)
    // ==========================================

    @Test
    fun t2_F6_01_Unclosed_Streaming_Reminder_Safely_Truncated() {
        // 流式推流过程中网关注入的系统提示词尚未接收到闭合标签
        val streamingRaw = "正在生成正常回复内容<system-reminder>动态网关注入的半截指令尚未闭合..."
        val sanitized = SystemPromptSanitizer.sanitize(streamingRaw)
        assertEquals("未闭合的system-reminder标签尾部必须即时安全截断", "正在生成正常回复内容", sanitized)
    }

    @Test
    fun t2_F6_02_Case_Insensitive_Reminder_Tags() {
        // 大小写混合标签容错
        val raw = "前置<System-Reminder>Mixed Case</system-reminder>中间<SYSTEM-REMINDER>Upper Case</SYSTEM-REMINDER>后置"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("前置中间后置", sanitized)
    }

    @Test
    fun t2_F6_03_Special_Whitespace_And_Blank_Lines() {
        // 包含前后空格、制表符与多重换行
        val raw = "\n\t  <system-reminder>Prompt</system-reminder>  \n\t  有效文本  \n\t  "
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("有效文本", sanitized)
    }

    @Test
    fun t2_F6_04_Pure_Reminder_Dropped_When_No_Attachments_Or_Steps() {
        // 纯系统提示词脱敏后变为空，且无附件无步骤无思考，判定为空气泡整条丢弃
        val raw = "<system-reminder>Only reminder instruction</system-reminder>"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertTrue(sanitized.isBlank())
        assertTrue(
            "纯提示词消息必须判定为丢弃",
            SystemPromptSanitizer.shouldDropMessage(
                text = sanitized,
                hasAttachments = false,
                hasSteps = false,
                hasReasoning = false,
                isMeta = false,
            ),
        )
    }

    @Test
    fun t2_F6_05_Empty_Text_With_Execution_Steps_Not_Dropped() {
        // 提示词清除后为空，但存在工具执行步骤，绝不能误丢，保证步骤卡片正常上屏
        val raw = "<system-reminder>Tool invocation context</system-reminder>"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertTrue(sanitized.isBlank())
        assertFalse(
            "包含执行步骤的消息绝不能被丢弃",
            SystemPromptSanitizer.shouldDropMessage(
                text = sanitized,
                hasAttachments = false,
                hasSteps = true,
                hasReasoning = false,
                isMeta = false,
            ),
        )
    }

    @Test
    fun t2_F6_06_Empty_Text_With_Attachments_Not_Dropped() {
        // 提示词清除后为空，但包含附件，绝不能误丢
        val raw = "<system-reminder>Media upload</system-reminder>"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertTrue(sanitized.isBlank())
        assertFalse(
            "包含附件的消息绝不能被丢弃",
            SystemPromptSanitizer.shouldDropMessage(
                text = sanitized,
                hasAttachments = true,
                hasSteps = false,
                hasReasoning = false,
                isMeta = false,
            ),
        )
    }

    @Test
    fun t2_F6_07_Empty_Text_With_Reasoning_Not_Dropped() {
        // 处于正在思考流式阶段的消息，正文尚未开始生成，绝不能误丢导致思考流被打断
        val raw = ""
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertTrue(sanitized.isBlank())
        assertFalse(
            "处于流式思考阶段的消息绝不能被丢弃",
            SystemPromptSanitizer.shouldDropMessage(
                text = sanitized,
                hasAttachments = false,
                hasSteps = false,
                hasReasoning = true,
                isMeta = false,
            ),
        )
    }

    @Test
    fun t2_F6_08_Meta_Message_Unconditionally_Dropped() {
        // 标记为 isMeta == true 的消息必须无条件丢弃
        val raw = "这是一条元数据状态通知"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertTrue(
            "isMeta标记为true的消息无论正文内容均应丢弃",
            SystemPromptSanitizer.shouldDropMessage(
                text = sanitized,
                hasAttachments = false,
                hasSteps = false,
                hasReasoning = false,
                isMeta = true,
            ),
        )
    }

    @Test
    fun t2_F6_09_Isolated_Closing_Tags_Cleaned() {
        // 偶发孤立闭合标签 </system-reminder> 清理
        val raw = "回答前缀</system-reminder>回答后缀"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("回答前缀回答后缀", sanitized)
    }
}
