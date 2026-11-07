package com.openandroidintelligence.mobile.settings.backup

import android.content.Context
import com.openandroidintelligence.conversation.batch.DebounceMode
import com.openandroidintelligence.conversation.ports.ConversationFolder
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSortMode
import com.openandroidintelligence.conversation.theme.ThemePalette
import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.account.AccountProfile
import com.openandroidintelligence.mobile.AndroidAccountProfileStore
import com.openandroidintelligence.mobile.AppearancePreferences
import com.openandroidintelligence.mobile.AppearanceSettings
import com.openandroidintelligence.mobile.LoginGatewayPrefill
import com.openandroidintelligence.mobile.ReplyNotificationSettings
import com.openandroidintelligence.mobile.ThemePreference
import com.openandroidintelligence.mobile.conversations.DebouncePreferences
import com.openandroidintelligence.mobile.conversations.EncryptedNativeConversationOrganizationStore
import com.openandroidintelligence.mobile.conversations.NativeLocalPartitions
import com.openandroidintelligence.mobile.plugins.ProductionPluginHost
import com.openandroidintelligence.mobile.push.DevicePushRuntime
import com.openandroidintelligence.mobile.push.PushConfiguration
import com.openandroidintelligence.mobile.updates.AppUpdateChannel
import com.openandroidintelligence.mobile.updates.AppUpdatePreferences
import com.openandroidintelligence.mobile.updates.installedAppBuild
import com.openandroidintelligence.notification.control.NotificationPolicyPort
import com.openandroidintelligence.notification.control.NotificationPolicySnapshot
import com.openandroidintelligence.notification.control.NotificationPolicyUpdate
import com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 导出文件名：`oai-settings-YYYYMMDD.json`。
 *
 * 保存器的默认文件名必须与真正写出的文件一致，所以只在 [SettingsBackupFileNames] 定义一次；
 * 界面在启动保存器之前也用它，避免两处日期格式各写各的。
 */
object SettingsBackupFileNames {
    fun suggested(clock: Clock = Clock.systemDefaultZone()): String =
        "oai-settings-${LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)}.json"
}

/** 导出结果：文件名与内容分开，便于直接交给系统文件保存器。 */
data class BackupExport(val suggestedFileName: String, val json: String)

/** 导入结果：只报告真实发生的写入与真实的跳过，不把「没做」包装成「成功」。 */
data class BackupImportOutcome(
    val appliedSummaries: List<String>,
    val skipped: List<String>,
    val ignoredFields: List<String>,
    /** 确实无法热更新的项目，需要用户下一步操作才能看到效果。 */
    val deferredNotices: List<String>,
    val exportedAt: String,
    val appVersionName: String,
)

/**
 * 设置备份与恢复的唯一实现。
 *
 * 它只经由各设置**原有权威**读写，不新建第二套存储：
 * 外观 → [AppearancePreferences]，通知提醒 → [ReplyNotificationSettings]，
 * 消息合并 → [DebouncePreferences]，会话组织 → 加密的会话组织存储，
 * 能力策略 → [PairingGrantStateHolder]，更新渠道 → [AppUpdatePreferences]，
 * 通知采集范围 → 现有的 [NotificationPolicyPort]，主动推送 → [DevicePushRuntime]。
 *
 * 导入采用「先快照、失败整体回滚」：任一步抛错就把已写入的项目恢复为快照值，
 * 并把失败如实返回。写入顺序固定，且每一步都先比较现值——重复导入同一份文件
 * 不会再产生任何写入（通知策略 revision 也不会被无谓推进）。
 *
 * 安全性由数据模型保证：文件里不存在凭据、主密钥、安装标识、配对原语授权、
 * 系统权限或临时批准字段，导入路径没有任何代码可以「恢复」它们；能力总开关与
 * 通知读取授权一律沿用本机现值。
 */
