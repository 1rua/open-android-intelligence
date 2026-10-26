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
 * 新部署默认使用 OAI Core。OpenClaw 保留旧 Gateway 兼容教程。
 * Core 命令来源：仓库 oai-core/README.md；OpenClaw 来源：openclaw-plugin-pin.json。
 * 旧 Hermes/DSH 教程 id 恢复时由 byId 回退到 Core，不迁移账号或设备密钥。
 */
object GatewayTutorials {

    /** 手机只登录 Core；Agent 接入在同一服务端配置中完成。 */
    val CORE = GatewayTutorial(
        id = "core",
        shortName = "OAI Core",
        displayName = "OAI Core",
        tagline = "一个服务端地址连接手机，统一管理设备调用与已接入 Agent 的原生会话。",
        chips = listOf("Python 3.11+", "Protocol v2.1", "Core v0.1.0"),
        repoUrl = "https://github.com/1rua/open-android-intelligence/tree/dev/oai-core",
        steps = listOf(
            TutorialStep(
                title = "安装 OAI Core 与 CLI",
                body = "在服务端主机创建独立 Python 环境。Core、CLI 与 MCP 来自同一个项目。",
                terminal = TerminalBlock(
                    label = "Terminal · 安装 Core",
                    command = "git clone --branch dev https://github.com/1rua/open-android-intelligence.git\n" +
                        "cd open-android-intelligence\n" +
                        "python3 -m venv .venv\n" +
                        ".venv/bin/python -m pip install -e ./oai-core",
                ),
            ),
            TutorialStep(
                title = "配置一个手机可访问的地址",
                body = "编辑 oai-core/examples/core.json。示例仅监听本机；远程连接前须配置 " +
                    "listen.host 和 TLS 证书 tls.certFile、tls.keyFile。agents 数组只填写明确接入的 " +
                    "Hermes、DSH 或 Codex 实例；具体适配器配置见官方文档。手机始终连接 Core 的同一个地址与端口。",
            ),
            TutorialStep(
                title = "创建 OAI 登录账号",
                body = "密码通过交互输入。设备能力逐账号授权；Hermes 会话保留账号隔离，" +
                    "DSH 和 Codex 在此部署中共享可读取的原生会话。",
                terminal = TerminalBlock(
                    label = "Terminal · 创建账号",
                    command = ".venv/bin/oai account create --config oai-core/examples/core.json --username alice",
                ),
            ),
            TutorialStep(
                title = "启动统一服务",
                body = "此服务维护手机连接、账号验证和设备请求状态。切换 Agent 不需要另开手机连接。",
                terminal = TerminalBlock(
                    label = "Terminal · 启动 Core",
                    command = ".venv/bin/oai serve --config oai-core/examples/core.json",
                ),
            ),
            TutorialStep(
                title = "登录并查看已接入的 Agent",
                body = "手机填写配置好的 Core 地址、OAI 账号与密码。登录后查看真实在线状态与原生会话；" +
                    "设备插件在设置中独立安装和授权。",
                verifyHint = "登录后展示此 Core 已明确接入的 Agent；未接入时显示空列表。" +
                    "不支持的会话操作会禁用，切换 Agent 不会重置手机连接。",
            ),
        ),
    )

    /** OpenClaw：Node.js 宿主，主密钥走环境变量。 */
    val OPENCLAW = GatewayTutorial(
        id = "openclaw",
        shortName = "OpenClaw 兼容",
        displayName = "OpenClaw",
        tagline = "旧 Gateway 兼容入口。本阶段不参与 Core 的 Agent 适配器重构，继续按原方式连接。",
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

    /** 全部教程，顺序即 Tab 顺序。 */
    val ALL: List<GatewayTutorial> = listOf(CORE, OPENCLAW)

    /** 按 id 取教程；找不到时回退到第一个，保证界面永远有内容可显示。 */
    fun byId(id: String?): GatewayTutorial =
        ALL.firstOrNull { it.id == id } ?: ALL.first()
}
