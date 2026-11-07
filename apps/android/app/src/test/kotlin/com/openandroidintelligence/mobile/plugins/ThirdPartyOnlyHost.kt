package com.openandroidintelligence.mobile.plugins

import android.content.Context
import com.openandroidintelligence.kernel.DeveloperTrustMode
import com.openandroidintelligence.kernel.NativePluginLoader
import com.openandroidintelligence.kernel.PairingGrantStateHolder

/**
 * 只加载第三方插件的宿主，用于保持既有第三方插件测试的语义。
 *
 * 生产宿主启动时会把 APK 内置的官方能力直接注册好。如果单元测试也用默认构造，
 * 官方能力就会先于测试自己的安装步骤出现，`PLUGIN_NOT_INSTALLED`、`未启用`、
 * 卸载等断言随之失效——于是测试会依赖“本机恰好没有打包内置资产”这一偶然条件。
 *
 * 需要验证「普通安装/启用/卸载」链路的测试显式用这个工厂：内建包一律读不到，
 * 宿主行为与改造前一致。内建能力本身的装配由 BuiltInCapabilitiesTest 覆盖。
 */
internal fun thirdPartyOnlyHost(
    context: Context,
    grants: PairingGrantStateHolder,
    trustMode: DeveloperTrustMode,
    nativeLoader: NativePluginLoader,
    installationId: String,
): ProductionPluginHost = ProductionPluginHost(
    context, grants, trustMode, nativeLoader, installationId,
    builtInPackages = BuiltInPackageReader { plugin -> throw BundledPluginUnavailable(plugin) },
)
