# Journey：最终 APK 新会话两条正文与独立回复

- **执行者**：按主 Agent 调度配置 GPT-6.1 Terra；不以自行探测结果声明底层模型。
- **环境**：原 AVD `emulator-5554`、Android 15 / API 35；执行环境进程丢失后复用原磁盘恢复，没有清数据。最终 APK 覆盖安装后保留登录，SHA-256 为 `f29e714ab5c3a635ea61c941e558d4b8d8ee524ed0f34626cc871dfcbbd110a0`，BuildConfig `f49d3d8d` 加未提交修复补丁。
- **计数范围**：最终版发送前 `userCount=7`、`turnCount=0`、`completedCount=0`、`liveTurns=0`。执行计数因后端进程重启归零；下表按跨进程追加 journal 中的真实消息 ID 分别核对，不混用旧基线。

## Results

### Action：新建并检查权威空会话。 ✅
- 点击前读取布局；执行 `adb -s emulator-5554 shell input tap 859 212`，观察到 `native.creation_saved` 和 `create.ui_applied`。
- 权威会话为 `conv_71b1e3a7-afb8-425d-901e-bf41f5ef100f`，空消息区与输入栏可见。
- 输入栏曾残留环境中断前的未发送测试草稿；未发送它。保留本地诊断后，在已确认焦点的当前测试输入栏全选替换；不据此宣称草稿隔离已通过。

### Action：确认焦点后发送第一条短句并检查正文和回复。 ✅
- 使用 `android layout` 确认 `FOCUSED` 后输入，布局确认内容一致；点击发送坐标 `975 2242`。
- 第一条正文和完整回复在消息区各出现一次，生成状态为完成。

### Action：确认焦点后发送第二条短句并检查两次结果。 ✅
- 再次确认输入框焦点及空内容后输入并发送。
- 两条用户正文及对应完整回复在同一屏幕各显示一次，输入栏为空，没有正文占位错误和过期离线提示；截图已目视检查。

| 样本 | clientMessageId | generationId | Core POST | Adapter POST | 宿主启动 | 宿主完成 |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 第一条 | `cm_7d0c215e-b1a7-4d45-868b-1571178544a8` | `gen_7c38711e5757da153349d832236e95cdbc8b1d19` | 1 | 1 | 1 | 1 |
| 第二条 | `cm_9d453e6b-bfe5-4680-a79b-8fe37f073d85` | `gen_6b156c94b48c9104f18ee73455c1684d6fe4853d` | 1 | 1 | 1 | 1 |

- **结束计数**：`userCount=9`、`turnCount=2`、`completedCount=2`、`liveTurns=0`。原卡死输入 `cm_a3f910d3-aa3d-4ab2-be6a-4a14726cee4c` 仍为 Core POST=0、Adapter POST=0、宿主启动=0，未盲目重发。
- **本地证据**：`final-two-messages.png`、`final-two-result.json`、`final-two-status.json`，仅本地留存；凭据与用户正文不写入文档。此前夹具缺口导致的两项失败不覆盖、不改记通过。
- **边界**：受控宿主不是实际大模型；本项不证明弱网、执行中恢复、物理手机或厂商省电机制。
