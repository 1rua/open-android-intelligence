---
status: accepted
date: 2026-08-21
---

# 将 Gateway 协议状态与 Agent 工作状态分离

**2026-10-28 授权范围更新**：本 ADR 中“Gateway/配对 × 插件许可”保留为历史规则，不再适用。现行决策见 [ADR-0059](0059-android-is-the-sole-device-capability-authority.md)：Android 是唯一设备能力授权者，普通前台能力默认开启；后台同步是独立的 Android 本地许可。该规则已在 `feature/android-device-authority-20261028` 实施，待 PR 合入 `dev`；分支测试与真机验收状态见[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。协议状态隔离、插件身份、故障关闭和审计规则继续有效。

Gateway Core 在 Agent 宿主分配给插件的数据目录中持久保存配对、防重放、消息队列、附件生命周期、操作回执和审计状态；Hermes 或 OpenClaw 继续拥有对话、任务、模型运行和长期记忆，不把聊天记录当作设备协议数据库。手机级安装和启用插件不自动授权任何 Gateway：授权与后台同步均按“配对 × 插件”单独开启，对话只连接当前活动 Gateway。Hermes 与 OpenClaw 可以采用不同的宿主监听集成方式，但必须向 Android 提供相同的 HTTPS 协议；不得为统一端口而重新引入外部 Bridge 或 sidecar。
