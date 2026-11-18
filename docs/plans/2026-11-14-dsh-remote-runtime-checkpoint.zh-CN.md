# PR #190 实施检查点

本文件用于同一任务中断后的继续执行，不能代替验收结论。2026-11-14 最新范围覆盖旧交接中的后台委派要求：
MCP 仅公开 android_*，目标为手机 OAI Virtual Host/phone-capabilities；后台跨 workspace 调度归上游，#191 已按范围取消。
正常多 Host、DSH/Codex/ACP workspace 与对话手动切换完整保留，由独立连接/代际与旧流取消排空保护。

| 所有者 | 独立工作区与分支 | 独占范围 | 已交付与下一入口 |
| --- | --- | --- | --- |
| 集成/运行时 | `.worktrees/dsh-remote-foundation` / `feature/dsh-remote-foundation`；`.worktrees/dsh-remote-client-runtime` / `feature/dsh-remote-client-runtime` | docs、共享 Gradle/package/CI；gateway-client remote 的 authproduction/network/runtime/rpc | foundation 当前共享点 `a9c185e3`；范围 `4109ee73`、接口 `32e9adf6`、依赖 `b951230f`/`b14d5327`；运行时候选 `1953344f` 17 项通过并冻结待新两项定点确认 |
| 会话/界面 | `.worktrees/dsh-remote-session-ui` / `feature/dsh-remote-session-ui` | conversation-data/domain/ui、App Remote 选择与主入口 | 候选 `0a3672e7`；前轮 App 211+data13 通过，最新 Appcompile+RemoteRuntime4 最小复验通过，fresh 5 项问题待修，旧本机消费迁移待继续 |
| 手机/桥 | `.worktrees/dsh-remote-phone-mcp` / `feature/dsh-remote-phone-mcp` | 手机控制账本/执行器/Session facade、纯手机 MCP/桥 | 候选 `931e16d5`；Overlay3/Kernel3/Bus7+Appcompile 通过，MCP2及原工具18字节产物 probe 通过；API31–33 SoM/真机待验 |

分支曾互相 cherry-pick 共享接口。集成时先固定各所有者最终提交及必要测试，再逐支 rebase 到 foundation；
等价补丁应跳过，保持原 PR 的普通线性推进，不强制推送、不恢复旧文档或重复接口。

已验证：范围变更路由检查 195 项、Node24 的 ACP 参考观察 3 项；运行时首片 Kotlin 编译；
RPC 生命周期/凭据重启首轮 6 项通过。运行时 HTTP/WS 双向 fixture、账号取消/关闭/事件生命周期修正仍在验证，
不能把编译通过写成网络或 Session V3 全链通过。本轮不做真机，不使用日常 Host；真实设备/后端/部署仍单列。

本地复用根 `.toolchains/` 的 Node `node-v24.18.0-linux-x64`、JDK `jdk-21-extract/jbrsdk_jcef-21.0.10-linux-x64-b1163.110`、
Android SDK `android-sdk`、Gradle 缓存 `gradle-home`。不要把 `android-user-home` 当作 Gradle 缓存；
不要使用 `--no-daemon` 或每次新建缓存。原 FUSE 的 0700 显示异常不能通过放宽安全断言解决。

旧依赖退役必须在新主入口和相关回归通过后执行：

- App 生产：`GatewayRuntime`、旧登录/配对/refresh/设备发现、`PlatformSnapshotRecovery`、`GatewayPushTransport`、
  Assistant/Bubble 的旧 HTTP 会话工作区、`ProductionPluginHost`/`DeviceExecutionDriver`/`CapabilityPublisher` 的旧网关接线。
  本地设置、插件授权/执行、通知/定时/推送持久队列、媒体镜像与导出保留并接新端口。
- 通信/数据：gateway-client 的旧 auth/http/protocol/envelope 与只被其消费的类型；conversation-data 的旧 repository/attachment adapter。
  中性 JSON/Schema/本地插件解析类型先迁出旧业务包，再移除依赖，不能连中性能力一起删除。
- 构建/夹具：`apps/android/app/build.gradle.kts` 的历史 Hermes/Core fixture 输入与生成任务；对应测试改为 Remote fixture 后再安全移走。
  固定原生 Remote 协议/Noise fixture 保留。旧 adapter 测试不能替代 Remote 验收，也不能简单 skip。
- 根工具/安装：package scripts 的 gateway:v2/conformance 与 gateway-contract 消费，旧 Core 安装/卸载/配置/观察脚本，
  `oai-core/`、`gateway-contract/`、`integrations/*-session-adapter` 与旧 pins 及引用它们的锁检查。
- CI/发布：`.github/workflows/oai-core.yml` 的 Core/Hermes/DSH jobs、`.github/workflows/ci.yml` 的旧契约/插件 checkout/conformance、
  `.github/actions/android-lane/action.yml` 的旧 fixture 环境、smart-test/impact 的旧路由与对应测试、release-gate 的目标工作流引用。

## 下一实施切片：定时与可靠推送

