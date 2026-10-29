# OAI Core

一个服务监听地址承载 Android 登录、配对、设备事件与原生 Agent 会话。
`oai serve`、CLI 与 MCP 属于同一个 Python 项目；设备协议、安全认证与请求状态机
由 `oai_core.gateway` 提供唯一实现。Core 安装不拉取 Hermes Gateway，启动不需要
Hermes SDK 或 Hermes 插件。可选 Hermes Session Adapter 依赖 Core 的公共库。
Android Platform Kernel、`.alp` 签名、WASM 沙箱与本地确认机制继续承担手机本地安全检查。

> **2026-10-28 授权规则**：Android 客户端是设备能力授权的唯一权威。设备插件安装且启用后能力默认开启，实际执行仍受本地显式开关、Android 系统权限、数据范围和必要敏感确认约束。Core 负责连接认证、设备绑定、能力发现、协议验证及请求路由，不再通过账号能力开关决定设备能力是否可用。规则已在 `feature/android-device-authority-20261028` 分支实施，待合入 `dev` 和真机验收。实现与当前验证见[实施记录](../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)；旧 `dev` 基线见[修复计划](../docs/reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md)。

同一个进程对外提供三套接口：Android 设备协议 `/open-android-intelligence/v2`、
设备 CLI/API/MCP `/open-android-intelligence/core/v1`、原生 Agent 会话
`/open-android-intelligence/v2/agents`。每条结论的可核对依据见
[架构事实基线](../docs/architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md)。

## 安装与启动

### 一键安装脚本（推荐）

使用 curl 命令完成 Core 依赖检查、环境安装、可选 Agent 探测与初始化配置向导：

```sh
curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/install.sh | bash
```

或在需要单独重新配置端口、账号或 Agent 时运行独立配置脚本：
```sh
curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/setup.sh | bash
```

或在仓库根目录直接运行：
```sh
./scripts/install.sh
# 或
./scripts/setup.sh
```

向导只安装 Core；发现 Hermes 不会自动安装插件、停用旧网关或启用会话桥。
选择接入后，Core 生成显式的一对一账号映射；Hermes 宿主包须在宿主自己的 Python
环境另行安装。DSH 和 Codex 的安装、使用均不要求存在 Hermes。

向导安装完成后，会自动将 `~/.local/bin` 注入 PATH 并在其中配置 `oai` 启动包装命令：
直接在终端输入 `oai` 即可一键启动服务；带参数执行时（如 `oai devices`、`oai mcp`）作为 CLI 客户端管理工具。

桥接 Token 生成或复用到 `runtime/secrets/adapter-tokens.env`，文件权限为 `0600`，
父目录为 `0700`；`start-core.sh` 只读取该私密文件，不嵌入凭据值。
重新运行 `setup.sh` 会备份旧配置并保留已有存储路径、主密钥、账号密码、配对和
Agent 实例，不自动重置账号密码或旋转桥接 Token。移除既有 Agent 时显式编辑 `agents`。

### 手动安装与启动

Python 3.11+。在仓库根目录安装，或直接安装构建出的 Core wheel；协议契约随包发布：

```sh
python -m venv .venv
.venv/bin/python -m pip install -e ./oai-core
source .venv/bin/activate
.venv/bin/oai account create --config oai-core/examples/core.json --username alice
.venv/bin/oai serve --config oai-core/examples/core.json
```

账号密码通过交互输入，不出现在命令行参数中。示例默认只监听本机，未注册 Agent 时列表为空。
配置中的路径相对于配置文件；`contractRoot` 可省略，默认使用包内真实协议契约。
从源码构建时，这些资产只取自本仓库的 `gateway-contract`，不维护第二份协议源文件。
运行数据与主密钥分别位于 `runtime/accounts` 和
`runtime/secrets`，均不提交；主密钥不能位于账号备份目录内。
手机远程连接时配置 `listen.host`、`tls.certFile` 和 `tls.keyFile`，使用同一个 HTTPS
地址及端口登录。TLS 终止在反向代理时还须配置与手机看到的证书一致的 `tlsSpkiSha256`；
只在受控开发环境显式使用 `allowInsecureHttp`。

