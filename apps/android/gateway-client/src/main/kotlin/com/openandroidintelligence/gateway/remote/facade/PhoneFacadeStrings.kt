package com.openandroidintelligence.gateway.remote.facade

import android.content.Context
import com.openandroidintelligence.gateway.client.R

/** 手机门面的显示文本由 Android 资源提供；协议标识与错误码保持固定。 */
data class PhoneFacadeStrings(
    val busTitle: String,
    val taskTitle: String,
    val workspaceTitle: String,
    val requestFailed: String,
    val streamUnavailable: String,
    val requestRejected: String,
) {
    companion object {
        fun from(context: Context) = PhoneFacadeStrings(
            context.getString(R.string.remote_phone_bus_title),
            context.getString(R.string.remote_phone_task_title),
            context.getString(R.string.remote_phone_workspace_title),
            context.getString(R.string.remote_phone_request_failed),
            context.getString(R.string.remote_phone_stream_unavailable),
            context.getString(R.string.remote_phone_request_rejected),
        )
    }
}