定时任务可独立先迁移：`PluginJobScheduler` 当前保存 account/pairing/grantRevision，执行时依赖
Gateway 已连接账号与旧 pairing grant，`PluginTimerService` 也恢复旧 Gateway。新入口应保存稳定 Remote
主体（origin、账号、双方设备/密钥指纹、本机授权代际）与本机后台授权，保留加密参数、JobScheduler、
执行前复核、防重复栅栏和结果未知；不得依赖临时 connectionId，不能因此删减原定时能力。

推送尚有真实接口缺口：`PushEndpoint` 仍为 Core 路由，`GatewayPushTransport` 依赖旧 `/v2/push`
已确认消费回执和撤销账本，`DevicePushComposition` 依赖旧登录/独立连接发现。下一切片须以完整 Remote
稳定目标取代路由，保留加密队列、本机选择、采集、过期/撤销与系统任务。固定上游的 Harness prompt
受理、Codex turn/start 或 ACP 回合结束没有统一事件消费 ACK；不得把 accepted 当作 CONSUMED，
不得用空 transport 或假 Delivered 让验收通过。后台投递也不能调用当前单活动 Client 的 connect 挤掉聊天。
只可复用现有安全独立通道并明确协商 ACK 支持；不擅自新增 Host 调度插件或恢复后台 Agent 委派。

当前 Runtime `1953344f` 已补状态发布原子性与 Host RTC 不可用后 Relay 降级两项 P2，
原 15 加新增 2 共 17 项回归通过（51 秒，失败/错误/跳过均 0），冻结待定点确认。手机 `cb626070`
敏感输出 P1 的 10 项与 App 编译通过，独立确认关闭。通过相应复审后才线性整合，
不得把各工作区不同 SHA 的结果拼成网络或全链路最终验收。

用户负责最终合并 PR、移除工作区及关闭/更新关联 issue；Agent 不自动合并或执行收尾删除。所有文件退役使用 mv 至忽略的 `.worktrees/trash/`。

## UI 生产消费者清退对照（本机行为保留）

- 旧 `GatewayTutorials`/部署命令测试：Core/OpenClaw 部署是明确退役范围；改由 `RemoteTutorialsTest` 核对 Remote 登录、Host 发现、手动工作区、本机授权，`OnboardingStateTest` 保留恢复/重看/阶段语义。教程稳定片 `9b2b8555` 的 16 项本次通过。
- 旧 `SettingsViewModel` 的 Core 协商、续期、账号选择、配对管理分支与对应协议断言退役；外观持久化、通知偏好与系统授权分离、信任确认、急停/持久失败、外部状态反应继续由中性 `SettingsViewModelTest`/`SettingsStateIntegrityTest` 覆盖。
- 旧 `ConnectionSettingsScreen`/`NativeAgentSessionsScreen` 无生产消费者后归档；Remote 连接页面保留账号/Client/Host 状态与手动发现选择，本机能力页不借旧账号或配对授予权限。
- 旧 `GatewayWorkbenchScreen`、头像 Gateway overload、Core 连接健康 helper 归档；`RemoteLocalEntryPointsTest` 保留窄/宽工作台退出回调与设置入口独立，`SettingsResponsiveHostRegressionTest` 继续核对真实导航、滚动、未提交表单和宽度切换。`AccountAvatarTest` 保留图像上限/编码/账号隔离，Remote 头像仍绑定发起账号。
- 旧 `GatewayWorkbenchLogoutTest` 的 Core HTTP/多账号协议断言随产品退役；`RemoteRuntimeTest` 新增真正运行回复→退后台服务保持→完成停、注销主/助手/流/通知/服务清理、端点切换停止旧服务、迟到回调与后台/拒绝通知不能重启服务。测试未执行前均为 UNVERIFIED，不以按钮回调替代生命周期证据。
- 旧 `ColdStartConnectionTest` 中仅两个旧 Core 设置账号探针归档；Remote 恢复/同会话跨工作区、离线镜像、本机组织、登录预填、Host 独立角色仍分别在 `RemoteRuntimeTest` 与 Remote account/runtime 测试中验证。旧 Core 凭据不做迁移。
- `RemoteAttachmentCoordinatorCancellationTest` 补上取消时真实 upload.abort、禁止旧 commit、另一端点不受影响、草稿恢复/丢弃隔离；助手附件交接所有权原测试保留。
- 备份 coordinator 的生产运行时类型改为 `LocalSettingsRuntime`；历史格式的纯本机偏好/插件配置读写暂保留，Remote 当前账号只枚举完整端点，不读取旧 Core 账号分区。备份地址权威唯一 P2 已在 `d941520b` 的 33 项本次验证后关闭。
- 仍未清退：Application 的 `gatewayRuntimeOwner`、DevicePush 与旧 PluginJobScheduler 生产消费者由对应推送/定时切片关闭后再移除；本片不得宣称旧 Core 已全部退出。

安全归档副本位于对应工作区忽略目录 `.worktrees/trash/ui-core-retirement/`，由用户最终复核清理；Git 保留原始历史。本清单不把临时 `/tmp` 索引当作验收证据。
