# 交互式命令审批卡片深度审查 —— 并行修复执行摘要

- **执行日期**：2026-09-22
- **输入规约**：[`2026-09-22-interactive-command-approval-deep-review-report.md`](2026-09-22-interactive-command-approval-deep-review-report.md)（21 项独立问题 = 3 RISKY + 7 CAREFUL + 11 SAFE，10 个文件聚类组）
- **执行流程**：`/deep-review-and-fix` Phase 4（冻结共享 API → 6 个文件隔离的并行修复单元 → 综合验证 → 独立两轴复审 → 回修 → 摘要）
- **门禁授权**：3 项 RISKY 经用户明确批准，全部纳入本轮自动修复
- **改动规模**：19 个文件（16 个 Kotlin、3 个 Python、1 个契约文档），`gateway-contract/**` 与 OpenClaw 适配器**零改动**

---

## 一、验证结果（全部为本机实测）

| 验证项 | 命令 | 结果 |
|---|---|---|
| Hermes 网关全量测试 | `/home/djbd/.venvs/oai-gateway/bin/pytest integrations/hermes/tests/ -q` | **202 passed**（基线 196 + 新增 6） |
| Hermes 审批卡片专项 | `… pytest integrations/hermes/tests/test_command_approval_cards.py -q` | **21 passed**（基线 15 + 新增 6） |
| Android `conversation-domain` | `./gradlew --offline :conversation-domain:testDebugUnitTest` | **19 tests, 0 failures** |
| Android `conversation-data` | `./gradlew --offline :conversation-data:testDebugUnitTest` | **35 tests, 0 failures** |
| Android `gateway-client` | `./gradlew --offline :gateway-client:testDebugUnitTest` | **142 tests, 0 failures** |
| Android `conversation-ui` | `./gradlew --offline :conversation-ui:testDebugUnitTest` | **204 tests, 0 failures** |
| Android 跨模块编译 | `./gradlew --offline :app:compileFullDebugKotlin` | **BUILD SUCCESSFUL** |
| 契约一致性 | `npx vitest run gateway-contract/test/` | **164 passed**（7 files） |
| 核心 Schema 摘要 | `git status --porcelain` 确认 `gateway-contract/**` 未被修改 | **摘要不变**：`sha256:665df51661c11f9f770c11abb10b9be5fc343ffd41507e12625f3a011072cacb` |

**结论：本次改动不需要 App 与插件同版本升级**（未触碰 7 个核心 Schema 中的任何一个，因此无需同步 Kotlin `SchemaContractHash.CORE` 与 OpenClaw `plugin-manifest.json#capabilitySchemaHash`）。

### 关键回归测试的「失败能力」已实测

新增的「宿主解阻塞不得在写事务内执行」用例若在旧实现下运行必然失败，这一点用独立探针实测确认而非推断：

```
在一条连接持有 BEGIN IMMEDIATE 期间，另一条连接连 AccountStore 都打不开：
  AccountStore.__init__ → INSERT INTO account_metadata(...) → sqlite3.OperationalError: database is locked
```

即 RISKY① 的危害（宿主线程/其它连接在排他锁窗口内被拒）真实存在，修复不是理论洁癖。

---

## 二、已修复问题清单

### 2.1 RISKY（3 项，均经用户批准）

| # | 问题 | 修复落点 | 回归防护 |
|---|---|---|---|
| 1 | `_resolve_host_approval` 在 `BEGIN IMMEDIATE` 内跨线程解阻塞；`settle()` CAS 返回值被忽略 → 幽灵决策事件与状态分裂 | `core.py`：`_run_idempotent` 新增尾参 `preflight=None`（**只读重放探测 → 无锁钩子 → 事务内权威复核 + 落定**）；决策处理拆为 `_prepare_approval_decision`（只读 + 解阻塞，拒绝以返回值回传）/ `_handle_approval_decision`（事务内 CAS + 事件 + 审计）；新增 `_already_settled_response` 处理「写入未胜出」 | 新增 `test_the_host_hand_off_happens_without_the_write_lock_held`、`test_a_press_that_loses_the_settlement_publishes_no_ghost_event`；既有重放/二次决策冲突/宿主不再等待三用例保持通过 |
| 2 | 决策端点响应体全量回传内部字段（`sessionKey`/`hostRequestId`/`decidedByDeviceId`） | `core.py`：新增 `_public_approval()` 白名单投影，**两个**成功返回点（已落定幂等返回 + 正常返回）均经它；幂等账本因此缓存公开体 | 新增 `test_the_decision_response_carries_no_host_internal_identifier`、`test_the_answer_to_a_decision_already_taken_is_projected_too` |
| 3 | 未声明审批卡片能力时 `adapter._send_exec_approval_prompt` 仍插卡并返回 `success=True`，抑制宿主原生文本提示 ⇒ 手机端无卡、无提示，命令阻塞到超时 | `adapter.py`：`not core.approval_cards_available` 时返回 `SendResult(success=False, error="APPROVAL_CARDS_UNAVAILABLE")`；`core.py`：`_prepare_approval_decision` 在能力不可用时拒绝端点（`APPROVAL_UNSUPPORTED` → HTTP 400，未登记状态码默认 400，**无需改 `http.py`**） | 新增 `test_a_gateway_without_the_capability_leaves_the_text_prompt_alone`、`test_the_decision_endpoint_is_closed_when_the_capability_was_never_declared`，并在状态映射用例中断言 400 |

