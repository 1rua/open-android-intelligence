package com.openandroidintelligence.conversation.workbench

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.ui.resources.R

/**
 * 会话标题动态解析器：
 * 负责将底层的空标题安全映射为展示层的本地化文本。
 * 杜绝将本地化中文字面量直接写入底层数据库或实体模型中。
 */
object ConversationTitleResolver {
    /**
     * 判定指定标题是否属于占位（未命名）标题：
     * 只认 null 或空白；非空标题没有来源凭据，不能仅凭字面值覆盖用户或服务端命名。
     */
    fun isPlaceholderTitle(title: String?): Boolean {
        return title.isNullOrBlank()
    }

    /** 展示与搜索共用同一个已本地化的默认值，不改写领域模型或持久化数据。 */
    fun resolve(title: String?, defaultTitle: String): String =
        if (isPlaceholderTitle(title)) defaultTitle else title!!

    /**
     * Compose 环境下的动态本地化会话标题解析
     */
    @Composable
    fun resolve(title: String?): String {
        return resolve(title, stringResource(R.string.conv_title_new_default))
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
