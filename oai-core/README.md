# OAI Core

一个服务监听地址承载 Android 登录、配对、设备事件与原生 Agent 会话。
`oai serve`、CLI 与 MCP 属于同一个 Python 项目；设备请求复用固定提交的
Hermes GatewayCore，不在 Adapter 内另写排队、超时、取消或结果状态机。
Android Platform Kernel、`.alp` 签名、WASM 沙箱与本地确认机制保持原样。

## 安装与启动

Python 3.11+。在仓库根目录安装，契约来自本仓库的 `gateway-contract`：

```sh
python -m venv .venv
.venv/bin/python -m pip install -e ./oai-core
source .venv/bin/activate
.venv/bin/oai account create --config oai-core/examples/core.json --username alice
.venv/bin/oai serve --config oai-core/examples/core.json
```

账号密码通过交互输入，不出现在命令行参数中。示例默认只监听本机，未注册 Agent 时列表为空。
配置中的路径相对于配置文件。运行数据与主密钥分别位于 `runtime/accounts` 和
`runtime/secrets`，均不提交；主密钥不能位于账号备份目录内。
手机远程连接时配置 `listen.host`、`tls.certFile` 和 `tls.keyFile`，使用同一个 HTTPS
地址及端口登录。TLS 终止在反向代理时还须配置与手机看到的证书一致的 `tlsSpkiSha256`；
只在受控开发环境显式使用 `allowInsecureHttp`。

先在 Android 登录 Core 并配对，安装、启用和授权设备插件。
服务端账号能力开关与手机授权同时生效，例如：

```sh
oai policy set --config oai-core/examples/core.json --account alice --capability org.openandroidintelligence.notifications.query --enabled
oai login --url https://core.example --username alice --device DEVICE_ID
oai capabilities
```

`account` 和 `policy` 为服务端本机管理命令。远程 CLI/API/MCP 请求不接受账号或设备身份覆盖；
登录凭据绑定该账号下已配对设备、配对代次、授权版本和过期时间。撤销或授权变更立即使旧凭据失效。
客户端配置以 `0600` 保存，退出使用 `oai ... logout` 撤销凭据。
上述登录和后续命令使用同一个默认客户端配置。需要自定义路径时，每条客户端命令均加
`--client-config`。在操作人的独立终端环境登录，避免给共享原生 Agent 的运行身份保存设备凭据。

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
共享 Agent 不自动获得任何 Android 设备凭据；独立 CLI/MCP 需使用当前账号明确授权的凭据。
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
环境变量引用的适配器令牌用于 Core 到宿主的内部认证，不能作为 Core 账号或设备凭据。

Hermes 通过宿主中的 Session Adapter 保留账号映射：

```json
{
  "type": "hermes", "id": "hermes", "name": "Hermes",
  "url": "http://127.0.0.1:8792", "tokenEnv": "OAI_HERMES_SESSION_TOKEN",
  "accounts": {"alice": "alice", "bob": "bob"}
}
```

映射由管理员配置，必须一对一；不会依据 App 传入的账号选择身份。
宿主部署与原账号目录复用见 [Hermes Session Adapter](../integrations/hermes-session-adapter/README.md)。
停止原 Gateway 后再切换，禁止两个进程同时写原有账号目录。
OpenClaw 不注册到本次新 Core 装配，继续沿用旧接入方式。

## 协议、迁移与验证

新增 Agent API、数据兼容、备份和回滚步骤见
[迁移方案](../docs/plans/2026-10-25-unified-core-migration.zh-CN.md)。
签名写操作绑定原 request ID；原生操作回执只记录幂等摘要和管理结果，不存聊天正文。
流中断或进程重启后未知的写操作不会自动重发，先读取原生历史确认结果。

```sh
python -m pip install pytest pytest-asyncio
python -m pytest oai-core/tests -q
npm ci
npx vitest run integrations/dsh-session-adapter/adapter.test.js
```

测试中的模型/宿主为原生协议替身；实际部署还需对应 Hermes/DSH/Codex 宿主与 Android
设备进行联调。真机本地确认、真实模型输出和服务端网络可达性不能由单元测试替代。