class SettingsBackupCoordinator(
    private val context: Context,
    private val appearance: AppearancePreferences,
    private val profiles: AndroidAccountProfileStore,
    private val grants: PairingGrantStateHolder,
    private val pluginHost: ProductionPluginHost?,
    private val replyNotifications: ReplyNotificationSettings?,
    private val runtime: com.openandroidintelligence.mobile.GatewayRuntime?,
    private val devicePush: DevicePushRuntime?,
    private val appUpdates: AppUpdatePreferences,
    private val notificationPolicy: NotificationPolicyPort,
    private val keyProvider: AesGcmKeyProvider? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    // ---------------------------------------------------------------- 导出

    fun export(): BackupExport {
        val installed = installedAppBuild(context)
        val json = SettingsBackupCodec.encode(
            sections = collectSections().encode(),
            appVersionName = installed.versionName.ifBlank { "unknown" },
            appVersionCode = installed.versionCode,
            exportedAt = Instant.now(clock).toString(),
        )
        return BackupExport(SettingsBackupFileNames.suggested(clock), json)
    }

    /** 导出前的摘要；与 [export] 使用同一个收集函数，不会出现「说的」和「写的」不一致。 */
    fun exportSummary(): List<String> = collectSections().summaries()

    private fun collectSections(): SettingsBackupSections = SettingsBackupSections(
        appearance = currentAppearance(),
        notifications = replyNotifications?.let { NotificationsSection(it.enabled.value) },
        messageProcessing = currentMessageProcessing(),
        conversation = currentOrganizations(),
        connection = currentConnection(),
        agents = currentAgents(),
        capabilities = currentCapabilities(),
        updates = UpdatesSection(appUpdates.readChannel().name),
        notificationCollection = currentNotificationCollection(),
        devicePush = currentDevicePush(),
    )

    private fun currentAppearance(): AppearanceSection {
        val settings = appearance.settings.value
        return AppearanceSection(
            theme = ThemeValue.valueOf(settings.theme.name),
            dynamicColor = settings.dynamicColor,
            dynamicIconColor = settings.dynamicIconColor,
            palette = settings.palette.name,
            reduceMotion = settings.reduceMotion,
        )
    }

    private fun currentMessageProcessing(): MessageProcessingSection {
        val preferences = DebouncePreferences(context)
        val global = preferences.read(null).toBackupValue()
        val cores = knownGatewayUrls().filter(preferences::hasOverride).associate { url ->
            debounceKey(url) to preferences.read(url).toBackupValue()
        }
        return MessageProcessingSection(global, cores)
    }

    private fun currentOrganizations(): ConversationSection? {
        val installId = installId() ?: return null
        val entries = buildList {
            profiles.list().forEach { profile ->
                val scope = scopeFor(profile, installId) ?: return@forEach
                partitionAgents(scope).forEach { agentId ->
                    val organization = readOrganization(scope, agentId) ?: return@forEach
                    if (organization.isDefault()) return@forEach
                    add(
                        ConversationOrganizationValue(
                            profileId = backupProfileKey(profile.localProfileId),
                            agentId = agentId,
                            sortMode = organization.sortMode.wireValue,
                            customOrder = organization.customOrder,
                            folders = organization.folders.map { ConversationFolderValue(it.folderId, it.name) },
                            assignments = organization.assignments,
                        ),
                    )
                }
            }
        }
        return entries.takeIf { it.isNotEmpty() }?.let(::ConversationSection)
    }

    private fun currentConnection(): ConnectionSection? =
        LoginGatewayPrefill.read(context)?.let(::ConnectionSection)

    private fun currentAgents(): AgentsSection? {
        val selected = profiles.list().mapNotNull { profile ->
            val agent = profile.selectedAgentId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            backupProfileKey(profile.localProfileId) to agent
        }.toMap()
        return selected.takeIf { it.isNotEmpty() }?.let(::AgentsSection)
    }

    private fun currentCapabilities(): CapabilitiesSection? {
        val state = grants.state.value ?: return null
        // 授权状态损坏时如实不导出：此时写出的策略不代表任何真实生效的配置。
        if (state.localError != null) return null
        val installed = pluginHost?.verifiedCapabilityPolicyKeys().orEmpty()
        val policies = state.capabilityPolicies
            .filterKeys { it in installed }
            .mapValues { CapabilityPolicyValue.of(it.value) }
        return policies.takeIf { it.isNotEmpty() }?.let(::CapabilitiesSection)
    }

    private fun currentNotificationCollection(): NotificationCollectionSection? {
        val snapshot = runCatching { notificationPolicy.snapshot() }.getOrNull() ?: return null
        if (!snapshot.installed) return null
        return NotificationCollectionSection(
            packageIds = snapshot.packageIds.sorted(),
            fieldAccess = snapshot.fieldAccess.name,
            mode = snapshot.mode.name,
        )
    }

    private fun currentDevicePush(): DevicePushSection? {
        val configuration = runCatching { devicePush?.snapshot()?.configuration }.getOrNull() ?: return null
        return DevicePushSection(
            scope = PushScopeValue(
                packageIds = configuration.notifications.scope.packageIds.sorted(),
                senderAddresses = configuration.sms.scope.senderAddresses.sorted(),
                smsScopeMode = configuration.sms.scope.mode.name,
                smsKeywords = configuration.sms.scope.keywords.sorted(),
                notificationContent = configuration.notifications.scope.includeContent,
                smsContent = configuration.sms.scope.includeContent,
            ),
            retentionHours = configuration.retentionHours,
            maxEvents = configuration.maxEvents,
            maxQueueBytes = configuration.maxQueueBytes,
        )
    }

    data class Preview(
        val summaries: List<String>,
        val ignoredFields: List<String>,
        val exportedAt: String,
        val appVersionName: String,
    )

    /** 校验文件并给出确认框所需的全部事实；不写入任何设置。 */
    fun preview(text: String): Preview {
        val document = SettingsBackupCodec.decode(text)
        val sections = SettingsBackupSections.decode(document, ThemePalette.entries.map { it.name }.toSet())
        if (sections.isEmpty) throw SettingsBackupFormatException("备份文件中没有可恢复的设置。")
        return Preview(
            summaries = sections.summaries(),
            ignoredFields = document.ignoredFields,
            exportedAt = document.exportedAt,
            appVersionName = document.appVersionName,
        )
    }

    // ---------------------------------------------------------------- 导入

    /**
     * 校验并导入一份备份文本。
     *
     * 失败时本机设置已回滚到导入前，返回的异常消息直接可展示给用户。
     */
    fun import(text: String): Result<BackupImportOutcome> {
        val document = try {
            SettingsBackupCodec.decode(text)
        } catch (failure: SettingsBackupFormatException) {
            return Result.failure(failure)
        }
        val sections = try {
            SettingsBackupSections.decode(document, ThemePalette.entries.map { it.name }.toSet())
        } catch (failure: SettingsBackupFormatException) {
            return Result.failure(failure)
        }
        if (sections.isEmpty) {
            return Result.failure(SettingsBackupFormatException("备份文件中没有可恢复的设置。"))
        }

        val snapshot = captureSnapshot()
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val deferred = mutableListOf<String>()
        try {
            applyAppearance(sections.appearance, applied)
            applyNotifications(sections.notifications, applied, skipped)
            applyMessageProcessing(sections.messageProcessing, applied, skipped, deferred)
            applyConversation(sections.conversation, applied, skipped, deferred)
            applyConnection(sections.connection, applied, deferred)
            applyAgents(sections.agents, applied, skipped)
            applyUpdates(sections.updates, applied)
            applyNotificationCollection(sections.notificationCollection, applied, skipped)
            applyDevicePush(sections.devicePush, applied, skipped)
            applyCapabilities(sections.capabilities, applied, skipped)
        } catch (failure: Exception) {
            val restored = runCatching { snapshot.restore() }.isSuccess
            return Result.failure(
                SettingsBackupFormatException(
                    if (restored) "导入失败，本机设置已恢复为导入前的状态。${readableFailure(failure)}"
                    else "导入失败，且部分设置可能未恢复，请到各设置页核对。",
                ),
            )
        }
        return Result.success(
            BackupImportOutcome(
                appliedSummaries = applied,
                skipped = skipped,
                ignoredFields = document.ignoredFields,
                deferredNotices = deferred.distinct(),
                exportedAt = document.exportedAt,
                appVersionName = document.appVersionName,
            ),
        )
    }

    private fun applyAppearance(section: AppearanceSection?, applied: MutableList<String>) {
        section ?: return
        val next = AppearanceSettings(
            theme = ThemePreference.valueOf(section.theme.name),
            dynamicColor = section.dynamicColor,
            dynamicIconColor = section.dynamicIconColor,
            palette = ThemePalette.valueOf(section.palette),
            reduceMotion = section.reduceMotion,
        )
        val current = appearance.settings.value
        if (next == current) {
            applied += section.summary() + "（本机已是该值）"
            return
        }
        appearance.applySettings(next)
        applied += section.summary()
    }

    private fun applyNotifications(
        section: NotificationsSection?,
        applied: MutableList<String>,
        skipped: MutableList<String>,
    ) {
        section ?: return
        val settings = replyNotifications ?: run {
            // 没有运行时就没有「任务通知」这个开关可写；如实说明，不伪造一条写入记录。
            skipped += "通知提醒偏好未导入：当前版本未接入 Agent 任务通知。"
            return
        }
        if (settings.enabled.value == section.replyAlertsEnabled) {
            applied += section.summary() + "（本机已是该值）"
            return
        }
        if (!settings.setEnabled(section.replyAlertsEnabled)) {
            throw IllegalStateException("NOTIFICATION_PREFERENCE_PERSISTENCE_FAILED")
        }
        applied += section.summary()
    }

    private fun applyMessageProcessing(
        section: MessageProcessingSection?,
        applied: MutableList<String>,
        skipped: MutableList<String>,
        deferred: MutableList<String>,
    ) {
        section ?: return
        val preferences = DebouncePreferences(context)
        var written = 0
        section.global?.let { value ->
            if (preferences.read(null).toBackupValue() != value) {
                preferences.write(null, value.delayMillis, value.extendWindow)
                written++
            }
        }
        val gateways = knownGatewayUrls().associateBy { debounceKey(it) }
        var unresolved = 0
        section.cores.forEach { (key, value) ->
            val url = gateways[key]
            if (url == null) { unresolved++; return@forEach }
            if (preferences.read(url).toBackupValue() != value || !preferences.hasOverride(url)) {
                preferences.write(url, value.delayMillis, value.extendWindow)
                written++
            }
        }
        if (written > 0) {
            applied += section.summary()
            runtime?.reloadDebounceSettings()
            deferred += "消息合并已保存；当前会话在下次发送时生效。"
        } else {
            applied += section.summary() + "（本机已是该值）"
        }
        if (unresolved > 0) {
            skipped += "$unresolved 个 Core 的消息合并覆盖在本机没有对应地址，已跳过；连接该 Core 后可重新设置。"
        }
    }

    private fun applyConversation(
        section: ConversationSection?,
        applied: MutableList<String>,
        skipped: MutableList<String>,
        deferred: MutableList<String>,
    ) {
        section ?: return
        if (section.organizations.isEmpty()) return
        val installId = installId() ?: run {
            skipped += "会话排序未导入：本机安装标识尚未生成。"
            return
        }
        val localProfiles = profiles.list().associateBy { backupProfileKey(it.localProfileId) }
        var written = 0
        var missingProfile = 0
        var missingAgent = 0
        section.organizations.forEach { entry ->
            val profile = localProfiles[entry.profileId]
            val scope = profile?.let { scopeFor(it, installId) }
            if (scope == null) { missingProfile++; return@forEach }
            if (entry.agentId !in partitionAgents(scope)) { missingAgent++; return@forEach }
            val store = organizationStore(scope)
            val current = store.read(entry.agentId)
            val next = ConversationOrganization(
                revision = current.revision,
                sortMode = ConversationSortMode.entries.first { it.wireValue == entry.sortMode },
                customOrder = entry.customOrder,
                folders = entry.folders.map { ConversationFolder(it.folderId, it.name) },
                assignments = entry.assignments,
            )
            if (next == current) return@forEach
            store.update(entry.agentId, current.revision, next)
            written++
        }
        if (written > 0) {
            applied += section.summary()
            runtime?.controller?.value?.refreshThreads()
            deferred += "会话排序与文件夹已保存；若列表未立即变化，重新进入会话列表即可。"
        }
        if (missingProfile > 0) skipped += "$missingProfile 条会话排序未导入：本机还没有对应账号。"
        if (missingAgent > 0) skipped += "$missingAgent 条会话排序未导入：本机还没有对应 Agent。"
    }

    private fun applyConnection(
        section: ConnectionSection?,
        applied: MutableList<String>,
        deferred: MutableList<String>,
    ) {
        section ?: return
        if (LoginGatewayPrefill.read(context) != section.gatewayUrl) {
            if (!LoginGatewayPrefill.write(context, section.gatewayUrl)) {
                throw IllegalStateException("PREFERRED_GATEWAY_PERSISTENCE_FAILED")
            }
            applied += section.summary()
            deferred += "连接地址已保存为登录页默认值；已登录的账号不受影响。"
        } else {
            applied += section.summary() + "（本机已是该值）"
        }
    }

    private fun applyAgents(
        section: AgentsSection?,
        applied: MutableList<String>,
        skipped: MutableList<String>,
    ) {
        section ?: return
        if (section.selectedByProfile.isEmpty()) return
        val local = profiles.list().associateBy { backupProfileKey(it.localProfileId) }
        var written = 0
        var missing = 0
        section.selectedByProfile.forEach { (key, agentId) ->
            val profile = local[key] ?: run { missing++; return@forEach }
            if (profile.selectedAgentId == agentId) return@forEach
            profiles.saveSelectedAgent(profile.localProfileId, agentId)
            written++
        }
        if (written > 0) {
            applied += section.summary()
            applied += "Agent 选择将在重新连接后生效。"
        }
        if (missing > 0) skipped += "$missing 个账号的 Agent 选择未导入：本机还没有对应账号。"
    }

    private fun applyUpdates(section: UpdatesSection?, applied: MutableList<String>) {
        section ?: return
        val channel = AppUpdateChannel.valueOf(section.channel)
        if (appUpdates.readChannel() == channel) {
            applied += section.summary() + "（本机已是该值）"
            return
        }
        if (!appUpdates.writeChannel(channel)) throw IllegalStateException("UPDATE_CHANNEL_PERSISTENCE_FAILED")
        applied += section.summary()
    }

    private fun applyNotificationCollection(
        section: NotificationCollectionSection?,
        applied: MutableList<String>,
        skipped: MutableList<String>,
    ) {
        section ?: return
        val current = runCatching { notificationPolicy.snapshot() }.getOrNull()
        if (current == null || !current.installed) {
            skipped += "通知采集范围未导入：当前版本未装配通知采集。"
            return
        }
        val fieldAccess = NotificationFieldAccess.valueOf(section.fieldAccess)
        val mode = NotificationDeliveryMode.valueOf(section.mode)
        if (current.packageIds.sorted() == section.packageIds.sorted() &&
            current.fieldAccess == fieldAccess && current.mode == mode
        ) {
            applied += section.summary() + "（本机已是该值）"
            return
        }
        val outcome = notificationPolicy.update(
            NotificationPolicyUpdate(
                expectedRevision = current.revision,
                packageIds = section.packageIds,
                fieldAccess = fieldAccess,
                mode = mode,
                // granted 传 null：读取授权只能由用户在本机确认，绝不因导入而改变。
                granted = null,
            ),
        )
        when (outcome) {
            is NotificationPolicyUpdateOutcome.Accepted -> applied += section.summary()
            is NotificationPolicyUpdateOutcome.Rejected -> throw IllegalStateException(
                if (outcome.reason == "REVISION_STALE") "通知采集范围保存时被其他操作修改，请重试。"
                else "通知采集范围保存失败（${outcome.reason}）。",
            )
        }
    }

    private fun applyDevicePush(
        section: DevicePushSection?,
        applied: MutableList<String>,
        skipped: MutableList<String>,
    ) {
        section ?: return
        val push = devicePush ?: run {
            skipped += "主动推送范围未导入：本机加密队列不可用。"
            return
        }
        val current = runCatching { push.snapshot() }.getOrNull() ?: run {
            skipped += "主动推送范围未导入：无法读取本机推送配置。"
            return
        }
        val configuration = current.configuration
        val next = configuration.importing(section)
        if (next == configuration) {
            applied += section.summary() + "（本机已是该值）"
            return
        }
        try {
            push.configure(next, expectedConfiguration = configuration)
        } catch (_: IllegalArgumentException) {
            // 触发条件只有一种：新容量小于当前排队量。换句用户能照做的话，而不是抛原文。
            throw IllegalStateException("推送容量不能小于当前排队的事件数，请先清理队列或调大上限。")
        }
        applied += section.summary()
    }

    private fun applyCapabilities(
        section: CapabilitiesSection?,
        applied: MutableList<String>,
        skipped: MutableList<String>,
    ) {
        section ?: return
        if (section.policies.isEmpty()) return
        val state = grants.state.value ?: run {
            skipped += "设备能力策略未导入：本机授权状态不可用。"
            return
        }
        if (state.localError != null) {
            skipped += "设备能力策略未导入：本机授权状态异常，请重启应用后重试。"
            return
        }
        val installed = pluginHost?.verifiedCapabilityPolicyKeys().orEmpty()
        if (installed.isEmpty()) {
            skipped += "设备能力策略未导入：当前没有已安装的能力可对应。"
            return
        }
        val configured = state.capabilityPolicies
        val next = mutableMapOf<String, com.openandroidintelligence.kernel.CapabilityPolicy>()
        section.policies.forEach { (key, value) ->
            // 只写回本机真实存在的能力；文件里多出来的键不创建任何条目，也不会打开总开关。
            if (key !in installed) return@forEach
            val local = configured[key] ?: com.openandroidintelligence.kernel.CapabilityPolicy(enabled = false)
            next[key] = value.appliedTo(local)
        }
        val changed = next.filter { (key, value) -> configured[key] != value }
        if (changed.isEmpty()) {
            applied += section.summary() + "（本机已是该值）"
            return
        }
        grants.updateCapabilityPolicies(changed)
        applied += "设备能力：${changed.size} 项的授权模式与读取范围（总开关保持本机值）"
    }

    // ---------------------------------------------------------------- 快照与回滚

    private inner class Snapshot(
        val appearance: AppearanceSettings,
        val notificationsEnabled: Boolean?,
        val debounce: Map<String, Any?>,
        val preferredGateway: String?,
        val agents: Map<String, String?>,
        val updates: AppUpdateChannel,
        val notificationCollection: NotificationPolicySnapshot?,
        val devicePush: PushConfiguration?,
        val capabilityPolicies: Map<String, com.openandroidintelligence.kernel.CapabilityPolicy>,
        val organizations: List<OrganizationSnapshot>,
    ) {
        fun restore() {
            appearance.let { previous ->
                if (this@SettingsBackupCoordinator.appearance.settings.value != previous) {
                    this@SettingsBackupCoordinator.appearance.applySettings(previous)
                }
            }
            notificationsEnabled?.let { enabled ->
                val settings = replyNotifications
                if (settings != null && settings.enabled.value != enabled) settings.setEnabled(enabled)
            }
            DebouncePreferences(context).restore(debounce)
            // 运行时手里还留着导入期间装载的批处理策略：回滚后必须按恢复值重新装载，
            // 否则「已恢复」只对下次启动成立，当前会话仍在用被导入的值。
            runtime?.reloadDebounceSettings()
            if (preferredGateway == null) LoginGatewayPrefill.clear(context)
            else LoginGatewayPrefill.write(context, preferredGateway)
            agents.forEach { (profileId, agentId) ->
                runCatching { profiles.saveSelectedAgent(profileId, agentId) }
            }
            if (appUpdates.readChannel() != updates) appUpdates.writeChannel(updates)
            notificationCollection?.let { previous ->
                runCatching {
                    notificationPolicy.update(
                        NotificationPolicyUpdate(
                            expectedRevision = notificationPolicy.snapshot().revision,
                            packageIds = previous.packageIds,
                            fieldAccess = previous.fieldAccess,
                            mode = previous.mode,
                        ),
                    )
                }
            }
            val previousPush = devicePush
            val pushRuntime = this@SettingsBackupCoordinator.devicePush
            if (previousPush != null && pushRuntime != null) {
                runCatching { pushRuntime.configure(previousPush) }
            }
            if (runCatching { grants.state.value?.capabilityPolicies }.getOrNull() != capabilityPolicies) {
                runCatching { grants.replaceCapabilityPolicies(capabilityPolicies) }
            }
            organizations.forEach { previous ->
                runCatching {
                    val store = organizationStore(previous.scope)
                    val current = store.read(previous.agentId)
                    if (current != previous.organization) {
                        store.update(previous.agentId, current.revision, previous.organization)
                    }
                }
            }
            if (runtime?.controller?.value != null) runtime.controller.value?.refreshThreads()
        }
    }

    private data class OrganizationSnapshot(
        val scope: ConversationScope,
        val agentId: String,
        val organization: ConversationOrganization,
    )

    private fun captureSnapshot(): Snapshot = Snapshot(
        appearance = appearance.settings.value,
        notificationsEnabled = replyNotifications?.enabled?.value,
        debounce = DebouncePreferences(context).snapshot(),
        preferredGateway = LoginGatewayPrefill.read(context),
        agents = profiles.list().associate { it.localProfileId to it.selectedAgentId },
        updates = appUpdates.readChannel(),
        notificationCollection = runCatching { notificationPolicy.snapshot() }.getOrNull(),
        devicePush = runCatching { devicePush?.snapshot()?.configuration }.getOrNull(),
        capabilityPolicies = grants.state.value?.capabilityPolicies.orEmpty(),
        organizations = captureOrganizations(),
    )

    private fun captureOrganizations(): List<OrganizationSnapshot> {
        val installId = installId() ?: return emptyList()
        return buildList {
            profiles.list().forEach { profile ->
                val scope = scopeFor(profile, installId) ?: return@forEach
                partitionAgents(scope).forEach { agentId ->
                    readOrganization(scope, agentId)?.let { add(OrganizationSnapshot(scope, agentId, it)) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 基础设施

    /**
     * 安装标识：会话组织存储的加密范围依赖它。
     *
     * 还没生成时返回 null，让会话组织相关节整体跳过；绝不编一个占位值——
     * 那样读写的会是另一份加密目录，导出与导入都会静默失效。
     */
    private fun installId(): String? {
        val prefs = context.applicationContext.getSharedPreferences(RUNTIME_PREFERENCES, Context.MODE_PRIVATE)
        return prefs.getString(KEY_INSTALL, null)?.takeIf { it.isNotBlank() }
    }

    private fun scopeFor(profile: AccountProfile, installId: String): ConversationScope? {
        val accountId = profiles.binding(profile.localProfileId)?.accountId ?: return null
        return ConversationScope(profile.localProfileId, profile.gatewayBaseUrl, accountId, installId)
    }

    private fun organizationStore(scope: ConversationScope) =
        EncryptedNativeConversationOrganizationStore(context, scope, keyProvider)

    private fun readOrganization(scope: ConversationScope, agentId: String): ConversationOrganization? =
        runCatching { organizationStore(scope).read(agentId) }.getOrNull()

    private fun partitionAgents(scope: ConversationScope): Set<String> =
        runCatching { NativeLocalPartitions(context, scope, keyProvider).agents() }.getOrDefault(emptySet())

    /** 本机已知的 Core 地址：已保存账号的地址，去重排序。 */
    private fun knownGatewayUrls(): List<String> = profiles.list()
        .map { it.gatewayBaseUrl }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

    private companion object {
        const val RUNTIME_PREFERENCES = "open_android_intelligence_runtime"
        const val KEY_INSTALL = "installation_id"
    }
}

/** 「网关地址 → 合并偏好存储键」的摘要算法，与 [DebouncePreferences] 内部按键一致。 */
internal fun debounceKey(gatewayUrl: String): String =
    MessageDigest.getInstance("SHA-256").digest(gatewayUrl.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** 档案 ID 在备份文件里只以 SHA-256 出现：文件无法反推出账号名。 */
internal fun backupProfileKey(profileId: String): String =
    MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun com.openandroidintelligence.conversation.batch.DebouncePolicy.toBackupValue(): DebounceValue =
    DebounceValue(delay.inWholeMilliseconds.toInt(), mode == DebounceMode.EXTEND_WINDOW)

private fun ConversationOrganization.isDefault(): Boolean =
    revision == 0L && sortMode == ConversationSortMode.UPDATED_DESC &&
        customOrder.isEmpty() && folders.isEmpty() && assignments.isEmpty()

private fun PushConfiguration.importing(section: DevicePushSection): PushConfiguration = copy(
    // 主动推送的开关与登录状态保持本机值：只迁移范围与容量策略。
    notifications = notifications.copy(
        scope = notifications.scope.copy(
            packageIds = section.scope.packageIds.toSet(),
            includeContent = section.scope.notificationContent,
        ),
    ),
    sms = sms.copy(
        scope = sms.scope.copy(
            senderAddresses = section.scope.senderAddresses.toSet(),
            mode = com.openandroidintelligence.kernel.SmsScopeMode.valueOf(section.scope.smsScopeMode),
            keywords = section.scope.smsKeywords.toSet(),
            includeContent = section.scope.smsContent,
        ),
    ),
    retentionHours = section.retentionHours,
    maxEvents = section.maxEvents,
    maxQueueBytes = section.maxQueueBytes,
)

private fun readableFailure(failure: Throwable): String = when (failure.message) {
    null -> "请重试。"
    "NOTIFICATION_PREFERENCE_PERSISTENCE_FAILED" -> "通知偏好未能保存。"
    "UPDATE_CHANNEL_PERSISTENCE_FAILED" -> "更新渠道未能保存。"
    "PREFERRED_GATEWAY_PERSISTENCE_FAILED" -> "连接地址未能保存。"
    else -> failure.message?.takeIf { it.isNotBlank() } ?: "请重试。"
}
