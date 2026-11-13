# 引导消息与执行过程聚合回归：根因证据与 TDD 边界

日期：2026-11-03（Asia/Shanghai）。目标分支：`dev`。

排查基线：`ed8301b47bbe2226d996d2e66f8b140c739b2a9a`，已通过 `git ls-remote origin refs/heads/dev` 核验。完整门禁基于 `e7d3bcbbc738faefa70933611f26f6ae31060fae`（dev PR #75）；提交前又快进同步 `5e2a616b39838053e4268775e966560ab7499a37`（dev PR #76）。两批合入文件均与本次改动无重叠；PR #76 的共享网络和附件 Core 路径另做定向复核。工作分支：`feature/android-steering-execution-20261103`；独立工作目录：`.worktrees/android-steering-execution-20261103`。

本报告第 1–9 节保留修复前的源码证据与验证记录；第 10 节起记录用户确认测试边界后的实际修复和验证。尚未在截图对应的手机、部署服务或真实模型会话中复现。下列源码路径可确定存在错误分支，但不代表截图对应部署必然与本基线一致。

用户本次要求作为新的行为依据：引导始终保留用户身份；同一真实 generation 中的引导不能结束任务；执行过程可按事件顺序拆为多个展示段；工具详情按真实身份与修订合并。既有文案在本次要求之外保持原样。

## 1. 引导身份和重复消息的直接原因

### 1.1 本地合并主动改写用户身份

[LocalSendAtom.kt](../../apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/model/LocalSendAtom.kt)：

- 第 70–84 行：STEER 的远端匹配要求 `sender == "system"`，实际宿主输入却为 `user`。
- 第 108–124 行：本地投影为引导消息拼接 `↳ 已引导：`，改写 `sender="system"`、`state="STEERED"`；此逻辑对 PREPARED、FAILED 等提交状态也没有先确认成功。
- 第 75–84 行：已绑定非占位服务端 ID 时，不再用 `clientMessageId` 匹配不同 ID。

[WorkbenchController.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt) 第 5213–5219 行调用这份合并结果，第 5183–5185 行直接根据 sender 决定是否为用户气泡。因此左侧“已引导”来自 Android 自己的消息投影，不能归因于截图中的用户实际发出了系统消息。

[WorkbenchDuplicateAndDeliveryTest.kt](../../apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/state/WorkbenchDuplicateAndDeliveryTest.kt) 第 223–239 行还把“引导后没有 user 气泡、存在已引导文本”写成测试预期。这是与本次要求冲突的旧行为断言，不能保留后再宣称回归通过。

### 1.2 将本地合成 ID 当成权威消息 ID

[CoreAgentConversationRepository.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt) 第 1513–1543 行：引导接口返回同一 generation 的 accepted 后，仓储自行构造 `steer_${clientMessageId}` 作为 `MessageAcceptance.messageId`。

[ConversationPorts.kt](../../apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/ports/ConversationPorts.kt) 第 122–128 行：`MessageAcceptance.authoritative` 默认 `true`。上述引导回执没有取得宿主 user 消息 ID，却沿用了这个默认值。

[WorkbenchController.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt) 第 3295–3317、3665–3674 行：首发和恢复重试均将该 ID 写入持久发送记录，再以 COMPLETED 表示引导交付结束。

服务端真正保存的引导输入由 [host_history.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/host_history.py) 第 303–365 行维护：原文保存为 `sender="user"`，身份是独立的 `msg_native_input_...`，并保留 `clientMessageId` 和已有 `generationId`。这份原稿不创建普通 dispatch。

于是远端 user 行到达时会同时遇到角色不匹配与非占位 ID 不可升级两道阻断，本地“已引导”行和远端 user 行可能并存。仅修改左右对齐或只改 sender 不足以闭合身份合并。

## 2. 工具详情在引导后的丢失路径

### 2.1 实时观察将下一条 user 行作为截止点

[tool_events.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/tool_events.py) 第 94–134 行：

1. 核对账号、会话、generation、原始 dispatch、宿主 session 与真实 `tool_call_id`。
2. 找到原始 `clientMessageId` 的唯一 user 锚点。
3. 第 125–128 行把下一条任意 user 行作为 `end`，只接收 `anchor < source.id < end` 的调用。

