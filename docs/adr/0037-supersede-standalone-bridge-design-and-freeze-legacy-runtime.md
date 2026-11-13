---
status: superseded
superseded_by: ADR-0063
date: 2026-08-24
---

# 取代独立 Bridge 设计并冻结旧运行时

> **2026-11-13 目标替代**：本决策中旧 Bridge、Gateway 或 Core 的部署、协议与服务端身份规则由 [ADR-0063](0063-adopt-dsh-remote-with-independent-client-and-host-identities.md) 与[新总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)取代；正文保留历史背景。本次仅更新文档，现有运行代码尚未切换。

2026-08-08 总设计、2026-08-11 Tailscale 默认链路决定和 2026-08-17 Bridge 生产部署设计由模块化插件架构取代，原文保留并明确标为历史。旧 `bridge-runtime` 在 Gateway Protocol v2 与两个宿主适配器通过一致性套件前只作为冻结迁移来源；可复用安全语义、存储逻辑和测试被提取后移入 `legacy`，不再发布或扩展 Docker/systemd 独立服务。
