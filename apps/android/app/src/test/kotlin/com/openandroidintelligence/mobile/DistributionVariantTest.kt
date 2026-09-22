package com.openandroidintelligence.mobile

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
 * 单元测试运行在 full 变体上，只能锁定本变体可观察的事实。
 */
class DistributionVariantTest {

    @Test
    fun theFullVariantBuildAllowsRuntimePluginsAndTrustMode() {
        assertTrue(BuildConfig.ALLOW_RUNTIME_PLUGINS)
        assertTrue(BuildConfig.ALLOW_DEVELOPER_TRUST_MODE)
    }
}
