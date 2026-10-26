# Codex 共享会话与设备授权边界

Codex Adapter 通过管理员显式选择的 `command` 和 `codexHome` 接入一个原生
app-server 实例。该实例中 CLI、脚本及 App 创建的可读取会话向当前 Core
部署的全部已认证账号开放；Core 不复制会话数据库，也不建立账号归属绑定。
这同时意味着会话中的消息和已返回的工具结果属于共享范围。

共享 Codex Adapter 发布 `deviceTools: false`，不会向原生进程或会话发送当前
账号的 OAI 设备令牌。该阶段没有能把共享原生执行绑定到当前 App 账号的可信
设备工具接口，因此不能给此实例配置任意账号的固定 OAI CLI/MCP 凭据。
独立的 `oai login`、`oai invoke` 与 `oai mcp` 继续以调用者自己的账号和已配对
设备调用 Core；这不表示共享聊天实例可以自动继承这些权限。

共享 Codex 必须使用独立的操作系统服务账号，该账号的文件系统权限不能读取
其他账号的 OAI 客户端配置、令牌、配对密钥和 Core 的私有存储。不要在该服务
账号下执行 `oai login`。不要将已有 OAI CLI 配置、含设备凭据的 MCP 包装脚本
或其他账号的私有目录挂载到该实例。仅修改 `HOME` 无法替代操作系统权限隔离。
Adapter 的普通子进程启动继承 Core 的操作系统身份，不会自动降权。如果原生
Agent 允许执行宿主命令，管理员需要让 `command` 使用可信的降权或沙箱启动器，
使原生进程无法读取 Core 服务的账号密钥和私有存储；同时只挂载显式接入的
Codex 会话范围。环境变量过滤不提供这种文件系统隔离。

Adapter 默认只传递进程运行、语言区域、代理和 CA 信任所需的环境变量。
模型提供方凭据必须通过 `envFrom` 逐项显式选取。例如：

```json
{
  "id": "codex",
  "kind": "codex",
  "command": ["codex", "app-server"],
  "codexHome": "./codex-instance",
  "envFrom": {"OPENAI_API_KEY": "OAI_CODEX_MODEL_API_KEY"}
}
```

`envFrom` 的键是原生进程环境变量名，值是服务启动环境中的来源变量名。
Adapter 只允许已知模型提供方变量以及运行所需变量；`OAI_*`、`AUTHORIZATION`
和其他任意服务凭据不能作为目标变量传入。来源变量名可以自定义。
使用 Codex 自身保存在显式 `codexHome` 中的模型登录时，无需配置模型密钥变量。

启动原生进程前，Adapter 只检查默认 `HOME/.config/oai/client.json` 是否存在，
不会读取其内容；存在时拒绝启动并返回 `AGENT_UNSAFE_DEVICE_CREDENTIALS`。
显式 Codex 配置中的直接 `oai mcp`、`python -m oai_core.cli` 等已知静态 OAI
入口以及 `OAI_*` MCP 凭据也会被拒绝。此检查用于阻止常见配置错误，不能识别
任意脚本、外部配置、插件或额外路径中隐藏的凭据；独立服务账号仍是部署前提。

原生 shell、文件修改等审批继续由 Codex 自己决定。本阶段没有原生审批界面，
需要客户端审批的命令/文件操作会被拒绝，并向 App 返回
`NATIVE_APPROVAL_UNSUPPORTED`；不会因为 OAI 登录而自动批准这些操作。
