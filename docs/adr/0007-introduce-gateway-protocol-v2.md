---
status: superseded
superseded_by: ADR-0063
date: 2026-08-21
---

# 引入 Gateway Protocol v2 并终止旧 Bridge 网络兼容

> **2026-11-13 目标替代**：本决策中旧 Bridge、Gateway 或 Core 的部署、协议与服务端身份规则由 [ADR-0063](0063-adopt-dsh-remote-with-independent-client-and-host-identities.md) 与[新总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)取代；正文保留历史背景。本次仅更新文档，现有运行代码尚未切换。

Android 宿主与 Agent 内 Gateway 使用新的 Gateway Protocol v2，不要求新插件模拟旧的独立 Bridge 网络接口，也不长期并行维护 Bridge v1。新协议复用既有安装实例身份、消息签名、防重放、幂等、类型化命令和测试向量等安全资产；只有存在真实部署数据时才提供一次性迁移工具，不把旧拓扑假设带入新协议。
