package com.openandroidintelligence.mobile.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import java.io.ByteArrayOutputStream
import java.io.IOException

object ContentResolverExtensions {
    private const val PREVIEW_MAX_BYTES = 256 * 1024
    private const val PREVIEW_MAX_DIMENSION = 512

    /** Reads only picker metadata here; the selected content stays a reopenable stream. */
    fun resolveAttachment(contentResolver: ContentResolver, uri: Uri): LocalAttachmentSelection {
        var filename = "attachment_${System.currentTimeMillis()}"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { filename = it }
            }
        }

        val rawType = contentResolver.getType(uri)?.takeIf { it.isNotBlank() }
        val mediaType = resolveMediaType(rawType, filename)
        val previewBytes = if (mediaType.startsWith("image/")) {
            runCatching { loadBoundedPreview(contentResolver, uri) }.getOrNull()
        } else {
            null
        }

        return LocalAttachmentSelection(
            filename = filename,
            mediaType = mediaType,
            contentSource = AttachmentContentSource {
                try {
                    contentResolver.openInputStream(uri) ?: throw IOException("ATTACHMENT_READ_FAILED")
                } catch (cause: SecurityException) {
                    throw IOException("ATTACHMENT_READ_FAILED", cause)
                } catch (cause: java.io.FileNotFoundException) {
                    throw IOException("ATTACHMENT_READ_FAILED", cause)
                }
            },
            previewBytes = previewBytes,
        )
    }

    private fun loadBoundedPreview(contentResolver: ContentResolver, uri: Uri): ByteArray? {
        val initial = contentResolver.loadThumbnail(
            uri,
            Size(PREVIEW_MAX_DIMENSION, PREVIEW_MAX_DIMENSION),
            null,
        )
        var bitmap = initial
        try {
            repeat(6) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)) return null
                val bytes = output.toByteArray()
                if (bytes.size <= PREVIEW_MAX_BYTES) return bytes
                val width = (bitmap.width * 3 / 4).coerceAtLeast(64)
                val height = (bitmap.height * 3 / 4).coerceAtLeast(64)
                if (width == bitmap.width && height == bitmap.height) return null
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (bitmap !== initial) bitmap.recycle()
                bitmap = scaled
            }
            return null
        } finally {
            if (bitmap !== initial) bitmap.recycle()
            initial.recycle()
        }
    }

    private fun inferMimeType(filename: String): String? {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "zip" -> "application/zip"
            else -> null
        }
    }

    internal fun resolveMediaType(providerType: String?, filename: String): String =
        providerType?.takeIf { it.isNotBlank() } ?: inferMimeType(filename) ?: "application/octet-stream"
}
