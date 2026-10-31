# Journey：加载失败时输入框保留文字

## Results

### Action：点击消息输入栏。 ✅
- **命令**：`adb -s emulator-5554 shell input tap 466 2242`。
- **检查**：布局中输入框状态为 `FOCUSED`。

### Action：在已获得焦点的输入栏输入测试短句。 ✅
- **命令**：`adb -s emulator-5554 shell input text <测试短句>`；未点击发送。

### Action：Verify：输入栏保留所输入的完整短句。 ✅
- **检查**：布局中的输入框仍为 `FOCUSED`，内容与输入的 16 个字符完全一致；截图目视确认。
- **截图**：`07-editor-redacted.png`，仅本地留存；输入内容及键盘建议已遮盖。
- **边界**：证明本次可编辑和保留文字，不证明发送、网络连接或控制器生命周期正常。
