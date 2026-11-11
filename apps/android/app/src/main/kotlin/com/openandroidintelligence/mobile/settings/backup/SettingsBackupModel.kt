package com.openandroidintelligence.mobile.settings.backup

import com.openandroidintelligence.gateway.http.GatewayEndpoint
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.kernel.CapabilityAuthorizationMode
import com.openandroidintelligence.kernel.CapabilityPolicy
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.mobile.conversations.DebouncePreferences

/**
 * 可迁移的客户端偏好数据模型。
 *
 * 每一节都只描述用户可见的偏好。授权开关（能力总开关「是否启用」、开发者信任模式、
 * 系统权限、主动推送开关）刻意不在模型里，因此导入路径没有任何字段可以「顺手打开」
 * 一项权限——这是需求第 3 条在类型层面的保证，而不是运行时的额外判断。
 */

/** 节名闭集；未知节名由 [SettingsBackupCodec.decode] 记入诊断并忽略。 */
enum class BackupSection(val wireName: String) {
    APPEARANCE("appearance"),
    NOTIFICATIONS("notifications"),
    MESSAGE_PROCESSING("messageProcessing"),
    CONVERSATION("conversation"),
    CONNECTION("connection"),
    AGENTS("agents"),
    CAPABILITIES("capabilities"),
    UPDATES("updates"),
    NOTIFICATION_COLLECTION("notificationCollection"),
    DEVICE_PUSH("devicePush"),
    ;

    companion object {
        val WIRE_NAMES: Set<String> = entries.map { it.wireName }.toSet()
    }
}

internal fun formatError(path: String, detail: String) =
    SettingsBackupFormatException("$path：$detail", BackupFormatErrorCode.FIELD_VALUE_INVALID, listOf(path, detail))

internal fun requireOneOf(value: String, allowed: Collection<String>, path: String): String {
    if (value !in allowed) throw formatError(path, "取值「$value」不受支持。")
    return value
}

data class AppearanceSection(
    val theme: ThemeValue,
    val dynamicColor: Boolean,
    val dynamicIconColor: Boolean,
    val palette: String,
    val reduceMotion: Boolean,
    val streamingHapticFeedback: Boolean = false,
) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(
        listOf(
            "theme" to Json.of(theme.name),
            "dynamicColor" to Json.of(dynamicColor),
            "dynamicIconColor" to Json.of(dynamicIconColor),
            "palette" to Json.of(palette),
            "reduceMotion" to Json.of(reduceMotion),
            "streamingHapticFeedback" to Json.of(streamingHapticFeedback),
        ),
    )

    fun summary(): String = buildString {
        append("外观：主题").append(theme.label)
        append("，预设色板 ").append(palette)
        if (dynamicColor) append("（跟随系统壁纸取色）")
        if (dynamicIconColor) append("，图标动态取色开")
        append(if (reduceMotion) "，已减少动画" else "，动画开启")
    }

    companion object {
        /**
         * @param allowedPalettes 合法色板名由调用方传入，避免备份层依赖 UI 主题模块；
         *   传入集合为空时跳过校验（只用于测试）。
         */
        fun decode(section: JsonValue.JObject, allowedPalettes: Set<String> = emptySet()): AppearanceSection {
            val themeName = BackupFields.string(section, "theme")
                ?: throw formatError("appearance.theme", "缺失。")
            val palette = BackupFields.string(section, "palette")
                ?: throw formatError("appearance.palette", "缺失。")
            if (allowedPalettes.isNotEmpty()) {
                requireOneOf(palette, allowedPalettes, "appearance.palette")
            }
            return AppearanceSection(
                theme = ThemeValue.valueOf(requireOneOf(themeName, ThemeValue.NAMES, "appearance.theme")),
                dynamicColor = BackupFields.boolean(section, "dynamicColor") ?: false,
                dynamicIconColor = BackupFields.boolean(section, "dynamicIconColor") ?: false,
                palette = palette,
                reduceMotion = BackupFields.boolean(section, "reduceMotion") ?: false,
                streamingHapticFeedback = BackupFields.boolean(section, "streamingHapticFeedback") ?: false,
            )
        }
    }
}

