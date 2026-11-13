# Journey：只读列表布局日志像素定位

- **环境**：按调度配置 GPT-6.1 Terra；原 `emulator-5554`、API 35。覆盖安装诊断 APK，SHA-256 `6fc1c32640c1e824271a02a2d4a2c0a7dd6cd38b25a54c93281cc2eed1fc4d9f`，`99f41bf3` 加只读 `T9.list_layout` 日志；保留账号与数据，未修改动画或 GPU。
- **范围**：本轮严格两个输入；新权威会话 `conv_23d4c9d0-6acc-4e1c-82f6-3e69c37082c7`。没有滚动、刷新、切换会话或追加循环清除失败画面。

## Results

### Action：新会话输入并单次发送基线消息。 ✅
- 输入前确认 `FOCUSED` 且空，输入后核对一致；真实生成完成后读取布局和截图。基线正文、完整回复均一份，截图已目视检查。
- `list-diag-01-baseline.png`、`list-diag-01-baseline-layout.json`、`list-diag-01-t9-latest.json`，仅本地留存。

### Action：息屏确认 OFF，唤醒后输入并单次发送另一条消息。 ✅
- 实际核对 `mWakefulness=Asleep`、`mScreenState=OFF` 后唤醒；直接回原会话，无需额外解锁操作；焦点和空内容再次确认后输入，单次发送。

### Action：Verify：像素与布局无顶部旧状态或复制残影。 ❌
- **复现**：新用户正文及回复各一份，但截图顶部右侧仍显示上一条用户行的处理完成状态和复制按钮；完整布局没有该顶区右侧节点。当前布局中的右侧状态为 `[818,686][1038,726]`，右侧复制为 `[958,801][1017,841]`；顶区只有左侧复制 `[121,416][180,456]` 等正常语义节点。
- 目视确认后立即保存完整布局及截图时最新 `T9.list_layout`，保持画面未滚动/刷新；不写根因已修复，也不据此归因为 GPU。

## 交给主 Agent 的只读诊断

以下数值为日志原始列表坐标，不直接等同于屏幕绝对坐标。最新记录 `monotonicNs=4645661684743`，`itemCount=4`，viewport 为 `[-21,1007]`。`userRows` 恰好两行，均 `COMPLETED`、`pendingAcceptance=false`，真实 messageId、clientMessageId 和 uiKey 已完整保留在本地 JSON。

| visibleItems index | uiKey | offset | size |
| --- | --- | ---: | ---: |
| 2 | `reply_gen_26759ca704969386a255075999ad3b3261c90a15` | -148 | 327 |
| 3 | `msg_9ae2a98d-8edd-4d8b-9237-28d4a7f165c2` | 232 | 332 |
| 4 | `reply_gen_25d85ac16c038f21d726cc6f1f0670916fb1497e` | 617 | 327 |

旧用户行不在该条最终记录的 visibleItems 内；但不能用这一最终快照断言此前始终只有一行。下节补充完整快照序列与主 Agent 的后续定位结论。

## 同一输入曾暂时出现双数据行

本执行者只读复核本地 28 条 T9 快照，发现 12 条存在相同 `clientMessageId` 对应多条用户行，其中息屏后输入有 7 条这样的快照；这些是状态快照数量，不是重复发送次数。

以息屏后输入 `cm_facb23ea-a9b1-4c81-a2bb-f64e0d74ca6a` 为例，`monotonicNs=4644090635619`、viewport `[-21,945]` 时，两行都为 `COMPLETED`、`pendingAcceptance=false`，并同时出现在 userRows 和 visibleItems：

| 来源对应的日志原始 messageId / uiKey | visible index | offset | size |
| --- | ---: | ---: | ---: |
| `local_cm_facb23ea-a9b1-4c81-a2bb-f64e0d74ca6a` | 3 | 550 | 332 |
| `msg_9ae2a98d-8edd-4d8b-9237-28d4a7f165c2` | 4 | 935 | 332 |

两行最终收敛为一行，而截图仍有旧操作区域残留；这轮证据不再仅是“最终语义一份、像素多份”的孤立观察。

主 Agent 根据该序列及实际 `mergeLocalSendAtoms → controller` 旧实现红例，确认 local 合并时丢失 `localPlaceholder` 来源标记，原生身份保护因而将它作为第二条真实消息保留，暂时双数据行触发旧退出层。该源码根因和红例结论由主 Agent 提供；本执行者直接核实的是上述双行及可见坐标序列，没有自行重跑代码回归。来源标记修复和优先权威行处理尚待新包验收，不能提前宣布像素修复通过，也不将其归为“只是 GPU”。

所有下列文件位于工作树 `tmp/emulator-acceptance/`，仅本地留存：

- `list-diag-02-pixels.png`：复现截图，已先目视检查。
- `list-diag-02-full-layout.json`：复现后立即取得的完整 layout。
- `list-diag-02-t9-at-capture.json`、`list-diag-02-t9-latest.json`：截图时与完整 layout 后最新 T9，单调时间相同。
- `list-diag-t9-session.jsonl`：本会话 28 条 T9 快照，供只读对照身份升级和坐标。
- `list-diag-duplicate-data-rows.json`：由已存快照导出的同输入多行记录，含两行身份、匹配的可见项坐标和 viewport；只读导出，没有 UI 操作。
- `list-diag-counts.jsonl`、`list-diag-final-status.json`：原始输入计数与结束状态；原始 OaiE2E 仍在 `logs/oai-e2e-environment-resume.log` 中保留。

## 身份和次数

| 样本 | clientMessageId | generationId | Core POST | Adapter POST | 宿主启动 | 宿主完成 |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 基线 | `cm_26fe5840-eb97-49f5-b1b5-b086780319a1` | `gen_26759ca704969386a255075999ad3b3261c90a15` | 1 | 1 | 1 | 1 |
| 息屏后 | `cm_facb23ea-a9b1-4c81-a2bb-f64e0d74ca6a` | `gen_25d85ac16c038f21d726cc6f1f0670916fb1497e` | 1 | 1 | 1 | 1 |

结束状态 `userCount=18`、`turnCount=11`、`completedCount=10`、`interruptedCount=1`、`liveTurns=0`。旧失败报告保持原判定；本轮像素失败，两个输入均未重复 POST/执行。服务与模拟器保持运行，本轮完成后不继续等待或执行更多场景，由主 Agent 收尾。
