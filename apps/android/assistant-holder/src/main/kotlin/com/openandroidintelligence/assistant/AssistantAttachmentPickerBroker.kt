package com.openandroidintelligence.assistant

import android.net.Uri
import java.lang.ref.WeakReference

/**
 * 进程级的「系统选择器结果」中转站。
 *
 * 为什么需要它：系统助理会话是 `VoiceInteractionSession`，没有 `ActivityResultRegistry`，
 * 无法直接 `rememberLauncherForActivityResult`。选择器改由一个透明 Activity 承载，结果
 * 通过这个单例交回发起选择的那一次会话。
 *
 * 持有的是**弱引用**：Activity 的回收节奏由系统决定，若强引用会话回调，一次取消选择
 * 就可能把整个会话（含截图与预览字节）留在内存里。会话已结束时回调直接丢弃。
 */
internal object AssistantAttachmentPickerBroker {

    private var listener: WeakReference<(List<Uri>) -> Unit>? = null

    /** 登记本次选择的回调（覆盖上一次未消费的登记，选择器同一时刻只可能有一个）。 */
    fun register(callback: (List<Uri>) -> Unit) {
        listener = WeakReference(callback)
    }

    /** 选择结束：把结果交给仍然存活的会话；没有存活回调时丢弃（会话已经结束）。 */
    fun deliver(uris: List<Uri>) {
        val callback = listener?.get()
        listener = null
        callback?.invoke(uris)
    }

    /** 取消选择：只清掉登记，不产生任何附件。 */
    fun cancel() {
        listener = null
    }
}
