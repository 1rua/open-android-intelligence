---
status: superseded
superseded_by: ADR-0063
date: 2026-08-22
---

# 支持多种 HTTPS 暴露方式并严格检查宿主兼容性

> **2026-11-13 目标替代**：本决策中旧 Bridge、Gateway 或 Core 的部署、协议与服务端身份规则由 [ADR-0063](0063-adopt-dsh-remote-with-independent-client-and-host-identities.md) 与[新总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)取代；正文保留历史背景。本次仅更新文档，现有运行代码尚未切换。

Gateway 适配器支持使用宿主已有 HTTPS 路由、仅监听本机并由用户现有反向代理暴露，或由用户显式提供证书后直接监听网络；默认优先宿主路由与本机监听，禁止默认公网暴露，且所有方式提供相同的 Gateway Protocol v2。Hermes 与 OpenClaw 适配器分别声明并验证已测试的宿主和插件 API 最低、最高版本；范围外保持数据只读，不执行迁移、不监听网络、不接收设备请求，也不允许用忽略警告绕过。
