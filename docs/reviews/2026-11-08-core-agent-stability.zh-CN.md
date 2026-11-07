# OAI Core 与 Agent 通信稳定性修复记录

基线：`dev@a688dd634b00f9b1902734c700ec6336f712b219`，已与远端核对。开发分支为 `feature/core-agent-stability-20261108`，目标 PR 分支为 `dev`。本记录区分源码复现、自动化回归、CI、模拟器与真机证据。测试尚未完成时，不将计划或包文件存在视为验收通过。

## 已复现的源码问题

| 问题 | 触发与结果 | 修复 |
| --- | --- | --- |
| 一次探测失败破坏能力 | Remote 先成功协商，再返回一次 503；send、eventSync 被清空，后续能力门禁返回 405 | 保留上次可信能力与身份扩展；健康状态单独管理 |
| 持续活动的流被截断 | 将同一总超时参数缩短为 120ms，真实 HTTP 连续每 10ms 输出；约 121ms 收到 12 帧后返回 AGENT_UNAVAILABLE，距上次活动仅约 8ms，POST 一次 | 消息观察采用连接与读取空闲预算；桥输出兼容的 NDJSON 空行心跳 |
| 上游认证错误被误传为手机认证错误 | 已通过 Core authenticator 的请求，遇桥 401 AUTHENTICATION_REQUIRED，Core 仍返回 401 同错误码 | 桥错误按来源转为 502 AGENT_UNAVAILABLE，保留受控诊断原因；真正 Core 认证错误仍为 401/403 |

这些复现不能证明本机每次故障都由同一路径触发。现场根因需要同时核对加载版本、时间、请求身份与各阶段日志。

## 当前实现与兼容边界

- 每个已发现的 Agent 分别探测，默认周期 10 秒、单次预算 5 秒；合并同一 Agent 的并发探测。已有成功证据时，连续三次暂时失败才离线；明确认证、安全或协议拒绝立即影响该 Agent。
- 网络错误和暂时离线不清空能力；首次尚未协商的能力返回暂不可用，确实不支持的能力仍返回 405。只读桥的有效声明不以执行器在线作为前提。
- 桥可声明 `capabilities.streamHeartbeat=true`，每 10 秒在原观察流输出空行，不产生业务事件。新 Core 使用 60 秒读取空闲预算；旧桥未声明时保留 1800 秒保守空闲预算。消息流不再使用该参数作为绝对总时长；附件预算不变。
- 认证、绑定和参数检查继续先于流响应。心跳不取消等待中的下一事件，不授予能力，不作为任务受理或完成证据。
- 保留原观察/执行所有权、幂等账本、保存后 ACK、真实消息/生成身份、设备授权与结果补传。网络恢复不重投业务 POST；已开始且结果未知的副作用不再次执行。
- 故障日志仅记录受控来源、状态、原因、请求/跟踪身份和恢复转换，不记录凭据、正文、原始游标或能力结果。

## 回归与门禁

- 第一批新增 Remote 回归在修复前为 **8 failed、1 passed**，修复后与既有流帧及生成回执测试合跑为 **13 passed**。
- Core 观察生命周期、操作账本、诊断及 Remote 集成批次为 **66 passed**。
- 桥心跳、健康管理及最终 Fast/PR 门禁：实施中，最终结果将在固定源码后补充。

回归入口：

```bash
python -m pytest oai-core/tests/test_remote_communication_stability.py oai-core/tests/test_agent_health_recovery.py oai-core/tests/test_hermes_bridge_heartbeat.py -q
npm run test:fast
npm run test:pr
```

测试使用仓库所需运行时和固定 Hermes SDK；不能把缺依赖、未完成的 Android 任务或测试中的源码变化计为通过。

## 部署与设备证据

当前只读确认本机 `oai-core.service` 和 `hermes-gateway.service` 均运行。Core 实际包来自既有安装根下的 Core 源码；Hermes 解释器加载该安装根的 Core 和 Session Adapter。服务启动时间、实际源码摘要、部署前后包匹配和回退记录仍待统一冻结核对，不根据监听端口推断会话成功。

由用户指定的 GPT-6.1 Terra 使用 Android CLI 创建专用 API 35 模拟器。测试使用既有测试账号，凭据不写入本记录、脚本或证据。AVD、日志与原始证据放在任务 worktree 的忽略目录。只有核对过来源、版本、签名和摘要的 APK 才用于对应版本验收。

## 未完成验收

- [ ] 固定源码后的 Fast、PR 门禁与相关 CI。
- [ ] 已验证版本的本机 Core、Hermes 桥和 APK 部署及回退证据。
- [ ] 模拟器认证基线、消息与并发、短网抖、后台/息屏、WS/SSE 切换、分层重启和设备结果补传。
- [ ] 至少 24 小时后台稳定性观察，包含实际起止时间、版本、恢复耗时和请求身份。
- [ ] 真实手机局域网、厂商后台与息屏验证；模拟器不能替代此项。
- [ ] 中文提交、面向 dev 的打开 PR、CI 终态和审查评论核对。

## 集成审查补充

独立审查补齐了 HTTP 200 非 JSON、无效 JSON、online 非布尔值的协议拒绝，以及消息 SSE 出口的受控来源详情。协议拒绝不继续保持暂时在线；流内账号未连接不能被解释为 Core 登录失效。首次探测成功记为 healthy，真实故障后才记 recovered。新增审查反馈先红 6 项，再通过对应 31 项；完整受影响聚焦批次为 **174 passed**，含原生事件同步、Core/桥观察断开、操作恢复和跨进程重启，另有既有 aiohttp NotAppKeyWarning。

