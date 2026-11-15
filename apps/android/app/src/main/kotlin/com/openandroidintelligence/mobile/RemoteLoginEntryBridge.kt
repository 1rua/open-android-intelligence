package com.openandroidintelligence.mobile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.openandroidintelligence.gateway.remote.auth.*
import com.openandroidintelligence.gateway.remote.authproduction.RemoteCredentialVault
import com.openandroidintelligence.gateway.remote.authproduction.RemoteLoginAttemptFence
import com.openandroidintelligence.gateway.remote.network.RemoteServerApi
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

typealias RemoteOAuthProvider = com.openandroidintelligence.gateway.remote.auth.RemoteOAuthProvider

data class RemoteLoginEntryState(
    val busy: Boolean = false,
    val errorCode: String? = null,
    val awaitingBrowser: Boolean = false,
    val scanRequested: Boolean = false,
    val qr: RemotePairingQr? = null,
    val session: RemotePairingSession? = null,
    val pairingState: String? = null,
    val verificationCode: String? = null,
) {
    override fun toString() = "RemoteLoginEntryState(busy=$busy,秘密=已隐藏)"
}

/** 原生界面只调用意图；账号令牌、临时手机私钥和扫码秘密均不进入普通设置或日志。 */
class RemoteLoginEntryBridge(
    context: Context,
    private val runtime: RemoteRuntime,
    private val scope: CoroutineScope,
) {
    private val vault = RemoteCredentialVault(context)
    private val fence = RemoteLoginAttemptFence(context)
    private val browser = RemoteBrowserLoginStore(vault)
    private val pairing = RemoteDevicePairing(vault)
    private val mutableState = MutableStateFlow(RemoteLoginEntryState())
    val state = mutableState.asStateFlow()
    private val entryLock = Any()
    private val generation = AtomicLong()
    private var operation: Job? = null
    private var poller: Job? = null
    private var pending: RemoteDevicePairing.Pending? = null
    private var foreground = false

    init {
        scope.launch { synchronized(entryLock) {
            if (generation.get() != 0L) return@launch
            try {
                if (fence.blocked() || vault.activeAccount() != null) {
                    browser.cancel(); pairing.clear()
                    return@launch
                }
                browser.pending()?.let { attempt ->
                    if (generation.get() != 0L) return@launch
                    vault.requireEpoch(attempt.epoch)
                    mutableState.value = RemoteLoginEntryState(busy = true, awaitingBrowser = true)
                }
                pairing.restore()?.let { restored ->
                    if (generation.get() != 0L) return@launch
                    vault.requireEpoch(restored.epoch)
                    fence.requirePending(restored.loginAttemptId)
                    pending = restored
                    mutableState.value = RemoteLoginEntryState(session = restored.session, pairingState = "recovering")
                    if (foreground) startPolling()
                }
            } catch (_: Exception) {
                if (generation.get() != 0L) return@launch
                runCatching { browser.cancel(); pairing.clear() }
                mutableState.value = RemoteLoginEntryState(errorCode = "REMOTE_LOGIN_RECOVERY_FAILED")
            }
        } }
    }

    fun startOAuth(context: Context, provider: RemoteOAuthProvider) {
        val ticket = replaceOperation()
        operation = scope.launch {
            synchronized(entryLock) { current(ticket); mutableState.value = RemoteLoginEntryState(busy = true) }
            try {
                synchronized(entryLock) {
                    current(ticket)
                    fence.block(); browser.cancel(); pairing.clear(); pending = null
                }
                val discovery = RemoteServerApi(RemoteServerApi.DEFAULT_ORIGIN).loginProviders()
                val items = (discovery.fields.firstOrNull { it.first == "items" }?.second as? JsonValue.JArray)?.items
                    ?: error("INVALID_MESSAGE")
                val providers = items.map { it as? JsonValue.JObject ?: error("INVALID_MESSAGE") }
                val selected = providers.singleOrNull { row -> text(row, "id") == provider.id }
                    ?: error("OAUTH_PROVIDER_UNAVAILABLE")
                current(ticket)
                val attempt = synchronized(entryLock) {
                    current(ticket)
                    fence.allowNewAttempt()
                    browser.begin(provider, vault.authorizationEpoch())
                }
                val url = RemoteBrowserLoginStore.authorizeUrl(RemoteServerApi.DEFAULT_ORIGIN, text(selected, "startUrl"), attempt)
                current(ticket)
                withContext(Dispatchers.Main) {
                    current(ticket)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                synchronized(entryLock) {
                    current(ticket)
                    mutableState.value = RemoteLoginEntryState(busy = true, awaitingBrowser = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                synchronized(entryLock) {
                    if (generation.get() == ticket) {
                        runCatching { fence.block(); browser.cancel() }
                        mutableState.value = RemoteLoginEntryState(errorCode = if (failure is ActivityNotFoundException) "REMOTE_BROWSER_UNAVAILABLE" else errorCode(failure))
                    }
                }
            }
        }
    }

    /** 独立 Activity 只转交完整 URI，先校验再原子消费；未发起、过期、重复或旧代次回调均拒绝。 */
    fun handleOAuthCallback(raw: String): Boolean {
        // 非本次回调不取消正在进行的合法尝试，避免任意恶意深链造成登录被抢占。
        val accepted = try { synchronized(entryLock) {
            check(!fence.blocked()) { "REMOTE_OAUTH_ATTEMPT_MISSING" }
            val attemptId = fence.currentAttemptId()
            val result = browser.consume(raw, vault.authorizationEpoch())
            Triple(result, replaceOperation(), attemptId)
        } }
        catch (_: Exception) {
            if (!state.value.awaitingBrowser) mutableState.update { it.copy(errorCode = "REMOTE_OAUTH_CALLBACK_INVALID") }
            return false
        }
        val (result, ticket, attemptId) = accepted
        operation = scope.launch {
            synchronized(entryLock) { current(ticket); mutableState.value = RemoteLoginEntryState(busy = true) }
            try {
                if (result.error != null) {
                    mutableState.value = RemoteLoginEntryState(errorCode = "REMOTE_OAUTH_CANCELLED")
                    return@launch
                }
                current(ticket)
                currentCoroutineContext().ensureActive()
                runtime.completeExternalLogin(requireNotNull(result.token), attemptId)
                current(ticket)
                mutableState.value = RemoteLoginEntryState()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation.get() == ticket) mutableState.value = RemoteLoginEntryState(errorCode = errorCode(failure)) }
        }
        return true
    }

    fun scanPairing() {
        cancel()
        if (state.value.errorCode == null) mutableState.value = RemoteLoginEntryState(scanRequested = true)
    }
    fun scannerUnavailable() { mutableState.value = RemoteLoginEntryState(errorCode = "REMOTE_CAMERA_UNAVAILABLE") }
    fun scanned(raw: String?) {
        mutableState.update { it.copy(scanRequested = false) }
        if (raw == null) { mutableState.value = RemoteLoginEntryState(); return }
        try {
            check(runtime.state.value.account == null) { "PAIRING_DEVICE_CONFLICT" }
            val qr = pairing.parseQr(raw)
            mutableState.value = RemoteLoginEntryState(qr = qr, session = qr.session, pairingState = if (qr.session == null) "origin" else "confirm")
        } catch (failure: Exception) { mutableState.value = RemoteLoginEntryState(errorCode = errorCode(failure)) }
    }
    fun confirmOrigin() = runEntry { ticket ->
        val qr = state.value.qr ?: error("INVALID_MESSAGE")
        val discovery = RemoteServerApi(RemoteServerApi.DEFAULT_ORIGIN).loginProviders()
        val capability = (discovery.fields.firstOrNull { it.first == "capabilities" }?.second as? JsonValue.JObject)
        check(capability != null && text(capability, "devicePairing") == "dsh-pairing-v1") { "PAIRING_UNSUPPORTED" }
        val session = pairing.resolve(qr)
        synchronized(entryLock) {
            current(ticket)
            mutableState.update { it.copy(busy = false, session = session, pairingState = "confirm") }
        }
    }
    fun confirmHost() = runEntry { ticket ->
        val session = state.value.session ?: error("INVALID_MESSAGE")
        if (pending == null) {
            val discovery = RemoteServerApi(RemoteServerApi.DEFAULT_ORIGIN).loginProviders()
            val capability = discovery.fields.firstOrNull { it.first == "capabilities" }?.second as? JsonValue.JObject
            check(capability != null && text(capability, "devicePairing") == "dsh-pairing-v1") { "PAIRING_UNSUPPORTED" }
            currentCoroutineContext().ensureActive()
        }
        val next = synchronized(entryLock) {
            current(ticket)
            pending?.takeIf { it.session.id == session.id } ?: run {
                val attemptId = fence.allowNewAttempt()
                pairing.prepareClaim(session, vault.authorizationEpoch(), attemptId).also { pending = it }
            }
        }
        val claimed = pairing.claim(next)
        synchronized(entryLock) {
            current(ticket)
            mutableState.update { it.copy(busy = false, qr = null, pairingState = claimed.state, verificationCode = claimed.verificationCode) }
            startPolling()
        }
    }
    fun retryPairing() {
        if (pending != null) startPolling(recoverCode = state.value.verificationCode == null)
    }
    private fun runEntry(action: suspend (Long) -> Unit) {
        val ticket = replaceOperation()
        operation = scope.launch {
            synchronized(entryLock) { current(ticket); mutableState.update { it.copy(busy = true, errorCode = null) } }
            try { action(ticket); current(ticket) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation.get() == ticket) mutableState.update { it.copy(busy = false, errorCode = errorCode(failure)) } }
        }
    }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { poller?.cancel(); poller = null }
        else {
            try {
                if (fence.blocked()) return
                val attempt = browser.pending()
                if (state.value.awaitingBrowser && attempt == null) mutableState.value = RemoteLoginEntryState(errorCode = "REMOTE_OAUTH_ATTEMPT_MISSING")
                if (pending != null) startPolling(recoverCode = state.value.verificationCode == null)
            } catch (_: Exception) {
                cancelLocal()
                mutableState.value = RemoteLoginEntryState(errorCode = "REMOTE_LOGIN_RECOVERY_FAILED")
            }
        }
    }
    private fun startPolling(recoverCode: Boolean = false) = synchronized(entryLock) {
        poller?.cancel()
        if (!foreground || fence.blocked()) return@synchronized
        val flow = pending ?: return@synchronized
        val ticket = generation.get()
        poller = scope.launch {
            try {
                current(ticket); vault.requireEpoch(flow.epoch)
                if (recoverCode && flow.session.expiresAt > System.currentTimeMillis()) {
                    // 查询结果优先，完成的会话不再重新领取验证码。
                    val status = pairing.status(flow)
                    if (acceptStatus(flow, status, ticket)) return@launch
                    if (status.state == "claimed") {
                        val code = pairing.claim(flow)
                        synchronized(entryLock) {
                            current(ticket)
                            mutableState.update { it.copy(verificationCode = code.verificationCode) }
                        }
                    }
                }
                while (isActive && foreground) {
                    current(ticket); vault.requireEpoch(flow.epoch)
                    val status = pairing.status(flow)
                    if (acceptStatus(flow, status, ticket)) return@launch
                    delay(2500)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation.get() == ticket) mutableState.update { it.copy(busy = false, errorCode = errorCode(failure)) } }
        }
    }
    private suspend fun acceptStatus(flow: RemoteDevicePairing.Pending, status: RemotePairingStatus, ticket: Long): Boolean {
        current(ticket); vault.requireEpoch(flow.epoch)
        when (status.state) {
            "completed" -> {
                mutableState.update { it.copy(busy = true, verificationCode = null) }
                current(ticket)
                runtime.completePairing(requireNotNull(status.imported), flow.session.hostId)
                synchronized(entryLock) {
                    current(ticket)
                    fence.block(); pairing.clear(); pending = null
                    mutableState.value = RemoteLoginEntryState()
                    runtime.refreshPairedHost(flow.session.hostId)
                }
                return true
            }
            "failed", "cancelled", "expired" -> {
                synchronized(entryLock) {
                    current(ticket)
                    fence.block(); pairing.clear(); pending = null
                    mutableState.value = RemoteLoginEntryState(errorCode = "PAIRING_CLOSED")
                }
                return true
            }
            else -> synchronized(entryLock) {
                current(ticket)
                mutableState.update { it.copy(busy = false, pairingState = status.state, errorCode = null,
                    verificationCode = if (status.state == "approved") null else it.verificationCode) }
            }
        }
        return false
    }
    fun dismissError() { mutableState.update { it.copy(errorCode = null) } }
    fun cancel() {
        val old = pending
        cancelLocal()
        if (old != null) scope.launch { runCatching { pairing.cancel(old) } }
    }
    /** 退出或切换登录方式先失效本地代次，迟到的服务器回执不能重新登录。 */
    fun cancelLocal() = synchronized(entryLock) {
        replaceOperation()
        pending = null
        try {
            fence.block()
            browser.cancel(); pairing.clear()
            mutableState.value = RemoteLoginEntryState()
        } catch (_: Exception) {
            mutableState.value = RemoteLoginEntryState(errorCode = "REMOTE_LOGIN_CANCELLATION_INCOMPLETE")
        }
    }
    private fun replaceOperation(): Long = synchronized(entryLock) {
        val next = generation.incrementAndGet()
        operation?.cancel(); operation = null; poller?.cancel(); poller = null
        next
    }
    private fun current(ticket: Long) { check(generation.get() == ticket) { "REMOTE_AUTHORIZATION_SUPERSEDED" } }
    private fun errorCode(failure: Exception): String = (failure as? RemoteRpcException)?.code
        ?: failure.message?.takeIf { Regex("[A-Z0-9_]{1,128}").matches(it) } ?: "CONNECTION_FAILED"
    private fun text(value: JsonValue.JObject, key: String) = (value.fields.firstOrNull { it.first == key }?.second as? JsonValue.JString)?.value ?: error("INVALID_MESSAGE")
}

@Composable
fun rememberRemoteLoginEntryBridge(runtime: RemoteRuntime): RemoteLoginEntryBridge = remember(runtime) { runtime.loginEntryBridge }