/** 与 `ThemePreference` 一一对应；备份层不 import 它，以免形成 App → 备份循环。 */
enum class ThemeValue(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    ;

    companion object {
        val NAMES: Set<String> = entries.map { it.name }.toSet()
    }
}

data class NotificationsSection(val replyAlertsEnabled: Boolean) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(
        listOf("replyAlertsEnabled" to Json.of(replyAlertsEnabled)),
    )

    fun summary(): String = "通知与提醒：Agent 任务通知${if (replyAlertsEnabled) "开" else "关"}" +
        "（系统通知权限仍需在 Android 设置中授予）"

    companion object {
        fun decode(section: JsonValue.JObject): NotificationsSection = NotificationsSection(
            replyAlertsEnabled = BackupFields.boolean(section, "replyAlertsEnabled")
                ?: throw formatError("notifications.replyAlertsEnabled", "缺失。"),
        )
    }
}

/**
 * 客户端的消息合并时长；只描述本机发送时机，不代表任何 Core 配置。
 *
 * 旧版本导出的节形是 `{global: {delayMillis, extendWindow}, cores: [...]}`。旧版每次导出都会
 * 无条件写 `global`（值为读取默认的 1.5 秒），文件里无法区分「用户主动设置」与「从未设置」，
 * 因此整节旧值都按 [legacyMergingSettings] 计数跳过，不继承——否则升级后默认 0 秒的承诺
 * 会对导入过旧备份的用户失效。
 */
data class MessageProcessingSection(
    val delayMillis: Int?,
    val legacyMergingSettings: Int = 0,
) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(
        listOfNotNull(delayMillis?.let { "delayMillis" to Json.of(it) }),
    )

    /** 旧文件里的合并值已不再适用，导入时需要如实告知。 */
    val hasLegacyMergingSettings: Boolean get() = legacyMergingSettings > 0

    companion object {
        fun decode(section: JsonValue.JObject): MessageProcessingSection {
            val direct = BackupFields.int(section, "delayMillis")
            if (direct != null &&
                direct !in DebouncePreferences.MIN_DELAY_MILLIS..DebouncePreferences.MAX_DELAY_MILLIS) {
                throw formatError("messageProcessing.delayMillis", "超出 0–30000 毫秒。")
            }
            val legacy = (if (BackupFields.objectOrNull(section, "global") != null) 1 else 0) +
                BackupFields.objects(section, "cores").orEmpty().size
            return MessageProcessingSection(direct, legacyMergingSettings = legacy)
        }
    }
}

data class ConversationFolderValue(val folderId: String, val name: String)

/**
 * 一个（账号档案, Agent）组合下的会话组织偏好。
 *
 * 会话历史不导出，但排序方式、自定义顺序、文件夹与归类属于手机端偏好，
 * 换机后仍应保留。`profileId` 由「网关地址 + 用户名」确定性地派生，所以同一
 * 账号在新设备上得到同一个 ID；Agent 列表在导入时不存在则跳过该条。
 */
data class ConversationOrganizationValue(
    val profileId: String,
    val agentId: String,
    val sortMode: String,
    val customOrder: List<String>,
    val folders: List<ConversationFolderValue>,
    val assignments: Map<String, String>,
) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(
        listOf(
            "profileId" to Json.of(profileId),
            "agentId" to Json.of(agentId),
            "sortMode" to Json.of(sortMode),
            "customOrder" to Json.of(customOrder),
            "folders" to JsonValue.JArray(folders.map {
                JsonValue.JObject(listOf("folderId" to Json.of(it.folderId), "name" to Json.of(it.name)))
            }),
            "assignments" to Json.of(assignments),
        ),
    )
}

