package com.openandroidintelligence.assistant

/** 交接失败时给用户看的中文结论；每条都对应一个真实原因，不做静默失败。 */
internal object AssistantHandoffMessages {
    const val NOT_CONNECTED = "主 App 未连接，草稿与附件仍保留在助手中"

    /**
     * 回执超时。时限与服务端等待附件就绪的上限绑定（见
     * `ASSISTANT_HANDOFF_RESULT_TIMEOUT_MILLIS`），文案随之更新，避免写着 2 秒实际是 5 秒。
     */
    const val TIMEOUT = "主 App 没有在 5 秒内确认交接，草稿与附件仍保留在助手中"
    const val REJECTED = "交接未完成，草稿与附件仍保留在助手中"
}
