---
status: superseded
superseded_by: ADR-0063
date: 2026-08-21
---

# Gateway Protocol v2 使用 HTTPS 请求与 SSE 事件流

> **2026-11-13 目标替代**：本决策中旧 Bridge、Gateway 或 Core 的部署、协议与服务端身份规则由 [ADR-0063](0063-adopt-dsh-remote-with-independent-client-and-host-identities.md) 与[新总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)取代；正文保留历史背景。本次仅更新文档，现有运行代码尚未切换。

Gateway Protocol v2 使用普通 HTTPS 请求承载配对、消息、确认、设备事件和附件操作，使用 SSE 承载流式回复与服务端事件；Android 以有界事件游标断点恢复，不把长连接在线视为可靠性前提。V1 不同时维护 WebSocket 等价协议。一个 Gateway 可以拥有多个对话线程，每个线程只属于该 Gateway，并绑定宿主自己的会话标识；不同 Gateway 不共享或继承对话上下文。
