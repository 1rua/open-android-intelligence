package com.openandroidintelligence.mobile.plugins

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import com.openandroidintelligence.plugin.ui.*

@Composable fun NativePluginSurface(host: ProductionPluginHost,id: String,provider: NativeUiProvider,context: NativeUiContext) {
    // This recovery control is always rendered by the host, outside guest composition.
    Column(Modifier.fillMaxSize()) {
        Text("Developer Trust · 原生界面与宿主共享 UID",style=MaterialTheme.typography.labelSmall)
        TextButton(onClick={ host.selectNativeUi(null) }) { Text("恢复宿主界面") }
        key(id) {
            host.beginNativeFrame(id)
            DisposableEffect(id) { onDispose { host.completeNativeFrame(id) } }
            provider.Render(context)
            SideEffect { host.completeNativeFrame(id) }
        }
    }
}
