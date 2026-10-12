# 附件与流式回复的实机反馈补修

## 用户反馈与范围

Android 基线为 `f82fc3390911b6aec4c1ed8b967225b1d755916b`。用户已确认侧边栏退出和普通消息收到真实回复；本机与 `47.251.8.196` 的所有附件仍显示“无法上传 / 附件完整性校验失败”，回复尚无逐步显示。切换会话及清理后台后的消息保留没有新的单独实机确认。

本轮修复附件上传媒体类型，并对独立 Hermes 插件的原生流式接口及 Android 两种事件通道补充回归。既有退出、发送记录、账号、配对、加密格式及核心 Schema 均未改变；未操作手机。

## 两仓改动与固定联调版本

Android 上传内容时遗漏 `Content-Type`，平台实际补入的表单类型与创建附件时声明的图片或文件类型不符，Hermes 因此以 `ATTACHMENT_DIGEST_MISMATCH` 拒绝正确的正文。本轮补入所选附件的媒体类型。详细差分、调用链与旧加密修复的边界见 [附件修复记录](2026-10-12-attachment-content-type-bugfix.md)。

`GatewayConnectionFactory` 增加可选连接创建函数，默认仍为平台 `URL.openConnection`；工厂继续设置协议、超时和禁止重定向，传输层仍在发送正文前通过 `GatewayConnectionSecurity` 核验 TLS。新增 Android 平台回归借此调用实际 `HttpURLConnectionImpl`，无需改动全局 URL 工厂。

