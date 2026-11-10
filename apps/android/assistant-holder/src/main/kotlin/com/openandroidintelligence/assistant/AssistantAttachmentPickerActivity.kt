package com.openandroidintelligence.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.openandroidintelligence.conversation.model.MAX_COMPOSER_ATTACHMENTS

/**
 * 承载系统附件选择器的透明 Activity。
 *
 * 为什么不是会话自己选：`VoiceInteractionSession` 没有 `ActivityResultRegistry`。这里用
 * 一个无 UI、不可导出的透明 Activity 申请自己的注册表，结果经
 * [AssistantAttachmentPickerBroker] 交回会话。
 *
 * 先试 Photo Picker（多选），用户取消/不可用时再退回 SAF 文档选择器：两者都是系统
 * 标准原语，不申请额外权限、不读字节（字节由主进程按 URI 读取）。
 * 选完即 `finish()`，不留在最近任务里（清单里同时声明 `excludeFromRecents`）。
 */
class AssistantAttachmentPickerActivity : ComponentActivity() {

    private var launchedGallery = false
    private var launchedDocument = false

    private val gallery = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_COMPOSER_ATTACHMENTS),
    ) { uris ->
        if (uris.isNotEmpty()) {
            deliver(uris)
        } else {
            // 用户主动取消：不再弹第二个选择器，直接把「没有结果」交回去。
            AssistantAttachmentPickerBroker.cancel()
            finish()
        }
    }

    private val document = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) deliver(listOf(uri)) else {
            AssistantAttachmentPickerBroker.cancel()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // 配置变化/进程重建时不再重开选择器：旧结果会随注册表恢复。
            return
        }
        launchGallery()
    }

    private fun launchGallery() {
        if (launchedGallery) return
        launchedGallery = true
        runCatching {
            gallery.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
            )
        }.onFailure {
            // 没有可用的 Photo Picker（极旧设备/无媒体库）时退回文档选择器。
            launchDocument()
        }
    }

    private fun launchDocument() {
        if (launchedDocument) return
        launchedDocument = true
        runCatching { document.launch(arrayOf("*/*")) }.onFailure {
            AssistantAttachmentPickerBroker.cancel()
            finish()
        }
    }

    private fun deliver(uris: List<Uri>) {
        // 持久化读授权由主进程在 `pickedAttachmentSelection` 里统一申请：
        // 它才是真正读字节的一方，而且 Photo Picker 的授权需要同一个授权持有者。
        AssistantAttachmentPickerBroker.deliver(uris)
        finish()
    }

    override fun onDestroy() {
        // 进程级 broker 里只留弱引用：这里再显式取消一次，避免残留「等待选择」的登记。
        if (isFinishing) AssistantAttachmentPickerBroker.cancel()
        super.onDestroy()
    }

    companion object {
        /** 会话用 `startAssistantActivity` 启动本 Activity 的意图。 */
        fun intent(context: android.content.Context): Intent =
            Intent().setClassName(context.packageName, AssistantAttachmentPickerActivity::class.java.name)
    }
}
