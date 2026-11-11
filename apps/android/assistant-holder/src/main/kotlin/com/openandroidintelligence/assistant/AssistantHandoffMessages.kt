package com.openandroidintelligence.assistant

import android.content.Context
import com.openandroidintelligence.ui.resources.R as UiR

/** 交接失败时给用户看的说明；每条都对应一个真实原因，不做静默失败。 */
internal object AssistantHandoffMessages {
    fun notConnected(context: Context): String =
        context.getString(UiR.string.assistant_handoff_not_connected)

    fun timeout(context: Context): String =
        context.getString(UiR.string.assistant_handoff_timeout)

    fun rejected(context: Context): String =
        context.getString(UiR.string.assistant_handoff_rejected)
}
