package com.openandroidintelligence.plugin.ui

import androidx.compose.runtime.Composable

/** Versioned SPI for globally trusted native plugins. Credentials and host objects are not passed through this SPI. */
interface NativeUiProvider {
    val uiApiVersion: Int get() = 1
    @Composable fun Render(context: NativeUiContext)
}

data class NativeUiMessage(val id: String,val sender: String,val text: String,val streaming: Boolean)
data class NativeUiContext(val gatewayLabel: String,val conversationId: String?,val title: String,val draft: String,
    val messages: List<NativeUiMessage>,val editDraft:(String)->Unit,val send:()->Unit,val stop:()->Unit,val openSettings:()->Unit)
