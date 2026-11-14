package com.openandroidintelligence.mobile.onboarding.data

import androidx.compose.runtime.Immutable
import com.openandroidintelligence.ui.resources.R

/**
 * Remote 登录与 Host 发现指引。
 *
 * 教程是**数据**而不是界面代码：调整 Remote 指引只需在 [RemoteTutorials.all]
 * 里追加一个常量，Tab、页面、空态全部自动生成，不需要改任何 Composable。
 *
 * 约束（避免教程变成不可维护的说明书）：
 * 1. [steps] 只覆盖「从零到能登录」必需的步骤；高级配置一律不展开。
 * 2. [TerminalBlock.command] 必须是可直接粘贴执行的**纯文本**，不做语法高亮分词，
 *    否则复制会带入标记。
 * 3. 所有命令与参数都必须能在官方仓库/项目 README 里找到出处，禁止臆造。
 */
@Immutable
data class RemoteTutorial(
    /** 稳定标识，同时用于持久化「上次查看的教程」。 */
    val id: String,
    /** Tab 上的短名。 */
    val shortName: String,
    /** 标题里的完整名称。 */
    val displayName: String,
    /** 一句话定位：它是什么、装在哪。 */
    val tagline: String,
    /** 三项元数据 chip：语言/运行环境、协议版本、插件版本。 */
    val chips: List<String>,
    /** 官方仓库或详细文档。 */
    val repoUrl: String,
    val steps: List<TutorialStep>,
)

/**
 * 教程中的一步。
 *
 * @param title 步骤标题（一句话说清「做什么」）。
 * @param body 补充说明，只放这一小步需要知道的上下文。
 * @param terminal 该步要执行的命令；没有命令的步骤为 null。
 * @param verifyHint 只有验证步骤才填：告诉用户「成功是什么样子」，
 *   避免用户执行完不知道对不对。
 */
@Immutable
data class TutorialStep(
    val title: String,
    val body: String,
    val terminal: TerminalBlock? = null,
    val verifyHint: String? = null,
)

/**
 * 终端代码块。
 *
 * @param label 代码框左上角的语境标签，例如「Terminal · 安装插件」「DSH · 安装 Bundle」。
 *   这个标签不是装饰：它告诉用户这条命令要在**哪个环境**里执行。
 * @param command 纯文本命令，允许多行与续行反斜杠。
 */
@Immutable
data class TerminalBlock(
    val label: String,
    val command: String,
)

/** 手机登录与 Host 发现步骤，不包含旧 Core 的部署、账号管理或配对命令。 */
object RemoteTutorials {
    fun all(context: android.content.Context): List<RemoteTutorial> {
        fun text(id: Int) = context.getString(id)
        return listOf(RemoteTutorial(
            id = "remote", shortName = "Remote", displayName = "DSH Remote",
            tagline = text(R.string.remote_guide_tagline),
            chips = listOf(text(R.string.remote_guide_chip_account), text(R.string.remote_guide_chip_host), text(R.string.remote_guide_chip_authorization)),
            repoUrl = "https://github.com/liguobao/ds-harness-remote",
            steps = listOf(
                TutorialStep(text(R.string.remote_guide_login_title), text(R.string.remote_guide_login_body)),
                TutorialStep(text(R.string.remote_guide_discovery_title), text(R.string.remote_guide_discovery_body)),
                TutorialStep(text(R.string.remote_guide_workspace_title), text(R.string.remote_guide_workspace_body)),
                TutorialStep(text(R.string.remote_guide_authorization_title), text(R.string.remote_guide_authorization_body),
                    verifyHint = text(R.string.remote_guide_verify)),
            ),
        ))
    }
    fun byId(context: android.content.Context, id: String?): RemoteTutorial =
        all(context).let { guides -> guides.firstOrNull { it.id == id } ?: guides.first() }
}
