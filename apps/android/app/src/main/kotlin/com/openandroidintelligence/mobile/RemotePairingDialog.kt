package com.openandroidintelligence.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.openandroidintelligence.ui.resources.R as UiR

/** 只有协议要求的服务器/Host 确认和电脑验证码步骤；验证码不提供复制或分享入口。 */
@Composable
fun RemotePairingDialog(bridge: RemoteLoginEntryBridge) {
    val state by bridge.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, bridge) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) bridge.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) bridge.setForeground(false)
        }
        lifecycle.addObserver(observer)
        bridge.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); bridge.setForeground(false) }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { bridge.scanned(it.contents) }
    val prompt = stringResource(UiR.string.remote_pairing_scan_prompt)
    LaunchedEffect(state.scanRequested) {
        if (state.scanRequested) {
            try {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt)
                    .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false)
                    .setCaptureActivity(RemotePairingCaptureActivity::class.java))
            } catch (_: Exception) { bridge.scannerUnavailable() }
        }
    }
    val visible = state.awaitingBrowser || state.pairingState != null
    if (!visible) return
    AlertDialog(
        onDismissRequest = bridge::cancel,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(stringResource(if (state.awaitingBrowser) UiR.string.remote_oauth_wait_title else UiR.string.remote_pairing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.awaitingBrowser -> Text(stringResource(UiR.string.remote_oauth_wait_body))
                    state.pairingState == "origin" -> Text(stringResource(UiR.string.remote_pairing_origin_body))
                    state.session != null -> {
                        Text(stringResource(UiR.string.remote_pairing_host_identity, state.session!!.hostName, state.session!!.fingerprint))
                        if (state.pairingState == "confirm") Text(stringResource(UiR.string.remote_pairing_confirm_body))
                        else {
                            state.verificationCode?.let { Text(it, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.fillMaxWidth()) }
                            Text(stringResource(UiR.string.remote_pairing_code_body))
                        }
                    }
                }
                if (state.busy && !state.awaitingBrowser) CircularProgressIndicator()
                if (state.errorCode != null) Text(stringResource(UiR.string.remote_pairing_retry_body), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            when (state.pairingState) {
                "origin" -> TextButton(onClick = bridge::confirmOrigin, enabled = !state.busy) { Text(stringResource(UiR.string.remote_pairing_confirm_origin)) }
                "confirm" -> TextButton(onClick = bridge::confirmHost, enabled = !state.busy) { Text(stringResource(UiR.string.remote_pairing_confirm_host)) }
                else -> if (state.errorCode != null && !state.awaitingBrowser) TextButton(onClick = bridge::retryPairing) { Text(stringResource(UiR.string.remote_pairing_recover)) }
            }
        },
        dismissButton = { TextButton(onClick = bridge::cancel) { Text(stringResource(android.R.string.cancel)) } },
    )
}
