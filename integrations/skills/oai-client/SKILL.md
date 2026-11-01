---
name: oai-client
description: Read and control paired Android devices through OAI Core (via CLI or MCP). Use whenever an agent (connected to Core or standalone) needs to inspect phone capabilities, query notifications, query SMS, perform screen actions, tap, swipe, launch apps, or invoke any Android device operation.
---

# OAI Core 客户端操控指南 (OAI Client Control)

> **2026-11-01 现行需求**：设备能力策略属于 Android 安装实例，独立于 Core、账号与 Agent；每项能力独立总开关及三级模式，新能力默认「每次手动」，主动推送默认关闭。旧配对 grant、迁移复核、账号能力开关和按配对后台许可已被取代。系统权限、合法设备访问关系和账号私有会话/附件隔离继续生效。以下冲突的历史描述不再生效；实现与验收尚在推进。详见 [全局授权规格](../../../docs/superpowers/specs/2026-11-01-global-device-authorization.md)。

本 Skill 指导任何智能体（无论是否接入 OAI Core 会话通道）通过统一的 OAI Core CLI 工具链（`oai`）或 MCP 工具读取并操控已连接的 Android 客户端。

---

## 1. 架构与接入模式

OAI Core 统一提供连接认证、设备绑定、能力发现、协议验证与请求路由。所有 Agent 读取或控制手机均调用 Core 接口；Android 是设备能力授权的唯一权威。

**2026-11-01 现行授权规则**：安装实例全局保存每项能力总开关及三级模式，新能力默认每次手动，主动推送独立且默认关闭；旧配对许可/迁移复核/按账号保存能力规则已被取代。系统权限、合法设备绑定、账号会话和附件隔离继续保留。详见[全局规格](../../../docs/superpowers/specs/2026-11-01-global-device-authorization.md)，实现和验收以[执行记录](../../../docs/reviews/2026-11-01-global-device-authorization.zh-CN.md)为准。

- **模式 A：独立 / 命令行 Agent（未接入 Core 会话）**：
  直接在终端执行 `oai` CLI 命令（如 `oai devices`、`oai capabilities`、`oai invoke`）。
- **模式 B：MCP 协议接入 Agent（未接入 Core 原生会话，但支持 MCP 协议）**：
  配置 `oai mcp` 作为 stdio MCP Server，调用标准工具：`oai_capabilities`、`oai_invoke`、`oai_request_status`、`oai_cancel_request`、`oai_ack_result`。
- **模式 C：接入 Core 原生会话的 Agent（如 DSH / Hermes / Codex 桥接）**：
  同样可使用上述 CLI 或宿主工具调用 OAI Core 调度设备。

---

## 2. 身份认证与准备工作

在操控设备之前，必须确保客户端凭据经 Core 认证并绑定目标账号、已配对设备与有效配对代次。共享 Agent 会话不自动继承任何个人设备调用凭据；模型不得提供或覆盖账号、设备和授权版本：

### 2.1 检查认证状态
运行 `oai devices`。
- 若返回设备列表 JSON（例如 `[{"deviceId": "device-xyz", "model": "Pixel 8", "status": "online"}]`），说明已成功认证并配对。
- 若报错 `LOGIN_REQUIRED` 或未登录，请执行登录：
  ```bash
  oai login --url http://127.0.0.1:8045 --username <USERNAME> --device <DEVICE_ID>
  ```
  *(注：远程连接使用实际 HTTPS 地址，受控开发测试环境如使用 HTTP 需加 `--allow-http` 参数)*

### 2.2 发现设备与可用能力
在执行任何控制前，**必须先通过 `capabilities` 发现可用能力及其参数契约（JSON Schema）**：
```bash
oai capabilities
```
返回结果包含当前配对设备支持的所有能力（Capability ID）、版本（Version）以及输入输出 JSON Schema。严禁臆测不存在的能力或参数。

---

## 3. 设备操控工作流 (CLI)

操控设备遵循 **"提交 -> 等待/轮询 -> 确认结果"** 幂等安全流程：

### 3.1 提交并等待操作（推荐一键方式）
使用 `oai invoke` 提交操作，并配合 `--wait <秒数>` 同步等待结果：
```bash
oai invoke <CAPABILITY_ID> --version <VERSION> --parameters '<JSON_OBJECT>' --agent-name '<展示名称>' --purpose '<本次具体目的>' --idempotency-key <UNIQUE_KEY> --wait 30
```
- `<CAPABILITY_ID>`：从 `oai capabilities` 获取的完整标识（例如 `org.openandroidintelligence.notifications.query` 或屏幕控制能力）。
- `--version`：能力的契约版本（如 `1.0.0`）。
- `--parameters`：传给设备的 JSON 参数（可传内联 JSON 或 `@path/to/params.json`）。
- `--agent-name`、`--purpose`：每次必须声明，分别用于手机展示请求方和目的；不是可信身份，也不会扩大权限。缺失返回 `CALLER_DECLARATION_REQUIRED`。
- `--idempotency-key`：唯一幂等键（如 UUID 或任务标识）。网络重试时必须复用同一个幂等键，避免重复触发侧效应。
- `--wait`：等待服务端及设备返回结果的最长等待秒数。

