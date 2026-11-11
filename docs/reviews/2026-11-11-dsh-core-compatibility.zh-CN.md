# 内置 DSH 宿主兼容与恢复验证

## 范围与结论

本分支以 `dev` 的 `24172841f9a564c53416f7d066f3450d4feb2bfe` 为基线，关联 #141、#142 及独立仓库 `dsh-android-gateway` #4、#6。本记录仅覆盖内置桥的源码修复与本地证据，不代表独立插件、实际模型成功回复、Android 或部署已全部验收。

修复内容：

- 为真实 `prompt(request, signal)` 提供有效且独立于 HTTP 观察连接的受理信号；保留观察挂起、超时、背压和断开清理。
- 会话摘要以迭代方式计算更新时间，十五万条合成事件不再导致列表整体失败。
- 历史优先读取完整原生日志，保留原生消息 ID 和 `source.rpcId` 对应的客户端身份；只将最终助手回复关联到该轮次的第一个原生输入，用户输入各自保留独立代次。映射索引预先构建，避免逐条扫描全部回执。
- 从原生 inbox 插入、消费和 turn 终态恢复生成事实。代次 ID 基于已接纳的原生用户消息 ID，排队、运行、终态不变更身份；没有原生终态且未附着时返回 `unknown`。
- 显式取消在同一次同步调用栈中读取活动 Agent、完整日志、匹配轮次并调用 `Agent.cancel`，中间不等待异步操作，且再次检查 Agent 未被替换。保留 inbox；已终态、排队目标和不同后续轮次不会误取消。
- 重复 POST 先查权威原始身份：终态直接重放；活动记录仅附着观察；未知记录不重投。长轮询注册监听后重读，补回注册窗口内的变化。

## 先红后绿

第一组五个红色回归稳定命中：缺失 `AbortSignal`、十五万条事件时 `503`、新旧两种用户消息格式历史丢失客户端身份、断开观察时未持有有效信号。最小回归约 0.3 秒运行完成。修复后两份既有套件与新增回归共 47 项通过。

第二组五个红色回归命中：活动生成路由缺失、稳定排队身份缺失、冷宿主未终态未知缺失、精确取消未接通以及旧终态隔离。补齐投影和路由后转绿。随后扩展 Agent 替换、缺失 ID、同轮多用户、外部活动、最终回复来源、重复提交与长轮询窗口。

## 当前本地证据

- Node 六份测试共 108 项通过，启用 `--detectAsyncLeaks` 后未报告泄漏，覆盖真实发行包签名与观察生命周期、日志投影、精确取消、完整历史及既有安装/诊断功能。重复身份预查即使挂起也按观察期限解绑；迟到读取结果不会触发重新入队。
- Core 真实 Python `DshSessionAdapter` → 当前 JavaScript HTTP 处理器的跨语言契约，加现有 DSH 与远端 generation 传输：6 项通过。该层宿主输入为合成夹具，不是模型端到端运行。
- 本机已安装的 `@deepseek-ai/dsh 0.2.2-rc.1` 真实 `SessionController.prototype.prompt` 契约通过：有效信号进入受理入口，缺失/入口前已中止的信号拒绝，观察结束后的独立信号仍有效。
- 同一发行包内部驱动类未导出，因此测试原样提取已发行的 `cancel` 方法体，在受控 receiver 上确认同步 abort 和 `keepInbox` 的语义。此项不是完整 Agent 实例运行的替代证据。
- 真实隔离 DSH Home/Profile、独立端口、合成桥密钥的 Cordis 注入、Core HMAC 握手、身份库注册回读、健康、创建、重命名、未认证拒绝、重启后持久会话均通过。没有配置模型凭据时，发送返回 `generation` 后 `AGENT_FAILED`，历史已有 5 行，证明信号入口与入队路径已通过；`businessSuccess=false`，不宣称模型回复成功。
- `npm run test:fast` 的锁门禁通过，Core 与脚本套件为 1211 项通过、13 项跳过、6 项子测试通过，1 项失败。失败为既有 Hermes 安装测试创建 `venv/lib64` 符号链接时返回 `FileNotFoundError`，对应当前文件系统已知的符号链接限制；该文件不在本分支改动范围。Fast 在此阶段退出，不能记为全门禁通过。[完整输出](2026-11-11-dsh-core-compatibility-artifacts/test-fast.txt)随分支保存，主 Agent 需记录该环境限制并另行完成必要 PR 验证。

