# 真实网络回归源码门禁补修的推送后独立复审

日期：2026-10-12。审查者未参与实现，Standards 与 Spec 分别由独立子 Agent 复审。此次没有运行 Gradle、提交、部署、永久删除或操作手机及用户账号。

## 固定范围

- 基线：`ea7a15f2e5cc50b1b3098d7b29e7c76866da1d32`。
- 被审查提交：`9dc4cc9446ab27b531ac4f2f09f9e3adb2a82139`，提交说明“修复: 为真实网络回归补齐窄范围源码门禁”。
- 差分命令：`git diff ea7a15f2e5cc50b1b3098d7b29e7c76866da1d32...9dc4cc9446ab27b531ac4f2f09f9e3adb2a82139`。
- 改动为 7 个文件：源码门禁脚本、TLS 职责及验证说明、[上一轮独立审查报告](2026-10-12-app-followup-post-push-review.md) 和四份门禁证据。
- 依据：`AGENTS.md`、模块化插件架构规格、Gateway 协议与 ADR 0047，以及上一轮 P1 门禁阻断和 P3 TLS 职责文案修正要求。

## Standards

**未发现新增规范违反或架构回归。** 上轮 P1 已从源码解决：`mvp-forbidden-surfaces.gradle.kts:71` 仅增加两个完整 JVM 测试路径；`:233` 逐文件选择检查规则。指定文件继续执行 `alwaysForbidden`，其他非网络所有者文件仍执行两组规则。网络所有者、扫描范围、注释处理及既有允许路径均未扩大。

补修没有拆分字符串或改变真实回归含义来避开检查，也没有将整个测试目录放行。归档的 RED/GREEN 和三处负对照覆盖了允许测试中的 VPN 标识、未列入测试中的网络标识、生产文件中的网络标识。

上轮 P3 已修正：补修说明第 13 行准确区分连接工厂的协议、超时和禁止重定向职责，以及传输层发送正文前的 TLS 身份核验。此前重复夹具的判断性疑点不构成本次新阻断。

## Spec

**未发现新增需求缺漏、错误实现或额外范围。** 小修复仅为两个明确测试文件允许出站断言，保留所有通用禁令及生产网络所有者；Android 产品代码与真实测试内容在本次差分中均未改变。原 P1 的源码缺口已消除，完整 CI 结果仍须单独确认。

归档说明如实区分本机门禁、完整 CI 和手机验收，未用 APK 成功替代完整检查。TLS 文案符合现有调用链。附件、可见流式及消息保留的实机边界继续保留。

## 独立核对及证据

审查者实际执行固定差分空白检查及静态一致性核对，均退出码 0。静态核对读取两个固定提交，确认以下事实：

1. `testOnlyOutboundAssertions` 恰好包含两条完整测试路径，且对应文件存在。
2. `alwaysForbidden`、`forbiddenOutsideOwners`、`networkOwnerModules`、生成目录排除集合及两组既有允许路径完全未变。
3. 注释处理、实际扫描及文件枚举三个函数完全未变；新分支始终包含 `alwaysForbidden`。
4. 本次 Android 路径下仅门禁脚本改变，没有产品或测试源码改动，也没有遗留临时探针。

审查核对以下实现阶段证据，没有将其声称为本轮重新运行 Gradle：

| 证据 | 结果及范围 |
|---|---|
| [原范围 RED](evidence/2026-10-12-attachment-streaming/source-guard-red.txt) | 与原 CI 相同两行被拒绝，实际执行 1 项任务 |
| [窄路径 GREEN](evidence/2026-10-12-attachment-streaming/source-guard-green.txt) | 真实源码门禁通过，实际执行 1 项任务 |
| [三处负对照](evidence/2026-10-12-attachment-streaming/source-guard-negative-controls.txt) | 指定测试中的 VPN、其他测试及生产代码中的网络标识全部被拒绝 |
| [对照及恢复详情](evidence/2026-10-12-attachment-streaming/source-guard-controls-details.txt) | 恢复原测试并将临时探针移入回收暂存目录；未提交探针 |

复审时查询的新提交 [CI 38227153418](https://github.com/1rua/open-android-intelligence/actions/runs/38227153418) 尚在运行；[APK 38227153499](https://github.com/1rua/open-android-intelligence/actions/runs/38227153499) 已成功，Nightly Release 尚在运行。这个时间点不能报告完整 Android 检查已经通过，后续状态由主 Agent 持续跟踪。

两轴结论：Standards 新增确证问题 0 项，Spec 新增确证问题 0 项；上轮 P1 和 P3 从源码及文档层面解决。完整 CI 和两处手机验收继续作为独立交付条件。
