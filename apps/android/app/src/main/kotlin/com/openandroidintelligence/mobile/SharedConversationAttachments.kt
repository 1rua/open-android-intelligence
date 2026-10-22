package com.openandroidintelligence.mobile

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/** Only explicit file shares are accepted; text and pairing links keep their existing routes. */
internal fun sharedAttachmentUris(intent: Intent): List<Uri> {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
    return buildList {
        runCatching {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                val streams: List<*> = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                addAll(streams.filterIsInstance<Uri>())
            } else {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            }
        }
        intent.clipData?.let { clip ->
            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
        }
    }.filter { it.scheme == "content" }.distinct()
}

/** Retain cold-start shares through login, rotation and saved-state recreation, and consume once. */
internal class SharedConversationAttachments(private val savedState: SavedStateHandle) : ViewModel() {
    val pending = savedState.getStateFlow(KEY, arrayListOf<String>())

    fun receive(intent: Intent) {
        val uris = sharedAttachmentUris(intent)
        if (uris.isNotEmpty()) savedState[KEY] = ArrayList((pending.value + uris.map(Uri::toString)).distinct())
    }

    fun consume(): List<Uri> {
        val result = pending.value.map(Uri::parse)
        savedState[KEY] = arrayListOf<String>()
        return result
    }

    private companion object { const val KEY = "shared_attachment_uris" }
}
