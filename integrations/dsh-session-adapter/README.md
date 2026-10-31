# DSH 原生会话适配器

通过 DSH `plugin_manager action=install_bundle` 安装本目录并重启目标 profile。该 Cordis bundle 只调用宿主真实 `sessionQuery` 与 `sessionController`，默认绑定 `127.0.0.1:8791`，通过至少 32 字符的 loopback token 接口供 Core 访问；不包含手机连接、账号库或设备调度器。该令牌仅用于 Core 到宿主的内部会话认证，不能作为 Core 账号或设备凭据。

**2026-10-28 设备授权规则**：Android 客户端是设备能力授权唯一权威；设备插件安装且启用后普通前台能力默认开启，手机本地显式开关、Android 系统权限、后台许可、数据范围和必要敏感确认继续生效。Core 负责连接认证、设备绑定、能力发现、协议验证与请求路由，DSH 会话桥不另设设备能力授权入口。规则已在 `feature/android-device-authority-20261028`，待 PR 合入 `dev`；当前验证见 [ADR-0059](../../docs/adr/0059-android-is-the-sole-device-capability-authority.md) 和[实施记录](../../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。外部独立 Gateway 的兼容性须按其版本单独检查。

Agent 如需设备能力，应使用 Core CLI/MCP/API 的已认证且绑定目标设备的凭据，不能把会话桥 Token 当作设备凭据。DSH 共享会话不得自动继承个人设备调用凭据。此目录的会话桥与外部 `dsh-android-gateway` 旧独立 Gateway 是不同接入方式；本次未验证外部旧插件对新授权规则的兼容性。

安装与初始化配置（`install.sh` / `setup.sh`）在检测到 DSH 环境并配置接入时，会全自动生成随机 Token 并注入/同步到 DSH 的环境文件（`$DSH_HOME/.env`）与凭据库（`$DSH_HOME/.credentials.yaml` 中的 `refs.OAI_DSH_ADAPTER_TOKEN`），无需开发者或用户手动复制粘贴。若手动启动或自定义启动环境，请确保 DSH 启动前导出 `OAI_DSH_ADAPTER_TOKEN`。

列表覆盖目标宿主已配置 persistence backend 与 live sessions，所有已认证 OAI 账号共享该部署范围。能力依据实际宿主方法启用（当前不宣告 delete 与 archive），未接入的其他系统实例不在发现范围。请求严格受小于 1 MiB 的紧凑 UTF-8 JSON 预算约束。

消息观察连接与宿主执行分开管理。关闭连接、受理回执超时或慢写端超过预算，只结束当前观察并清理监听与 busy；不会取消宿主已加入 inbox 的输入。显式取消仍调用宿主 `sessionController.cancel`。收到 `clientMessageId` 时原样作为原生 `requestId`，用于关联 `source.rpcId`；没有该字段的旧调用仍使用一次性的随机 ID。这只建立关联，不表示宿主提供幂等提交或持久执行恢复，`generationRecovery`、`idempotentSend` 均为 `false`。

观察队列同时限制为 128 帧和 1 MiB。`res.write()` 返回 `false` 后等待 `drain`；无法在预算内恢复或队列超限时关闭当前响应，宿主执行继续。可选配置 `promptTimeoutMs`（默认 30000）、`observationTimeoutMs`（默认 1800000）、`drainTimeoutMs`（默认 5000）均为正整数毫秒，最大为 2147483647。没有原生终态证据的期限结束返回 `OUTCOME_UNKNOWN` / `state=unknown`；迟到的受理回执不会改写已经结束的观察。

本轮调用回归、复跑命令和能力边界见[通信可靠性验证](communication-reliability.zh-CN.md)。

架构细节与代码出处见[架构事实基线](../../docs/architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md)，完整配置、实际 API 证据、设备凭据隔离、测试与回滚步骤见[原生会话适配器部署文档](../../docs/plans/2026-10-25-native-session-adapters.zh-CN.md)。
