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

    /**
     * 选择结束（有结果或取消）时回调一次：会话据此恢复自己的窗口。
     *
     * 与结果回调分开登记：取消路径不产生附件，但同样必须让窗口重新可见，
     * 否则浮层会一直停在「不可触摸」的状态。
     */
    private var finished: WeakReference<() -> Unit>? = null

    /** 登记本次选择的回调（覆盖上一次未消费的登记，选择器同一时刻只可能有一个）。 */
    fun register(callback: (List<Uri>) -> Unit, onFinished: () -> Unit = {}) {
        listener = WeakReference(callback)
        finished = WeakReference(onFinished)
    }

    /** 选择结束：把结果交给仍然存活的会话；没有存活回调时丢弃（会话已经结束）。 */
    fun deliver(uris: List<Uri>) {
        val callback = listener?.get()
        listener = null
        notifyFinished()
        callback?.invoke(uris)
    }

    /** 取消选择：只清掉登记，不产生任何附件；仍然通知一次「已结束」。 */
    fun cancel() {
        listener = null
        notifyFinished()
    }

    /** 结束回调只执行一次：迟到或重复的取消不能反复改写会话窗口。 */
    private fun notifyFinished() {
        val callback = finished?.get()
        finished = null
        callback?.invoke()
    }
}
