package com.openandroidintelligence.mobile.updates

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openandroidintelligence.mobile.BuildConfig
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 非秘密的本机更新偏好，与账号、Gateway 和配对授权分别保存。 */
class AppUpdatePreferences(context: Context, private val defaultChannel: AppUpdateChannel) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "open_android_intelligence_app_updates",
        Context.MODE_PRIVATE,
    )

    fun readChannel(): AppUpdateChannel = preferences.getString("channel", null)
        ?.let { saved -> AppUpdateChannel.entries.firstOrNull { it.name == saved } }
        ?: defaultChannel

    fun writeChannel(channel: AppUpdateChannel): Boolean =
        preferences.edit().putString("channel", channel.name).commit()
}

data class AppUpdateUiState(
    val channel: AppUpdateChannel,
    val isChecking: Boolean = false,
    val result: AppUpdateResult? = null,
    val preferenceSaveFailed: Boolean = false,
)

/** 页面离开后取消检查；切换渠道时，旧请求不能覆盖新渠道的结果。 */
class AppUpdateViewModel(
    val installed: InstalledAppBuild,
    private val preferences: AppUpdatePreferences,
    private val checkUpdate: suspend (AppUpdateChannel) -> AppUpdateResult = AppUpdateChecker(installed)::check,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    externalScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = externalScope ?: viewModelScope
    private val _uiState = MutableStateFlow(AppUpdateUiState(channel = preferences.readChannel()))
    val uiState = _uiState.asStateFlow()
    private var checking: Job? = null

    fun checkIfNeeded() {
        if (_uiState.value.result == null) check()
    }

    fun selectChannel(channel: AppUpdateChannel) {
        if (_uiState.value.channel == channel) return
        checking?.cancel()
        _uiState.value = AppUpdateUiState(channel = channel)
        check()
    }

    fun check() {
        if (_uiState.value.isChecking) return
        val channel = _uiState.value.channel
        _uiState.value = _uiState.value.copy(isChecking = true, result = null)
        checking = scope.launch {
            val saved = withContext(ioDispatcher) {
                runCatching { preferences.writeChannel(channel) }.getOrDefault(false)
            }
            val result = try {
                withContext(ioDispatcher) { checkUpdate(channel) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AppUpdateResult.Unavailable(AppUpdateFailure.SERVICE)
            }
            _uiState.value = AppUpdateUiState(
                channel = channel,
                result = result,
                preferenceSaveFailed = !saved,
            )
        }
    }
}

/** 使用实际安装包的版本与签名，不借用 Gateway 身份或凭据。 */
@Suppress("DEPRECATION")
fun installedAppBuild(context: Context): InstalledAppBuild {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val certificate = info.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray()
    val fingerprint = certificate?.let {
        MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) }
    }
    return InstalledAppBuild(
        versionName = info.versionName ?: BuildConfig.VERSION_NAME,
        versionCode = info.longVersionCode,
        commit = BuildConfig.GIT_COMMIT,
        channel = BuildConfig.UPDATE_CHANNEL,
        applicationId = context.packageName,
        distribution = BuildConfig.FLAVOR,
        signingCertificateSha256 = fingerprint,
    )
}

internal fun defaultAppUpdateChannel(installed: InstalledAppBuild): AppUpdateChannel =
    if (installed.channel == "stable") AppUpdateChannel.STABLE else AppUpdateChannel.PREVIEW

internal fun appBuildChannelLabel(channel: String, versionName: String): String = when {
    channel == "stable" -> "正式版"
    channel == "development" || DEVELOPMENT_PREVIEW_VERSION.matches(versionName) -> "开发预览测试版"
    channel == "preview" -> "版本号测试版"
    else -> "开发预览测试版"
}

private val DEVELOPMENT_PREVIEW_VERSION = Regex(".+-dev\\.[0-9]+(?:\\+[^+]+)?")
