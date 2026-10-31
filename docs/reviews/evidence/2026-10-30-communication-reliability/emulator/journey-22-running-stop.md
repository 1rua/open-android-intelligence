# Journey：HOME 返回后停止同一运行任务

- **环境与前提**：按调度配置 GPT-6.1 Terra，最终 APK `f29e714a…b110a0`、API 35 原 AVD；隔离 Core 已恢复。基线 `userCount=13`、`turnCount=6`、`completedCount=6`、`interruptedCount=0`、`liveTurns=0`。

## Results

### Action：新建并在确认焦点后发送受控等待任务。 ✅
- `POST /hold-next {}`；权威会话 `conv_3153c9c5-6aa1-4f71-a567-0d8a5161e4a1`。输入框为 `FOCUSED` 且空，输入后核对一致，仅点击发送一次。
- 原输入 `cm_368b02aa-7ecf-43cd-b26b-99e200f4be0d`；生成 `gen_f788b6bb868bd0ddf6580d148671279e8274522a`。

### Action：Verify：首段内容可见，任务运行且停止入口存在。 ✅
- 宿主 `host.started`；控制接口唯一活动生成匹配上述 ID，`completed=false`、`released=false`、`liveTurns=1`。App 首段内容和停止入口可见。

### Action：HOME 后从系统应用列表返回。 ✅
- `adb -s emulator-5554 shell input keyevent KEYCODE_HOME`，布局确认系统桌面；上滑并读取应用列表中 OAI 图标，点击返回。

### Action：Verify：返回同一会话、原生成仍运行且停止目标明确。 ✅
- App 的首段回复语义标识包含原 `generationId`；停止入口可见。即将点击前再次读取控制接口，唯一运行生成及会话仍完全匹配，尚未释放，`interruptedCount=0`。
- 已目视检查 `stop-target-before.png`，仅本地留存。没有把回复条目的显示属性当作运行终态，目标由控制接口运行身份和当前会话共同确认。

### Action：点击当前任务的停止入口。 ✅
- 单次执行 `adb -s emulator-5554 shell input tap 964 1364`，没有调用 `/release` 使它正常结束。

### Action：Verify：原生成明确取消、不再运行，停止入口消失。 ✅
- Core 与 Adapter 的该会话 `/cancel` 各一次，均返回 200；Core 真实 SSE `T5` 回执为 `eventType=done`、`state=cancelled`，关联原始消息 ID、原 `generationId` 和请求 `req02c7cd42948c4211840d`。
- 宿主记录该生成 `host.cancelled` 一次，`interruptedCount` 从 0 增至 1，`liveTurns=0`；没有把观察断开当作取消。
- App 先显示等待停止确认，随后显示“本次生成已停止”，运行状态及停止入口消失；`stop-cancelled.png` 已目视检查，仅本地留存。

| 核对项 | 次数或变化 |
| --- | --- |
| 原输入 Core T2 / Core 消息 POST / Adapter 消息 POST | 各 1 |
| 原生成宿主启动 / 宿主取消 | 各 1 |
| 原生成宿主正常完成 | 0，取消场景的预期结果 |
| 定向取消 POST：Core / Adapter | 各 1，均 200 |
| 全局 interruptedCount / liveTurns | 0 → 1 / 1 → 0 |
| completedCount | 保持 6 |

- **结束状态**：`userCount=14`、`turnCount=7`、`completedCount=6`、`interruptedCount=1`、`liveTurns=0`；模拟器 Wi-Fi 和蜂窝数据仍按原设置启用，隔离服务保持运行。
- **本地证据**：`stop-cancelled-evidence.json`、`stop-return-status.json`、`stop-target-final-ui.json`、`stop-after-settled-ui.json`，仅本地留存；不提交正文、凭据、截图或日志。
- **边界**：只验证当前唯一运行任务在 HOME 返回后的定向停止，不证明任意多任务取消、队列取消或真实模型工具链中断。此前独立绘制问题仍保留原失败结果。
