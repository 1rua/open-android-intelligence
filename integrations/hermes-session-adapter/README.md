# Hermes 原生会话适配器

在 Hermes Python 环境安装 `../../oai-core` 后，用标准 Hermes plugin loader 安装此目录。该插件替换旧 Gateway 的平台注册，保留真实账号会话绑定、SessionStore 和原生 dispatcher；只开放 loopback 会话接口，不注册旧设备工具。

部署前先停用旧 Gateway，保留原账号目录与主密钥，禁止同时写入同一目录。必须配置显式账号 allowlist，并在 Core 配置一对一账号映射。

完整安装、启动、权限边界、验证与回滚步骤见[原生会话适配器部署文档](../../docs/plans/2026-10-25-native-session-adapters.zh-CN.md)。
