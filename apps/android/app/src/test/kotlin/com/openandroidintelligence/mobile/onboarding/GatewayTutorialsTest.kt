package com.openandroidintelligence.mobile.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gateway 教程内容测试。
 *
 * 这些用例是「教程不能虚构」的自动防线：任何一条命令被改成凭空捏造的样子，
 * 或锁定提交被随手替换，测试会立刻失败。
 *
 * 教程内容的来源在 [com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials]
 * 的类注释里逐条列明（项目 README + 各插件仓库的实际检出）。
 */
class GatewayTutorialsTest {

    @Test
    fun `three gateways are covered`() {
        val all = com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.ALL
        assertEquals(3, all.size)
        assertEquals(
            listOf("hermes", "openclaw", "dsh"),
            all.map { it.id },
        )
    }

    @Test
    fun `tutorial ids are unique and non blank`() {
        val ids = com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.ALL.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    /** 每套教程至少三步，且最后一步必须告诉用户「怎么验证成功」。 */
    @Test
    fun `every tutorial ends with a verification hint`() {
        com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.ALL.forEach { tutorial ->
            assertTrue(
                "${tutorial.id} 应有至少 3 步",
                tutorial.steps.size >= 3,
            )
            val last = tutorial.steps.last()
            assertTrue(
                "${tutorial.id} 最后一步必须给出验证方式",
                !last.verifyHint.isNullOrBlank(),
            )
        }
    }

    /** 官方仓库链接必须指向本项目的 GitHub 组织。 */
    @Test
    fun `repository links point at the project org`() {
        com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.ALL.forEach { tutorial ->
            assertTrue(
                "${tutorial.id} 的仓库链接异常：${tutorial.repoUrl}",
                tutorial.repoUrl.startsWith("https://github.com/1rua/"),
            )
        }
    }

    /** 命令必须是可直接粘贴的纯文本：不允许全角字符混进来。 */
    @Test
    fun `commands are paste ready ascii`() {
        com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.ALL.forEach { tutorial ->
            tutorial.steps.forEach { step ->
                step.terminal?.let { block ->
                    assertTrue(
                        "${tutorial.id} / ${step.title} 的命令为空",
                        block.command.isNotBlank(),
                    )
                    assertTrue(
                        "${tutorial.id} / ${step.title} 的命令含全角字符",
                        block.command.none { it.code in 0xFF01..0xFF5E || it == '\u3000' },
                    )
                    // 语境标签决定「在哪执行」，不能缺失。
                    assertTrue(block.label.isNotBlank())
                }
            }
        }
    }

    /** Hermes：安装命令与锁定提交必须与项目 README 一致。 */
    @Test
    fun `hermes commands match the pinned commit`() {
        val hermes = com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.HERMES
        val joined = hermes.steps.mapNotNull { it.terminal?.command }.joinToString("\n")

        assertTrue("缺少 hermes plugins install", joined.contains("hermes plugins install"))
        assertTrue(
            "锁定提交不匹配 README",
            joined.contains("190441415652ae34fbd1cb8cc485f6208fff944b"),
        )
        assertTrue("缺少 --enable", joined.contains("--enable"))
        assertTrue("缺少 gateway setup", joined.contains("hermes gateway setup"))
        assertTrue(
            "缺少账号创建命令",
            joined.contains("hermes open-android-intelligence account create"),
        )
        assertTrue("缺少状态验证", joined.contains("hermes open-android-intelligence status"))
    }

    /** OpenClaw：安装命令、锁定提交与主密钥环境变量。 */
    @Test
    fun `openclaw commands match the pinned commit`() {
        val openclaw = com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.OPENCLAW
        val joined = openclaw.steps.mapNotNull { it.terminal?.command }.joinToString("\n")

        assertTrue(joined.contains("openclaw plugins install"))
        assertTrue(
            "锁定提交不匹配 openclaw-plugin-pin.json",
            joined.contains("c7c087b9fe28adcc658a1cbdb71689a377b65214"),
        )
        assertTrue(
            "缺少主密钥环境变量",
            joined.contains("OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE"),
        )
        assertTrue(
            "缺少运行状态核验",
            joined.contains("openclaw plugins inspect"),
        )
    }

    /** DSH：构建、Bundle 安装与一次性凭据路径。 */
    @Test
    fun `dsh commands cover build install and credentials`() {
        val dsh = com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.DSH
        val joined = dsh.steps.mapNotNull { it.terminal?.command }.joinToString("\n")

        assertTrue(joined.contains("npm ci"))
        assertTrue(joined.contains("npm run build"))
        assertTrue(joined.contains("plugin_manager action=install_bundle"))
        assertTrue(joined.contains("setup-account.json"))
    }

    /** 未知 id 回退到第一个教程，保证界面永远有内容而不是空白页。 */
    @Test
    fun `unknown gateway id falls back to the first tutorial`() {
        val fallback = com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.byId("does-not-exist")
        assertEquals(
            com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.ALL.first().id,
            fallback.id,
        )
        assertEquals(
            "hermes",
            com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.byId(null).id,
        )
    }

    /** 元数据 chip 必须非空：它们是用户判断「版本是否匹配」的依据。 */
    @Test
    fun `every tutorial declares metadata chips`() {
        com.openandroidintelligence.mobile.onboarding.data.GatewayTutorials.ALL.forEach { tutorial ->
            assertTrue("${tutorial.id} 缺少元数据 chip", tutorial.chips.isNotEmpty())
            assertTrue(tutorial.tagline.isNotBlank())
            assertTrue(tutorial.displayName.isNotBlank())
            assertTrue(tutorial.shortName.isNotBlank())
        }
    }
}
