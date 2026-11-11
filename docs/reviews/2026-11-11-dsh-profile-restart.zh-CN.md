# DSH 安装目标与重启隔离修复记录

关联问题：#140（AUD-006）。基线：`24172841f9a564c53416f7d066f3450d4feb2bfe`，目标分支：`dev`。

## 任务目标

安装、升级、重启与注册始终使用同一个已选 DSH Home / Profile。重启前核验服务或受管进程归属，保留该目标的启动参数与环境；无法核验时明确报告未自动重启，不能停止其他实例。

## 实施范围

- 调整 Core 配置与安装入口中的目标传递，以及必要的自动升级调用。
- 使用真实函数与模拟 systemd、进程信息构造先失败后通过的回归测试。
- 不更改消息桥和 Android 逻辑，不改现有账号、密钥、会话或历史。
- 本分支验证不执行真实服务停止、重启或部署。

## 验收标准

- 两个不同 Home / Profile 同时存在时，仅已核验的目标可收到重启命令。
- 非默认 Profile、自定义 Home、端口和参数贯穿安装、重启与注册。
- 归属不明或多个目标时不进行猜测性重启，保留明确的操作结果。
- 聚焦安装配置测试、相关轻量门禁和差异格式检查通过；真实部署证据单独记录。

## 当前状态

2026-11-11：任务启动文档提交 `4eba75cd`，草稿 PR #144，Issue 认领评论 `6797746979`。

## 已确认根因与改动

- 原重启函数重新发现全部 Home 后按固定服务名或模糊进程名选择目标，忽略调用方选中的 Home/Profile；升级仅返回布尔值，注册补救时再次丢失所选目标。
- 现在要求明确目标，从真实进程命令行、环境、属主及工作目录核对 Home/Profile，并检查 systemd 下一次启动的参数、环境和工作目录。自定义服务名可以被发现；多个目标或归属不明时拒绝自动重启。
- 旧 PID 文件启动路径保留原完整命令、环境和工作目录，使用绑定到进程的句柄发送终止信号，避免 PID 被其他进程重用后误停；保留启动日志，不再扫描并停止模糊匹配进程。
- 升级结果保留 Home/Profile，通过宿主官方 `--dump-config` 读取有效端口和实例标识（含命令行补丁）；注册前核验监听端口属于所选进程，再执行真实握手。无法核验时明确显示已安装但未自动重启或注册。
- 自动更新入口保留原目标参数，在无法自动重启时给出明确提示，不以任意在线实例的健康结果代替目标身份。

## 本地验证

1. 修复前运行 `PYTHONPATH=oai-core/src python -m pytest -q oai-core/tests/test_dsh_setup.py -k 'restart_rejects or restart_requires or selected_upgrade_target' -p no:cacheprovider --tb=line`：**4 failed**。分别复现错误服务重启、PID 文件不足以证明身份、无目标仍选择服务以及升级后丢失所选 Profile；全部服务操作和信号均被模拟。
2. 修复后运行 `PYTHONPATH=oai-core/src python -m pytest -q oai-core/tests/test_dsh_setup.py oai-core/tests/test_dsh_installation.py -p no:cacheprovider --tb=short`：**82 passed，0 failed**（6.71 秒）。覆盖两个 Home/Profile、自定义服务、配置不一致、归属不明、PID 重启参数与环境保留、监听端口归属及自定义 instanceId/端口真实握手注册。
3. 运行 `PYTHONPATH=oai-core/src python -m pytest -q tests/test_scripts_runtime.py tests/test_scripts_runtime_security.py oai-core/tests/test_agent_handshake_errors.py -p no:cacheprovider --tb=short`：**30 passed，0 failed**（3.39 秒）。
4. `npm run test:fast`：依赖锁门禁通过；Core 阶段 **1222 passed，13 skipped，6 subtests passed，1 failed**（166.46 秒）。唯一失败 `test_hermes_installation.py::test_probe_succeeds_when_invoked_via_parent_relative_venv_python` 在 Python 虚拟环境创建阶段尝试建立 `lib64 -> lib` 符号链接时收到 `FileNotFoundError`；当前工作区文件系统也拒绝其他符号链接，此失败没有进入本次 DSH 修复函数。没有通过改变测试或跳过用例掩盖该环境限制。
5. 补齐 systemd 默认工作目录处理后，重新运行 `PYTHONPATH=oai-core/src python -m pytest -q oai-core/tests/test_dsh_setup.py -p no:cacheprovider --tb=short`：**28 passed，0 failed**（6.53 秒）。
6. `git diff --check` 通过；`npm run test:pr` 已后台启动，结果由主任务继续收集。

以上为隔离单元与 Core 握手集成证据，没有执行真实服务停止、重启、部署或 Android 验收。真实双实例升级与活跃任务不中断仍须单独验收。

## 独立审查后的第二轮修复

独立审查固定在 `064889e1`，发现三项有效问题：环境文件等来源未参与下次启动核验、官方脚本的 Node 解释器展开导致正常服务误拒绝、新身份读取无意要求可选 PyYAML。三项对应回归先运行，得到 **3 failed**（0.13 秒），确认均能捕获真实函数中的缺口。

本轮改动：

- 从对应 systemd 管理器当前环境开始，按 `PassEnvironment`、服务 `Environment`、按序 `EnvironmentFiles` 与最终 `UnsetEnvironment` 重建下次环境；系统管理器的继承仅取显式传递项，不以旧 DSH 进程的环境补齐。管理器环境只捕获在内存中，环境文件经属主、类型、大小及非符号链接检查后读取，包含密钥也不输出。
- 重启前重新读取管理器环境及服务配置，防止核验期间发生变化。官方 `EnvironmentFiles` 的多行重复输出保持原顺序；支持正常环境文件、路径含空格、文件通配符、引号和续行。仅在 PAM 环境、未解析的路径占位符或无法安全读取等确实不能核验的情况停止自动操作。
- 仅对已核验 `@deepseek-ai/dsh` 包内的官方 `bin.js` 和 `#!/usr/bin/env node` 规范化解释器、入口及全部参数，兼容脚本直接启动与显式 Node 启动，保留原启动方式。
- 移除新路径中的 Python YAML 依赖。使用已经安装的 DSH 所属 Node 与其 `js-yaml` 依赖解析官方配置输出，只向 Core 返回端口和实例标识两个字段；表达式标签只作为数据保留，不执行表达式，不输出完整配置。

验证：`PYTHONPATH=oai-core/src python -m pytest -q oai-core/tests/test_dsh_setup.py oai-core/tests/test_dsh_installation.py -p no:cacheprovider --tb=short` 得到 **95 passed、0 failed**（6.76 秒）；`git diff --check` 通过。新增真实已安装 DSH 的隔离 `dump-config` 测试在禁用 Python YAML 导入时仍成功，并验证配置表达式不执行、合成密钥与完整配置不进入输出。正常环境文件自动重启、环境变化拒绝、清除规则、解释器展开均有保护回归。

本轮没有重复全量 Fast/PR 门禁，没有停止或重启真实服务。前一提交的门禁环境限制与远端 CI 结果不能替代本轮审查和真实部署验收。
