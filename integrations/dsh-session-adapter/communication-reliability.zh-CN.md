# DSH / Codex 通信可靠性修复与专用验证

本轮基线为 `33ab7a2a159b095e0d6408edc8888625011b3e84`，写入工作树为 `communication-reliability-20261030`。只修改本目录、[Codex 适配器](../../oai-core/src/oai_core/adapters/codex.py)及其[专用测试](../../oai-core/tests/test_codex.py)。共享工作树中的 Core、Android、Hermes 和其他改动由对应 agent 负责；此处的结果不代表其他改动或全量门禁通过。未提交、推送、调用真实模型或扰动现网。

## DSH 行为

- `prompt()` 的受理 Promise 与 HTTP 观察分开。受理一直未回、响应关闭、写出超限均会清理 timer、listeners、drain 订阅和观察 busy。未完成的宿主 Promise 只持有可解绑的回调槽，观察退出后不继续持有响应与正文队列。
- 不向 `controller.prompt` 传入随 HTTP 关闭而 abort 的信号，不取消宿主已进入 inbox 的输入。受理回执和原生 user/message 均可作为受理事实；原生终态无需等待迟到的受理 Promise 才能交付。
- 队列最多 128 帧且最多 1 MiB；流式 attempt 关联集合也有限额。写端返回 `false` 时停止写出并等待 `drain`。超过队列或 drain 预算会结束观察，宿主任务继续。
- 已收到的 `clientMessageId` 经格式校验后原样传给原生 `requestId`。不按文本或时间猜身份；旧调用没有该字段时维持原来的随机 request ID。
- `generationRecovery=false`、`idempotentSend=false`。此处没有持久回执库，也不宣称原生 host 会按 requestId 去重。观察期限或通信异常无法证明执行失败，因此使用 `OUTCOME_UNKNOWN` 与 `state=unknown`。原生 completed、error、aborted 等事实继续按真实 reason 映射。

## Codex 行为

- Core 已持有消息 producer；适配器另持有的仅是 native turn 生命周期任务和有限事件订阅，不复制持久聊天库或 producer。`send().aclose()`、消费任务取消、观察超时和队列溢出均只移除观察订阅。
- 原生读循环独立跟踪真实 turn/completed。2000 个 delta 即使导致观察缓冲溢出，也不会阻塞其他会话的 RPC，或删除服务持有的 turn 关联。turn/start 前到达的终态用真实 turn ID 匹配，缓冲有上限。
- turn/start RPC 由服务任务等待；外部观察的受理期限不取消该等待。迟到的实际 turn ID 仍可绑定，显式取消仍能命中真实 turn，且不会重发 turn/start。
- 显式 cancel 才调用 turn/interrupt；带 generationId 时必须与当前真实 turn ID 一致。身份未知时保留 SESSION_BUSY 预约，取消返回 OUTCOME_UNKNOWN；不会中断任意宿主 turn。
- 宿主失联或 Core 关闭只报告观察未知，不制造 native failed/completed。只有真实 native completed / interrupted / failed 才释放对应执行终态；未知 status 不作为终态。
- 保留显式 CODEX_HOME、运行环境白名单、设备凭据隔离和原生审批拒绝策略。没有新增宿主未支持的 turn/start 字段，`generationRecovery` 与 `idempotentSend` 都为 false。

## 测试与复跑

以下命令在授权工作树根目录执行。Python 与 Node 复用原根现有环境，不安装依赖。Python 源码来自本工作树；所有测试临时内容与 Vitest 缓存放在本目录被忽略的 `.test-cache/`。Codex fixture 用隔离 HOME 和本地 Python 子进程模拟 JSON-RPC，不接触真实 Codex home、模型账户或设备凭据。

