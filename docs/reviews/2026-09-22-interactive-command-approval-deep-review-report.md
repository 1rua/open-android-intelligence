# 交互式命令审批卡片与协议诊断深度代码审查与主审计员报告

- **报告日期**：2026-09-22
- **审查流水线**：`/deep-review-and-fix`（5 专家并行审查 + 首席审计员仲裁 + 文件聚类）
- **审查范围**：`git diff 12ae2e9..HEAD`（涵盖交互式命令执行审批卡片、事件流断线恢复、协议诊断等系列提交）
- **核心契约依据**：
  - 契约总纲：[`docs/contracts/gateway-protocol-v2.md`](file:///mnt/数据/项目/open-android-intelligence/docs/contracts/gateway-protocol-v2.md)（重点：§7.2 交互式命令执行审批卡片、§4 能力协商与可诊断性）
  - 架构总览：[`docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md`](file:///mnt/数据/项目/open-android-intelligence/docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md)
  - 功能规格：[`docs/superpowers/specs/2026-09-21-interactive-command-approval-card.md`](file:///mnt/数据/项目/open-android-intelligence/docs/superpowers/specs/2026-09-21-interactive-command-approval-card.md)
  - 开发守则：[`AGENTS.md`](file:///mnt/数据/项目/open-android-intelligence/AGENTS.md)
- **仲裁优先级铁律**：`功能正确性与契约规约 (Correctness & Spec) > 运行与并发安全 (Safety) > 代码规范与可读性 (Standards & Readability) > 微观性能 (Micro-perf)`

---

## 一、执行摘要与数据总览 (Executive Summary)

本次审查由 5 位专业专家子 Agent（代码复用、代码质量、效率与健壮性、工程规范与代码异味、契约与规约）独立并行展开，并由主审计员（Chief Auditor）执行全量去重、冲突仲裁、风险分级与文件级聚类。

### 1.1 核心质量指标
- **专家初审原始发现**：27 项（R1 复用: 4 项, R2 质量: 12 项, R3 效率/安全: 4 项, R4 规范/异味: 5 项, R5 契约: 2 项）
- **去重合并后有效独立问题**：**21 项**
- **多方独立共识项（[多方共识]）**：**6 项**（由 2~3 位审查员独立发现并交叉印证）
- **风险等级分布**：
  - 🔴 **`RISKY`（高危门禁区）**：**3 项**（涉及跨线程锁争用/CAS 幽灵事件、会话密钥安全泄漏、未声明能力误发致人机交互死锁，**必须人工审批，严禁自动修复**）
  - 🟡 **`CAREFUL`（中度风险/重构区）**：**7 项**（涉及协程取消语义恢复、视图物理连续性重构、倒计时局部重组隔离、热路径 JSON 去重、协商特性防卫读取等，需自动化测试验证）
  - 🟢 **`SAFE`（低风险局部清理区）**：**11 项**（涉及死代码清理、冗余计算消除、全限定类名噪点导入清理、设计系统 Token 规范化等，零行为风险）
- **聚类文件组**：严格按物理路径隔离归属为 **10 个独立文件组**。

---

## 二、前期调查核验与事实纠偏 (Critique & Factual Corrections)

主审计员对前期各调查分支的记录进行了逐行对照与批判性复核，纠正了以下几处关键事实偏差：

| # | 前序声明（Claim） | 引用证据与行号 | 实际代码表现 | 纠偏结论与影响 |
|---|---|---|---|---|
| 1 | 前序称“共 20 项原始发现，去重后 15 项” | 初审记录概览 | 经严密核对，阶段 1 原始条目为 **27 项**。 | **数字失实纠偏**：实际合并去重后为 **21 项** 独立问题。 |
| 2 | `ConversationPorts.kt` 标记为多方共识 | 声称 R2 与 R4 共同发现 | R2 仅审查了 `GatewayConversationRepository.kt`；仅 R4 审查了 `ConversationPorts.kt`。 | **虚构共识纠偏**：校准为 R4 单方发现的 SAFE 项。 |
| 3 | 将 `core.py:3438-3453` 归为构造器参数膨胀 | `core.py:3438-3453` | 该处为普通领域方法 `request_command_approval`，带默认值可选关键字参数。 | **概念混淆纠偏**：`WorkbenchController` 构造器（14 参数）确有膨胀，但 Python 端符合惯用法。 |
| 4 | 前序称系统未装 pytest 无法单机验证 Hermes | 遗留问题记录 | 用户主目录下存在专属环境 `/home/djbd/.venvs/oai-gateway/bin/pytest`。 | **认知错误纠偏**：执行该环境运行 Hermes 审批卡片测试 **15 passed**，全量 196 passed。 |
| 5 | 前序漏检 CAS 返回值忽略的核心对照铁证 | `core.py:3578-3595` | `core.py:3568`（withdrawn 分支）显式执行了 `if account.approvals.settle(...)`，而第 3579 行却直接忽略返回值。 | **铁证确立**：同文件相邻分支对比直接证实第 3579 行系编码遗漏缺陷，而非设计如此。 |

---

## 三、🔴 核心高风险门禁项清单 (RISKY - 必须报送人工审批)

---

### 1. [RISKY] `core.py` 事务排他锁跨线程调用与 CAS 返回值忽略引发状态分裂与死锁
- **目标文件**：[`integrations/hermes/open_android_intelligence_gateway/core.py:3557-3595`](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/core.py#L3557-L3595)
- **代码位置与证据**：
  在 `core.py:3801`，决策请求通过 `_run_idempotent` 处理。第 3309 行在执行业务闭包前开启事务：
  ```python
  with account.store.transaction():  # 内部执行 BEGIN IMMEDIATE，获取 SQLite 排他写锁
      ...
      resolved = self._resolve_host_approval(  # 第 3557 行：跨线程调用宿主 Agent 运行时解阻塞！
          str(approval.get("sessionKey") or ""), decision, approval.get("hostRequestId"),
      )
  ```
  随后在第 3578-3580 行：
  ```python
  with account.store.transaction():
      account.approvals.settle(approval_id, decision, device_id=context["deviceId"], now=now)
      account.events.append(APPROVAL_EVENT_RESOLVED, ...)  # 忽略 settle 返回值无条件发事件！
  ```
- **技术危害分析**：
  1. **跨线程锁争用**：在持有 SQLite 排他写锁期间跨线程等待 Hermes 宿主唤醒。若宿主 Agent 线程在恢复执行时尝试向网关数据库写入消息/事件，或者并发收到其他客户端的请求，均会因等待该排他锁而发生互锁、阻塞堆积乃至崩溃。
  2. **幽灵决策事件与状态分裂**：`settle()` 是原子 CAS 更新（`WHERE approval_id = ? AND decision IS NULL`），写入成功返回 `True`，若已超时或已被其他并发请求处理则返回 `False`。第 3579 行**完全忽略了返回值**；即使写入返回 `False`，依然向客户端追加 `APPROVAL_EVENT_RESOLVED` 并记录审计日志，导致事件流与持久化数据库真实状态分裂。
- **整改方案建议**：
  - 检查 `settle()` 返回值，若为 `False` 重新读取终态；若终态与请求一致则作为幂等成功返回，若冲突则抛出 `APPROVAL_ALREADY_RESOLVED`，严禁虚发事件；
  - 调整事务边界，将跨线程解阻塞调用 `_resolve_host_approval` 移出数据库排他写事务，仅在数据库 CAS 成功落定后再唤醒宿主线程。

---

### 2. [RISKY] 决策接口响应体泄露宿主内部会话键等敏感凭据
- **目标文件**：[`integrations/hermes/open_android_intelligence_gateway/core.py:3552, 3594`](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/core.py#L3552)（源自 [`core.py:1625-1647`](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/core.py#L1625-L1647)）
- **代码位置与证据**：
  规约 [`docs/contracts/gateway-protocol-v2.md §7.2`](file:///mnt/数据/项目/open-android-intelligence/docs/contracts/gateway-protocol-v2.md#L548) 明确规定：
  > “`approvalId` 是唯一的交互句柄。宿主的会话键（`sessionKey`）、请求标识等内部概念**一律不下发客户端**，也不得由客户端提交；Gateway 必须自己持久化 `approvalId → 宿主内部标识` 的映射，使宿主重启后迟到的决策仍可解析。客户端不得借决策接口操作任意宿主会话。”
  而在 `core.py:3594` 中，决策端点直接返回：
  ```python
  return _success(context, {"approval": account.approvals.lookup(approval_id) or approval})
  ```
  底层字典映射直接包含了内部持久化字段：
  ```python
  "sessionKey": row["session_key"],        # 内部会话密钥泄漏！
  "hostRequestId": row["host_request_id"],  # 内部请求 ID 泄漏！
  "decidedByDeviceId": row["decided_by_device_id"], # 内部拓扑标识泄漏！
  ```
- **技术危害分析**：
  虽然广播事件流中做了字段过滤，但 HTTP 决策端点 `POST /approvals/{id}/decisions` 全量响应了包含内部会话凭证的数据库字典，导致宿主隔离边界失效，违反最小权限安全原则。
- **整改方案建议**：
  在 `core.py` 中建立公开字段白名单投影函数 `_public_approval(approval)`，仅对外返回 `approvalId`、`conversationId`、`decision`、`decidedAt`，彻底剔除内部会话密钥与拓扑字段。

---

### 3. [RISKY] 网关未声明审批卡片能力时 adapter.py 拦截审批提示返回 success=True 导致会话永久死锁
- **目标文件**：[`integrations/hermes/open_android_intelligence_gateway/adapter.py:1073-1138`](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py#L1073-L1138)
- **代码位置与证据**：
  契约 [`docs/contracts/gateway-protocol-v2.md §7.2`](file:///mnt/数据/项目/open-android-intelligence/docs/contracts/gateway-protocol-v2.md#L574) 规定：“未声明该能力的 Gateway 不得产生本节事件，也不得接受决策端点；此时审批只表现为宿主的文本提示，客户端必须明示卡片不可用。”
  但在 `adapter.py:1073` 的 `_send_exec_approval_prompt` 中，代码完全未检查 `core.approval_cards_available`：
  1. 无论是否声明卡片能力，适配器依然向数据库插入卡片并返回 `SendResult(success=True)`；
  2. Hermes 宿主收到 `success=True`，**抑制了原生纯文本命令（`/approve`）的输出**；
  3. Android 客户端协商结果为 `supportsApprovalCards == false`，在 [`WorkbenchController.kt:739`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L739)（`if (!supportsApprovalCards) return`）**直接丢弃该事件**，不渲染任何卡片或按钮；
  4. **死锁结果**：用户在手机上既看不到卡片也看不到命令提示，Agent 线程阻塞等待决策直到 300 秒超时。
- **整改方案建议**：
  在 `adapter.py:1073` 中增加能力感知检查：若 `not core.approval_cards_available`，立即返回 `SendResult(success=False)`，让 Hermes 宿主正常退化为控制台/聊天纯文本 `/approve` 提示逻辑；同时在 `core.py` 路由分派处增加能力门禁拦截。

---

## 四、多方共识核心缺陷详析 (Multi-Agent Consensus Issues)

### 缺陷 1：消息时间线回调签名丢失卡片标识导致上层 6 层解构与单条目退化包装 `[四方共识: R1, R2, R3, R4]`
- **目标文件**：
  - [`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt:68-71`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt#L68-L71)
  - [`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt:293-301`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt#L293-L301)
  - [`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/FloatingConversationPanel.kt:42-46`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/FloatingConversationPanel.kt#L42-L46)
- **根因分析**：
  `MessageTimeline` 接受多条目列表，但其入参签名仅为 `onDecide: (ApprovalChoice) -> Unit`，丢失了被点击卡片的标识。调用方被迫在外部通过 6 层属性解构 `last.last().approval?.request?.approvalId?.value` 手动包装闭包；`WorkbenchScreen` 更被迫在 LazyColumn 每一行都单独包一层 `MessageTimeline(entries = listOf(entry))`，将容器组件退化为单条目包装器。
- **整改方案**：
  将回调签名扩展为主签名 `onDecide: (approvalId: String, choice: ApprovalChoice) -> Unit`（内部卡片点击时透传自身 ID）；上层调用方直接使用方法引用 `controller::decideApproval`，彻底消除单条目列表包装与闭包开销。

### 缺陷 2：跨模块跨层审批档位枚举双向转换存在重复 Switch/When `[多方共识: R1, R4]`
- **目标文件**：
  - [`GatewayConversationRepository.kt:230-245, 267-278`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayConversationRepository.kt#L230-L245)
  - [`WorkbenchController.kt:891-905`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L891-L905)
  - [`ApprovalCardState.kt:59-68`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/ApprovalCardState.kt#L59-L68)
- **根因分析**：
  在数据层、状态层与领域模型层之间，针对四个审批档位（ONCE, SESSION, ALWAYS, DENY）的手工 `when` 映射逻辑被无隔离地复制了 4 次。后续一旦协议扩充档位，极易遗漏某处导致散弹式修改（Shotgun Surgery）。
- **整改方案**：
  复用各枚举已有的 `wireValue` 与工厂函数 `of`，或在领域模型中提供成对的扩展属性（`ApprovalChoice.toOutcome()` 与 `ApprovalOutcome.toChoice()`），消除重复过程式映射。

### 缺陷 3：Hermes 平台适配器重复实现 Host Approval 解析器 `[多方共识: R1, R2]`
- **目标文件**：
  - [`integrations/hermes/open_android_intelligence_gateway/adapter.py:998-1020`](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/adapter.py#L998-L1020) 对比 [`core.py:81-98`](file:///mnt/数据/项目/open-android-intelligence/integrations/hermes/open_android_intelligence_gateway/core.py#L81-L98)
- **根因分析**：
  `core.py` 已定义顶层通用解析函数 `resolve_host_approval`，完整封装了从 `tools.approval` 动态导入、执行调用、异常拦截与日志记录；而 `adapter.py` 内部又手写了完全相同的内嵌闭包 `_resolve`，属同模块重复造轮子。
- **整改方案**：
  在 `adapter.py` 中直接引入并复用 `core.py` 的 `resolve_host_approval`。

### 缺陷 4：高频 SSE 事件流热路径与 409 响应体重复反序列化 JSON `[多方共识: R2, R3]`
- **目标文件**：
  - [`GatewayConversationRepository.kt:291-295`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayConversationRepository.kt#L291-L295)
  - [`ApprovalClient.kt:117-158, 172-197`](file:///mnt/数据/项目/open-android-intelligence/apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/approvals/ApprovalClient.kt#L117-L158)
- **根因分析**：
  在 SSE 接收循环的热路径上，收到的每一个事件（包含高频 message delta）均先后独立调用 `decoder.generationIdOf(event)` 与 `decoder.decode(event)`，每帧流式文本块均承受了双重 JSON 反序列化开销；在 `ApprovalClient` 409 错误处理中同样重复解析了 2~3 次。
- **整改方案**：
  重构解码器单次解析出 AST 并在解码事件的同时提取 `generationId`；在 `ApprovalClient` 中先解析一次 `val body = bodyOf(response)` 传给各辅助函数。

### 缺陷 5：OptionGroup 分块计算存在多余的三元表达式 `[多方共识: R1, R2, R4]`
- **目标文件**：[`ApprovalCard.kt:292`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt#L292)
- **根因分析**：
  `val rows = options.chunked(if (options.size <= MAX_BUTTONS_PER_ROW) options.size else MAX_BUTTONS_PER_ROW)` 中，Kotlin 标准库 `chunked` 内部天然支持剩余元素打包为单块返回，外层的三元表达式完全多余。
- **整改方案**：
  简化为 `val rows = options.chunked(MAX_BUTTONS_PER_ROW)`。

### 缺陷 6：代码中大量滥用超长全限定类名（FQCN）`[多方共识: R2, R4]`
- **目标文件**：[`GatewayConversationRepository.kt:220-285`](file:///mnt/数据/项目/open-android-intelligence/apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayConversationRepository.kt#L220-L285)
- **根因分析**：
  在 60 余行内充斥了数十处 `com.openandroidintelligence.conversation.model.*` 和 `com.openandroidintelligence.gateway.approvals.*` 全限定名，代码视觉噪点严重。
- **整改方案**：
  在文件顶部统一进行局部 import，移除冗长前缀。

---

## 五、按目标文件聚类的修复矩阵 (File-Level Clustered Fix Matrix)

所有待修复的 18 项 SAFE 与 CAREFUL 问题已完成物理文件隔离归类，可由多个修复 Agent 无冲突并行执行：

| 分组 ID | 目标文件绝对路径 | 包含问题项 | 风险级别 | 验证手段 |
|---|---|---|---|---|
| **Group 1** | `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ApprovalCard.kt` | ① 简化 chunked 冗余三元判断<br>② 替换魔数 `2.dp` 为 `Dimensions.StrokeStitch`<br>③ 收敛 `AnimatedContent` 作用范围，恢复按钮文字原地转圈物理连续性<br>④ 下沉 `now` 局部状态，消除整卡每秒重组 | CAREFUL / SAFE | `:conversation-ui:testDebugUnitTest`（`ApprovalCardTest`） |
| **Group 2** | `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/ApprovalCardState.kt` | ① 清理死代码扩展属性 `isSubmitting` 与 `isAnswerable`<br>② 消除 `settledChoice` 重复 7 分支 when 映射 | SAFE | `:conversation-ui:testDebugUnitTest`（`ApprovalStateMachineTest`） |
| **Group 3** | `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/model/Approval.kt` | ① 清理无调用的死函数 `ApprovalRequest.optionFor`<br>② 引入 `ApprovalSeverity` 枚举，收敛基本类型偏执 | SAFE | `:conversation-domain:testDebugUnitTest` |
| **Group 4** | `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt` | ① 将 `approvalCardsSupported` 默认值对齐为 `false`，消除打字/chunk 高频 `.copy()`<br>② 显式 rethrow `CancellationException` 维护协程生命周期<br>③ 消除 `outcomeOfChoice` 重复 when<br>④ 消除 `ApprovalId` 拆装箱 | CAREFUL / SAFE | `:conversation-ui:testDebugUnitTest`（`WorkbenchControllerTest`） |
| **Group 5** | `apps/android/conversation-data/src/main/kotlin/com/openandroidintelligence/conversation/data/GatewayConversationRepository.kt` | ① 显式 rethrow `CancellationException`<br>② 事件流热路径 JSON 反序列化去重<br>③ 集中 import 清理全限定类名<br>④ 枚举互转消除重复 when | CAREFUL / SAFE | `:conversation-data:testDebugUnitTest`（`GatewayEventDecoderTest`） |
| **Group 6** | `apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/approvals/ApprovalClient.kt` | ① 409 响应体解析去重<br>② 清理与状态机语义冲突的死属性 `isSettled` | CAREFUL | `:gateway-client:testDebugUnitTest`（`ApprovalClientTest`） |
| **Group 7** | `apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/MessageTimeline.kt` | 回调签名透传 `approvalId`，消除 `WorkbenchScreen` 与 `FloatingConversationPanel` 的 6 层深层解构与单条目退化包装 | CAREFUL | `:conversation-ui:testDebugUnitTest` |
| **Group 8** | `apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/ports/ConversationPorts.kt` | 清理数据类字段冗余的全限定类名前缀噪点 | SAFE | `:conversation-domain:testDebugUnitTest` |
| **Group 9** | `integrations/hermes/open_android_intelligence_gateway/adapter.py` | 平台适配器直接复用 `core.py` 顶层 `resolve_host_approval`，消除重复闭包 | CAREFUL | `pytest integrations/hermes/tests/test_command_approval_cards.py` |
| **Group 10** | `integrations/hermes/open_android_intelligence_gateway/core.py` | 协商特性读取防卫化（使用 `.get()` 防未捕获 500 崩溃），对齐日志记录粒度 | CAREFUL | `pytest integrations/hermes/tests/test_conversation_new_command.py` |

---

## 六、阶段 4 修复执行与验证规划 (Phase 4 Execution Plan)

1. **门禁审批**：就第二节 3 项 `RISKY` 问题的整改方案征求用户与架构负责人确认；
2. **并行修复**：针对 Group 1 ~ Group 10 按文件派发修复子任务，严格遵循每个文件单独编辑与自测原则；
3. **闭环测试验证**：
   - 契约网关测试：`npm --prefix gateway-contract test`（164 passed）
   - OpenClaw 适配器：`npm --prefix integrations/openclaw test`（55 passed）
   - Hermes 网关测试：`/home/djbd/.venvs/oai-gateway/bin/pytest integrations/hermes/tests/`（196 passed）
   - Android 单元测试：最小化 Gradle 单测覆盖改动模块。

