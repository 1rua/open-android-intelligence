# Journey：最终 APK 息屏唤醒后发送

- **环境**：按调度配置 GPT-6.1 Terra；最终 APK `f29e714a…b110a0`，原 API 35 AVD。原会话 `conv_71b1e3a7-afb8-425d-901e-bf41f5ef100f`，不清数据、不修改锁屏安全设置。

## Results

### Action：息屏并检查显示器关闭。 ✅
- `adb -s emulator-5554 shell input keyevent KEYCODE_SLEEP`；随后读取系统状态，确认 `mWakefulness=Asleep`、`mState=OFF`、`mScreenState=OFF`。

### Action：唤醒并按实际界面解锁。 ✅
- `adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP`；布局及截图显示已直接返回 OAI，无密码或滑动解锁界面，无需额外解锁动作；未改变安全设置。

### Action：Verify：原会话完整，无需登录且无过期离线提示。 ✅
- 两条原用户正文及两个原完成回复均仍在，顶部显示已连接，没有登录表单或离线镜像提示。`final-screen-wake.png`、`final-wake-lockscreen.json`，仅本地留存，截图已目视检查。

### Action：确认焦点后输入新短句并发送。 ✅
- 用 `android layout` 确认输入框为 `FOCUSED` 且空，输入后再次核对一致；点击发送坐标 `975 2242`，仅点击一次。

### Action：Verify：新正文与完整回复各显示一次，生成完成。 ❌
- **终态与传输**：新回复已完成；`clientMessageId=cm_2315eb14-92c1-4b26-9bfc-cc998094568e`，`generationId=gen_fde3dd96b345a5816dccfdf1982b2a169c5e963f`。该消息的 Core POST、Adapter POST、宿主启动、宿主完成均各 1 次。
- **可见异常**：两次独立截图均显示该新用户气泡两份，其中一份与旧回复区域并列；没有把“各显示一次”判为通过。与此同时，重复读取布局树只列出一份新用户文本节点和一个对应完成回复。可见画面与语义布局不一致，具体是应用绘制、动画或模拟器图形层问题尚未定位，不能据此声称后端重复执行。
- **本地证据**：`final-screen-send.png`、`final-screen-send-confirmed.png`、`final-screen-duplicate-check.json`、`final-screen-status.json`，仅本地留存；截图均已先目视检查，未提交原始日志或正文。
- **结束计数**：控制接口 `userCount=10`、`turnCount=3`、`completedCount=3`、`liveTurns=0`。按旅程规则在失败处停止，未执行断网等后续场景。

## 最小复现与边界

1. 同一最终 APK 会话内完成两条正常消息。
2. 使用系统息屏键，核对显示器 OFF，然后唤醒，保持默认无锁屏界面。
3. 焦点确认后输入另一条短句，单次点击发送，等待该生成完成。
4. 不再操作界面，比较截图中的新用户气泡与布局树节点数量；按原始消息 ID 核对后端计数。

本样本只覆盖一次短时息屏/唤醒，不证明深度 Doze、长时间后台、物理手机或厂商省电策略。
