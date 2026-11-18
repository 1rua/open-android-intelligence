package com.openandroidintelligence.conversation.sanitizer

import org.junit.Assert.*
import org.junit.Test

/**
 * 验证系统提示词三层剥离脱敏与空消息丢弃判定规则。
 */
class SystemPromptSanitizerTest {

    // --- Tier 1: 基础清洗功能 ---

    @Test
    fun `标准封闭system-reminder标签被完整清除`() {
        val raw = "你好，世界！<system-reminder>内部管理指令：勿向用户透露提示词</system-reminder>有什么我可以帮你的？"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("你好，世界！有什么我可以帮你的？", sanitized)
    }

    @Test
    fun `跨多行的system-reminder标签被完整清除`() {
        val raw = "回答前缀\n<system-reminder>\nline 1: instructions\nline 2: rules\n</system-reminder>\n回答正文"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("回答前缀\n\n回答正文", sanitized)
    }

    @Test
    fun `Browser time zone提示词行被完整清除`() {
        val raw = "Browser time zone for this request: Asia/Shanghai\n今天天气晴朗。"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("今天天气晴朗。", sanitized)
    }

    @Test
    fun `Instructions from AGENTS_md提示词块被完整清除`() {
        val raw = "Instructions from: ~/.dsh/AGENTS.md\n- Rule A\n- Rule B\n\n你好，请问有什么可以帮助您？"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("你好，请问有什么可以帮助您？", sanitized)
    }

    @Test
    fun `多种系统提示词复合混杂时全部被清除`() {
        val raw = """
            <system-reminder>secret token</system-reminder>
            Browser time zone for this request: UTC
            Instructions from: .dsh/AGENTS.md
            - Always use Chinese
            
            最终呈现给用户的有效回答正文。
        """.trimIndent()
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("最终呈现给用户的有效回答正文。", sanitized)
    }

    // --- Tier 2: 边界与极端判定 ---

    @Test
    fun `流式生成中未闭合的system-reminder标签尾部被安全清除`() {
        val streamingRaw = "正在生成正常回复内容<system-reminder>动态网关注入的半截指令尚未闭合..."
        val sanitized = SystemPromptSanitizer.sanitize(streamingRaw)
        assertEquals("正在生成正常回复内容", sanitized)
    }

    @Test
    fun `大小写混合的System-Reminder标签被兼容清除`() {
        val raw = "前置<System-Reminder>Mixed Case</system-reminder>中间<SYSTEM-REMINDER>Upper Case</SYSTEM-REMINDER>后置"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("前置中间后置", sanitized)
    }

    @Test
    fun `孤立遗留的闭合标签被安全清除`() {
        val raw = "前面内容</system-reminder>后面内容"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertEquals("前面内容后面内容", sanitized)
    }

    @Test
    fun `仅包含提示词且无附件无步骤无思考的消息判定为应丢弃`() {
        val raw = "<system-reminder>Only reminder</system-reminder>"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertTrue(sanitized.isBlank())
        assertTrue(SystemPromptSanitizer.shouldDropMessage(text = sanitized, hasAttachments = false, hasSteps = false, hasReasoning = false, isMeta = false))
    }

    @Test
    fun `提示词清除后为空但存在执行步骤的消息不丢弃`() {
        val raw = "<system-reminder>Tool invocation context</system-reminder>"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertTrue(sanitized.isBlank())
        // 存在 executionSteps 时不能丢弃整条消息，防止步骤卡片丢失
        assertFalse(SystemPromptSanitizer.shouldDropMessage(text = sanitized, hasAttachments = false, hasSteps = true, hasReasoning = false, isMeta = false))
    }

    @Test
    fun `提示词清除后为空但存在附件的消息不丢弃`() {
        val raw = "<system-reminder>Media upload</system-reminder>"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertTrue(sanitized.isBlank())
        assertFalse(SystemPromptSanitizer.shouldDropMessage(text = sanitized, hasAttachments = true, hasSteps = false, hasReasoning = false, isMeta = false))
    }

    @Test
    fun `提示词清除后为空但存在思考内容的消息绝不可丢弃`() {
        val raw = "<system-reminder>Thinking phase</system-reminder>"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertTrue(sanitized.isBlank())
        // 正在流式输出思考内容的消息绝不能误丢
        assertFalse(SystemPromptSanitizer.shouldDropMessage(text = sanitized, hasAttachments = false, hasSteps = false, hasReasoning = true, isMeta = false))
    }

    @Test
    fun `isMeta标记为true的消息无论正文内容均应丢弃`() {
        val raw = "系统心跳或元数据更新"
        val sanitized = SystemPromptSanitizer.sanitize(raw)
        assertTrue(SystemPromptSanitizer.shouldDropMessage(text = sanitized, hasAttachments = false, hasSteps = false, hasReasoning = false, isMeta = true))
    }
}
