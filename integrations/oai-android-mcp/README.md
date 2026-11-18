# 手机 OAI MCP

该插件只暴露 `android_*` 手机能力，经已安装 DSH Remote 的公开 `runSessionTool` 连接
`harness / phone-capabilities`。不会选择电脑工作区，不提供跨 Agent 委派或任意 RPC。
协议基准为 `liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`。

将该目录作为 DSH Cordis 插件安装，配置 `privateConfigPath` 指向用户私有的 MCP 配置文件。
插件通过 DSH 注入的 `dshRemoteClient`、`connection`、`webServer` 注册已有本机服务的
`/oai-android-mcp/v1` 路由；生成目录权限为 `0700`、配置权限为 `0600`。
把生成的 `mcpServers.oai-android` 放入外部 Agent 的原生 MCP 配置。Stdio 只访问该桥，
不用复制 Remote token 或私钥。注销、切服务及卸载后需要重新生成配置。
面向智能体的调用工作流与参数约束见 [OAI Phone Control Skill](../skills/oai-phone-control/SKILL.md)。

调用 `android_list_devices` 确定手机目标，首次读树或截图可省略 taskSessionId 并返回新任务；
后续必须传同一 taskSessionId，动作同时传 snapshotId。等待手机批准时返回
`awaiting_confirmation`，通过 `android_request_result` 继续读取当前任务正文，
通过 `android_request_status` 查询最小持久回执，或 `android_cancel_request` 精确取消原请求。
停止观察、Stdio 退出和本机 HTTP 超时均不代表手机请求已取消。

`android_read_delivery` 与 `android_ack_delivery` 的工具参数是固定手机目标，加上
`request_id`、可选 `taskSessionId`/`resume`、`deliveryId`、`eventId` 和 `payloadDigest`；不接受 token 或自报主体。
`taskSessionId`只指定原手机任务，新ACK使用新request_id并可复用读取任务；它不表示恢复旧请求。
同进程重试相同request_id只查询不重发。桥重启或结果未知时优先使用已有`android_request_result`；
也可传原taskSessionId、原request_id与`resume:true`只查询，即使未找到结果也不发送业务请求。
默认`resume:false`表示首次提交新的业务请求，不能拿它重试未知副作用。
普通推送 prompt 只含公开引用，不能据此直接读取。首次取件由手机本机可信确认展示实际来访
Client/pin 与手机已保存的精确目标 Host/pin/backend/workspace/session，批准后绑定此目标的实际收件主体。
确认表示用户授予该设备访问目标推送的许可，不表示已证明该 Client 来自目标会话。
后续同主体按手机已有独立推送授权自动取件；配置、账号、身份变化和急停需要重新授权，
正常手动切换当前聊天不改投旧事件。

读取不会确认消费。处理完事件后调用 `android_ack_delivery`，由手机核对同主体、事件和摘要，
原子清理该端点正文；重复 ACK 仅返回元数据。prompt受理、模型完成、MCP超时均不是消费确认，
未知结果不自动重发通知。正文/历史/流回读仍受当前手机授权控制；该机制不创建电脑workspace，
不提供后台跨workspace派发，也不新增服务监听。

短信发送及 SoM 仅在手机真实提供者、授权和本机确认落实后可用；当前缺少的能力返回明确错误。
在项目根目录执行 `npm run test:phone-mcp:unit`，使用真实 MCP SDK Stdio 客户端检验列工具与调用；
它只覆盖 Node 单元回归。完整准入执行 `npm run test:phone-mcp`，同时覆盖下述 Kotlin 原工具互通。
平台数据使用隔离替身，不能作为真机验收。

原生手机屏幕工具直接进入 Kotlin 内核目录，保留独立的读树、截图和控制权限。确认与急停
通过 `TYPE_ACCESSIBILITY_OVERLAY` 显示，所有远端节点、坐标与穿越胶囊的手势均拒绝。
动作完成后保持手机全局执行权，等待 400ms 布局静默或 3000ms 硬期限后返回最新观测；
动作执行结果与界面是否稳定分别报告，超时不会重试动作。自动观测仍要求独立的只读许可。
SoM 使用 Android 34 起的窗口截图排除胶囊，并以本地 Canvas 标记真实可交互节点、输出 WebP；
较低系统版本因无法证明可靠排除而返回 `SCREEN_OVERLAY_EXCLUSION_UNSUPPORTED`。该能力边界
须在真机继续验收，不能以本地构建或隔离截图字节证明支持所有 Android 31–36 设备。

固定上游互通探针由项目根目录的完整命令准备当前源码的 Android JVM 类再运行：

```bash
npm run test:phone-mcp:interop
```

命令需要 JDK 21、Android SDK 和已安装的 MCP 依赖，复用 `GRADLE_USER_HOME`（未设置时使用 Gradle 默认缓存）。
先执行 `:gateway-client:compileDebugUnitTestKotlin`，由 Gradle 核对当前源码与依赖；编译失败不运行旧产物，
缺类、缺运行依赖、Java 提前退出或 RPC 超时均失败。随后加载有 SHA-256 和许可证记录的未修改上游源码。
探针覆盖真实手机门面和控制总线；系统采集器与账号只使用隔离数据，不连接日常 Host 或账号。
