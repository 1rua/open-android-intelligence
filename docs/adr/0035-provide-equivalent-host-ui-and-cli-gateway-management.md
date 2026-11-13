---
status: superseded
superseded_by: ADR-0063
date: 2026-08-24
---

# 提供等价的宿主界面与命令行 Gateway 管理

> **2026-11-13 目标替代**：本决策中旧 Bridge、Gateway 或 Core 的部署、协议与服务端身份规则由 [ADR-0063](0063-adopt-dsh-remote-with-independent-client-and-host-identities.md) 与[新总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)取代；正文保留历史背景。本次仅更新文档，现有运行代码尚未切换。

Hermes 与 OpenClaw Gateway 适配器同时提供宿主内管理界面和语义等价的本地命令行，用于账号创建与重置、邀请、证书信任、备份恢复、插件状态和审计导出。两种入口调用同一管理服务与授权规则，不能各自形成不同状态；删除账号、撤销全部设备、重置身份等敏感操作仍要求安全的本机确认，不能因命令行可用而默认开放远程管理。