data class ConversationSection(val organizations: List<ConversationOrganizationValue>) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(
        listOf("organizations" to JsonValue.JArray(organizations.map { it.encode() })),
    )

    fun summary(): String = buildString {
        append("会话列表：${organizations.size} 个账号/Agent 的排序、文件夹与归类")
    }

    companion object {
        const val SORT_UPDATED = "updated-desc"
        const val SORT_CUSTOM = "custom"
        private val SORT_MODES = setOf(SORT_UPDATED, SORT_CUSTOM)

        fun decode(section: JsonValue.JObject): ConversationSection {
            val entries = BackupFields.objects(section, "organizations").orEmpty().map { entry ->
                val profileId = BackupFields.string(entry, "profileId")?.takeIf { it.isNotBlank() }
                    ?: throw formatError("conversation.organizations", "缺少 profileId。")
                val agentId = BackupFields.string(entry, "agentId")?.takeIf { it.isNotBlank() }
                    ?: throw formatError("conversation.organizations", "缺少 agentId。")
                val sortMode = BackupFields.string(entry, "sortMode")
                    ?.also { requireOneOf(it, SORT_MODES, "conversation.sortMode") } ?: SORT_UPDATED
                val customOrder = BackupFields.strings(entry, "customOrder").orEmpty()
                val folders = BackupFields.objects(entry, "folders").orEmpty().map {
                    ConversationFolderValue(
                        BackupFields.string(it, "folderId") ?: throw formatError("conversation.folders", "缺少 folderId。"),
                        BackupFields.string(it, "name") ?: throw formatError("conversation.folders", "缺少 name。"),
                    )
                }
                val assignments = BackupFields.objectOrNull(entry, "assignments")?.let { map ->
                    map.fields.associate { (conversationId, folder) ->
                        conversationId to ((folder as? JsonValue.JString)?.value
                            ?: throw formatError("conversation.assignments", "只能包含字符串。"))
                    }
                }.orEmpty()
                val ids = folders.map { it.folderId }
                if (ids.size != ids.toSet().size || ids.any(String::isBlank)) {
                    throw formatError("conversation.folders", "含空或重复的 folderId。")
                }
                if (folders.any { it.name.isBlank() }) throw formatError("conversation.folders", "含空文件夹名称。")
                if (customOrder.size != customOrder.toSet().size || customOrder.any(String::isBlank)) {
                    throw formatError("conversation.customOrder", "含空或重复的会话 ID。")
                }
                if (assignments.keys.any(String::isBlank)) throw formatError("conversation.assignments", "含空会话 ID。")
                ConversationOrganizationValue(
                    profileId = profileId,
                    agentId = agentId,
                    sortMode = sortMode,
                    customOrder = customOrder,
                    folders = folders,
                    // 指向不存在文件夹的归类是坏数据：剔除它，而不是让整份文件失败。
                    assignments = assignments.filterValues { it in ids.toSet() },
                )
            }
            val keys = entries.map { it.profileId to it.agentId }
            if (keys.size != keys.toSet().size) throw formatError("conversation.organizations", "含重复的账号/Agent 组合。")
            return ConversationSection(entries)
        }
    }
}

data class ConnectionSection(val gatewayUrl: String) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(listOf("gatewayUrl" to Json.of(gatewayUrl)))

    fun summary(): String = "连接地址：$gatewayUrl（只用于登录页预填，不包含账号与凭据）"

    companion object {
        fun decode(section: JsonValue.JObject): ConnectionSection {
            val url = BackupFields.string(section, "gatewayUrl")?.takeIf { it.isNotBlank() }
                ?: throw formatError("connection.gatewayUrl", "缺失。")
            if (GatewayEndpoint.parse(url) == null) {
                throw formatError("connection.gatewayUrl", "「$url」不是合法的 http/https 地址。")
            }
            return ConnectionSection(url)
        }
    }
}