[已验证的输入捕获代码 runtime.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/runtime.py) 第 248–287 行给宿主 user 行写入 `oaiGatewayNativeInputs` 元数据与 `platform_message_id`。引导本身正是一条新 user 行。

因此，在仍属于原 generation 的引导行后出现的新工具源行，会被上述原始锚点窗口排除。修改时必须识别可验证的同 generation 引导输入，继续保留跨账号、跨会话、不同 generation 和未绑定输入的拒绝规则。

### 2.2 历史回放也丢失 generation，最终生成摘要正文

[tool_history.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/tool_history.py) 第 17–23 行：遇到每条 user 行都重新解析 generation，并清空待匹配的 tool calls。

[host_history.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/host_history.py) 第 550–557 行传入的 generation 解析器只查询普通 `messages` 表及其 workflow；引导原稿与 generation 关联保存在原生输入元数据中，未在这里参与解析。

同文件第 613–615 行：assistant 工具源行不在结构化投影中、且正文为空时，退回 `_tool_activity(row)`。第 60–71 行该函数只从原始 `tool_calls` 取名称，生成 `调用工具：xxx`。

这条路径能解释：参数和结果本来存在于宿主记录中，但 Android 最终收到只有名称的普通消息。应优先修正实时与历史共享的真实归属，而不是按这段文字反向识别工具、隐藏摘要或伪造详情。

### 2.3 现有字段链路与限制

| 数据 | 已确认来源与传递 | 本次处理原则 |
| --- | --- | --- |
| 调用/步骤 ID | `tool_calls[].id` / hook 的 `tool_call_id` → `stepId` | 按真实 ID 和步骤修订合并，不按工具名去重 |
| 工具名 | `function.name` / `tool_name` → `toolName` | 原样展示 |
| 输入及命令 | `function.arguments` / hook 的 `args` → `arguments` | 保留已有参数；不从摘要猜测命令 |
| 返回结果 | tool 行 `content` / hook 的 `result` → `output` | 保留文本或序列化的结构化结果；仅在宿主实际提供时展示 stdout/stderr 等内容 |
| 状态 | hook 的状态、tool 行 `is_error` → running/completed/failed/unknown | 缺少结果不等于成功；迟到旧修订不能重开完成步骤 |
| 修订 | 独立 `executionRevision` 与步骤 `revision` | 不混用正文修订与生命周期修订 |
| 时间与来源位置 | 宿主行已有 ID、时间；观察快照内部保存 `sourceMessageId`、`sourceIndex` | 公共步骤当前不含每步来源锚点/时间；先用测试确定严格跨引导排序需要哪些最小字段，再决定是否扩展契约 |

字段依据：`tool_events.py` 第 31–35、148–164 行；`tool_history.py` 第 28–56 行。

Android 现有解码和映射保留上述公共参数/结果字段：[AgentSessionClient.kt](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/agents/AgentSessionClient.kt) 第 471–477 行、[CoreAgentConversationRepository.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt) 第 1237–1249 行、[TimelineDisplay.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/TimelineDisplay.kt) 第 234–248 行。因此不能先认定所有详情都是 Android 解码丢弃。

## 3. 执行过程容器与实际顺序的冲突

[TimelineDisplay.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/TimelineDisplay.kt)：

- 第 171–176、191–200 行：旧 Markdown 分组按连续条目收集；用户、审批等行会 flush 展示段。
- 第 264–290 行：`shouldFold` 依赖最终正文、粘滞最终状态或段内停止流式。执行中没有最终正文时退回每条 Single，容器不出现。
- 第 166–189 行：结构化步骤预先按整个 generation 分组，在扫描到该 generation 的第一条消息时一次输出所有步骤。引导后发生的步骤也会被搬到引导前的同一张卡片。
- 第 477–478 行：进入执行段只检查 `!isUser`，没有检查真实 assistant sender，普通 system 引导回执行也可进入助手分析路径。

[WorkbenchController.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt) 第 5254–5288 行又把该 generation 的执行事实整体插在原问题后。当前事实模型没有为跨引导的每个步骤保留独立展示位置。

