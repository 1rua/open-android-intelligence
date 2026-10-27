# 权限审批与消息调度修复记录

日期：2026-10-28

基线：`dev@9c11f10040fc5deb5ec30d52aea2f84651bc18ab`

## 范围

本次修复覆盖 Android App、OAI Core 与 Hermes 原生会话适配器，保持
`App → OAI Core → Agent 插件/宿主` 架构不变。没有修改 Gateway Protocol v2.1
核心 Schema，也没有改变账号、主密钥、配对或历史数据库格式。

## 根因

### 审批卡

1. Hermes 原生审批事件包含 `command`、`reason`、`options` 等字段。App 在结构化
   解码失败时会让整条流进入 `AGENT_STREAM_INVALID`，而不是保留待审批状态。
2. App 未协商审批卡能力时会直接丢弃审批请求；没有保留命令，也没有可读降级。
3. Hermes 的 `request_id` 未出现在宿主 metadata 时，旧恢复逻辑在多个待审批项中
   选择最早一条。这可能把用户决定关联到另一条命令。
4. App 在生成中把 `/approve`、`/deny` 直接发出，但 Hermes 适配器仍先等待普通
   generation 锁。审批命令正是用来解除该 generation 的，因此会自锁到超时。
5. “审批卡不可用”曾被 App 文案描述成“命令已被拒绝”，混淆了渲染失败和用户决定。

### Queue 与 Steering

1. 原有拦截只是在客户端内存里排队，并立即把本地 ID 当作已受理；没有独立 Queue
   卡片、模式选择、失败状态或重连后的 generation 校验。
2. Core、Android Gateway Client 和 Hermes 适配器都没有 Steering 能力或专用路由。
3. 即使手动发送 `/steer ...`，也会经过普通 `accept_message()`，创建新的 generation，
   不符合“引导当前运行轮次”的语义。

## 修复

- 审批请求解析失败或无卡片能力时，生成不可提交的文本审批卡，保留原命令、原因及
  `/approve`、`/approve session`、`/approve always`、`/deny` 手动命令。
- 审批解析失败不再终止当前回复，也不自动批准/拒绝；后端 `resolved` 事件仍可落定文本卡。
- 缺失宿主 `request_id` 时，只有唯一命令匹配或整个队列仅一项才恢复；否则走文本审批。
- `/approve`、`/deny` 只允许走 Hermes 受控命令通道，绕过 generation 锁，不创建第二个
  generation；其他普通追问仍受 Queue/Steering 规则约束。
- Android 忙碌时显示“排队 / 引导”两个明确操作。Steering 仅在 Agent 声明 `steer=true`、
  已取得当前 generation 且消息不含附件时可用。
- Queue 项显示在输入框上方的独立卡片中，按 Agent、会话和顺序保存；当前轮完成前不发送。
- Core 新增当前 generation 只读快照。重连或重新打开会话时，只有明确得到空闲快照才发送
  Queue 队首；`queued`、`running`、`unknown`、查询失败或发送结果未知都会保持暂停。
- Core、远程桥和 Hermes 只接受与当前账号、会话及 generation 完全匹配的 Steering；失败时
  返回明确错误，不自动降级成 Queue。

## 协议兼容性

- 新增逐 Agent 布尔能力 `steer`，缺失按 `false` 处理，旧客户端会忽略。
- 新增 OAI Core 原生扩展路由：
  - `GET /agents/{agent}/sessions/{id}/generation`
  - `POST /agents/{agent}/sessions/{id}/steer`
- 两个路由位于第 17 节扩展面，不属于 v2.1 核心 Schema；`SchemaContractHash.CORE`
  不需要同步升级。
- 旧 Gateway/Agent 不声明 `steer`，App 只显示 Queue，不伪装 Steering。

## 验证

本机已执行：

- Core/Hermes 审批、Steering、重连 generation 快照：`22 passed`
- 固定本机 Hermes SDK 真实加载与原生会话契约：`1 passed`
- `CoreAgentConversationRepositoryTest`：通过
- `AgentSessionClientTest`：通过
- 审批卡、Composer Queue/Steering、Workbench 重连/切会话、审批状态机：通过
- 依赖锁：`PASS (8 rows)`
- 智能 Fast 门禁的 Core 阶段：`276 passed, 5 skipped`

本机未完成：

- `npm run test:fast` 的 Android 全量阶段需要 Robolectric Android 31/32/33/36
  运行时，本机缓存只有 34/35 且运行器无法联网，因此该总门禁未完成。
- 未进行真实 Samsung 设备、真实 Hermes 模型服务与跨网络重连验收；这些不能由
  Robolectric、协议双桩或本地 SQLite 测试替代。
