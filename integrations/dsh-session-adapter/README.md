# DSH 原生会话适配器

通过 DSH `plugin_manager action=install_bundle` 安装本目录并重启目标 profile。该 Cordis bundle 只调用宿主真实 `sessionQuery` 与 `sessionController`，默认绑定 `127.0.0.1:8791`，通过至少 32 字符的 loopback token 接口供 Core 访问；不包含手机连接、账号库或设备调度器。该令牌仅用于 Core 到宿主的内部会话认证，不能作为 Core 账号或设备凭据。

**2026-10-28 设备授权规则**：Android 客户端是设备能力授权唯一权威；设备插件安装且启用后普通前台能力默认开启，手机本地显式开关、Android 系统权限、后台许可、数据范围和必要敏感确认继续生效。Core 负责连接认证、设备绑定、能力发现、协议验证与请求路由，DSH 会话桥不另设设备能力授权入口。规则已在 `feature/android-device-authority-20261028`，待 PR 合入 `dev`；当前验证见 [ADR-0059](../../docs/adr/0059-android-is-the-sole-device-capability-authority.md) 和[实施记录](../../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。外部独立 Gateway 的兼容性须按其版本单独检查。

Agent 如需设备能力，应使用 Core CLI/MCP/API 的已认证且绑定目标设备的凭据，不能把会话桥 Token 当作设备凭据。DSH 共享会话不得自动继承个人设备调用凭据。此目录的会话桥与外部 `dsh-android-gateway` 旧独立 Gateway 是不同接入方式；本次未验证外部旧插件对新授权规则的兼容性。

安装与初始化配置（`install.sh` / `setup.sh`）在检测到 DSH 环境并配置接入时，会全自动生成随机 Token 并注入/同步到 DSH 的环境文件（`$DSH_HOME/.env`）与凭据库（`$DSH_HOME/.credentials.yaml` 中的 `refs.OAI_DSH_ADAPTER_TOKEN`），无需开发者或用户手动复制粘贴。若手动启动或自定义启动环境，请确保 DSH 启动前导出 `OAI_DSH_ADAPTER_TOKEN`。

从旧版 `dsh-android-gateway` 会话插件升级时，`/status` 返回在线仍可能缺少 Core 注册所需的 `/handshake`。此时 `setup` 显示 `AGENT_BRIDGE_UPGRADE_REQUIRED`，并列出本机已经启用会话插件的 DSH profile（独立运行配置）。选择并确认一个目标后，安装入口通过该 DSH 安装自带的官方插件管理 API 安装当前 bundle，将启用列表中的旧 bundle 替换为 `oai-dsh-session-adapter`，保留旧依赖、其他插件配置和账号历史。

升级前的 `package.json`、可选 `pnpm-lock.yaml` 和文件摘要保存在 Core 安装目录的 `runtime/dsh-adapter-backups/<记录编号>/`，目录仅部署所有者可读写。安装后会再次执行真实握手；若 DSH 进程仍运行旧代码，保持未注册，回到菜单并提示重新加载所选 profile。重新加载后再执行 Agent 接入。配置取消、安装失败、凭据不匹配或握手失败均不会写入成功注册记录，也不会以 `/status` 代替身份验证。

同时存在多个 DSH 主目录时不会自动批量升级。可通过 `DSH_HOME` 指定宿主目录，使用 `DSH_PROFILE` 指定其中的 profile；仍需在菜单确认安装目标。没有官方插件管理 API 的旧版 DSH 会显示 `DSH_PLUGIN_MANAGER_UNAVAILABLE`，应先更新 DSH。`setup.sh` 只调用本机已安装的 Core 管理程序；仅重新下载该入口不会更新 Core 源码或宿主插件。

列表覆盖目标宿主已配置 persistence backend 与 live sessions，所有已认证 OAI 账号共享该部署范围。能力依据实际宿主方法启用（当前不宣告 delete 与 archive），未接入的其他系统实例不在发现范围。请求严格受小于 1 MiB 的紧凑 UTF-8 JSON 预算约束。超长历史摘要使用迭代计算时间，避免事件数量超过 JavaScript 函数参数限制时拖垮整个列表。

消息观察连接与宿主执行分开管理。真实 DSH `0.2.2-rc.1` 的 `sessionController.prompt(request, signal)` 要求有效信号；桥为每次受理提供独立信号。关闭连接、受理回执超时或慢写端超过预算，只结束当前观察并清理监听与 busy；不会取消宿主已加入 inbox 的输入。收到 `clientMessageId` 时原样作为原生 `requestId`，用于关联 `source.rpcId`；没有该字段的旧调用仍使用一次性的随机 ID。历史优先读取完整原生日志，保留用户来源关联，仅将同轮次的最终助手回复与客户端身份关联，不把中间工具步骤当成重复回复。

宿主提供 `sessionQuery.readSession` 与同步 `agents.get` 时启用 `generationRecovery` 和 `cancel`。`GET /sessions/:id/generation` 返回权威活动或排队记录；可通过 `clientMessageId`、`generationId` 精确读取原始受理记录及真实终态，也支持 `afterRevision` 和最长 25000 毫秒的 `waitMs`。代次 ID 基于宿主已接纳的原生用户消息 ID，排队、运行和终态不更换身份；修订号来自原生日志序号。没有 `turn/end` 且宿主未附着或重启时保持 `unknown`，不把连接中断或适配器内存清空解释为空闲。

精确取消先从当前活动 Agent 的同步完整日志核对目标所属轮次，再在同一次 JavaScript 调用栈中执行 `agent.cancel({ kind: "user" }, { keepInbox: true })`；比较与副作用之间不插入异步等待，并复核 Agent 未被替换。迟到或已终态的目标不会取消后续任务，排队目标不会误取消其他活动轮次。取消回执的 `cancelled`、`already_completed` 必须有原生终态依据；仅发出取消但终态尚未来到时返回 `unknown`，随后可精确查询。`idempotentSend=false` 继续保留：原生 `rpcId` 去重的已知行为不等同于已证明跨重启精确执行一次。

观察队列同时限制为 128 帧和 1 MiB。`res.write()` 返回 `false` 后等待 `drain`；无法在预算内恢复或队列超限时关闭当前响应，宿主执行继续。可选配置 `promptTimeoutMs`（默认 30000）、`observationTimeoutMs`（默认 1800000）、`drainTimeoutMs`（默认 5000）均为正整数毫秒，最大为 2147483647。没有原生终态证据的期限结束返回 `OUTCOME_UNKNOWN` / `state=unknown`；迟到的受理回执不会改写已经结束的观察。

本轮调用回归、复跑命令和能力边界见[通信可靠性验证](communication-reliability.zh-CN.md)。

架构细节与代码出处见[架构事实基线](../../docs/architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md)，完整配置、实际 API 证据、设备凭据隔离、测试与回滚步骤见[原生会话适配器部署文档](../../docs/plans/2026-10-25-native-session-adapters.zh-CN.md)。

诊断日志默认关闭。测试部署可设置 `OAI_DSH_ADAPTER_DIAGNOSTICS=1`，按 Core 传入的 traceparent/requestId 关联桥提交、原生输入事件、首内容、原生终态和观察关闭。日志仅输出固定阶段、公开身份及单调耗时，不含消息正文或凭据；Native 输入事件不等同模型首输出。详情见[通信生命周期](../../docs/architecture/2026-10-30-communication-lifecycle.zh-CN.md)。
