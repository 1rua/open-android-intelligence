package com.openandroidintelligence.mobile.settings.backup

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/**
 * 设置备份文件的外层结构与编解码。
 *
 * 设计约束（对应需求第 3、4 条）：
 * 1. 文件里只有客户端偏好。密码、访问令牌、刷新凭据、主密钥、安装标识、TLS
 *    信任锚、配对原语授权与临时批准在本包里没有对应字段，任何导出路径都不可能
 *    把它们带出去。
 * 2. 每一节都可缺失：缺失表示「这份文件没有这一项」，导入时保持本机现值，不会用
 *    默认值把新版本设置清空。
 * 3. 未知的节名或字段只记诊断、不参与写入；未知的枚举值或越界数值属于格式非法，
 *    整份文件拒绝导入——宁可拒绝，也不猜一个「差不多」的值。
 */
object SettingsBackupSchema {
    const val CURRENT_VERSION = 1

    /** 顶层字段闭集。出现其他字段时忽略并记录诊断，不参与导入。 */
    val TOP_LEVEL_FIELDS = setOf("schemaVersion", "exportedAt", "appVersionName", "appVersionCode", "settings")

    /**
     * 旧版本 → 当前版本的迁移表。
     *
     * 目前只有版本 1，因此为空。将来改名或放宽字段时，迁移函数必须登记在这里，
     * 由 [SettingsBackupCodec.decode] 统一执行，而不是散落在读取处。
     */
    val MIGRATIONS: Map<Int, (JsonValue.JObject) -> JsonValue.JObject> = emptyMap()
}

data class SettingsBackupDocument(
    val schemaVersion: Int,
    val exportedAt: String,
    val appVersionName: String,
    val appVersionCode: Long,
    /** 节名 → 该节的原始对象；内容校验由 [SettingsBackupSections.decode] 完成。 */
    val sections: Map<String, JsonValue.JObject>,
    /** 解析时忽略的字段路径，仅用于如实告知用户「忽略了什么」。 */
    val ignoredFields: List<String> = emptyList(),
)

object SettingsBackupCodec {

    /**
     * 把已通过校验的节编码成文件文本。
     *
     * 使用 RFC 8785 规范化输出：同一份配置每次导出的字节序稳定，便于用户比对，
     * 也让测试可以逐字节断言。
     */
    fun encode(
        sections: Map<String, JsonValue.JObject>,
        appVersionName: String,
        appVersionCode: Long,
        exportedAt: String,
    ): String {
        require(exportedAt.isNotBlank() && appVersionName.isNotBlank()) { "backup metadata must not be blank" }
        require(appVersionCode >= 0) { "backup version code must not be negative" }
        val ordered = sections.entries.sortedWith(compareBy { entry -> entry.key })
        val settings = JsonValue.JObject(ordered.map { entry -> entry.key to entry.value })
        val root = JsonValue.JObject(
            listOf(
                "schemaVersion" to Json.of(SettingsBackupSchema.CURRENT_VERSION),
                "exportedAt" to Json.of(exportedAt),
                "appVersionName" to Json.of(appVersionName),
                "appVersionCode" to Json.of(appVersionCode),
                "settings" to settings,
            ),
        )
        return Json.canonical(root)
    }

