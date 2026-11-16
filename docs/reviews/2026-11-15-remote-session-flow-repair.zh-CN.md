# Remote 会话链路修复与验收边界

关联 issue：#196；集成 PR：#190（保持 Draft）。本文记录 2026-11-15 实机反馈后的源码诊断与自动化回归，不替代整个 DSH Remote 迁移验收。架构边界仍以[架构重构规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)为准。

## 已确认的缺陷与修复

| 反馈 | 证据与根因 | 修复及当前证据 |
| --- | --- | --- |
| Harness 会话未显示、无法进入 | 固定上游 `ea1c0925…` 与 0.6.5 `eef232d2…` 的 `hostCapabilities()` 独立声明 Harness，`hostWorkspaceTypes()` 只列 Codex/ACP。本仓库对非空类型表要求存在 `harness`，真实混合目录回归得到期望 `[harness,codex]`、实际 `[codex]`。 | Harness 按已协商的 `harness.remote.v3` 启用，Codex/ACP 保留逐项就绪检查；无能力时不探测或构造端点。测试验证发现、选择、真实 `session/page` 历史上屏及 `session/follow` 订阅。**VERIFIED（受控 RPC 边界）**。 |
| 设置页只剩当前工作区 | `discover` 写入完整目录后，`finally → restoreOffline` 将目录替换成保存的单项。红灯为期望 `[one,two]`、实际 `[one]`。 | 离线恢复仅补上次选择，保留当前 origin/account 的已发现目录和可用状态。**VERIFIED（运行时回归）**。 |
| 新建会话闪回旧会话 | 真实链为创建回执 → 打开新会话 → 原生流就绪 → 刷新目录；尚未进入持久目录的新会话被当作缺席，`leaveManagedThread` 再打开旧会话。红灯为期望 `new`、实际 `old`。固定 Codex 上游也明确允许新建但尚未持久化的会话继续使用。 | 按完整 `ConversationScope + 会话 ID` 保留创建回执，首次被完整目录收录后释放；用户切换仍优先，明确本机/远端删除与关闭控制器会清理回执。未引入等待时长、次数上限或重试。**VERIFIED（真实控制器/仓储/carrier 回归）**。 |
| 当前页面卡在待接收，通知已有回答；标题显示编码 ID | 生产投影事件 ID 含合法冒号，镜像正文消费者的 `applyAndCommit → saveCursor` 却套用旧线协议字符限制并抛出 `SNAPSHOT_BASELINE_INVALID`。通知此前已由运行时前置提交收到同一事件，因而能先显示。独立订阅测试直接捕获该异常。 | 本机事件检查点按不透明 ID 校验，不放宽远端线协议游标验证；通知标题从真实会话目录与镜像元数据读取。**VERIFIED（正文订阅与通知回归）**。 |
| 普通发送的远端用户回声与本地原稿重复 | Codex 回执提供原生 turn，回声提供该 turn 的原生 user item，但原投影没有补齐两者与客户端发送身份的映射；历史补读也可能丢失已确认映射。 | 使用本次 prompt 回执确认的 turn 绑定唯一普通用户项，发布真实 `MessageAccepted` 并持久化原生用户 ID；历史保留映射。相同文字的不同发送保持独立，用户行位于对应助手回复之前。**VERIFIED（普通发送的受控回归）；STEER 限制见下文**。 |

核心实现位于 `RemoteRuntime`、`WorkbenchController`、`RemoteConversationRepository`、`RemoteTimelineProjection` 与 `EncryptedConversationMirror`；保持现有 UI、Material 3 结构与文案。

上游固定来源登记于 `tools/remote-interop/fixtures/source.json`（`ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`）和 `tools/remote-interop/release-fixtures/source.json`（0.6.5，`eef232d2c43b0c9d04998a18db98cbc9e673e488`）。能力声明取自上述固定版本的 `packages/plugin/src/service.ts`、`packages/plugin/src/client-capabilities.ts`；仓库所存官方客户端接口位于相应 fixture 下的 `packages/client-core/src/harness-alpha-client.ts`、`remote-gateway.ts`，Session API 固定元数据位于 `tools/remote-interop/harness-fixtures/source.json`。

## 未关闭的 STEER 验收

同一 turn 内的普通发送与 STEER 可以产生多个原生用户项。已执行的唯一性场景仍为**期望 2 条用户消息、实际 3 条**；issue #196 的“同一次发送只显示一条”在该场景尚未满足。原始红灯测试与输出保留在临时证据中，不能用正式安全回归通过代替它。

当前正式回归只证明保守边界：后来的 STEER 回声不会冒认普通发送的客户端身份；缺少权威关联时保留独立本地发送原子，不丢正文。固定协议及本轮核对的公开 Remote 实现未提供 `clientUserMessageId`。在同时保留可见正文、禁止凭文字猜测发送归属的约束下，现有字段不足以安全绑定同轮的多个用户项；这不等于界面去重在所有产品策略下都不可能。保留正文或收到 ACK 后隐藏本地项等策略仍待用户确认，本次没有变更该产品行为。**BLOCKED（身份关联/产品策略待确认）**。

## 验证记录

- 回归基准：`113ade75a3828316ec7d499aa8ea8901316c36f2`。五项反馈的上述修复路径均先记录目标失败，再改生产实现并复验；第五项通过范围限普通发送，不包含同轮 STEER 唯一性。
- 最终集成源码：`8d083fae4ab9b129750ef1ebbc68c14bf8fbb502`。`test:fast`（相对 `113ade75`）与 `test:pr` 均已通过；`test:pr` Full App 运行 134 类、1468 项，其中 1465 通过、3 按条件跳过。三个新增 App 回归类共 13 项全部执行且无跳过；`RemoteRuntimeTest` 18 项、`SqliteConversationMigrationTest` 10 项也全部执行通过。
- `RemoteSessionDeliveryIntegrationTest` 覆盖实时正文/通知标题、回声先于回执、回执先于回声、无客户端身份的历史补读、切换会话后的迟到回执、不透明游标及 STEER 保守边界。安全边界通过不计为 STEER 唯一性通过。
- 同一源码的 Play 定向验证：三个新增类 13 项及 H2 精确场景 1 项，共 14/14 通过、0 跳过。Full 与 Play 重复场景不相加为唯一总数。
- 本轮远端 CI 与新 APK 尚待推送后触发、回读，**UNVERIFIED**；旧提交的远端 CI/APK 不作为 `8d083fae` 的证据。后续文档提交不改变上述已验证业务源码的归属。
- 真实 Host、用户账号、手机设备及新 APK 复测：**UNVERIFIED**。本轮仅控制认证后的 RPC 边界，不声称线上部署、实机交互或整个迁移已经验收。

原始日志、堆栈与失败夹具保留在 `/tmp`，不入库。诊断记录为 `/tmp/dsh-visibility-red-113ade75.log`、`/tmp/dsh-visibility-h1h3-red.log`、`/tmp/dsh-stream-red-113ade75-v4.log`、`/tmp/dsh-stream-steer-red.log`；最终集成验证为 `/tmp/dsh-issue196-integrated-fast.log`、`/tmp/dsh-issue196-integrated-pr.log`、`/tmp/dsh-issue196-integrated-play.log`。临时日志不是长期分发产物，远端 CI 链接待集成方回读后补入 PR。
