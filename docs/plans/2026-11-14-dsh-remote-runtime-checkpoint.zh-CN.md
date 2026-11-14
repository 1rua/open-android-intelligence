# PR #190 实施检查点

本文件用于同一任务中断后的继续执行，不能代替验收结论。2026-11-14 最新范围覆盖旧交接中的后台委派要求：
MCP 仅公开 android_*，目标为手机 OAI Virtual Host/phone-capabilities；后台跨 workspace 调度归上游，#191 已按范围取消。
正常多 Host、DSH/Codex/ACP workspace 与对话手动切换完整保留，由独立连接/代际与旧流取消排空保护。

| 所有者 | 独立工作区与分支 | 独占范围 | 已交付与下一入口 |
| --- | --- | --- | --- |
| 集成/运行时 | `.worktrees/dsh-remote-foundation` / `feature/dsh-remote-foundation`；`.worktrees/dsh-remote-client-runtime` / `feature/dsh-remote-client-runtime` | docs、共享 Gradle/package/CI；gateway-client remote 的 authproduction/network/runtime/rpc | foundation 依赖点 `35791d91`；范围 `4109ee73`、接口 `32e9adf6`、依赖 `b951230f`/`b14d5327`；运行时首片 `545d22ea` 已编译，后续修正与定向测试进行中 |
| 会话/界面 | `.worktrees/dsh-remote-session-ui` / `feature/dsh-remote-session-ui` | conversation-data/domain/ui、App Remote 选择与主入口 | 首片 `7e64e80a`；三 carrier 定向 4 项通过，App 生产接线与原能力保留继续实施 |
| 手机/桥 | `.worktrees/dsh-remote-phone-mcp` / `feature/dsh-remote-phone-mcp` | 手机控制账本/执行器/Session facade、纯手机 MCP/桥 | 稳定片 `d7aaa450`；原生工具至 Kotlin Router 的隔离互通及 MCP stdio 通过；原生屏幕/收敛/overlay 与生产 handler 装配继续实施 |

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

用户负责最终合并 PR、移除工作区及关闭/更新关联 issue；Agent 不自动合并或执行收尾删除。所有文件退役使用 mv 至忽略的 `.worktrees/trash/`。