先在 Android 登录 Core 并配对，再安装、启用设备插件。当前 feature 分支规则无需 Core 或 Agent Gateway 再授予账号级设备能力；本地关闭、系统权限不足和敏感确认未完成仍会阻止执行。调用方单独取得绑定目标设备的 Core 凭据，例如：

```sh
oai login --url https://core.example --username alice --device DEVICE_ID
oai capabilities
```

`account` 是服务端本机身份管理命令。旧 `dev` 基线中的 `policy` 账号能力开关不再参与本分支的设备授权裁决；它不是目标设计的操作步骤。出现 `GATEWAY_DISABLED` 时，应先确认客户端与 Core 是否完成新授权模型协商；不要另建服务端授权入口。

远程 CLI/API/MCP 请求不接受账号或设备身份覆盖。当前登录凭据绑定该账号下已配对设备、配对代次、授权版本和过期时间，默认有效期 86400 秒；撤销或授权版本变化会使旧凭据失效。后续对授权版本与设备状态同步的调整须按计划实现和验收，认证、配对绑定及凭据撤销机制必须保留。
客户端配置默认写入 `~/.config/oai/client.json`，以 `0600` 保存，退出使用 `oai ... logout`
撤销凭据。上述登录和后续命令使用同一个默认客户端配置。需要自定义路径时，每条客户端命令均加
`--client-config`。`--device` 取 `oai devices` 返回的设备 ID。在操作人的独立终端环境登录，
避免给共享原生 Agent 的运行身份保存设备凭据。

## 设备 CLI / API / MCP

```sh
oai devices
oai capabilities
oai invoke org.openandroidintelligence.notifications.query --version 1.0.0 --parameters '{"limit":1}' --idempotency-key read-once --wait 30
oai status REQUEST_ID
oai cancel REQUEST_ID
oai ack REQUEST_ID
oai mcp
```

能力 ID、版本和参数以 `capabilities` 返回的实际插件 schema 为准。
`--wait` 只等待服务端状态，不创建另一套任务状态；等待结束后可用 `status` 继续读取原请求。
相同幂等键及参数返回同一请求，参数不同返回冲突。高风险操作仍需手机本地确认。
`ack` 在消费设备结果后清理临时结果正文。MCP 使用官方 SDK 的 stdio transport，工具均调用同一 API。

设备 REST 前缀为 `/open-android-intelligence/core/v1`：
`POST /tokens`、`DELETE /tokens/current`、`GET /devices`、`GET /capabilities`、
`POST /device-requests`、`GET /device-requests/{id}`、`POST /device-requests/{id}/cancel|ack`。
除登录外使用受限 Bearer token；写请求需要 `Idempotency-Key`。
Android 继续使用原 `/open-android-intelligence/v2` 的签名、配对及设备事件协议。
会话使用本次新版 App 的 `/agents` 接口；旧 `/conversations` 聊天 API 在 Core 不可用，
对应可选会话能力不会宣告。旧 App 聊天需保留或回滚到原 Gateway。
成功协商响应头 `X-OAI-Agent-Sessions: 1` 用于新版 App 选择原生会话界面，旧宿主无需接收 `/agents` 探测。

## 会话接口

原生会话路由位于 `/open-android-intelligence/v2/agents`：
`GET /agents`、`GET|POST /agents/{agent}/sessions`、
`GET /agents/{agent}/sessions/{id}/history`、
`POST /agents/{agent}/sessions/{id}/messages`（SSE，`type: delta/done/error`）、
`PATCH /agents/{agent}/sessions/{id}`（`action: rename|archive`）、
`DELETE /agents/{agent}/sessions/{id}`、
`POST /agents/{agent}/sessions/{id}/cancel`。

能力为 `list`、`history`、`create`、`send`、`rename`、`archive`、`delete`、`cancel`、
`stream` 九项布尔值，缺失按 false；`sessionScope` 取 `account` 或 `deployment`。
认证先于 Agent 与会话查找，每个写操作服务端再次检查能力声明。流中断不伪造成功：
未收到终态事件时明确发出 `AGENT_STREAM_INTERRUPTED`。

## 接入原生 Agent

