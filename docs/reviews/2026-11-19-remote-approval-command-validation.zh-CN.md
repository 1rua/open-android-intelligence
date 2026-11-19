# Android 远程审批与命令目录回归

复现基线为 `dev@dbf69eae`，对应 Issue #208。

## 协议依据

- Harness Session V3 在 `$events` 的 `ready` 帧提供 `clientId`。答复通过 `$events/result` 提交同一订阅的 `clientId`、审批 `eventId` 和 `allowed-once` 或 `rejected`。基线错用了 `$events/answer` 并丢失 `clientId`。固定上游客户端展示了[审批请求和回执](../../tools/remote/fixtures/media-upstream/packages/client-core/src/harness-alpha-client.ts#L1105-L1137)。
- Harness 的空回执仅表示事件应答端点已返回，已结束事件也可能得到同样的空回执。`cancel` 表明本订阅的待办已移除。会话审计使用独立身份，`approval/asked` 和 `approval/decided` 由该身份配对。依据为[事件应答与清理](https://github.com/deepseek-ai/deepseek-harness/blob/66491b05e3f05ad35bcdd4f6cd7ed26041d9e3db/packages/api/gateway/src/index.ts#L670-L731)与[审计事件](https://github.com/deepseek-ai/deepseek-harness/blob/66491b05e3f05ad35bcdd4f6cd7ed26041d9e3db/packages/interaction/user-approval/src/index.ts#L243-L261)。
- ACP 和 Codex 使用 Remote Host 生成的 `requestHandle`。成功转发返回 `{resolved:true}`。网络写入前失败允许用户重新提交，结果未知时保留后续事件收敛路径。依据为[ACP 权限回执](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/acp/gateway.ts#L818-L841)与[Codex 回执](../../tools/remote/fixtures/media-upstream/packages/plugin/src/codex/domain.ts#L393-L403)。
- Harness 的 `commands/list` 接收 `payload.args.agentId` 并返回实际命令数组。基线把会话标识封装到 `request` 对象中，解码器又只接受带 `items` 或 `commands` 的对象。目录按已打开会话读取，参数提示可取自 `input.hint`。依据为[官方目录实现](https://github.com/deepseek-ai/deepseek-harness/blob/66491b05e3f05ad35bcdd4f6cd7ed26041d9e3db/packages/interaction/commands/src/index.ts#L309-L320)和[官方客户端](https://github.com/deepseek-ai/deepseek-harness/blob/66491b05e3f05ad35bcdd4f6cd7ed26041d9e3db/packages/client/ui-commands/src/client/service.ts#L97-L130)。
- ACP 通过 `available_commands_update` 发送所属会话的目录。Android 只接收已绑定会话的公告。无会话、无目录能力或尚未收到公告时显示现有空态，手输命令仍按原文提交。依据为[ACP 斜杠命令规范](https://github.com/agentclientprotocol/agent-client-protocol/blob/7e87dc205a7325bd07d0249fd20bb7486ee6ba95/docs/protocol/v1/slash-commands.mdx#L8-L73)和[Remote 会话定向转发](https://github.com/liguobao/ds-harness-remote/blob/ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7/packages/plugin/src/acp/peer-bridge.ts#L304-L327)。

## 验证范围

- 回归从会话传输、仓储、工作台状态到补全筛选。覆盖发送前失败、空回执、明确终态、回执迟到、目录异常重试、作用域切换、空目录和手输命令。
- 历史 Gateway v2 的审批回执只做形态回放。当前生产会话使用已认证的 Remote RPC 通道。
- 真实 Android 设备和线上 Host 联合验收待实测。ACP 目录是否可见取决于当前 Agent 是否通告及 Host 是否投递该帧。