```bash
mkdir -p integrations/dsh-session-adapter/.test-cache/python
PYTHONDONTWRITEBYTECODE=1 \
PYTHONPATH=oai-core/src \
TMPDIR="$PWD/integrations/dsh-session-adapter/.test-cache/python" \
../../.toolchains/pr64-core-only-python/bin/python -B -m pytest -p no:cacheprovider \
  oai-core/tests/test_codex.py oai-core/tests/test_dsh.py -q

../../node_modules/.bin/vitest run \
  --config integrations/dsh-session-adapter/vitest.config.js --reporter=dot --detectAsyncLeaks

node --check integrations/dsh-session-adapter/index.js
node --check integrations/dsh-session-adapter/adapter.test.js
node --check integrations/dsh-session-adapter/reliability.test.js

git diff --check -- integrations/dsh-session-adapter \
  oai-core/src/oai_core/adapters/codex.py oai-core/tests/test_codex.py
```

修复前已实际复现 DSH 7 项失败；Codex 3 项回归均命中观察关闭、超时或慢观察误发 turn/interrupt。首次 Python 环境探测缺少 aiohttp，后改用原根已有的 pr64-core-only-python；随后为 fixture 隔离 HOME，未绕过凭据保护。

当前验证：Python Codex 26 项及 DSH 3 项，共 29 项通过；DSH JavaScript 33 项通过，并启用 `--detectAsyncLeaks` 检查。包括 Core HTTP → Codex JSON-RPC 的实际调用回归：关闭 HTTP 观察后无 interrupt，另一会话历史可读，显式 cancel 端点仅对当前真实 turn 发 interrupt。另覆盖 pending prompt / 迟到 accepted / 断开观察 / native 终态清理 / 宿主失联未知 / 2000 delta 慢写端 / drain 等待 / 字节预算和稳定 client 身份。

主 agent 通用 Core 验证曾出现 344 passed / 8 skipped / 1 failed，失败点为审批测试在宿主消费 decline 之前立即读取 requests.jsonl。已核对[该轮证据](../../docs/reviews/evidence/2026-10-30-communication-reliability/core-suite.txt)。修复只修改同步条件：fixture 在实际消费审批回复后记录决策，测试等待同一有序原生连接上的 `test/approval-barrier` RPC 回应，再同时断言宿主决策恰为 decline、真实回复内容为 decline、无隐式 turn/interrupt。生产审批策略不变，没有添加固定 sleep。修复后 Codex 26 项通过；该审批用例用 unittest.TestSuite 单独重复 100 次，100 项全部通过。通用 Core 套件由主 agent 另行复核，此处不将旧失败轮次改记为通过。

额外泄漏检查发现旧 DSH 测试只断言 HTTP status、未消费错误响应正文。现已读取并断言对应 JSON 错误码，DSH 33 项和异步泄漏检查重新通过，未忽略泄漏报告。

## 整合边界

Python [DshSessionAdapter](../../oai-core/src/oai_core/adapters/dsh.py) 在本轮读取时仍沿用 RemoteSessionAdapter 默认的 `forward_client_message_ids=false`，因此旧 Core 到 DSH 的默认请求仍只有 text。本次只实现 HTTP 入口对“已收到的 clientMessageId”的真实透传；Python 桥启用透传需要主 agent 在其授权范围内单独对接及更新传输回归，不可把本轮结果视为已经端到端启用。可选 traceparent / 额外分段计时未在本轮添加。

以上为本地 fixture、实际 HTTP 和 JSON-RPC 界面验证；没有进行真实模型、手机、现网宿主或 CI 验收。未运行整个共享工作树的 test:fast，避免把其他 agent 正在修改的模块结果计入本 scope；这里只报告上面的专用验证。

后续身份回归补齐：Codex 在 turn/start 确认后发出真实 generation ID，done 同样关联该 ID。取消按原生 interrupted/completed 事实返回 cancelled/already_completed；RPC 成功且无终态时仍 unknown。新增 Core HTTP 到原生 turn 的匹配取消、错误 ID、终态竞争及查询回归后，Codex 专项为 30 passed、6 subtests passed。
