package com.openandroidintelligence.mobile

import android.content.Context
import com.openandroidintelligence.gateway.http.GatewayEndpoint

/**
 * 登录页的 Core 地址预填。
 *
 * 只保存一个地址字符串，不保存账号、密码或任何凭据：它的用途仅仅是「换机后
 * 不用再手输一遍服务器地址」。它与 [GatewayRuntime] 里的 `last_gateway_url`
 * 刻意分开——后者属于会话恢复路径，登出或解除配对会被清理；这里的东西在
 * 用户显式改地址或被设置导入覆盖之前一直保留。
 */
object LoginGatewayPrefill {
    private const val PREFERENCES_NAME = "open_android_intelligence_login"
    private const val KEY_PREFERRED_GATEWAY = "preferred_gateway_url"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** 读取预填地址；存储里不是合法 http/https 地址时返回 null，不把坏值交给输入框。 */
    fun read(context: Context): String? = preferences(context).getString(KEY_PREFERRED_GATEWAY, null)
        ?.takeIf { it.isNotBlank() }
        ?.takeIf { GatewayEndpoint.parse(it) != null }

    /** 写入预填地址；非法地址直接拒绝，不落盘。 */
    fun write(context: Context, gatewayUrl: String): Boolean {
        if (GatewayEndpoint.parse(gatewayUrl) == null) return false
        return preferences(context).edit().putString(KEY_PREFERRED_GATEWAY, gatewayUrl).commit()
    }

    fun clear(context: Context): Boolean = preferences(context).edit().remove(KEY_PREFERRED_GATEWAY).commit()

    /** 用户真正登录成功时记录地址：下次登录页直接带出来，无需额外操作。 */
    fun remember(context: Context, gatewayUrl: String) {
        if (GatewayEndpoint.parse(gatewayUrl) == null) return
        preferences(context).edit().putString(KEY_PREFERRED_GATEWAY, gatewayUrl).commit()
    }
}