/** 仅记录「账号本地档案 ID → 上次选择的 Agent」；档案不存在时导入会跳过并提示。 */
data class AgentsSection(val selectedByProfile: Map<String, String>) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(listOf("selectedByProfile" to Json.of(selectedByProfile)))

    fun summary(): String = "Agent 选择：记录 ${selectedByProfile.size} 个账号的上次选择"

    companion object {
        fun decode(section: JsonValue.JObject): AgentsSection {
            val selected = BackupFields.objectOrNull(section, "selectedByProfile")?.let { map ->
                map.fields.associate { (profileId, agent) ->
                    if (profileId.isBlank()) throw formatError("agents.selectedByProfile", "含空档案 ID。")
                    val agentId = (agent as? JsonValue.JString)?.value
                        ?: throw formatError("agents.selectedByProfile", "只能包含字符串。")
                    if (agentId.isBlank()) throw formatError("agents.selectedByProfile", "含空 Agent ID。")
                    profileId to agentId
                }
            }.orEmpty()
            return AgentsSection(selected)
        }
    }
}

/**
 * 单条能力策略。刻意不含 `enabled`：导入永远不会打开某能力的总开关，
 * 只替换「授权模式 + 读取范围」，与现有能力授权机制保持一致。
 */
data class CapabilityPolicyValue(
    val mode: CapabilityAuthorizationMode,
    val allowedSources: List<String>,
    val includeContent: Boolean,
    val smsScopeMode: SmsScopeMode,
    val smsKeywords: List<String>,
) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(
        listOf(
            "mode" to Json.of(mode.name),
            "allowedSources" to Json.of(allowedSources),
            "includeContent" to Json.of(includeContent),
            "smsScopeMode" to Json.of(smsScopeMode.name),
            "smsKeywords" to Json.of(smsKeywords),
        ),
    )

    /** 与本机现值合并：总开关 `enabled` 沿用本机，不被文件改写。 */
    fun appliedTo(local: CapabilityPolicy): CapabilityPolicy = CapabilityPolicy(
        enabled = local.enabled,
        mode = mode,
        allowedSources = allowedSources.toSet(),
        includeContent = includeContent,
        smsScopeMode = smsScopeMode,
        smsKeywords = smsKeywords.toSet(),
    )

    companion object {
        fun of(policy: CapabilityPolicy) = CapabilityPolicyValue(
            mode = policy.mode,
            allowedSources = policy.allowedSources.sorted(),
            includeContent = policy.includeContent,
            smsScopeMode = policy.smsScopeMode,
            smsKeywords = policy.smsKeywords.sorted(),
        )

        fun decode(obj: JsonValue.JObject, path: String): CapabilityPolicyValue {
            val mode = BackupFields.string(obj, "mode") ?: throw formatError("$path.mode", "缺失。")
            val scopeMode = BackupFields.string(obj, "smsScopeMode") ?: SmsScopeMode.ALL.name
            return CapabilityPolicyValue(
                mode = CapabilityAuthorizationMode.valueOf(
                    requireOneOf(mode, CapabilityAuthorizationMode.entries.map { it.name }, "$path.mode"),
                ),
                allowedSources = BackupFields.strings(obj, "allowedSources").orEmpty(),
                includeContent = BackupFields.boolean(obj, "includeContent") ?: true,
                smsScopeMode = SmsScopeMode.valueOf(
                    requireOneOf(scopeMode, SmsScopeMode.entries.map { it.name }, "$path.smsScopeMode"),
                ),
                smsKeywords = BackupFields.strings(obj, "smsKeywords").orEmpty(),
            )
        }
    }
}

