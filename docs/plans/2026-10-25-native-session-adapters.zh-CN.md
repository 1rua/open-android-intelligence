# 原生会话适配器部署与验证

本文件记录统一 Core 中 Hermes、DSH 与 Codex 的真实接入边界。手机只连接 `oai serve`；下面的 loopback 会话桥只供 Core 调用，不是手机连接入口，也不提供设备请求接口。各行为的代码依据见[架构事实基线](../architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md)。

## 调研证据

- OAI `dev` 基线 `157e7e5b` 已拆除内嵌 Hermes 插件。旧工作区遗留的隐藏插件目录不属于该分支，不能当作待发布代码。
- [Hermes Gateway](https://github.com/1rua/hermes-gateway-plugin/tree/b64dda00a0de9ff1a95a375dcf92f62b5a1027b3) 的 `AgentSessionBindings` 在每账号 SQLite 中保存 `conversationId -> agentSessionId/sessionKey`。`HermesHistoryPort` 通过真实 SessionStore 的 `_db_for_key/_db_for_session_id` 读取 SessionDB，归档和删除调用原生生命周期 API，不能枚举其他账号的原生目录。
- [DSH Gateway](https://github.com/1rua/dsh-android-gateway/tree/26935f9) 的 `src/dsh/bridge.ts` 已确认 `sessionController.create/prompt/cancel/rename`、`sessionQuery.readSurface/readTitle`、`session/event` 和 `agent/assistant-stream`。其现有 Gateway 列表仅包含 Gateway 映射，不能满足终端历史自动发现。
- 公开 npm `@deepseek-ai/dsh-session-query@0.0.1-rc.1` 的 `lib/types/index.d.ts`、`lib/types/types.d.ts` 明确提供 `listSessions()`：返回当前部署已配置 persistence backend 与 live sessions 合并后的 `SessionRecord{header,live,persisted}`。新 DSH 桥只调用该 API，不扫描其他用户目录、其他 profile 或其他服务。
- 公开 npm `@deepseek-ai/dsh-api-session-controller@0.1.2-alpha.2` 的 `prompt` 实现将 `requestId` 写入原生用户消息 `source.rpcId`。新桥依据这个真实来源确认所属 turn，避免终端并发 turn 提前结束 App 的流。该 API 若不存在，能力协商禁用相应功能，不能退回伪会话。
- 公开 npm `@deepseek-ai/dsh-session@0.0.1-rc.1` 的 `SessionEventMap['user/message']` 直接以 `UserMessage` 作为 `event.data`，不是 `data.message`。桥接历史与 turn 关联以真实形状为准，并兼容旧包装；`blocked`、`max-tokens`、`interrupted` 等非成功结束原因明确返回错误。

## DSH

先在 Core 与目标 DSH 宿主进程环境中配置同一个至少 32 字符的随机 `OAI_DSH_ADAPTER_TOKEN`，通过进程管理器的私有环境配置提供，不写入仓库。会话桥凭据只授予该 DSH 会话接口，不能作为 OAI Core 设备凭据使用。

在目标 DSH 会话中通过标准插件管理安装本仓库 bundle（将路径替换为当前 checkout 的真实路径）：

```text
plugin_manager action=install_bundle target=./integrations/dsh-session-adapter
```

重启目标 DSH profile，使环境变量及插件载入。bundle 默认绑定 `127.0.0.1:8791`，不会对外暴露手机登录入口。若需改变端口、token 环境变量名称、工作目录或 agent preset，在安装后的 Cordis 配置中设置 `port`、`tokenEnv`、`cwd`、`agentPreset`。

Core 的 `agents` 中加入：

```json
{
  "id": "dsh",
  "name": "DSH",
  "type": "dsh",
  "url": "http://127.0.0.1:8791",
  "tokenEnv": "OAI_DSH_ADAPTER_TOKEN"
}
```

所有已认证 OAI 账号看到同一个部署的原生会话。每次列表读取都重新查询宿主，因此终端已有会话和后续新建会话自动出现。历史来自 `readSurface`，不会复制一套独立会话数据库。创建、发送、重命名与取消仅在真实宿主提供对应方法时启用；删除和归档因尚未核实可完整读取的原生生命周期接口，当前明确禁用。状态探测实际调用 native query，持久化后端异常时显示离线。

**共享 DSH/Codex 宿主进程不能继承某一个 OAI 账号的通用设备 CLI/MCP token。** 共享会话可影响同一宿主执行的命令；把某账号设备凭据放进该进程环境会把该凭据的能力交给共享会话参与者。当前桥只转发会话文本和桥接凭据，不向 Agent 转发 Core 设备 token、账号 ID 或设备授权。设备调用必须通过当前操作主体另外明确认证的 Core CLI/API/MCP 环境进行；共享会话本身不授予设备权限。

## Hermes

在 Hermes 的 Python 运行环境安装本仓库 Core 包（同时安装锁定的现有 Hermes Gateway Python 包）：

```sh
python -m pip install ./oai-core
hermes plugins disable open-android-intelligence-gateway
hermes plugins install ./integrations/hermes-session-adapter --enable
```

安装前停掉旧 Hermes Gateway，备份原账号目录和对应主密钥。不能同时运行旧 Gateway 与新 session adapter 对同一账号目录写入；不要删除或覆盖原数据。新插件注册相同 canonical/legacy platform IDs，因此原生 SessionStore 的既有路由键保持可解析。插件不注册旧 Android 设备工具，不启动旧 Gateway v2 HTTP/SSE 设备端口。

在 Hermes 宿主环境中设置：

| 环境变量 | 内容 |
|---|---|
| `OAI_HERMES_SESSION_TOKEN` | Core 与此宿主共享的至少 32 字符随机桥接凭据 |
| `OAI_HERMES_GATEWAY_ROOT` | **原 Gateway 的 accounts 目录本身**，禁止猜测或自动迁移 |
| `OAI_HERMES_ACCOUNTS` | 明确允许接入的原账号 JSON 数组，例如 `["alice", "bob"]` |
| `OAI_HERMES_SESSION_PORT` | 可选，默认 `8792`，仅绑定 `127.0.0.1` |
| `OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE` | 原 Gateway 使用的主密钥文件，保持原值 |
| `OPEN_ANDROID_GATEWAY_CONTRACT_ROOT` | 可选，已核实的 Gateway 契约目录 |

使用原有平台配置启用 `open-android-intelligence-gateway` 平台并重新启动 Hermes Gateway Runner。加载的新插件是 `oai-hermes-session-adapter`；平台名称保持原值是为了保留原生会话来源键。Runner 给 adapter 注入真实 SessionStore 与 message handler 后才能在线；缺少宿主注入时不会声称支持创建或发送。

Core 的 `agents` 中加入：

```json
{
  "id": "hermes",
  "name": "Hermes",
  "type": "hermes",
  "url": "http://127.0.0.1:8792",
  "tokenEnv": "OAI_HERMES_SESSION_TOKEN",
  "accounts": {"oai-alice": "alice", "oai-bob": "bob"}
}
```

左侧是 Core 认证后的 OAI 账号，右侧是既有 Hermes Gateway 账号。映射必须一对一，禁止两个 OAI 账号共享同一 Hermes 上下文；未映射账号收到 `AGENT_ACCOUNT_NOT_CONNECTED`。只由 Core 验证后的 principal 选择映射，客户端请求不能传入 `accountId` 切换目标。loopback 桥会在读取数据前检查 Core 专用 token 与宿主账号 allowlist。

Hermes 会话列表仅返回既有账号绑定到真实原生 Session ID 的记录，不展示尚未绑定宿主的 Gateway 占位行。历史与生命周期管理复用原实现，创建必须完成真实 SessionStore 绑定才返回成功，消息通过原生 dispatcher 执行并从实际 `eventType` 事件接收流式文本。标题通过账号绑定后的 SessionDB `set_session_title/get_session_title` 读写，终端修改同样可见；缺少真实标题接口时不宣称支持重命名。HTTP 断开只停止观察，不能被当作原生 turn 已取消；取消保留 Core 验证后的配对代次，不从独立旧账号库推断手机授权。

会话桥当前没有原生 exec approval 卡片决策通路，因此立即向 Hermes 返回拒绝并在流中报告 `UNSUPPORTED_APPROVAL`，不等待不可操作的审批卡片超时，也不伪造用户批准。

桥接模式没有自行调用设备的授权。需要 Android 设备能力时，Hermes 工具应使用统一 Core CLI/MCP 的当前账号凭据；不得重新启用旧 Gateway 设备调度器。

## 兼容、回滚与验收

本阶段不改 Hermes 原生历史存储格式、原账号目录、SessionStore keys 或压缩链。Core 的账号/设备库与 Hermes 会话桥目录应分别配置，不对相同账号库双写。回滚时先停 Core 与会话桥，再停用新 Hermes 插件、启用原插件与原平台配置，恢复手机旧服务器配置；仅在变更前的备份上恢复文件，不反向拷贝 Core 设备库覆盖 Hermes 数据。

自动化覆盖真实 Gateway 账号 SQLite、真实 EventStore 事件格式、跨账号读取拒绝、未绑定会话排除、一对一映射、认证前数据访问拒绝、身份字段注入拒绝、DSH 共享列表刷新、真实宿主 controller 参数、原生流 turn 关联和断流错误。运行：

```sh
PYTHONPATH=oai-core/src python -m pytest oai-core/tests/test_hermes.py oai-core/tests/test_dsh.py
npm test -- integrations/dsh-session-adapter/adapter.test.js
```

真实 Hermes SDK 契约回归使用固定 checkout `75b083e9399115b12513385252811f09c3503c90`：

```sh
python -m pip install PyYAML==6.0.3 python-dotenv==1.2.4 psutil==7.2.2 httpx==0.28.1
HERMES_SDK_ROOT=.toolchains/hermes-native-sdk PYTHONPATH=oai-core/src python -m pytest oai-core/tests/test_hermes_native.py
```

该测试在新的隔离子进程和临时 Hermes Home 中使用真实 `parse_manifest_file`、`PluginContext`、平台注册、`SessionStore` 和 `SessionDB`，验证原生创建/历史/标题/归档/删除、账号隔离、实际 BasePlatformAdapter 消息生命周期、重新配对后的取消及不支持审批的明确拒绝。模型回调仅返回合成文本，不使用模型凭据，不读取真实用户目录。未设置 SDK checkout 时此项明确跳过，不能把跳过当作宿主兼容性已通过。

发布前仍需在实际 Hermes/DSH 运行环境和实体 Android 设备上验证宿主版本、插件加载、旧会话读取、App/终端交叉创建、原生流式回复、账号切换、设备授权隔离及敏感操作本机确认；协议与宿主 seam 测试不能冒充真实模型推理或真机验收。