只有 `agents` 显式声明的实例会被发现，不遍历其他用户目录或服务。
所有账号登录后能读取该部署的 DSH/Codex 共享会话，包括终端创建的原生历史。
Hermes 单独保留账号绑定；所有操作均读取或修改真实宿主，不保存第二份聊天历史。
在线状态由宿主握手/查询得出；宿主不支持的能力在服务端和 App 同时禁用。

原生会话请求在 Core 与 Hermes/DSH 会话桥均须小于 1 MiB；消息另保留 262144 字符上限。
Core 在记录写操作或开启 SSE 前检查紧凑 JSON 的实际 UTF-8 字节数，过大请求返回
HTTP 413 `MESSAGE_TOO_LARGE`，不会创建未知结果的操作回执。桥传输使用紧凑 UTF-8 JSON，
避免把多字节文本转成更大的 ASCII 转义序列。

Codex 使用明确指定的 app-server 和实例目录：

```json
{
  "type": "codex",
  "id": "codex",
  "name": "Codex",
  "command": ["codex", "app-server"],
  "codexHome": "../runtime/codex"
}
```

把条目加入配置的 `agents` 数组，并为该目录配置可用的原生 Codex 实例。
原生 app-server 的真实 thread API 提供列表、历史、创建、发送、重命名、归档和取消；
删除暂不支持。原生审批请求会明确拒绝并报告，不伪造用户批准。
模型提供方的凭据通过 `envFrom` 显式引用，例如
`"envFrom": {"OPENAI_API_KEY": "CODEX_MODEL_API_KEY"}`；不会继承 Core 的完整环境。
部署执行身份与凭据检测见 [Codex 设备隔离边界](docs/codex-device-isolation.zh-CN.md)。
共享 Agent 不自动获得任何 Android 设备凭据；独立 CLI/MCP 需使用经 Core 认证并绑定目标设备的调用凭据。凭据只界定调用身份和设备范围，不替代 Android 的设备能力决定，也不构成账号级能力开关。
共享原生 Agent 应运行在独立服务身份下，不得配置任意个人账号的常驻 OAI CLI/MCP 凭据。

DSH 接入宿主的轻量 Session Adapter：

```json
{
  "type": "dsh", "id": "dsh", "name": "DSH",
  "url": "http://127.0.0.1:8791", "tokenEnv": "OAI_DSH_ADAPTER_TOKEN"
}
```

宿主安装方式见 [DSH Session Adapter](../integrations/dsh-session-adapter/README.md)。
它使用原生 `sessionQuery.listSessions/readSurface` 与 `sessionController`，不处理设备调用。
删除与归档因尚未核实可完整读取的原生生命周期接口而不宣告，App 侧对应操作禁用。
环境变量引用的适配器令牌用于 Core 到宿主的内部认证，不能作为 Core 账号或设备凭据。DSH 会话适配器不提供额外设备能力授权；Agent 需要设备工具时统一通过 Core CLI/MCP/API，并由 Android 裁决实际执行。

Hermes 通过独立可选包 `oai-hermes-session-adapter` 保留账号映射。
在 Hermes 自身的 Python 环境安装当前配套版本，再通过其原生插件加载器安装入口：

```sh
python -m pip install ./oai-core ./integrations/hermes-session-adapter
hermes plugins install ./integrations/hermes-session-adapter --enable
```

宿主环境须先配置好明确的原账号目录、账号清单、原主密钥与会话桥 Token。
新 Adapter 只提供原生会话，不运行设备监听器或设备请求调度器。

Core 配置条目为：

```json
{
  "type": "hermes", "id": "hermes", "name": "Hermes",
  "url": "http://127.0.0.1:8792", "tokenEnv": "OAI_HERMES_SESSION_TOKEN",
  "accounts": {"alice": "alice", "bob": "bob"}
}
```

