# Journey：运行中隔离 Core 崩溃重启

- **环境**：最终 APK `f29e714a…b110a0`、API 35 原 AVD；执行者按调度配置 GPT-6.1 Terra。模拟器 Wi-Fi/蜂窝原设置保持启用，不涉及现网。
- **基线**：`userCount=12`、`turnCount=5`、`completedCount=5`、`interruptedCount=0`、`liveTurns=0`。

## Results

### Action：新建、确认输入焦点后发送受控等待任务。 ✅
- `POST /hold-next {}`；新权威会话 `conv_92189dcb-b28c-4580-8149-322a17be74dd`，单次点击发送。
- 原输入 `cm_57b8b40c-cd6f-4f94-935d-5722704ad9d5`；生成 `gen_0484125191ed1b7ad692c0f3ffd1bc7b38ebffb5`。

### Action：Verify：首段回复可见，任务运行中。 ✅
- App 显示首段回复和停止入口；控制接口 `liveTurns=1`、目标未完成且未释放，宿主已 `host.started`。
- 故障前已有 Core `T2`、`core.operation.committed`、`core.adapter.submitted`，对应请求 `req0f505bde1aba433c9bc3`。

### 环境注入与 Action：Verify：隔离 Core 中断，App 通道断开。 ✅
- 使用已准备的 `python3 tmp/emulator-acceptance/core-lifecycle.py crash`。脚本在副作用前检查 PID、`backend.py` 命令、`--role core` 和隔离数据根目录，只中断 Core PID `33531`。
- Adapter PID `33080` 保持存活且命令未变；控制接口确认目标仍运行。App 显示通道已断开和生成结果尚未确认。截图已目视检查。

### 环境释放与 Action：Verify：重启后无需登录或刷新，补回原完成终态。 ✅
- Core 缺席时指定原 `generationId` 调用 `/release`，以实际状态确认宿主完成且 `liveTurns=0`，再执行受限脚本 `start`；新 Core PID `119999`，同端口启动。
- 未操作 App 登录、刷新或重开；App 自动恢复已连接，原正文及完整回复可见，原生成具有完成终态。

| 计数项 | 该原始输入观察结果 |
| --- | --- |
| Core T2 请求解析 | 1 |
| Core operation committed / adapter submitted | 各 1 |
| 宿主启动 / 宿主完成 | 各 1 |
| Core / Adapter 消息 handler 结束记录 | 均缺失，不能解释为 POST=0 |

- **结论**：在本次跨 Core 进程的追加阶段日志中未观察到原输入重发；任务执行不随 Core 观察中断取消。结束时 `userCount=13`、`turnCount=6`、`completedCount=6`、`liveTurns=0`。
- **本地证据**：`core-crash-off.png`、`core-crash-recovered.png`、`core-crash-all-stages.json`、`core-crash-finished-before-start.json`、`core-lifecycle.jsonl`，仅本地留存；不提交原始日志、正文或凭据。
- **边界**：单个已受理运行任务的恢复，不证明受理前中断、Adapter 重启、模型执行失败或全量崩溃矩阵。
