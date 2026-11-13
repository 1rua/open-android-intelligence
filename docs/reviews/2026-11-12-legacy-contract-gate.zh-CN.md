# 冻结历史 Gateway 与当前契约分层门禁验证

日期：2026-11-12。关联 Issue #158 / PR #163。基线 `26c0e5c9`，实现提交 `9ea213a90ead23039c2e680dd6d8fe96d22f4756`；本报告和结果摘要为后续文档提交，没有修改执行代码。

## 修复内容与保持的门禁

完整 CI 刻意固定历史 HTTP Gateway 到 `ebe527da2426dd25877dd335786e80cf85d1cdde`，其原始 `contract-pin.json` 固定本仓 `f0b3f1bd4f6f3301b820e38c9f3241bcac05cd10`。现行契约早于 DSH 本轮已演进，故要求历史 pin 与当前相等会恒失败。这个差异不等于当前 Core 或 DSH 协议失败；但不能跳过历史兼容性。

新增门禁先严格验证该历史检出的仓库、提交、未修改源码和 pin，再从固定 Git 对象导出 schemas、vectors、registry 和 src，逐文件与固定对象核对。它拒绝错版本、错 pin、未提交源码、导出路径逃逸/符号链接、缺失/额外/改写文件、运行向量失败/缺项/重复。导出输出保留在显式的新隔离目录，不永久删除，不覆盖当前契约或历史源码。

真实历史 Gateway 在独立进程中对该导出运行全部共享向量；导出和当前契约在执行后再次核验。**这仅证明固定历史基线自洽**，当前兼容断言继续单独执行：

- `check-contract-pin.py --plugin-root-env OPENCLAW_PLUGIN_ROOT` 未修改。
- `npm run gateway:v2:conformance` 继续把当前契约显式交给历史 Gateway，并与当前 OpenClaw 结果比对。
- 历史回归及 Android HTTP 夹具仍使用当前 `OPEN_ANDROID_GATEWAY_CONTRACT_ROOT`，没有改为历史目录。
- 原 `check-contract-pin.py` 源码与其拒绝真正 pin 漂移的回归保持；没有改变任何外仓 pin 或更改协议数据。

已用 GitHub 仓库元数据核验两个已有检出名称 `1rua/hermes-android-gateway`、`1rua/hermes-gateway-plugin` 指向同一仓库，清单仅显式允许这两个身份，提交仍必须完全匹配。不是接受任意重命名或分支。

## 实际复跑命令

以下从任务工作树根运行；所有路径相对，继续复用主仓现有依赖。输出目录须为新目录，旧结果保留：

```bash
# 原门禁仍红，证明未弱化现行 pin 一致性断言。
HERMES_PLUGIN_ROOT=../../.toolchains/legacy-gateway-ebe527da \
  ../../.toolchains/pr64-legacy-python/bin/python gateway-contract/tools/check-contract-pin.py

# 新增历史来源、自洽和真实向量门禁。
HERMES_PLUGIN_ROOT=../../.toolchains/legacy-gateway-ebe527da \
  ../../.toolchains/pr64-legacy-python/bin/python \
  gateway-contract/tools/verify-historical-gateway-contract.py \
  --output ../dsh-remediation-state/legacy-contract-committed-verified-20261112

# 原运行器对当前契约继续实际执行，不用历史成功替代。
../../.toolchains/pr64-legacy-python/bin/python gateway-contract/tools/run-hermes-conformance.py

python -m pytest -q gateway-contract/tools/test_historical_gateway_contract.py \
  gateway-contract/tools/test_check_contract_pin.py
python -m pytest -q tools/test_ci_planning.py \
  gateway-contract/tools/test_check_contract_pin.py \
  gateway-contract/tools/test_historical_gateway_contract.py
```

CI 使用新临时目录和其独立历史解释器，不依赖本机工具路径。固定清单为 `gateway-contract/historical-hermes-gateway.json`。

## 结果与边界

| 层级 | 结果 |
| --- | --- |
| 原 pin 对当前检查 | 退出 1，真实内容不相等；[原红输出](2026-11-12-legacy-contract-gate-artifacts/original-pin-red.txt) |
| 新门禁及原 pin 聚焦 | 21 项通过；[输出](2026-11-12-legacy-contract-gate-artifacts/focused-green.txt) |
| 加上 CI 规划 | 99 项通过，其中规划 78；[输出](2026-11-12-legacy-contract-gate-artifacts/planning-green.txt) |
| 固定旧 Gateway + 固定旧契约 | 真实共享向量 70/70，导出完整性与当前工作区未变核验通过 |
| 同一旧 Gateway + 当前契约 | 原 `run-hermes-conformance.py` 真实共享向量 70/70 |
| Fast | 退出 0；锁门禁、Hermes 261 项与 noVpnSurfaceCheck 全部成功 |
| PR | 退出 1；Core 1249 通过、1 失败、3 跳过、6 subtests通过；唯一失败是既有 Python venv 创建 lib64 符号链接 ENOENT，原断言未改。未把整轮写绿。 |

[结构化结果](2026-11-12-legacy-contract-gate-artifacts/results.json)保存固定来源、受管文件摘要和执行脚本摘要。[向量摘要](2026-11-12-legacy-contract-gate-artifacts/real-vector-validation.txt)只含计数；没有真实账户、Token、模型、设备或会话数据。

初次新增测试在实现前因缺少新工具全部报错；真正原问题的红反馈另由原 pin 命令明确重现，未把缺文件错误当作业务根因。新增完整命令失败测试确实让运行边界返回失败向量，最终 CLI 退出 1 且不产生成功回执；没有以纯常量测试代替实际历史运行。

目前只本地执行历史运行器的两组向量。完整 OpenClaw 跨宿主结果比对、Android HTTP 业务、同提交完整 CI 和独立代码审查须由主任务继续；没有声称本 PR 已使整个 Full 成功。相对 Python 解释器启动产生 `sys.prefix` 规范化警告，不影响退出码，未改系统环境或新建解释器缓存。
