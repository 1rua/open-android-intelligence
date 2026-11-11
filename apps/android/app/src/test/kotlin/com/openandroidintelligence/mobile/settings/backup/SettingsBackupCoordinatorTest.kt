package com.openandroidintelligence.mobile.settings.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.kernel.AndroidAuditStore
import com.openandroidintelligence.kernel.CapabilityAuthorizationMode
import com.openandroidintelligence.kernel.CapabilityPolicy
import com.openandroidintelligence.kernel.PairingGrantStateHolder
import com.openandroidintelligence.kernel.SmsScopeMode
import com.openandroidintelligence.mobile.AndroidAccountProfileStore
import com.openandroidintelligence.mobile.AppearancePreferences
import com.openandroidintelligence.mobile.LoginGatewayPrefill
import com.openandroidintelligence.mobile.ReplyNotificationSettings
import com.openandroidintelligence.mobile.ThemePreference
import com.openandroidintelligence.mobile.conversations.DebouncePreferences
import com.openandroidintelligence.mobile.conversations.EncryptedNativeConversationOrganizationStore
import com.openandroidintelligence.mobile.conversations.NativeLocalPartitions
import com.openandroidintelligence.mobile.updates.AppUpdateChannel
import com.openandroidintelligence.mobile.updates.AppUpdatePreferences
import com.openandroidintelligence.notification.control.NotificationControlRegistry
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置备份的端到端行为：只经由真实偏好与加密存储读写，不替身替换这些权威，
 * 因此「导出 → 改动 → 导入」验证的是用户真正会遇到的那条路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsBackupCoordinatorTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private companion object { const val INSTALL_ID = "install-test" }

    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 11 }, "AES")
        override fun delete() = Unit
    }

    private lateinit var appearance: AppearancePreferences
    private lateinit var profiles: AndroidAccountProfileStore
    private lateinit var grants: PairingGrantStateHolder
    private lateinit var updates: AppUpdatePreferences
    private lateinit var notifications: ReplyNotificationSettings

    @Before
    fun setUp() {
        clearStores()
        context.getSharedPreferences("open_android_intelligence_runtime", 0).edit()
            .putString("installation_id", INSTALL_ID).commit()
        appearance = AppearancePreferences(context)
        profiles = AndroidAccountProfileStore(context)
        grants = PairingGrantStateHolder(com.openandroidintelligence.kernel.InMemoryPairingGrantStore(), AndroidAuditStore())
        updates = AppUpdatePreferences(context, AppUpdateChannel.PREVIEW)
        notifications = ReplyNotificationSettings(context)
    }

    @After
    fun tearDown() = clearStores()

    private fun clearStores() {
        listOf(
            "open_android_intelligence_appearance",
            "open_android_intelligence_login",
            "open_android_intelligence_app_updates",
            "agent-reply-alert-settings",
            "conversation-debounce-v1",
            "open_android_intelligence_runtime",
        ).forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        profilesOrNull()?.let { store ->
            runCatching { store.list() }.getOrDefault(emptyList()).forEach { profile ->
                runCatching { store.delete(profile.localProfileId) }
            }
        }
    }

    private fun profilesOrNull(): AndroidAccountProfileStore? =
        runCatching { AndroidAccountProfileStore(context) }.getOrNull()

    private fun coordinator(
        policy: com.openandroidintelligence.notification.control.NotificationPolicyPort =
            NotificationControlRegistry.policyPort(),
        refreshes: SettingsRuntimeRefresh? = null,
    ) = SettingsBackupCoordinator(
        context = context,
        appearance = appearance,
        profiles = profiles,
        grants = grants,
        pluginHost = null,
        replyNotifications = notifications,
        runtime = null,
        devicePush = null,
        appUpdates = updates,
        notificationPolicy = policy,
        keyProvider = key,
        runtimeRefresh = refreshes,
    )

    @Test
    fun exportThenImportRestoresAppearanceNotificationsAndUpdateChannel() {
        appearance.applySettings(
            com.openandroidintelligence.mobile.AppearanceSettings(
                theme = ThemePreference.DARK,
                dynamicColor = true,
                dynamicIconColor = true,
                palette = com.openandroidintelligence.conversation.theme.ThemePalette.VIOLET,
                reduceMotion = true,
            ),
        )
        notifications.setEnabled(false)
        updates.writeChannel(AppUpdateChannel.STABLE)
        val exported = coordinator().export()
        assertTrue(exported.suggestedFileName.matches(Regex("oai-settings-\\d{8}\\.json")))
        assertFalse(exported.json.contains("\"password\""))

        // 模拟另一台设备的默认状态。
        appearance.applySettings(com.openandroidintelligence.mobile.AppearanceSettings())
        notifications.setEnabled(true)
        updates.writeChannel(AppUpdateChannel.PREVIEW)

        val outcome = coordinator().import(exported.json).getOrThrow()
        assertEquals(ThemePreference.DARK, appearance.settings.value.theme)
        assertTrue(appearance.settings.value.dynamicColor)
        assertTrue(appearance.settings.value.dynamicIconColor)
        assertEquals(com.openandroidintelligence.conversation.theme.ThemePalette.VIOLET, appearance.settings.value.palette)
        assertTrue(appearance.settings.value.reduceMotion)
        assertFalse(notifications.enabled.value)
        assertEquals(AppUpdateChannel.STABLE, updates.readChannel())
        assertTrue(outcome.appliedSummaries.isNotEmpty())
        assertTrue(outcome.ignoredFields.isEmpty())
    }

    @Test
    fun importingTheSameFileTwiceWritesNothingTheSecondTime() {
        appearance.applySettings(com.openandroidintelligence.mobile.AppearanceSettings(theme = ThemePreference.LIGHT))
        val exported = coordinator().export()
        coordinator().import(exported.json).getOrThrow()
        val second = coordinator().import(exported.json).getOrThrow()
        assertTrue(
            "第二次导入必须只报告「本机已是该值」",
            second.appliedSummaries.all { it.contains("本机已是该值") },
        )
    }

    @Test
    fun conversationOrganizationFromAnotherBuildIsSkippedInsteadOfInvented() {
        val text = backupWith(
            "conversation" to mapOf(
                "organizations" to listOf(
                    mapOf(
                        "profileId" to "d".repeat(64),
                        "agentId" to "hermes",
                        "sortMode" to "custom",
                        "customOrder" to listOf("conv_a"),
                        "folders" to listOf(mapOf("folderId" to "folder_1", "name" to "工作")),
                        "assignments" to mapOf("conv_a" to "folder_1"),
                    ),
                ),
            ),
        )
        val outcome = coordinator().import(text).getOrThrow()
        assertTrue(outcome.skipped.any { it.contains("本机还没有对应账号") })
        assertTrue(outcome.appliedSummaries.none { it.startsWith("会话列表") })
    }

    @Test
    fun conversationOrganizationForKnownAccountAndAgentIsApplied() {
        profiles.save(
            com.openandroidintelligence.gateway.account.AccountProfile("profile_a", "http://core.example", "alice", ""),
            AndroidAccountProfileStore.Binding("account_a", "device_a", "session_a", 1),
        )
        val scope = ConversationScope("profile_a", "http://core.example", "account_a", installId())
        NativeLocalPartitions(context, scope, key).register("hermes")
        val profileKey = backupProfileKey("profile_a")
        val text = backupWith(
            "conversation" to mapOf(
                "organizations" to listOf(
                    mapOf(
                        "profileId" to profileKey,
                        "agentId" to "hermes",
                        "sortMode" to "custom",
                        "customOrder" to listOf("conv_b", "conv_a"),
                        "folders" to listOf(mapOf("folderId" to "folder_1", "name" to "工作")),
                        "assignments" to mapOf("conv_a" to "folder_1"),
                    ),
                ),
            ),
        )
        coordinator().import(text).getOrThrow()
        val organization = EncryptedNativeConversationOrganizationStore(context, scope, key).read("hermes")
        assertEquals("conv_b", organization.customOrder.first())
        assertEquals("工作", organization.folders.single().name)
        assertEquals("folder_1", organization.assignments["conv_a"])
    }

    @Test
    fun capabilityImportKeepsLocalEnabledSwitchAndIgnoresUnknownKeys() {
        // 本机有一项能力，总开关打开；文件里同一项要求 AUTOMATIC，另一项本机不存在。
        val localKey = "plugin:org.example:${"a".repeat(64)}:capability:read"
        val otherKey = "plugin:org.example:${"b".repeat(64)}:capability:write"
        grants.updateCapabilityPolicy(localKey, CapabilityPolicy(enabled = true, mode = CapabilityAuthorizationMode.MANUAL))
        val section = CapabilitiesSection(
            mapOf(
                localKey to CapabilityPolicyValue(
                    mode = CapabilityAuthorizationMode.AUTOMATIC,
                    allowedSources = listOf("+8613800000000"),
                    includeContent = false,
                    smsScopeMode = SmsScopeMode.PHONE_NUMBERS,
                    smsKeywords = emptyList(),
                ),
                otherKey to CapabilityPolicyValue(
                    mode = CapabilityAuthorizationMode.AUTOMATIC,
                    allowedSources = emptyList(),
                    includeContent = true,
                    smsScopeMode = SmsScopeMode.ALL,
                    smsKeywords = emptyList(),
                ),
            ),
        )
        val text = SettingsBackupCodec.encode(
            sections = SettingsBackupSections(capabilities = section).encode(),
            appVersionName = "1.0.0",
            appVersionCode = 1,
            exportedAt = "2026-11-08T10:00:00Z",
        )
        // pluginHost 为 null ⇒ 没有已安装能力的白名单，任何键都不应被写入。
        val outcome = coordinator().import(text).getOrThrow()
        assertEquals(
            CapabilityAuthorizationMode.MANUAL,
            grants.state.value!!.capabilityPolicies.getValue(localKey).mode,
        )
        assertNull(grants.state.value!!.capabilityPolicies[otherKey])
        assertTrue(outcome.appliedSummaries.none { it.startsWith("设备能力") })
    }

    @Test
    fun missingRuntimeReportsNotificationSkipInsteadOfPretendingSuccess() {
        val coordinator = SettingsBackupCoordinator(
            context = context,
            appearance = appearance,
            profiles = profiles,
            grants = grants,
            pluginHost = null,
            replyNotifications = null,
            runtime = null,
            devicePush = null,
            appUpdates = updates,
            notificationPolicy = NotificationControlRegistry.policyPort(),
            keyProvider = key,
        )
        val text = backupWith("notifications" to mapOf("replyAlertsEnabled" to false))
        val outcome = coordinator.import(text).getOrThrow()
        assertTrue(outcome.skipped.any { it.contains("未接入 Agent 任务通知") })
        assertTrue(outcome.appliedSummaries.isEmpty())
    }

    @Test
    fun connectionAddressFallsBackToSavedProfilesForUpgradedInstalls() {
        // 老版本升级上来的用户没有偏好里的 prefill，但账号已经存了地址：不能因此导出缺节。
        LoginGatewayPrefill.clear(context)
        profiles.save(
            com.openandroidintelligence.gateway.account.AccountProfile("profile_a", "http://legacy.example", "alice", ""),
            AndroidAccountProfileStore.Binding("account_a", "device_a", "session_a", 1),
        )
        val exported = coordinator().export()
        assertTrue(exported.json.contains("legacy.example"))
        assertTrue(exported.json.contains("\"connection\""))
        assertEquals(emptyList<String>(), coordinator().exportSummary().filter { it.startsWith("连接地址：null") })
    }

    @Test
    fun aRollbackThatCannotBeCompletedIsReportedAsPartialInsteadOfSuccess() {
        // 通知策略在导入与回滚时都拒绝：导入失败后，界面必须说「部分未恢复」，
        // 而不是照抄一句「已恢复原状」。
        val rejecting = object : com.openandroidintelligence.notification.control.NotificationPolicyPort {
            override fun snapshot() = com.openandroidintelligence.notification.control.NotificationPolicySnapshot(
                installed = true,
                granted = true,
                packageIds = listOf("com.example.mail"),
                fieldAccess = com.openandroidintelligence.core.model.NotificationFieldAccess.METADATA,
                mode = com.openandroidintelligence.core.model.NotificationDeliveryMode.ON_DEMAND,
                revision = 7uL,
            )
            override fun observe() = kotlinx.coroutines.flow.flowOf(snapshot())
            override fun update(request: com.openandroidintelligence.notification.control.NotificationPolicyUpdate) =
                com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome.Rejected("TEST_REJECT")
        }
        val text = backupWith(
            "notificationCollection" to mapOf(
                "packageIds" to listOf("com.example.chat"),
                "fieldAccess" to "CONTENT",
                "mode" to "AUTO_SEND",
            ),
        )
        val failure = coordinator(policy = rejecting).import(text).exceptionOrNull()
        assertTrue(failure != null)
        val message = failure!!.message.orEmpty()
        assertTrue("应指出未恢复的项目：$message", message.contains("未能恢复"))
        assertTrue("应列出具体项目：$message", message.contains("通知采集范围"))
    }

    @Test
    fun capabilityPoliciesWhoseKeysNoLongerMatchAreReportedAsSkipped() {
        // 键里带作者指纹与 Schema 摘要：插件升级后旧键对不上，必须告诉用户要重配，
        // 而不是把这一节算作「本机已是该值」。
        val text = backupWith(
            "capabilities" to mapOf(
                "policies" to listOf(
                    mapOf(
                        "key" to "plugin:org.example:${"f".repeat(64)}:capability:read",
                        "policy" to mapOf(
                            "mode" to "AUTOMATIC",
                            "allowedSources" to emptyList<String>(),
                            "includeContent" to true,
                            "smsScopeMode" to "ALL",
                            "smsKeywords" to emptyList<String>(),
                        ),
                    ),
                ),
            ),
        )
        val outcome = coordinator().import(text).getOrThrow()
        assertTrue(
            "应说明能力策略未导入：${outcome.skipped}",
            outcome.skipped.any { it.contains("设备能力策略未导入") },
        )
        assertTrue(outcome.appliedSummaries.none { it.contains("（本机已是该值）") && it.startsWith("设备能力") })
    }

    @Test
    fun credentialsEmbeddedInTheCoreAddressNeverReachTheBackupFile() {
        // 地址里带 user-info 时，parse() 仍会接受；备份是明文文件，必须去掉或整节不写。
        LoginGatewayPrefill.clear(context)
        context.getSharedPreferences("open_android_intelligence_login", 0).edit()
            .putString("preferred_gateway_url", "https://alice:secret@core.example.test").commit()
        val exported = coordinator().export()
        assertFalse("不得出现口令：${exported.json}", exported.json.contains("secret"))
        assertFalse(exported.json.contains("alice"))
        assertTrue(exported.json.contains("core.example.test"))
    }

    @Test
    fun notificationPolicyChangeIsDisclosedAsIrreversibleInsteadOfSilentlyRestored() {
        var revision = 3uL
        var packages = listOf("com.example.mail")
        val port = object : com.openandroidintelligence.notification.control.NotificationPolicyPort {
            override fun snapshot() = com.openandroidintelligence.notification.control.NotificationPolicySnapshot(
                installed = true, granted = true, packageIds = packages,
                fieldAccess = com.openandroidintelligence.core.model.NotificationFieldAccess.METADATA,
                mode = com.openandroidintelligence.core.model.NotificationDeliveryMode.ON_DEMAND,
                revision = revision,
            )
            override fun observe() = kotlinx.coroutines.flow.flowOf(snapshot())
            override fun update(request: com.openandroidintelligence.notification.control.NotificationPolicyUpdate) =
                com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome.Accepted(
                    run {
                        revision += 1uL
                        request.packageIds?.let { packages = it }
                        snapshot()
                    },
                )
        }
        val text = backupWith(
            "notificationCollection" to mapOf(
                "packageIds" to listOf("com.example.chat"),
                "fieldAccess" to "METADATA",
                "mode" to "ON_DEMAND",
            ),
        )
        val outcome = coordinator(policy = port).import(text).getOrThrow()
        assertTrue(
            "必须说明已采集通知不会补发：${outcome.deferredNotices}",
            outcome.deferredNotices.any { it.contains("不会补发") },
        )
    }

    @Test
    fun capabilitiesWithoutLocalPolicyEntryKeepTheirDefaultEnabledSwitch() {
        // 没有显式条目的能力在授权权威里是 CapabilityPolicy()（enabled = true），
        // 能否执行由既有原语授权检查决定。导入只能改模式与范围，不能把它关掉。
        val key = "plugin:org.example:${"a".repeat(64)}:capability:read"
        val merged = mergeCapabilityPolicies(
            incoming = mapOf(
                key to CapabilityPolicyValue(
                    mode = CapabilityAuthorizationMode.READ_ONLY,
                    allowedSources = emptyList(),
                    includeContent = true,
                    smsScopeMode = SmsScopeMode.ALL,
                    smsKeywords = emptyList(),
                ),
            ),
            installed = setOf(key),
            current = emptyMap(),
            baselineOf = { CapabilityPolicy() },
        )
        assertEquals(0, merged.unmatched)
        val policy = merged.policies.getValue(key)
        assertTrue("导入不得关闭本机默认开启的能力", policy.enabled)
        assertEquals(CapabilityAuthorizationMode.READ_ONLY, policy.mode)
    }

    @Test
    fun capabilitiesWithAnExistingEntryKeepThatEntrysEnabledSwitch() {
        val key = "plugin:org.example:${"b".repeat(64)}:capability:write"
        val merged = mergeCapabilityPolicies(
            incoming = mapOf(
                key to CapabilityPolicyValue(
                    mode = CapabilityAuthorizationMode.AUTOMATIC,
                    allowedSources = listOf("+8613800000000"),
                    includeContent = false,
                    smsScopeMode = SmsScopeMode.PHONE_NUMBERS,
                    smsKeywords = emptyList(),
                ),
            ),
            installed = setOf(key),
            current = mapOf(key to CapabilityPolicy(enabled = false)),
            baselineOf = { error("已有条目时不应回落到权威默认值") },
        )
        val policy = merged.policies.getValue(key)
        assertFalse(policy.enabled)
        assertEquals(CapabilityAuthorizationMode.AUTOMATIC, policy.mode)
        assertEquals(setOf("+8613800000000"), policy.allowedSources)
    }

    @Test
    fun unmatchedCapabilityKeysAreCountedRatherThanWritten() {
        val merged = mergeCapabilityPolicies(
            incoming = mapOf(
                "plugin:org.example:${"c".repeat(64)}:capability:read" to CapabilityPolicyValue(
                    mode = CapabilityAuthorizationMode.MANUAL,
                    allowedSources = emptyList(),
                    includeContent = true,
                    smsScopeMode = SmsScopeMode.ALL,
                    smsKeywords = emptyList(),
                ),
            ),
            installed = emptySet(),
            current = emptyMap(),
            baselineOf = { CapabilityPolicy() },
        )
        assertEquals(1, merged.unmatched)
        assertTrue(merged.policies.isEmpty())
    }

    @Test
    fun corruptFileLeavesEveryStoreUntouched() {
        appearance.applySettings(com.openandroidintelligence.mobile.AppearanceSettings(theme = ThemePreference.LIGHT))
        notifications.setEnabled(true)
        val before = appearance.settings.value
        val failure = coordinator().import("""{"schemaVersion":1,"settings":{"appearance":{"theme":"NEON"}}}""")
        assertTrue(failure.isFailure)
        assertEquals(before, appearance.settings.value)
        assertTrue(notifications.enabled.value)
    }

    @Test
    fun connectionAddressIsRestorableOfflineAndNeverContainsCredentials() {
        LoginGatewayPrefill.write(context, "https://core.example.test")
        val exported = coordinator().export()
        assertTrue(exported.json.contains("core.example.test"))
        LoginGatewayPrefill.clear(context)
        coordinator().import(exported.json).getOrThrow()
        assertEquals("https://core.example.test", LoginGatewayPrefill.read(context))
        assertNull(LoginGatewayPrefill.read(context)?.takeIf { it.contains("password") })
    }

    @Test
    fun exportAndImportPreviewDescribeTheActualMessageMergingDelay() {
        val preferences = DebouncePreferences(context)
        val owner = coordinator()
        assertTrue(owner.exportSummary().contains("消息合并：立即发送"))

        preferences.write(2_500)
        val exported = owner.export()
        assertTrue(owner.exportSummary().contains("消息合并：2.5 秒"))
        assertTrue(owner.preview(exported.json).summaries.contains("消息合并：2.5 秒"))

        preferences.write(0)
        assertTrue(owner.preview(exported.json).summaries.contains("消息合并：2.5 秒"))
        assertEquals("读取预览不能写入本机偏好", 0L, preferences.read().delay.inWholeMilliseconds)
    }

    @Test
    fun messageProcessingDelayRoundTripsAndLegacyMergingValuesAreReportedNotImported() {
        val preferences = DebouncePreferences(context)
        preferences.write(3_000)
        val exported = coordinator().export()
        assertTrue(exported.json.contains("\"delayMillis\":3000"))

        preferences.write(0)
        coordinator().import(exported.json).getOrThrow()
        assertEquals(3_000L, preferences.read().delay.inWholeMilliseconds)

        // 旧文件的合并值（global 固定默认 + 按 Core 覆盖）都没有「用户主动设置」的可信依据：
        // 整节不写入，也不会为该地址生成看不见的键，并如实告知。
        val unknownKey = "e".repeat(64)
        val text = backupWith(
            "messageProcessing" to mapOf(
                "global" to mapOf("delayMillis" to 1_000, "extendWindow" to true),
                "cores" to listOf(mapOf("key" to unknownKey, "delayMillis" to 100, "extendWindow" to false)),
            ),
        )
        preferences.write(4_000)
        assertTrue(coordinator().preview(text).summaries.contains("消息合并：保持本机现值"))
        val outcome = coordinator().import(text).getOrThrow()
        assertEquals("旧文件的合并值不覆盖本机现值", 4_000L, preferences.read().delay.inWholeMilliseconds)
        assertTrue(outcome.skipped.any { it.contains("消息合并设置未导入") })
        assertTrue(outcome.skipped.contains(
            "旧版本备份里的 2 项消息合并设置未导入：" +
                "旧版导出会固定写入默认值且不再按 Core 区分，消息合并现由客户端统一设置；请按需重新调整。",
        ))
        val all = context.getSharedPreferences("conversation-debounce-v1", 0).all
        assertTrue(all.keys.none { it.startsWith("gateway:$unknownKey") })
    }

    /**
     * 合并策略刷新会把时长归零变成「立即发送已收集的消息」，这是不可撤销的副作用。
     * 导入必须等全部节写成功后才刷新；中途失败时不得发出任何消息。
     */
    @Test
    fun aFailedImportNeverAppliesTheMergingChangeToTheRunningSession() {
        val appliedDelays = mutableListOf<Long>()
        val refreshes = object : SettingsRuntimeRefresh {
            override fun reloadDebounceSettings() {
                // 记录运行时真正会看到的时长：0 会让合并器立刻发出已收集的消息。
                appliedDelays += DebouncePreferences(context).read().delay.inWholeMilliseconds
            }
            override fun refreshThreads() = Unit
        }
        val rejecting = object : com.openandroidintelligence.notification.control.NotificationPolicyPort {
            override fun snapshot() = com.openandroidintelligence.notification.control.NotificationPolicySnapshot(
                installed = true,
                granted = true,
                packageIds = emptyList(),
                fieldAccess = com.openandroidintelligence.core.model.NotificationFieldAccess.METADATA,
                mode = com.openandroidintelligence.core.model.NotificationDeliveryMode.ON_DEMAND,
                revision = 1uL,
            )
            override fun observe() = kotlinx.coroutines.flow.flowOf(snapshot())
            override fun update(request: com.openandroidintelligence.notification.control.NotificationPolicyUpdate) =
                com.openandroidintelligence.notification.control.NotificationPolicyUpdateOutcome.Rejected("REVISION_STALE")
        }
        DebouncePreferences(context).write(30_000)
        val text = backupWith(
            "messageProcessing" to mapOf("delayMillis" to 0),
            "notificationCollection" to mapOf(
                "packageIds" to listOf("com.example.chat"),
                "fieldAccess" to "METADATA",
                "mode" to "ON_DEMAND",
            ),
        )

        assertTrue(coordinator(policy = rejecting, refreshes = refreshes).import(text).isFailure)
        assertTrue("失败导入不得让运行时看到归零设置，否则已收集的消息会被发出：$appliedDelays", appliedDelays.none { it == 0L })
        assertEquals("偏好必须回滚到导入前", 30_000L, DebouncePreferences(context).read().delay.inWholeMilliseconds)
    }

    @Test
    fun aSuccessfulImportAppliesTheMergingChangeOnlyAfterEverySectionIsWritten() {
        var mergingReloads = 0
        val refreshes = object : SettingsRuntimeRefresh {
            override fun reloadDebounceSettings() { mergingReloads++ }
            override fun refreshThreads() = Unit
        }
        DebouncePreferences(context).write(30_000)
        val outcome = coordinator(refreshes = refreshes)
            .import(backupWith("messageProcessing" to mapOf("delayMillis" to 2_000))).getOrThrow()

        assertEquals(1, mergingReloads)
        assertEquals(2_000L, DebouncePreferences(context).read().delay.inWholeMilliseconds)
        assertTrue(outcome.appliedSummaries.any { it.contains("2.0") })
    }

    private fun backupWith(vararg sections: Pair<String, Map<String, Any?>>): String =
        SettingsBackupCodec.encode(
            sections = sections.associate { (name, fields) ->
                name to com.openandroidintelligence.gateway.schema.Json.of(fields)
                    as com.openandroidintelligence.gateway.schema.JsonValue.JObject
            },
            appVersionName = "1.0.0",
            appVersionCode = 1,
            exportedAt = "2026-11-08T10:00:00Z",
        )

    private fun installId(): String = INSTALL_ID

    @Test
    fun aMissingInstallIdSkipsConversationOrganizationInsteadOfInventingOne() {
        profiles.save(
            com.openandroidintelligence.gateway.account.AccountProfile("profile_a", "http://core.example", "alice", ""),
            AndroidAccountProfileStore.Binding("account_a", "device_a", "session_a", 1),
        )
        context.getSharedPreferences("open_android_intelligence_runtime", 0).edit().clear().commit()
        val text = backupWith(
            "conversation" to mapOf(
                "organizations" to listOf(
                    mapOf(
                        "profileId" to backupProfileKey("profile_a"),
                        "agentId" to "hermes",
                        "sortMode" to "custom",
                        "customOrder" to listOf("conv_a"),
                        "folders" to emptyList<Any>(),
                        "assignments" to emptyMap<String, String>(),
                    ),
                ),
            ),
        )
        val outcome = coordinator().import(text).getOrThrow()
        assertTrue(outcome.skipped.any { it.contains("安装标识") })
    }
}
