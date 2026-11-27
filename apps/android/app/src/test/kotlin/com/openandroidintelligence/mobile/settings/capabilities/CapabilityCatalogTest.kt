package com.openandroidintelligence.mobile.settings.capabilities

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.mobile.plugins.VerifiedCapability
import com.openandroidintelligence.plugin.pkg.PluginIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主页面信息架构的纯函数接缝。
 *
 * 这里只锁用户在主页面上看到的东西：三个分组、六条能力、哪条能力背后还有子能力，
 * 以及已验证声明如何挂到这些固定入口上。目录本身永远不证明能力已就绪。
 */
class CapabilityCatalogTest {

    private fun verified(plugin: String, id: String, version: String = "1.0.0") = VerifiedCapability(
        PluginIdentity(plugin, "a".repeat(64), version), id, version, Json.parse("{}"), "read", setOf("kernel.sms.read"),
    )

    @Test
    fun theMainPageHasExactlyThreeGroupsOfBuiltInCapabilities() {
        val catalog = capabilityCatalog(emptyList())

        assertEquals(listOf("屏幕", "通讯与通知"), catalog.map { it.group }.distinct())
        assertEquals(6, catalog.size)
        assertEquals(
            listOf("屏幕内容读取", "屏幕截图", "屏幕控制"),
            catalog.filter { it.group == "屏幕" }.map { it.title },
        )
        assertEquals(
            listOf("短信", "通知读取", "通话记录"),
            catalog.filter { it.group == "通讯与通知" }.map { it.title },
        )
    }

    @Test
    fun theSmsEntryOwnsItsThreeSiblingCapabilitiesInsteadOfSpendingItsOwnRows() {
        val sms = capabilityCatalog(emptyList()).single { it.title == "短信" }

        assertEquals("org.openandroidintelligence.sms.query", sms.capability.substringBefore('@'))
        assertEquals(
            listOf(
                "org.openandroidintelligence.sms.query",
                "org.openandroidintelligence.sms.schedule",
                "org.openandroidintelligence.sms.job-status",
                "org.openandroidintelligence.sms.cancel",
            ),
            sms.members.map { it.substringBefore('@') },
        )
        // 其余五项没有兄弟能力，members 只有它自己。
        capabilityCatalog(emptyList()).filter { it.title != "短信" }.forEach {
            assertEquals(listOf(it.capability.substringBefore('@')), it.members.map { member -> member.substringBefore('@') })
        }
    }

    @Test
    fun aVerifiedDeclarationIsAttachedToItsOwnEntryAndNeverCreatesAnExtraRow() {
        val official = verified("org.openandroidintelligence.sms", "org.openandroidintelligence.sms.query")
        val catalog = capabilityCatalog(listOf(official))

        assertEquals(6, catalog.size)
        val sms = catalog.single { it.title == "短信" }
        assertEquals(official.key, sms.capability)
        assertEquals(official, sms.verified)
        // 声明缺失不等于插件缺失：入口仍在，只是没有已验证提供者。
        assertNull(catalog.single { it.title == "屏幕控制" }.verified)
    }

    @Test
    fun thirdPartyCapabilitiesNeverAppearOnTheBuiltInCapabilityPage() {
        val external = verified("org.example.other", "org.example.other.custom")
        val foreignScreen = verified("org.example.provider", "org.openandroidintelligence.sms.query")

        val catalog = capabilityCatalog(listOf(external, foreignScreen))

        assertEquals(6, catalog.size)
        assertEquals(emptyList<CapabilityCatalogItem>(), catalog.filter { it.pluginId.startsWith("org.example") })
        assertTrue("第三方能力只由插件管理维护", catalog.none { it.title == "org.example.other.custom" })
    }

    @Test
    fun everyEntryKeepsThePluginThatOwnsIt() {
        val expected = mapOf(
            "屏幕内容读取" to "org.openandroidintelligence.screen-reader",
            "屏幕截图" to "org.openandroidintelligence.screen-capture",
            "屏幕控制" to "org.openandroidintelligence.screen-control",
            "短信" to "org.openandroidintelligence.sms",
            "通知读取" to "org.openandroidintelligence.notifications",
            "通话记录" to "org.openandroidintelligence.call-log",
        )
        capabilityCatalog(emptyList()).forEach { item ->
            assertEquals(expected.getValue(item.title), item.pluginId)
        }
    }
}
