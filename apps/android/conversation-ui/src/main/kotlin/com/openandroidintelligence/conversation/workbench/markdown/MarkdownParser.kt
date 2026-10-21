package com.openandroidintelligence.conversation.workbench

object MarkdownParser {

    // 代码围栏必须从行首（最多 3 个前导空格）开始，否则引用块里的 "> ```" 会被外层误吞，
    // 导致代码块跑到引用外面。引用块会先剥掉 "> " 再递归解析，围栏在那里才成为真正的围栏。
    private val codeBlockRegex = Regex(
        "^ {0,3}```([a-zA-Z0-9_+:-]*)[\\r\\n]+([\\s\\S]*?)^ {0,3}```[ \\t]*$",
        RegexOption.MULTILINE,
    )
    private val unclosedCodeBlockRegex = Regex(
        "^ {0,3}```([a-zA-Z0-9_+:-]*)[\\r\\n]+([\\s\\S]*)$",
        RegexOption.MULTILINE,
    )
    private val toolCallXmlRegex = Regex(
        "<tool_call(?:\\s+name=[\"']([^\"']*)[\"'])?\\s*>([\\s\\S]*?)</tool_call>",
        RegexOption.IGNORE_CASE
    )
    private val unclosedToolCallXmlRegex = Regex(
        "<tool_call(?:\\s+name=[\"']([^\"']*)[\"'])?\\s*>([\\s\\S]*)$",
        RegexOption.IGNORE_CASE
    )
    /**
     * Hermes / 宿主以纯文本发送的工具调用行，例如「调用工具：execute_code」。
     * 只在独立成行时识别，避免把正文里的同义句子误判成工具卡。
     */
    private val hermesToolLineRegex = Regex(
        "^\\s*[^\\p{L}\\p{N}]*\\s*(?:调用工具|工具调用|Tool call|Calling tool)\\s*[：:]\\s*(.+?)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Hermes 实时进度行（agent/display.py 的友好工具标签），例如
     * 「Running code ...」「Searching files for ...」「Reading ...」。
     * 实时会话里这些是普通文本；不识别就会既没有工具卡、也不会聚合。
     */
    private val hermesActivityLineRegex = Regex(
        "^\\s*[^\\p{L}\\p{N}]*\\s*(" +
            "Running code|Running|Searching files(?: for)?|Searching the web(?: for)?|" +
            "Searching past sessions|Searching|Reading skill|Reading|Writing|Editing|" +
            "Browsing|Clicking|Typing|Generating image|Generating video|Generating speech|" +
            "Looking at the image|Listing skills|Updating skill|Delegating|Scheduling|Asking|" +
            "Updating memory|Updating tasks" +
            ")\\b\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    /** host_history.py 用「、」连接同一条进度消息里的多个工具名。 */
    private val hermesToolNameSeparator = Regex("[、，]")
    private val hermesToolNameAndArgs = Regex("^([A-Za-z0-9._~-]{1,128})(?:\\s+|\\s*[(（]\\s*)(.*)$")
    private val thinkStartRegex = Regex("<think(?:\\s+[^>]*)?>", RegexOption.IGNORE_CASE)
    private val thinkEndRegex = Regex("</think>", RegexOption.IGNORE_CASE)
    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val thematicBreakRegex = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")

    /** 列表项：任意缩进 + 无序标记（-、*、+）或有序标记（1. / 1)）。 */
    private val listItemRegex = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val taskItemRegex = Regex("^\\[([ xX])\\]\\s*(.*)$")

    /** 嵌套列表最多解析 6 级，防御异常深缩进把整条消息压进右侧。 */
    private const val MaxListLevel = 6

    /** 无法从缩进差推出步长时的回退值：GFM 与主流模型默认按 2 空格一级。 */
    private const val DefaultListIndentStep = 2

/** 引用块的最大递归深度；更深的内容扁平化为段落，避免超长 `>>>>…` 打爆调用栈。 */
private const val MaxBlockquoteDepth = 8

    /**
     * 将原始 Markdown 文本解析为 MarkdownBlock / TimelineBlock AST 节点列表。
     * 具备容错能力：未闭合语法安全降级，流式输出增量期间不崩溃。
     */
    fun parse(text: String): List<TimelineBlock> {
        if (text.isEmpty()) {
            return listOf(TimelineBlock.Paragraph(""))
        }
        return parseThoughtBlocks(text)
    }

    /**
     * 解析「叙述性 Markdown」：普通段落、标题、列表、引用、表格与代码块。
     *
     * 与 [parse] 的区别是**不识别** `<think>` / `<tool_call>` 执行块：
     * 思维链正文与执行过程里的叙述本身就可能包含这些标记的示例文本，
     * 再递归解析会生成嵌套卡片（思考卡里再套一张思考卡），所以这里一律当普通文本。
     * fenced `tool_call` 与 `$ adb` 命令兼容路径同样保持为代码块，不升格成工具卡。
     *
     * 解析异常时回退为单段纯文本，保证流式输出期间界面不崩（规格 §3.3 / §6）。
     */
    fun parseNarrative(text: String): List<TimelineBlock> {
        if (text.isEmpty()) {
            return listOf(TimelineBlock.Paragraph(""))
        }
        return runCatching {
            parseContentWithCodeBlocks(text, recognizeToolCalls = false)
                .ifEmpty { listOf(TimelineBlock.Paragraph(text)) }
        }.getOrElse {
            listOf(TimelineBlock.Paragraph(text))
        }
    }

    private fun parseThoughtBlocks(text: String): List<TimelineBlock> {
        if (!text.contains("<think", ignoreCase = true)) {
            return parseContentWithXmlToolCalls(text).ifEmpty { listOf(TimelineBlock.Paragraph(text)) }
        }

        val blocks = mutableListOf<TimelineBlock>()
        var currentIndex = 0

        while (currentIndex < text.length) {
            val startMatch = thinkStartRegex.find(text, startIndex = currentIndex)
            if (startMatch == null) {
                val remaining = text.substring(currentIndex).trim()
                if (remaining.isNotEmpty()) {
                    blocks.addAll(parseContentWithXmlToolCalls(remaining))
                }
                break
            }

            if (startMatch.range.first > currentIndex) {
                val before = text.substring(currentIndex, startMatch.range.first).trim()
                if (before.isNotEmpty()) {
                    blocks.addAll(parseContentWithXmlToolCalls(before))
                }
            }

            val endMatch = thinkEndRegex.find(text, startIndex = startMatch.range.last + 1)
            if (endMatch != null) {
                val thought = text.substring(startMatch.range.last + 1, endMatch.range.first).trim()
                blocks.add(TimelineBlock.ThoughtBlock(thought = thought, isComplete = true))
                currentIndex = endMatch.range.last + 1
            } else {
                val thought = text.substring(startMatch.range.last + 1).trim()
                blocks.add(TimelineBlock.ThoughtBlock(thought = thought, isComplete = false))
                currentIndex = text.length
            }
        }

        return blocks.ifEmpty { listOf(TimelineBlock.Paragraph(text)) }
    }

    private fun parseContentWithXmlToolCalls(text: String): List<TimelineBlock> {
        if (!text.contains("<tool_call", ignoreCase = true)) {
            return parseContentWithCodeBlocks(text)
        }

        val blocks = mutableListOf<TimelineBlock>()
        val matches = toolCallXmlRegex.findAll(text).toList()

        if (matches.isNotEmpty()) {
            var lastIndex = 0
            for (match in matches) {
                val before = text.substring(lastIndex, match.range.first).trim()
                if (before.isNotEmpty()) {
                    blocks.addAll(parseContentWithCodeBlocks(before))
                }
                val toolNameAttr = match.groups[1]?.value?.trim()?.ifBlank { null } ?: "执行命令"
                val content = match.groupValues[2]
                blocks.add(parseXmlToolCallContent(toolNameAttr, content))
                lastIndex = match.range.last + 1
            }
            val remaining = text.substring(lastIndex).trim()
            if (remaining.isNotEmpty()) {
                // 剩余文本里可能还有尚未闭合的 <tool_call>（流式下一段工具调用）。
                // 继续走同一入口，才能把它标成 isRunning，而不是当成普通段落。
                blocks.addAll(parseContentWithXmlToolCalls(remaining))
            }
            return blocks
        }

        // 容错流式未闭合 <tool_call>：闭合标签还没写成，标记为仍在执行（isRunning = true）。
        val unclosedMatch = unclosedToolCallXmlRegex.find(text)
        if (unclosedMatch != null) {
            val before = text.substring(0, unclosedMatch.range.first).trim()
            if (before.isNotEmpty()) {
                blocks.addAll(parseContentWithCodeBlocks(before))
            }
            val toolNameAttr = unclosedMatch.groups[1]?.value?.trim()?.ifBlank { null } ?: "执行命令"
            val content = unclosedMatch.groupValues[2]
            blocks.add(parseXmlToolCallContent(toolNameAttr, content).copy(isRunning = true))
            return blocks
        }

        return parseContentWithCodeBlocks(text)
    }

    /**
     * 代码围栏解析主路径。
     *
     * [recognizeToolCalls] 为 false 时（[parseNarrative] 使用）所有围栏一律当普通代码块，
     * 不再把 `tool_call` 围栏或 `$ adb` 兼容段落升格成工具卡，避免思维链正文里
     * 出现嵌套的执行卡片。
     */
    private fun parseContentWithCodeBlocks(
        text: String,
        quoteDepth: Int = 0,
        recognizeToolCalls: Boolean = true,
    ): List<TimelineBlock> {
        if (!text.contains("```")) {
            return parseNonCodeText(text, quoteDepth, recognizeToolCalls)
        }

        val matches = codeBlockRegex.findAll(text).toList()
        if (matches.isNotEmpty()) {
            val blocks = mutableListOf<TimelineBlock>()
            var lastIndex = 0
            for (match in matches) {
                val before = text.substring(lastIndex, match.range.first).trim()
                if (before.isNotEmpty()) {
                    blocks.addAll(parseNonCodeText(before, quoteDepth, recognizeToolCalls))
                }
                val language = match.groupValues[1].trim()
                val code = match.groupValues[2].trimEnd()

                val toolCall = if (recognizeToolCalls) tryParseCodeBlockAsToolCall(language, code) else null
                if (toolCall != null) {
                    blocks.add(toolCall)
                } else {
                    blocks.add(TimelineBlock.CodeBlock(language.ifBlank { "代码" }, code))
                }
                lastIndex = match.range.last + 1
            }
            val remaining = text.substring(lastIndex).trim()
            if (remaining.isNotEmpty()) {
                // 剩余文本可能包含尚未闭合的下一个围栏（流式工具调用/代码块）。
                // 继续走同一入口，才能把它识别成 CodeBlock/ToolCall，而不是把
                // "```tool_call" 原样当最终回答渲染。
                blocks.addAll(
                    parseContentWithCodeBlocks(
                        remaining,
                        quoteDepth = quoteDepth,
                        recognizeToolCalls = recognizeToolCalls,
                    ),
                )
            }
            return blocks
        }

        // 容错流式未闭合代码块：未闭合即代表流还没写完，工具卡标记为仍在执行。
        val unclosedCodeMatch = unclosedCodeBlockRegex.find(text)
        if (unclosedCodeMatch != null) {
            val blocks = mutableListOf<TimelineBlock>()
            val before = text.substring(0, unclosedCodeMatch.range.first).trim()
            if (before.isNotEmpty()) {
                blocks.addAll(parseNonCodeText(before, quoteDepth, recognizeToolCalls))
            }
            val language = unclosedCodeMatch.groupValues[1].trim()
            val code = unclosedCodeMatch.groupValues[2].trimEnd()
            val toolCall = if (recognizeToolCalls) tryParseCodeBlockAsToolCall(language, code) else null
            if (toolCall != null) {
                blocks.add(toolCall.copy(isRunning = true))
            } else {
                blocks.add(TimelineBlock.CodeBlock(language.ifBlank { "代码" }, code))
            }
            return blocks
        }

        return parseNonCodeText(text, quoteDepth, recognizeToolCalls)
    }

    private fun tryParseCodeBlockAsToolCall(language: String, code: String): TimelineBlock.ToolCallBlock? {
        val langLower = language.lowercase().trim()
        if (langLower.startsWith("tool_call") || langLower.startsWith("tool:")) {
            val toolName = when {
                langLower.contains(":") -> language.substringAfter(":").trim().ifBlank { "执行命令" }
                else -> "执行命令"
            }
            return parseToolCallFromFencedCode(toolName, code)
        }

        // 兼容普通命令代码块：当代码块首行为 $ adb shell ... 且后续紧跟状态提示时，自动聚合成复合工具卡片
        if (langLower in listOf("bash", "sh", "shell", "adb", "terminal", "console", "cmd", "")) {
            val trimmed = code.trim()
            val lines = trimmed.lines()
            val firstLine = lines.firstOrNull()?.trim() ?: ""
            val remainingLines = lines.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
            if (firstLine.startsWith("$ adb") && remainingLines.isNotEmpty()) {
                val toolName = if (firstLine.contains("shell")) "adb_shell" else "adb"
                val command = firstLine.removePrefix("$ ").trim()
                val remainingText = remainingLines.joinToString("\n")
                val isFail = isExecutionFailure(remainingText)
                val isSuccess = !isFail
                val (summary, output) = if (remainingLines.size == 1 && remainingText.length <= 80) {
                    remainingText to null
                } else {
                    (if (isSuccess) "执行成功" else "执行失败") to remainingText
                }
                return TimelineBlock.ToolCallBlock(
                    toolName = toolName,
                    command = command,
                    output = output,
                    isSuccess = isSuccess,
                    summary = summary,
                )
            }
        }

        return null
    }

    fun isExecutionFailure(text: String): Boolean =
        text.contains("error", ignoreCase = true) ||
            text.contains("failed", ignoreCase = true) ||
            text.contains("failure", ignoreCase = true)

    private fun parseToolCallFromFencedCode(toolName: String, code: String): TimelineBlock.ToolCallBlock {
        val trimmed = code.trim()
        if (trimmed.contains("<command>")) {
            return parseXmlToolCallContent(toolName, trimmed)
        }

        val delimiterRegex = Regex("\n(?:---+|===+|Output:|Result:|\\[output\\]|\\[result\\])\n?", RegexOption.IGNORE_CASE)
        val splitMatch = delimiterRegex.find(trimmed)
        if (splitMatch != null) {
            val cmdPart = trimmed.substring(0, splitMatch.range.first).trim().removePrefix("$ ").trim()
            val outPart = trimmed.substring(splitMatch.range.last + 1).trim()
            val isFail = isExecutionFailure(outPart)
            val isSuccess = !isFail
            val lines = outPart.lines()
            val summary = if (lines.size == 1 && lines[0].length <= 80) lines[0] else if (isSuccess) "执行成功" else "执行失败"
            val output = if (lines.size > 1 || lines[0].length > 80) outPart else null
            return TimelineBlock.ToolCallBlock(
                toolName = toolName,
                command = cmdPart,
                output = output,
                isSuccess = isSuccess,
                summary = summary,
            )
        }

        if (trimmed.startsWith("$ ")) {
            val lines = trimmed.lines()
            val cmd = lines[0].removePrefix("$ ").trim()
            val remaining = lines.drop(1).joinToString("\n").trim()
            if (remaining.isNotEmpty()) {
                val isFail = isExecutionFailure(remaining)
                val isSuccess = !isFail
                val summary = if (lines.size == 2 && remaining.length <= 80) remaining else if (isSuccess) "执行成功" else "执行失败"
                val output = if (lines.size > 2 || remaining.length > 80) remaining else null
                return TimelineBlock.ToolCallBlock(
                    toolName = toolName,
                    command = cmd,
                    output = output,
                    isSuccess = isSuccess,
                    summary = summary,
                )
            } else {
                return TimelineBlock.ToolCallBlock(
                    toolName = toolName,
                    command = cmd,
                    output = null,
                    isSuccess = true,
                    summary = null,
                )
            }
        }

        return TimelineBlock.ToolCallBlock(
            toolName = toolName,
            command = trimmed,
            output = null,
            isSuccess = true,
            summary = null,
        )
    }

    private fun parseXmlToolCallContent(defaultToolName: String, content: String): TimelineBlock.ToolCallBlock {
        val toolNameTag = Regex("<tool_name>([\\s\\S]*?)</tool_name>", RegexOption.IGNORE_CASE)
            .find(content)?.groupValues?.get(1)?.trim()
        val toolName = toolNameTag?.ifBlank { null } ?: defaultToolName.ifBlank { "执行命令" }

        val command = Regex("<command>([\\s\\S]*?)(?:</command>|$)", RegexOption.IGNORE_CASE)
            .find(content)?.groupValues?.get(1)?.trim()
            ?: Regex("<cmd>([\\s\\S]*?)(?:</cmd>|$)", RegexOption.IGNORE_CASE)
                .find(content)?.groupValues?.get(1)?.trim()
            ?: content.substringBefore("<result").substringBefore("<output").trim()

        val resultMatch = Regex("<result(?:\\s+status=[\"']([^\"']*)[\"'])?\\s*>([\\s\\S]*?)(?:</result>|$)", RegexOption.IGNORE_CASE)
            .find(content)

        val outputMatch = Regex("<output>([\\s\\S]*?)(?:</output>|$)", RegexOption.IGNORE_CASE)
            .find(content)

        val summaryMatch = Regex("<summary>([\\s\\S]*?)(?:</summary>|$)", RegexOption.IGNORE_CASE)
            .find(content)

        val errorMatch = Regex("<error>([\\s\\S]*?)(?:</error>|$)", RegexOption.IGNORE_CASE)
            .find(content)

        var isSuccess = true
        if (resultMatch != null) {
            val status = resultMatch.groupValues[1].trim().lowercase()
            if (status in listOf("failed", "error", "failure", "false")) {
                isSuccess = false
            }
        }
        if (errorMatch != null) {
            isSuccess = false
        }

        var summary: String? = summaryMatch?.groupValues?.get(1)?.trim()?.ifBlank { null }
        var output: String? = outputMatch?.groupValues?.get(1)?.trim()?.ifBlank { null }

        if (errorMatch != null && output == null) {
            output = errorMatch.groupValues[1].trim().ifBlank { null }
            if (summary == null) summary = "执行失败"
        }

        if (resultMatch != null) {
            val resBody = resultMatch.groupValues[2].trim()
            if (output == null && resBody.contains("\n")) {
                output = resBody
                if (summary == null) summary = if (isSuccess) "执行成功" else "执行失败"
            } else if (summary == null && resBody.isNotBlank()) {
                summary = resBody
            }
        }

        if (summary == null && output != null) {
            summary = if (isSuccess) "执行成功" else "执行失败"
        }

        return TimelineBlock.ToolCallBlock(
            toolName = toolName,
            command = command,
            output = output,
            isSuccess = isSuccess,
            summary = summary,
        )
    }

    /**
     * 解析 Hermes 的纯文本工具调用行：工具名后的剩余文本作为命令/参数，
     * 没有参数时标记 [TimelineBlock.ToolCallBlock.invocationOnly]。
     */
    private fun parseHermesToolLines(
        text: String,
        quoteDepth: Int,
        includeActivities: Boolean,
    ): List<TimelineBlock> {
        val blocks = mutableListOf<TimelineBlock>()
        val pending = mutableListOf<String>()

        fun flushPending() {
            if (pending.isEmpty()) return
            val chunk = pending.joinToString("\n").trim()
            pending.clear()
            if (chunk.isNotEmpty()) {
                // 已拆掉全部工具行，递归不会再命中 Hermes 分支。
                blocks.addAll(parseNonCodeText(chunk, quoteDepth, recognizeToolCalls = true))
            }
        }

        text.lines().forEach { line ->
            val match = hermesToolLineRegex.find(line.trim())
            if (match == null) {
                val activity = if (includeActivities) hermesActivityLineRegex.find(line.trim()) else null
                if (activity == null) {
                    pending += line
                } else {
                    flushPending()
                    val verb = activity.groupValues[1]
                    val command = activity.groupValues[2].trim()
                    blocks += TimelineBlock.ToolCallBlock(
                        toolName = hermesToolNameForVerb(verb),
                        command = command,
                        isSuccess = true,
                        summary = null,
                        invocationOnly = true,
                    )
                }
            } else {
                flushPending()
                val namesText = match.groupValues[1]
                    .trim()
                    .trim('*', '`', ' ')
                // 一条消息可能写成「调用工具：execute_code、search_files」：每个名称一张卡。
                namesText.split(hermesToolNameSeparator)
                    .map { it.trim().trim('*', '`', ' ') }
                    .filter { it.isNotEmpty() }
                    .forEach { segment ->
                        val withArgs = hermesToolNameAndArgs.find(segment)
                        val toolName: String
                        val command: String
                        if (withArgs != null) {
                            toolName = withArgs.groupValues[1]
                            command = withArgs.groupValues[2].trim()
                        } else {
                            toolName = segment
                            command = ""
                        }
                        blocks += TimelineBlock.ToolCallBlock(
                            toolName = toolName.ifBlank { "执行命令" },
                            command = command,
                            isSuccess = true,
                            summary = null,
                            invocationOnly = true,
                        )
                    }
            }
        }
        flushPending()
        return blocks.ifEmpty { listOf(TimelineBlock.Paragraph(text)) }
    }

/**
 * 是否为 Hermes 工具占位行，或整条消息都是实时工具进度行。
 *
 * 活动行只在“整条消息没有别的内容”时识别：最终回答里出现一句
 * "Reading the error message..." 不能被误判成 read_file 工具卡。
 */
internal fun mayContainHermesToolText(text: String): Boolean {
    if (text.lineSequence().any { hermesToolLineRegex.matches(it.trim()) }) return true
    return isHermesActivityOnlyText(text)
}

internal fun isHermesActivityOnlyText(text: String): Boolean {
    val lines = text.lines().filter { it.isNotBlank() }
    // 至少两行，且行尾没有句读：单行或带句号的 "Reading your question..." 更像正文，
    // 而不是 Hermes 的工具进度预览。
    return lines.size >= 2 && lines.all { line ->
        val trimmed = line.trim()
        // Hermes 的预览会用 "..." 截断长命令，这仍是进度行；单个句号/问号/感叹号
        // 才更像正文句尾。
        val truncatedPreview = trimmed.endsWith("...")
        val sentenceEnd = !truncatedPreview &&
            (trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?"))
        hermesActivityLineRegex.matches(trimmed) && !sentenceEnd
    }
}

    /** 把 Hermes 的友好动词映射回工具名；未知动词保留原文作为工具名。 */
    private fun hermesToolNameForVerb(verb: String): String = when (verb.trim().lowercase()) {
        "running code" -> "execute_code"
        "running" -> "terminal"
        "searching files", "searching files for" -> "search_files"
        "searching the web", "searching the web for" -> "web_search"
        "searching past sessions" -> "session_search"
        "reading", "reading skill" -> "read_file"
        "writing" -> "write_file"
        "editing" -> "patch"
        "browsing" -> "browser_navigate"
        "clicking" -> "browser_click"
        "typing" -> "browser_type"
        "generating image" -> "image_generate"
        "generating video" -> "video_generate"
        "generating speech" -> "text_to_speech"
        "looking at the image" -> "vision_analyze"
        "listing skills" -> "skills_list"
        "updating skill" -> "skill_manage"
        "delegating" -> "delegate_task"
        "scheduling" -> "cronjob_manage"
        "asking" -> "clarify"
        "updating memory" -> "memory"
        "updating tasks" -> "todo_list"
        else -> verb.trim()
    }

    private fun parseNonCodeText(
        text: String,
        quoteDepth: Int = 0,
        recognizeToolCalls: Boolean = true,
    ): List<TimelineBlock> {
        // Hermes 的工具调用是纯文本行（不是 XML/围栏），必须先拆成 ToolCallBlock，
        // 否则它会被当成普通段落：既没有工具卡，也不会参与执行过程聚合。
        if (recognizeToolCalls && mayContainHermesToolText(text)) {
            val hasExplicitToolLine = text.lineSequence().any { hermesToolLineRegex.matches(it.trim()) }
            return parseHermesToolLines(
                text = text,
                quoteDepth = quoteDepth,
                includeActivities = !hasExplicitToolLine && isHermesActivityOnlyText(text),
            )
        }
        val lines = text.lines()
        val blocks = mutableListOf<TimelineBlock>()
        var lineIndex = 0

        while (lineIndex < lines.size) {
            val line = lines[lineIndex]

            // 跳过空行
            if (line.isBlank()) {
                lineIndex++
                continue
            }

            // 1. 分割线
            if (thematicBreakRegex.matches(line)) {
                blocks.add(TimelineBlock.ThematicBreak)
                lineIndex++
                continue
            }

            // 2. 标题
            val headingMatch = headingRegex.matchEntire(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val title = headingMatch.groupValues[2].trim()
                blocks.add(TimelineBlock.Heading(level, title))
                lineIndex++
                continue
            }

            // 3. 块级数学公式 $$ ... $$
            val mathBlock = parseMathBlock(lines, lineIndex)
            if (mathBlock != null) {
                blocks.add(mathBlock.block)
                lineIndex = mathBlock.nextLine
                continue
            }
            // `$$` 开头但未形成合法公式（例如仅有 `$$`、或闭合后还有正文）：
            // 必须按段落消费当前行，否则外层循环不会推进，组合期会死循环。
            if (line.trim().startsWith("$$")) {
                val paragraphLines = mutableListOf(line)
                lineIndex++
                while (lineIndex < lines.size &&
                    lines[lineIndex].isNotBlank() &&
                    !startsStructuralBlock(lines, lineIndex)
                ) {
                    paragraphLines.add(lines[lineIndex])
                    lineIndex++
                }
                blocks.add(TimelineBlock.Paragraph(paragraphLines.joinToString("\n")))
                continue
            }

            // 4. 表格检测
            if (line.contains("|") && lineIndex + 1 < lines.size && isTableSeparatorRow(lines[lineIndex + 1])) {
                val headers = parseTableCells(line)
                val separatorCells = parseTableCells(lines[lineIndex + 1])
                val alignments = separatorCells.map { cell ->
                    val trimmed = cell.trim()
                    when {
                        trimmed.startsWith(":") && trimmed.endsWith(":") -> TableAlignment.CENTER
                        trimmed.endsWith(":") -> TableAlignment.RIGHT
                        else -> TableAlignment.LEFT
                    }
                }
                lineIndex += 2
                val rows = mutableListOf<List<String>>()
                while (lineIndex < lines.size && lines[lineIndex].isNotBlank() && lines[lineIndex].contains("|")) {
                    rows.add(parseTableCells(lines[lineIndex]))
                    lineIndex++
                }
                blocks.add(TimelineBlock.Table(headers, alignments, rows))
                continue
            }

            // 5. 引用块：剥掉一层 `>` 后递归解析，嵌套引用自然形成 Blockquote(Blockquote(...))
            if (isBlockquoteLine(line)) {
                val quoteLines = mutableListOf<String>()
                val atDepthLimit = quoteDepth >= MaxBlockquoteDepth
                while (lineIndex < lines.size && isBlockquoteLine(lines[lineIndex])) {
                    val stripped = if (atDepthLimit) {
                        stripAllBlockquoteMarkers(lines[lineIndex])
                    } else {
                        stripBlockquoteMarker(lines[lineIndex])
                    }
                    quoteLines.add(stripped)
                    lineIndex++
                }
                val innerText = quoteLines.joinToString("\n")
                if (innerText.isNotBlank()) {
                    if (atDepthLimit) {
                        // 超出深度预算：保留内容但停止递归，避免无限嵌套与栈溢出
                        blocks.add(TimelineBlock.Paragraph(innerText.trim()))
                    } else {
                        val innerBlocks = parseContentWithCodeBlocks(
                            innerText,
                            quoteDepth + 1,
                            recognizeToolCalls = recognizeToolCalls,
                        )
                        if (innerBlocks.isNotEmpty()) {
                            blocks.add(TimelineBlock.Blockquote(innerBlocks))
                        }
                    }
                }
                continue
            }

            // 6. 列表：一次收集整个列表（含嵌套项），层级与任务标记在解析阶段定好
            val listBlock = parseListBlock(lines, lineIndex)
            if (listBlock != null) {
                blocks.add(listBlock.block)
                lineIndex = listBlock.nextLine
                continue
            }

            // 7. 普通段落：收集连续非空非结构行
            val paragraphLines = mutableListOf<String>()
            while (lineIndex < lines.size) {
                val current = lines[lineIndex]
                if (current.isBlank()) break
                // 如果遇到新的结构开始，中断段落
                if (startsStructuralBlock(lines, lineIndex)) {
                    break
                }
                paragraphLines.add(current)
                lineIndex++
            }
            if (paragraphLines.isNotEmpty()) {
                blocks.add(TimelineBlock.Paragraph(paragraphLines.joinToString("\n")))
            }
        }

        return blocks
    }

    /**
     * 当前行是否是段落的「结构中断点」：标题、分割线、引用、列表、数学块、表格。
     * 段落收集循环与列表续行归并共用同一判定，避免两处规则漂移。
     */
    private fun startsStructuralBlock(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        if (thematicBreakRegex.matches(line)) return true
        if (headingRegex.matches(line)) return true
        if (isBlockquoteLine(line)) return true
        if (listItemRegex.matches(line)) return true
        if (line.trim().startsWith("$$")) return true
        if (line.contains("|") && index + 1 < lines.size && isTableSeparatorRow(lines[index + 1])) return true
        return false
    }

    private fun isBlockquoteLine(line: String): Boolean = line.trimStart().startsWith(">")

    /** 去掉引用行所有层级的 `>` 标记；仅在达到递归深度预算时用于扁平化展示。 */
    private fun stripAllBlockquoteMarkers(line: String): String {
        var rest = line.trimStart()
        while (rest.startsWith(">")) {
            rest = rest.substring(1)
            if (rest.startsWith(" ")) rest = rest.substring(1)
        }
        return rest
    }

    /** 去掉引用行的一层 `>` 标记与紧随其后的一个空格，保留剩余缩进供递归解析。 */
    private fun stripBlockquoteMarker(line: String): String {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith(">")) return line
        val rest = trimmed.substring(1)
        return if (rest.startsWith(" ")) rest.substring(1) else rest
    }

    private data class ParsedMathBlock(
        val block: TimelineBlock.MathBlock,
        val nextLine: Int,
    )

    /**
     * 识别 `$$ ... $$` 块级公式：
     * - 单行 `$$x$$` 直接解析；闭合后还有正文时返回 null，交给行内公式降级处理；
     * - `$$` 独立成行时收集到下一个以 `$$` 结尾的行；
     * - 尚未闭合时把已收到的内容当作公式，流式期间不把 `$$` 暴露在正文里；
     * - 内容为空的 `$$` / `$$$$` 返回 null，由调用方按普通段落消费（保证游标推进）。
     */
    private fun parseMathBlock(lines: List<String>, start: Int): ParsedMathBlock? {
        val trimmed = lines[start].trim()
        if (!trimmed.startsWith("$$")) return null

        val rest = trimmed.substring(2)
        val sameLineClose = rest.indexOf("$$")
        if (sameLineClose >= 0) {
            val latex = rest.substring(0, sameLineClose).trim()
            val trailing = rest.substring(sameLineClose + 2).trim()
            // 闭合后还有正文：整行按行内公式解析，避免吞掉后续内容
            if (latex.isEmpty() || trailing.isNotEmpty()) return null
            return ParsedMathBlock(TimelineBlock.MathBlock(latex), start + 1)
        }

        val body = StringBuilder(rest.trim())
        var index = start + 1
        while (index < lines.size) {
            val currentTrimmed = lines[index].trim()
            if (currentTrimmed.endsWith("$$")) {
                val content = currentTrimmed.removeSuffix("$$").trim()
                if (content.isNotEmpty()) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(content)
                }
                val latex = body.toString().trim()
                return if (latex.isEmpty()) null else ParsedMathBlock(TimelineBlock.MathBlock(latex), index + 1)
            }
            // 行内出现 `$$` 但后面还有文字：说明这一行不是块级闭合，整体降级为段落
            if (currentTrimmed.contains("$$")) return null
            if (body.isNotEmpty()) body.append('\n')
            body.append(lines[index].trimEnd())
            index++
        }

        val latex = body.toString().trim()
        return if (latex.isEmpty()) null else ParsedMathBlock(TimelineBlock.MathBlock(latex), lines.size)
    }

    private data class ParsedListBlock(
        val block: TimelineBlock.ListBlock,
        val nextLine: Int,
    )

    private data class RawListItem(
        val indent: Int,
        val ordinal: Int?,
        val text: String,
    )

    /**
     * 收集连续的列表项（含更深缩进的子项），统一算出层级、序号与任务状态。
     * 判定顺序：先按行收集原始缩进，再用「最小正缩进差」推断每级步长，
     * 因此 2 空格与 4 空格两种主流写法都能得到正确的父子关系。
     */
    private fun parseListBlock(lines: List<String>, start: Int): ParsedListBlock? {
        val firstMatch = listItemRegex.matchEntire(lines[start]) ?: return null
        val firstIndent = indentColumns(firstMatch.groupValues[1])

        val raws = mutableListOf<RawListItem>()
        var lastItemIndent = firstIndent
        var index = start

        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) break

            val match = listItemRegex.matchEntire(line)
            if (match != null) {
                val indent = indentColumns(match.groupValues[1])
                val marker = match.groupValues[2]
                // `-` 等无序标记没有序号；`1.` / `1)` 取数字部分
                val ordinal = marker.dropLast(1).toIntOrNull()
                raws.add(RawListItem(indent = indent, ordinal = ordinal, text = match.groupValues[3]))
                lastItemIndent = indent
                index++
                continue
            }

            // 列表项的折行：缩进比当前项更深且不是新结构时并入上一项
            val indent = indentColumns(line)
            if (raws.isNotEmpty() && indent > lastItemIndent && !startsStructuralBlock(lines, index)) {
                val last = raws.removeAt(raws.lastIndex)
                raws.add(last.copy(text = last.text + " " + line.trim()))
                index++
                continue
            }
            break
        }