这两种路径必须一起考虑：不能只把 `shouldFold` 改成 true，也不能继续将同 generation 的所有未来步骤放到第一个位置。任务身份和展示段身份应分开：引导保留为其实际位置的用户条目，同一任务前后可有多个稳定展示段，各段共享任务的真实 generation 与生命周期。

## 4. 折叠偏好、列表身份与终态

[ExecutionViews.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/ExecutionViews.kt) 第 197–201 行以 `rememberSaveable(run.segmentKey)` 保存展开状态；默认仅在无最终回答且含附件时展开。不能通过无条件重置 expanded 实现“任务完成后折叠”，否则会覆盖用户选择。

[WorkbenchScreen.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt) 第 490 行已经使用展示项的 key 和 contentType。问题不是缺少这两个 Compose 参数，而是展示模型在流式期间会改变条目的分组形态和身份。实际滚动跳变是否发生仍需组件交互回归验证。

[StructuredExecutionDisplayTest.kt](../../apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/workbench/StructuredExecutionDisplayTest.kt) 第 80–95 行甚至要求把审批前后两个步骤放进审批前的一张卡片；本次严格事件顺序要求下，需要审视该预期，保留审批本身的独立位置。

引导 accepted 的生产代码目前只在该分支结束引导发送记录，没有显式停止原 generation；[runtime.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/runtime.py) 第 512–540 行返回的也是原 generation。是否存在其他终态或旧回执误关活动任务，需在控制器与仓储的公开状态边界做回归，不能仅由上述分支推断整个生命周期安全。

## 5. 宿主回显与部署边界

CI 固定 Hermes SDK 为 `75b083e9399115b12513385252811f09c3503c90`，见 [.github/workflows/ci.yml](../../.github/workflows/ci.yml) 第 66–71 行；本机只读核查的 Hermes checkout 与该 SHA 一致。

该版本上游 `gateway/run_busy.py` 第 690–743 行的 `_compose_busy_ack_message` 明确生成 `Steered into current run`、`Redirected current run` 等忙碌处理回执。因此截图中英文重定向状态有宿主源码来源，不能把它当作第二条用户原文，也不能根据文本相同就删除历史。

本地中文 `↳ 已引导：` 则已定位到 Android 的 LocalSendAtom 投影。Core/Adapter 的 steer 成功分支只返回 accepted，但宿主仍可能通过独立平台发送路径发送状态回执。后续必须沿可信输入关联区分 authored user 原稿与状态事件；没有明确事件分类时不得按文字过滤宿主合法消息。

未读取用户真实会话数据库、生产日志或手机状态，也未修改运行中的服务。截图对应部署版本、真实 eventId/requestId 与手机交互仍待验证。

## 6. 已确认的公开测试边界

用户提供的 `tdd/SKILL.md` 第 22 行明确要求：写任何测试前先列出并由用户确认测试边界。用户随后明确回复“确认”，已授权下列 A–D 边界；不直接测试私有 `renderTimeline()`、`flushSegment()` 或内部数据库结果。

| 边界 | 公开输入与观察结果 | 首条 red 用例 |
| --- | --- | --- |
| A. 消息身份与提交状态 | `mergeLocalSendAtoms()`；`WorkbenchController.sendDraft()`、会话打开/切换、仓储事件 → `state` 和公开持久恢复结果 | STEER accepted 后原文仍为唯一 user 气泡；真实历史先后到达均可合并 |
| B. 原生仓储与传输 | `CoreAgentConversationRepository.steerMessage()`、公开消息/事件观察；AgentSessionClient 历史和事件解码 | accepted 只有 generation 时不将本地合成 ID 当成宿主消息 ID；失败/未知保持原任务 |
| C. 展示与用户交互 | `TimelineDisplayModel.build()`；`ExecutionProcessPanel` / 工作台公开 Compose 组件 | 工具步骤一 → 引导 → 步骤二：顺序不变、同 generation、多段容器稳定，执行中也有容器 |
| D. 宿主适配与回放 | `HermesSessionAdapter.steer()`、`HermesHistoryPort.read()`、公开 pre/post hook 及事件流；固定真实 SDK 合成会话 | 原 generation 中插入真实标记的引导 user 行后，后续工具仍有同一归属、参数和结果；实时与重载一致 |

## 7. 用户要求的九类回归映射

