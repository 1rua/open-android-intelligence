package com.openandroidintelligence.mobile.settings.backup

import android.content.Context
import com.openandroidintelligence.conversation.ports.ConversationFolder
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSortMode
import com.openandroidintelligence.conversation.theme.ThemePalette
import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.http.GatewayEndpoint
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
 * 导入成功后需要让运行时采用的设置变更。
 *
 * 抽成接口是为了让「导入中途失败时不得产生不可撤销的发送」可以被测试直接验证，
 * 而不必在测试里搭一个已连接的工作台。
 */
interface SettingsRuntimeRefresh {
    fun reloadDebounceSettings()
    fun refreshThreads()
}

fun gatewayRuntimeRefresh(runtime: com.openandroidintelligence.mobile.GatewayRuntime): SettingsRuntimeRefresh =
    object : SettingsRuntimeRefresh {
        override fun reloadDebounceSettings() = runtime.reloadDebounceSettings()
        override fun refreshThreads() { runtime.controller.value?.refreshThreads() }
    }

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
    /**
     * 运行时刷新入口；默认桥接到 [runtime]，测试可替换为记录器。
     */
    private val runtimeRefresh: SettingsRuntimeRefresh? = runtime?.let(::gatewayRuntimeRefresh),
) {
    /**
     * 导入期间累积的运行时刷新动作，只在全部写入成功后才执行。
     *
     * 合并策略的刷新不是纯读：把时长改回 0 会立刻提交已收集的消息。若在导入中途执行，
     * 后续节失败触发回滚也无法撤回这次发送，界面却会说「已恢复为导入前的状态」。
     */
    private val pendingRuntimeRefreshes = mutableListOf<() -> Unit>()

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

    private fun currentMessageProcessing(): MessageProcessingSection =
        MessageProcessingSection(DebouncePreferences(context).read().delay.inWholeMilliseconds.toInt())

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

    /**
     * 导出的 Core 地址：优先用预填偏好，没有就回落到本机已有连接信息。
     *
     * 老版本升级上来的用户还没走过「登录后记录预填」这一步，若只读预填偏好，
     * 首次导出会静默缺掉整节——而这正是本功能承诺要迁移的东西。回落顺序：
     * 预填偏好 → 运行时的最近地址（含已登出/离线镜像）→ 已保存账号的地址。
     */
    private fun currentConnection(): ConnectionSection? {
        val candidates = sequenceOf(
            LoginGatewayPrefill.read(context),
            runtime?.lastGatewayUrl(),
            profiles.list().firstOrNull()?.gatewayBaseUrl,
        )
        val url = candidates.filterNotNull()
            .firstOrNull { it.isNotBlank() && GatewayEndpoint.parse(it) != null }
            ?: return null
        // 地址里可能带着 user-info（`https://alice:secret@core.example`）：`GatewayEndpoint.parse`
        // 只校验 scheme 与 host，会原样接受。备份是明文的便携文件，绝不能把它写出去；
        // 去不掉就整节不导出——宁可少迁移一项，也不能泄漏凭据。
        return credentialFreeGateway(url)?.let(::ConnectionSection)
    }

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
        pendingRuntimeRefreshes.clear()
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
            // 以下三节有不可撤销的副作用，按「最难恢复的排最后」排列：
            // - 能力策略的审计失败会留下拒绝屏障（本身可被如实报告为部分未恢复）；
            // - 推送收窄会真正删掉排队事件并排定远端撤销；
            // - 通知采集范围一旦换 revision，已排队记录的 captureRevision 就不再匹配，
            //   恢复旧策略也换不回这些记录的投递资格。
            // 这样任何失败都发生在后面的节之前，不会把不可撤销的结果当成可撤销来承诺。
            applyCapabilities(sections.capabilities, applied, skipped)
            applyDevicePush(sections.devicePush, applied, skipped, deferred)
            applyNotificationCollection(sections.notificationCollection, applied, skipped, deferred)
        } catch (failure: Exception) {
            // restore() 会返回**确认恢复失败**的清单；只有清单为空才敢说「已恢复原状」。
            pendingRuntimeRefreshes.clear()
            val unrestored = runCatching { snapshot.restore() }.getOrElse { listOf("设置") }
            return Result.failure(
                SettingsBackupFormatException(
                    if (unrestored.isEmpty()) {
                        "导入失败，本机设置已恢复为导入前的状态。${readableFailure(failure)}"
                    } else {
                        "导入失败，且以下设置未能恢复原状，请到对应设置页核对：${unrestored.joinToString("、")}。" +
                            readableFailure(failure)
                    },
                ),
            )
        }
        // 所有节都已成功写入，才允许运行时采用新设置。
        pendingRuntimeRefreshes.forEach { refresh -> runCatching(refresh) }
        pendingRuntimeRefreshes.clear()
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
        var unchanged = false
        section.delayMillis?.let { delay ->
            if (preferences.read().delay.inWholeMilliseconds.toInt() == delay) {
                unchanged = true
            } else {
                preferences.write(delay)
                written++
            }
        }
        if (written > 0) {
            applied += section.summary()
            pendingRuntimeRefreshes += { runtimeRefresh?.reloadDebounceSettings() }
            deferred += "消息合并已保存；当前会话在下次发送时生效。"
        } else if (unchanged) {
            applied += section.summary() + "（本机已是该值）"
        }
        // 旧文件的合并值无法区分「用户主动设置」与「版本默认值」，整节不迁移，如实说明。
        if (section.hasLegacyMergingSettings) {
            skipped += "stringResource(UiR.string.settings_backup_legacy_section_dropped, section.legacyMergingSettings)" +
                "stringResource(UiR.string.settings_backup_legacy_note)"
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
            pendingRuntimeRefreshes += { runtimeRefresh?.refreshThreads() }
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
        deferred: MutableList<String>,
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
            is NotificationPolicyUpdateOutcome.Accepted -> {
                applied += section.summary()
                // 换 revision 后，此前按旧策略采集、仍在队列里的通知不再满足投递条件。
                // 这是不可撤销的，必须说清楚，而不是等用户发现通知没发出去。
                deferred += "通知采集范围已更新；此前已采集、尚未发送的通知按旧策略记录，不会补发。"
            }
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
        deferred: MutableList<String>,
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
        // 收窄来源范围或缩短保留期会立即删除不再符合条件的排队事件，且会向 Core 排定撤销；
        // 这两件事都无法撤销，必须提前/事后都说清楚。
        deferred += "主动推送范围已更新；不再符合新范围或已超保留期的排队事件已移除，该操作不可撤销。"
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
        val merged = mergeCapabilityPolicies(
            incoming = section.policies,
            installed = installed,
            current = configured,
            baselineOf = grants::capabilityPolicy,
        )
        // 键里有作者指纹、Schema 摘要与风险等级；插件升级后会变化。这不是「已是最新」，
        // 而是「这条策略在本机没有对应声明」——必须让用户知道要手动重配，而不是静默算作已恢复。
        if (merged.unmatched > 0) {
            skipped += "${merged.unmatched} 项设备能力策略未导入：本机插件声明已变化（升级或重装），请在对应能力页重新设置。"
        }
        val changed = merged.policies.filter { (key, value) -> configured[key] != value }
        if (changed.isEmpty()) {
            if (merged.policies.isNotEmpty()) applied += "设备能力：${merged.policies.size} 项（本机已是该值）"
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
        val capabilityPolicies: Map<String, com.openandroidintelligence.kernel.CapabilityPolicy>,
        val organizations: List<OrganizationSnapshot>,
    ) {
        /**
         * 把各类权威写回快照值，返回**确认未恢复**的项目名称。
         *
         * 返回空列表才等于「已恢复原状」：每一项都收集失败而不是笼统地 `isSuccess`。
         * 典型场景是能力持有者在审计失败后留下 `LOCAL_AUTHORIZATION_UPDATE_INCOMPLETE`
         * 拒绝屏障，后续替换会被它拒绝——那种情况必须如实报告，而不是吞掉异常后宣称已恢复。
         * 同时也不绕开拒绝屏障（不直接改内存态）：屏障本身就是防止权限悄悄放开的保险。
         */
        fun restore(): List<String> {
            val failed = mutableListOf<String>()
            fun attempt(label: String, action: () -> Unit) {
                if (runCatching(action).isFailure) failed += label
            }
            attempt("外观") {
                val previous = appearance
                if (this@SettingsBackupCoordinator.appearance.settings.value != previous) {
                    this@SettingsBackupCoordinator.appearance.applySettings(previous)
                }
            }
            notificationsEnabled?.let { enabled ->
                attempt("通知提醒") {
                    replyNotifications?.let { if (it.enabled.value != enabled) it.setEnabled(enabled) }
                }
            }
            attempt("消息处理") {
                DebouncePreferences(context).restore(debounce)
                runtimeRefresh?.reloadDebounceSettings()
            }
            attempt("连接地址") {
                val written = if (preferredGateway == null) LoginGatewayPrefill.clear(context)
                else LoginGatewayPrefill.write(context, preferredGateway)
                check(written) { "PREFERRED_GATEWAY_PERSISTENCE_FAILED" }
            }
            attempt("Agent 选择") {
                agents.forEach { (profileId, agentId) -> profiles.saveSelectedAgent(profileId, agentId) }
            }
            attempt("应用更新") {
                check(appUpdates.readChannel() == updates || appUpdates.writeChannel(updates)) {
                    "UPDATE_CHANNEL_PERSISTENCE_FAILED"
                }
            }
            // 通知采集范围只恢复「设置值」，不承诺恢复投递能力：换 revision 之后，
            // 排队记录的 captureRevision 已不匹配，回滚策略也无法让它们重新可投递。
            // 这一步仍要执行（否则白名单/字段访问会停在导入值），但不计入「完全恢复」。
            notificationCollection?.let { previous ->
                attempt("通知采集范围") {
                    val outcome = notificationPolicy.update(
                        NotificationPolicyUpdate(
                            expectedRevision = notificationPolicy.snapshot().revision,
                            packageIds = previous.packageIds,
                            fieldAccess = previous.fieldAccess,
                            mode = previous.mode,
                        ),
                    )
                    check(outcome is NotificationPolicyUpdateOutcome.Accepted) { "NOTIFICATION_POLICY_RESTORE_FAILED" }
                }
            }
            if (capabilityPolicies.isNotEmpty() || grants.state.value?.capabilityPolicies != capabilityPolicies) {
                attempt("设备能力策略") {
                    grants.replaceCapabilityPolicies(capabilityPolicies)
                }
            }
            organizations.forEach { previous ->
                attempt("会话排序") {
                    val store = organizationStore(previous.scope)
                    val current = store.read(previous.agentId)
                    if (current != previous.organization) {
                        store.update(previous.agentId, current.revision, previous.organization)
                    }
                }
            }
            attempt("会话列表刷新") {
                runtimeRefresh?.refreshThreads()
            }
            return failed.distinct()
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

    private companion object {
        const val RUNTIME_PREFERENCES = "open_android_intelligence_runtime"
        const val KEY_INSTALL = "installation_id"
    }
}

/** 能力策略合并的结果：可写回的策略，以及在本机没有对应声明的条数。 */
internal data class MergedCapabilityPolicies(
    val policies: Map<String, com.openandroidintelligence.kernel.CapabilityPolicy>,
    val unmatched: Int,
)

/**
 * 把备份里的能力策略合并到本机现值上。
 *
 * 两条不可动摇的规则：
 * 1. 只写回本机**真实存在**的声明（`installed`），文件里多出来的键不创建条目；
 * 2. 本机基线**必须来自授权权威**（`baselineOf`）。没有显式条目的能力在
 *    `PairingGrantStateHolder.capabilityPolicy()` 里是 `CapabilityPolicy()`（`enabled = true`），
 *    能否真正执行由既有的原语授权检查决定。若在这里自己造一个 `enabled = false`，
 *    导入会把「默认开启」的能力悄悄关掉，与「总开关沿用本机值」的承诺正好相反。
 */
internal fun mergeCapabilityPolicies(
    incoming: Map<String, CapabilityPolicyValue>,
    installed: Set<String>,
    current: Map<String, com.openandroidintelligence.kernel.CapabilityPolicy>,
    baselineOf: (String) -> com.openandroidintelligence.kernel.CapabilityPolicy,
): MergedCapabilityPolicies {
    val policies = mutableMapOf<String, com.openandroidintelligence.kernel.CapabilityPolicy>()
    var unmatched = 0
    incoming.forEach { (key, value) ->
        if (key !in installed) { unmatched++; return@forEach }
        policies[key] = value.appliedTo(current[key] ?: baselineOf(key))
    }
    return MergedCapabilityPolicies(policies, unmatched)
}

/**
 * 去掉 URL 里的 user-info，返回可安全写进备份文件的地址；无法安全处理时返回 null。
 *
 * 用 `URI` 而不是字符串切割：只有它知道 `@` 出现在 authority 的哪个位置，
 * 手写 `substringAfter("@")` 会把 query 里的 `@` 也当成凭据分隔符。
 */
internal fun credentialFreeGateway(url: String): String? = runCatching {
    val uri = java.net.URI(url)
    if (uri.rawUserInfo == null) return@runCatching url
    val rebuilt = java.net.URI(
        uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment,
    ).toString()
    // 重建后必须仍是被同一个解析器接受的地址，否则说明这条 URL 的形状我们理解错了。
    if (GatewayEndpoint.parse(rebuilt) == null) null else rebuilt
}.getOrNull()

/** 档案 ID 在备份文件里只以 SHA-256 出现：文件无法反推出账号名。 */
internal fun backupProfileKey(profileId: String): String =
    MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

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