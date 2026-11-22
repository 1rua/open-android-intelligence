package com.openandroidintelligence.conversation.sanitizer

import com.openandroidintelligence.conversation.model.ExecutionTimelineStep
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.MessageReasoning
import com.openandroidintelligence.conversation.ports.TimelineMessage

/**
 * 跨 Agent 通用的上下文注入提示词净化与消息可见性分类管线（领域唯一真实源）。
 *
 * 采用两级识别策略，统一服务于本地发送回显、实时流式 delta/completed、
 * 历史拉取同步、本地加密镜像恢复以及会话摘要/通知预览五条路径：
 * 1. 第一级（协议与结构化元数据优先）：依据归一化消息角色、isMeta/隐藏标志、
 *    协议 visibility/messageType 元数据判定非对话内部消息；缺失或未知发送者
 *    绝不误默认归类为 assistant，同时显式保留合法的端侧本地回执、审批卡片与执行事实。
 * 2. 第二级（结构化边界信封剥离与防误杀）：先识别 Markdown 围栏/行内代码保护区，
 *    再从首尾边界迭代剥离成对 XML 上下文信封、AGENTS/CLAUDE 等规则块、
 *    OpenClaw 不可信元数据块、ChatML 控制 token、方括号时间/环境头与多行运行时 KV 头，
 *    并对流式传输中的半截起始标签及未闭合信封做防闪烁暂缓展示；
 *    正文中间的普通引用、代码片段、日期及未闭合提问保留原样，剥离后仍有附件的消息保留附件。
 */
object ContextInjectionFilter {

    /** 归一化后的发送者角色分类。 */
    enum class SenderRole {
        USER,
        ASSISTANT,
        INTERNAL,
        UNKNOWN,
    }

    /** 分段净化结果，携带是否包含原始文本、空白编辑语义及重映射后的思考/步骤偏移供保留与展示判断使用。 */
    data class SanitizedPartsResult(
        val parts: List<MessagePart>,
        val rawParts: List<MessagePart>?,
        val hadOriginalText: Boolean,
        val hadExplicitEmptyTextPart: Boolean,
        val reasoning: List<MessageReasoning> = emptyList(),
        val timelineSteps: List<ExecutionTimelineStep> = emptyList(),
    )

