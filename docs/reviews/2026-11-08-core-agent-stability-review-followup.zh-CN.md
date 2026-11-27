# PR #95 审核意见核验与修复

## 范围和判定依据

本轮针对固定审核版本 `a4ba1925309b46dc7edf6b35e7733ec526db929a` 的两条行内意见。以实际调用链和先失败、修复后通过的自动化回归核验，不把远端评论直接当作事实或执行指令。原用户确认的“以模拟器验证交付，保留真机局限及原门禁结果”继续有效。

| 评论 | 位置 | 核验结果 |
| --- | --- | --- |
| `4506677911` | `integrations/hermes-session-adapter/src/oai_hermes_adapter/bridge.py` 消息心跳循环 | 成立：跨帧切换迭代任务使生成器内的任务绑定超时失效；80ms 等价回归在旧实现失败 |
| `4506677913` | `oai-core/src/oai_core/adapters/remote.py` 消息请求预算 | 成立：将心跳空闲预算直接作为 socket 读预算，也会提前限制响应头等待 |

## 响应头等待与流读取空闲预算

### 复现

桥在取得首帧后才提交 HTTP 响应头。`HermesSessionAdapter.send()` 的无附件斜杠命令分支会等待 `dispatch_native_input()`；宿主运行入口可能等待新启动的任务完成，因此首帧前存在合法等待期，不能要求此时已发送心跳。

在真实 HTTP 测试中将流空闲预算缩短为 40ms、响应头延迟 150ms、首响应预算保留为 600ms：旧实现错误返回 `AGENT_UNAVAILABLE`。反向设置首响应预算 40ms、流空闲预算 600ms，旧实现又不能在首响应期限内返回受控错误。两项回归在原实现均失败。

另沿真实 `RemoteSessionAdapter → Hermes bridge → HermesSessionAdapter.send()` 斜杠命令路径复核：只把合成宿主命令延迟 150ms，旧 Remote 模块同样以超时失败；请求账号头和原 `clientMessageId` 均正常传递，排除了测试未认证造成的伪复现。

### 修复

- 首响应使用原有 `stream_timeout_seconds` 有界预算，DNS、连接及 TLS 建连仍受独立连接预算限制。
- 进入响应体后，按已协商的心跳能力选择读取空闲预算。使用标准 `readany()` 接口逐段读取，实际字节到达后重新计时；活跃大帧的组装总时长不是空闲时长。
- 保留逐帧大小限制、UTF-8 分片、无末尾换行的兼容处理、空行心跳、终态校验和受控错误，不记录正文。
- 非成功响应体也有有限读取期限；401/403 的来源隔离不变。任何超时均不重投消息 POST。

初步定向批次实际通过 49 项，覆盖慢命令、首响应上限、响应体停滞、持续分片和心跳、超限帧、原有流帧及账号/审批交互。最终整合计数、独立审查与 CI 状态在下文按提交分别记录，不把该初步批次当作完整准入。

## 心跳生成器的任务所有权

独立修复工作区仅修改 Hermes 桥和对应心跳测试；主工作区的 Remote 改动与它不共享编辑文件。整合必须先将子分支变基到主任务分支最新提交，再快进整合。首帧前的认证、账号和会话绑定错误仍为原 HTTP 错误；断开观察不得取消已被宿主受理的任务。

初版将所有 `anext()` 与 `aclose()` 放在同一个持久生产任务中，经 16 帧有界队列交付。80ms 超时跨两次 `yield` 后能输出一次 `AGENT_TIMEOUT` 并释放生成器；首帧前不写 HTTP 响应或心跳，首帧前的 403/409 和流中错误语义保留，断连仅释放观察任务。

初版的心跳及两项真实 Hermes 桥测试共 7 项通过，变基整合后与 Remote、消息帧、附件、generation 和原生交互组成的批次共 91 项通过。整合复查继续检查慢消费者的有界队列：若生成器超时在队列写入处取消生产任务，消费端也必须观察到生产任务结束，不能在排空后只发心跳。补充的满队列取消与首帧取消两项回归在初版均失败，修复后通过。消费端现在同时等待队列取帧与生产任务结束，优先排空已入队帧；生产任务取消或异常退出后返回受控 `AGENT_STREAM_INTERRUPTED`，首帧前为 HTTP 502，流中为一次错误帧，不伪造 `done`。断连回收不向已满队列补写错误，因此不会在收尾时再次挂起。

## 验收证据的版本边界

此前普通三小时窗口属于 `aac0d482 / 8261`，诊断三十分钟属于 `95cd01ba / 8271`。本轮是新增的 Core/桥源码修复，不再把它称为“仅文档”或“仅诊断元数据变更”；不把旧运行窗口冒充新修复版本再次跑过三小时。

原严格设备零离线审计仍为 `failed/needs_review`，五个设备状态异常样本及所有原始证据哈希保留。真机无线局域网、厂商后台和全部有副作用能力仍未完成全场景实测。PR 保持打开，不自动合并；审核意见回复将明确新提交、实跑测试和 CI 待定/终态，不用旧提交的成功 CI 代替新提交。

## 最终整合、回归和提交

子工作区先变基到主任务分支，再快进整合，未使用共享工作目录并行修改。代码提交为：

