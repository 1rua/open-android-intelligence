---
status: accepted
date: 2026-10-26
---

# OAI Core 复用固定提交的上游 GatewayCore 设备状态机

**后续范围说明（2026-10-28）**：下文记录状态机的历史来源；当前实现已迁入 `oai_core.gateway`，不运行时依赖 Hermes 插件。对签名、配对、幂等和恢复行为的继承，不表示继续继承旧账号级设备能力许可。授权规则按 [ADR-0059](0059-android-is-the-sole-device-capability-authority.md) 修订为 Android 唯一权威，已在 `feature/android-device-authority-20261028` 实施，待 PR 合入 `dev`；分支测试和真机验收状态见[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。

OAI Core 的设备请求队列、认领、超时、取消、结果接收与事件语义直接复用 Hermes Gateway 插件
在固定提交 `b64dda00a0de9ff1a95a375dcf92f62b5a1027b3` 提供的 GatewayCore 实现，
Core 进程不重写这些状态机，也不另建第二套任务状态。CLI/API/MCP 的设备幂等账本只保存
参数摘要与请求 ID，权威状态仍由该实现拥有。

选择复用而不是重新实现，是因为该实现已经覆盖签名、防重放、授权版本与崩溃恢复等高风险语义
并有跨端一致性测试；重写会引入第二套必然漂移的安全边界。代价是把 Core 的设备行为锁定到该
提交，升级需要显式更新固定引用并通过既有回归；这比隐式跟随上游小版本更可审计。
