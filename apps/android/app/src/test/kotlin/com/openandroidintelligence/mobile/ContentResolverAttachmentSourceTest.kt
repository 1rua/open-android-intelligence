package com.openandroidintelligence.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows
import org.robolectric.fakes.BaseCursor
import com.openandroidintelligence.mobile.util.ContentResolverExtensions
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentResolverAttachmentSourceTest {
    @Test
    fun providerMediaTypeIsPassedThroughWithoutNormalization() {
        assertEquals(
            "Image/AVIF; profile=WideGamut",
            ContentResolverExtensions.resolveMediaType("Image/AVIF; profile=WideGamut", "photo.dat"),
        )
    }

    @Test
    fun aPickerUriIsKeptAsAReopenableStreamAndHasNoTwentyFiveMibClientGate() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val size = 25L * 1024 * 1024 + 9
        val uri = Uri.parse("content://picker/item/1")
        var streamsOpened = 0
        val shadowResolver = Shadows.shadowOf(resolver)
        shadowResolver.setCursor(uri, DisplayNameCursor())
        shadowResolver.registerInputStreamSupplier(uri) {
            streamsOpened++
            SyntheticStream(size)
        }

        val selection = ContentResolverExtensions.resolveAttachment(resolver, uri)

        assertEquals("large.dat", selection.filename)
        assertEquals("application/octet-stream", selection.mediaType)
        assertEquals("metadata resolution must not read the whole provider stream", 0, streamsOpened)
        var read = 0L
        selection.contentSource.openStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                read += count
            }
        }
        assertEquals(size, read)
        assertEquals(1, streamsOpened)
    }

    private class DisplayNameCursor : BaseCursor() {
        override fun moveToFirst() = true
        override fun getColumnIndex(columnName: String) = if (columnName == OpenableColumns.DISPLAY_NAME) 0 else -1
        override fun getString(columnIndex: Int) = "large.dat"
        override fun close() = Unit
    }

    private class SyntheticStream(private val size: Long) : InputStream() {
        private var offset = 0L
        override fun read(): Int {
            if (offset >= size) return -1
            return (offset++ and 0xff).toInt()
        }
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (offset >= size) return -1
            val count = minOf(length.toLong(), size - offset).toInt()
            repeat(count) { index -> buffer[start + index] = ((offset + index) and 0xff).toByte() }
            offset += count
            return count
        }
    }
}
