package com.openandroidintelligence.assistant

import android.net.Uri
import java.lang.ref.WeakReference

/**
 * 「＋」可以打开的两类系统选择器。
 *
 * 规格 §3.5 要求「Photo Picker 多选 + 文档选择，支持现有全部附件类型」：只给照片
 * 选择器会让 PDF、文本等现有附件类型在浮层里彻底选不到——用户点开「＋」看到的
 * 只有相册，没有任何通往文档的入口。
 */
internal enum class AssistantAttachmentKind(val wireValue: String) {
    /** 照片与视频：系统 Photo Picker，多选。 */
    MEDIA("media"),

    /** 任意文件：SAF 文档选择器。 */
    DOCUMENT("document"),

    /**
     * 拍照：系统相机（`TakePicturePreview`），返回一张位图。
     *
     * 与 [MEDIA] 分开：相机不走 URI，主进程无法按 URI 读字节，位图必须由会话侧
     * 编码后走既有的屏幕附件通道。合并成两项会让「拍照」静默退化成相册选择。
     */
    CAPTURE("capture"),

    /**
     * 展开宿主自己窗口内的附件来源卡片（不启动任何系统页面）。
     *
     * 为什么不是 `ModalBottomSheet`：它内部是 `Dialog`（`TYPE_APPLICATION`），而系统
     * 助理会话的宿主窗口是 `VoiceInteractionSession`（type=2031+），系统会直接抛
     * `Window type mismatch` 并崩溃（模拟器已复现）。卡片以图层形式渲染在会话窗口内，
     * 内容与主 App 完全一致，只是不新建窗口。
     */
    SHEET("sheet"),
    ;

    companion object {
        fun of(value: String?): AssistantAttachmentKind =
            entries.firstOrNull { it.wireValue == value } ?: MEDIA
    }
}

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
     * 拍照结果回调：系统相机返回的是位图，不是 URI，因此单独一个通道。
     *
     * 与 URI 回调分开而不是塞进同一个列表：位图要走「编码 → 屏幕附件管道」，
     * 与「按 URI 交给主进程读取」是两条完全不同的路径，混在一起会让两种来源
     * 各自都拿不到正确的结果。
     */
    private var captureListener: WeakReference<(android.graphics.Bitmap) -> Unit>? = null

    /**
     * 选择结束（有结果或取消）时回调一次：会话据此恢复自己的窗口。
     *
     * 与结果回调分开登记：取消路径不产生附件，但同样必须让窗口重新可见，
     * 否则浮层会一直停在「不可触摸」的状态。
     */
    private var finished: WeakReference<() -> Unit>? = null

    /** 登记本次选择的回调（覆盖上一次未消费的登记，选择器同一时刻只可能有一个）。 */
    fun register(
        callback: (List<Uri>) -> Unit,
        onFinished: () -> Unit = {},
        onCaptured: ((android.graphics.Bitmap) -> Unit)? = null,
    ) {
        listener = WeakReference(callback)
        finished = WeakReference(onFinished)
        captureListener = onCaptured?.let(::WeakReference)
    }

    /** 选择结束：把结果交给仍然存活的会话；没有存活回调时丢弃（会话已经结束）。 */
    fun deliver(uris: List<Uri>) {
        val callback = listener?.get()
        listener = null
        captureListener = null
        notifyFinished()
        callback?.invoke(uris)
    }

    /** 拍照完成：把位图交给仍然存活的会话；没有存活回调时回收位图。 */
    fun deliverCapture(bitmap: android.graphics.Bitmap) {
        val callback = captureListener?.get()
        listener = null
        captureListener = null
        notifyFinished()
        if (callback != null) callback(bitmap) else if (!bitmap.isRecycled) bitmap.recycle()
    }

    /** 取消选择：只清掉登记，不产生任何附件；仍然通知一次「已结束」。 */
    fun cancel() {
        listener = null
        captureListener = null
        notifyFinished()
    }

    /** 结束回调只执行一次：迟到或重复的取消不能反复改写会话窗口。 */
    private fun notifyFinished() {
        val callback = finished?.get()
        finished = null
        callback?.invoke()
    }
}
