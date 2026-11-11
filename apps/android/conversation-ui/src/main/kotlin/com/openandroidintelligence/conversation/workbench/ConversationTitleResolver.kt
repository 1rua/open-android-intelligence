package com.openandroidintelligence.conversation.workbench

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.ui.resources.R

/**
 * 会话标题动态解析器：
 * 负责将会话标题从底层存储形态（空字符串/存量哨兵）安全映射为展示层的本地化文本。
 * 杜绝将本地化中文字面量直接写入底层数据库或实体模型中。
 */
object ConversationTitleResolver {
    /** 存量版本曾硬编码写入数据库的历史中文占位标题，用于保证存量会话在未重命名时平滑兼容 */
    const val LEGACY_DEFAULT_TITLE = "新对话" // i18n-ignore: 存量数据库兼容常量，用于比对存量历史持久化占位标题

    /**
     * 判定指定标题是否属于占位（未命名）标题：
     * 包括 null、空字符串、全空白字符串，以及存量历史标题 "新对话"。
     */
    fun isPlaceholderTitle(title: String?): Boolean {
        return title.isNullOrBlank() || title == LEGACY_DEFAULT_TITLE
    }

    /**
     * Compose 环境下的动态本地化会话标题解析
     */
    @Composable
    fun resolve(title: String?): String {
        return if (isPlaceholderTitle(title)) {
            stringResource(R.string.conv_title_new_default)
        } else {
            title!!
        }
    }

    /**
     * Context / Service / Notification 环境下的动态本地化会话标题解析
     */
    fun resolve(title: String?, context: Context): String {
        return if (isPlaceholderTitle(title)) {
            context.getString(R.string.conv_title_new_default)
        } else {
            title!!
        }
    }
}

/** Compose 顶级扩展函数 */
@Composable
fun resolveDisplayConversationTitle(title: String?): String =
    ConversationTitleResolver.resolve(title)

/** Context 顶级扩展函数 */
fun resolveDisplayConversationTitle(title: String?, context: Context): String =
    ConversationTitleResolver.resolve(title, context)
