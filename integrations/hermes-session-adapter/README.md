# Hermes 原生会话适配器

在 Hermes Python 环境安装 `../../oai-core` 后，用标准 Hermes plugin loader 安装此目录。该插件替换旧 Gateway 的平台注册，保留真实账号会话绑定、SessionStore 和原生 dispatcher；默认仅绑定 `127.0.0.1:8792` 开放 loopback 会话接口，不注册旧设备工具。所配 `OAI_HERMES_SESSION_TOKEN` 至少 32 字符，仅用于内部会话认证，不能充当 Core 账号或设备凭据。

部署前先停用旧 Gateway，保留原账号目录与主密钥，禁止同时写入同一目录。必须配置显式账号 allowlist（`OAI_HERMES_ACCOUNTS`），并在 Core 配置一对一账号映射。会话请求严格受小于 1 MiB 紧凑 UTF-8 JSON 预算限制，原生审批在无界面时明确拒绝并返回 `UNSUPPORTED_APPROVAL`。

架构细节与代码依据见[架构事实基线](../../docs/architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md)，完整安装、启动、权限边界、验证与回滚步骤见[原生会话适配器部署文档](../../docs/plans/2026-10-25-native-session-adapters.zh-CN.md)。