| 场景 | 主要边界 | 必须观察的结果 |
| --- | --- | --- |
| 普通提问、多工具、最终回答 | C、D | 原有回答和工具详情保持；明确终态关闭流式 |
| 工具执行中一次引导 | A、B、C、D | 用户气泡、原 generation、引导前后正确位置及完整工具结果 |
| 同任务多次引导 | A、C、D | 每条输入各自唯一；每个展示段稳定且共享任务状态 |
| 回执与历史乱序 | A、B、D | 历史先/回执先、重复回执均幂等；不改 sender |
| 引导后断线、重连、切会话 | A、B、C、D | 不重发引导；不关闭仍运行任务；恢复与实时一致 |
| 详情完整/缺失/增量/失败 | B、C、D | 已有信息可展开；缺失信息不伪造；同 step 只更新一次卡片 |
| 未结束与已结束历史任务 | A、B、C、D | 容器与状态正确；默认折叠与手动偏好分别验证 |
| 相邻不同 generation | C、D | 明确隔离；未知旧历史不按时间或文字误归组 |
| 引导失败、未知、重复事件 | A、B、C、D | 原始用户身份和草稿/重试能力保持；原任务生命周期不变 |

另将已存在的普通回答、审批、附件、流式光标、列表 key、滚动锚点和旧历史行为列为受影响回归，避免修复引导时降级已有能力。

## 8. 最小实施顺序

确认测试边界后逐个执行“一条失败测试 → 最小实现 → 测试通过”，不先横向堆完所有测试：

1. 从原生历史边界锁定引导后工具投影的 red；共享明确的原稿/任务关联，保持多账号、会话和不同 generation 的拒绝规则；修复历史与实时观察的同一归属问题。
2. 从本地合并/控制器边界锁定角色和身份冲突的 red；STEER 保留 user 原文，区分投递 accepted 与权威消息 ID，首发/恢复路径保持一致。
3. 从展示边界锁定跨引导顺序与执行中容器的 red；按真实任务和步骤身份建立稳定的连续展示段，不重排历史、不隐藏工具消息。
4. 从组件边界锁定手动展开、终态折叠与增量更新的 red；保留已有 UI 文案和用户偏好。
5. 逐步补足九类场景，再运行 `npm run test:fast` 与 `npm run test:pr`；完成后提交中文 commit、面向 dev 创建 PR。CI 触发后记录链接，后台处理结果，不前台等待。

只有确认上游来源位置/时间在公共协议中丢失、现有契约无法表达严格顺序时，才增加所需的最小可选字段，并同时修改适配器、Core 校验、Android 解码、镜像保存和回归断言。

## 9. 本轮验证记录

- 远端 dev SHA 已通过 `git ls-remote` 核验；本地原工作区无未提交改动。
- 已创建独立 worktree；本轮只新增本报告。
- `npm run test:fast`：通过。依赖锁校验 8 项通过，`:noVpnSurfaceCheck` 通过；仅覆盖本轮文档变动门禁，不等于本缺陷回归通过。首次因默认 Gradle 缓存目录只读中断，授权复用既有缓存后执行成功，未更换临时缓存或运行全量编译。
- `git diff --check` 与未跟踪文档的 `git diff --no-index --check`：通过；21 个本地文件引用检查通过。
- 生产代码修复、TDD red/green、PR 准入、CI 与真机验收：尚未执行。


## 10. 已实施的行为修复

用户确认 A–D 边界后，在原独立 worktree 按实际失败、最小实现、通过验证逐切片推进：

- 用户输入统一投影为 user 原文；旧 `steer_${clientMessageId}` 仅在明确 STEER 发送记录中兼容升级，不能解除真实消息 ID 的冲突保护。
- 原生仓储的引导 accepted 只返回客户端占位与真实 generation，`authoritative=false`；控制器记录 ACCEPTED/DELIVERED 及持久 generation 关联，恢复后不重发已经交付的引导。
- 已知引导输入的 MessageStatus 终态只结束该输入投递，不结束原 generation、取消等待或流式正文。
- 适配器通过原生输入的可信持久绑定识别同 generation 引导，实时 observer 与历史投影共享归属规则；未绑定、跨会话、压缩摘要、重复输入或错代边界继续拒绝归属。
- 增加可选 `sourceMessageId`、`occurredAt`。来源 ID 与真实公共历史消息一致；时间来自原调用，完成结果不重写其位置。Core 保持透传，Android 解码、仓储映射与加密镜像完整保留。
- 实时步骤按来源位置形成展示事实，已存在的历史工具按真实 stepId 和独立修订更新，不重复建立卡片。任务身份与连续展示段身份分开，保留引导和审批的实际位置。
- 专用 busy ack 用固定 SDK 方法指纹、任务隔离的 ContextVar 和明确输入范围分类，只作为投递状态。普通 slash 回复、同文模型回复、错范围和既有历史继续保留。没有按文字删除任何消息。

