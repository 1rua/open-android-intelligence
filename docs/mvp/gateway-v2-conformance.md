# Gateway Protocol v2 双宿主一致性门禁

## 目的

Open Android Intelligence 的 Gateway Protocol v2 有两个**独立实现**：

| 宿主 | 实现语言 | 代码位置 | 声明的 implementation ID |
| --- | --- | --- | --- |
| OpenClaw | TypeScript | `integrations/openclaw/src/core/shared-vectors.ts` | `openclaw-typescript` |
| Hermes | Python | `integrations/hermes/open_android_intelligence_gateway/core.py` | `hermes-python` |

两者**不共享二进制、不互相调用**，但必须对同一份协议向量产生**完全一致的可观察结果**。本门禁就是证明这一点的自动检查。

## 运行方式

```bash
npm run gateway:v2:conformance
```

该命令依次执行三步，任一失败即整体失败：

1. `gateway-contract/tools/run-openclaw-conformance.ts` — OpenClaw runner（TypeScript 进程）
2. `gateway-contract/tools/run-hermes-conformance.py` — Hermes runner（Python 进程）
3. `gateway-contract/test/cross-host-conformance.test.ts` — 比对两侧产物

> 单独执行 `npx vitest run` 也会覆盖该门禁：若产物缺失或过期，测试会**自动重新生成**两个 runner 的产物后再比对。

## 共享输入

两个 runner 消费完全相同的输入，不存在任何宿主本地的 Schema/向量副本：

- 六个向量文件：`request-signatures.json`、`protocol-negotiation.json`、`auth-sessions.json`、`attachments.json`、`sse-events.json`、`device-requests.json`
- 唯一的动态分派 fixture registry：`dispatched-schema-fixtures.json` + `dispatched-schema-fixtures-1.0.0.schema.json`，binding set 恒为 `gateway-core-fixtures-v1`

每个 runner 在构造校验器**之前**必须：

1. 用 meta schema 验证 registry；
2. 校验 `formatVersion`、`catalogEntries` 数量（5）、`bindingSets` 数量（1）与 binding set ID；
3. **重算五个规范 JCS digest**并与登记值比对；
4. 校验每个 binding 都指向 catalog 中 digest 匹配的条目。

任一步不满足即抛 `INVALID_FIXTURE_REGISTRY`，进程失败关闭。请求侧不允许携带 `schema`、`schemaSha256`、`binding`、`resolver` 或 `validator` 字段。

## 结果格式

每个 case 归一化为一个契约闭合的 actual result：

- value：`{ vectorId, operation, outcome: "value", value }`
- error：`{ vectorId, operation, outcome: "error", code }`

其中 `code` 只允许 `SCHEMA_INVALID`、`NON_CANONICAL_TARGET`、`INVALID_STATE_TRANSITION`。

输出记录：

```json
{ "vectorId": "...", "operation": "...", "implementation": "...", "status": "pass|fail", "resultHash": "sha256:..." }
```

`resultHash = "sha256:" + lowercaseHex(SHA-256(JCS_UTF8(normalizedActualResult)))`

**哈希只覆盖契约可观察结果**，不包含 `implementation`、`status`、宿主内部 ID、时间、路径、堆栈或任何 vendor 诊断信息。这正是两个不同语言、不同进程的宿主能得出相同哈希的原因。

## 产物与防篡改

产物默认写入 `gateway-contract/.artifacts/conformance/`（已加入 `.gitignore`，属可重新生成的构建产物）：

- `<implementation>.jsonl` — 每行一条结果记录
- `<implementation>.manifest.json` — `formatVersion`、`implementation`、`caseCount`、`vectorDigests`、`recordsDigest`

`recordsDigest` 把 manifest 与其 JSONL **字节**绑定。因此：

- 手改任一 `resultHash` → digest 不匹配 → 判定 `CONFORMANCE_ARTIFACTS_STALE`
- 手改任一向量或 fixture 文件 → `vectorDigests` 不匹配 → 判定过期
- 两者都会在测试内触发**自动重新生成**，随后比对真实结果

也就是说，产物不可被用来“固化”一个错误的结果。

## 当前覆盖

24 个向量 case，覆盖以下 7 类 operation，且每个向量文件都同时包含成功与失败样例：

| operation | 覆盖内容 |
| --- | --- |
| `request.target` | canonical origin-form target 的排序边界与拒绝 |
| `request.signature` | 区分大小写 method、wire ID、固定毫秒 UTC、canonical 16-byte nonce、exact body bytes、10 字段/9 LF/末尾无 LF、空与非空 body |
| `schema.validate` | 协商、认证、附件的严格 Schema |
| `schema.validate_dispatched` | SSE 事件与设备请求的动态分派 |
| `attachment.transition` | 附件状态机 |
| `device.transition` | 设备请求状态机 |
| `device.maximum_queue_seconds` | 四档风险队列 TTL：read/sync=86400、write=900、high-privilege-ephemeral=0 |

门禁断言：两侧 `vectorId` 顺序一致、每个哈希逐项相等、状态全为 `pass`、implementation ID 互不相同但哈希相同。

## 自检记录（2026-08-29）

为确认门禁不是“空转”，执行了以下负向验证：

| 注入的变更 | 门禁反应 |
| --- | --- |
| OpenClaw 侧把 `preimageHex` 改为大写 | runner 报 `22/24 pass`，流水线退出码 1 |
| 直接篡改产物中一条 `resultHash` | 哈希比对测试失败（2 项） |
| 给 manifest 写入错误的 `recordsDigest` | 判定过期并自动重跑，比对真实结果 |

负向验证后已全部还原，当前状态为全绿。

## 验收门槛对应关系

对应实施计划 Task 6 Step 4 的要求：“协商、认证、签名、SSE、附件、队列、删除和多账号隔离向量全部等价”。

其中**删除**与**多账号隔离**属于宿主运行时行为（SQLite、CAS、附件 staging、账号目录），不是纯 reducer/向量的可观察输出，因此不进入本共享向量集，而是分别由以下宿主自身测试覆盖：

- OpenClaw：`integrations/openclaw/test/`（账号隔离、附件生命周期、备份与轮换、设备请求队列）
- Hermes：`integrations/hermes/tests/`（同构测试，91 项）

一致性门禁保证的是**协议层**的跨宿主等价；**状态层**的等价由上述两套同构宿主测试保证。