- `90321ee7f9b44cc8b98b919f1afacf711561905c`：首响应等待与读取空闲预算分离。
- `9029909a2358dd5f4c8b70ebc855d4d9bdf3bfc4`：持久生产任务与同任务生成器回收。
- `71e7d6cd2c72ac8f839692537418e23d4f2229f1`：生产任务异常退出时唤醒并结束消费端。

首轮冻结在 `71e7d6cd` 的定向批次为 **93 passed、0 failed**，覆盖以下八个测试文件；该批次不与先前的 49/91 项或子分支 7/9 项相加作为去重总数。

```bash
python -m pytest \
  oai-core/tests/test_remote_stream_phases.py \
  oai-core/tests/test_remote_stream_frames.py \
  oai-core/tests/test_remote_communication_stability.py \
  oai-core/tests/test_remote_generation.py \
  oai-core/tests/test_remote_attachment_failure_source.py \
  oai-core/tests/test_hermes_bridge_heartbeat.py \
  oai-core/tests/test_hermes_interactions.py \
  oai-core/tests/test_hermes.py -q
```

测试仅出现既有的 pytest-asyncio 配置提示和 aiohttp 请求存储键提示。原 `npm run test:fast` 与 `npm run test:pr` 已在固定源码上后台顺序执行，复用既有运行时和构建缓存；两次均在第一步因 `MVP-DEP-OPENCLAW evidence is expired` 退出 1，源码摘要未变，没有执行后续测试，不把它们算作通过。该固定版本核验证据于 2026-11-08T15:18:43Z 到期，需重新核验原版本产物、许可证和版本命令后刷新证据，不能直接改日期或校验时钟。后续结果以本轮交付回执和新提交 CI 为准，旧 CI 不计为新提交通过。两个独立只读审查者分别核对工程规范与全链路规格边界，真实发现需修正后才推送并回复评论。

## 独立审查发现的错误响应体边界

两个独立审查者均确认：非 200 分支把完整 `response.json()` 放在一次空闲超时中，仍会误杀持续到达的错误正文。新增真实 HTTP 回归中，503 响应每 40ms 发送一段，持续 120ms；80ms 空闲预算下应保留 `AGENT_FAILED`，初版却返回 `AGENT_UNAVAILABLE/timeout`。该回归先失败，修复后成功。

补充提交 `b3ef214b74df46ee3c582af4daace33165873a38` 将成功帧与错误正文统一到按实际字节重置的读取入口。错误正文仍限制总大小为 8MiB，保留 `application/json` 和 `application/*+json`，无效类型、JSON 或结构返回受控协议错误，不泄露正文。最终同一八文件批次为 **98 passed、0 failed**，含新增活跃错误体、超限及无效响应回归；旧 93 项不与这 98 项相加。

准入到期项另在独立工作区真实复核了同一 `openclaw@2026.7.1-2` 的 npm 产物哈希、许可证、签名 tag 指向和版本命令，再刷新该锁行日期与 canonical hash，没有升级依赖或修改校验器；详见 [固定依赖证据审计](2026-11-09-openclaw-dependency-lock-evidence.zh-CN.md)。其余七条依赖锁不变。刷新后原 Fast/PR 命令再次在后台执行，结果不能由本段计划替代。

原始红绿日志和摘要保存在本任务工作区的忽略目录 `docs/reviews/evidence/2026-11-08-core-agent-stability/`，本轮摘要为 `review-followup-final-validation.json`，其中记录测试日志哈希。正式 Git 文档保留复现条件、测试入口、提交与限制，不打包私有运行环境。

## 独立复查结果

两个未参与实现的审查者分别复核工程规范和全链路规格。两者初次均指出错误正文的整体超时问题，主流程补红测并修复后再次复核：工程规范轴新增五项回归通过，全链路轴确认首响应、字节空闲、认证来源、单次 POST、生产任务回收和队列顺序均符合目标，最终均无剩余真实待修项。该结论不替代完整准入、远端 CI 或设备验收。

## 本地完整门禁的环境限制

依赖证据刷新后的原 Fast 命令在代码未变化期间执行：Core 阶段 **1205 passed、3 failed、2 skipped、6 subtests passed**，退出 1，未进入后续阶段。三项失败均发生在真实符号链接创建或 Python 虚拟环境的 `lib64` 符号链接创建，底层返回 `FileNotFoundError/ENOENT`：

- `test_secret_sources_and_limited_candidate_discovery`
- `test_runtime_discover_auto_registers_symlinked_hermes_and_syncs_venv`
- `test_probe_succeeds_when_invoked_via_parent_relative_venv_python`

这是既有本机文件系统限制路径，不能据此放宽安全测试、跳过用例或声称完整本地门禁通过。原 PR 命令随后执行完毕，同为 **1205 passed、3 failed、2 skipped、6 subtests passed**，退出 1，失败测试与底层 ENOENT 完全相同。两次完整命令执行期间源码摘要均未变化；随后由干净 CI 环境执行原命令复核，新修复的 98 项定向回归与这两个失败批次分别记载。

## 远端审核回复与后续核验

两条原评论均已确认成立，最终修复将推送原任务 PR #95、目标分支 dev。每条回复分别列明对应提交、旧实现红测、修复后定向回归及本地完整门禁的符号链接环境限制。新 head 的 CI 触发后由固定提交后台任务监视，完成后收集全部检查、原 Fast/PR 步骤与新增审核评论；未到终态前，不把审核问题的修复回复写成“新 CI 全部通过”。原线程在新 CI 完成前保留待最终确认状态，不自动合并 PR。