        if (raws.isEmpty()) return null

        // 基准取所有项里的最小缩进，而不是第一项：首项缩进更深时，
        // 后面的浅层项与再次嵌套的子项仍能得到正确的父子层级。
        val baseIndent = raws.minOf { it.indent }
        val distinctIndents = raws.map { it.indent }.distinct().sorted()
        val step = distinctIndents
            .zipWithNext { previous, next -> next - previous }
            .filter { it > 0 }
            .minOrNull()
            ?: DefaultListIndentStep

        val nextNumberAtLevel = mutableMapOf<Int, Int>()
        val previousOrderedAtLevel = mutableMapOf<Int, Boolean>()

        val items = raws.map { raw ->
            val offset = (raw.indent - baseIndent).coerceAtLeast(0)
            val level = ((offset + step / 2) / step).coerceIn(0, MaxListLevel)

            // 进入新的父项后，更深的子列表是新的编号序列（2. B 下面的子项应从 1. 重新开始）
            for (deeper in (level + 1)..MaxListLevel) {
                nextNumberAtLevel.remove(deeper)
                previousOrderedAtLevel.remove(deeper)
            }

            val (text, taskState) = parseTaskState(raw.text)
            val number = if (raw.ordinal != null) {
                val continueNumbering = previousOrderedAtLevel[level] == true
                val value = if (continueNumbering) nextNumberAtLevel[level] ?: raw.ordinal else raw.ordinal
                nextNumberAtLevel[level] = value + 1
                previousOrderedAtLevel[level] = true
                value
            } else {
                previousOrderedAtLevel[level] = false
                null
            }
            MarkdownListItem(
                text = text,
                level = level,
                taskState = taskState,
                number = number,
            )
        }

        return ParsedListBlock(TimelineBlock.ListBlock(items), index)
    }

    private fun parseTaskState(text: String): Pair<String, TaskState> {
        val match = taskItemRegex.matchEntire(text) ?: return text to TaskState.NONE
        val state = if (match.groupValues[1].equals("x", ignoreCase = true)) {
            TaskState.CHECKED
        } else {
            TaskState.UNCHECKED
        }
        return match.groupValues[2].trim() to state
    }

    /** 缩进换算成列数：制表符按 4 列，普通空格按 1 列，其余空白按 2 列。 */
    private fun indentColumns(indent: String): Int {
        var columns = 0
        for (character in indent) {
            columns += when (character) {
                '\t' -> 4
                ' ' -> 1
                else -> 2
            }
        }
        return columns
    }

    private fun isTableSeparatorRow(line: String): Boolean {
        if (!line.contains("|")) return false
        val cells = parseTableCells(line)
        return cells.isNotEmpty() && cells.all { cell ->
            val trimmed = cell.trim()
            trimmed.matches(Regex("^:?-+:?$"))
        }
    }

    private fun parseTableCells(line: String): List<String> {
        var trimmed = line.trim()
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1)
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length - 1)
        return trimmed.split("|").map { it.trim() }
    }
}