## 11. 实际 TDD 证据（持续追加）

所有原始日志位于当前 worktree 忽略目录 `.worktrees/steering-validation/`，未写入临时系统目录，未提交用户原文、服务日志或凭据。

| 公开边界场景 | 修复前实际结果 | 修复后已有结果 |
| --- | --- | --- |
| 本地引导与真实 user 合并 | 预期 1 行，实际 2 行 | 对应 domain 用例通过 |
| 工具 → 引导 → 工具 | 预期 3 个顺序展示项，实际 2 个 | 对应展示用例通过 |
| accepted 回执的消息权威性 | 本地合成消息 ID 被标为 authoritative | 原生仓储用例通过，原任务保持 RUNNING |
| 实时与历史来源字段 | 解码为 null | 传输用例通过 |
| 加密镜像重开来源字段 | 来源 ID 与时间丢失 | 镜像重开用例通过 |
| 已有历史工具接收实时结果 | output 仍为空 | 控制器用例通过，来源行不变 |
| 旧 STEER 合成 ID 持久升级 | MESSAGE_ID_CONFLICT | 镜像升级用例通过 |
| 引导 accepted 的持久恢复 | 本地仍为 PREPARED | 控制器 18 项通过，重启不再重发 |
| 同代两段共享任务状态 | 第一段被按单步完成判为结束 | 纯展示状态用例通过；控制器补齐任务状态投影 |
| 引导自己的完成回执 | 原 generation 被错误标为 COMPLETED | 投递类型 guard 后对应控制器回归通过 |
| 引导后历史工具归属 | 后续步骤缺失，前一步结果丢失 | Hermes 专项通过 |
| busy ack 写入 assistant 历史 | 原稿之外另有状态正文 | 专用状态分类用例通过，同文真实消息仍保留 |

Hermes 第一阶段专项 66 项通过；busy ack 修复后完整专项 189 项通过，1 条既有 aiohttp warning。固定 SDK origin 22 项全部通过。源 SDK 为 `75b083e9399115b12513385252811f09c3503c90`，未修改其 checkout。

Core 的公开原生 SSE 用例也已验证完整保留新来源字段和结构化 stdout/stderr/返回值正文；没有新增独立 stdout/stderr 流或伪造时间。

## 12. 独立审查与新增回归

- 审查发现可选来源字段在较新快照中缺失时可能覆盖已有来源。公开控制器测试实际得到 `execution-fact:g:source` 变成 `execution-fact:g`；已按同一步骤首次明确来源与时间保留处理，不用结果到达时间移动调用。
- 新增失败任务切换用例实际得到 FAILED 任务被展示成 COMPLETED。原终态集合改为按任务保留真实终态的有界映射，旧任务不随新任务重新运行。
- 原始审查曾怀疑引导 client ID 能查询原 generation 回执；经核对，Core 对 specific 查询校验 client ID，适配器对缺普通 ledger 的引导返回 GENERATION_NOT_FOUND，仓储继续其他恢复。此推测未确认为漏洞，不能当作生产事实。

- 第二轮独立审查确认原生压缩副本触发两处新增边界问题：合法父子 session 的引导副本被物理 session 等值检查拒绝；同逻辑输入的活动/归档副本被 Counter 当成重复注入。已通过真实 SessionDB 压缩 API 的 red/green 回归修复。原稿回放与工具归属分开判断；仅凭受范围约束的原生来源标记和已验证的压缩副本关系保留原位置，不能按正文建立身份或排除全部历史行。后续工具与 origin 专项为 79 项通过。

## 13. 待完成的最终检查