首轮本地 Fast 为 24 failed、1091 passed、2 skipped，包含回收目录权限、符号链接/虚拟环境创建、固定 SDK 依赖和旧单次失败预期。收紧用户自有回收目录至 0700 后，相关卸载检查已继续通过；符号链接最小复现即使在沙箱外仍返回 ENOENT，未修改断言。后续 Fast 又暴露原 setup fixture 会按本机是否安装 DSH 而重启真实服务、导致握手次数随环境变化；已将该 fixture 的服务重启限定为合成桥测试，相关 45 项通过。实际进程恢复继续由独立通信进程测试及部署场景覆盖。最终门禁不以这些中途运行替代。

本机候选通过任务专用导入路径加载。源码副本保留既有部署的不相关修改，只加入当前通信修复，原安装源码和账号数据未覆盖。首次摘要核对发现桥补丁未被嵌套目录中的 git apply 实际应用；未将这轮称为心跳部署成功，已改用 patch 并断言心跳代码存在，再重载 Hermes。最终加载摘要与启动时间以脱敏证据为准。

## 本机候选与只读观察器冒烟

- Core 候选服务于 **2026-11-08 05:27:28（Asia/Shanghai）**启动，Hermes 心跳候选于 **05:29:14**启动。Core 实际加载 sessions、agent_health、remote；Hermes 实际加载对应 sessions、agent_health、bridge，均按源码摘要核对，不只根据服务 active 判断。
- 标准 Core 客户端使用现有测试身份认证成功，随后 devices 查询成功，Agent 列表中 Hermes 在线，原生发送、历史、generationRecovery 和 eventSync 均有有效声明。没有记录账号密码、令牌、设备内容或消息正文。
- `tools/observe-communication.py` 通过现有客户端续签读取 Agent 状态；只读、无业务 POST、无会话创建。默认 86400 秒、间隔 10 秒；短运行、源版本不明、零样本或失败不会输出 passed=true。
- 本机两秒冒烟得到 2 个正常样本、Core/Agent 请求失败均为 0、版本摘要不变；结果是 **incomplete / passed=false**，不能作为 24 小时通过。
- Android 新增回归不改生产 UI 或文案；覆盖桥 502 不触发 Core 续签、POST 不重发，以及 Native 原游标与 ACK 恢复。Kotlin 实际执行结果仍待完整门禁或 CI，不凭静态检查声明通过。

正式观察示例（配置文件为任务私有凭据配置，不提交）：

```bash
python tools/observe-communication.py \
  --client-config .toolchains/acceptance/core-observer-client.json \
  --output-dir docs/reviews/evidence/2026-11-08-core-agent-stability/observe-24h \
  --duration 86400 --interval 10 --request-timeout 5 \
  --agent-id hermes --manifest .toolchains/deployment/observer-manifest.json
```

由调用方后台启动并记录真实进程句柄；若故障注入、部署版本变化或进程停止，应保留原始样本并单独说明，不能改写为稳定性通过。PR 在最终门禁、24 小时或真机验收缺失时保持待验收状态。

## PR 与真实消息基线

首个修复提交 `a23be926a26300f258eb8aa09c51d59c2c32d9df` 已推送，草稿 PR 为 **#95**（目标 dev，保持打开）。固定提交已触发 Core/Adapter 和 APK CI；后台观察记录检查终态及 review，不前台等待远端构建。

本地 PR 门禁完成的 Core/安装器阶段为 **1127 passed、3 failed、2 skipped、6 subtests passed**，三项失败均为符号链接创建 ENOENT；该次运行期间观察工具发生变更，不能作为最终固定源码准入。未执行到后续 Android 阶段，因此 Android 的新增回归仍交 CI 验证，不声明本地完整门禁通过。

本机真实 Core＋Hermes 已创建一条明确标记的测试会话，并仅发送一次不调用工具的合成消息。创建 POST 与消息 POST 各一次；消息以原 `clientMessageId` 取得 `generationId` 和真实 `messageId`，约 **11.707 秒**收到 done、未收到 error。精确身份和计数保存于脱敏证据；这证明该请求链路成功，不代表尚未执行的并发、断网、设备能力或 24 小时场景通过。

## 故障恢复与审查修正

- **Core 真重启：通过该场景。**独立测试会话消息只 POST 一次，收到真实 generation 后关闭观察并重启 Core；Hermes PID 不变。约 17.71 秒内以只读查询取回同 generation 的 completed，宿主历史中相应用户行和回复各一条。该证据不扩展为其他故障场景通过。
- **Hermes/Gateway 真重启：通过该场景。**任务已结束后重启 Hermes，Core PID 保持；约 11.54 秒内原测试历史重新可读。期间 Agent 的已协商能力保留；Agent 在线并不能替代具体会话可读，因此同时核对了历史响应。没有重发任务。
- **观察器审查：**补齐最后一段等待期间的源码变更检查及 HTTP 200 协议错误的分跳失败统计，新增回归先红 4 项，修复后 8 项通过。
- **附件调用：**补齐远端 PUT 401/403 的来源转译；本地 NativeAttachment 授权拒绝仍保持原义，远端认证故障不触发客户端 Core 续签。10 项真实 HTTP 附件回归通过，相邻 32 项通过。
- **首个 CI：**Core 3.11/3.12、Hermes、DSH 和 APK 已成功。新增 Fast/PR 准入 job 在卸载 fixture 上失败，原因是另一个测试先以 0755 创建公共测试回收目录，卸载正确要求 0700。准入 job 已补受控、用户自有目录的私有权限准备；不放宽卸载安全校验。Android job 及后续固定提交 CI 继续待核对。