### 3.2 异步模式与状态查询
若未指定 `--wait` 或等待超时，`invoke` 会立即返回包含 `requestId` 和初始 `state` 的对象。
通过以下命令轮询或查询状态：
```bash
oai status <REQUEST_ID>
```
请求状态说明：
- `pending`、`claimed`、`cancel_requested`：非终态。`localAuthorizationState=awaiting_confirmation` 表示手机正在等待用户确认；`executing` 表示已进入本地执行。两者都不是成功。
- `succeeded`：终态成功，返回数据包含在 `result` 中。
- `failed` / `denied` / `cancelled` / `expired` / `outcome_unknown`：终态结束或被拒绝/超时。

### 3.3 结果确认与资源释放
当 Agent 成功解析并消费了操作结果后，建议向 Core 发送确认，以便 Core 与手机释放相关瞬态敏感数据：
```bash
oai ack <REQUEST_ID> --result-digest <RESULT_DIGEST>
```

ACK 仅确认已经产生且被消费的终态结果；提交前或仅 Claim 后会返回 `RESULT_NOT_READY`。重复 ACK 返回同一持久回执。`resultDigest` 由状态响应提供，用于确认消费的是同一结果。

### 3.4 取消操作
如果任务不再需要，可请求取消设备端操作：
```bash
oai cancel <REQUEST_ID>
```
*(注意：`cancel_requested` 并不保证能彻底阻断已在手机端生效的物理操作)*

---

## 4. MCP 工具使用规范

如果 Agent 通过 MCP（`oai mcp`）操控设备，工具映射如下：

1. **`oai_capabilities`**：
   - 作用：查询当前凭据所绑定的 Android 设备支持的能力列表及输入 Schema。
2. **`oai_invoke`**：
   - 参数：`capability_id` (str), `capability_version` (str), `parameters` (dict), `idempotency_key` (str), `agent_name` (str), `purpose` (str)。
   - 作用：提交设备操作请求。
3. **`oai_request_status`**：
   - 参数：`request_id` (str)。
   - 作用：查询请求状态及执行结果。
4. **`oai_ack_result`**：
   - 参数：`request_id` (str), `result_digest` (str)。
   - 作用：确认消费已产生的终态结果，重复 ACK 幂等。
5. **`oai_cancel_request`**：
   - 参数：`request_id` (str)。
   - 作用：请求取消进行中的任务。

---

## 5. 核心能力调用范例

### 范例 1：查询手机通知（Notifications）
```bash
oai invoke org.openandroidintelligence.notifications.query \
  --version 1.0.0 \
  --parameters '{"limit": 5}' \
  --agent-name "通知助手" --purpose "读取用户要求查看的测试通知" \
  --idempotency-key "notifications-request-001" \
  --wait 15
```

### 范例 2：查询短信（SMS）
```bash
oai invoke org.openandroidintelligence.sms.query \
  --version 1.0.0 \
  --parameters '{"limit": 10}' \
  --agent-name "短信助手" --purpose "读取用户指定范围的短信" \
  --idempotency-key "sms-request-001" \
  --wait 15
```

### 范例 3：屏幕与交互操控
详细能力说明请查阅 `references/capabilities.md`。

---

## 6. 安全与行为铁律

1. **零虚构原则**：所有 capability_id、version 与 parameters 必须与 `oai capabilities` 输出的 Schema 严格一致，严禁臆想参数字段。
2. **幂等性保障**：重试同一次操作必须保持相同的 `idempotency-key` 与 `parameters`；参数变更加新幂等键。
3. **非终端状态不报成功**：遇到 `pending`、`claimed` 或 `localAuthorizationState=awaiting_confirmation` 时，应告知用户手机端正在等待响应或需要本地确认，严禁在未获得 `succeeded` 之前向用户谎报操作完成。
4. **隐私与最小暴露**：手机通知、短信与屏幕内容属于用户高度敏感隐私，严格遵循任务范围，不随意全量抓取或外泄。

## 7. 主动推送接收端点

注册只创建经过认证的路由，不产生接收敏感数据的许可。使用现有合法设备凭据：

```bash
oai endpoint register --registration-key "agent-receiver-001" --label "测试接收器" --ttl-seconds 3600
oai endpoint list
```

让用户在手机「短信与通知主动推送」中选择完整稳定端点。稳定路由包括 Core 实例、账号路由范围、端点 ID、代次；不可用名称代替。
端点短期租约到期需由持有相同合法凭据的接收方续注册；重新注册行为以 Core 返回的 route 为准，不假设代次可以沿用。

```bash
oai endpoint receive <ENDPOINT_ID>
oai endpoint ack <ENDPOINT_ID> <DELIVERY_ID> <EVENT_ID>
oai endpoint unregister <ENDPOINT_ID>
```

只有实际解析、消费事件后才 ACK。读取和 Core HTTP 接受都不等于消费 ACK；重试使用相同事件与投递 ID。
MCP 对应 `oai_register_endpoint`、`oai_endpoints`、`oai_receive_deliveries`、`oai_ack_delivery`、`oai_unregister_endpoint`，参数与 CLI 对应。
Android 保存长期敏感内容加密队列，Core 只短期暂存路由正文并保留去重、ACK 元数据。
手机撤销或移除端点后，Agent 不能自行订阅恢复权限；名称、操作理由和端点注册都不能代替用户选择。
