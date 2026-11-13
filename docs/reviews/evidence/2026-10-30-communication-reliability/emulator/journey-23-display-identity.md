# Journey：列表展示身份修复像素复测

- **执行环境**：按调度配置 GPT-6.1 Terra，原 AVD `emulator-5554`、Android 15 / API 35。APK SHA-256 `9bd8b9d4c317c6a1a743d4b930bbbd6368d00554edaa4a0bae066d169a2840d3`，BuildConfig 对应 `1d519a18` 加未提交 TimelineDisplay 补丁，与旧 `f29e714a` APK 分开记录。
- **安装与约束**：`adb install -r` 成功，设备内摘要再次匹配，保留账号和所有应用数据；未关闭动画、改变 GPU 或重设 App。动画设置前后均为 animator=null（系统未显式覆盖）、transition=1.0、window=1.0。
- **基线**：`userCount=14`、`turnCount=7`、`completedCount=6`、`interruptedCount=1`、`liveTurns=0`。

## Results

### Action：新建会话并发送一条普通消息，检查基线。 ✅
- 权威新会话 `conv_a4c928be-1333-4caf-9a40-050540597a26`；输入前布局确认焦点及空内容，输入后核对一致，单次点击发送。
- 等待实际生成完成及 UI 更新后，基线截图目视确认用户正文一份、完整回复一份；布局相同，无过期离线提示。
- `display-01-baseline.png`、`display-01-settled.json`，仅本地留存。

### Action：息屏确认 OFF，再唤醒。 ✅
- `KEYCODE_SLEEP` 后实际状态为 `mWakefulness=Asleep`、`mScreenState=OFF`，随后 `KEYCODE_WAKEUP`。
- 设备直接回到原会话，无需额外锁屏解锁操作或登录；原消息完整，未修改锁屏设置。

### Action：只发送一条新短句，检查两张独立截图与布局。 ❌
- 确认输入栏焦点和空内容，输入另一条短句并单次点击发送，宿主完成；没有重复点击或重发。
- **改善观察**：两张独立截图中的本次新用户气泡及新回复各一份，布局中的本次正文节点也只有一份，没有重现旧样本中的两份新用户气泡。
- **未通过原因**：两张截图顶部右侧仍存在上一条用户行的“Agent 处理完成”和“复制”图层，但该位置的布局树没有对应节点。布局只在右侧更低位置列出当前用户行的完成状态/复制按钮，旧顶区语义则仅有左侧回复时间和复制按钮。像素与语义仍不一致，完整“没有旧图层”目标失败，不能宣布绘制问题全部解决。
- **本地证据**：`display-02-after-wake-a.png`、`display-02-after-wake-b.png`，均先目视检查；对应 `display-02-settled.json`、`display-02-second-check.json`。原图和布局仅本地留存。
- **执行边界**：按旅程规则在此停止；条件性第三、第四条连续发送未执行，未声称完成至少三次权威身份升级像素验证。

### Action：第三条、第四条连续发送与检查。
- 原旅程失败后未执行，不补写通过。

## 独立滚动观察

在保留上述两张失败截图后，另立观察，按已授权的慢速滚动查看旧区域：`adb -s emulator-5554 shell input swipe 540 700 540 1150 800`。

`display-old-region-scroll.png` 已目视检查，当前可见旧区域与布局基本一致，先前额外的顶区状态/复制图层不再出现。此观察不覆盖滚动前的失败，也不证明根因属于渲染器、动画或某一模块。没有切换会话、重开进程或清数据以获取通过结果。

## 原始身份与后端计数

| 样本 | clientMessageId | generationId | Core POST | Adapter POST | 宿主启动 | 宿主完成 |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 基线普通消息 | `cm_60f4cfa0-bb6c-4f3b-90e6-a671e4f52576` | `gen_536b07c2c1e185b8940f3e236da64ec1883e2051` | 1 | 1 | 1 | 1 |
| 息屏后消息 | `cm_657f2e33-5d0b-47c2-afbc-996efd2a437d` | `gen_bb3cf6ca4b4711638e4deef40cb44533d2ee2016` | 1 | 1 | 1 | 1 |

两条的原始身份不同，后端没有重复执行。结束时 `userCount=16`、`turnCount=9`、`completedCount=8`、`interruptedCount=1`、`liveTurns=0`。未重跑已验证的网络、Core 和取消场景；旧 APK 的原失败报告不覆盖。当前包的**完整像素验收未通过**，仅记录本样本中新用户气泡重复未复现。
