---
status: accepted
date: 2026-10-26
---

# 用单一 OAI Core 进程统一承载设备权威与多 Agent 原生会话

此前每类 Agent 宿主各自内嵌 Gateway 适配器，手机要为不同 Agent 维护不同的服务地址与凭据，
设备授权分散在各宿主内。现在改为一个独立可安装的 `oai-core` Python 项目：`oai serve`
以单一监听地址同时承载 Android 设备协议、设备调用面（CLI/API/MCP）与原生 Agent 会话接入，
不同 Agent 的会话出现在同一个入口里。

选择独立进程而不是继续在每类宿主内嵌适配器，是因为设备权威（配对、授权版本、幂等、审计）
与宿主种类天然正交，收拢后 App 只需一个连接配置；代价是宿主侧需要新增仅会话的接入形态
（Session Adapter），且旧聊天入口不再由该进程提供。旧独立 Gateway 部署继续可用；
OpenClaw 不在本次装配范围内。完整行为边界见
[架构事实基线](../architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md)。
