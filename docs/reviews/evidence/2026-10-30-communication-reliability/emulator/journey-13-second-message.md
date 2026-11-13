# Journey：修复 APK 已有会话第二条消息

## Results

### Action：在消息输入栏输入另一条测试短句。 ✅
- 检查输入框为 `FOCUSED`，输入短句并确认完整保留。

### Action：点击发送。 ✅
- **命令**：`adb -s emulator-5554 shell input tap 975 1422`。

### Action：Verify：第二条用户消息正文与完整 Agent 回复各显示一次，未显示发送失败。 ❌
- **实际结果**：第二个完整 Agent 回复显示；对应用户气泡显示“此消息没有可显示的文本”，未显示输入的短句。
- **真实标识**：`clientMessageId=cm_95f1f182-6532-49e2-bb6a-457af37995ff`；`generationId=gen_29d3583d3977da86382fe99844057b12b42efdc6`；会话 `conv_b06de779-4322-4c03-b8e8-ccaaccdc4d10`。
- **后端计数**：该消息 Core POST 1 次（200）、Adapter POST 1 次（200）、宿主启动 1 次并完成，产生两条事件。
- **夹具核查**：主 Agent 后续确认受控宿主遗漏 `HermesHistoryPort.capture_submission` 的原稿绑定；此时原生历史按既定规则返回空的显示文本。该结果是夹具缺口，不将本次失败改记为通过；修正夹具后须在新会话独立重跑。
- **边界**：夹具结论来自主 Agent 的核查，本执行者未独立复核其实现。两条发送后端各执行一次不等于 UI 完整通过；原 APK 未确认消息的 Core POST 仍为 0。
