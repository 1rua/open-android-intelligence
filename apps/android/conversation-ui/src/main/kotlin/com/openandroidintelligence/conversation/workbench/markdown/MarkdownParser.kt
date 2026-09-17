package com.openandroidintelligence.conversation.workbench

object MarkdownParser {

    private val codeBlockRegex = Regex("```([a-zA-Z0-9_+-]*)[\\r\\n]+([\\s\\S]*?)```")
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

        // 1. 如果包含完整的代码块，先以代码块为锚点分段
        if (text.contains("```")) {
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
                    blocks.add(TimelineBlock.CodeBlock(language.ifBlank { "代码" }, code))
                    lastIndex = match.range.last + 1
                }
                val remaining = text.substring(lastIndex).trim()
                if (remaining.isNotEmpty()) {
                    blocks.addAll(parseNonCodeText(remaining))
                }
                return blocks.ifEmpty { listOf(TimelineBlock.Paragraph(text)) }
            }
        }

        // 2. 无代码块或未闭合代码块文本
        return parseNonCodeText(text).ifEmpty { listOf(TimelineBlock.Paragraph(text)) }
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

