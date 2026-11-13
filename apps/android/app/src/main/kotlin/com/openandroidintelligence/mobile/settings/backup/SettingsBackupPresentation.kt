package com.openandroidintelligence.mobile.settings.backup

import android.content.Context
import com.openandroidintelligence.ui.resources.R as UiR

/** 摘要只在展示边界读取资源，文件编解码与偏好模型不持有 Android 资源。 */
internal fun MessageProcessingSection.summary(context: Context): String {
    val value = when (delayMillis) {
        null -> context.getString(UiR.string.settings_backup_msg_merge_unchanged)
        0 -> context.getString(UiR.string.settings_backup_msg_merge_immediate)
        else -> context.getString(UiR.string.settings_backup_msg_merge_seconds, (delayMillis / 1000.0).toString())
    }
    return context.getString(UiR.string.settings_backup_msg_merge_val, value)
}

internal fun SettingsBackupSections.summaries(context: Context): List<String> = buildList {
    appearance?.let { add(it.summary()) }
    notifications?.let { add(it.summary()) }
    messageProcessing?.let { add(it.summary(context)) }
    conversation?.let { add(it.summary()) }
    connection?.let { add(it.summary()) }
    agents?.let { add(it.summary()) }
    capabilities?.let { add(it.summary()) }
    updates?.let { add(it.summary()) }
    notificationCollection?.let { add(it.summary()) }
    devicePush?.let { add(it.summary()) }
}

/** 旧异常只保留兼容消息；新错误码在展示时读取当前语言的资源。 */
internal fun BackupFormatErrorCode.toStringRes(): Int? = when (this) {
    BackupFormatErrorCode.LEGACY_MESSAGE -> null
    BackupFormatErrorCode.INVALID_JSON -> UiR.string.err_backup_invalid_json
    BackupFormatErrorCode.TOP_LEVEL_OBJECT_REQUIRED -> UiR.string.err_backup_top_level_object_required
    BackupFormatErrorCode.SETTINGS_REQUIRED -> UiR.string.err_backup_settings_required
    BackupFormatErrorCode.SCHEMA_VERSION_REQUIRED -> UiR.string.err_backup_schema_version_required
    BackupFormatErrorCode.SCHEMA_VERSION_NEWER -> UiR.string.err_backup_schema_version_newer
    BackupFormatErrorCode.SCHEMA_VERSION_INVALID -> UiR.string.err_backup_schema_version_invalid
    BackupFormatErrorCode.EXPORTED_AT_REQUIRED -> UiR.string.err_backup_exported_at_required
    BackupFormatErrorCode.APP_VERSION_NAME_REQUIRED -> UiR.string.err_backup_app_version_name_required
    BackupFormatErrorCode.APP_VERSION_CODE_REQUIRED -> UiR.string.err_backup_app_version_code_required
    BackupFormatErrorCode.MIGRATION_UNAVAILABLE -> UiR.string.err_backup_migration_unavailable
    BackupFormatErrorCode.SECTION_OBJECT_REQUIRED -> UiR.string.err_backup_section_object_required
    BackupFormatErrorCode.FIELD_STRING_REQUIRED -> UiR.string.err_backup_field_string_required
    BackupFormatErrorCode.FIELD_BOOLEAN_REQUIRED -> UiR.string.err_backup_field_boolean_required
    BackupFormatErrorCode.FIELD_INTEGER_REQUIRED -> UiR.string.err_backup_field_integer_required
    BackupFormatErrorCode.FIELD_ARRAY_REQUIRED -> UiR.string.err_backup_field_array_required
    BackupFormatErrorCode.FIELD_STRINGS_REQUIRED -> UiR.string.err_backup_field_strings_required
    BackupFormatErrorCode.FIELD_OBJECTS_REQUIRED -> UiR.string.err_backup_field_objects_required
    BackupFormatErrorCode.FIELD_OBJECT_REQUIRED -> UiR.string.err_backup_field_object_required
    BackupFormatErrorCode.FIELD_VALUE_INVALID -> UiR.string.err_backup_field_value_invalid
    BackupFormatErrorCode.NO_RESTORABLE_SETTINGS -> UiR.string.err_backup_no_restorable_settings
    BackupFormatErrorCode.FILE_TOO_LARGE -> UiR.string.err_backup_file_too_large
}

internal fun BackupNotice.displayMessage(context: Context): String {
    val failure = formatFailure ?: return message
    val resource = failure.errorCode.toStringRes() ?: return message
    val prefix = when (failureOperation) {
        BackupFailureOperation.INSPECT -> UiR.string.settings_backup_inspect_failed
        BackupFailureOperation.IMPORT -> UiR.string.settings_backup_import_failed
        null -> return message
    }
    return context.getString(prefix, context.getString(resource, *failure.detailArgs.toTypedArray()))
}
