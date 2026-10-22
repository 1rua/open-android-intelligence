package com.openandroidintelligence.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openandroidintelligence.gateway.account.AccountProfile
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal const val ACCOUNT_AVATAR_MAX_BYTES = 256 * 1024
private const val AVATAR_MAX_DIMENSION = 256

internal data class AccountAvatarPicker(val image: ImageBitmap?, val pick: (() -> Unit)?)

/** The original account owns the result, even if Activity configuration changes mid-picker. */
internal class AccountAvatarPickerState : ViewModel() {
    var pendingProfileId: String? = null
    var currentProfileId: String? = null
    var saving by mutableStateOf(false)
        private set

    fun save(context: Context, runtime: GatewayRuntime, uri: Uri?) {
        val target = pendingProfileId
        pendingProfileId = null
        if (uri == null || target == null) return
        if (currentProfileId != target) {
            Toast.makeText(context, "账号已切换，头像未更换", Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { loadAccountAvatar(context, uri) }
                if (currentProfileId == target) runtime.setAccountAvatar(target, image)
                else Toast.makeText(context, "账号已切换，头像未更换", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "无法更换头像，请重新选择图片", Toast.LENGTH_SHORT).show()
            } finally { saving = false }
        }
    }
}

@Composable
internal fun rememberAccountAvatarPicker(runtime: GatewayRuntime, profile: AccountProfile?): AccountAvatarPicker {
    val context = LocalContext.current.applicationContext
    val state: AccountAvatarPickerState = viewModel()
    SideEffect { state.currentProfileId = profile?.localProfileId }
    DisposableEffect(state) { onDispose { state.currentProfileId = null } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        state.save(context, runtime, uri)
    }
    val image = key(profile?.localProfileId, profile?.avatarBase64) {
        val decoded by produceState<ImageBitmap?>(null) {
            value = withContext(Dispatchers.IO) { decodeAccountAvatar(profile?.avatarBase64) }
        }
        decoded
    }
    val pick: (() -> Unit)? = if (profile == null) null else ({
        if (!state.saving && state.pendingProfileId == null) {
            state.pendingProfileId = profile.localProfileId
            try {
                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (_: Exception) {
                state.pendingProfileId = null
                Toast.makeText(context, "无法打开系统相册", Toast.LENGTH_SHORT).show()
            }
        }
    })
    return AccountAvatarPicker(image, pick)
}

/** Decode directly to a bounded image; never persist a borrowed content URI or a full-size photo. */
internal fun loadAccountAvatar(context: Context, uri: Uri): ByteArray {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val scale = (AVATAR_MAX_DIMENSION.toFloat() / maxOf(info.size.width, info.size.height)).coerceAtMost(1f)
        decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
            (info.size.height * scale).roundToInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    return try {
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, output))
            output.toByteArray().also { check(it.isNotEmpty() && it.size <= ACCOUNT_AVATAR_MAX_BYTES) }
        }
    } finally { bitmap.recycle() }
}

internal fun decodeAccountAvatar(encoded: String?): ImageBitmap? = runCatching {
    if (encoded == null || encoded.length > (ACCOUNT_AVATAR_MAX_BYTES + 2) / 3 * 4) return null
    val bytes = Base64.getDecoder().decode(encoded)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..AVATAR_MAX_DIMENSION || bounds.outHeight !in 1..AVATAR_MAX_DIMENSION) return null
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()
