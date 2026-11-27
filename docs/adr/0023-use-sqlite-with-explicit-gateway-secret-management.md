---
status: superseded
superseded_by: ADR-0063
date: 2026-08-24
---

# 使用 SQLite 与显式 Gateway 密钥管理

> **2026-11-13 目标替代**：本决策中旧 Bridge、Gateway 或 Core 的部署、协议与服务端身份规则由 [ADR-0063](0063-adopt-dsh-remote-with-independent-client-and-host-identities.md) 与[新总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)取代；正文保留历史背景。本次仅更新文档，现有运行代码尚未切换。

Hermes 与 OpenClaw 的 Gateway Core V1 都以 SQLite 实现同一套协议存储语义、事务边界、约束、清理规则、迁移和一致性测试，不要求外部数据库。活动密钥、密码验证材料和附件数据使用 Gateway 主密钥加密；主密钥优先来自宿主 Secret Store，没有时由用户提供权限受限的专用密钥文件或一次性解锁来源，禁止与数据库一同导出、写入普通配置、日志、备份或诊断。密钥来源缺失或文件权限不安全时 Gateway 拒绝启动。
