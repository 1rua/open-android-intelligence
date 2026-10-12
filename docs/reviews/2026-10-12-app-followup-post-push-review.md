# 附件与流式补修的推送后独立审查

日期：2026-10-12。审查者未参与实现；按 Code Review Skill 分别委派 Standards 和 Spec 审查，两轴结论单独保留。

## 固定范围与依据

- Android 基线：`f82fc3390911b6aec4c1ed8b967225b1d755916b`。
- Android 被审查提交：`ea7a15f2e5cc50b1b3098d7b29e7c76866da1d32`，提交说明“修复: 补齐附件媒体类型并验证原生流式互通”。
- 差分：`git diff f82fc3390911b6aec4c1ed8b967225b1d755916b...ea7a15f2e5cc50b1b3098d7b29e7c76866da1d32`。
- 独立 Hermes 插件：`8a883b907dd46704de2946ff0e4edec26ed92d1f`；检查配套夹具及原生流式编辑返回契约，不把插件整仓纳入本次 Android 差分。
- 规范：`AGENTS.md`、`CONTEXT.md`、模块化插件架构规格、ADR 0047，以及 Gateway Protocol v2 §6.1、§8、§9。
- 需求：用户的修复计划与追加反馈；本轮对应 [附件修复记录](2026-10-12-attachment-content-type-bugfix.md) 和 [附件及流式补修记录](2026-10-12-attachment-streaming-followup.md)。

本报告只评审上述不可变提交。主 Agent 随后准备的门禁小修复、临时负对照及其他工作树文件不属于本报告范围。本轮未运行 Gradle、未提交、未部署、未操作手机或用户账号。

## Standards

**P1：新增回归遗漏源码门禁的测试允许路径，阻断完整 Android CI。** 新增 `AndroidAttachmentContentTypeTest.kt:164` 引用了真实 Android `OkHttpClient`，`HermesHttpInteropTest.kt:59` 引用了实际通道名称 `websocket`。被审查提交中的 `gradle/mvp-forbidden-surfaces.gradle.kts` 仍对这两个非网络所有者模块的 JVM 测试应用 `forbiddenOutsideOwners`，于是 `:noVpnSurfaceCheck` 在这两行失败。CI 已证实该触发条件，不是推测的生产网络漏洞。`AGENTS.md` 要求完整 CI 失败后先修复再交付；应仅为这两个真实回归文件增加窄路径允许，并继续检查通用 VPN、代理、路由和监听禁令，随后复核 CI。

**P3：补修说明的 TLS 职责表述应更准确。** `2026-10-12-attachment-streaming-followup.md:13` 将 TLS 策略归于连接工厂；实际工厂负责协议、超时及禁止重定向，`GatewayTransport.kt:62` 调用 `GatewayConnectionSecurity.classify` 核验 TLS。建议分别说明职责；源码本身未退化。

判断性疑点：新旧互通测试重复了启动、登录和签名准备，可能属于 Duplicated Code；后续可提取共享夹具。这不是规范硬违反，也不阻断本次功能修复。

除此之外未发现可证实的规范违反。连接工厂默认仍使用 `URL.openConnection`，新连接创建函数只在实际平台回归中显式传入；协议过滤、禁止重定向、超时及发送正文前的身份核验均保留。

## Spec

**无新增确证功能缺漏、错误实现或额外范围。** `AttachmentUploader.kt:124` 的媒体类型来自同一所选附件，与创建请求相同，满足协议 §6.1“必须精确等于……mediaType”；固定长度、摘要和有界流未改变。实际 Android 网络类的负对照仅改变媒体类型，能隔离本次断点。

`HermesHttpInteropTest.kt:101` 必须先观察非空 `STREAMING`，才通过标准输入允许夹具产生下一段；随后确认同一回复身份、最终状态及用户消息完成。实际通道日志明确核对 WebSocket 或 SSE，默认 WebSocket 不能靠自动降级误过。这与协议 §9 的即时投递要求一致。

CI 的两个插件入口均固定到相同的 40 位提交。报告明确承认旧插件也可能已发出部分增量，因此 `SendResult` 修复不是手机无流式的唯一根因；真实模型增量、手机显示以及两处附件验收仍待新版本确认。没有以确定性夹具代替实机结论。

Standards 所列 CI 阻断同时是当前交付缺口，须在后续提交中修复；这里不重复计作功能实现缺陷。

## 独立核对与证据边界

本轮审查者实际执行并检查结果：

| 核对 | 结果 | 证明范围 |
|---|---|---|
| 固定差分与提交解析 | 成功 | 被审查范围为一个提交，共 20 个文件 |
| `git diff --check` | 退出码 0 | 固定差分无空白错误 |
| 独立插件契约 pin CLI | 退出码 0 | 插件锁定契约与 Android 输入一致；不代表宿主或手机验收 |
| `PlatformHeaderProbe.java` 重新编译并执行 | 退出码 0 | SDK 35 实际平台类在缺少媒体类型时发送表单类型，显式设置后发送图片类型，实际正文长度均为 3 |
| 读取固定提交 CI 元数据和失败日志 | 成功 | 确认两个真实测试标识触发门禁，未将 APK 成功视为完整检查成功 |

平台差分的独立执行输出：

```text
attempt=0 contentType=application/x-www-form-urlencoded bodyLength=3
response=200
attempt=1 contentType=image/png bodyLength=3
response=200
```

同时核对提交保存的 RED、GREEN、原生宿主日志及计数详情。48 项不同定向测试来自实现阶段的记录；本轮审查没有重新执行这些 Gradle 测试，也没有把原生重复执行累加进项目数。软件密钥提供者、Android 平台网络类、原生宿主确定性夹具、真实模型和手机验收分别构成不同证据层级。

被审查提交的 [CI 38202260104](https://github.com/1rua/open-android-intelligence/actions/runs/38202260104) 中，Node.js 与 Gateway 一致性任务通过，Android Build & Check 在 `:noVpnSurfaceCheck` 失败；[APK 38202260117](https://github.com/1rua/open-android-intelligence/actions/runs/38202260117) 及 Nightly Release 成功。完整 Android 测试尚不能据此记为通过。主 Agent 正在补修已确认门禁缺口，后续不可变提交需另行审查及跟踪 CI。

两轴结论：Standards 发现 1 项 P1 交付阻断、1 项 P3 文档事实修正及 1 项判断性重复代码疑点；Spec 新增确证功能缺陷 0 项。实机附件、可见流式和切换或重启后的消息保留继续保持待验收状态。
