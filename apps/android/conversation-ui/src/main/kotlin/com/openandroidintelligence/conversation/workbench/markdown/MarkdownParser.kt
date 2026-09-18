package com.openandroidintelligence.conversation.workbench

object MarkdownParser {

    private val codeBlockRegex = Regex("```([a-zA-Z0-9_+:-]*)[\\r\\n]+([\\s\\S]*?)```")
    private val unclosedCodeBlockRegex = Regex("```([a-zA-Z0-9_+:-]*)[\\r\\n]+([\\s\\S]*)$")
    private val toolCallXmlRegex = Regex(
        "<tool_call(?:\\s+name=[\"']([^\"']*)[\"'])?\\s*>([\\s\\S]*?)</tool_call>",
        RegexOption.IGNORE_CASE
    )
    private val unclosedToolCallXmlRegex = Regex(
        "<tool_call(?:\\s+name=[\"']([^\"']*)[\"'])?\\s*>([\\s\\S]*)$",
        RegexOption.IGNORE_CASE
    )
    private val thinkStartRegex = Regex("<think(?:\\s+[^>]*)?>", RegexOption.IGNORE_CASE)
    private val thinkEndRegex = Regex("</think>", RegexOption.IGNORE_CASE)
    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val unorderedListRegex = Regex("^\\s*[-*+]\\s+(.*)$")
    private val orderedListRegex = Regex("^\\s*(\\d+)\\.\\s+(.*)$")
    private val thematicBreakRegex = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")

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
                blocks.addAll(parseContentWithCodeBlocks(remaining))
            }
            return blocks
        }

        // 容错流式未闭合 <tool_call>
        val unclosedMatch = unclosedToolCallXmlRegex.find(text)
        if (unclosedMatch != null) {
            val before = text.substring(0, unclosedMatch.range.first).trim()
            if (before.isNotEmpty()) {
                blocks.addAll(parseContentWithCodeBlocks(before))
            }
            val toolNameAttr = unclosedMatch.groups[1]?.value?.trim()?.ifBlank { null } ?: "执行命令"
            val content = unclosedMatch.groupValues[2]
            blocks.add(parseXmlToolCallContent(toolNameAttr, content))
            return blocks
        }

        return parseContentWithCodeBlocks(text)
    }

    private fun parseContentWithCodeBlocks(text: String): List<TimelineBlock> {
        if (!text.contains("```")) {
            return parseNonCodeText(text)
        }

        val matches = codeBlockRegex.findAll(text).toList()
        if (matches.isNotEmpty()) {
            val blocks = mutableListOf<TimelineBlock>()
            var lastIndex = 0
            for (match in matches) {
                val before = text.substring(lastIndex, match.range.first).trim()
                if (before.isNotEmpty()) {
                    blocks.addAll(parseNonCodeText(before))
                }
                val language = match.groupValues[1].trim()
                val code = match.groupValues[2].trimEnd()

                val toolCall = tryParseCodeBlockAsToolCall(language, code)
                if (toolCall != null) {
                    blocks.add(toolCall)
                } else {
                    blocks.add(TimelineBlock.CodeBlock(language.ifBlank { "代码" }, code))
                }
                lastIndex = match.range.last + 1
            }
            val remaining = text.substring(lastIndex).trim()
            if (remaining.isNotEmpty()) {
                blocks.addAll(parseNonCodeText(remaining))
            }
            return blocks
        }

        // 容错流式未闭合代码块
        val unclosedCodeMatch = unclosedCodeBlockRegex.find(text)
        if (unclosedCodeMatch != null) {
            val blocks = mutableListOf<TimelineBlock>()
            val before = text.substring(0, unclosedCodeMatch.range.first).trim()
            if (before.isNotEmpty()) {
                blocks.addAll(parseNonCodeText(before))
            }
            val language = unclosedCodeMatch.groupValues[1].trim()
            val code = unclosedCodeMatch.groupValues[2].trimEnd()
            val toolCall = tryParseCodeBlockAsToolCall(language, code)
            if (toolCall != null) {
                blocks.add(toolCall)
            } else {
                blocks.add(TimelineBlock.CodeBlock(language.ifBlank { "代码" }, code))
            }
            return blocks
        }

        return parseNonCodeText(text)
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

    private fun parseNonCodeText(text: String): List<TimelineBlock> {
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

            // 3. 表格检测
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

            // 4. 引用块
            if (line.trimStart().startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (lineIndex < lines.size && lines[lineIndex].trimStart().startsWith(">")) {
                    val qLine = lines[lineIndex].trimStart().removePrefix(">").trimStart()
                    quoteLines.add(qLine)
                    lineIndex++
                }
                blocks.add(TimelineBlock.Blockquote(quoteLines.joinToString("\n")))
                continue
            }

            // 5. 无序列表
            if (unorderedListRegex.matches(line)) {
                val items = mutableListOf<String>()
                while (lineIndex < lines.size) {
                    val m = unorderedListRegex.matchEntire(lines[lineIndex]) ?: break
                    items.add(m.groupValues[1])
                    lineIndex++
                }
                blocks.add(TimelineBlock.UnorderedList(items))
                continue
            }

            // 6. 有序列表
            val orderedMatch = orderedListRegex.matchEntire(line)
            if (orderedMatch != null) {
                val startNumber = orderedMatch.groupValues[1].toIntOrNull() ?: 1
                val items = mutableListOf<String>()
                while (lineIndex < lines.size) {
                    val m = orderedListRegex.matchEntire(lines[lineIndex]) ?: break
                    items.add(m.groupValues[2])
                    lineIndex++
                }
                blocks.add(TimelineBlock.OrderedList(items, startNumber))
                continue
            }

            // 7. 普通段落：收集连续非空非结构行
            val paragraphLines = mutableListOf<String>()
            while (lineIndex < lines.size) {
                val current = lines[lineIndex]
                if (current.isBlank()) break
                // 如果遇到新的结构开始，中断段落
                if (thematicBreakRegex.matches(current) ||
                    headingRegex.matches(current) ||
                    current.trimStart().startsWith(">") ||
                    unorderedListRegex.matches(current) ||
                    orderedListRegex.matches(current) ||
                    (current.contains("|") && lineIndex + 1 < lines.size && isTableSeparatorRow(lines[lineIndex + 1]))
                ) {
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
