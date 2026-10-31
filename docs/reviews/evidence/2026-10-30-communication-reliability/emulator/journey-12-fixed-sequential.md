# Journey：修复 APK 新会话连续发送

## Results

### Action：在消息输入栏输入第一条测试短句。 ✅
- 点击输入栏并确认 `FOCUSED`，输入短句后布局确认内容完整。

### Action：点击发送。 ✅
- **命令**：`adb -s emulator-5554 shell input tap 975 1422`。

### Action：Verify：第一条用户消息及其完整 Agent 回复各显示一次，未显示发送失败。 ❌
- **实际结果**：Agent 完整回复显示一次，状态为处理完成；用户消息气泡却显示“此消息没有可显示的文本”，未显示输入的短句。会话标题包含测试短句；正文不写入文档。截图 `12-first-message-raw.png` 仅本地留存。
- **真实标识**：`clientMessageId=cm_d94a28e3-2cff-4097-96d9-50ae3c9a368d`；`generationId=gen_33219c7426a5d07f31d617207f9d9a73d9d736ea`；会话 `conv_b06de779-4322-4c03-b8e8-ccaaccdc4d10`。
- **后端计数**：该消息 Core POST 1 次（200）、Adapter POST 1 次（200）、宿主启动 1 次并完成，宿主产生两条事件。
- **夹具核查**：主 Agent 后续确认受控宿主只追加消息，遗漏真实运行时会调用的 `HermesHistoryPort.capture_submission`，导致原稿 presentation 未绑定；原生历史按既定规则返回空的显示文本。该结果是夹具缺口，不据此要求修改产品投影；本次 UI 验收结果仍保留为失败，修正夹具后须在新会话独立重跑。
- **边界**：夹具结论来自主 Agent 的核查，本执行者未独立复核其实现。按旅程规则在此停止，后续第二条另立独立旅程。

### Action：在消息输入栏输入第二条测试短句。

### Action：点击发送。

### Action：Verify：两条用户消息和对应的完整 Agent 回复各显示一次，未显示发送失败。
