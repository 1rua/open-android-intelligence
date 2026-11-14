package com.openandroidintelligence.control.remote

import com.openandroidintelligence.control.ScreenAction
import com.openandroidintelligence.control.ScreenReadQuery
import java.util.Collections

/** 纯参数树，不解析 JSON，不依赖传输模块；对象和数组保存独立只读快照。 */
sealed interface ControlValue {
    data object Null : ControlValue
    data class Text(val value: String) : ControlValue
    data class Number(val value: String) : ControlValue
    data class Boolean(val value: kotlin.Boolean) : ControlValue
    class Object(values: Map<String, ControlValue>) : ControlValue {
        val values: Map<String, ControlValue> = Collections.unmodifiableMap(LinkedHashMap(values))
    }
    class Array(values: List<ControlValue>) : ControlValue {
        val values: List<ControlValue> = Collections.unmodifiableList(values.toList())
    }
}

sealed interface ControlOperation {
    val wireName: String

    data class DumpTree(val query: ScreenReadQuery) : ControlOperation { override val wireName = "ui.dump_tree" }
    data object CaptureSom : ControlOperation { override val wireName = "ui.capture_som" }
    data object Capabilities : ControlOperation { override val wireName = "phone.capabilities" }
    data class Screen(val snapshotId: String, val action: ScreenAction) : ControlOperation {
        override val wireName: String get() = when (action) {
            is ScreenAction.Click -> "ui.tap"
            is ScreenAction.LongPress -> "ui.long_press"
            is ScreenAction.Swipe -> "ui.swipe"
            is ScreenAction.Drag -> "ui.drag"
            is ScreenAction.SetText -> "ui.input_text"
            is ScreenAction.Scroll -> "ui.scroll"
            is ScreenAction.Global -> "ui.global_action"
            is ScreenAction.LaunchApp -> "ui.launch_app"
        }
    }
    data class PluginInvoke(val pluginId: String, val capabilityId: String, val method: String, val params: ControlValue.Object) : ControlOperation {
        override val wireName = "plugin.invoke"
    }
    data class Status(val targetRequestId: String, val targetTaskSessionId: String) : ControlOperation { override val wireName = "request.status" }
    data class Cancel(val targetRequestId: String) : ControlOperation { override val wireName = "request.cancel" }
}

/** 规范参数摘要覆盖操作及完整 args，正文只保留于活跃请求内存，不进入元数据账本。 */
class ParsedControlRequest(
    val requestId: String,
    val operation: ControlOperation,
    val parameterDigest: String,
) {
    init {
        require(Regex("[A-Za-z0-9_-]{16,128}").matches(requestId)) { "CONTROL_REQUEST_ID_INVALID" }
        require(Regex("sha256:[0-9a-f]{64}").matches(parameterDigest)) { "CONTROL_PARAMETER_DIGEST_INVALID" }
    }

    override fun toString(): String = "ParsedControlRequest(requestId=$requestId,op=${operation.wireName})"
}
