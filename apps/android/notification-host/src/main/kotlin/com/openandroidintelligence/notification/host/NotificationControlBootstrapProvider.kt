package com.openandroidintelligence.notification.host

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 自注册装配钩子：系统在 app 进程启动、任何组件初始化之前创建
 * ContentProvider，因此装配不依赖 OpenAndroidIntelligenceApplication.onCreate
 * 已初始化的字段（审计 sink / 配对授权等上游设施一律延迟注入或可选）。
 *
 * 这不是数据提供者：所有 CRUD 方法返回空值，仅 onCreate 完成装配。
 * manifest 里 android:exported="false"。
 */
class NotificationControlBootstrapProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false
        NotificationHostInstaller.install(context)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
