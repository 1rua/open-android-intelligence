package com.openandroidintelligence.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 主 App 的完整工作台；助理入口从这里回到 App 内。 */
internal const val MAIN_ACTIVITY = "com.openandroidintelligence.mobile.MainActivity"

/** 交接深链使用的 action：与主进程 `ReplyNotificationTarget` 的 `REPLY_ACTION` 一字不差。 */
internal const val ASSISTANT_REPLY_ACTION = "com.openandroidintelligence.mobile.OPEN_AGENT_REPLY"

/** 深链会话段的最大长度：超过就当非法的外部输入处理，不构造 Intent。 */
private const val MAX_DEEP_LINK_SEGMENT = 512

/**
 * 从助理进程打开主 App。
 *
 * 助理会话没有 Activity 栈，必须带 `NEW_TASK`。
 */
internal fun openAssistantMainApp(context: Context): Boolean =
    launchMainActivity(context, handoffIntent = null)

/**
 * 交接成功后打开主 App，并带上被接管会话的深链身份。
 *
 * 复用既有 `oai-reply://conversation/{profile}/{account}/{conversation}[/{agent}]` 形状与
 * `REPLY_ACTION`：主 App 的解析路径与 `pendingReply` 机制因此完全不变，不需要新的
 * action 或新的白名单。额外的 `EXTRA_SESSION_TOKEN` 只用于让主进程核对「这就是刚
 * 交接的那个助手会话」，不是路由的一部分。
 *
 * [handoff] 为空（未连接、旧版主进程）时退回不带身份的入口：界面仍能打开，只是不会
 * 跳到具体会话 —— 调用方在严格交接路径上不会走到这里。
 */
internal fun openAssistantMainApp(context: Context, handoff: AssistantHandoffLink?): Boolean =
    launchMainActivity(context, handoff?.toIntent(context))

/**
 * 启动主 App，优先走 `VoiceInteractionSession.startAssistantActivity`。
 *
 * 为什么不能只用 `context.startActivity`：助手浮层不是「当前可见的应用窗口」，
 * 直接 `startActivity` 会落在 Android 10+ 的后台启动（BAL）限制里——系统拒绝、
 * 既不抛异常也没有任何提示，表现正是「横条上滑动画播完了，什么都没发生」。
 * `startAssistantActivity` 由 `IVoiceInteractionSession` 委托给系统执行，是助理
 * 启动 Activity 的官方通道，不受 BAL 限制。
 *
 * 仍然保留 `startActivity` 回退：ASSIST 兜底页（`AssistantActivity`）有自己的
 * Activity，用普通启动即可；两条路径都失败时如实返回 false，交给界面提示。
 *
 * @param session 系统助理会话；为 null 时（兜底页）用普通 `startActivity`。
 */
internal fun launchMainActivity(
    context: Context,
    handoffIntent: Intent?,
    session: android.service.voice.VoiceInteractionSession? = null,
): Boolean {
    val target = (handoffIntent ?: Intent().setClassName(context.packageName, MAIN_ACTIVITY))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (session != null) {
        val viaSession = runCatching { session.startAssistantActivity(target) }.isSuccess
        AssistantDiagnostics.handoff(
            "launch",
            "startAssistantActivity=${if (viaSession) "ok" else "threw"} data=${target.data}",
        )
        if (viaSession) return true
    }
    val viaContext = runCatching { context.startActivity(target) }.isSuccess
    AssistantDiagnostics.handoff("launch", "startActivity=$viaContext data=${target.data}")
    return viaContext
}

/**
 * 交接后用于打开主 App 的深链（与主进程 `ReplyNotificationTarget` 的线上形状一致）。
 *
 * @param conversationId 被接管的会话；尚未产生会话时为 null（只打开主 App 的当前输入栏）。
 * @param sessionToken 助手会话身份，主进程据此确认「这就是刚交接的会话」。
 */
internal data class AssistantHandoffLink(
    val profileId: String,
    val accountId: String,
    val conversationId: String?,
    val agentId: String?,
    val sessionToken: String,
) {
    fun toIntent(context: Context): Intent {
        val builder = Uri.Builder()
            .scheme("oai-reply")
            .authority("conversation")
            .appendPath(profileId)
            .appendPath(accountId)
        // 没有会话时用占位段落让深链保持四段合法形状？不：主 App 会把它当成真实会话。
        // 因此没有会话时只带 action + session token，由主 App 停在自己的输入栏。
        conversationId?.let(builder::appendPath)
        agentId?.let(builder::appendPath)
        val intent = Intent()
            .setClassName(context.packageName, MAIN_ACTIVITY)
            .setAction(ASSISTANT_REPLY_ACTION)
            .putExtra(EXTRA_SESSION_TOKEN, sessionToken)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (conversationId != null) intent.data = builder.build()
        return intent
    }

    companion object {
        const val EXTRA_SESSION_TOKEN = "com.openandroidintelligence.assistant.SESSION_TOKEN"

        /**
         * 从主进程下发的交接身份构造深链；身份不合法（空白、含路径分隔符）时返回 null。
         *
         * 深链是**外部可构造**的入口：主 App 已按 `oai-reply://conversation/a/b/c[/d]`
         * 的形状解析，这里不添加任何新的段，也不把用户输入拼进 authority。
         */
        fun from(
            profileId: String,
            accountId: String,
            conversationId: String?,
            agentId: String?,
            sessionToken: String,
        ): AssistantHandoffLink? {
            fun valid(value: String) = value.isNotBlank() && value.length <= MAX_DEEP_LINK_SEGMENT &&
                '/' !in value && value.none { it.isWhitespace() }
            if (!valid(profileId) || !valid(accountId) || !valid(sessionToken)) return null
            if (conversationId != null && !valid(conversationId)) return null
            if (agentId != null && !valid(agentId)) return null
            return AssistantHandoffLink(profileId, accountId, conversationId, agentId, sessionToken)
        }
    }
}
