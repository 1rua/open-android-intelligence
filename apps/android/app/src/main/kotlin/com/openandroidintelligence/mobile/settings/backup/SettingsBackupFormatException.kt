package com.openandroidintelligence.mobile.settings.backup

/**
 * 备份文件的格式错误。消息面向用户，不包含异常栈或内部字段路径以外的细节。
 *
 * 任何一条抛出都意味着「整份文件不导入」，调用方不得吞掉后继续写入——
 * 部分导入比不导入更危险：用户会以为恢复成功，实际只恢复了一半。
 */
class SettingsBackupFormatException(message: String) : IllegalArgumentException(message)
