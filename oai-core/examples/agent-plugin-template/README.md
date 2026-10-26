# OAI Core 最小可运行 Agent 插件模板

本目录提供了一个独立第三方 Agent 适配器的完整参考实现。

## 目录结构

- `adapter.py`：实现 `SessionAdapter` 协议的适配器类与工厂函数。
- `config.example.json`：如何将该适配器配置进 OAI Core 的 `core.json`。
- `pyproject.toml`：展示如何通过 `entry_points` 自动暴露给 OAI Core。
- `test_adapter.py`：可独立执行的契约与单元测试。
