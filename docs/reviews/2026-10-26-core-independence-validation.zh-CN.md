# Core 依赖独立化验证记录

迁移前 OAI 基线为 `f2c4a4eab081aa48fc0307c51f0f3c8754a5891d`；通用实现来源为 Hermes
`b64dda00a0de9ff1a95a375dcf92f62b5a1027b3`。分类及数据约束见[迁移清单](../architecture/2026-10-26-core-independence-migration.zh-CN.md)，部署步骤见[独立部署说明](../deployment/2026-10-26-core-independent.zh-CN.md)。

## 本地自动化

2026-10-26 在隔离任务 worktree 完成：

| 环境及入口 | 实际结果 |
| --- | --- |
| Python 3.14，当前 Core、可选 Adapter、固定真实 Hermes SDK，`pytest oai-core/tests tests/test_install_script.py -q` | 238 passed，29.59 秒 |
| Python 3.11，安装 Core wheel 后，`pytest oai-core/tests tests/test_install_script.py`，排除两个 Hermes 文件 | 227 passed，29.91 秒 |
| 固定 Hermes SDK `75b083e9399115b12513385252811f09c3503c90`，两个 Hermes 测试文件 | 11 passed；含真实插件加载器、SessionStore/SessionDB 和旧绑定兼容 |
| Python 3.11，Core、可选 Adapter、Hermes 入口三个 wheel，固定 SDK 原生测试 | 11 passed，4.02 秒；子进程沿用实际安装路径 |
| Hermes 轻量入口仓 `pytest tests -q` 及 manifest 检查 | 5 passed；manifest passed |
| Node 24.18.0，DSH `adapter.test.js` | 16 passed |
| Shell 语法、两份 workflow YAML 解析、变更文档本地链接及 diff 空白检查 | 通过 |
| Core 与可选 Adapter 的 sdist/wheel 构建 | 通过，包内包含各自协议资产或标准 loader manifest |

上述项目存在覆盖关系，不能将表中数字相加当作不同测试的总数。源安装的完整套件存在一条 aiohttp
`NotAppKeyWarning` 提示；所有用例通过。

## 只有 Core 的独立环境

另建 Python 3.11 虚拟环境，仅用 `pip install` 安装 Core wheel 及其声明的第三方依赖，尚未安装
pytest、Hermes SDK、Hermes 旧包或可选 Adapter 时，依次完成：

1. `pip check` 返回 `No broken requirements found`。
2. [verify_distribution.py](../../oai-core/tools/verify_distribution.py) 确认可选包不可见、Core 元数据无 Hermes/Git 依赖、协议资产位于已安装包内；在仓库外实际启动 `oai serve`，正确 Hash 协商成功，错误 Hash 被拒绝，主密钥为 `0600`。
3. [independent_core_contract.py](../../oai-core/tests/independent_core_contract.py) 在导入前阻断旧插件及 Hermes SDK，完成真实 HTTP 协商、密码登录、Ed25519 签名、能力发布、Core Token、请求提交、Claim、Result、ACK。导入拦截器没有记录任何禁止导入尝试。

本地所测 Core wheel SHA-256 为
`884f02a3da0e97e35e6a1e20693c0224d28fc1d8a316b6056b39a14f99ae29e9`。
Core Schema Hash 为
`sha256:032f580246eb4a214624bded717819b4d5a76191208207edc9e5a4607f5c4d23`。
构建过程未修改仓库 `gateway-contract/`，没有引入新的公共设备协议版本。

## 原账号和安全状态

自动化使用临时合成账号，验证跨账号拒绝、签名原始字节和重复安全头、防重放、凭据撤销、授权版本变化、
幂等冲突、未知结果、超时与取消、重启恢复、SSE/WS 游标恢复和撤销关闭，以及共享的 70 条协议向量。
原设备数据库结构、加密用途标识、账号路径算法及关键状态语义沿用迁移来源。

Hermes 测试保留旧平台来源、会话 ID、历史与压缩链；验证终端修改标题对 App 可见、全部已声明生命周期操作，
以及不支持审批时明确拒绝。旧数据库存在活动写入者时 Adapter 激活失败；Adapter 持有库时竞争写入被拒绝，
关闭后原凭据仍可从数据库读取。Core 设备库与旧 Hermes 会话账号库保持独立。

安装器回归验证重新配置保留密码、主密钥、路径和原 Token；旧启动脚本只进行严格格式解析，绝不执行；
新启动脚本不含 Token 字面值。已有数据库丢失主密钥时，Core 和配置向导均拒绝生成替代密钥。

## 仍需部署验收

本记录不表示已经切换用户现有服务。固定 SDK 测试使用真实原生存储和生命周期实现，但消息处理器返回合成文本，
没有调用真实模型；DSH/Codex 的自动化也不能替代生产宿主联调。尚需按部署文档验证现有 Hermes 服务、模型回复、
Android 真机连接、本地插件授权、敏感操作确认和断线恢复。远程 CI 与独立审查应以 PR 中对应提交的实际结果为准。

## 推送后独立审查与修复

对首轮提交 `eb6798dabbe4acbe5a45bbfe1dce83131b3513cd` 分别进行了[规范审查](2026-10-26-core-independence-standards-review.zh-CN.md)、[规格审查](2026-10-26-core-independence-spec-review.zh-CN.md)和[Core 安全审查](2026-10-26-core-independence-security-review.zh-CN.md)。
发现并修复两项 P1：同一 Hermes 宿主的多个 Agent 入口可能形成跨账号共享映射；会话存储未知提交后的重连可能丢失数据库独占。
两项均由原发现者独立复验关闭，新增 9 项等价 URL/账号映射和 2 项故障恢复回归；上表为修复后的完整结果。
原报告保留首轮发现，修复验证绑定报告中源码指纹及后续修复提交，不把首轮提交视为可合并状态。
