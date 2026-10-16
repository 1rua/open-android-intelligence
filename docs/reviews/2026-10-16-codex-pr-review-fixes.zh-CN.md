# Codex PR review 复核与修复

复核范围：主仓 [PR #7](https://github.com/1rua/open-android-intelligence/pull/7)、OpenClaw [PR #1](https://github.com/1rua/openclaw-gateway-plugin/pull/1)、Hermes [PR #1](https://github.com/1rua/hermes-gateway-plugin/pull/1)。主仓基线 `8ca5483`，OpenClaw 基线 `9ad6456`。

初轮共 9 条具体意见，后续自动 review 继续补充宿主边界；累计复核 25 条（主仓 10 条、OpenClaw 10 条、Hermes 5 条），均确认存在可触发的问题。Hermes 另配套接受规范取消请求。主仓 PR #7 已合并，后续增量基于合并提交 `8bd2142` 另行提交；插件继续更新各自尚未合并的 PR。

| 评论 ID | 判断依据与修复 |
| --- | --- |
| 4289937800 | 创建已提交但回执丢失或等待取消后，下一次请求改变客户端身份，会创建重复空会话。在同一 Controller 和账号作用域内保留未确认的创建身份，直到获得成功回执；首条消息创建也使用这一入口。 |
| 4289937810 | 创建中的标记属于原线程，旧实现会阻塞切换后的其他线程。文字与语音共用按源线程判断的门禁，保留原线程的发送保护。 |
| 4289937817 | 已 POST 后的确定失败会把录音留在共享输入框，普通文字发送会带走该录音。结束独立语音提交时将其从共享草稿移除，保留用户原文字与附件。独立失败消息保留显式重试所需身份和唯一原件；未知结果同样必须保留恢复事实，不能直接删除。 |
| 4289937824 | MVP 锁文档仍 checkout/安装旧 OpenClaw 提交，必然与新 pin 校验冲突。生成器从 `openclaw-plugin-pin.json` 派生 checkout、安装和完整性字段，并增加真实锁文档与 pin 一致性回归。 |
| 4289937833 | 只有第一页被空 current-generation 回执结算，后续页的旧进度会使 UI 永久运行。每一页都验证当前生成，且在加载该页前捕获事实版本，查询期间的新事实不能被空回执清除。 |
| 4289937836 | 同轮迟到 delta 会把取消中的 UI 改回 RUNNING，旧判断据此丢弃成功停止回执。现在比较生成身份和新提交事实，允许同轮回执结算，阻止旧回执覆盖下一轮及其终态。 |
| 4289836412 | 固定 OpenClaw SDK 在 `commands.list` 缺少 agentId 时选择默认 Agent。现在传入当前 channel account 路由得到的 Agent。 |
| 4289836420 | 原生插件注册表在缺少 provider 时包含其他渠道专用命令。现在传入 Android Gateway provider，由宿主过滤可见性。 |
| 4289836422 | 宿主 session label 必须唯一，而 Gateway 标题可重复。创建宿主会话不再把显示标题当作 label；Gateway 仍保存显示标题，原生 session key 保持独立。 |
| 4290090065 | 原实现混用旧 session key/store 与重新路由的 Agent。宿主会话绑定已整组保存和恢复 Agent、account、key 与 store，配置变化不能改掉已有会话的归属。 |
| 4290090070 | 已发布取消 Schema 要求 `clientMessageId`，两个宿主却只接收 `requestId`。App 改为规范请求体，两宿主验证规范原文并保留严格的旧请求兼容；幂等键和原体摘要不变，共享 Schema 无需更改。 |
| 4290090075 | 仅阻止取消后的 SSE 输出不会阻止原生 transcript 再次出现在历史接口。已按原生会话与条目身份持久过滤被取消的助手输出，保留旧历史和后续正常回合。 |
| 4290090080 | runner 的 synthetic correlation 先占用去重取消事件，导致真正 HTTP 取消请求的 correlation 丢失。已在取消前保存请求关联身份，并统一 runner 与请求路径使用。 |
| 4292305156 | 无可信回合的异步通知会借用最近历史用户消息或 current generation。只有匹配 core、账号、会话和消息的可信 turn，或显式生成身份，才能把输出归入某轮。 |
| 4292305165 | 取消先落库时，宿主完成回调无法结算已发 delta，并跳过派发终态。取消路径发布规范失败状态，回调只用已发内容快照完成已追踪消息，不放行新增迟到正文或媒体。 |
| 4292356013 | 原生取消已请求但 runner 尚未退出时，generation 为 unknown，历史过滤尚未生效。持久取消请求的在途状态也必须使用边界，避免另一客户端读到迟到输出。 |
| 4292356022 | 取消前全量读取原生 transcript，会延迟或因损坏而阻止实际 abort。以原生文件字节边界记录取消前位置，实际取消不再依赖全量历史扫描。 |
| 4292356031 | 空 languageCode 使命令目录违反响应 Schema 的 minLength。缺省、空值及空白值回退 en，保留正常语言标签；两宿主行为一致。 |
| 4292421653 | 第 1 页看到运行中、第 2 页看到空生成时，只结算第 2 页仍会留下第 1 页旧进度。有效空生成回执必须在完整分页合并后重新核验事实版本，再结算此前累计的页。 |
| 4292421660 | 兼容的无 generationId 终态没有建立迟到屏障，无 ID delta 可重新打开结束的生成。按线程与已结束的消息身份设置屏障，仅在明确的新提交或用户轮开始后重开。 |
| 4292421665 | orderly close 先设置 closed 再取消 HTTP job，旧 guard 会跳过持久未知结果，PENDING 记录重开后不可恢复。关闭过程在 store 仍打开时用 NonCancellable 写入未知结果，保留原身份和原件，已受理事实不能降级。 |
| 4292421670 | 麦克风在明知无法发送时仍启动录音，结束后被 ready 拒绝并删除。录音前、权限回调后以及发送共用 Controller 门禁，并在主界面和浮层禁用暂不可用的麦克风。 |
| 4296227063 | 会话创建已提交后，宿主绑定异常越过 HTTP 外壳成为非结构化 500。现在返回带准确会话身份和原请求修复指引的 HOST_INCOMPATIBLE，保留成功 ledger，修复后重试不会创建额外宿主会话。 |
| 4296227071 | HTTP 取消先发布 failed、稍后回调才发布 partial message.completed，顺序与正常生命周期相反。实际停止后先闭合已发布快照，再由 Core 发派发终态；queued 与没有回调的取消仍有终态。 |
| 4296227077 | 旧账号允许重复 client_conversation_id，任意查找可能返回错误时间线。只用唯一且通过 AEAD 与原 input_hash 验证的创建回执恢复 canonical；证据不足或冲突明确返回 IDEMPOTENCY_CONFLICT。不删除或合并旧会话，不改旧 ledger；新增 canonical 标题通过 AEAD 保存。 |

OpenClaw 配套提交为 `432e7c0545ecfe36af25fd4c451d777d434ae8fc`。主仓 pin、README 和 MVP 锁定流程均固定此提交；Hermes CI 输入固定 `03aaab2015e219b855cc73cad416664a67a95473`。共享 Gateway 契约及其提交 `4dd825a` 没有变化。

## 复现与验证

- Android 生产修改前的定向回归：state 32 项中 8 项失败；分页恢复 8 项中 2 项失败。原有断言保留。
- OpenClaw 固定真实 SDK 的前 3 项新场景在旧实现上全部失败；最终 36 个文件、186 项测试通过，source/runtime 两入口的 24 项取消与会话边界回归通过，类型、构建和契约检查通过。测试使用原生 SessionManager，覆盖 reopen、等待取消时读历史、排队期间的迟到条目、后续正常回合、新原生会话未落盘、真实取消优先与 ALREADY_COMPLETED。
- MVP 文档与 pin 一致性等 9 项测试通过；本地真实插件 pin 和共享契约匹配检查通过。
- 最终固定提交的跨宿主协议验证：Hermes、OpenClaw 各 70 个向量通过，3 项输出一致性检查通过。
- 主仓 Node 全套 799 项测试与 TypeScript 类型检查通过。
- Android 会话界面 271 项和 App 236 项完整测试通过；初轮状态与分页定向绿测 40 项通过，另增加普通消息/批次显式重试的取消竞态回归。
- 规范请求体更新后，网关客户端 211 项、分页恢复 8 项与协商门禁 2 项通过；门禁测试精确校验原始 HTTP 体及幂等身份，并等待实际生成就绪。
- 后续主仓 4 条意见的新增回归在旧实现上复现 6 项失败；补修后领域层 23 项、会话界面 277 项、App 238 项完整测试通过，包含最终分页回执失效、新轮重开、ordinary/voice/batch 关闭恢复和录音门禁。
- 最终 Full Debug APK 构建和固定调试签名校验通过，SHA-256 为 `00518d2c6a2a390ebde9c76a4720df6a9ac3d10b710b907cf9a3f51acaff1544`。
- Hermes 初轮规范与旧版取消请求的边界回归 25 项、真实 SDK 原生矩阵 43 项通过；最终独立测试 415 项通过、14 项 SDK 专用跳过，强制 SDK 矩阵 74 项全部通过且无跳过，覆盖创建绑定故障、取消事件顺序与旧 canonical 恢复/AEAD 边界。
- Android 最终回归与 PR CI 结果以本轮提交对应的检查记录为准。

本地复核另补 OpenClaw 的两个相邻竞态：真实取消已经落盘时，迟到的 UNSUPPORTED/ALREADY_COMPLETED 不能删除取消边界；SDK 新 Session 在第一次 flush 前可能没有原生文件，可选历史记账不能阻断新 runner。新增 6 项 source/runtime 红测全部复现，补修后包含在以上 186 项绿测中。

## 已有依赖锁限制

当前时间运行全局 `mvp:lock:check`，Android、Artifact、Bridge、Hermes、Model、TSNET 六行的证据已过期；在本轮基线也得到相同失败。OpenClaw 插件行的一致性与完整性检查通过。此修复没有在缺少重新核验的情况下延长其他依赖的证据日期。