映射由管理员配置，必须一对一；不会依据 App 传入的账号选择身份。
宿主部署与原账号目录复用见 [Hermes Session Adapter](../integrations/hermes-session-adapter/README.md)。
先验证独立 Core 并备份，再停止原 Gateway、启动新 Adapter；保留旧安装用于回滚。
新 Adapter 对原账号库持有独占锁，禁止新旧进程同时写入。
Core 设备数据库另设目录，不会与 Hermes 的原账号库合并；主密钥也不自动复制或替换。
完整步骤见[独立部署与安全切换](../docs/deployment/2026-10-26-core-independent.zh-CN.md)。
OpenClaw 不注册到本次新 Core 装配，继续沿用旧接入方式；其外部实现与旧 DSH/Hermes 独立 Gateway 未在本次修订中验证，不能据此宣称已支持新的授权规则。

## 协议、迁移与验证

新增 Agent API、数据兼容、备份和回滚步骤见
[迁移方案](../docs/plans/2026-10-25-unified-core-migration.zh-CN.md)。
签名写操作绑定原 request ID；原生操作回执只记录幂等摘要和管理结果，不存聊天正文。
流中断或进程重启后未知的写操作不会自动重发，先读取原生历史确认结果。

```sh
python -m pip install ./oai-core pytest pytest-asyncio
python -m pytest oai-core/tests tests/test_install_script.py -q \
  --ignore=oai-core/tests/test_hermes.py --ignore=oai-core/tests/test_hermes_native.py
npm ci
npx vitest run integrations/dsh-session-adapter/adapter.test.js
```

固定 Hermes 原生 SDK 的契约回归需要额外设置：

```sh
export HERMES_SDK_ROOT=/按固定 checkout 75b083e9 检出的 Hermes 源码目录
python -m pip install ./oai-core ./integrations/hermes-session-adapter PyYAML==6.0.3 python-dotenv==1.2.4 psutil==7.2.2 httpx==0.28.1
python -m pytest oai-core/tests/test_hermes.py oai-core/tests/test_hermes_native.py -q
```

未设置 `HERMES_SDK_ROOT` 时该项明确跳过，不能把跳过当作宿主兼容性已通过。
此前版本的 2026-10-25 结果见[历史验证记录](../docs/reviews/2026-10-25-unified-core-validation.zh-CN.md)，
不代表本次迁移提交的验证结果。本次 CI 把干净 Core wheel 安装、协议与安全回归、
固定 Hermes SDK 集成、DSH 桥和 Android 检查分别运行。

测试中的模型/宿主为原生协议替身；实际部署还需对应 Hermes/DSH/Codex 宿主与 Android
设备进行联调。真机本地确认、真实模型输出和服务端网络可达性不能由单元测试替代。

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [设备能力授权修复计划](../docs/reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md) | e5 `dev` 基线的历史拒绝链路、兼容迁移与安全验收 |
| [Android 唯一授权权威（ADR-0059）](../docs/adr/0059-android-is-the-sole-device-capability-authority.md) | 已接受的授权决策；分支实现和当前验证见[实施记录](../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md) |
| [第三方 Agent 适配器开发指南](../docs/guides/agent-adapter-development-guide.zh-CN.md) | 统一会话协议、能力声明、通用 Remote 适配器与插件模板 |
| [架构事实基线](../docs/architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md) | 每条行为对应的代码位置，文档更新的事实来源 |
| [独立部署与安全切换](../docs/deployment/2026-10-26-core-independent.zh-CN.md) | 干净安装、私密配置、Hermes 备份切换与回滚 |
| [统一 Core 迁移方案](../docs/plans/2026-10-25-unified-core-migration.zh-CN.md) | 协议增量、分阶段交付、数据兼容与回滚 |
| [原生会话适配器部署](../docs/plans/2026-10-25-native-session-adapters.zh-CN.md) | Hermes/DSH 宿主接入、环境变量、验证与回滚 |
| [Codex 设备隔离边界](docs/codex-device-isolation.zh-CN.md) | 共享会话与设备授权的边界与拒绝条件 |
| [DSH Session Adapter](../integrations/dsh-session-adapter/README.md) | DSH 宿主侧 Cordis bundle 安装 |
| [Hermes Session Adapter](../integrations/hermes-session-adapter/README.md) | Hermes 宿主侧仅会话插件安装 |
| [Gateway Protocol v2](../docs/contracts/gateway-protocol-v2.md) | 设备协议与本次扩展的规范正文 |
