# 原生消息系统回归：只读基线与验证边界

日期：2026-10-29。此报告是修复前的只读调查记录，不代表七项现象已经运行时复现。

## 固定基线与架构

执行时重新拉取了两条远端分支，得到：

- `dev`：`4a5fd397c5612fe8b3024c9b3b2754e217c68ccd`。
- `main`：`d88fffed134f57c5cf6ca4733e65b61f6e13120d`。

开发基于 `dev` 的独立 `feature/message-lifecycle-recovery` worktree；PR 目标为 `dev`。Android 继续只连接统一 OAI Core，Hermes 专有逻辑保留在 Adapter。所有运行时测试应使用临时数据库、合成消息与显式隔离的 Hermes Home。

`main` 没有新的 `CoreAgentConversationRepository`、`AgentSessionClient` 和独立 Hermes Session Adapter；这些入口由统一 Core 重构新增。因此不能把旧 Gateway SSE 的游标、恢复和健康状态实现，等同于原生 Agent POST SSE 已具备相同保证。

## 旧修复与迁移边界

| 已有保证 | `main` 的实现边界 | `dev` 原生路径的差别 |
| --- | --- | --- |
| 事件连接真实健康状态 | `GatewayConversationRepository` 从真实 `EventStreamStatusSink` 接收状态；Gateway SSE 客户端跟踪连接、完整心跳和失败 | `CoreAgentConversationRepository` 在基线初始化 `LIVE`，`observeEvents` 转发内存 SharedFlow；其健康状态不能证明原生 POST SSE 健康 |
| 后台恢复与单订阅 | `WorkbenchController.onForegrounded` 替换并等待旧订阅，随后读取权威历史；账号事件流无需每次切换线程重建 | 相同控制器调用仍存在，但重新订阅内存流不会重新建立已中断的原生 POST SSE，也不能重放无订阅期间的帧 |
| 消费后推进可靠游标 | Gateway 事件传输与 CursorStore 分离持久化确认，断线按游标补偿 | 新原生协议提供请求对应的 SSE 与历史接口，尚不能直接复用 Gateway 事件游标；应明确增加重放或安全的快照及指定请求状态补偿 |
| 生成终态不可逆、旧请求不能关闭新轮次 | Gateway 仓储按会话与 generation 记录终态和revision；旧查询通过revision屏障拒绝回写 | 原生接口 `current_generation` 只提供当前活动任务；任务消失本身不是完成、失败或取消的证据 |
| 真实身份合并 | 旧协议提供消息、generation、client、事件身份及修订号 | 原生路径虽已补真实ID透传，仍有本地临时受理、`stream_<clientMessageId>` 占位与后续真实ID升级；必须验证完整映射，而非靠正文或前缀去重 |

旧修复证据见 [2026-10-15 对话修复](2026-10-15-agent-conversation-bugfixes.zh-CN.md)、[2026-10-18 会话恢复](2026-10-18-gateway-session-recovery.zh-CN.md)。原生契约见 [消息身份与附件交付](../architecture/2026-10-27-native-session-message-integrity.zh-CN.md) 及 [ADR 0054](../adr/0054-unify-device-authority-and-native-sessions-in-one-oai-core.md)。

## 七项问题的验证矩阵

下表中的“源码证据”只确认实现事实；除非后续修复记录提供实际失败命令及结果，均不能称作已复现根因。

