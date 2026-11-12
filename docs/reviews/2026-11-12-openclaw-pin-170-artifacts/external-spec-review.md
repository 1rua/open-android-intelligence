# OpenClaw PR #4 独立规格审查

审查日期：2026-11-12。Canonical 仓库为 `1rua/openclaw-android-gateway`，实时 PR #4 的 base 为 `35b2a595fd4374eb64761d08a90d83ea5390570f`，head 为 `2f1a08a33ec0255302789a03b48194364dd754ed`，与本次固定范围一致。使用 `git diff 35b2a595fd4374eb64761d08a90d83ea5390570f...2f1a08a33ec0255302789a03b48194364dd754ed` 审查。执行源码提交 `8ad90bcb52e298242f7726acc7724eb52b2551d8` 至 head 的源码、runtime、依赖与清单无进一步变化。

## 规格结论

**规格轴未发现需要修复的功能缺陷，0 项。** 实时读取插件 Issue #3 与 OAI Issue #170，需求是“保持严格 pin 与快照校验，让 OpenClaw 的受管源、包内 runtime 和当前 OAI 契约完全一致；携带当前 CORE 摘要的客户端可以继续正常协商”。固定差异按原生成机制同步目标 OAI `02ad53e09049c2b06c21cacd9d009c2f14e6d5bd`，符合这轮修复范围。

Issue #3 明确“不弱化 checker”“不扩展 OpenClaw 业务职责”；本次没有改动 `tools/`、`src/`、依赖锁或既有能力开关，manifest 仅更新真实 CORE 摘要，包版本仍为 `1.1.0`。新 caller、push 与授权进度是共享 Schema 注册，不新增主动推送、设备授权业务处理器的声明。不能因协议中出现定义就把这轮结果理解为新增这些业务能力，PR 和报告已明确此边界。

## 独立复核

| 验收项 | 本次独立证据 |
| --- | --- |
| 完整受管契约 | 直接读取 OAI 02ad Git 对象；26 份源文件逐字节相同；保留的原生成输出亦为 26 份且与源完全相同。仅预期 4 份契约源变化。 |
| 安装包原始契约 | 21 份 runtime JSON 与目标 OAI 字节完全一致。 |
| 构建稳定性 | 当前 61 个 runtime 文件名与 SHA-256 等于首次构建清单，亦等于第二次清单；两份清单完全一致。审查未重复执行 build，不将摘要复核表述为重新构建。 |
| 新定义与实际路径 | 使用 Node 24.18.0 重跑新增测试，12/12 通过。覆盖 source/runtime caller 闭集、旧持久请求兼容、push 五类定义、授权进度状态集、真实 EventStore 保存与回读 caller、拒绝坏 caller、真实 Gateway Core 协商接受当前 CORE 并拒绝旧 CORE。 |
| 完整共享向量 | 使用 Node 24.18.0 分别调用真实 source/runtime `createGatewayCore().runSharedVectors()`；各 70/70，通过 70 个唯一 ID，完整结果逐项相同并等于已纳管记录。 |
| CORE 与清单 | 源码/runtime 真实算法与 manifest 均为 `sha256:38786286ca26f601747cf7e52953e1cd4a57b8089e3b95cdc31fbb6f7a8b6d83`。 |
| 严格门禁 | 在当前 OAI 02ad 检出执行原 `check-contract-pin.py --plugin-root-env OPENCLAW_PLUGIN_ROOT` 退出 0；checker 本身未改。 |
| 全套历史证据 | 已核对最终 38 文件、220/220 输出、12/12 输出及所有 `results.json` 证据摘要，无摘要不符。本次未重复全套测试，220/220 属原执行证据。 |
| 远端状态 | 实时 PR head 相同，`plugin-ci` 的 `test` 已 SUCCESS（run `43123126400`）；不把这一状态替代真实设备或模型验收。 |

## 证据口径提醒

PR/报告写“git diff --check 通过”与固定 base/head 的实际结果存在小幅不符：8 份原样保存的命令输出日志末尾存在新增空行，固定范围执行该命令会报 `new blank line at EOF`。这不影响契约、构建或协商行为，也不构成功能规格缺陷；建议 Standards 轴单独处理此处陈述准确性，不能把空工作树的 diff 检查当成整个 PR 的检查。

## 验收边界

没有修改源码、提交、推送、合并或部署。本次没有重复 build/完整 220 项套件，没有操作真实 Android、模型或用户账号。OAI #170 的后续实现 pin 更新、完整跨宿主对照及同一最终 dev 提交的 Full 成功仍属后续验收，不是插件 PR #4 的缺项。
