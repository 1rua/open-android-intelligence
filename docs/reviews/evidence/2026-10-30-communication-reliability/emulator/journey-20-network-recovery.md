# Journey：运行中断开模拟器全部网络并恢复

- **执行环境**：按调度配置 GPT-6.1 Terra；最终 APK `f29e714a…b110a0`，API 35 原 AVD `emulator-5554`。只修改专用模拟器网络，不操作现网、不清数据、不重装 APK。
- **基线**：控制接口 `userCount=10`、`turnCount=3`、`completedCount=3`、`liveTurns=0`；原模拟器 Wi-Fi 与蜂窝数据均启用。

## Results

### Action：新建会话，确认焦点后输入独特短句并发送。 ✅
- `POST /hold-next {}` 返回已设置等待；App 新建权威会话 `conv_50186537-8b2a-4769-b98c-dcd79983a2b1`。
- 用 `android layout` 确认空会话；确认输入栏 `FOCUSED` 后输入，核对内容一致，仅点击发送一次。

### Action：Verify：已受理并运行，首段内容可见。 ✅
- 控制接口 `liveTurns=1`，目标生成 `completed=false`、`released=false`；宿主已有对应 `host.started`。App 显示首段回复、正在接收回复及停止入口。
- 原输入：`cm_afda04c1-7fc0-4768-8adf-259e56f6cb7e`；生成：`gen_299be6340844aceec74e8da9677c8b1bf819a1c9`。

### 环境注入与 Action：Verify：真实通道已断。 ✅
- 执行 `adb -s emulator-5554 shell svc wifi disable` 和 `svc data disable`。初始系统网络信息尚未收敛，未立即判定成功；再次读取后确认 Wi-Fi disabled、蜂窝数据为 0、`Active default network: none`。
- App 明确显示“通道已断开”及“生成结果尚未确认”，首段内容仍在；宿主依然运行，尚未释放。截图已目视检查。
- **观察边界**：释放前未记录到服务端 `core.observer.detached`；该阶段以模拟器无默认网络和 App 通道断开共同证明客户端已断。服务端 detached 在释放、流处理结束后出现，不能倒推为它在释放前已经记录了断开。

### 环境释放与 Action：Verify：恢复后自动补回同一生成的完成。 ✅
- 在模拟器仍无默认网络时，`POST /release` 指定原 `generationId`；观察到宿主完成，控制接口确认该生成已完成且 `liveTurns=0`。
- 再执行 `svc wifi enable` 和 `svc data enable`，恢复原设置。App 无需登录、无需手动刷新，自动显示同一生成的完整回复和已连接状态，不再显示待确认提示；截图已目视检查。

### Action：恢复后再发一条并检查完整回复。 ✅
- 再次确认输入栏焦点及空内容，输入另一条测试短句并仅点击发送一次。新正文和完整回复可见，生成完成；未为回避既有绘制问题修改正文或执行器。

| 样本 | clientMessageId | generationId | Core T2 | Core POST | Adapter POST | 宿主启动 | 宿主完成 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 断网中的原任务 | `cm_afda04c1-7fc0-4768-8adf-259e56f6cb7e` | `gen_299be6340844aceec74e8da9677c8b1bf819a1c9` | 1 | 1 | 1 | 1 | 1 |
| 恢复后新输入 | `cm_36aedbb5-d19b-4a79-b92c-0e851919bd19` | `gen_b9e3d8bcc26263819384b4f7979609e7339cac0d` | 1 | 1 | 1 | 1 | 1 |

- **结论**：本样本原任务没有重复 POST 或执行；断网期间宿主继续完成，客户端恢复后补回同一生成终态，并能继续发送。
- **结束状态**：`userCount=12`、`turnCount=5`、`completedCount=5`、`liveTurns=0`；蜂窝数据恢复为 1，系统默认网络重新建立。
- **本地证据**：`net-disconnected.png`、`net-recovered.png`、`net-follow-completed.png`、`net-sample-counts.jsonl`、`net-core-stages.json`、`net-app-stages.json`，仅本地留存；不提交截图、原始布局、日志或正文。
- **未覆盖**：本批没有执行 Core 中断备用场景、进程恢复、队列、停止、多轮弱网矩阵或真实手机省电测试。此前独立绘制失败保持原判定，不因本次通信通过而覆盖。没有用固定睡眠冒充状态恢复，判断均基于实际状态读取。