定向验证已通过消息领域 39 项、原生会话解码 34 项、受影响 UI 122 项；随后新增的离线历史终态与折叠验证 29 项通过。独立审查的必要修复已加入回归。

`npm run test:fast` 已执行：Core 693 通过、1 跳过，Hermes 193 通过；Android 消息领域 39、会话 UI 736、Gateway 客户端 372 项全通过。App 首轮 1462 项中 22 失败，其中 19 项为验证环境缺失，3 项为第 14 节基线断言矛盾。

补齐环境后执行 `npm run test:pr`，最终进程退出 1：

| 检查 | 最终实际结果 |
| --- | --- |
| 依赖锁与 no-VPN 静态门禁 | 通过 |
| Core、安装卸载与测试入口 | 693 通过、1 跳过、6 个 subtests 通过 |
| Hermes 全受影响原生会话与工具 | 193 通过、1 条既有 aiohttp warning |
| Android conversation-domain | 39 / 0 失败 |
| Android gateway-client | 372 / 0 失败 |
| Android conversation-ui | 736 / 0 失败，含本次引导、流式、折叠、身份与恢复回归 |
| Android App Full Debug | 1462 项：1459 通过、3 失败；仅第 14 节列出的 dev 基线问题 |
| 空白与本地文档引用检查 | 通过 |

XML 汇总保存在 `.worktrees/steering-validation/pr-gate-summary.json`；两级门禁完整日志、退出码与各切片红绿证据均保留在同一忽略目录。验证使用项目已有 SDK、Gradle/Robolectric 缓存、插件载荷和 CI 固定历史夹具，未更换冷缓存或删除历史。

完整门禁未全绿；不改动无关 UI 文案或断言掩盖失败。PR 将保持面向 dev 开放，并按确切提交 SHA 在后台检查 Android/Core Actions 和 review 评论。真机、模型和截图对应部署验收未执行，不能以单元或固定 SDK 测试代替。


## 14. dev 基线门禁阻塞

PR 准入重跑使用 CI 固定历史夹具及项目已有插件 WASM 产物。首轮 Fast 的 19 项缺文件失败来自验证环境，已补齐后重跑，不修改业务代码或禁用测试。

以下三项位于 PR #75 已合入的 `e7d3bcbb` 基线：本次以 `git show HEAD:<path>` 与工作区逐字节比较，相关三个生产文件和三个测试文件均未改变；静态追溯到 `b5d20454` 与 `941e859b`。这属于源码与既有断言之间的明确矛盾，未宣称做过单独的干净基线运行。

| 失败用例 | 基线矛盾及证据 |
| --- | --- |
| `DeviceCapabilityBlockTest.readyAndSyncingStatusesDoNotAddMisleadingBlockDetail` | `PluginManagementScreen.kt` 的 `capabilityBlockDetail()` 对 SYNCING 返回 null；测试第 197 行要求 SYNC_FAILED 的结果非空，而分类器把该状态映射为 SYNCING。 |
| `PluginManagementGrantUiTest.grantedEntryIsDisabledAndSaysSoInsteadOfAskingAgain` | `DeviceGrantRow` 的状态标签和禁用按钮都显示既有“已授权”；测试第 184 行按文字查唯一节点，实际匹配两个节点。 |
| `ScreenCapabilityPresentationTest.captureAuthorizationHintDependsOnLocalSessionFirst` | `ScreenCapabilitySection.kt` 对无障碍已连接优先显示无需重复授权；测试第 132 行在该状态下仍要求包含“尚未授权”。 |

本次不改变截图授权文案、授权策略或上述测试断言以消除这些无关失败。完整门禁不能记为全绿；引导回归的通过证据与这些基线阻塞分别报告。


## 15. 提交前同步 dev PR #76

完整两级门禁结束后，`dev` 合入附件上传 PR #76。本任务在保留已暂存修复的前提下快进到 `5e2a616b`，没有冲突或覆盖其他任务改动。

补跑 `test_core_transport.py`、`gateway_runtime/test_attachment_policy.py`、原生工具/历史及固定 SDK origin 回归：134 项通过。共享 Gateway 客户端完整回归：375 项通过。引导控制器 32 项、原生仓储与加密镜像 61 项均通过，Gradle 实际退出 0（`dev-pr76-integration.log`）。不因目标分支更新重复无关全量编译。
