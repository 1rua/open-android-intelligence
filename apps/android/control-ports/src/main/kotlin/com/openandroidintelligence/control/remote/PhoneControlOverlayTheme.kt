package com.openandroidintelligence.control.remote

import android.content.Context

/** 应用进程可提供当前色板。参数使用服务的配置和显示资源。 */
interface PhoneControlOverlayTheme {
    fun phoneControlOverlayColors(context: Context): PhoneControlOverlayColors
}

/** 仅接收已解析的 Material 3 色彩角色，应用可以直接传入当前色板。 */
data class PhoneControlOverlayColors(
    val surfaceContainerHigh: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
)
