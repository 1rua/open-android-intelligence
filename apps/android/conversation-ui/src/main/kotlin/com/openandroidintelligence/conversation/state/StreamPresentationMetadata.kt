package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.StreamPresentationOrigin

/** 正文进入界面的来源与内容修订；缓存恢复时不保留这份临时信息。 */
data class StreamPresentationMetadata(
    val origin: StreamPresentationOrigin,
    val revision: Long,
)
