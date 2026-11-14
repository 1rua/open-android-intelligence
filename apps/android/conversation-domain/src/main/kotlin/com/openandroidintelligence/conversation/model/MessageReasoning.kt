package com.openandroidintelligence.conversation.model

/** 后端明确给出的思考块；正文偏移只定位呈现顺序，不把思考标记写入回答正文。 */
data class MessageReasoning(
    val id: String,
    val text: String,
    val bodyOffset: Int,
    val complete: Boolean,
) {
    init { require(id.isNotEmpty() && bodyOffset >= 0) { "MESSAGE_REASONING_INVALID" } }
}
