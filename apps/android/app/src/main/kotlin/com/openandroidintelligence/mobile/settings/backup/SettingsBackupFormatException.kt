package com.openandroidintelligence.mobile.settings.backup

/**
 * 备份文件的格式错误。消息面向用户，不包含异常栈或内部字段路径以外的细节。
 *
 * 任何一条抛出都意味着「整份文件不导入」，调用方不得吞掉后继续写入——
 * 部分导入比不导入更危险：用户会以为恢复成功，实际只恢复了一半。
 */
class SettingsBackupFormatException(
    message: String,
    val errorCode: BackupFormatErrorCode = BackupFormatErrorCode.LEGACY_MESSAGE,
    val detailArgs: List<String> = emptyList(),
) : IllegalArgumentException(message)

/** 错误码与参数不依赖 UI；旧调用仍可只传 message，保持兼容提示。 */
enum class BackupFormatErrorCode {
    LEGACY_MESSAGE,
    INVALID_JSON,
    TOP_LEVEL_OBJECT_REQUIRED,
    SETTINGS_REQUIRED,
    SCHEMA_VERSION_REQUIRED,
    SCHEMA_VERSION_NEWER,
    SCHEMA_VERSION_INVALID,
    EXPORTED_AT_REQUIRED,
    APP_VERSION_NAME_REQUIRED,
    APP_VERSION_CODE_REQUIRED,
    MIGRATION_UNAVAILABLE,
    SECTION_OBJECT_REQUIRED,
    FIELD_STRING_REQUIRED,
    FIELD_BOOLEAN_REQUIRED,
    FIELD_INTEGER_REQUIRED,
    FIELD_ARRAY_REQUIRED,
    FIELD_STRINGS_REQUIRED,
    FIELD_OBJECTS_REQUIRED,
    FIELD_OBJECT_REQUIRED,
    FIELD_VALUE_INVALID,
    NO_RESTORABLE_SETTINGS,
    FILE_TOO_LARGE,
}