data class CapabilitiesSection(val policies: Map<String, CapabilityPolicyValue>) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(
        listOf(
            "policies" to JsonValue.JArray(
                policies.entries.sortedBy { it.key }.map { (key, value) ->
                    JsonValue.JObject(listOf("key" to Json.of(key), "policy" to value.encode()))
                },
            ),
        ),
    )

    fun summary(): String = "设备能力：${policies.size} 项的授权模式与读取范围（总开关与系统权限不变）"

    companion object {
        fun decode(section: JsonValue.JObject): CapabilitiesSection {
            val policies = BackupFields.objects(section, "policies").orEmpty().associate { entry ->
                val key = BackupFields.string(entry, "key")?.takeIf { it.isNotBlank() }
                    ?: throw formatError("capabilities.policies", "缺少 key。")
                val policy = BackupFields.objectOrNull(entry, "policy")
                    ?: throw formatError("capabilities.policies[$key]", "缺少 policy。")
                key to CapabilityPolicyValue.decode(policy, "capabilities.policies[$key]")
            }
            return CapabilitiesSection(policies)
        }
    }
}

data class UpdatesSection(val channel: String) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(listOf("channel" to Json.of(channel)))

    fun summary(): String = "应用更新：${if (channel == "STABLE") "正式版" else "预览版"}渠道"

    companion object {
        private val CHANNELS = setOf("STABLE", "PREVIEW")

        fun decode(section: JsonValue.JObject): UpdatesSection {
            val channel = BackupFields.string(section, "channel")?.also { requireOneOf(it, CHANNELS, "updates.channel") }
                ?: throw formatError("updates.channel", "缺失。")
            return UpdatesSection(channel)
        }
    }
}

/** 一份已通过校验的偏好集合；为 null 的节表示「这份文件不涉及」，导入时保持本机现值。 */
data class SettingsBackupSections(
    val appearance: AppearanceSection? = null,
    val notifications: NotificationsSection? = null,
    val messageProcessing: MessageProcessingSection? = null,
    val conversation: ConversationSection? = null,
    val connection: ConnectionSection? = null,
    val agents: AgentsSection? = null,
    val capabilities: CapabilitiesSection? = null,
    val updates: UpdatesSection? = null,
    val notificationCollection: NotificationCollectionSection? = null,
    val devicePush: DevicePushSection? = null,
) {
    fun encode(): Map<String, JsonValue.JObject> = buildMap {
        appearance?.let { put(BackupSection.APPEARANCE.wireName, it.encode()) }
        notifications?.let { put(BackupSection.NOTIFICATIONS.wireName, it.encode()) }
        messageProcessing?.let { put(BackupSection.MESSAGE_PROCESSING.wireName, it.encode()) }
        conversation?.let { put(BackupSection.CONVERSATION.wireName, it.encode()) }
        connection?.let { put(BackupSection.CONNECTION.wireName, it.encode()) }
        agents?.let { put(BackupSection.AGENTS.wireName, it.encode()) }
        capabilities?.let { put(BackupSection.CAPABILITIES.wireName, it.encode()) }
        updates?.let { put(BackupSection.UPDATES.wireName, it.encode()) }
        notificationCollection?.let { put(BackupSection.NOTIFICATION_COLLECTION.wireName, it.encode()) }
        devicePush?.let { put(BackupSection.DEVICE_PUSH.wireName, it.encode()) }
    }

    val isEmpty: Boolean get() = encode().isEmpty()

    companion object {
        /** 解析所有已知节；未知节已由 [SettingsBackupCodec.decode] 记入诊断，这里静默跳过。 */
        fun decode(document: SettingsBackupDocument, allowedPalettes: Set<String> = emptySet()): SettingsBackupSections =
            SettingsBackupSections(
                appearance = document.sections[BackupSection.APPEARANCE.wireName]
                    ?.let { AppearanceSection.decode(it, allowedPalettes) },
                notifications = document.sections[BackupSection.NOTIFICATIONS.wireName]
                    ?.let(NotificationsSection::decode),
                messageProcessing = document.sections[BackupSection.MESSAGE_PROCESSING.wireName]
                    ?.let(MessageProcessingSection::decode),
                conversation = document.sections[BackupSection.CONVERSATION.wireName]
                    ?.let(ConversationSection::decode),
                connection = document.sections[BackupSection.CONNECTION.wireName]
                    ?.let(ConnectionSection::decode),
                agents = document.sections[BackupSection.AGENTS.wireName]?.let(AgentsSection::decode),
                capabilities = document.sections[BackupSection.CAPABILITIES.wireName]
                    ?.let(CapabilitiesSection::decode),
                updates = document.sections[BackupSection.UPDATES.wireName]?.let(UpdatesSection::decode),
                notificationCollection = document.sections[BackupSection.NOTIFICATION_COLLECTION.wireName]
                    ?.let(NotificationCollectionSection::decode),
                devicePush = document.sections[BackupSection.DEVICE_PUSH.wireName]?.let(DevicePushSection::decode),
            )
    }
}