    /**
     * 解析并做外层校验。
     *
     * @param knownSections 接受的节名；其他节列入诊断。
     * @param knownFields 各节的字段闭集；其他字段列入诊断。
     */
    fun decode(
        text: String,
        knownSections: Set<String> = BackupSection.WIRE_NAMES,
        knownFields: Map<String, Set<String>> = BackupFieldSets.SECTIONS,
    ): SettingsBackupDocument {
        val root = try {
            Json.parse(text)
        } catch (_: IllegalArgumentException) {
            throw SettingsBackupFormatException("备份文件不是有效的 JSON。", BackupFormatErrorCode.INVALID_JSON)
        } as? JsonValue.JObject ?: throw SettingsBackupFormatException(
            "备份文件顶层必须是 JSON 对象。", BackupFormatErrorCode.TOP_LEVEL_OBJECT_REQUIRED, // i18n-ignore: 保留兼容 message；展示由错误码读取资源
        )

        val settings = JsonFields.obj(JsonFields.field(root, "settings"))
            ?: throw SettingsBackupFormatException("备份文件缺少 settings。", BackupFormatErrorCode.SETTINGS_REQUIRED)
        val ignored = buildList {
            root.fields.map { it.first }.filter { it !in SettingsBackupSchema.TOP_LEVEL_FIELDS }.forEach(::add)
            settings.fields.forEach { (name, value) ->
                if (name !in knownSections) {
                    add("settings.$name")
                    return@forEach
                }
                val fields = knownFields[name] ?: return@forEach
                (value as? JsonValue.JObject)?.fields?.map { it.first }?.filter { it !in fields }
                    ?.forEach { add("settings.$name.$it") }
            }
        }.distinct()

        var version = JsonFields.int(root, "schemaVersion")
            ?: throw SettingsBackupFormatException("备份文件缺少 schemaVersion。", BackupFormatErrorCode.SCHEMA_VERSION_REQUIRED)
        if (version > SettingsBackupSchema.CURRENT_VERSION) {
            throw SettingsBackupFormatException(
                "备份文件来自更新版本的 App（schemaVersion=$version），请先升级 App。",
                BackupFormatErrorCode.SCHEMA_VERSION_NEWER, listOf(version.toString()),
            )
        }
        if (version < 0) throw SettingsBackupFormatException("备份文件 schemaVersion 非法。", BackupFormatErrorCode.SCHEMA_VERSION_INVALID)
        val exportedAt = JsonFields.string(root, "exportedAt")?.takeIf { it.isNotBlank() }
            ?: throw SettingsBackupFormatException("备份文件缺少导出时间。", BackupFormatErrorCode.EXPORTED_AT_REQUIRED)
        val appVersionName = JsonFields.string(root, "appVersionName")?.takeIf { it.isNotBlank() }
            ?: throw SettingsBackupFormatException("备份文件缺少 App 版本。", BackupFormatErrorCode.APP_VERSION_NAME_REQUIRED)
        val appVersionCode = JsonFields.long(root, "appVersionCode")
            ?: throw SettingsBackupFormatException("备份文件缺少 App versionCode。", BackupFormatErrorCode.APP_VERSION_CODE_REQUIRED)

        var migrated = settings
        while (version < SettingsBackupSchema.CURRENT_VERSION) {
            val migrate = SettingsBackupSchema.MIGRATIONS[version]
                ?: throw SettingsBackupFormatException(
                    "备份文件版本 $version 无法迁移到当前版本。",
                    BackupFormatErrorCode.MIGRATION_UNAVAILABLE, listOf(version.toString()),
                )
            migrated = migrate(migrated)
            version++
        }
        val sections = migrated.fields.mapNotNull { (name, value) ->
            if (name !in knownSections) return@mapNotNull null
            val section = value as? JsonValue.JObject
                ?: throw SettingsBackupFormatException(
                    "设置项 $name 必须是 JSON 对象。", BackupFormatErrorCode.SECTION_OBJECT_REQUIRED, listOf(name),
                )
            name to section
        }.toMap()
        return SettingsBackupDocument(
            schemaVersion = version,
            exportedAt = exportedAt,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            sections = sections,
            ignoredFields = ignored,
        )
    }
}

/** 读取节内字段并拒绝类型错误：字段在就按声明类型校验，不在就返回 null（保持本机值）。 */
internal object BackupFields {
    fun string(section: JsonValue.JObject, name: String): String? =
        field(section, name)?.let { (it as? JsonValue.JString)?.value
            ?: throw SettingsBackupFormatException("字段 $name 必须是字符串。", BackupFormatErrorCode.FIELD_STRING_REQUIRED, listOf(name)) }

    fun boolean(section: JsonValue.JObject, name: String): Boolean? =
        field(section, name)?.let { (it as? JsonValue.JBool)?.value
            ?: throw SettingsBackupFormatException("字段 $name 必须是布尔值。", BackupFormatErrorCode.FIELD_BOOLEAN_REQUIRED, listOf(name)) }

    fun int(section: JsonValue.JObject, name: String): Int? =
        field(section, name)?.let { raw ->
            (raw as? JsonValue.JNumber)?.raw?.toIntOrNull()
                ?: throw SettingsBackupFormatException("字段 $name 必须是整数。", BackupFormatErrorCode.FIELD_INTEGER_REQUIRED, listOf(name))
        }

    fun strings(section: JsonValue.JObject, name: String): List<String>? =
        field(section, name)?.let { raw ->
            val array = raw as? JsonValue.JArray ?: throw SettingsBackupFormatException(
                "字段 $name 必须是数组。", BackupFormatErrorCode.FIELD_ARRAY_REQUIRED, listOf(name),
            )
            array.items.map { item ->
                (item as? JsonValue.JString)?.value
                    ?: throw SettingsBackupFormatException("字段 $name 只能包含字符串。", BackupFormatErrorCode.FIELD_STRINGS_REQUIRED, listOf(name))
            }
        }

    fun objects(section: JsonValue.JObject, name: String): List<JsonValue.JObject>? =
        field(section, name)?.let { raw ->
            val array = raw as? JsonValue.JArray ?: throw SettingsBackupFormatException(
                "字段 $name 必须是数组。", BackupFormatErrorCode.FIELD_ARRAY_REQUIRED, listOf(name),
            )
            array.items.map { item ->
                item as? JsonValue.JObject ?: throw SettingsBackupFormatException(
                    "字段 $name 只能包含对象。", BackupFormatErrorCode.FIELD_OBJECTS_REQUIRED, listOf(name),
                )
            }
        }

    fun objectOrNull(section: JsonValue.JObject, name: String): JsonValue.JObject? =
        field(section, name)?.let { raw ->
            raw as? JsonValue.JObject ?: throw SettingsBackupFormatException(
                "字段 $name 必须是对象。", BackupFormatErrorCode.FIELD_OBJECT_REQUIRED, listOf(name),
            )
        }

    private fun field(section: JsonValue.JObject, name: String): JsonValue? =
        section.fields.firstOrNull { it.first == name }?.second
}
