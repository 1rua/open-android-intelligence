package com.openandroidintelligence.conversation.workbench

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableLayoutTest {

    @Test
    fun calculateColumnWidths_shortContent_usesMinimumWidth() {
        val headers = listOf("A", "B")
        val rows = listOf(
            listOf("1", "2"),
        )
        val density = Density(density = 2f, fontScale = 1f)
        val minWidth = 84.dp
        val maxWidth = 280.dp

        val widths = calculateTableColumnWidths(headers, rows, minWidth, maxWidth, density)

        assertEquals(2, widths.size)
        assertEquals(minWidth, widths[0])
        assertEquals(minWidth, widths[1])
    }

    @Test
    fun calculateColumnWidths_unifiesWidthAcrossHeadersAndRows() {
        val headers = listOf("左对齐", "居中显示", "右对齐数值")
        val rows = listOf(
            listOf("较短内容", "🟢 正常", "¥128.50"),
            listOf("这是一段非常长非常长的文本用来测试表格单元格换行", "⚠️ 警告", "¥12,345.00"),
        )
        val density = Density(density = 2f, fontScale = 1f)
        val minWidth = 84.dp
        val maxWidth = 280.dp

        val widths = calculateTableColumnWidths(headers, rows, minWidth, maxWidth, density)

        assertEquals(3, widths.size)
        // 第一列由于含有长文本，其宽度应明显大于第二列和第三列，且受 maxWidth 约束
        assertTrue("第一列宽度应大于最小宽度", widths[0] > minWidth)
        assertTrue("第一列宽度应不超过最大宽度", widths[0] <= maxWidth)
        // 且宽度必须是确定的单个值，使得表头与每行统一
        assertTrue(widths[0].value > widths[1].value)
    }

    @Test
    fun calculateColumnWidths_respectsMaxWidthForVeryLongText() {
        val headers = listOf("超长文本列")
        val rows = listOf(
            listOf("这是一段极其极其极其极其极其极其极其极其极其极其极其极其极其极其极其长并且不断延伸的文本"),
        )
        val density = Density(density = 2f, fontScale = 1f)
        val minWidth = 84.dp
        val maxWidth = 280.dp

        val widths = calculateTableColumnWidths(headers, rows, minWidth, maxWidth, density)

        assertEquals(1, widths.size)
        assertEquals(maxWidth, widths[0])
    }

    @Test
    fun calculateColumnWidths_handlesEmptyOrMismatchedCells() {
        val headers = listOf("列1", "列2", "列3")
        val rows = listOf(
            listOf("值1"), // 缺少列2和列3
            emptyList(),
        )
        val density = Density(density = 2f, fontScale = 1f)
        val minWidth = 84.dp
        val maxWidth = 280.dp

        val widths = calculateTableColumnWidths(headers, rows, minWidth, maxWidth, density)

        assertEquals(3, widths.size)
        assertEquals(minWidth, widths[0])
        assertEquals(minWidth, widths[1])
        assertEquals(minWidth, widths[2])
    }
}
