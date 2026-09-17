package com.openandroidintelligence.conversation.workbench

/**
 * 表格列对齐方式
 */
enum class TableAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

/**
 * Markdown 时间线文档块体系：严格对齐 mobile_motion_preview.html 中的格式规范。
 * 兼容现有的 TimelineBlock.Paragraph 与 TimelineBlock.CodeBlock。
 */
sealed interface TimelineBlock {
    data class Paragraph(val text: String) : TimelineBlock
    data class CodeBlock(val language: String, val code: String) : TimelineBlock
    data class Heading(val level: Int, val text: String) : TimelineBlock
    data class Blockquote(val text: String) : TimelineBlock
    data class UnorderedList(val items: List<String>) : TimelineBlock
    data class OrderedList(val items: List<String>, val startNumber: Int = 1) : TimelineBlock
    data class Table(
        val headers: List<String>,
        val alignments: List<TableAlignment>,
        val rows: List<List<String>>,
    ) : TimelineBlock
    data object ThematicBreak : TimelineBlock
}

typealias MarkdownBlock = TimelineBlock

