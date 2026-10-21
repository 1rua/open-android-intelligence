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
 * GFM 任务列表状态。
 *
 * `- [ ]` / `- [x]` 由解析器转成 [UNCHECKED] / [CHECKED]，
 * 渲染层据此画复选框，而不是把方括号当正文显示。
 */
enum class TaskState {
    NONE,
    UNCHECKED,
    CHECKED,
}

/**
 * 一条列表项。
 *
 * 嵌套层级、任务状态与有序序号都在解析阶段确定：
 * - [level] 从 0 开始，渲染层只按层级缩进，不再猜测原始空格数；
 * - [taskState] 只在 GFM 任务列表里不是 [TaskState.NONE]；
 * - [number] 只对有序项有值，无序项为 null，渲染层据此选择圆点或序号。
 */
data class MarkdownListItem(
    val text: String,
    val level: Int = 0,
    val taskState: TaskState = TaskState.NONE,
    val number: Int? = null,
)

/**
 * Markdown 时间线文档块体系：严格对齐 mobile_motion_preview.html 中的格式规范。
 *
 * 引用块与列表在 2026-10 的渲染修复中改成结构化树：
 * [TimelineBlock.Blockquote] 持有已经去掉一层 `>` 的递归子块，
 * [TimelineBlock.ListBlock] 用 [MarkdownListItem.level] 表达嵌套，
 * 这样多级引用、子列表缩进与任务复选框都不再依赖字符串前缀。
 */
sealed interface TimelineBlock {
    data class Paragraph(val text: String) : TimelineBlock
    data class CodeBlock(val language: String, val code: String) : TimelineBlock
    data class Heading(val level: Int, val text: String) : TimelineBlock

    /** 引用块：children 是去掉一层 `>` 后递归解析出的块，嵌套引用表现为 children 中的另一个 Blockquote。 */
    data class Blockquote(val blocks: List<TimelineBlock>) : TimelineBlock

    /** 有序 / 无序 / 任务 / 嵌套统一为一种列表块，逐项携带层级与标记。 */
    data class ListBlock(val items: List<MarkdownListItem>) : TimelineBlock

    data class Table(
        val headers: List<String>,
        val alignments: List<TableAlignment>,
        val rows: List<List<String>>,
    ) : TimelineBlock

    /** 块级数学公式：latex 不含外层的 `$$`。 */
    data class MathBlock(val latex: String) : TimelineBlock

    data object ThematicBreak : TimelineBlock

    data class ThoughtBlock(
        val thought: String,
        val isComplete: Boolean = true,
    ) : TimelineBlock

    /**
     * 一次工具调用。
     *
     * [isRunning] 表示这条工具调用在流式文本里还没有闭合：解析器遇到未闭合的
     * `<tool_call>` / fenced `tool_call` 时置 true，闭合后置 false。
     * 它只描述「文本是否已经写完」，真实是否仍在执行还要结合消息的
     * `TimelineEntry.isStreaming`，由卡片渲染层判定（规格 §3.2）。
     */
    data class ToolCallBlock(
        val toolName: String,
        val command: String,
        val output: String? = null,
        val isSuccess: Boolean = true,
        val summary: String? = null,
        val isRunning: Boolean = false,
        /**
         * Hermes/宿主把工具调用作为一条纯文本消息（如「调用工具：execute_code」）发送时置 true。
         * 这类消息没有命令参数与返回值，状态应显示「已调用」，不能伪装成「执行成功」。
         */
        val invocationOnly: Boolean = false,
    ) : TimelineBlock
}

typealias MarkdownBlock = TimelineBlock
