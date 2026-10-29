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

## 第二步：压缩边界与重复原生调用

增加两项边界后专项先得到 `2 failed, 5 passed`：压缩摘要没有原请求身份，后续调用仍沿用了前一轮 generation；同一原生 call ID 的重复源行被转成额外的“调用工具”正文。修复后 `7 passed`。压缩摘要现在关闭当前关联，重复调用只保留由真实 ID 唯一标识的结构化步骤，不额外伪造正文。

## Android 展示的失败反馈

真实 `:conversation-ui:testDebugUnitTest` 中的 `StructuredExecutionDisplayTest` 首轮为 **5 tests，4 failures，0 skipped**。没有结构化聚合时，多条源消息仍是单行，过程卡为空，步骤版本无法按 ID 合并；无 generation 的普通正文回退用例通过。后续修改将以这四项失败作为反馈信号。

## 第三步：结构化卡片与展示状态

修复后，`StructuredExecutionDisplayTest` 扩展到 6 项，`StructuredToolComposeTest` 为 3 项，两类共 9 项在恢复后的持久 worktree 中全部通过。覆盖单轮一张过程卡、独立最终回答、多轮隔离、重复步骤只取较新版本、审批行保留，以及“已完成”不伪称成功、“结果未知”不伪称失败。

Compose 的 T9 诊断仅在显式调试开关下记录首个可见快照及非流式快照；正文继续变化不会每个 token 写日志，测试确认日志不含合成私有正文。此处测量的是组合成功应用的边界，不等于显示屏或 GPU 已完成呈现。

同轮的兼容验证为 conversation-ui 154 项，其中 153 项通过；剩余一项来自另一个尚未提交的 Workbench 受理逻辑修改，暴露了既有精确关联保护被绕过。该失败不记为通过，交由消息恢复步骤修正。本步骤只提交结构化展示与必要的可选模型字段，原生 HTTP 解码、消息恢复和实时工具钩子另行验证。
