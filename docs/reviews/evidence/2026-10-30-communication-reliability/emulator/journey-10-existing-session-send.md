# Journey：原 APK 既有会话发送

## Results

### Action：在消息输入栏输入一条测试短句。 ✅
- **命令**：点击输入栏，确认布局为 `FOCUSED` 后执行 `adb -s emulator-5554 shell input text <测试短句>`。
- **前提**：已有烟测会话，顶部显示已连接，但同时有“离线镜像”提示；不作为新建会话测试。

### Action：点击“发送”。 ✅
- **命令**：`adb -s emulator-5554 shell input tap 975 1422`。
- **实际结果**：输入框清空，出现本地消息气泡和等待状态。

### Action：Verify：显示一条用户消息和一条完整 Agent 回复，未显示发送失败。 ❌
- **实际结果**：显示“等待 Gateway 接收”“AI 正在思考”和“消息已接收，等待回复”，未出现该消息的完整回复。输入内容仅保留在本地截图 `10-send-original-raw.png`，不归档正文。
- **原始关联**：`clientMessageId=cm_a3f910d3-aa3d-4ab2-be6a-4a14726cee4c`；会话 `conv_2ffedf97-f289-4099-84e4-47da538ef91f`；未观察到该消息的 `generationId`。
- **后端核对**：截至诊断版覆盖安装后检查时，该 ID 的 Core 消息 POST、Adapter 消息 POST、`host.started` 均为 0；控制接口计数仍为基线 `userCount=3`、`turnCount=1`、`completedCount=1`、`liveTurns=0`。
- **边界**：这只证明观察窗口内未送达，不能断言该待处理消息以后不会发送。后续应继续按该 ID 检查，不能重复手动发送以制造通过结果。
