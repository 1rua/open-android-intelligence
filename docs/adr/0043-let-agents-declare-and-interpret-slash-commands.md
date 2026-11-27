---
status: accepted
date: 2026-08-29
---

# 由 Agent 声明并解析斜杠命令

> **2026-11-13 目标范围**：命令由真实 Agent 解释的原则保留，旧 Gateway 命令传输由 Remote 后端适配取代；手机控制 CLI 仅适用于确定性控制会话。 见[新总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)。本次仅确认目标，不宣称运行时代码已变更。

Hermes 与 OpenClaw 必须向 Android 提供按 Gateway 和语言版本化、由该 Gateway 全部对话线程共用的 Agent 命令目录；Android 只缓存、展示并通过普通消息路径原样透传斜杠命令，普通命令先填入输入框供用户确认，顶部“新建对话”可以直接发送无参数 `/new`，离线缓存只用于展示而不自动发送。目录和未知命令都不成为本地设备授权或 App 端命令解释器，斜杠命令始终作为普通文本合并批次的边界。`/new` 由 Agent 统一解析为创建不继承旧上下文的新 Agent 对话会话：Android 生成 `clientConversationId` 与幂等请求标识，Agent 宿主生成内部 `agentSessionId`，Gateway Adapter 原子创建 `conversationId`、保存绑定并返回结构化创建结果；即使旧对话仍在生成，`/new` 也由命令入口立即处理，Gateway 认证 `sessionId` 保持不变。命令保留在来源对话中，新对话从空上下文开始，旧对话及其生成继续可恢复。
