# OAI Core 最小可运行 Agent 插件模板

本目录提供了一个独立第三方 Agent 适配器的完整参考实现。

**2026-10-28 Android 设备授权规则**：适配器只负责协议接入与会话桥接，不另设设备能力授权入口。设备工具统一调用经认证并绑定目标设备的 Core CLI/MCP/API；Android 是设备能力授权的唯一权威，已安装且启用的设备插件能力默认开启，执行仍受本地显式开关、Android 系统权限和必要敏感确认约束。会话桥 Token 不作为设备调用凭据。规则已在 `feature/android-device-authority-20261028` 分支实施，待合入 `dev` 和真机验收；实现与验证见[实施记录](../../../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。第三方适配器仍按 [ADR-0059](../../../docs/adr/0059-android-is-the-sole-device-capability-authority.md) 和[第三方接入指南](../../../docs/guides/agent-adapter-development-guide.zh-CN.md)执行。

## 目录结构

- `adapter.py`：实现 `SessionAdapter` 协议的适配器类与工厂函数。
- `config.example.json`：如何将该适配器配置进 OAI Core 的 `core.json`。
- `pyproject.toml`：展示如何通过 `entry_points` 自动暴露给 OAI Core。
- `test_adapter.py`：可独立执行的契约与单元测试。
