package com.openandroidintelligence.mobile.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

object ContentResolverExtensions {
    private const val PREVIEW_MAX_BYTES = 256 * 1024
    private const val PREVIEW_MAX_DIMENSION = 512
    private const val MAX_IMAGE_DIMENSION = 1920
    private const val TARGET_IMAGE_QUALITY = 80
    private const val MAX_IMAGE_BYTES = 1024 * 1024 // 1MB

    /** Reads only picker metadata here; the selected content stays a reopenable stream. */
    fun resolveAttachment(contentResolver: ContentResolver, uri: Uri): LocalAttachmentSelection {
        var filename = "attachment_${System.currentTimeMillis()}"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { filename = it }
            }
        }

        val rawType = runCatching { contentResolver.getType(uri) }.getOrNull()?.takeIf { it.isNotBlank() }
        val originalMediaType = resolveMediaType(rawType, filename)
        val isImage = originalMediaType.startsWith("image/", ignoreCase = true)
        val previewBytes = if (isImage) {
            runCatching { loadBoundedPreview(contentResolver, uri) }.getOrNull()
        } else {
            null
        }
        val compressedImage = if (isImage) {
            runCatching {
                compressImageUri(contentResolver, uri, fallbackMimeType = originalMediaType)
            }.getOrNull()
        } else {
            null
        }
        val convertedToWebp = compressedImage != null &&
            compressedImage.second == "image/webp" &&
            compressedImage.first.isNotEmpty()
        val resolvedMediaType = if (convertedToWebp) "image/webp" else originalMediaType
        val cachedBytes = compressedImage?.first?.takeIf { it.isNotEmpty() }

        return LocalAttachmentSelection(
            filename = filename,
            mediaType = resolvedMediaType,
            contentSource = AttachmentContentSource {
                if (cachedBytes != null) {
                    ByteArrayInputStream(cachedBytes)
                } else {
                    try {
                        contentResolver.openInputStream(uri) ?: throw IOException("ATTACHMENT_READ_FAILED")
                    } catch (cause: SecurityException) {
                        throw IOException("ATTACHMENT_READ_FAILED", cause)
                    } catch (cause: java.io.FileNotFoundException) {
                        throw IOException("ATTACHMENT_READ_FAILED", cause)
                    }
                }
            },
            previewBytes = previewBytes,
        )
    }

    private fun isValidImageHeader(header: ByteArray): Boolean {
        if (header.size < 4) return false
        // JPEG: FF D8 FF
        if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) return true
        // PNG: 89 50 4E 47
        if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) return true
        // GIF: GIF8
        if (header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == '8'.code.toByte()) return true
        // WEBP: RIFF....WEBP
        if (header.size >= 12 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() && header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()) return true
        // BMP: BM
        if (header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) return true
        // HEIF/AVIF: ....ftyp
        if (header.size >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() && header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()) return true
        return false
    }

    fun compressImageUri(
        contentResolver: ContentResolver,
        uri: Uri,
        maxDimension: Int = MAX_IMAGE_DIMENSION,
        quality: Int = TARGET_IMAGE_QUALITY,
        fallbackMimeType: String? = null,
    ): Pair<ByteArray, String> {
        val header = ByteArray(12)
        val headerLen = runCatching { contentResolver.openInputStream(uri)?.use { it.read(header) } }.getOrNull() ?: 0
        val mimeFallback = fallbackMimeType?.takeIf { it.isNotBlank() }
            ?: runCatching { contentResolver.getType(uri) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "image/jpeg"
        if (headerLen < 4 || !isValidImageHeader(header.copyOf(headerLen))) {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            return bytes to mimeFallback
        }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

        val rawWidth = options.outWidth
        val rawHeight = options.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            return bytes to mimeFallback
        }

        var sampleSize = 1
        while ((rawWidth / sampleSize) > maxDimension * 2 || (rawHeight / sampleSize) > maxDimension * 2) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampledBitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IOException("IMAGE_DECODE_FAILED")

        val maxSide = maxOf(sampledBitmap.width, sampledBitmap.height)
        val targetBitmap = if (maxSide > maxDimension) {
            val scale = maxDimension.toFloat() / maxSide
            val scaled = Bitmap.createScaledBitmap(
                sampledBitmap,
                (sampledBitmap.width * scale).toInt().coerceAtLeast(1),
                (sampledBitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== sampledBitmap) sampledBitmap.recycle()
            scaled
        } else {
            sampledBitmap
        }

        var currentQuality = quality
        var outputBytes: ByteArray
        try {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            while (true) {
                val outputStream = ByteArrayOutputStream()
                targetBitmap.compress(format, currentQuality, outputStream)
                outputBytes = outputStream.toByteArray()
                if (outputBytes.size <= MAX_IMAGE_BYTES || currentQuality <= 30) {
                    break
                }
                currentQuality -= 15
            }
        } finally {
            targetBitmap.recycle()
        }

        return outputBytes to "image/webp"
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