Hermes 插件 `edit_message` 原先返回布尔值，而宿主 `GatewayStreamConsumer` 按原生契约读取 `SendResult.success`。修复返回结构及同一消息 ID，避免把已发布编辑误判为失败及重新投递最终回复。插件程序固定为 `8a883b907dd46704de2946ff0e4edec26ed92d1f`，CI [38201747552](https://github.com/1rua/hermes-gateway-plugin/actions/runs/38201747552) 通过；推送后两名独立审查者分别审查规范与契约，均无确证发现，并各独立运行原生 consumer、SSE 和 WebSocket 三项回归通过。

原插件也能先发布部分增量，因此返回值错误不能当作手机无流式的唯一根因。实际宿主回调接线已检查；模型提供方是否持续产生增量、两处手机是否逐步显示，仍须部署后实测。插件报告见 [固定提交的流式修复记录](https://github.com/1rua/hermes-gateway-plugin/blob/8a883b907dd46704de2946ff0e4edec26ed92d1f/docs/reviews/2026-10-12-native-streaming-followup.md)。

Android `HermesHttpInteropTest` 新增默认 WebSocket 与明确 SSE 两项回归。每段必须经生产签名客户端、会话仓库及事件解码成为非空 `STREAMING`；Android 已观察后才通过标准输入释放下一段，最终确认同一回复身份及用户消息完成。测试还核对服务实际使用的通道，避免把 WebSocket 降级到 SSE 误当作 WebSocket 通过。没有为此改变 Android 生产事件处理。

App CI 的 Node/协议任务和 Android 任务都固定插件上述 40 位提交；契约输入与原插件锁定版本一致，Gateway Protocol 仍为 2.1，核心摘要仍为 `sha256:99309b87dec79f9c737b7d875a6e949ceffb6bb18b468d7ce98ca37d77dd3d50`。

## 本机验证证据

| 验证 | 结果 | 证据与边界 |
|---|---|---|
| Android 平台附件原实现 | 4 项中 3 项失败 | JPEG、PDF、空文件正确字节均被媒体类型拒绝；负对照通过，见附件修复记录的 RED |
| 附件最小回归 | 29 项通过，0 失败、错误、跳过 | Android 实际网络类、加密暂存、真实 HTTP 上传及提交；另含密钥约束、兼容、篡改及截断回归 |
| 连接工厂安全回归 | 15 项通过，0 失败、错误、跳过 | 默认平台连接、允许协议、超时、无重定向和 TLS/明文边界 |
| Android 跨端互通 | 4 项通过，0 失败、错误、跳过 | 两项既有登录/附件/批次及两项新增逐段事件回归，显式插件 `8a883b9` |
| 强制实际 Hermes 宿主 | 附件 4 项与跨端互通 4 项分别再次通过 | 宿主 `75b083e9399115b12513385252811f09c3503c90`，`OAI_REQUIRE_NATIVE_HERMES=1`；重复执行不累加项目数 |

本轮共 48 项不同定向测试通过。软件提供者密钥约束测试不能代替手机硬件密钥库；隔离确定性 Agent 不能代替用户账号调用真实模型。插件的 338 项 Python 回归及原生 11 项验证属于另一仓的证据，不累加到 Android 项目数。

Android 流式与连接工厂的 19 项原始记录为 [定向日志](evidence/2026-10-12-attachment-streaming/android-streaming-and-transport-green.txt)。实际宿主再次运行整个跨端互通类的 4 项原始记录为 [原生日志](evidence/2026-10-12-attachment-streaming/android-native-streaming-green.txt)，JUnit 计数及实际事件通道见 [结果详情](evidence/2026-10-12-attachment-streaming/android-streaming-details.txt)。

验证显式设置 `HERMES_PLUGIN_ROOT`、主仓契约路径和独立 Python 3.11，使用 `--no-build-cache --no-daemon`。两个定向测试任务均实际执行，没有测试任务 `FROM-CACHE` 或 `UP-TO-DATE`。未执行本机完整 Gradle `check` 或 APK 构建；完整检查及签名兼容 APK 由对应提交 CI 负责。

## 后续交付与待验收项

首次补修提交 `ea7a15f2e5cc50b1b3098d7b29e7c76866da1d32` 的 [CI 38202260104](https://github.com/1rua/open-android-intelligence/actions/runs/38202260104) 中，Node/Gateway 契约任务通过；Android 任务在 `noVpnSurfaceCheck` 处失败，尚未执行全套 Android 测试。对应 APK 和 Nightly 构建虽成功，不能代替完整 `check`。

门禁将新增平台回归中的 `OkHttpClient` 类名及逐段互通回归中的实际 `websocket` 通道名判为非网络 owner 的出站接口。补修只为这两个完整 JVM 测试路径允许出站断言，未扩大生产模块 owner 集合；`alwaysForbidden` 仍检查这两个文件的 VPN、路由、代理与通用监听/拨号关键字。没有拆分字符串或修改回归含义来绕过检查。

真实源码门禁先在原范围重现相同两处失败，窄路径修改后通过。再临时加入三处负对照：允许测试里的 `VpnService`、未列入测试里的 `OkHttpClient`、生产文件里的 `OkHttpClient`，全部仍被拒绝。随后恢复原测试，将临时探针移入回收暂存目录，再运行门禁通过。证据为 [原范围失败](evidence/2026-10-12-attachment-streaming/source-guard-red.txt)、[窄范围通过](evidence/2026-10-12-attachment-streaming/source-guard-green.txt)、[负对照拒绝](evidence/2026-10-12-attachment-streaming/source-guard-negative-controls.txt) 及 [结果详情](evidence/2026-10-12-attachment-streaming/source-guard-controls-details.txt)。

推送后须完成 Android 独立审查及完整 CI，再更新两处现有插件安装并重启现有服务，只更新程序和必要依赖，保留配置、账号、密钥、配对及宿主会话。新 APK 对应修复提交，并核对既有调试签名后供用户覆盖安装。

用户需分别验证图片或文件上传并让真实 Agent 接收、较长回复在最终完成前逐段显示，以及切换会话和清理后台后的消息保留。退出登录及普通回复已在上一版得到用户确认；附件和流式在新版本完成两处手机确认前保持待验收，不能报告整个任务已闭环。
