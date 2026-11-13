# Journey：同一终版 APK 的三项故障链路

- **执行者与版本**：按原调度配置 GPT-6.1 Terra；原 AVD `emulator-5554`、API 35。设备包摘要在本批开始时只读核对为 `d72906941d5aaf5becfb65e151076caef9f3cc38a4b444b0e5a4b470ed17fe48`；BuildConfig 构建时 `99f41bf3` 加来源修复/诊断补丁，关键业务源与 `3b07cb64` 的对应关系沿用主 Agent 固定的摘要校验，本批工作树 HEAD 为 `fce34fb1`。
- **直接复用**：App PID `7484`、模拟器 PID `35387`、Core PID `119999`、Adapter PID `33080` 均存活，因此没有重启 App/模拟器、重装 APK 或清数据。动画/GPU 未改；只在指定样本通过受限脚本中断隔离 Core，不操作现网。
- **基线**：`userCount=21`、`turnCount=14`、`completedCount=13`、`interruptedCount=1`、`liveTurns=0`。本批三项均使用同一 d729 包，不合并旧 f29 包验收。

## 1. 已受理任务断开模拟器网络并恢复：通过

新权威会话 `conv_badfa279-168e-4bb2-a2ca-64d42db961da`，确认空输入栏焦点后单次发送。宿主已 started、首段正文可见且 `liveTurns=1` 时，仅关闭模拟器 Wi-Fi 和蜂窝数据。实际核对 `Active default network: none`，并等 App 明确显示“通道已断开”及待确认提示后，才指定原 generation 调用 release。

宿主在模拟器仍无默认网络时完成，随后恢复原 Wi-Fi/蜂窝设置。App 无需登录、手动刷新或重发，自动补回原生成的完整回复及完成终态。截图已目视：正文和回复各一份，没有重复气泡/旧操作图层。网络已恢复。

## 2. 已受理任务隔离 Core 崩溃恢复：通过

新权威会话 `conv_92ea7235-758a-4ffc-a429-c3d073672100`；确认宿主 started、首段回复与运行状态后，故障前已保存 Core T2、operation committed、adapter submitted。运行 `python3 tmp/emulator-acceptance/core-lifecycle.py crash`，脚本严格校验记录 PID、backend.py 命令、core 角色及测试数据根目录，仅中断 Core PID `119999`。

Adapter PID `33080` 保持存活，控制接口确认原任务继续 live；App 显示通道已断开。指定原 generation release 后，宿主在 Core 缺席期间完成，再用同脚本 start 启动同目录 Core，新 PID `303166`。

App 无需登录、手动刷新或重发，自动补回同一 generation 的完整回复和已连接状态；正文、终态与截图一致，未见重复像素。跨进程累计 Core T2=1、宿主启动/完成各 1。Core/Adapter 消息 handler 结束记录均缺失；这是流处理中断的证据限制，不能误写为“POST 未收到”或用日志行数 0 否认已受理。

## 3. HOME 返回后停止同一运行 generation：通过

新权威会话 `conv_d5bcf437-38c5-4f7f-9763-2f5c3462566a`；hold 首段后确认 host.started 和运行中，HOME 后从真实系统应用列表返回。点击前再次核对当前 UI 回复身份、会话、停止入口与唯一实际活动 generation 一致；历史已取消但 completed=false 的生成不误当 running。

仅单次点击停止，没有 release 假取消。Core 真实 SSE `T5` 为 `done/state=cancelled`，关联原 clientMessageId 与 generationId；宿主 `host.cancelled=1`，全局 `interruptedCount` 从 1 增至 2，`liveTurns=0`。App 显示“本次生成已停止”，停止入口消失，正文/首段回复保留；截图无重复。正常完成计数不增是取消场景的预期，不记失败。

## 三条原始身份及次数

| 场景 | clientMessageId | generationId | Core T2 | Core/Adapter 消息 POST 结束记录 | host.started | host.finished | host.cancelled |
| --- | --- | --- | ---: | --- | ---: | ---: | ---: |
| 网络恢复 | `cm_80d4d73b-046f-4205-a0b2-e14e5837228c` | `gen_f3837d230f879d02d8aca6243c374423b8fde4cc` | 1 | 1 / 1 | 1 | 1 | 0 |
| Core 恢复 | `cm_c0cdae42-1e2e-4a76-8760-f0cc3d1ea4e7` | `gen_b3f1aa08f4c6eddd8945709584dfc0d7a8df9adc` | 1 | 缺失 / 缺失，不能解释为未到达 | 1 | 1 | 0 |
| HOME 后取消 | `cm_b7fe1b34-31b7-4739-bed3-5ca5b89f04db` | `gen_d528dc7898786e9f85d2862e5c79a1662ee0dc8f` | 1 | 1 / 1 | 1 | 0 | 1 |

取消真实回执请求标识 `req8e3de6a24e79487c8aae`。每个样本独立创建、单次输入，不重复点击，不将观察断开当作取消。

## 本地证据与结束状态

`f26-net-recovered.png`、`f26-core-recovered.png`、`f26-cancel-cancelled.png` 均已先目视检查；相应完整布局、状态、逐层 journal 及 `f26-stage-counts.json`、`f26-core-stages.json` 仅在工作树 `tmp/emulator-acceptance/` 本地留存。用户正文、凭据、截图、XML、JSON 和原始日志不提交。

结束状态 `userCount=24`、`turnCount=17`、`completedCount=15`、`interruptedCount=2`、`liveTurns=0`；模拟器 Wi-Fi 和蜂窝数据按原设置启用，App/模拟器、隔离 Adapter 和新 Core 均保持运行。旧包报告保留；未将顺序创建称为高频并发，未扩展全弱网矩阵、排队、实际大模型或物理手机/厂商 Doze 范围。本批只新增本 Markdown，不修改公共总报告。
