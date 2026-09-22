package com.openandroidintelligence.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渠道策略真实生效（条目 2-12）：
 *
 * 构建变体通过 BuildConfig 声明策略；策略不再由零引用的 `DistributionPolicy`
 * 数据类承载，而是由组合根原样传入设置环境，并控制插件管理区域的安装入口
 * （full 允许 / play 禁止——入口可用性与禁用说明由
 * PluginManagementScreenTest 的两个门控用例锁定）。
 *
 * 同一份测试源集会在 full 与 play 两个变体上各跑一遍，因此按当前变体
 * 锁定各自的既定事实，而不是只写 full 一侧。
 */
class DistributionVariantTest {

    @Test
    fun theChannelPolicyMatchesTheDeclaredVariant() {
        when (BuildConfig.FLAVOR) {
            "full" -> {
                assertTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
                assertTrue(BuildConfig.ALLOW_DEVELOPER_TRUST_MODE)
            }
            "play" -> {
                assertFalse(BuildConfig.ALLOW_RUNTIME_PLUGINS)
                assertFalse(BuildConfig.ALLOW_DEVELOPER_TRUST_MODE)
            }
            else -> throw AssertionError("未知的分发变体: ${BuildConfig.FLAVOR}")
        }
    }
}
