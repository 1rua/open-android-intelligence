---
status: accepted
date: 2026-08-24
---

# 将 Tailscale 作为可选 Companion 插件运行

> **2026-11-13 目标范围**：Tailscale 仍可为独立可选 companion；核心连通由 Remote 选路承担，不将它作为身份或控机前置条件。 见[新总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)。本次仅确认目标，不宣称运行时代码已变更。

Tailscale 不再是 Android 默认链路，现有 tsnet AAR、原生绑定、持久化与真机证据迁入独立 Companion APK 连接插件。Companion 只提供到同一 HTTPS Gateway 的网络路径，不持有账号密码、刷新凭据、设备私钥或 Gateway access token；Android 平台内核以短期单用途 IPC 令牌调用。插件默认不安装、不启用，故障时连接失败关闭并允许用户显式回到直接 HTTPS。
