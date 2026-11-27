# 原生工具过程归属与时间线展示验证

日期：2026-10-29。基线：`dev 4a5fd397`、`main d88fffed`。仅使用合成消息与隔离测试。

## 已验证的故障路径

`MarkdownParser.parse` 会把普通正文中的独立 `Tool call:` / `调用工具：` 行，或连续 `Running` / `Reading` / `Looking at the image` 行解析为工具块。`TimelineDisplayModel` 又用相同正文特征判断最终回答是否可见并把相邻助手消息归入执行段。因而没有宿主工具身份的普通回答也会变成工具过程，后续消息可能改变展示分组。

这条具体正文推断逻辑在本次最新 `main` 和 `dev` 都存在，不能将其归因于仅在 `dev` 出现的代码差异。统一原生会话入口是否放大其触发率，必须结合宿主事件与真实设备链路另行验证。

反馈命令：运行 `NativeToolTextTest`。它直接编译并调用真实 `MarkdownParser` / `MarkdownModel` 源码。修复前 3 项中 2 项失败，分别为普通 `Tool call: execute_code` 被转为工具块、多行活动关键词被转为工具块；XML/fenced 显式工具标记用例通过。

## 第一步修复

- 移除 Hermes 纯正文关键词工具识别和最终回答边界猜测。
- 保留 XML/fenced 显式执行标记、思考块、现有手动 adb 命令兼容路径。
- 无可靠工具元数据时，消息按普通正文安全显示；后续回复不能使其变成工具卡。
- 移除依赖旧推断的测试断言，以 `NativeToolTextTest` 与 `ExecutionDisplayTest.plainToolWordsStayOrdinaryMessagesAfterLaterReplies` 锁定新的契约。

## 结构化事件的宿主边界

固定 Hermes SDK 中存在 `ToolCallChunk`、`ToolCallFinished`，但 Session Adapter 的 `format_tool_event` 边界只收到 start 且受宿主工具显示模式过滤，finish 不转发该边界。不能宣称该接口能够完整、实时提供所有工具开始和完成事实。

原生历史的 `tool_calls[].id` 与工具结果 `tool_call_id` 则可作为稳定归属依据。后续结构化投影必须使用这些真实身份，并仅在客户端消息和执行记录存在可验证关联时附带 `generationId`。没有明确工具结果时展示结果未知，不能伪造成功或仍在执行。
