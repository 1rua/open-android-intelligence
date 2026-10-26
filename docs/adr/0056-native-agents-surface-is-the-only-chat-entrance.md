---
status: accepted
date: 2026-10-26
---

# OAI Core 不提供旧聊天路由，原生 /agents 是唯一会话入口

OAI Core 刻意不实现 `/open-android-intelligence/v2/conversations*` 聊天路由，
并在协商响应中剔除 `conversationUi` 可选能力；会话读写全部走
`/open-android-intelligence/v2/agents` 原生会话面，直接对接各宿主真实的会话存储。
协商成功时以响应头 `X-OAI-Agent-Sessions: 1` 显式声明该扩展，新版 App 仅在标记存在时
装配原生会话界面，旧宿主无需处理未知探测路由。

不保留旧聊天路由的兼容层，是因为 Core 侧没有也不应有第二份聊天历史；兼容只会制造
影子会话数据库和两套权威。代价是旧 App 的聊天功能在 Core 上不可用——这类部署必须
回退到原 Gateway，`messages: chat-v1` 等字段仅作为认证与设备协议的兼容基线保留。