/** 各节接受的字段闭集；用于把未知字段记进诊断，而不是悄悄丢弃。 */
internal object BackupFieldSets {
    val APPEARANCE = setOf("theme", "dynamicColor", "dynamicIconColor", "palette", "reduceMotion")
    val NOTIFICATIONS = setOf("replyAlertsEnabled")
    // 新文件只写 `delayMillis`；`global`/`cores` 属于旧版本导出的形状，读取时兼容，
    // 因此不算「本版本不认识的字段」。
    val MESSAGE_PROCESSING = setOf("delayMillis", "global", "cores")
    val CONVERSATION = setOf("organizations")
    val NOTIFICATION_COLLECTION = setOf("packageIds", "fieldAccess", "mode")
    val DEVICE_PUSH = setOf("scope", "retentionHours", "maxEvents", "maxQueueBytes")
    val CONNECTION = setOf("gatewayUrl")
    val AGENTS = setOf("selectedByProfile")
    val CAPABILITIES = setOf("policies")
    val UPDATES = setOf("channel")

    val SECTIONS: Map<String, Set<String>> = mapOf(
        BackupSection.APPEARANCE.wireName to APPEARANCE,
        BackupSection.NOTIFICATIONS.wireName to NOTIFICATIONS,
        BackupSection.MESSAGE_PROCESSING.wireName to MESSAGE_PROCESSING,
        BackupSection.CONVERSATION.wireName to CONVERSATION,
        BackupSection.CONNECTION.wireName to CONNECTION,
        BackupSection.AGENTS.wireName to AGENTS,
        BackupSection.CAPABILITIES.wireName to CAPABILITIES,
        BackupSection.UPDATES.wireName to UPDATES,
        BackupSection.NOTIFICATION_COLLECTION.wireName to NOTIFICATION_COLLECTION,
        BackupSection.DEVICE_PUSH.wireName to DEVICE_PUSH,
    )
}

data class NotificationCollectionSection(
    val packageIds: List<String>,
    val fieldAccess: String,
    val mode: String,
) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(
        listOf(
            "packageIds" to Json.of(packageIds),
            "fieldAccess" to Json.of(fieldAccess),
            "mode" to Json.of(mode),
        ),
    )

    fun summary(): String = buildString {
        append("通知采集范围：").append(packageIds.size).append(" 个允许读取的应用，")
        append(if (fieldAccess == "CONTENT") "含正文" else "仅元数据").append("，")
        append(if (mode == "AUTO_SEND") "自动发送" else "按需查询")
        append("（读取授权仍需在本机确认）")
    }

    companion object {
        private val FIELD_ACCESS = setOf("METADATA", "CONTENT")
        private val MODES = setOf("ON_DEMAND", "AUTO_SEND")

        fun decode(section: JsonValue.JObject): NotificationCollectionSection = NotificationCollectionSection(
            packageIds = BackupFields.strings(section, "packageIds").orEmpty(),
            fieldAccess = requireOneOf(
                BackupFields.string(section, "fieldAccess") ?: "METADATA", FIELD_ACCESS, "notificationCollection.fieldAccess",
            ),
            mode = requireOneOf(
                BackupFields.string(section, "mode") ?: "ON_DEMAND", MODES, "notificationCollection.mode",
            ),
        )
    }
}

