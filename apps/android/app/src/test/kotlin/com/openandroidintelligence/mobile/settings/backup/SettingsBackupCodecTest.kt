package com.openandroidintelligence.mobile.settings.backup

import com.openandroidintelligence.gateway.schema.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupCodecTest {

    private fun decode(text: String) = SettingsBackupCodec.decode(text)

    private val appearance = AppearanceSection(
        theme = ThemeValue.DARK,
        dynamicColor = true,
        dynamicIconColor = false,
        palette = "INDIGO",
        reduceMotion = true,
    )

    @Test
    fun roundTripKeepsEverySupportedSetting() {
        val sections = SettingsBackupSections(
            appearance = appearance,
            notifications = NotificationsSection(replyAlertsEnabled = false),
            messageProcessing = MessageProcessingSection(delayMillis = 2_000),
            conversation = ConversationSection(
                listOf(
                    ConversationOrganizationValue(
                        profileId = "b".repeat(64),
                        agentId = "hermes",
                        sortMode = ConversationSection.SORT_CUSTOM,
                        customOrder = listOf("conv_b", "conv_a"),
                        folders = listOf(ConversationFolderValue("folder_x", "工作")),
                        assignments = mapOf("conv_a" to "folder_x"),
                    ),
                ),
            ),
            connection = ConnectionSection("https://core.example.test"),
            agents = AgentsSection(mapOf("b".repeat(64) to "hermes")),
            capabilities = CapabilitiesSection(
                mapOf(
                    "plugin:org.example:${"c".repeat(64)}:capability:read" to CapabilityPolicyValue(
                        mode = com.openandroidintelligence.kernel.CapabilityAuthorizationMode.READ_ONLY,
                        allowedSources = listOf("+15550001111"),
                        includeContent = false,
                        smsScopeMode = com.openandroidintelligence.kernel.SmsScopeMode.PHONE_NUMBERS,
                        smsKeywords = listOf("验证码"),
                    ),
                ),
            ),
            updates = UpdatesSection("STABLE"),
            notificationCollection = NotificationCollectionSection(
                packageIds = listOf("com.example.mail"),
                fieldAccess = "METADATA",
                mode = "ON_DEMAND",
            ),
            devicePush = DevicePushSection(
                scope = PushScopeValue(
                    packageIds = listOf("com.example.chat"),
                    senderAddresses = emptyList(),
                    smsScopeMode = "ALL",
                    smsKeywords = emptyList(),
                    notificationContent = false,
                    smsContent = true,
                ),
                retentionHours = 24,
                maxEvents = 500,
                maxQueueBytes = 2 * 1_024 * 1_024,
            ),
        )
        val text = SettingsBackupCodec.encode(
            sections = sections.encode(),
            appVersionName = "1.2.3",
            appVersionCode = 45,
            exportedAt = "2026-11-08T10:00:00Z",
        )
        val document = decode(text)
        assertEquals(SettingsBackupSchema.CURRENT_VERSION, document.schemaVersion)
        assertEquals("2026-11-08T10:00:00Z", document.exportedAt)
        assertEquals("1.2.3", document.appVersionName)
        assertEquals(45L, document.appVersionCode)
        assertTrue(document.ignoredFields.isEmpty())

        val restored = SettingsBackupSections.decode(document, setOf("EMBER", "INDIGO"))
        assertEquals(sections, restored)
    }

    @Test
    fun exportedFileNeverContainsCredentialsOrIdentifiers() {
        val text = SettingsBackupCodec.encode(
            sections = SettingsBackupSections(appearance = appearance).encode(),
            appVersionName = "1.0.0",
            appVersionCode = 1,
            exportedAt = "2026-11-08T10:00:00Z",
        ).lowercase()
        // 这些是真实敏感字段名：文件里出现任何一个都意味着导出面被扩大。
        listOf(
            "password", "refresh", "credential", "token", "privatekey", "private_key",
            "installation_id", "installid", "keystore", "secret", "pairinggrant",
            "screenselection", "granted", "tls", "spki",
        ).forEach { forbidden ->
            assertFalse("备份文件不得包含 $forbidden：$text", text.contains(forbidden))
        }
    }

    @Test
    fun newerSchemaIsRejectedWithActionableMessage() {
        val text = Json.canonical(
            Json.of(
                mapOf(
                    "schemaVersion" to 99,
                    "exportedAt" to "2026-11-08T10:00:00Z",
                    "appVersionName" to "9.0.0",
                    "appVersionCode" to 900,
                    "settings" to mapOf("appearance" to mapOf("theme" to "DARK")),
                ),
            ),
        )
        val failure = assertThrows(SettingsBackupFormatException::class.java) { decode(text) }
        assertTrue(failure.message!!.contains("schemaVersion=99"))
    }

    @Test
    fun unknownSectionsAndFieldsAreReportedNotApplied() {
        val text = Json.canonical(
            Json.of(
                mapOf(
                    "schemaVersion" to 1,
                    "exportedAt" to "2026-11-08T10:00:00Z",
                    "appVersionName" to "1.0.0",
                    "appVersionCode" to 1,
                    "futureRoot" to true,
                    "settings" to mapOf(
                        "appearance" to mapOf(
                            "theme" to "DARK",
                            "dynamicColor" to true,
                            "dynamicIconColor" to false,
                            "palette" to "EMBER",
                            "reduceMotion" to false,
                            "futureField" to "x",
                        ),
                        "unknownSection" to mapOf("a" to 1),
                    ),
                ),
            ),
        )
        val document = decode(text)
        assertEquals(setOf("futureRoot", "settings.unknownSection", "settings.appearance.futureField"), document.ignoredFields.toSet())
        assertTrue("unknownSection" !in document.sections)
        assertEquals(setOf("appearance"), document.sections.keys)
    }

    @Test
    fun missingSectionsDecodeToAbsentSettingsInsteadOfDefaults() {
        val text = Json.canonical(
            Json.of(
                mapOf(
                    "schemaVersion" to 1,
                    "exportedAt" to "2026-11-08T10:00:00Z",
                    "appVersionName" to "1.0.0",
                    "appVersionCode" to 1,
                    "settings" to mapOf("updates" to mapOf("channel" to "PREVIEW")),
                ),
            ),
        )
        val sections = SettingsBackupSections.decode(decode(text))
        assertEquals(UpdatesSection("PREVIEW"), sections.updates)
        assertEquals(null, sections.appearance)
        assertEquals(null, sections.notifications)
        assertEquals(null, sections.capabilities)
    }

    @Test
    fun exportsBeyondAnyArbitraryCountAreStillImportable() {
        // 导出侧不设条数上限，导入侧就不能设：否则用户导出的文件自己读不回来。
        val organizations = ConversationSection(
            (1..80).map { index ->
                ConversationOrganizationValue(
                    profileId = index.toString().padStart(64, '0'),
                    agentId = "agent-$index",
                    sortMode = ConversationSection.SORT_UPDATED,
                    customOrder = emptyList(),
                    folders = emptyList(),
                    assignments = emptyMap(),
                )
            },
        )
        val messageProcessing = MessageProcessingSection(delayMillis = 30_000)
        val policies = CapabilitiesSection(
            (1..400).associate { index ->
                "plugin:example:$index" to CapabilityPolicyValue(
                    mode = com.openandroidintelligence.kernel.CapabilityAuthorizationMode.MANUAL,
                    allowedSources = emptyList(),
                    includeContent = true,
                    smsScopeMode = com.openandroidintelligence.kernel.SmsScopeMode.ALL,
                    smsKeywords = emptyList(),
                )
            },
        )
        val text = SettingsBackupCodec.encode(
            sections = SettingsBackupSections(
                conversation = organizations,
                messageProcessing = messageProcessing,
                capabilities = policies,
            ).encode(),
            appVersionName = "1.0.0",
            appVersionCode = 1,
            exportedAt = "2026-11-08T10:00:00Z",
        )
        val restored = SettingsBackupSections.decode(decode(text))
        assertEquals(80, restored.conversation!!.organizations.size)
        assertEquals(30_000, restored.messageProcessing!!.delayMillis)
        assertEquals(400, restored.capabilities!!.policies.size)
    }

    @Test
    fun invalidValuesRejectTheWholeFile() {
        fun reject(settings: Map<String, Any?>): Throwable {
            val text = Json.canonical(
                Json.of(
                    mapOf(
                        "schemaVersion" to 1,
                        "exportedAt" to "2026-11-08T10:00:00Z",
                        "appVersionName" to "1.0.0",
                        "appVersionCode" to 1,
                        "settings" to settings,
                    ),
                ),
            )
            return assertThrows(SettingsBackupFormatException::class.java) {
                SettingsBackupSections.decode(decode(text), setOf("EMBER", "INDIGO"))
            }
        }
        assertTrue(
            reject(mapOf("appearance" to mapOf("theme" to "NEON", "palette" to "EMBER"))).message!!.contains("theme"),
        )
        assertTrue(
            reject(mapOf("appearance" to mapOf("theme" to "DARK", "palette" to "UNKNOWN"))).message!!.contains("palette"),
        )
        assertTrue(reject(mapOf("messageProcessing" to mapOf("delayMillis" to 60_000))).message!!.contains("delayMillis"))
        assertTrue(
            reject(mapOf("connection" to mapOf("gatewayUrl" to "ftp://nope"))).message!!.contains("gatewayUrl"),
        )
        assertTrue(
            reject(mapOf("updates" to mapOf("channel" to "NIGHTLY"))).message!!.contains("channel"),
        )
        assertTrue(reject(mapOf("notifications" to mapOf("replyAlertsEnabled" to "yes"))).message!!.contains("replyAlertsEnabled"))
    }

    @Test
    fun brokenJsonAndMissingMetadataFailWithReadableErrors() {
        assertTrue(
            assertThrows(SettingsBackupFormatException::class.java) { decode("{not json") }.message!!.contains("JSON"),
        )
        assertTrue(
            assertThrows(SettingsBackupFormatException::class.java) {
                decode("""{"schemaVersion":1,"settings":{}}""")
            }.message!!.contains("导出时间"),
        )
    }

    @Test
    fun canonicalEncodingIsStableAcrossKeyOrder() {
        val first = SettingsBackupCodec.encode(
            sections = SettingsBackupSections(
                connection = ConnectionSection("https://a.example"),
                notifications = NotificationsSection(true),
            ).encode(),
            appVersionName = "1.0.0",
            appVersionCode = 1,
            exportedAt = "2026-11-08T10:00:00Z",
        )
        val second = SettingsBackupCodec.encode(
            sections = SettingsBackupSections(
                notifications = NotificationsSection(true),
                connection = ConnectionSection("https://a.example"),
            ).encode(),
            appVersionName = "1.0.0",
            appVersionCode = 1,
            exportedAt = "2026-11-08T10:00:00Z",
        )
        assertEquals(first, second)
    }
}
