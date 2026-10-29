# 原生结构化工具步骤归属回归记录

日期：2026-10-29。续修 PR #64，第一步基于 `f10bf29b`。测试只使用合成账号、临时 Core 数据库与合成的宿主历史，不读取真实 Hermes 会话数据。

## 第一步：宿主历史到原生会话的结构化投影

已有的正文关键词清理避免了普通回答误分类，但 `HermesHistoryPort.read()` 仍将无正文的原生工具调用转为“调用工具：名称”文字，原生调用 ID、参数、结果和 generation 归属没有交付到 Android。`HermesSessionAdapter.history()` 已支持透传 `executionSteps`，但上游未产生该字段。

最小回归直接经过真实 `HermesHistoryPort.read()` 与 `HermesSessionAdapter.history()`，输入为合成用户、工具调用、工具结果和最终回答。将 PR 原提交 `HEAD` 中的旧 `host_history.py` 通过独立 Python 模块执行同一回归时，查找结构化工具行失败（`StopIteration`）；修复后专项 `test_structured_tool_history.py` 为 **6 passed**。

修复方式：

- 按真实原生用户 `platform_message_id` 查当前账号、当前会话内已受理的 `clientMessageId`，再查持久 workflow 得到 `generationId`。无关联的旧历史不猜测 generation，保留原有普通文本展示。
- 用原生 `tool_calls[].id` 与 `tool_call_id` 关联调用和结果。下一条真实用户消息关闭当前关联范围，晚到的其他轮次结果不能被认领。
- `stepId` 为原生调用 ID，`eventId` / `revision` 来自真实原生消息行身份。工具名称和参数来自结构化 function 字段，输出来自匹配的工具结果。
- 没有结果时状态为 `unknown`；收到结果只表示 `completed`，不从结果正文猜测成功。仅宿主明确给出 `is_error=true` 时标记 `failed`。
- 工具调用行无正文时保留空正文并携带步骤，最终回答独立携带相同 generation。无需创建第二份聊天历史，也不改变设备授权。

验证命令（运行时使用现有隔离 Python 环境）：

```sh
PYTHONPATH=oai-core/src:integrations/hermes-session-adapter/src python -m pytest oai-core/tests/test_structured_tool_history.py -q
```

覆盖原生步骤身份、跨轮隔离、无明确 generation 的安全回退、无结果与明确错误区分、畸形工具字段安全跳过，以及真实历史端口和原生会话适配器贯通。该结果不代表真实模型、实时工具 start/finish 事件或手机画面已验收；Android 接收和展示在后续步骤完成。