| 问题 | 基线源码证据与候选故障路径 | 必须验证的触发条件与断言 | 受影响组件、风险与修复方向 |
| --- | --- | --- | --- |
| BUG-01 重复回复/状态 | Repository 本地受理与真实身份升级；Workbench 的临时消息和快照合并；Adapter 的 send/edit/complete 回调身份 | 单轮多条真实回复、同文不同ID、临时ID升级、SSE与历史交叠，分别断言远端持久记录和端侧气泡数量 | Adapter、Core契约、Android仓储及控制器；新路径身份契约回归候选；必须依据显式原生ID/关联字段合并，禁止正文去重 |
| BUG-02 气泡延迟 | `sendDraft` 冻结提交，但本地发送记录与气泡创建处于后续异步流程；持久化与会话确认会先挂起 | 挂起持久化、附件验证及HTTP：点击同一状态更新立即清空已提交草稿并显示稳定本地气泡；持久化失败时HTTP请求数为0 | Workbench发送状态；本地记录顺序与UI时序需分别保证，不能为提速绕过写前记录 |
| BUG-03 接收停滞 | 原生内存事件流无重放；健康初值为LIVE；观察者绑定调用时选中Agent；重新订阅不一定重启原生读取 | SSE断开、消费者延迟、无UI订阅、前后台/Agent切换后自动恢复；区分网络读取完成和事件状态应用完成 | Android网络、仓储、控制器；与BUG-04共享恢复缺口。容量小仅能说明背压可能性，不能单独证明丢帧或死锁 |
| BUG-04 长期未知 | AgentSessionClient 将无done及IOException映射未知；`current_generation` 只返回活动任务，旧终态不能由该入口查询 | 首token前、最后done前断线，按原client/generation查询持久终态；当前任务为空但没有权威结果时必须保留未知 | Adapter持久工作流、Core查询、Android恢复；需要指定请求/生成的受授权终态查询，不能把空活动任务解释为成功 |
| BUG-05 断网影响宿主 | Adapter已用后台task分派，SSE生成器、Core迭代器、HTTP取消和回调的实际传播仍需测试；媒体完成finally仍含未定义的 `media_HistoryMedia._retire_path(path)` | 在受理后关闭手机SSE、Core到Adapter连接，宿主仍只执行一次并留下可查终态；附件完成清理不抛NameError | Adapter/Core；后台task存在不是生命周期解耦的充分证明。“Recovered reply”与System note来源必须靠宿主事实定位，不得按正文删消息 |
| BUG-06 工具错位 | `TimelineDisplay` 仍有 `looksLikeHermesActivityLog` / `looksLikeHermesProcessEntry` / `mayContainHermesMarkup` 正文分类 | 普通回答含Reading/Running/Tool call仍为普通消息；结构化步骤按generation/step/event归属，新轮不改变历史归属 | Adapter事件契约与Android展示；只在可靠结构化元数据存在时归类，保留审批、手动命令及其他Agent语义 |
| BUG-07 卡顿/丢事件 | Adapter队列满时无差别 `get_nowait()` 丢旧帧；Android已存在合并文本快照与约33ms发布的旧修复 | 填满队列，审批/身份/终态不可丢；多真实消息不可被conflation合并；记录逐阶段首增量及完成延迟 | 先保证事件可靠性，再测传输与渲染耗时。没有阶段时延测量不能声称性能已改善 |

主要共享根因候选为：原生入口的请求、执行、事件和持久历史身份未形成完整恢复契约；HTTP观察连接与执行事实的边界不完整；状态消费与快照补偿能力未完全迁移。是否成立应由各个最小复现判定。

## 已有报告的证据校正

[2026-10-27 前置审查](2026-10-27-oai-core-conversation-regression-review.zh-CN.md) 含未清理的合并冲突标记，并把若干静态嫌疑写作“已确认”；不能直接作为当前根因结论。应以 [同日修复记录](2026-10-27-oai-core-conversation-regression-fix.zh-CN.md) 对证据边界的更正，以及当前基线实际运行的测试为准。

[2026-10-28 身份冲突修复](2026-10-28-message-identity-conflict-fix.zh-CN.md) 已通过真实崩溃堆栈修复 `MESSAGE_ID_CONFLICT`；它证明异常不再退出进程，不证明双身份和重复消息已经完整消除。其记录中App全套存在24个环境缺件失败，也不能引用为全套通过。

## 验证策略与环境

仓库 `AGENTS.md` 要求开发执行 `npm run test:fast`、发起PR前执行 `npm run test:pr`。受影响模块应先执行精准回归，合并阶段运行上述门禁。

`tools/smart-test.py` 的当前实现仍有以下范围限制，必须在最终报告中据实注明：

- fast与pr目前采用同一执行分支；等级名称不能代替实际测试范围。
- `oai-core/tests` 批量测试明确排除了 `test_hermes.py`、`test_hermes_native.py`、`test_hermes_interactions.py`、`test_hermes_approval_prompts.py` 和 `test_interaction_pipeline.py`；Hermes变更必须另行执行相关文件。
- Adapter目录只触发 `affects_hermes`，该标志尚无专属执行分支；不能仅因智能测试退出0就声称Adapter已验证。
- 原生SDK用例未设置显式 `HERMES_SDK_ROOT` 时会跳过；需要固定SDK及隔离Home强制运行，跳过不计作通过。

最小验证集合应覆盖：Core `test_sessions.py` 及新增断线恢复测试；Hermes上述专属测试与指定SDK真实SessionStore/SessionDB契约；Android `conversation-domain`、`conversation-ui`、`gateway-client` 及 `app` 的直接受影响测试，再运行fast/pr。完整App测试另需参考插件的真实WASM编译产物及历史HTTP fixture；缺件应补齐或明确记为未验证。

本次环境最初只有Node 24.19.0与OpenJDK 21，未预装Android SDK/Gradle。为运行真实Android回归，工具链安装到仓库外共享工作目录，使用Gradle 8.12、SDK platforms 35/36、build-tools 35.0.0/36.0.0；保持仓库wrapper和构建配置不变，并沿用环境代理。安装成功不等于测试通过，各项结果由后续修复/回归报告记录。

尚未执行用户手机、真实模型、真实生产网络、厂商后台策略或宿主真实进程死亡验收；自动化合成宿主和回环HTTP测试不能替代这些场景。