### 2.2 CAREFUL / SAFE（18 项，按报告 Group 1~10）

| 分组 | 目标文件 | 修复内容 | 验证 |
|---|---|---|---|
| Group 1 | `ApprovalCard.kt` | ① `chunked(MAX_BUTTONS_PER_ROW)` 去掉冗余三元；② `strokeWidth = 2.dp` → `Dimensions.StrokeStitch`（并移除因此不再使用的 `dp` import）；③ 按钮组移出所有动画作用域——Waiting 与 Submitting 共用同一个 `OptionGroup`，被按下的档位保持组合身份、**原地**由文字变进度指示，其余档位原地转 inert，仅结论行保留淡入且继续遵守 `reduceMotion`；④ 计时状态收敛为「写点唯一、读点唯一」——卡片主体只读一次性派生的「窗口已关闭」布尔（整卡每秒不再重组），剩余秒数改为延迟读取、在 `CountdownBadge` 自己的作用域内求值 | `ApprovalCardTest` 全绿 + 新增 `aPressedTierTurnsIntoTheSpinnerInPlace`（断言被按下按钮仍在原位、其余按钮仍在且不可用） |
| Group 2 | `ApprovalCardState.kt` | ① 删除零引用扩展属性 `isSubmitting`、`isAnswerable`；② `settledChoice` 由 7 分支手工 `when` 改为复用 `ApprovalOutcome.asChoice` | 全仓库残留检索 0 命中；`ApprovalStateMachineTest` 全绿 |
| Group 3 | `Approval.kt` | ① 删除零引用死函数 `optionFor`；② 新增契约闭集枚举 `ApprovalSeverity`（`info\|elevated\|critical`，`of()` 对未知值返回 `null`，不伪造新档）+ 成对扩展 `ApprovalChoice.toOutcome()` / `ApprovalOutcome.asChoice`（本轮作为**冻结共享 API** 先行落地） | `conversation-domain` 19 用例全绿；`severity` 字符串比较与构造全仓库 0 残留 |
| Group 4 | `WorkbenchController.kt` | ① `WorkbenchUiState.approvalCardsSupported` 默认值 `true → false`（无 Gateway 事实时不得宣称支持）；② `update()` 仅在能力值确实变化时才 `copy`，消除每次按键的额外分配（「任何一次 update 后能力值等于真实值」的保证保留）；③ `submitApprovalDecision` 用 try/catch 替代 `runCatching`/`Result.runCatching`，**显式重抛 `CancellationException`** 且不再对 value class `ApprovalId` 装箱；④ 删除 `outcomeOfChoice`，改用 `ApprovalChoice.toOutcome()` | `ApprovalStateMachineTest` 全绿；`outcomeOfChoice` 残留 0 命中 |
| Group 5 | `GatewayConversationRepository.kt` + `GatewayEventDecoder.kt` | ① 决策提交显式重抛取消异常；② SSE 热路径单次 JSON 解析：新增 `DecodedFrame` + `decodeWithGenerationId`，`decode`/`generationIdOf` 保留原签名并委托同一次解析；③ 全限定类名清理（**报告只点名仓库文件，实际解码器噪点更重，已一并覆盖**）；④ 档位↔决策枚举按 `wireValue` 互转（`ApprovalDecision.of` / `ApprovalChoice.of`），删除两处手工 `when` | `conversation-data` 35 用例全绿 + 新增 2 用例锁「一次调用两半都对」「未建模事件名仍能取到 generationId」 |
| Group 6 | `ApprovalClient.kt` | ① 每个分支只解析一次响应体，`dataOf`/`errorCodeOf`/`recordedDecisionOf` 改为吃已解析 AST（类保持无状态、可并发）；② 删除 `ApprovalDecisionResult.isSettled`（与 `ApprovalCardState.isSettled` **同名反义**），两处测试断言改为直接断言终态 `ALREADY_RESOLVED`/`EXPIRED` | `gateway-client` 142 用例全绿 |
| Group 7 | `MessageTimeline.kt` + `WorkbenchScreen.kt` + `FloatingConversationPanel.kt` | 回调签名扩展为 `onDecide(approvalId, choice)`；抽出**行级组件 `TimelineRow`**，由卡片内部绑定自身 `approvalId`；`WorkbenchScreen` 直接用 `TimelineRow(entry, controller::decideApproval)`，删除 6 层属性解构与 `MessageTimeline(listOf(entry))` 单条目包装；浮动面板删除 `last.last()…` 解构 | `MessageTimeline` 调用点全部适配（位置传参 + 默认参数对既有测试源码兼容）；`listOf(entry)` 在生产代码 0 残留 |
| Group 8 | `ConversationPorts.kt` | 8 处冗余全限定名替换为文件顶部已有 `model.*` 通配 import 的短名 | 纯等价替换，字段/默认值/签名语义未动；`conversation-domain` 全绿 |
| Group 9 | `adapter.py` | 删除内嵌重复闭包，改为复用 `core.resolve_host_approval`（**保留**「宿主运行时是否可达」的探测语义，否则能力位会退化为永不声明） | Hermes 202 用例全绿 |
| Group 10 | `core.py` | ② 日志粒度对齐：`_assert_negotiation_bound` 的两条拒绝分支补齐 warning（含 refuse 原因：未绑定/绑定到其它账号/绑定到其它安装/已过期），与协商其它拒绝分支同级 | Hermes 202 用例全绿 |