data class PushScopeValue(
    val packageIds: List<String>,
    val senderAddresses: List<String>,
    val smsScopeMode: String,
    val smsKeywords: List<String>,
    val notificationContent: Boolean,
    val smsContent: Boolean,
) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(
        listOf(
            "packageIds" to Json.of(packageIds),
            "senderAddresses" to Json.of(senderAddresses),
            "smsScopeMode" to Json.of(smsScopeMode),
            "smsKeywords" to Json.of(smsKeywords),
            "notificationContent" to Json.of(notificationContent),
            "smsContent" to Json.of(smsContent),
        ),
    )

    fun summary(): String = "主动推送范围：${packageIds.size} 个通知来源、${senderAddresses.size} 个短信号码，" +
        "开关状态与登录凭据不在备份内"

    companion object {
        fun decode(section: JsonValue.JObject): PushScopeValue = PushScopeValue(
            packageIds = BackupFields.strings(section, "packageIds").orEmpty(),
            senderAddresses = BackupFields.strings(section, "senderAddresses").orEmpty(),
            smsScopeMode = requireOneOf(
                BackupFields.string(section, "smsScopeMode") ?: SmsScopeMode.ALL.name,
                SmsScopeMode.entries.map { it.name }, "devicePush.smsScopeMode",
            ),
            smsKeywords = BackupFields.strings(section, "smsKeywords").orEmpty(),
            notificationContent = BackupFields.boolean(section, "notificationContent") ?: true,
            smsContent = BackupFields.boolean(section, "smsContent") ?: true,
        )
    }
}

data class DevicePushSection(
    val scope: PushScopeValue,
    val retentionHours: Int,
    val maxEvents: Int,
    val maxQueueBytes: Int,
) {
    fun encode(): JsonValue.JObject = JsonValue.JObject(
        listOf(
            "scope" to scope.encode(),
            "retentionHours" to Json.of(retentionHours),
            "maxEvents" to Json.of(maxEvents),
            "maxQueueBytes" to Json.of(maxQueueBytes),
        ),
    )

    fun summary(): String = scope.summary() + "；保留 $retentionHours 小时，上限 $maxEvents 条"

    companion object {
        /** 与 `PushConfiguration` 的 `require` 完全一致：越界值在导入前就拒绝，不让它走到写入阶段。 */
        private const val MIN_RETENTION = 1
        private const val MAX_RETENTION = 168
        private const val MIN_EVENTS = 1
        private const val MAX_EVENTS = 4_096
        private const val MIN_QUEUE_BYTES = 4_096
        private const val MAX_QUEUE_BYTES = 8 * 1_024 * 1_024

        fun decode(section: JsonValue.JObject): DevicePushSection {
            val scope = BackupFields.objectOrNull(section, "scope")?.let(PushScopeValue::decode)
                ?: throw formatError("devicePush.scope", "缺失。")
            val retention = BackupFields.int(section, "retentionHours") ?: 72
            if (retention !in MIN_RETENTION..MAX_RETENTION) {
                throw formatError("devicePush.retentionHours", "超出 $MIN_RETENTION–$MAX_RETENTION 小时。")
            }
            val events = BackupFields.int(section, "maxEvents") ?: 1_000
            if (events !in MIN_EVENTS..MAX_EVENTS) {
                throw formatError("devicePush.maxEvents", "超出 $MIN_EVENTS–$MAX_EVENTS 条。")
            }
            val bytes = BackupFields.int(section, "maxQueueBytes") ?: 4 * 1_024 * 1_024
            if (bytes !in MIN_QUEUE_BYTES..MAX_QUEUE_BYTES) {
                throw formatError("devicePush.maxQueueBytes", "超出允许的队列容量。")
            }
            return DevicePushSection(scope, retention, events, bytes)
        }
    }
}
