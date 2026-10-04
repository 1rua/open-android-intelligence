package com.openandroidintelligence.assistant

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** Owns the bitmap and invokes the callback on a worker after releasing it. */
internal fun encodeAssistantScreen(bitmap: Bitmap, onEncoded: (ByteArray?) -> Unit) {
    Thread({
        val bytes = try {
            val output = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
        } catch (_: Exception) { null }
        finally { if (!bitmap.isRecycled) bitmap.recycle() }
        onEncoded(bytes)
    }, "assistant-screen-encoding").start()
}