> Group 10 的第 ① 项（协商特性读取防卫化）经实证判为**无效报告**，见下节第 3 条。

---

## 三、与审查报告不一致的事实（纠正记录）

1. **`ApprovalClient.isSettled` 不是「死属性」**。生产代码确实零引用，但 `ApprovalClientTest` 有两个断言在用它。按用户授权，处理方式为：删除该属性并**把断言改为直接断言终态值**（语义等价，覆盖强度不降）。删除该属性的理由是它与会话层 `ApprovalCardState.isSettled` 同名反义（这里把 `SUBMITTED` 也算「已落定」，而会话层只有 `Resolved` 才算）。

2. **`ApprovalSeverity` 枚举化并不是「零行为风险的 SAFE」项**。它跨 3 个主源文件（`Approval.kt`、`GatewayEventDecoder.kt`、`ApprovalCard.kt`）与 3 个测试文件，属跨模块领域模型变更（CAREFUL 级）。因此本轮把它作为**冻结的共享 API 先行落地**，再让其余单元使用，以把失败面收窄到单文件。

3. **Group 10 第①项（协商特性读取防卫化）是误报，未做「修复」**。报告称不写 `.get()` 会导致「未捕获 500 崩溃」。实测探针结果：
   ```
   complete body        -> success
   missing auth         -> SCHEMA_INVALID
   missing messages     -> SCHEMA_INVALID
   missing attachments  -> SCHEMA_INVALID
   missing events       -> SCHEMA_INVALID
   missing deviceRequests -> SCHEMA_INVALID
   no features at all   -> SCHEMA_INVALID
   ```
   原因是 `negotiate.request` 的 schema 已把这 5 个字段声明为 `required`，而 `_build_negotiation_response` 在任何读取之前先执行 `contracts.validate("negotiate.request", body)` ⇒ 非法请求得到 400 `SCHEMA_INVALID`（客户端映射为「协议不兼容」类），根本走不到下标读取。**因此没有加 `.get()`**：加了只会是永远不可达的死防御代码，且会把「拒绝不合规请求」悄悄变成「接受它」，属于契约退化。

4. **Group 5 的全限定名清理范围应覆盖解码器**。报告点名的 `GatewayConversationRepository.kt` 有噪点，但同模块 `GatewayEventDecoder.kt` 的 FQCN 密度更高，已一并清理（纯等价替换）。

5. **报告 §六 的整改建议「仅在数据库 CAS 成功落定后再唤醒宿主线程」与契约冲突，未按字面执行**。契约 §7.2 明确规定：决策到达时宿主已不再等待该审批（命令已由其它路径解除阻塞/宿主重启/会话重置）时，**必须在同一事务内结算为 `withdrawn`，不得把用户按下的档位记成已允许**。要判断「宿主是否还在等待」，只能在写入终态**之前**调用宿主解阻塞。因此实现为「无锁阶段解阻塞 → 事务内 CAS 落定」，同时满足：跨线程调用已移出排他事务、同 `Idempotency-Key` 重放不会二次解阻塞宿主（既有测试 `test_the_same_decision_replayed_is_the_retry_it_looks_like` 锁定该不变量）、CAS 未胜出时不产生幽灵事件。

6. **独立复审（标准轴）提出的「`ApprovalCard` 可能同时渲染两行结论」经核实为误报**。`settled = state.isSettled`，本地超时行位于 `if (!settled)` 块内、Gateway 结论行位于 `if (settledOutcome != null)`，而 `settledOutcome != null` 当且仅当状态为 `Resolved`（此时 `settled == true`）⇒ 两者互斥，不存在重叠渲染。

