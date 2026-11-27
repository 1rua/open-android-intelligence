---
status: superseded
superseded_by: ADR-0063
date: 2026-08-24
---

# 从 Bridge v1 迁移时重新配对而不复制身份

> **2026-11-13 目标替代**：本决策中旧 Bridge、Gateway 或 Core 的部署、协议与服务端身份规则由 [ADR-0063](0063-adopt-dsh-remote-with-independent-client-and-host-identities.md) 与[新总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)取代；正文保留历史背景。本次仅更新文档，现有运行代码尚未切换。

Gateway Protocol v2 不导入旧 Bridge 的配对私钥、消息队列、未确认请求或数据库身份，也不提供 v1 线协议兼容层。迁移工具只允许单向导出经白名单定义的非敏感连接配置与插件清单；每台 Android 设备在新逻辑 Gateway 上重新配对、重新确认 TLS 身份并重新授予插件能力，从而避免把旧信任和不确定副作用静默带入新边界。
