package com.openandroidintelligence.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.mobile.util.ContentResolverExtensions
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ConversationAttachmentPickers(
    val camera: () -> Unit,
    val gallery: () -> Unit,
    val document: () -> Unit,
)

internal enum class ConversationAttachmentPicker { CAMERA, GALLERY, DOCUMENT }

/** A result belongs to the window, session, and conversation that launched its picker. */
internal data class AttachmentPickerTarget(
    val controller: WorkbenchController,
    val conversationId: String?,
    val revision: Long = controller.attachmentTargetRevision,
) {
    fun matches(current: WorkbenchController?): Boolean =
        current === controller && current.state.value.isOnline && current.state.value.activeThreadId == conversationId &&
            current.attachmentTargetRevision == revision
}

/** Retain pending destinations and metadata work across Activity configuration changes. */
internal class ConversationAttachmentPickerState : ViewModel() {
    var controller: WorkbenchController? = null
    var isCurrentController: (WorkbenchController) -> Boolean = { true }
    private val targets = mutableMapOf<ConversationAttachmentPicker, AttachmentPickerTarget>()

    fun capture(picker: ConversationAttachmentPicker): Boolean {
        if (picker in targets) return false
        val current = controller?.takeIf { it.state.value.isOnline && isCurrentController(it) } ?: return false
        targets[picker] = AttachmentPickerTarget(current, current.state.value.activeThreadId)
        return true
    }

    fun consume(picker: ConversationAttachmentPicker): AttachmentPickerTarget? = targets.remove(picker)

    fun accepts(target: AttachmentPickerTarget): Boolean = target.matches(controller) && isCurrentController(target.controller)

    fun prepare(target: AttachmentPickerTarget?, context: Context, selection: () -> LocalAttachmentSelection) {
        if (target == null || !accepts(target)) {
            notice(context, "对话已切换，附件未加入")
            return
        }
        viewModelScope.launch {
            try {
                val attachment = withContext(Dispatchers.IO) { selection() }
                if (accepts(target)) target.controller.addAttachment(attachment)
                else notice(context, "对话已切换，附件未加入")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                notice(context, "附件处理失败：${failure.message}")
            }
        }
    }
}

/** The main window and native bubble launch identical system contracts and use the same attachment path. */
@Composable
internal fun rememberConversationAttachmentPickers(
    controller: WorkbenchController?,
    isCurrentController: (WorkbenchController) -> Boolean = { true },
): ConversationAttachmentPickers {
    val context = LocalContext.current.applicationContext
    val state: ConversationAttachmentPickerState = viewModel()
    SideEffect {
        state.controller = controller
        state.isCurrentController = isCurrentController
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        val target = state.consume(ConversationAttachmentPicker.CAMERA)
        if (bitmap != null) state.prepare(target, context) { cameraAttachmentSelection(bitmap) }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = state.consume(ConversationAttachmentPicker.GALLERY)
        if (uri != null) state.prepare(target, context) { pickedAttachmentSelection(context, uri) }
    }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = state.consume(ConversationAttachmentPicker.DOCUMENT)
        if (uri != null) state.prepare(target, context) { pickedAttachmentSelection(context, uri) }
    }
    return remember(state, camera, gallery, document) {
        fun launch(picker: ConversationAttachmentPicker, action: () -> Unit) {
            if (!state.capture(picker)) {
                notice(context, "对话尚未就绪，无法选择附件")
                return
            }
            try {
                action()
            } catch (failure: Exception) {
                state.consume(picker)
                notice(context, "无法打开附件选择器：${failure.message}")
            }
        }
        ConversationAttachmentPickers(
            camera = { launch(ConversationAttachmentPicker.CAMERA) { camera.launch(null) } },
            gallery = { launch(ConversationAttachmentPicker.GALLERY) {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } },
            document = { launch(ConversationAttachmentPicker.DOCUMENT) { document.launch(arrayOf("*/*")) } },
        )
    }
}

internal fun pickedAttachmentSelection(context: Context, uri: Uri): LocalAttachmentSelection {
    // SAF and the Photo Picker can grant durable read access. Some fallback providers only
    // grant temporary access; they are still staged immediately through the same stream path.
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // This provider supplied a temporary grant; do not reject a readable selection.
    } catch (_: UnsupportedOperationException) {
        // Older/fallback providers need not implement persisted grants.
    }
    return ContentResolverExtensions.resolveAttachment(context.contentResolver, uri)
}

internal fun cameraAttachmentSelection(bitmap: Bitmap): LocalAttachmentSelection = LocalAttachmentSelection(
    filename = "camera_${System.currentTimeMillis()}.jpg",
    mediaType = "image/jpeg",
    contentSource = AttachmentContentSource.fromWriter { output ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) throw IOException("CAMERA_IMAGE_ENCODING_FAILED")
    },
    previewBytes = boundedCameraPreview(bitmap),
)

private fun boundedCameraPreview(source: Bitmap): ByteArray? {
    var bitmap = source
    var owned = false
    try {
        while (true) {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)) return null
            val bytes = output.toByteArray()
            if (bytes.size <= 256 * 1024) return bytes
            val width = (bitmap.width * 3 / 4).coerceAtLeast(64)
            val height = (bitmap.height * 3 / 4).coerceAtLeast(64)
            if (width == bitmap.width && height == bitmap.height) return null
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (owned) bitmap.recycle()
            bitmap = scaled
            owned = bitmap !== source
        }
    } finally {
        if (owned) bitmap.recycle()
    }
}

private fun notice(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
