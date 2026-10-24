package com.openandroidintelligence.mobile.onboarding.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.theme.AppTextStyles
import com.openandroidintelligence.conversation.theme.Dimensions

/** 左侧键名列宽：与原型的 `grid-template-columns: 74px 1fr` 一致，键值区因此纵向对齐。 */
private val KeyColumnWidth = 74.dp

/**
 * 字号放大到这个比例以上时，横向两列会互相挤压（74dp 的键名列塞不下一行大写等宽字），
 * 因此改成上下堆叠。1.3 是「大字辅助模式」（系统字体 130%）的常见分界。
 */
private const val StackedLayoutFontScale = 1.3f

/** 行高下限：低于 44dp 的表格行会显得局促，也保证分隔线之间的呼吸一致。 */
private val MinRowHeight = 44.dp

/** 键名额外字距：等宽大写字母挤在一起会糊成一团，加一点间隙读得更清楚。 */
private val KeyLetterSpacing = 0.6.sp

/**
 * 遥测行（Telemetry Row）：欢迎页参数表的一行，左侧等宽键名 + 右侧说明值。
 *
 * 视觉上是「工程数据表」而不是普通列表：键名用等宽大写 + 字距，值用正文字体，
 * 行与行之间靠 1dp 分隔线而不是留白连接，形成一张连续的规格表。
 *
 * 无障碍与大字号：
 * - 整行合并为一个语义节点，读屏一次读出「键：值」，不会把键和值拆成两段；
 * - 系统字体放大超过 130% 时自动切成上下两行（键在上、值在下），
 *   否则 74dp 的键名列会把右侧说明压成很窄的一列、每行只剩两三个字。
 *
 * @param key 键名，语义上是"这是什么"（Hosts / Protocol / Transport…）。显示时转大写。
 * @param value 值，语义上是"它是什么"，允许换行，不截断。
 */
@Composable
fun TelemetryRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    TelemetryRow(key = key, value = AnnotatedString(value), modifier = modifier)
}

/**
 * 需要行内强调时的重载：值里关键的专有名词（宿主名、协议版本）标成 onSurface + 半粗，
 * 其余文字保持 onSurfaceVariant，形成「说明里点到关键词」的层次，而不是整句都抢眼。
 *
 * 复用 Compose 原生的 [AnnotatedString]，不另造富文本类型；调用方用 [buildAnnotatedString]
 * + [withStyle] 构造即可，样式仍由本组件统一决定（见 [emphasizedValue]）。
 */
@Composable
fun TelemetryRow(
    key: String,
    value: AnnotatedString,
    modifier: Modifier = Modifier,
) {
    // 从 CompositionLocal 读字号缩放，不需要额外的 Configuration 观察者。
    val stacked = LocalDensity.current.fontScale > StackedLayoutFontScale

    val keyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = MaterialTheme.colorScheme.onSurfaceVariant

    // 键名统一大写 + 等宽：与终端/元数据的排版语言一致，强调"这是字段名"。
    val keyText = key.uppercase()

    Column(modifier = modifier.fillMaxWidth()) {
        if (stacked) {
            // 大字号：纵向堆叠，键在上、值在下，两者都允许自然换行。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinRowHeight)
                    .padding(vertical = Dimensions.SpaceSmall)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny),
            ) {
                KeyLabel(text = keyText, color = keyColor)
                ValueText(text = value, color = valueColor)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinRowHeight)
                    .padding(vertical = Dimensions.SpaceSmall)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceCompact),
            ) {
                // 键名列固定宽度且只占一行：键名本身很短，折行反而破坏表格对齐。
                Box(modifier = Modifier.width(KeyColumnWidth)) {
                    KeyLabel(text = keyText, color = keyColor, singleLine = true)
                }
                ValueText(
                    text = value,
                    color = valueColor,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 分隔线画在行底部：每一行都是自洽的表格行，行间不留额外空隙。
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = Dimensions.StrokeHairline,
        )
    }
}

/**
 * 构造带强调片段的值文本：`plain` 普通、`emphasized` 加粗为 onSurface、`tail` 收尾。
 *
 * 用法：`emphasizedValue("Gateway Protocol ", "v2.1", " · 数据直连")`。
 * 强调色始终取自本组件的语义角色，调用方不能带入自己的色值，
 * 保证「参数表里被强调的词」全站只有一种颜色。
 */
@Composable
fun emphasizedValue(plain: String, emphasized: String, tail: String = ""): AnnotatedString {
    val strongColor = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        append(plain)
        withStyle(SpanStyle(color = strongColor, fontWeight = FontWeight.SemiBold)) {
            append(emphasized)
        }
        append(tail)
    }
}

/** 键名：等宽小字 + 轻微字距，颜色弱于正文，读作"字段名"而不是内容。 */
@Composable
private fun KeyLabel(
    text: String,
    color: Color,
    singleLine: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = AppTextStyles.MonoSmall.copy(
            letterSpacing = KeyLetterSpacing,
            fontWeight = FontWeight.Medium,
        ),
        color = color,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Visible,
        modifier = modifier,
    )
}

/** 值文本：允许换行（不截断），强调片段由调用方通过 AnnotatedString 提供。 */
@Composable
private fun ValueText(
    text: AnnotatedString,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier,
    )
}