跨层核对发现 Android 的 `generation` 帧只接受 `queued/running/unknown`，带 `state` 时还要求有效 `revision`。当前正常输入事件有原生序号时同时发真实修订号；缺序号的旧夹具只发身份。重放已终态记录时仅发身份帧，由随后 `done/error` 表达真实终态。Node 与 Core 跨语言回归均加入该消费约束，防止传输层通过却被 Android 拒绝。

真实宿主探针只访问新建隔离目录，不读取已有用户会话；[脱敏输出](2026-11-11-dsh-core-compatibility-artifacts/real-host-builtin.txt)随本分支入库。启动探针第一次误传目录入口，宿主返回 `ERR_UNSUPPORTED_DIR_IMPORT`；改用实际 `index.js` 后上述验证通过。未改动现有账号、密钥、设备绑定或服务。

## 复跑与剩余层级

```bash
node ../../node_modules/vitest/vitest.mjs run --config integrations/dsh-session-adapter/vitest.config.js --reporter=dot --detectAsyncLeaks
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=oai-core/src python3 -B -m pytest -p no:cacheprovider oai-core/tests/test_dsh_builtin_contract.py oai-core/tests/test_dsh.py oai-core/tests/test_remote_generation.py -q
npm run test:fast
git diff --check
```

提交主 Agent 集成后执行 `test:pr` 和独立 code-review，分别记录跨仓库同步、使用专用模型配置的成功回复、取消后的真实终态、断线/宿主重启恢复与 Android 模拟器结果。当前没有新增全宿主能力字段，DSH 已实现既有 `send` 门禁会调用的权威 generation 路由，避免借本项改变其他宿主行为。

## 独立评审后的第二轮修复

独立评审固定 `8e077193` 后确认四项 P2。已实际运行其探针复现重附迟到用户事件丢绑定、预查窗口丢完成、旧 attempt 分片缺失、重启未知 current 为空、raw 历史误显模型副本及取消只读即时未知，随后补回归修复：

1. 保留已恢复的原生轮次，预查后同步重读当前原生日志。重新附着使用真实宿主 `SessionController.follow` 的 `assistantStream` 开场 baseline，恢复正在执行的 attempt、已有正文及后续分片；正文帧与持久事件都走官方同一有序通路，避免同步终态抢先关闭观察。
2. 原生 `interruptedTurnClosers()` 补写的 `interrupted` 只证明结果未知，特定身份与 current 查询均保留同一未知屏障，不解锁后续队列。
3. 修正评审原先把模型 surface 当成人类历史的预期。实际发行包 `dsh-session/lib/types/surface.d.ts:50-58` 明确规定人类 transcript 使用 append-origin，replacement 副本只用于模型；`dsh-api-session-controller/lib/index.js:1209` 同样使用 `isAppendSurfaceEvent`。因此保留完整 raw 日志里的原始可见消息，排除模型 replace 副本；元数据投影也跳过替换副本，避免同 rpcId 多命中。真实 `Session.create` 及 `isAppendSurfaceEvent` 回归通过。
4. 同步匹配和取消保持原子性，之后先订阅、再核对、有界等待同目标真实终态。异步 `aborted` 返回 `cancelled`，已完成返回 `already_completed`，期限到后才返回协议 `outcome_unknown`；关闭取消观察不重复中止其他任务。

修复版评审探针及输出：[脚本](2026-11-11-dsh-core-compatibility-artifacts/builtin-review-fixed-probe.mjs)、[结果](2026-11-11-dsh-core-compatibility-artifacts/builtin-review-fixed-probe.txt)。该脚本为真实 Session/恢复原语加官方 follow 形状的受控夹具，未冒充真实模型验证。当前六份 Node 测试 **116 项通过**，Core 专用契约仍为 **6 项通过**；本轮不重复全量 Fast。原 Fast 输出只规范化尾随空白，原失败与通过数量保留。

第二轮独立复查进一步发现同一开场 snapshot 的两个接纳窗口：已有持久助手正文尚未终态时漏复制正文/消息 ID，以及预查排队、开场已运行时先过滤尝试后更新轮次。两个新增回归先稳定失败，再将开场接纳顺序最小调整为“同步最新原生轮次、正文、消息 ID → 恢复 activeAttempt 和前缀 → 处理后续帧”，没有扩展其他模块。修复后六份 Node **118 项通过**，异步泄漏检查无报告，Core 专用契约 **6 项通过**。独立探针确认完整 `done.text/messageId` 与前缀、后续 delta 均恢复，监听器清理为 0；[修复探针](2026-11-11-dsh-core-compatibility-artifacts/builtin-review-round2-fixed-probe.mjs)和[输出](2026-11-11-dsh-core-compatibility-artifacts/builtin-review-round2-fixed-probe.txt)已入库，待最终独立复核。
