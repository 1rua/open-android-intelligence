package com.openandroidintelligence.assistant

/** 交接失败时给用户看的中文结论；每条都对应一个真实原因，不做静默失败。 */
internal object AssistantHandoffMessages {
    const val NOT_CONNECTED = "主 App 未连接，草稿与附件仍保留在助手中"
    const val TIMEOUT = "主 App 没有在 2 秒内确认交接，草稿与附件仍保留在助手中"
    const val REJECTED = "交接未完成，草稿与附件仍保留在助手中"
}
