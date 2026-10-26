package com.openandroidintelligence.mobile.onboarding.data

import androidx.compose.runtime.Immutable

/**
 * 一个 Gateway 的部署教程。
 *
 * 教程是**数据**而不是界面代码：新增一个 Agent 宿主只需在 [GatewayTutorials.ALL]
 * 里追加一个常量，Tab、页面、空态全部自动生成，不需要改任何 Composable。
 *
 * 约束（避免教程变成不可维护的说明书）：
 * 1. [steps] 只覆盖「从零到能登录」必需的步骤；高级配置一律不展开。
 * 2. [TerminalBlock.command] 必须是可直接粘贴执行的**纯文本**，不做语法高亮分词，
 *    否则复制会带入标记。
 * 3. 所有命令与参数都必须能在官方仓库/项目 README 里找到出处，禁止臆造。
 */
@Immutable
data class GatewayTutorial(
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

/**
 * 项目当前实际支持的全部 Agent Gateway 教程。
 *
 * 内容来源（2026-10 核实）：
 * - 项目 README.md「为 Agent 宿主安装网关插件」一节；
 * - `openclaw-plugin-pin.json` 固定的版本与提交；
 * - 对 `1rua/hermes-gateway-plugin`、`1rua/openclaw-gateway-plugin`、
 *   `1rua/dsh-android-gateway` 三个仓库的实际检出与 `package.json` 核对。
 *
 * 版本号与提交 SHA 会随插件升级失效，届时只需改这里一处。
 */
object GatewayTutorials {

    /** Hermes Agent：Python 宿主，插件按提交 SHA 锁定。 */
    val HERMES = GatewayTutorial(
        id = "hermes",
        shortName = "Hermes",
        displayName = "Hermes Agent",
        tagline = "Python 原生宿主。插件以固定提交锁定，与当前 App 版本严格对应。",
        chips = listOf("Python 3.11+", "Protocol v2.1", "插件 v2.2.0"),
        repoUrl = "https://github.com/1rua/hermes-gateway-plugin",
        steps = listOf(
            TutorialStep(
                title = "确认 Hermes 已安装",
                body = "在宿主机上执行下面的命令，能正常输出版本号即可。需要 Python 3.11+，" +
                    "并确保手机能访问这台主机（局域网或 Tailscale）。",
                terminal = TerminalBlock(
                    label = "Terminal · 校验环境",
                    command = "hermes --version",
                ),
            ),
            TutorialStep(
                title = "安装并启用 Gateway 插件",
                body = "用与当前 App 版本锁定的提交安装。--enable 会在同一步完成启用，" +
                    "不需要再单独执行 enable。",
                terminal = TerminalBlock(
                    label = "Terminal · 安装插件",
                    command = "hermes plugins install 1rua/hermes-gateway-plugin \\\n" +
                        "    --ref 190441415652ae34fbd1cb8cc485f6208fff944b \\\n" +
                        "    --enable",
                ),
            ),
            TutorialStep(
                title = "生成主密钥并完成网关配置",
                body = "这条命令会引导你生成 32 字节主密钥文件（权限 0600）并写入网关配置。" +
                    "消息正文用它做对称加密，请勿提交到版本库。",
                terminal = TerminalBlock(
                    label = "Terminal · 配置网关",
                    command = "hermes gateway setup",
                ),
            ),
            TutorialStep(
                title = "创建手机登录账号",
                body = "为手机端单独创建一个账号，把 my_user 和密码换成你自己的。" +
                    "--confirm-local 表示确认这是本机操作。",
                terminal = TerminalBlock(
                    label = "Terminal · 创建账号",
                    command = "hermes open-android-intelligence account create \\\n" +
                        "    -u my_user -p '你的密码' --confirm-local",
                ),
            ),
            TutorialStep(
                title = "验证网关已就绪",
                body = "确认插件已加载、账号存在、协议版本匹配。三项都正常后，就可以回手机登录了。",
                terminal = TerminalBlock(
                    label = "Terminal · 验证状态",
                    command = "hermes open-android-intelligence status\n" +
                        "hermes open-android-intelligence contract status",
                ),
                verifyHint = "输出里应能看到插件已启用、账号列表包含刚创建的账号、协议版本为 2.1。",
            ),
        ),
    )

    /** OpenClaw：Node.js 宿主，主密钥走环境变量。 */
    val OPENCLAW = GatewayTutorial(
        id = "openclaw",
        shortName = "OpenClaw",
        displayName = "OpenClaw",
        tagline = "Node.js 宿主。插件通过固定提交安装，账号隔离与配对由插件自带的 Gateway Core 负责。",
        chips = listOf("Node.js 24.x", "Protocol v2.1", "插件 v1.1.0"),
        repoUrl = "https://github.com/1rua/openclaw-gateway-plugin",
        steps = listOf(
            TutorialStep(
                title = "准备主密钥文件",
                body = "生成一个 32 字节的随机密钥文件，并把权限设为 0600。" +
                    "这是加密存储消息正文的根密钥，不要放进版本库。" +
                    "命令带存在性判断：**密钥已存在时不会覆盖**——换掉密钥会让历史消息无法解密。",
                terminal = TerminalBlock(
                    label = "Terminal · 生成主密钥",
                    // 必须防覆盖：直接 `> 文件` 每次执行都会换一个新密钥，
                    // 而网关已用它加密过历史消息 —— 换掉之后旧消息永久无法解密。
                    // 这里先判断文件是否存在，只在缺失时生成。
                    command = "test -f ~/gateway-master-key.bin || " +
                        "head -c 32 /dev/urandom > ~/gateway-master-key.bin\n" +
                        "chmod 600 ~/gateway-master-key.bin",
                ),
            ),
            TutorialStep(
                title = "告知 OpenClaw 密钥位置",
                body = "通过环境变量指定密钥文件路径。这一条要在**启动 OpenClaw 的那个环境**里生效，" +
                    "改了之后需要重启宿主。",
                terminal = TerminalBlock(
                    label = "Environment",
                    // 必须写在同一行：`export VAR=\\` 后接换行会保留下一行的缩进，
                    // 使 $HOME/... 变成独立参数而非变量值，Bash 报 not a valid identifier。
                    command = "export OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE=" +
                        "\$HOME/gateway-master-key.bin",
                ),
            ),
            TutorialStep(
                title = "安装 Gateway 插件",
                body = "按固定提交安装，避免版本漂移。插件会随安装自动同步契约快照，" +
                    "启动时不再联网下载。",
                terminal = TerminalBlock(
                    label = "Terminal · 安装插件",
                    command = "openclaw plugins install \\\n" +
                        "    git:github.com/1rua/openclaw-gateway-plugin@c7c087b9fe28adcc658a1cbdb71689a377b65214",
                ),
            ),
            TutorialStep(
                title = "核验插件运行状态",
                body = "检查插件是否已被运行时加载。JSON 输出里能看到插件状态与协议版本。",
                terminal = TerminalBlock(
                    label = "Terminal · 检查插件",
                    command = "openclaw plugins inspect \\\n" +
                        "    open-android-intelligence-gateway --runtime --json",
                ),
                verifyHint = "JSON 中插件状态为已加载，且协议版本显示 2.1。",
            ),
        ),
    )

    /** DSH (DeepSeek Harness)：独立 Bundle 插件，复用 DSH 原生 Web 端口。 */
    val DSH = GatewayTutorial(
        id = "dsh",
        shortName = "DSH",
        displayName = "DSH (DeepSeek Harness)",
        tagline = "独立 Bundle 插件，复用 DSH 原生 Web 端口，无需额外守护进程。",
        chips = listOf("Node.js 24.13+", "Protocol v2.1", "插件 v0.2.0"),
        repoUrl = "https://github.com/1rua/dsh-android-gateway",
        steps = listOf(
            TutorialStep(
                title = "克隆并构建插件",
                body = "插件以 TypeScript 源码分发，需要先本地编译出一个可安装的 Bundle。",
                terminal = TerminalBlock(
                    label = "Terminal · 构建插件",
                    command = "git clone https://github.com/1rua/dsh-android-gateway.git\n" +
                        "cd dsh-android-gateway\n" +
                        "npm ci\n" +
                        "npm run build",
                ),
            ),
            TutorialStep(
                title = "在 DSH 中安装 Bundle",
                body = "在 DSH 会话里执行安装指令。target 填上一步构建产物的**绝对路径**。",
                terminal = TerminalBlock(
                    label = "DSH · 安装 Bundle",
                    command = "plugin_manager action=install_bundle \\\n" +
                        "    target=/absolute/path/to/dsh-android-gateway",
                ),
            ),
            TutorialStep(
                title = "获取首次登录凭据",
                body = "首次启动且账号表为空时，插件会自动生成一次性凭据到 " +
                    "\$DSH_HOME/dsh-android-gateway/setup-account.json（权限 0600）。" +
                    "其中的用户名与密码就是手机端要填的内容。",
                terminal = TerminalBlock(
                    label = "Terminal · 读取凭据",
                    command = "cat \$DSH_HOME/dsh-android-gateway/setup-account.json",
                ),
            ),
            TutorialStep(
                title = "登录并清理临时凭据",
                body = "手机登录成功后，请及时删除这个临时文件。网关地址填 DSH Web 界面地址即可。",
                terminal = TerminalBlock(
                    label = "Terminal · 清理",
                    command = "rm \$DSH_HOME/dsh-android-gateway/setup-account.json",
                ),
                verifyHint = "登录成功后设备公钥随注册自动生成，后续请求都用 Ed25519 签名，" +
                    "不需要再输密码。",
            ),
        ),
    )

    /** 全部教程，顺序即 Tab 顺序。 */
    val ALL: List<GatewayTutorial> = listOf(HERMES, OPENCLAW, DSH)

    /** 按 id 取教程；找不到时回退到第一个，保证界面永远有内容可显示。 */
    fun byId(id: String?): GatewayTutorial =
        ALL.firstOrNull { it.id == id } ?: ALL.first()
}
