# Journey：来源修复后息屏像素与数据复测

- **执行环境**：按调度配置 GPT-6.1 Terra，原 `emulator-5554`、Android 15 / API 35。
- **APK**：SHA-256 `d72906941d5aaf5becfb65e151076caef9f3cc38a4b444b0e5a4b470ed17fe48`；覆盖安装后再次读取设备内摘要匹配，保留账号和应用数据。
- **版本边界**：BuildConfig 构建时为 `99f41bf3` 加未提交来源修复/布局日志；主 Agent 后续校验四个关键生产源摘要与提交 `3b07cb641e1982e55e95487a54b594bd00ef4d1b` 一致。此源码对应关系来自主 Agent 的校验，本执行者不将 APK 的 BuildConfig 改称新提交。
- **固定条件**：没有滚动、刷新、关闭键盘、切换会话或重设 App 来清除残影；没有修改 GPU、动画或锁屏设置。前后系统动画值保持 animator=null、transition=1.0、window=1.0。
- **基线**：`userCount=18`、`turnCount=11`、`completedCount=10`、`interruptedCount=1`、`liveTurns=0`。

## Results

### Action：新建权威会话，发送一条普通消息并检查基线。 ✅

新会话为 `conv_c5de8829-9bd3-4733-92ea-78d7a4d16756`，空消息区与已连接状态确认后，读取布局确认空输入框 `FOCUSED`，输入并核对一致，单次点击发送。实际生成完成且 UI 更新后，基线截图目视确认用户正文及完整回复各一份，布局一致。

`provenance-01-baseline.png`、`provenance-01-baseline-layout.json`，仅本地留存。

### Action：息屏确认 OFF，唤醒后单次发送另一条。 ✅

`KEYCODE_SLEEP` 后实际状态为 `mWakefulness=Asleep`、`mScreenState=OFF`，再用 `KEYCODE_WAKEUP` 唤醒。设备直接返回原会话，无需额外解锁或登录。再次确认输入焦点及空内容后输入，核对一致，单次点击发送，没有重复点击。

### Action：Verify：两张独立截图与布局一致，没有双用户行或旧操作图层。 ✅

两张独立截图均先目视检查：当前新用户气泡和完整回复各一份；顶部只显示实际仍在可见范围内的旧回复时间/左侧复制按钮，没有前两版出现的顶部右侧旧完成状态或旧复制残影。完整布局与像素对应，无需滚动或刷新清除。

两消息结束时保存的 21 条本会话 `T9.list_layout` 快照中，同一 `clientMessageId` 同时对应多个 userRows 的次数为 0；真实 messageId 已升级，而各输入最初的展示 uiKey 保持稳定。

`provenance-02-pixels-a.png`、`provenance-02-pixels-b.png`、`provenance-02-full-layout.json`、`provenance-02-t9-session.jsonl`、`provenance-02-t9-latest.json`，仅本地留存。

## 第三条独立验证

前述像素和数据检查均通过后，按授权再做一条独立样本：不改变界面条件，再次确认焦点及空输入，输入第三条短句，单次发送并等待真实完成。截图与完整布局再次一致，新正文/回复各一份，没有顶部旧状态或复制图层。

`provenance-03-pixels.png`、`provenance-03-full-layout.json`，仅本地留存，截图已目视检查。

三消息全程共保存 35 条本会话 T9 快照：同输入双用户行快照为 0；每个输入的 uiKey 集合大小均为 1，而其 messageId 均记录了从本地占位到权威 ID 的变化。最终三条权威用户行均为 `COMPLETED`、`pendingAcceptance=false`，原 clientMessageId 和后端 generationId 可逐条关联，没有用展示键替代协议身份。

## 原始身份与逐层次数

| 样本 | clientMessageId | generationId | Core POST | Adapter POST | 宿主启动 | 宿主完成 |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 普通基线 | `cm_1af720ce-d93b-46bf-8961-2cb6e1076ce8` | `gen_77f777014d66a01c077fed689893bfe93d3bcdad` | 1 | 1 | 1 | 1 |
| 息屏后 | `cm_9654a2e3-f02e-4030-b509-97964dd54838` | `gen_1eb312f61553c73ba247823c7927059ad19bde65` | 1 | 1 | 1 | 1 |
| 第三条独立样本 | `cm_4b513c5c-735f-404c-aa5a-75defc995e6d` | `gen_b8a75f3664dba573f8ca8341d5c0b2f23fc2833b` | 1 | 1 | 1 | 1 |

完整真实 messageId、uiKey、状态、可见 index/offset/size 和 viewport 保存在 `provenance-t9-summary.json`、`provenance-t9-latest.json`、`provenance-t9-session.jsonl`；逐层次数保存在 `provenance-counts.jsonl`，均仅本地留存。

## 判定与边界

**本轮有界像素和数据复测通过**：来源修复包在相同息屏条件下未复现先前双用户行及旧操作图层，第三条独立样本也通过。此结果不覆盖或改写 journey-19、journey-23、journey-24 的旧版失败判定，不扩大为所有设备/GPU/动画组合都已验证。

结束时 `userCount=21`、`turnCount=14`、`completedCount=13`、`interruptedCount=1`、`liveTurns=0`，模拟器及隔离服务保持运行。本批没有重跑已验证的网络、Core 崩溃恢复和定向取消场景。原始截图、正文、凭据、XML、JSON 和日志不提交，只交付本 Markdown；未修改公共总报告。