---

## 四、仍待处理问题

| # | 问题 | 性质 |
|---|---|---|
| 1 | **未提交**：按仓库 `AGENTS.md`「先验证、后提交推送、再由 CI 复核」，本轮已完成到「验证通过」，`commit`/`push` 待用户确认后执行 | 流程待办 |
| 2 | 真机回归未做：SSE/WS 断线续传、前后台自动补齐（`c1a79d3`）、schema 升级后 APK 验证等既有待办仍与本次改动叠加 | 环境限制（真机时连时断、无 emulator） |
| 3 | 新回调签名的「多条目 + 携带卡片标识」行为无专门用例：`MessageTimelineStreamingTest`/`MarkdownRenderingComposeTest` 仍以默认参数调用（源码兼容），`TimelineRow` 是新公开 API 但只有间接覆盖 | 测试缺口 |
| 4 | `settledChoice` 改走 `asChoice` 后，编译器不再对新增 `ApprovalOutcome` 常量强制穷尽；现有断言只覆盖 `ALLOWED_ONCE` 一档 | 测试缺口 |
| 5 | Hermes 决策路径的「解阻塞与落定不可交错」依赖**单进程、单事件循环同步分发**这一前提（已写入代码注释）。若把 `GatewayCore` 嵌入到多线程并发调用 `handle` 的场景，需为该钩子与落定加同一把门 | 架构前提（当前实现下不可达） |
| 6 | 幂等账本写入失败（`TransactionOutcomeUnknown`）路径下，超时清理会随外层事务回滚（属既有设计，不是本轮引入）；`decidedAt` 仍取窗口结束时刻 | 既有边界 |
| 7 | 登录/刷新阶段的协商拒绝仍无日志（既有遗留，本轮只补齐了「协商绑定拒绝」这一分支） | 既有缺口 |
| 8 | 契约 §14 未点名 `APPROVAL_UNSUPPORTED`：客户端目前靠「未登记错误码 → 400」通用规则识别为「不可用」 | 契约待补 |
| 9 | `WorkbenchScreen.kt:567` 仍有 `strokeWidth = 2.dp` 魔数（不在本轮点名范围） | 技术债 |

---

## 五、后续建议

1. **提交与推送**：确认后按 `AGENTS.md` 用中文提交说明（建议 `修复: 审批决策事务边界与响应投影、卡片渲染连续性及死代码清理`），推送后跟踪 `ci.yml` / `android-apk.yml` 闭环。本次无 Schema 摘要变化，不涉及 App/插件同版本升级。
2. **把错误码写进契约**：在 §14（或 §7.2）登记 `APPROVAL_UNSUPPORTED`，避免第三方网关用近义码回复时客户端退化为兜底文案。
3. **补两处缺口测试**：`TimelineRow` 级用例（单条目与多条目共享回调）、`settledChoice` 全档位参数化断言。
4. **把「单进程事件循环」前提显式化**：若未来宿主允许并发调用 `GatewayCore.handle`，应在 `_run_idempotent` 的 `preflight` 外侧加进程内一次性守门（按 `approvalId`），而不是回退到「把解阻塞放回排他事务」。
5. **契约文档已补记两条**（决策端点响应的公开字段闭集 +「未声明能力即拒绝该端点」的落地语义），建议在下一次契约评审时确认措辞是否需与 §14 交叉引用。

---

## 六、本轮改动文件清单（19 个）

**Hermes 网关**：`integrations/hermes/open_android_intelligence_gateway/core.py`、`adapter.py`、`integrations/hermes/tests/test_command_approval_cards.py`

**Android 领域/数据/传输层**：`conversation-domain/.../model/Approval.kt`、`conversation-domain/.../ports/ConversationPorts.kt`、`conversation-data/.../GatewayConversationRepository.kt`、`conversation-data/.../GatewayEventDecoder.kt`、`conversation-data/src/test/.../GatewayEventDecoderTest.kt`、`gateway-client/.../approvals/ApprovalClient.kt`、`gateway-client/src/test/.../ApprovalClientTest.kt`

**Android 界面层**：`conversation-ui/.../state/ApprovalCardState.kt`、`conversation-ui/.../state/WorkbenchController.kt`、`conversation-ui/.../workbench/ApprovalCard.kt`、`conversation-ui/.../workbench/MessageTimeline.kt`、`conversation-ui/.../workbench/WorkbenchScreen.kt`、`conversation-ui/.../workbench/FloatingConversationPanel.kt`、`conversation-ui/src/test/.../workbench/ApprovalCardTest.kt`、`conversation-ui/src/test/.../state/ApprovalStateMachineTest.kt`

**契约文档**：`docs/contracts/gateway-protocol-v2.md`（§7.2 补记两条，未改动任何 schema/vector）
