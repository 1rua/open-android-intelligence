# Codex PR review 复核与修复

复核范围：主仓 [PR #7](https://github.com/1rua/open-android-intelligence/pull/7)、OpenClaw [PR #1](https://github.com/1rua/openclaw-gateway-plugin/pull/1)、Hermes [PR #1](https://github.com/1rua/hermes-gateway-plugin/pull/1)。主仓基线 `8ca5483`，OpenClaw 基线 `9ad6456`。

初轮共 9 条具体意见，后续 OpenClaw review 追加 4 条，均确认存在可触发的问题；Hermes 没有报告具体问题，但需要配套接受规范取消请求。

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

OpenClaw 配套提交为 `66b20e56da7131a1acc084d733a4329ec782efaf`。主仓 pin、README 和 MVP 锁定流程均固定此提交。共享 Gateway 契约及其提交 `4dd825a` 没有变化。

## 复现与验证

- Android 生产修改前的定向回归：state 32 项中 8 项失败；分页恢复 8 项中 2 项失败。原有断言保留。
- OpenClaw 固定真实 SDK 的前 3 项新场景在旧实现上全部失败，修复后全部通过；最终 35 个文件、168 项测试通过，source/runtime 两入口的 10 项取消与会话边界回归通过，类型、构建和契约检查通过。测试使用原生 SessionManager，覆盖 reopen、排队期间的迟到条目、后续正常回合、新原生会话与 ALREADY_COMPLETED。
- MVP 文档与 pin 一致性等 9 项测试通过；本地真实插件 pin 和共享契约匹配检查通过。
- 最终固定提交的跨宿主协议验证：Hermes、OpenClaw 各 70 个向量通过，3 项输出一致性检查通过。
- 主仓 Node 全套 799 项测试与 TypeScript 类型检查通过。
- Android 会话界面 271 项和 App 236 项完整测试通过；初轮状态与分页定向绿测 40 项通过，另增加普通消息/批次显式重试的取消竞态回归。
- 规范请求体更新后，网关客户端 211 项、分页恢复 8 项与协商门禁 2 项通过；门禁测试精确校验原始 HTTP 体及幂等身份，并等待实际生成就绪。
- 最终 Full Debug APK 构建和固定调试签名校验通过，SHA-256 为 `2d57e1f57895bed59e9dd9a431883a342df7996608281ca7fbb52243dcdb8e2c`。
- Hermes 规范与旧版取消请求的边界回归 25 项、固定真实 SDK 原生矩阵 43 项通过。
- Android 最终回归与 PR CI 结果以本轮提交对应的检查记录为准。

## 已有依赖锁限制

当前时间运行全局 `mvp:lock:check`，Android、Artifact、Bridge、Hermes、Model、TSNET 六行的证据已过期；在本轮基线也得到相同失败。OpenClaw 插件行的一致性与完整性检查通过。此修复没有在缺少重新核验的情况下延长其他依赖的证据日期。
