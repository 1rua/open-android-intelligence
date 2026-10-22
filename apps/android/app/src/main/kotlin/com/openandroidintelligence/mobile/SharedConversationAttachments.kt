package com.openandroidintelligence.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.openandroidintelligence.conversation.state.WorkbenchController

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

/** The wildcard MIME filter also matches text/URL shares; reject those visibly. */
internal fun receiveSharedConversationAttachments(context: Context, shares: SharedConversationAttachments, intent: Intent) {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
    if (!shares.receive(intent)) Toast.makeText(context,
        "未收到可读取的文件；文字或链接请复制后粘贴到输入框", Toast.LENGTH_LONG).show()
}

/** Retain cold-start shares through login, rotation and saved-state recreation, and consume once. */
internal class SharedConversationAttachments(private val savedState: SavedStateHandle) : ViewModel() {
    val pending = savedState.getStateFlow(KEY, arrayListOf<String>())

    fun receive(intent: Intent): Boolean {
        val uris = sharedAttachmentUris(intent)
        if (uris.isNotEmpty()) savedState[KEY] = ArrayList((pending.value + uris.map(Uri::toString)).distinct())
        return uris.isNotEmpty()
    }

    /** Online precedes the initial thread refresh; keep ownership until a destination accepts it. */
    fun deliverTo(controller: WorkbenchController, receive: (List<Uri>) -> Boolean): Boolean {
        val state = controller.state.value
        if (pending.value.isEmpty() || !state.isOnline || state.activeThreadId == null ||
            !state.historyHydrated || state.creatingThread || state.managingThreads) return false
        if (!receive(pending.value.map(Uri::parse))) return false
        consume()
        return true
    }

    fun consume(): List<Uri> {
        val result = pending.value.map(Uri::parse)
        savedState[KEY] = arrayListOf<String>()
        return result
    }

    private companion object { const val KEY = "shared_attachment_uris" }
}

@Composable
internal fun ReceivePendingConversationShares(
    shares: SharedConversationAttachments,
    controller: WorkbenchController?,
    receive: (List<Uri>) -> Boolean,
    onImported: () -> Unit,
) {
    if (controller == null) return
    val state by controller.state.collectAsState()
    val pending by shares.pending.collectAsState()
    LaunchedEffect(controller, pending, state.isOnline, state.activeThreadId, state.historyHydrated,
        state.creatingThread, state.managingThreads) {
        if (shares.deliverTo(controller, receive)) onImported()
    }
}
