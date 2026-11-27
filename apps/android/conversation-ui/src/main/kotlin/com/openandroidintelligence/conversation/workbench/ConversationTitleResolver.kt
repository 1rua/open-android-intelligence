package com.openandroidintelligence.conversation.workbench

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.conversation.sanitizer.ContextInjectionFilter
import com.openandroidintelligence.ui.resources.R

/**
 * 会话标题动态解析器：
 * 负责将底层的空标题安全映射为展示层的本地化文本。
 * 杜绝将本地化中文字面量直接写入底层数据库或实体模型中。
 */
object ConversationTitleResolver {
    /**
     * 判定指定标题是否属于占位（未命名）标题：
     * 经上下文注入净化后为空或空白时视作无有效命名，回落到底层安全默认标题。
     */
    fun isPlaceholderTitle(title: String?): Boolean {
        return ContextInjectionFilter.sanitizeSummaryText(title).isEmpty()
    }

    /** 展示与搜索共用同一个已本地化的默认值，同时净化可能混入的动态注入信封。 */
    fun resolve(title: String?, defaultTitle: String): String {
        val cleaned = ContextInjectionFilter.sanitizeSummaryText(title)
        return cleaned.ifEmpty { defaultTitle }
    }

    /**
     * Compose 环境下的动态本地化会话标题解析
     */
    @Composable
    fun resolve(title: String?): String {
        val defaultTitle = stringResource(R.string.conv_title_new_default)
        return remember(title, defaultTitle) { resolve(title, defaultTitle) }
    }

    /**
     * Context / Service / Notification 环境下的动态本地化会话标题解析
     */
    fun resolve(title: String?, context: Context): String {
        return resolve(title, context.getString(R.string.conv_title_new_default))
    }
}

/** Compose 顶级扩展函数 */
@Composable
fun resolveDisplayConversationTitle(title: String?): String =
    ConversationTitleResolver.resolve(title)

/** Context 顶级扩展函数 */
fun resolveDisplayConversationTitle(title: String?, context: Context): String =
    ConversationTitleResolver.resolve(title, context)