    private class IndexedText(
        val text: String,
        val indices: IntArray,
    ) {
        fun slice(startInclusive: Int, endExclusive: Int): IndexedText =
            if (startInclusive >= endExclusive) {
                IndexedText("", IntArray(0))
            } else {
                IndexedText(
                    text = text.substring(startInclusive, endExclusive),
                    indices = indices.copyOfRange(startInclusive, endExclusive),
                )
            }

        fun trimmed(): IndexedText {
            var start = 0
            var end = text.length
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            return if (start == 0 && end == text.length) this else slice(start, end)
        }

        fun trimmedEnd(): IndexedText {
            var end = text.length
            while (end > 0 && text[end - 1].isWhitespace()) end--
            return if (end == text.length) this else slice(0, end)
        }

        fun removeRanges(removedRanges: List<IntRange>): IndexedText {
            if (removedRanges.isEmpty()) return this
            val sorted = removedRanges.sortedBy { it.first }
            val sb = StringBuilder(text.length)
            val kept = IntArray(text.length)
            var keptSize = 0
            var rangeIdx = 0
            var i = 0
            while (i < text.length) {
                while (rangeIdx < sorted.size && i > sorted[rangeIdx].last) {
                    rangeIdx++
                }
                if (rangeIdx < sorted.size && i in sorted[rangeIdx]) {
                    i = sorted[rangeIdx].last + 1
                    continue
                }
                sb.append(text[i])
                kept[keptSize++] = indices[i]
                i++
            }
            return IndexedText(sb.toString(), kept.copyOf(keptSize))
        }

        fun rebasedOffset(rawOffset: Int): Int {
            if (rawOffset <= 0 || indices.isEmpty()) return 0
            var lo = 0
            var hi = indices.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (indices[mid] < rawOffset) {
                    lo = mid + 1
                } else {
                    hi = mid
                }
            }
            return lo
        }
    }

    private const val MAX_PEEL_ITERATIONS = 32
    private const val MAX_OPEN_TAG_SCAN = 512

    private val USER_SENDERS = setOf("user", "human", "end_user", "end-user", "client")

    private val ASSISTANT_SENDERS = setOf(
        "assistant",
        "model",
        "agent",
        "bot",
        "ai",
        "codex",
        "hermes",
        "openclaw",
        "dsh",
        "claude",
        "gemini",
    )

    private val INTERNAL_SENDERS = setOf(
        "system",
        "developer",
        "dev",
        "context",
        "tool",
        "tool_result",
        "tool-result",
        "toolresult",
        "tool_call",
        "tool-call",
        "toolcall",
        "tool_use",
        "tool-use",
        "function",
        "function_call",
        "function_result",
        "environment",
        "env",
        "metadata",
        "meta",
        "instruction",
        "instructions",
        "prompt",
        "internal",
        "control",
        "runtime",
        "hook",
        "memory",
        "skill",
        "skills",
        "inject",
        "injected",
        "context_injection",
        "event",
        "status",
        "heartbeat",
        "notification",
        "system_prompt",
        "system_context",
        "developer_context",
    )

    private val HIDDEN_VISIBILITY_VALUES = setOf(
        "hidden",
        "hide",
        "internal",
        "system",
        "context",
        "meta",
        "none",
        "invisible",
        "server_only",
        "server-only",
        "model_only",
        "model-only",
        "agent_only",
        "agent-only",
        "assistant_only",
        "assistant-only",
        "developer_only",
        "developer-only",
        "background",
        "suppress",
        "suppressed",
        "silent",
        "non_ui",
        "non-ui",
        "false",
    )

    private val CONVERSATIONAL_MESSAGE_TYPES = setOf(
        "message",
        "chat",
        "text",
        "user",
        "user_message",
        "usermessage",
        "assistant",
        "assistant_message",
        "assistantmessage",
        "agent",
        "agent_message",
        "agentmessage",
        "model_message",
        "modelmessage",
        "markdown",
        "final",
        "reply",
        "response",
        "comment",
        "answer",
    )

    private val INTERNAL_MESSAGE_TYPES = setOf(
        "system",
        "system_prompt",
        "system_reminder",
        "system_context",
        "system_instruction",
        "system_instructions",
        "system_message",
        "system_notice",
        "system_note",
        "developer",
        "developer_message",
        "developer_instruction",
        "developer_instructions",
        "developer_context",
        "context",
        "context_injection",
        "injected_context",
        "injected_prompt",
        "runtime_context",
        "environment",
        "environment_context",
        "memory",
        "memory_context",
        "skills",
        "skill_list",
        "available_skills",
        "meta",
        "metadata",
        "instruction",
        "instructions",
        "prompt",
        "internal",
        "control",
        "status",
        "heartbeat",
        "hook",
        "hook_output",
        "command_output",
        "local_command",
        "tool",
        "tool_call",
        "tool_result",
        "tool_use",
        "function_call",
        "function_result",
    )

    /** 可单独作为上下文字根标签的规范名称集合。 */
    private val EXACT_CONTEXT_ENVELOPE_TAGS = setOf(
        "system",
        "developer",
        "context",
        "instructions",
        "instruction",
        "environment",
        "metadata",
        "skills",
        "system_prompt",
        "system_reminder",
        "system_reminders",
        "system_context",
        "system_instruction",
        "system_instructions",
        "system_info",
        "system_state",
        "system_status",
        "system_message",
        "system_note",
        "system_notice",
        "system_notification",
        "system_alert",
        "system_warning",
        "user_prompt_submit_hook",
        "hook_context",
        "injected_context",
        "injected_prompt",
        "injected_instructions",
        "runtime_context",
        "runtime_info",
        "runtime_state",
        "runtime_env",
        "runtime_environment",
        "environment_context",
        "env_context",
        "device_context",
        "device_state",
        "device_info",
        "session_context",
        "session_info",
        "session_metadata",
        "workspace_context",
        "workspace_info",
        "repo_context",
        "project_context",
        "agent_context",
        "agent_instructions",
        "agent_info",
        "developer_instructions",
        "developer_message",
        "developer_context",
        "available_skills",
        "skill_list",
        "skill_context",
        "skills_instructions",
        "memory_context",
        "retrieved_context",
        "rag_context",
        "attached_context",
        "conversation_info",
        "sender_info",
        "inbound_context",
        "channel_context",
        "user_info",
        "user_context",
        "date_context",
        "time_context",
        "temporal_context",
        "turn_context",
        "turn_state",
        "turn_metadata",
        "meta_context",
        "local_command_caveat",
        "local_command_stdout",
        "command_name",
        "command_message",
        "command_args",
    )

    private val CONTEXT_TAG_NOUNS = setOf(
        "context",
        "reminder",
        "reminders",
        "instruction",
        "instructions",
        "prompt",
        "prompts",
        "metadata",
        "meta",
        "skills",
        "skill",
        "env",
        "environment",
        "state",
        "status",
        "caveat",
        "hook",
        "memory",
        "capabilities",
        "workspace",
        "runtime",
        "info",
        "notice",
        "rules",
    )

    private val CONTEXT_TAG_QUALIFIERS = setOf(
        "system",
        "sys",
        "developer",
        "dev",
        "agent",
        "assistant",
        "user",
        "session",
        "turn",
        "conversation",
        "channel",
        "sender",
        "inbound",
        "runtime",
        "env",
        "environment",
        "workspace",
        "repo",
        "project",
        "device",
        "local",
        "available",
        "injected",
        "attached",
        "retrieved",
        "rag",
        "hook",
        "harness",
        "dsh",
        "openclaw",
        "hermes",
        "codex",
        "acp",
        "claude",
        "gemini",
        "temporal",
        "time",
        "date",
        "skill",
        "memory",
        "command",
        "untrusted",
        "auto",
        "dynamic",
        "client",
        "host",
        "platform",
    )

    /** 仅当外层已剥离至少一个注入信封时，才允许解包的输入/响应外壳标签。 */
    private val UNWRAPPABLE_BODY_TAGS = setOf(
        "user_message",
        "user_query",
        "user_input",
        "actual_user_message",
        "assistant_response",
    )

    /** 仅在助手角色且位于代码块外部时，允许从多段流式拼接正文中剥离的无歧义运行时闭合成对标签。 */
    private val MID_ASSISTANT_STRICT_INJECTION_TAGS = setOf(
        "system_reminder",
        "system_reminders",
        "environment_context",
        "available_skills",
        "hook_context",
        "injected_context",
        "local_command_caveat",
        "local_command_stdout",
    )

    /** 终态助手消息或通知预览若在以下严格系统/上下文根标签内部被截断，同样按未闭合信封剥离至结尾，避免泄露注入文本。 */
    private val COMPLETED_UNCLOSED_ROOT_CONTEXT_TAGS = MID_ASSISTANT_STRICT_INJECTION_TAGS + setOf(
        "system_prompt",
        "system_instructions",
        "developer_prompt",
        "developer_instructions",
        "user_instructions",
        "system_context",
        "runtime_context",
        "memory_context",
        "developer_context",
        "user_context",
        "turn_context",
        "session_context",
        "system_message",
        "developer_message",
        "antml_context",
    )

    private val STRICT_UNCLOSED_QUALIFIERS = setOf(
        "system", "sys", "developer", "runtime", "environment", "env",
        "injected", "hook", "harness", "dsh", "openclaw", "hermes", "codex", "acp", "available", "local",
    )

    private val STRICT_UNCLOSED_NOUNS = setOf(
        "reminder", "reminders", "prompt", "instructions", "context", "skills", "caveat",
    )

    /** 流式传输末尾出现未完整闭合尖括号前缀时，参与匹配的候选根前缀集合。 */
    private val STREAMING_TAG_PREFIX_ANCHORS = (EXACT_CONTEXT_ENVELOPE_TAGS + CONTEXT_TAG_QUALIFIERS)
        .flatMap { token ->
            val hyphenated = token.replace('_', '-')
            if (hyphenated == token) listOf(token) else listOf(token, hyphenated)
        }
        .toSet()

    /** 无歧义的强运行时遥测 KV 键，出现 1 条即可认定为边界元数据头。 */
    private val STRONG_RUNTIME_KV_KEYS = setOf(
        "browser time zone for this request",
        "injected context",
        "runtime context",
        "execution environment",
        "agent runtime",
        "workspace root",
        "working directory",
        "current working directory",
        "loaded skills",
        "active skills",
        "available skills",
        "session key",
        "device battery",
        "device network",
        "device locale",
        "client timezone",
        "request time zone",
    )

    /** 辅助运行时元数据 KV 键，需与至少 3 行全 KV 且包含时间/环境/工作区信息共现才剥离，防止误杀错误报告或备忘。 */
    private val ALL_RUNTIME_KV_KEYS = STRONG_RUNTIME_KV_KEYS + setOf(
        "current time",
        "local time",
        "current date",
        "time zone",
        "timezone",
        "utc time",
        "platform",
        "os",
        "os version",
        "app version",
        "hostname",
        "host",
        "device",
        "device model",
        "device state",
        "workspace",
        "repo root",
        "project root",
        "agent id",
        "session id",
        "thread id",
        "channel",
        "model",
        "locale",
    )

    private val TEMPORAL_OR_ENV_ANCHOR_KEYS = STRONG_RUNTIME_KV_KEYS + setOf(
        "current time",
        "local time",
        "current date",
        "time zone",
        "timezone",
        "utc time",
        "workspace",
        "repo root",
        "project root",
        "agent id",
        "session id",
    )

    private val BROWSER_TIMEZONE_LINE_REGEX = Regex(
        "(?im)^[ \\t]*Browser time zone for this request:[^\\r\\n]*(?:\\r?\\n|$)"
    )

    private val MARKDOWN_AGENT_HEADER_REGEX = Regex(
        "(?i)^(?:#\\s*[A-Za-z0-9_.-]+\\.md instructions for [^\\r\\n]+|Instructions from:\\s*[^\\r\\n]+)"
    )

    private val STANDALONE_INSTRUCTIONS_FROM_REGEX = Regex(
        "(?i)^Instructions from:\\s*(?:~?/?\\.?[A-Za-z0-9_./-]*AGENTS\\.md|[A-Za-z0-9_./-]+/(?:CLAUDE|GEMINI|HERMES|OPENCLAW|DSH|SOUL|TOOLS|MEMORY)\\.md)[^\\r\\n]*(?:\\r?\\n(?![ \\t]*\\r?\\n)[^\\r\\n]*)*"
    )

    private val UNTRUSTED_METADATA_HEADER_REGEX = Regex(
        "(?i)^(?:[A-Za-z0-9][A-Za-z0-9 _/-]{0,60}\\s*\\(untrusted\\s+(?:metadata|context|content)[^)]*\\)|Untrusted\\s+context\\s*\\([^)]*\\))\\s*:\\s*(?:\\r?\\n|$)"
    )

    private val CHATML_START_REGEX = Regex(
        "(?is)^<\\|im_start\\|>\\s*(?:system|developer|context|tool)\\b[\\s\\S]*?(?:<\\|\\s*im_end\\s*\\|>|$)"
    )

    private val LLAMA_HEADER_REGEX = Regex(
        "(?is)^<\\|start_header_id\\|>\\s*(?:system|developer|context|tool)\\s*<\\|end_header_id\\|>[\\s\\S]*?(?:<\\|eot_id\\|>|$)"
    )

    private val PIPE_ROLE_BLOCK_REGEX = Regex(
        "(?is)^<\\|(?:system|developer|context|tool)\\|>\\s*[\\s\\S]*?(?=<\\|(?:user|assistant|end|eot_id)\\|>|$)"
    )

    private val CHATML_BODY_WRAPPER_PREFIX_REGEX = Regex(
        "(?is)^(?:<\\|im_start\\|>\\s*(?:user|assistant)\\s*\\r?\\n?|<\\|start_header_id\\|>\\s*(?:user|assistant)\\s*<\\|end_header_id\\|>\\s*\\r?\\n?|<\\|(?:user|assistant)\\|>\\s*\\r?\\n?)"
    )

    private val CHATML_TRAILING_END_REGEX = Regex(
        "(?is)(?:<\\|\\s*im_end\\s*\\|>|<\\|eot_id\\|>|<\\|end\\|>)\\s*$"
    )

    private val CHATML_TRAILING_OPEN_REGEX = Regex(
        "(?is)<\\|im_start\\|>\\s*(?:system|developer|context|tool)\\b[\\s\\S]*$"
    )

    private val LLAMA_TRAILING_OPEN_REGEX = Regex(
        "(?is)<\\|start_header_id\\|>\\s*(?:system|developer|context|tool)\\s*<\\|end_header_id\\|>[\\s\\S]*$"
    )

    private val BRACKET_PAIRED_START_REGEX = Regex(
        "(?is)^\\[(SYSTEM|System Context|System Message|System Notice|System Reminder|Developer Context|Runtime Context|Environment Context|Available Skills|Skills|Injected Context|Session Context|Device Context|Workspace Context)\\]"
    )

    private val BRACKET_SECTION_KV_START_REGEX = Regex(
        "(?im)^\\[(?:System Context|Developer Context|Runtime Context|Environment Context|Available Skills|Injected Context|Session Context|Device Context|Workspace Context)\\]\\s*(?:\\r?\\n(?:(?![ \\t]*\\r?\\n)[^\\r\\n]*\\r?\\n?)+|$)"
    )

    private val BRACKET_INLINE_PREFIX_REGEX = Regex(
        "(?im)^\\[(?:Current time|Local time|Time zone|Timezone|System|System Message|System Notice|System Reminder|Runtime|Context|Session|Channel|Workspace|Device(?:\\s+state)?)\\s*:\\s*[^\\]\\r\\n]+\\](?:[ \\t]*\\r?\\n|[ \\t]+|$)"
    )

    private val BRACKET_INLINE_SUFFIX_REGEX = Regex(
        "(?im)(?:\\r?\\n|[ \\t]+|^)\\[(?:Current time|Local time|Time zone|Timezone|System|System Message|System Notice|System Reminder|Runtime|Context|Session|Channel|Workspace|Device(?:\\s+state)?)\\s*:\\s*[^\\]\\r\\n]+\\][ \\t]*$"
    )

    private val OPENCLAW_WEEKDAY_TZ_PREFIX_REGEX = Regex(
        "(?m)^\\[(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s+\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}(?::\\d{2})?\\s+(?:UTC|GMT|[A-Za-z]+/[A-Za-z_]+|[+-]\\d{2}:?\\d{2})\\](?:[ \\t]*\\r?\\n|[ \\t]+|$)"
    )

    private val OPENCLAW_SYSTEM_TIMESTAMP_PREFIX_REGEX = Regex(
        "(?im)^System:\\s*\\[\\d{4}-\\d{2}-\\d{2}[^\\]]*\\][^\\r\\n]*(?:\\r?\\n|$)"
    )

    private val EXTERNAL_DELIMITER_START_REGEX = Regex(
        "(?is)^<<<(?:BEGIN_)?([A-Z0-9_][A-Z0-9_-]{2,63})(?:\\s+[^\\r\\n>]*)?>>>([\\s\\S]*?)<<<END_\\1(?:\\s+[^\\r\\n>]*)?>>>"
    )

    private val EXTERNAL_DELIMITER_UNCLOSED_REGEX = Regex(
        "(?is)^<<<(?:BEGIN_)?(?:EXTERNAL_UNTRUSTED_CONTENT|SYSTEM_CONTEXT|RUNTIME_CONTEXT|MEMORY_CONTEXT|SKILLS|UNTRUSTED_CONTENT|INJECTED_CONTEXT)\\b[^>]*>>>[\\s\\S]*$"
    )

    private val BANNER_BEGIN_REGEX = Regex(
        "(?im)^([-=]{3,})\\s*BEGIN\\s+(SYSTEM|DEVELOPER|RUNTIME|ENVIRONMENT|MEMORY|SKILLS|CONTEXT|INJECTED)(?:[A-Z0-9 _-]*)\\s*\\1\\s*(?:\\r?\\n|$)"
    )

    private val HTML_COMMENT_CONTEXT_REGEX = Regex(
        "(?is)^<!--\\s*(?:BEGIN\\s+)?(?:SYSTEM|CONTEXT|DEVELOPER|SKILLS|RUNTIME|MEMORY|ENVIRONMENT|INJECTED|META)[A-Za-z0-9 _:-]*-->([\\s\\S]*?)<!--\\s*(?:END\\s+|/)?(?:SYSTEM|CONTEXT|DEVELOPER|SKILLS|RUNTIME|MEMORY|ENVIRONMENT|INJECTED|META)[A-Za-z0-9 _:-]*-->"
    )

    private val HTML_COMMENT_SINGLE_CONTEXT_REGEX = Regex(
        "(?is)^<!--\\s*(?:SYSTEM|CONTEXT|MEMORY|RUNTIME|SKILLS|DEVELOPER)\\s*:[^\\r\\n]*[\\s\\S]*?-->"
    )

    private val SKILL_SECTION_HEADING_REGEX = Regex(
        "(?im)^#{1,3}\\s*(?:Available Skills|Loaded Skills|System Context|Runtime Context|Environment Context|Injected Memory)\\s*(?:\\r?\\n|$)"
    )

    private val KV_LINE_REGEX = Regex("^([A-Za-z][A-Za-z0-9 _-]{1,48}):\\s+\\S.*$")

    private val TRAILING_PARAGRAPH_SPLIT_REGEX = Regex("(?:\\r?\\n[ \\t]*){2,}")

    private val FENCE_START_REGEX = Regex("^[ ]{0,3}(`{3,}|~{3,})[^\\r\\n]*$")

    private val SUMMARY_WHITESPACE_REGEX = Regex("\\s+")

    /**
     * 对消息发送者进行严格分类。
     * 缺失、空白或无法识别的发送者返回 [SenderRole.UNKNOWN]，绝不默认归类为 [SenderRole.ASSISTANT]。
     */
    fun classifySender(sender: String?): SenderRole {
        val normalized = sender?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return SenderRole.UNKNOWN
        if (normalized in USER_SENDERS) return SenderRole.USER
        if (normalized in ASSISTANT_SENDERS) return SenderRole.ASSISTANT
        if (isInternalSenderNormalized(normalized)) return SenderRole.INTERNAL
        return SenderRole.UNKNOWN
    }

    /** 将发送者归一为正文净化策略所需的 [SenderRole.USER] 或 [SenderRole.ASSISTANT]。 */
    fun contentSanitizationRole(sender: String?): SenderRole =
        if (classifySender(sender) == SenderRole.USER) SenderRole.USER else SenderRole.ASSISTANT

    /** 将已识别的对话发送者别名（如 `human`/`end_user`、`model`/`agent`）归一为 `"user"` / `"assistant"`；内部或未知发送者保持原样。 */
    fun canonicalSender(sender: String?): String = when (classifySender(sender)) {
        SenderRole.USER -> "user"
        SenderRole.ASSISTANT -> "assistant"
        SenderRole.INTERNAL, SenderRole.UNKNOWN -> sender ?: ""
    }

    private fun isInternalSenderNormalized(normalized: String): Boolean {
        if (normalized in INTERNAL_SENDERS) return true
        return normalized.startsWith("system_") ||
            normalized.startsWith("system-") ||
            normalized.startsWith("context_") ||
            normalized.startsWith("context-") ||
            normalized.startsWith("developer_") ||
            normalized.startsWith("developer-") ||
            normalized.startsWith("tool_") ||
            normalized.startsWith("tool-") ||
            normalized.startsWith("internal_") ||
            normalized.startsWith("internal-")
    }

    /** 判断协议 visibility 字段是否声明为非用户可见。 */
    fun isHiddenVisibility(visibility: String?): Boolean {
        val normalized = visibility?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        return normalized in HIDDEN_VISIBILITY_VALUES
    }

    /** 判断协议 messageType / type / kind 字段是否属于系统、上下文或控制类内部消息。 */
    fun isInternalMessageType(messageType: String?): Boolean {
        val normalized = messageType?.trim()?.lowercase()
            ?.replace('-', '_')
            ?.replace(' ', '_')
            .orEmpty()
        if (normalized.isEmpty()) return false
        if (normalized in CONVERSATIONAL_MESSAGE_TYPES) return false
        if (normalized in INTERNAL_MESSAGE_TYPES) return true
        return normalized.startsWith("system_") ||
            normalized.startsWith("context_") ||
            normalized.startsWith("developer_") ||
            normalized.startsWith("internal_") ||
            normalized.startsWith("injected_") ||
            normalized.startsWith("tool_")
    }

    /**
     * 第一级判定：基于协议角色、isMeta 标志、visibility 与 messageType 判断是否属于内部元数据消息。
     * 合法的客户端本地提示（`/new` 回执、审批卡片、执行事实）受 [isLocalSyntheticHint] 保护不被误杀。
     */
    fun isNonConversationalMessage(
        sender: String?,
        isMeta: Boolean = false,
        visibility: String? = null,
        messageType: String? = null,
        isLocalSyntheticHint: Boolean = false,
    ): Boolean {
        if (isLocalSyntheticHint) return false
        if (isMeta) return true
        if (isHiddenVisibility(visibility)) return true
        if (isInternalMessageType(messageType)) return true
        val role = classifySender(sender)
        return role == SenderRole.INTERNAL || role == SenderRole.UNKNOWN
    }

    /**
     * 综合第一级元数据与第二级内容判空逻辑，判断消息是否应当被隐藏丢弃。
     */
    fun shouldDropMessage(
        text: String,
        hasAttachments: Boolean = false,
        hasSteps: Boolean = false,
        hasReasoning: Boolean = false,
        isMeta: Boolean = false,
        sender: String? = "assistant",
        visibility: String? = null,
        messageType: String? = null,
        isLocalSyntheticHint: Boolean = false,
    ): Boolean {
        if (isLocalSyntheticHint) return false
        if (isNonConversationalMessage(sender, isMeta, visibility, messageType, isLocalSyntheticHint = false)) {
            return true
        }
        return text.isBlank() && !hasAttachments && !hasSteps && !hasReasoning
    }

    /**
     * 第二级净化入口：对文本执行边界结构化上下文信封剥离与流式半截标签防闪烁处理。
     */
    fun sanitizeText(
        text: String,
        role: SenderRole = SenderRole.ASSISTANT,
        streaming: Boolean = false,
    ): String {
        if (text.isEmpty()) return ""
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        if (!mayContainContextInjectionMarker(trimmed)) return trimmed
        return sanitizeTextIndexed(trimmed, role, streaming).text
    }

    /**
     * 快速特征预检：所有上下文注入信封（XML/ChatML/HTML 注释/外部界定符/方括号头/
     * Markdown 指令与技能段/KV 元数据/浏览器时区/横线分隔横幅）至少包含
     * `<`、`[`、`#`、`:` 或连续 3 个 `[-=]` 之一；不含这些特征的常规纯文本可直接短路。
     */
    private fun mayContainContextInjectionMarker(text: String): Boolean {
        var bannerRun = 0
        for (i in text.indices) {
            when (text[i]) {
                '<', '[', '#', ':' -> return true
                '-', '=' -> {
                    bannerRun++
                    if (bannerRun >= 3) return true
                }
                else -> bannerRun = 0
            }
        }
        return false
    }

    private fun containsBannerRun(text: String): Boolean {
        var run = 0
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '-' || ch == '=') {
                run++
                if (run >= 3) return true
            } else {
                run = 0
            }
        }
        return false
    }

    private fun hasCollapsibleWhitespace(text: String): Boolean {
        var previousSpace = false
        for (i in text.indices) {
            val ch = text[i]
            if (ch == ' ') {
                if (previousSpace) return true
                previousSpace = true
            } else if (ch.isWhitespace()) {
                return true
            } else {
                previousSpace = false
            }
        }
        return false
    }

    private fun sanitizeTextIndexed(
        text: String,
        role: SenderRole,
        streaming: Boolean,
    ): IndexedText {
        if (text.isEmpty()) return IndexedText("", IntArray(0))
        var current = IndexedText(text, IntArray(text.length) { it }).trimmed()
        if (current.text.isEmpty()) return current

        var strippedAny = false
        for (iteration in 0 until MAX_PEEL_ITERATIONS) {
            val protectedRanges = findProtectedCodeRanges(current.text, streaming)
            val peeledLeading = peelLeadingEnvelope(current.text, protectedRanges, role, streaming, strippedAny)
            if (peeledLeading != null && peeledLeading.length != current.text.length) {
                val removedPrefix = current.text.length - peeledLeading.length
                current = current.slice(removedPrefix, current.text.length).trimmed()
                strippedAny = true
                if (current.text.isEmpty()) break
                continue
            }

            val peeledTrailing = peelTrailingEnvelope(current.text, protectedRanges, role, streaming, strippedAny)
            if (peeledTrailing != null && peeledTrailing.length != current.text.length) {
                current = current.slice(0, peeledTrailing.length).trimmed()
                strippedAny = true
                if (current.text.isEmpty()) break
                continue
            }

            val telemetryRanges = findUnambiguousTelemetryLineRanges(current.text, protectedRanges)
            if (telemetryRanges.isNotEmpty()) {
                current = current.removeRanges(telemetryRanges).trimmed()
                strippedAny = true
                if (current.text.isEmpty()) break
                continue
            }

            break
        }

        // 流式助手消息中间可能夹带后续文本到达的运行时 `<system-reminder>...</system-reminder>`；
        // 严格限定在助手角色且位于 Markdown 代码保护区之外的成对闭环标签，绝不误杀用户输入或代码示例。
        if (role == SenderRole.ASSISTANT && current.text.isNotEmpty()) {
            val midProtected = findProtectedCodeRanges(current.text, streaming)
            val midTagRanges = findMidAssistantStrictTagRanges(current.text, midProtected)
            if (midTagRanges.isNotEmpty()) {
                current = current.removeRanges(midTagRanges).trimmed()
                strippedAny = true
            }
        }

        // 若剥离后剩余内容处于明确的运行时输入外壳标签内（如 <user_message>...</user_message>），解包保留真实内容
        if (strippedAny && current.text.isNotEmpty()) {
            unwrapSingleBodyContainerRange(current.text, streaming)?.let { range ->
                current = current.slice(range.first, range.last + 1).trimmed()
            }
        }

        // 用户角色输入绝不剥离正文中间的普通内容；若流式尾巴出现半截标签前缀则在流式态暂缓展示
        if (streaming && current.text.isNotEmpty()) {
            val protectedRanges = findProtectedCodeRanges(current.text, streaming = true)
            val partialTrimmed = trimTrailingPartialTagForStreaming(current.text, protectedRanges)
            if (partialTrimmed.length != current.text.length) {
                current = current.slice(0, partialTrimmed.length)
            }
            current = current.trimmedEnd()
        }

        return current.trimmed()
    }

    private data class TextSegmentMap(
        val rawStart: Int,
        val rawEnd: Int,
        val cleanStart: Int,
        val indexed: IndexedText,
    )

    /**
     * 对消息分段列表执行合并文本清洗，无损保留附件与原始未清洗分段（仅在发生变化时记录 [rawParts]），
     * 并同步按移除区间重映射思考块 [MessageReasoning.bodyOffset] 与统一有序步骤 [ExecutionTimelineStep.bodyOffset] 至净化后的展示文本坐标系。
     */
    fun sanitizeParts(
        parts: List<MessagePart>,
        role: SenderRole = SenderRole.ASSISTANT,
        streaming: Boolean = false,
        reasoning: List<MessageReasoning> = emptyList(),
        timelineSteps: List<ExecutionTimelineStep> = emptyList(),
    ): SanitizedPartsResult {
        if (parts.isEmpty()) {
            return SanitizedPartsResult(
                parts = emptyList(),
                rawParts = null,
                hadOriginalText = false,
                hadExplicitEmptyTextPart = false,
                reasoning = if (reasoning.isEmpty()) emptyList() else reasoning.map { it.copy(bodyOffset = 0) },
                timelineSteps = if (timelineSteps.isEmpty()) emptyList() else timelineSteps.map { it.withBodyOffset(0) },
            )
        }

        val hadExplicitEmptyTextPart = parts.any { it is MessagePart.Text && it.value.isEmpty() }
        val originalText = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
        val hadOriginalText = originalText.isNotEmpty()

        // 合并相邻连续文本分段再做净化，防止注入信封跨 content block 切分导致漏网
        val output = mutableListOf<MessagePart>()
        val pendingText = StringBuilder()
        val segmentMaps = mutableListOf<TextSegmentMap>()
        var rawCursor = 0
        var cleanCursor = 0

        fun flushPendingText() {
            if (pendingText.isEmpty()) return
            val rawChunk = pendingText.toString()
            pendingText.clear()
            val indexed = sanitizeTextIndexed(rawChunk, role = role, streaming = streaming)
            val segRawStart = rawCursor
            val segRawEnd = rawCursor + rawChunk.length
            rawCursor = segRawEnd
            val segCleanStart = cleanCursor
            if (indexed.text.isNotEmpty()) {
                output += MessagePart.Text(indexed.text)
                cleanCursor += indexed.text.length
            }
            segmentMaps += TextSegmentMap(segRawStart, segRawEnd, segCleanStart, indexed)
        }

        for (part in parts) {
            when (part) {
                is MessagePart.Text -> pendingText.append(part.value)
                is MessagePart.Attachment, is MessagePart.Command -> {
                    flushPendingText()
                    output += part
                }
            }
        }
        flushPendingText()

        fun rebaseOffset(rawOffset: Int): Int {
            if (rawOffset <= 0 || segmentMaps.isEmpty()) return 0
            var rebased = 0
            for (seg in segmentMaps) {
                when {
                    rawOffset >= seg.rawEnd -> rebased = seg.cleanStart + seg.indexed.text.length
                    rawOffset <= seg.rawStart -> return rebased
                    else -> return seg.cleanStart + seg.indexed.rebasedOffset(rawOffset - seg.rawStart)
                }
            }
            return rebased
        }

        val rebasedReasoning = if (reasoning.isEmpty()) {
            emptyList()
        } else {
            reasoning.map { item ->
                val mapped = rebaseOffset(item.bodyOffset)
                if (mapped == item.bodyOffset) item else item.copy(bodyOffset = mapped)
            }
        }
        val rebasedTimelineSteps = if (timelineSteps.isEmpty()) {
            emptyList()
        } else {
            timelineSteps.map { step ->
                val mapped = rebaseOffset(step.bodyOffset)
                step.withBodyOffset(mapped)
            }
        }

        val rawPartsIfChanged = if (output != parts) parts else null
        return SanitizedPartsResult(
            parts = output,
            rawParts = rawPartsIfChanged,
            hadOriginalText = hadOriginalText,
            hadExplicitEmptyTextPart = hadExplicitEmptyTextPart,
            reasoning = rebasedReasoning,
            timelineSteps = rebasedTimelineSteps,
        )
    }

    private fun rebaseProjectedExecution(
        message: TimelineMessage,
        targetParts: List<MessagePart>,
        role: SenderRole,
        streaming: Boolean,
    ): Pair<List<MessageReasoning>, List<ExecutionTimelineStep>> {
        if (message.reasoning.isEmpty() && message.timelineSteps.isEmpty()) {
            return emptyList<MessageReasoning>() to emptyList()
        }
        val currentCleanLen = message.parts.filterIsInstance<MessagePart.Text>().sumOf { it.value.length }
        val targetCleanLen = targetParts.filterIsInstance<MessagePart.Text>().sumOf { it.value.length }
        if (message.rawParts != null && targetParts == message.parts) {
            val clampedReasoning = message.reasoning.map { r ->
                if (r.bodyOffset <= targetCleanLen) r else r.copy(bodyOffset = targetCleanLen)
            }
            val clampedSteps = message.timelineSteps.map { step ->
                if (step.bodyOffset <= targetCleanLen) step else step.withBodyOffset(targetCleanLen)
            }
            return clampedReasoning to clampedSteps
        }
        val repass = sanitizeParts(
            parts = message.parts,
            role = role,
            streaming = streaming,
            reasoning = message.reasoning,
            timelineSteps = message.timelineSteps,
        )
        if (repass.parts == targetParts) {
            return repass.reasoning to repass.timelineSteps
        }
        fun proportional(offset: Int): Int = if (currentCleanLen > 0) {
            ((offset.toLong() * targetCleanLen) / currentCleanLen).toInt().coerceIn(0, targetCleanLen)
        } else {
            0
        }
        return message.reasoning.map { r -> r.copy(bodyOffset = proportional(r.bodyOffset)) } to
            message.timelineSteps.map { step -> step.withBodyOffset(proportional(step.bodyOffset)) }
    }

    /**
     * 以消息当前已落盘的 `timelineSteps`（处于 `message.parts` 坐标系）为基准，
     * 在展示导出路径按需重映射偏移，防止对已被 [sanitizeParts] 映射过的步骤重复偏移。
     */
    fun rebaseTimelineStepsOnProjection(
        message: TimelineMessage,
        targetParts: List<MessagePart>,
        role: SenderRole = contentSanitizationRole(message.sender),
        streaming: Boolean = message.state.equals("STREAMING", ignoreCase = true),
    ): List<ExecutionTimelineStep> =
        if (message.timelineSteps.isEmpty()) emptyList()
        else rebaseProjectedExecution(message, targetParts, role, streaming).second

    /**
     * 以消息当前已落盘的 `reasoning`（处于 `message.parts` 坐标系）为基准，
     * 在展示导出路径按需重映射偏移，防止对已被 [sanitizeParts] 映射过的思考块重复偏移。
     */
    fun rebaseReasoningOnProjection(
        message: TimelineMessage,
        targetParts: List<MessagePart>,
        role: SenderRole = contentSanitizationRole(message.sender),
        streaming: Boolean = message.state.equals("STREAMING", ignoreCase = true),
    ): List<MessageReasoning> =
        if (message.reasoning.isEmpty()) emptyList()
        else rebaseProjectedExecution(message, targetParts, role, streaming).first

    /**
     * 将 [TimelineMessage] 投影为可展示的干净消息；若应隐藏则返回 null。
     * 注意：`isDeleted` 墓碑保留原样以维护快照删除和解逻辑，本地合成提示受 [isLocalSyntheticHint] 保护。
     */
    fun projectMessage(
        message: TimelineMessage,
        isLocalSyntheticHint: Boolean = false,
    ): TimelineMessage? {
        if (message.isDeleted) return message

        val streaming = message.state.equals("STREAMING", ignoreCase = true)
        if (isNonConversationalMessage(
                sender = message.sender,
                isMeta = message.isMeta,
                visibility = message.visibility,
                messageType = message.messageType,
                isLocalSyntheticHint = isLocalSyntheticHint,
            )
        ) {
            return null
        }

        val canonicalSenderValue = canonicalSender(message.sender)
        val sourceParts = message.rawParts ?: message.parts
        val role = classifySender(message.sender)
        val sanitizationRole = contentSanitizationRole(message.sender)
        val sanitized = sanitizeParts(
            parts = sourceParts,
            role = sanitizationRole,
            streaming = streaming,
        )

        val cleanedText = sanitized.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
        val hasNonTextContent = sanitized.parts.any { it is MessagePart.Attachment || it is MessagePart.Command }
        val hasSteps = message.hasExecutionSteps
        val hasReasoning = message.hasReasoningSteps

        // 保留显式用户空编辑（原文即空且含 contentRevision 或空 Text 分段），防止回滚被旧本地副本复活
        val explicitEmptyUserEdit = role == SenderRole.USER &&
            !sanitized.hadOriginalText &&
            (sanitized.hadExplicitEmptyTextPart || message.contentRevision != null)

        val effectiveParts = sanitized.parts

        if (!isLocalSyntheticHint &&
            !explicitEmptyUserEdit &&
            cleanedText.isBlank() &&
            !hasNonTextContent &&
            !hasSteps &&
            !hasReasoning &&
            !message.localPlaceholder
        ) {
            return null
        }

        val preservedRaw = message.rawParts ?: sanitized.rawParts
        val (projectedReasoning, projectedTimelineSteps) = rebaseProjectedExecution(message, effectiveParts, sanitizationRole, streaming)
        return if (
            canonicalSenderValue == message.sender &&
            effectiveParts == message.parts &&
            preservedRaw == message.rawParts &&
            projectedReasoning == message.reasoning &&
            projectedTimelineSteps == message.timelineSteps
        ) {
            message
        } else {
            message.copy(
                sender = canonicalSenderValue,
                parts = effectiveParts,
                rawParts = preservedRaw,
                reasoning = projectedReasoning,
                timelineSteps = projectedTimelineSteps,
            )
        }
    }

    /** 过滤并投影时间线消息列表，移除隐藏/内部及净化后为空的非附件气泡。 */
    fun projectTimeline(messages: List<TimelineMessage>): List<TimelineMessage> =
        messages.mapNotNull { projectMessage(it) }

    /**
     * 净化会话列表摘要、标题或预览文本，折叠换行为单行并截断。
     */
    fun sanitizeSummaryText(raw: String?, maxLength: Int = 160): String {
        if (raw.isNullOrBlank()) return ""
        val sanitized = sanitizeText(raw, role = SenderRole.ASSISTANT, streaming = false)
        if (sanitized.isEmpty()) return ""
        val cleaned = if (hasCollapsibleWhitespace(sanitized)) {
            sanitized.replace(SUMMARY_WHITESPACE_REGEX, " ").trim()
        } else {
            sanitized
        }
        if (cleaned.isEmpty()) return ""
        return if (cleaned.length > maxLength) cleaned.take(maxLength).trimEnd() else cleaned
    }

    /** 净化通知正文预览文本（与摘要规则保持单源一致）。 */
    fun sanitizeNotificationPreview(raw: String?): String =
        sanitizeText(raw.orEmpty(), role = SenderRole.ASSISTANT, streaming = false)
            .replace(SUMMARY_WHITESPACE_REGEX, " ")
            .trim()

    /**
     * 判定一条实时事件消息是否允许触发通知/回复摘录，并输出净化后的预览文本。
     */
    fun filterNotificationPreview(
        sender: String?,
        isMeta: Boolean = false,
        visibility: String? = null,
        messageType: String? = null,
        rawText: String?,
        hasAttachments: Boolean = false,
    ): String? {
        if (isNonConversationalMessage(sender, isMeta, visibility, messageType)) return null
        val cleaned = sanitizeNotificationPreview(rawText)
        if (cleaned.isBlank() && !hasAttachments) return null
        return cleaned
    }

    /** 判断 XML 标签名是否符合机器上下文注入信封特征，排除混合大小写 UI 组件与常见 HTML 标记。 */
    fun isContextEnvelopeTag(rawTagName: String): Boolean {
        val trimmed = rawTagName.trim()
        if (trimmed.isEmpty()) return false
        if (isMixedPascalCaseUiTag(trimmed)) return false

        val normalized = normalizeTagName(trimmed)
        if (normalized in EXACT_CONTEXT_ENVELOPE_TAGS) return true

        val parts = normalized.split('_', ':', '-').filter { it.isNotEmpty() }
        if (parts.size < 2) return false
        val hasNoun = parts.any { it in CONTEXT_TAG_NOUNS }
        val hasQualifier = parts.any { it in CONTEXT_TAG_QUALIFIERS }
        return hasNoun && hasQualifier
    }

    private fun normalizeTagName(raw: String): String {
        // 将分隔符 -, :, . 归一化为下划线并转为小写；混合大小写 PascalCase 组件标签已在调用前拦截
        val builder = StringBuilder(raw.length + 4)
        for (i in raw.indices) {
            val ch = raw[i]
            if (ch == '-' || ch == ':' || ch == '.') {
                builder.append('_')
            } else {
                builder.append(ch.lowercaseChar())
            }
        }
        return builder.toString()
    }

    /** 混合大小写 PascalCase（如 DeveloperSettings / SystemCard）视为代码/UI 标签而非机器提示词信封。 */
    private fun isMixedPascalCaseUiTag(tagName: String): Boolean {
        if (tagName.isEmpty() || !tagName[0].isUpperCase()) return false
        return tagName.any { it.isLowerCase() }
    }

    /** 仅供 [SystemPromptSanitizer] 兼容层复用的代码块保护区探测入口。 */
    internal fun protectedCodeRangesForLegacyCompat(text: String, streaming: Boolean): List<IntRange> =
        findProtectedCodeRanges(text, streaming)

    private fun findProtectedCodeRanges(text: String, streaming: Boolean): List<IntRange> {
        if (text.indexOf('`') < 0 && text.indexOf('~') < 0) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var lineStart = 0
        var inFence = false
        var fenceChar = '`'
        var fenceLen = 0
        var fenceStartOffset = 0

        // 第一遍按行扫描围栏代码块
        while (lineStart < text.length) {
            val newlineIdx = text.indexOf('\n', lineStart)
            val lineEndExclusive = if (newlineIdx >= 0) newlineIdx + 1 else text.length
            val lineContentEnd = if (newlineIdx >= 0 && newlineIdx > lineStart && text[newlineIdx - 1] == '\r') {
                newlineIdx - 1
            } else if (newlineIdx >= 0) {
                newlineIdx
            } else {
                text.length
            }
            val line = text.substring(lineStart, lineContentEnd)

            if (!inFence) {
                val match = FENCE_START_REGEX.matchEntire(line)
                if (match != null) {
                    val fence = match.groupValues[1]
                    inFence = true
                    fenceChar = fence[0]
                    fenceLen = fence.length
                    fenceStartOffset = lineStart
                }
            } else {
                val trimmed = line.trim()
                if (trimmed.length >= fenceLen && trimmed.all { it == fenceChar }) {
                    ranges += fenceStartOffset until lineEndExclusive
                    inFence = false
                }
            }
            lineStart = lineEndExclusive
        }
        if (inFence) {
            ranges += fenceStartOffset until text.length
        }

        // 第二遍在非围栏区域内扫描行内反引号跨度（不跨越空行）
        var i = 0
        while (i < text.length) {
            val enclosingFence = ranges.firstOrNull { i in it }
            if (enclosingFence != null) {
                i = enclosingFence.last + 1
                continue
            }
            if (text[i] == '`') {
                var openCount = 0
                val openStart = i
                while (i < text.length && text[i] == '`') {
                    openCount++
                    i++
                }
                var j = i
                var matchedCloseEnd = -1
                while (j < text.length) {
                    if (ranges.any { j in it }) break
                    if (j + 1 < text.length && text[j] == '\n' && (text[j + 1] == '\n' || text[j + 1] == '\r')) {
                        break
                    }
                    if (text[j] == '`') {
                        var closeCount = 0
                        val closeStart = j
                        while (j < text.length && text[j] == '`') {
                            closeCount++
                            j++
                        }
                        if (closeCount == openCount) {
                            matchedCloseEnd = j
                            break
                        }
                    } else {
                        j++
                    }
                }
                if (matchedCloseEnd >= 0) {
                    ranges += openStart until matchedCloseEnd
                    i = matchedCloseEnd
                } else if (streaming && !hasBlankLineAfter(text, i)) {
                    // 流式渲染同行尚未闭合的行内代码片段也受保护，避免把代码样例里的 `<system-` 吞掉
                    ranges += openStart until text.length
                    break
                }
            } else {
                i++
            }
        }
        return ranges
    }

    private fun hasBlankLineAfter(text: String, startIndex: Int): Boolean {
        var i = startIndex
        while (i < text.length - 1) {
            if (text[i] == '\n') {
                var next = i + 1
                while (next < text.length && (text[next] == ' ' || text[next] == '\t' || text[next] == '\r')) {
                    next++
                }
                if (next < text.length && text[next] == '\n') return true
            }
            i++
        }
        return false
    }

    private fun isProtected(index: Int, protectedRanges: List<IntRange>): Boolean =
        protectedRanges.any { index in it }

    private fun peelLeadingEnvelope(
        text: String,
        protectedRanges: List<IntRange>,
        role: SenderRole,
        streaming: Boolean,
        alreadyStripped: Boolean,
    ): String? {
        if (text.isEmpty() || isProtected(0, protectedRanges)) return null

        // 1. Codex / 通用 .md instructions + <INSTRUCTIONS> 或 Instructions from: ...AGENTS.md
        stripLeadingInstructionsBlock(text, protectedRanges, streaming)?.let { return it }

        // 2. OpenClaw / Hermes untrusted metadata 标题 + 紧随其后的围栏块/界定块/KV块
        stripLeadingUntrustedMetadata(text, streaming)?.let { return it }

        // 3. <<<...>>>...<<<END_...>>>、--- BEGIN ... ---、HTML 注释上下文字段
        stripLeadingDelimiterOrCommentBlock(text, role, streaming)?.let { return it }

        // 4. ChatML / Llama / control token 段落
        stripLeadingChatMl(text, alreadyStripped)?.let { return it }

        // 5. 成对或助手终态截断的结构化 XML 上下文信封（支持同名嵌套与属性）
        stripLeadingXmlEnvelope(text, protectedRanges, role, streaming)?.let { return it }

        // 6. 方括号控制/时间/环境头或成对 [SYSTEM]...[/SYSTEM]
        stripLeadingBracketEnvelope(text, streaming)?.let { return it }

        // 7. 具有明确机器标记的 Markdown Skills / Context 整段
        stripLeadingMachineSkillSection(text)?.let { return it }

        // 8. 多行结构化运行时 KV 元数据首段
        stripLeadingRuntimeKvParagraph(text)?.let { return it }

        return null
    }

    private fun peelTrailingEnvelope(
        text: String,
        protectedRanges: List<IntRange>,
        role: SenderRole,
        streaming: Boolean,
        alreadyStripped: Boolean,
    ): String? {
        if (text.isEmpty()) return null
        val lastIdx = text.length - 1
        val trailingProtected = isProtected(lastIdx, protectedRanges)

        // 若末尾是 `untrusted metadata` 所带围栏块或根上下文 XML 信封（含内部被截断围栏），各自校验起始标签是否在保护区外
        stripTrailingUntrustedMetadata(text, streaming)?.let { return it }
        stripTrailingXmlEnvelope(text, protectedRanges, role, streaming)?.let { return it }

        if (trailingProtected) return null

        if (text.indexOf('#') >= 0 || text.contains("Instructions from:", ignoreCase = true)) {
            stripTrailingParagraphBlock(text, protectedRanges) { tail, shiftedProtected ->
                stripLeadingInstructionsBlock(tail, shiftedProtected, streaming)
            }?.let { return it }
        }
        if (text.indexOf('<') >= 0 || containsBannerRun(text)) {
            stripTrailingParagraphBlock(text, protectedRanges) { tail, _ ->
                stripLeadingDelimiterOrCommentBlock(tail, role, streaming)
            }?.let { return it }
        }
        stripTrailingChatMl(text, role, streaming, alreadyStripped)?.let { return it }
        stripTrailingBracketEnvelope(text, protectedRanges)?.let { return it }
        if (text.indexOf('#') >= 0) {
            stripTrailingParagraphBlock(text, protectedRanges) { tail, _ ->
                stripLeadingMachineSkillSection(tail)
            }?.let { return it }
        }
        stripTrailingRuntimeKvParagraph(text, protectedRanges)?.let { return it }

        return null
    }

    private fun stripLeadingInstructionsBlock(
        text: String,
        protectedRanges: List<IntRange>,
        streaming: Boolean,
    ): String? {
        if (text.isEmpty()) return null
        val first = text[0]
        if (first != '#' && first != 'I' && first != 'i') return null
        val headerMatch = MARKDOWN_AGENT_HEADER_REGEX.find(text)
        if (headerMatch != null && headerMatch.range.first == 0) {
            var cursor = headerMatch.range.last + 1
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            if (cursor < text.length && text.startsWith("<INSTRUCTIONS>", startIndex = cursor, ignoreCase = true)) {
                val closeEnd = findMatchingXmlCloseEnd(text, cursor, "INSTRUCTIONS", protectedRanges)
                if (closeEnd >= 0) return text.substring(closeEnd)
                if (streaming) return ""
            }
        }
        val standalone = STANDALONE_INSTRUCTIONS_FROM_REGEX.find(text)
        if (standalone != null && standalone.range.first == 0) {
            return text.substring(standalone.range.last + 1)
        }
        return null
    }

    private fun stripLeadingUntrustedMetadata(text: String, streaming: Boolean): String? {
        if (text.indexOf(':') < 0 || !text.contains("untrusted", ignoreCase = true)) return null
        val headerMatch = UNTRUSTED_METADATA_HEADER_REGEX.find(text) ?: return null
        if (headerMatch.range.first != 0) return null
        var cursor = headerMatch.range.last + 1
        while (cursor < text.length && (text[cursor] == ' ' || text[cursor] == '\t' || text[cursor] == '\r' || text[cursor] == '\n')) {
            cursor++
        }
        if (cursor >= text.length) {
            return if (streaming) "" else null
        }

        // 情况 A：紧随围栏块 ```...``` 或 ~~~...~~~
        if (text.startsWith("```", cursor) || text.startsWith("~~~", cursor)) {
            val fenceChar = text[cursor]
            var fenceLen = 0
            while (cursor + fenceLen < text.length && text[cursor + fenceLen] == fenceChar) fenceLen++
            val firstNewline = text.indexOf('\n', cursor)
            if (firstNewline < 0) return if (streaming) "" else null
            var search = firstNewline + 1
            while (search < text.length) {
                val nextNl = text.indexOf('\n', search)
                val endLine = if (nextNl >= 0) nextNl else text.length
                val line = text.substring(search, endLine).trim()
                if (line.length >= fenceLen && line.all { it == fenceChar }) {
                    val consumeEnd = if (nextNl >= 0) nextNl + 1 else text.length
                    return text.substring(consumeEnd)
                }
                if (nextNl < 0) break
                search = nextNl + 1
            }
            return if (streaming) "" else null
        }

        // 情况 B：紧随 <<<...>>>...<<<END_...>>>
        if (text.startsWith("<<<", cursor)) {
            val rest = text.substring(cursor)
            val ext = EXTERNAL_DELIMITER_START_REGEX.find(rest)
            if (ext != null && ext.range.first == 0) {
                return rest.substring(ext.range.last + 1)
            }
            if (streaming && EXTERNAL_DELIMITER_UNCLOSED_REGEX.matchesAt(rest, 0)) {
                return ""
            }
        }

        // 情况 C：非围栏结构，持续消费到下一个空行分隔
        val blankMatch = TRAILING_PARAGRAPH_SPLIT_REGEX.find(text, cursor)
        return if (blankMatch != null) {
            text.substring(blankMatch.range.last + 1)
        } else if (text.substring(cursor).trim().startsWith("{")) {
            val trimmedRest = text.substring(cursor).trimEnd()
            if (trimmedRest.endsWith("}") || streaming) "" else null
        } else {
            null
        }
    }

    private fun stripTrailingUntrustedMetadata(text: String, streaming: Boolean): String? {
        if (text.indexOf(':') < 0 || !text.contains("untrusted", ignoreCase = true)) return null
        val matches = UNTRUSTED_METADATA_HEADER_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return null
        val candidate = matches.last()
        val start = candidate.range.first
        if (start > 0 && text[start - 1] != '\n') return null
        val tail = text.substring(start)
        val peeled = stripLeadingUntrustedMetadata(tail, streaming)
        if (peeled != null && peeled.isBlank()) {
            return text.substring(0, start)
        }
        return null
    }

    private fun stripLeadingDelimiterOrCommentBlock(
        text: String,
        role: SenderRole,
        streaming: Boolean,
    ): String? {
        if (text.isEmpty()) return null
        val first = text[0]
        if (first != '<' && first != '-' && first != '=') return null
        EXTERNAL_DELIMITER_START_REGEX.find(text)?.takeIf { it.range.first == 0 }?.let {
            return text.substring(it.range.last + 1)
        }
        if ((streaming || role == SenderRole.ASSISTANT) && EXTERNAL_DELIMITER_UNCLOSED_REGEX.matchesAt(text, 0)) {
            return ""
        }
        val bannerMatch = BANNER_BEGIN_REGEX.find(text)
        if (bannerMatch != null && bannerMatch.range.first == 0) {
            val marker = Regex.escape(bannerMatch.groupValues[1])
            val kind = Regex.escape(bannerMatch.groupValues[2])
            val endRegex = Regex("(?im)^$marker\\s*END\\s+$kind[A-Z0-9 _-]*\\s*$marker\\s*(?:\\r?\\n|$)")
            val endMatch = endRegex.find(text, bannerMatch.range.last + 1)
            if (endMatch != null) return text.substring(endMatch.range.last + 1)
            if (streaming) return ""
        }
        HTML_COMMENT_CONTEXT_REGEX.find(text)?.takeIf { it.range.first == 0 }?.let {
            return text.substring(it.range.last + 1)
        }
        HTML_COMMENT_SINGLE_CONTEXT_REGEX.find(text)?.takeIf { it.range.first == 0 }?.let {
            return text.substring(it.range.last + 1)
        }
        return null
    }

    private fun stripLeadingChatMl(text: String, alreadyStripped: Boolean): String? {
        if (!text.startsWith("<|")) return null
        CHATML_START_REGEX.find(text)?.takeIf { it.range.first == 0 }?.let {
            return text.substring(it.range.last + 1)
        }
        LLAMA_HEADER_REGEX.find(text)?.takeIf { it.range.first == 0 }?.let {
            return text.substring(it.range.last + 1)
        }
        PIPE_ROLE_BLOCK_REGEX.find(text)?.takeIf { it.range.first == 0 }?.let {
            return text.substring(it.range.last + 1)
        }
        if (alreadyStripped || CHATML_TRAILING_END_REGEX.containsMatchIn(text)) {
            CHATML_BODY_WRAPPER_PREFIX_REGEX.find(text)?.takeIf { it.range.first == 0 }?.let {
                return text.substring(it.range.last + 1)
            }
        }
        return null
    }

    private fun stripTrailingChatMl(
        text: String,
        role: SenderRole,
        streaming: Boolean,
        alreadyStripped: Boolean,
    ): String? {
        if (!text.contains("<|")) return null
        if (streaming || role == SenderRole.ASSISTANT) {
            CHATML_TRAILING_OPEN_REGEX.find(text)?.let {
                return text.substring(0, it.range.first)
            }
            LLAMA_TRAILING_OPEN_REGEX.find(text)?.let {
                return text.substring(0, it.range.first)
            }
        }
        if (alreadyStripped || text.contains("<|im_start|>") || text.contains("<|start_header_id|>") || text.contains("<|system|>")) {
            CHATML_TRAILING_END_REGEX.find(text)?.let {
                return text.substring(0, it.range.first)
            }
        }
        val idx = text.lastIndexOf("<|im_start|>")
        if (idx >= 0) {
            val tail = text.substring(idx)
            val peeled = stripLeadingChatMl(tail, alreadyStripped = true)
            if (peeled != null && peeled.isBlank()) {
                return text.substring(0, idx)
            }
        }
        return null
    }

    private fun stripLeadingXmlEnvelope(
        text: String,
        protectedRanges: List<IntRange>,
        role: SenderRole,
        streaming: Boolean,
    ): String? {
        val open = parseOpenTagAt(text, 0) ?: return null
        if (!isContextEnvelopeTag(open.tagName)) return null
        if (open.selfClosing) {
            return text.substring(open.endExclusive)
        }
        val closeEnd = findMatchingXmlCloseEnd(text, 0, open.tagName, protectedRanges)
        if (closeEnd >= 0) {
            return text.substring(closeEnd)
        }
        if (shouldStripUnclosedEnvelope(text, 0, open, role, streaming)) {
            return ""
        }
        return null
    }

    private fun stripTrailingXmlEnvelope(
        text: String,
        protectedRanges: List<IntRange>,
        role: SenderRole,
        streaming: Boolean,
    ): String? {
        if (text.indexOf('<') < 0) return null
        // 情况 A：文本末尾紧跟闭合标签 </tag>
        if (text.endsWith(">")) {
            val lt = text.lastIndexOf('<')
            if (lt >= 0 && lt + 2 < text.length && text[lt + 1] == '/' && !isProtected(lt, protectedRanges)) {
                val closeTagName = text.substring(lt + 2, text.length - 1).trim()
                if (isContextEnvelopeTag(closeTagName)) {
                    val openStart = findMatchingXmlOpenStartBackward(text, lt, closeTagName, protectedRanges)
                    if (openStart >= 0) {
                        return text.substring(0, openStart)
                    }
                }
            }
        }

        // 情况 B：流式传输或助手终态回复在严格根上下文信封内被截断至末尾（尚未收到 </tag>）
        val unclosedStart = findTrailingUnclosedEnvelopeStart(text, protectedRanges, role, streaming)
        if (unclosedStart >= 0) {
            return text.substring(0, unclosedStart)
        }

        return null
    }

    private fun findTrailingUnclosedEnvelopeStart(
        text: String,
        protectedRanges: List<IntRange>,
        role: SenderRole,
        streaming: Boolean,
    ): Int {
        var idx = text.length - 1
        while (idx >= 0) {
            val lt = text.lastIndexOf('<', idx)
            if (lt < 0) break
            if (!isProtected(lt, protectedRanges) && lt + 1 < text.length && text[lt + 1] != '/') {
                val open = parseOpenTagAt(text, lt)
                if (open != null && !open.selfClosing && isContextEnvelopeTag(open.tagName)) {
                    val closeEnd = findMatchingXmlCloseEnd(text, lt, open.tagName, protectedRanges)
                    if (closeEnd < 0 && shouldStripUnclosedEnvelope(text, lt, open, role, streaming)) {
                        return lt
                    }
                }
            }
            idx = lt - 1
        }
        return -1
    }

    private fun shouldStripUnclosedEnvelope(
        text: String,
        tagStart: Int,
        open: ParsedOpenTag,
        role: SenderRole,
        streaming: Boolean,
    ): Boolean {
        if (streaming) return true
        if (role != SenderRole.ASSISTANT) return false
        if (!isStrictRootContextTag(open.tagName)) return false
        return !looksLikeInlineTagMentionInProse(text, tagStart, open.endExclusive)
    }

    private fun isStrictRootContextTag(tagName: String): Boolean {
        val normalized = normalizeTagName(tagName)
        if (normalized in COMPLETED_UNCLOSED_ROOT_CONTEXT_TAGS) return true
        val parts = normalized.split('_', ':', '-').filter { it.isNotEmpty() }
        if (parts.size < 2) return false
        return parts.any { it in STRICT_UNCLOSED_QUALIFIERS } && parts.any { it in STRICT_UNCLOSED_NOUNS }
    }

    private fun looksLikeInlineTagMentionInProse(text: String, tagStart: Int, afterTagIndex: Int): Boolean {
        val hasLeadingInlineSpace = tagStart > 0 && text[tagStart - 1] != '\n' && text[tagStart - 1].isWhitespace()
        if (!hasLeadingInlineSpace) return false
        val after = text.substring(afterTagIndex)
        if ('\n' in after) return false
        if (after.isEmpty()) return true
        if (!after[0].isWhitespace()) return false
        val trimmedAfter = after.trim()
        if (trimmedAfter.isEmpty()) return true
        if (trimmedAfter.startsWith('<')) return false
        if (trimmedAfter.endsWith('?') || trimmedAfter.endsWith('？')) return true
        return !trimmedAfter[0].isUpperCase()
    }

    private fun trimTrailingPartialTagForStreaming(text: String, protectedRanges: List<IntRange>): String {
        val searchLimit = (text.length - 96).coerceAtLeast(0)
        val lt = text.lastIndexOf('<')
        if (lt < searchLimit) return text
        if (isProtected(lt, protectedRanges)) return text
        // 若 `<` 之后已有 `>`，则不是半截标签
        if (text.indexOf('>', lt + 1) >= 0) return text

        val tail = text.substring(lt)
        // `3 < 5` 等空格分隔算术比较不予拦截
        if (tail == "<" || tail == "</") {
            return text.substring(0, lt)
        }
        val afterBracket = if (tail.startsWith("</")) tail.substring(2) else tail.substring(1)
        if (afterBracket.isEmpty()) return text.substring(0, lt)
        if (!afterBracket[0].isLetter()) return text

        val namePart = afterBracket.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' || it == ':' }
        val restAfterName = afterBracket.substring(namePart.length)
        if (restAfterName.isNotEmpty() && !restAfterName[0].isWhitespace()) return text
        if (restAfterName.contains('\n')) return text

        return if (couldBeContextEnvelopeTagPrefix(namePart)) {
            text.substring(0, lt)
        } else {
            text
        }
    }

    private fun couldBeContextEnvelopeTagPrefix(prefix: String): Boolean {
        val lower = prefix.lowercase()
        if (lower.isEmpty()) return true
        if (isContextEnvelopeTag(prefix)) return true
        if (STREAMING_TAG_PREFIX_ANCHORS.any { anchor -> anchor.startsWith(lower) || lower.startsWith(anchor) }) {
            return true
        }
        val normalized = lower.replace('-', '_').replace(':', '_')
        val firstToken = normalized.substringBefore('_')
        return '_' in normalized && firstToken in CONTEXT_TAG_QUALIFIERS
    }

    private data class ParsedOpenTag(
        val tagName: String,
        val endExclusive: Int,
        val selfClosing: Boolean,
    )

    private fun parseOpenTagAt(text: String, start: Int): ParsedOpenTag? {
        if (start < 0 || start >= text.length || text[start] != '<') return null
        var i = start + 1
        if (i >= text.length || !text[i].isLetter()) return null
        val nameStart = i
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-' || text[i] == ':')) {
            i++
        }
        val tagName = text.substring(nameStart, i)
        if (i >= text.length) return null
        val next = text[i]
        if (next != '>' && next != '/' && !next.isWhitespace()) return null

        val limit = (start + MAX_OPEN_TAG_SCAN).coerceAtMost(text.length)
        var quote: Char? = null
        while (i < limit) {
            val ch = text[i]
            if (quote != null) {
                if (ch == quote) quote = null
            } else {
                when (ch) {
                    '\'', '"' -> quote = ch
                    '>' -> {
                        val selfClosing = i > nameStart && text[i - 1] == '/'
                        return ParsedOpenTag(tagName, i + 1, selfClosing)
                    }
                    '<' -> return null
                }
            }
            i++
        }
        return null
    }

    private fun findMatchingXmlCloseEnd(
        text: String,
        openStart: Int,
        tagName: String,
        protectedRanges: List<IntRange>,
    ): Int {
        val open = parseOpenTagAt(text, openStart) ?: return -1
        if (open.selfClosing) return open.endExclusive
        var depth = 1
        var cursor = open.endExclusive
        while (cursor < text.length) {
            val lt = text.indexOf('<', cursor)
            if (lt < 0) return -1
            // 仅跳过已完整闭合的内部代码块（避免尾部未闭合示例围栏反过来卡住真实闭合标签）
            if (isClosedCodeProtected(lt, text.length, protectedRanges)) {
                cursor = lt + 1
                continue
            }
            if (lt + 1 < text.length && text[lt + 1] == '/') {
                val gt = text.indexOf('>', lt + 2)
                if (gt < 0) return -1
                val closeName = text.substring(lt + 2, gt).trim()
                if (closeName.equals(tagName, ignoreCase = true)) {
                    depth--
                    if (depth == 0) return gt + 1
                }
                cursor = gt + 1
            } else {
                val nested = parseOpenTagAt(text, lt)
                if (nested != null && !nested.selfClosing && nested.tagName.equals(tagName, ignoreCase = true)) {
                    depth++
                    cursor = nested.endExclusive
                } else {
                    cursor = lt + 1
                }
            }
        }
        return -1
    }

    private fun isClosedCodeProtected(index: Int, textLength: Int, protectedRanges: List<IntRange>): Boolean =
        protectedRanges.any { index in it && it.last < textLength - 1 }

    private fun findMatchingXmlOpenStartBackward(
        text: String,
        closeTagStart: Int,
        tagName: String,
        protectedRanges: List<IntRange>,
    ): Int {
        var depth = 1
        var searchEnd = closeTagStart - 1
        while (searchEnd >= 0) {
            val lt = text.lastIndexOf('<', searchEnd)
            if (lt < 0) return -1
            if (!isProtected(lt, protectedRanges)) {
                if (lt + 1 < closeTagStart && text[lt + 1] == '/') {
                    val gt = text.indexOf('>', lt + 2)
                    if (gt in (lt + 2)..closeTagStart) {
                        val innerCloseName = text.substring(lt + 2, gt).trim()
                        if (innerCloseName.equals(tagName, ignoreCase = true)) {
                            depth++
                        }
                    }
                } else {
                    val open = parseOpenTagAt(text, lt)
                    if (open != null && open.endExclusive <= closeTagStart && !open.selfClosing &&
                        open.tagName.equals(tagName, ignoreCase = true)
                    ) {
                        depth--
                        if (depth == 0) return lt
                    }
                }
            }
            searchEnd = lt - 1
        }
        return -1
    }

    private fun stripLeadingBracketEnvelope(text: String, streaming: Boolean): String? {
        if (text.isEmpty()) return null
        val first = text[0]
        if (first != '[' && first != 'S' && first != 's') return null
        val pairedMatch = BRACKET_PAIRED_START_REGEX.find(text)
        if (pairedMatch != null && pairedMatch.range.first == 0) {
            val label = Regex.escape(pairedMatch.groupValues[1])
            val closeRegex = Regex("(?is)\\[/$label\\]")
            val closeMatch = closeRegex.find(text, pairedMatch.range.last + 1)
            if (closeMatch != null) return text.substring(closeMatch.range.last + 1)
            if (streaming) return ""
        }
        BRACKET_SECTION_KV_START_REGEX.find(text)?.takeIf { it.range.first == 0 }?.let {
            return text.substring(it.range.last + 1)
        }
        BRACKET_INLINE_PREFIX_REGEX.find(text)?.takeIf { it.range.first == 0 }?.let {
            return text.substring(it.range.last + 1)
        }
        OPENCLAW_WEEKDAY_TZ_PREFIX_REGEX.find(text)?.takeIf { it.range.first == 0 }?.let {
            return text.substring(it.range.last + 1)
        }
        OPENCLAW_SYSTEM_TIMESTAMP_PREFIX_REGEX.find(text)?.takeIf { it.range.first == 0 }?.let {
            return text.substring(it.range.last + 1)
        }
        return null
    }

    private fun stripTrailingBracketEnvelope(text: String, protectedRanges: List<IntRange>): String? {
        if (text.indexOf('[') < 0 && !text.contains("System:", ignoreCase = true)) return null
        BRACKET_INLINE_SUFFIX_REGEX.find(text)?.let {
            if (!isProtected(it.range.first, protectedRanges)) {
                return text.substring(0, it.range.first)
            }
        }
        return stripTrailingParagraphBlock(text, protectedRanges) { tail, _ ->
            stripLeadingBracketEnvelope(tail, streaming = false)
        }
    }

    private fun stripLeadingMachineSkillSection(text: String): String? {
        if (text.isEmpty() || text[0] != '#') return null
        val heading = SKILL_SECTION_HEADING_REGEX.find(text) ?: return null
        if (heading.range.first != 0) return null
        val bodyStart = heading.range.last + 1
        val blankSplit = TRAILING_PARAGRAPH_SPLIT_REGEX.find(text, bodyStart)
        val blockEnd = blankSplit?.range?.first ?: text.length
        val body = text.substring(bodyStart, blockEnd)
        return if (isMachineSkillCatalogBody(body)) {
            if (blankSplit != null) text.substring(blankSplit.range.last + 1) else ""
        } else {
            null
        }
    }

    private fun isMachineSkillCatalogBody(body: String): Boolean {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        val hasSkillArtifactMarker = lines.any { line ->
            line.contains("SKILL.md", ignoreCase = true) ||
                line.contains("/skills/", ignoreCase = true) ||
                line.contains("<skill", ignoreCase = true) ||
                line.contains("location:", ignoreCase = true)
        }
        if (hasSkillArtifactMarker) return true
        // 若全部是非自然对话的结构化列表/XML/KV 项，才判定为机器技能注入块
        return lines.all { line ->
            line.startsWith("-") ||
                line.startsWith("*") ||
                line.startsWith("<") ||
                KV_LINE_REGEX.matches(line)
        } && lines.any { line -> line.contains(':') }
    }

    private fun stripLeadingRuntimeKvParagraph(text: String): String? {
        if (text.indexOf(':') < 0) return null
        val blankSplit = if (text.indexOf('\n') >= 0) TRAILING_PARAGRAPH_SPLIT_REGEX.find(text) else null
        val firstParagraphEnd = blankSplit?.range?.first ?: text.length
        val paragraph = text.substring(0, firstParagraphEnd)
        if (!isStructuredRuntimeKvParagraph(paragraph)) return null
        return if (blankSplit != null) text.substring(blankSplit.range.last + 1) else ""
    }

    private fun stripTrailingRuntimeKvParagraph(text: String, protectedRanges: List<IntRange>): String? {
        if (text.indexOf(':') < 0) return null
        val starts = findParagraphBoundaryStarts(text)
        val lastStart = starts.lastOrNull() ?: 0
        if (isProtected(lastStart, protectedRanges)) return null
        val paragraph = text.substring(lastStart)
        if (!isStructuredRuntimeKvParagraph(paragraph)) return null
        return text.substring(0, lastStart)
    }

    private fun isStructuredRuntimeKvParagraph(paragraph: String): Boolean {
        var firstNonWs = 0
        while (firstNonWs < paragraph.length && paragraph[firstNonWs].isWhitespace()) firstNonWs++
        if (firstNonWs >= paragraph.length) return false
        val startChar = paragraph[firstNonWs]
        if (startChar !in 'A'..'Z' && startChar !in 'a'..'z') return false
        val lines = paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        val keys = mutableListOf<String>()
        for (line in lines) {
            val first = line[0]
            if (first !in 'A'..'Z' && first !in 'a'..'z') return false
            if (line.indexOf(':') < 0) return false
            val match = KV_LINE_REGEX.matchEntire(line) ?: return false
            val key = match.groupValues[1].trim().lowercase().replace(SUMMARY_WHITESPACE_REGEX, " ")
            if (key !in ALL_RUNTIME_KV_KEYS) return false
            keys += key
        }
        if (keys.any { it in STRONG_RUNTIME_KV_KEYS }) return true
        return keys.size >= 3 && keys.any { it in TEMPORAL_OR_ENV_ANCHOR_KEYS }
    }

    private fun findUnambiguousTelemetryLineRanges(text: String, protectedRanges: List<IntRange>): List<IntRange> {
        if (!text.contains("Browser time zone", ignoreCase = true)) return emptyList()
        val matches = BROWSER_TIMEZONE_LINE_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapNotNull { match ->
            if (isProtected(match.range.first, protectedRanges)) null else match.range
        }
    }

    private fun findMidAssistantStrictTagRanges(text: String, protectedRanges: List<IntRange>): List<IntRange> {
        val removed = mutableListOf<IntRange>()
        var cursor = 0
        while (cursor < text.length) {
            val lt = text.indexOf('<', cursor)
            if (lt < 0) break
            if (!isProtected(lt, protectedRanges)) {
                val open = parseOpenTagAt(text, lt)
                if (open != null && !isMixedPascalCaseUiTag(open.tagName)) {
                    val normalized = normalizeTagName(open.tagName)
                    if (normalized in MID_ASSISTANT_STRICT_INJECTION_TAGS) {
                        if (open.selfClosing) {
                            removed += lt until open.endExclusive
                            cursor = open.endExclusive
                            continue
                        }
                        val closeEnd = findMatchingXmlCloseEnd(text, lt, open.tagName, protectedRanges)
                        if (closeEnd >= 0) {
                            removed += lt until closeEnd
                            cursor = closeEnd
                            continue
                        }
                    }
                }
            }
            cursor = lt + 1
        }
        return removed
    }

    private fun unwrapSingleBodyContainerRange(text: String, streaming: Boolean): IntRange? {
        val open = parseOpenTagAt(text, 0)
        if (open != null && !open.selfClosing) {
            val normalized = normalizeTagName(open.tagName)
            if (normalized in UNWRAPPABLE_BODY_TAGS) {
                val protectedRanges = findProtectedCodeRanges(text, streaming)
                val closeEnd = findMatchingXmlCloseEnd(text, 0, open.tagName, protectedRanges)
                if (closeEnd == text.length) {
                    val closeStart = text.lastIndexOf("</", ignoreCase = true)
                    if (closeStart >= open.endExclusive) {
                        return open.endExclusive until closeStart
                    }
                } else if (closeEnd < 0 && streaming) {
                    return open.endExclusive until text.length
                }
            }
        }
        val bracketHeader = Regex("(?im)^\\[(?:User Message|Actual User Input|Assistant Response)\\]\\s*(?:\\r?\\n|$)").find(text)
        if (bracketHeader != null && bracketHeader.range.first == 0) {
            return (bracketHeader.range.last + 1) until text.length
        }
        val markdownHeader = Regex("(?im)^#{1,3}\\s*(?:User Message|Actual User Input|User Prompt)\\s*(?:\\r?\\n|$)").find(text)
        if (markdownHeader != null && markdownHeader.range.first == 0) {
            return (markdownHeader.range.last + 1) until text.length
        }
        return null
    }

    private fun stripTrailingParagraphBlock(
        text: String,
        protectedRanges: List<IntRange>,
        peelLeading: (tail: String, shiftedProtected: List<IntRange>) -> String?,
    ): String? {
        for (start in findParagraphBoundaryStarts(text)) {
            if (isProtected(start, protectedRanges)) continue
            val tail = text.substring(start)
            val shifted = protectedRanges.mapNotNull { range ->
                val clippedStart = maxOf(range.first, start)
                if (clippedStart > range.last) null else (clippedStart - start)..(range.last - start)
            }
            val peeled = peelLeading(tail, shifted)
            if (peeled != null && peeled.isBlank()) {
                return text.substring(0, start)
            }
        }
        return null
    }

    private fun findParagraphBoundaryStarts(text: String): List<Int> {
        if (text.indexOf('\n') < 0) return listOf(0)
        val starts = mutableListOf(0)
        TRAILING_PARAGRAPH_SPLIT_REGEX.findAll(text).forEach { match ->
            val next = match.range.last + 1
            if (next < text.length) starts += next
        }
        return starts
    }
}
