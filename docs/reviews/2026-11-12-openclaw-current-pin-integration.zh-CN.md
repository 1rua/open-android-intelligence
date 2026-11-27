# 当前 OpenClaw 实现 pin 集成验证

日期：2026-11-12。关联 OAI Issue #170、草稿 PR #172，以及 canonical 外仓 `1rua/openclaw-android-gateway` PR #4。

执行配置固定提交：`ae4d8e57cde7f920b4fa2f730053ea60db9da3a5`。本报告和验证产物由后续文档提交归档，执行内容仍为同一 pin / 锁行。

## 结果与变更范围

本分支以最新 dev `dabfdbe35d52f4ca11ba2cfeaaff38cdfddd2ebb` 为基线。OAI 根 `openclaw-plugin-pin.json` 的 ref / revision 由旧 `c7c087b9fe28adcc658a1cbdb71689a377b65214` 更新为外仓真实 squash 合并提交 `e73ddfe1b333cfe0f69c4ea519db3553b056381a`，版本仍为 `1.1.0`。原 `MVP-DEP-OPENCLAW-PLUGIN` 行使用现有 `openClawPluginLockFields()` 和 `sha256Evidence()` 重新计算，其他七行不变，未延长证据有效期。新行摘要为 `sha256:5ebe09f81961f14ec037bd33d4f9ae4592f76019cb5f389831ad0381c4554046`。

原 checker 严格要求 repository 为 `1rua/openclaw-gateway-plugin`，因此 pin 和锁行保留已有 GitHub 可重定向 alias；canonical 身份在报告和来源证据明确为 `1rua/openclaw-android-gateway`。未修改 checker、生成算法、工作流、契约或业务实现，不引用漂移分支/PR head 作为安装版本。

## 外仓固定来源与独立审查

- 外仓 PR #4 已合并，GitHub API 的 merge commit 为 `e73ddfe1b333cfe0f69c4ea519db3553b056381a`，最终审查候选为 `3fafc92a148e83b8b47a0876029c94dbd51ff20b`。
- 两者整棵 tree 完全相同：`257d3d763d52275370adbab4805978700234bcaa`。独立检出的合并版本为干净工作树，所有受管源与当前 OAI 26/26 字节相同，61 个 runtime 名称与摘要等于已审查的双次构建结果。
- Standards 独立审查人工规范发现 0、判断性异味 0；Spec 独立审查功能缺陷 0。独立重跑源码/runtime 新增回归 12/12，双边 70/70，26 源、21 原始 runtime JSON、61 构建文件与原严格 checker 均复核通过。完整审查原文已归档。
- 旧证据 head `2f1a08a3` 的八份日志 EOF 空行问题在 `3fafc92a` 中各移除一个 LF，正文与失败内容不变；全部 25 份证据摘要重新匹配，非 docs 内容相等，完整三点 diff-check 退出 0。归一复核记录和外仓结果均已纳管。
- 外仓 CI `43123126400` 对应 `2f1a08a3`，成功；归一后 CI `43124113590` 对应最终 `3fafc92a`，亦成功。两轮 head 分开保存，未把旧轮状态当作新 head 结果。

外仓原执行证据为 38 文件 / 220 测试通过、5 项 Python 工具、两次原 build 的 61 文件完全相同，以及 source/runtime 各 70 向量通过。本轮没有重复插件完整 220 项或 build，而是在合并 tree 完全相同的前提下执行 OAI 当前集成门禁。

## 本轮红绿验证

原 pin 对新真实合并检出执行 `python tools/check-openclaw-plugin-pin.py`，退出 1，准确报告实际 e73ddfe 与旧 c7c087b9 不符。只更新 JSON 后，既有锁同步测试 8 通过 / 1 失败，准确发现锁行仍指旧 SHA；保留两份红证据。随后通过原算法同步唯一插件锁行，没有改测试或弱化判定。

从本任务工作树执行（外仓检出固定在相邻 `openclaw-merged-170`）：

```bash
OPENCLAW_PLUGIN_ROOT=../openclaw-merged-170 python tools/check-openclaw-plugin-pin.py
OPENCLAW_PLUGIN_ROOT=../openclaw-merged-170 python gateway-contract/tools/check-contract-pin.py --plugin-root-env OPENCLAW_PLUGIN_ROOT
OPEN_ANDROID_GATEWAY_CONTRACT_ROOT="$PWD/gateway-contract" npm --prefix ../openclaw-merged-170 run contract:check
node ../../node_modules/vitest/vitest.mjs run tools/check-dependency-lock.test.ts --maxWorkers=1
npm run lock:check
python -m pytest -q gateway-contract/tools/test_check_contract_pin.py gateway-contract/tools/test_historical_gateway_contract.py tools/test_ci_planning.py
npm run gateway:v2:conformance
```

最后一条使用 Node `24.18.0`、固定历史 Hermes 解释器与独立证据输出目录，`OPENCLAW_PLUGIN_ROOT` 在进程环境中解析为合并检出的真实路径；Hermes 实际导入 `.toolchains/legacy-gateway-ebe527da/open_android_intelligence_gateway/core.py`。没有把历史锁定契约的自洽运行替代当前向量；两边均读取本分支 `gateway-contract/`，原命令连同三项跨宿主断言完整执行。

| 本轮门禁 | 实际结果 |
| --- | --- |
| 实现 pin checker | 退出 0，版本 1.1.0 / 合并 SHA e73ddfe |
| 当前契约 pin checker | 退出 0，外仓契约 pin 02ad 与当前 OAI 输入相等 |
| OpenClaw 原 contract:check | 退出 0 |
| 依赖锁工具回归 | 9/9 通过 |
| 原 lock:check | 8 行通过，未改校验时间 |
| 契约 checker / 历史工具 / CI 规划 | 103/103 通过 |
| 当前 OpenClaw 完整向量 | 70/70，加载已合并版本的真实 runtime |
| 当前 Hermes 完整向量 | 70/70，固定历史运行器读取本轮当前契约 |
| 原跨宿主测试 | 3/3，70 个唯一 ID / operation / resultHash 逐项相同 |

本轮复用主仓既有 Node 依赖，实际 Vitest 输出为 `4.1.10`；外仓完整 220 项与 build 的记录使用其锁定依赖 `4.1.11`，两份证据没有混为同一环境。没有重装依赖、伪造版本或新建冷缓存。日志只规范化 EOF 多余空行，正文与计数原样保留，前后摘要另存。

## 证据与后续边界

证据目录为 `2026-11-12-openclaw-pin-170-artifacts/`，含严格 pin 红绿、锁同步红绿、全部当前向量 JSONL/manifest、外仓两轴原文、EOF 归一、merge/API/CI 元数据与逐文件内容复核。

本轮没有合并 OAI PR，没有部署本机 Core / DSH，没有执行模型或安卓设备操作，也没有启动 Android 全量编译。完整 OAI Full CI、真实 Android/宿主链路和最终发布仍需在最终集成提交上完成。锁行中的完整 `verify_command` 保留原生安装步骤作为可执行验收清单；本轮没有宣称逐字执行该整串安装命令，只报告表中实际门禁和外仓独立 CI/来源证据。
