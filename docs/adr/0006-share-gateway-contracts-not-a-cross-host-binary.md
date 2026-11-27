---
status: superseded
superseded_by: ADR-0063
date: 2026-08-21
---

# 共享 Gateway 契约而非跨宿主二进制

> **2026-11-13 目标替代**：本决策中旧 Bridge、Gateway 或 Core 的部署、协议与服务端身份规则由 [ADR-0063](0063-adopt-dsh-remote-with-independent-client-and-host-identities.md) 与[新总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)取代；正文保留历史背景。本次仅更新文档，现有运行代码尚未切换。

Hermes 与 OpenClaw 的 Gateway 适配器共享协议定义、数据模型、错误码、迁移语义、测试向量和一致性测试，但分别使用各自宿主的原生插件语言实现 Gateway Core。V1 不引入 Rust、WASM 跨语言核心或共同子进程，因为这会增加安装和发布复杂度，或重新形成 sidecar；只有在两端真实实现证明存在高价值、纯逻辑且容易漂移的公共部分后，才重新评估共享可执行核心。
