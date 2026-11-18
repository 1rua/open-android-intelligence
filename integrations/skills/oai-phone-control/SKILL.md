---
name: oai-phone-control
description: Read, observe, and control Android phones under the DSH Remote architecture via either native DSH tools (remote_list_hosts, remote_list_workspaces, remote_create_session, remote_send_message, remote_wait_sessions, remote_read_session, remote_download_file) or the external Stdio MCP server (oai-android-mcp / android_* tools). Use whenever an agent needs to discover an Android phone, inspect phone capabilities, dump UI trees, capture SoM screenshots, tap/swipe/scroll/input/navigate/launch apps, query SMS/notifications/call logs, send SMS, read/ack proactive push deliveries, check request receipts, or configure/troubleshoot OAI phone control under ds-harness-remote.
---

# OAI Phone Control (DSH Remote 架构)

本 Skill 指导智能体在 **新版 `ds-harness-remote` 架构**下读取与操控 Android 手机。旧版独立 `oai-core`、`oai` CLI（`oai login` / `oai devices` / `oai invoke` / `oai mcp`）、Gateway Protocol v2 及 `hermes open-android-intelligence` 均已退役，禁止再调用。

详细参数 Schema、能力插件入参与错误码见 [protocol-and-tools.md](references/protocol-and-tools.md)。

---

## 1. 架构与两个固定入口

手机端以 **OAI Virtual Host**（`oai.android`）接入 DSH Remote 端到端加密通道（`Noise_IK_25519_ChaChaPoly_SHA256`），对外固定暴露：

- `backend`: `"harness"`
- `workspaceId`: `"phone-capabilities"`
- 常驻总线会话：`sessionId: "control-bus"`（用于能力查询、旧请求回执查询 `request.status` 及单步独立调用）
- 独立任务会话：通过 `session/create` 创建的 `taskSessionId`（用于多步读树/截图/动作/文件下载隔离）

手机端**不运行本地 LLM**，仅执行确定性 JSON 信封或等价 CLI 简写；权限与确认由手机 `platform-kernel`、`policy-engine` 和无障碍浮窗（`TYPE_ACCESSIBILITY_OVERLAY`）独立裁决。

按当前 Agent 所在运行环境选择唯一入口：

1. **入口 A：原生 DSH Agent（内置 `ds-harness-remote` 工具）**
   直接调用 `remote_list_hosts`、`remote_list_workspaces`、`remote_create_session`、`remote_send_message`、`remote_wait_sessions`、`remote_read_session`、`remote_download_file`、`remote_interrupt_session`、`remote_get_operation`。
2. **入口 B：外部 Agent（Claude Code / Codex / OpenCode / Hermes ACP 等）**
   通过电脑上 DSH 进程内桥 + `oai-android-mcp` Stdio MCP Server 调用 `android_*` 工具（如 `android_list_devices`、`android_get_ui_tree`、`android_tap` 等）。
   - 外部 Agent **不得**读取或复制 Remote 账号 token/私钥。
   - ACP 上游 `session/new` / `session/load` 强制使用 `mcpServers: []`，外部 Agent 必须在电脑原生 MCP 配置中挂载生成的 `mcpServers["oai-android"]`。

---

## 2. 入口 A：原生 DSH `remote_*` 操作流程

### 2.1 发现手机与确认能力

1. 调用 `remote_list_hosts({})`，从在线设备中选择目标手机的 `hostId`（多台手机存在歧义时先向用户确认，禁止猜测）。
2. 调用 `remote_list_workspaces({ "hostId": "<HOST_ID>" })`，确认存在 `backend: "harness"` 且 `workspaceId: "phone-capabilities"` 的工作区。
3. 查询手机已启用的能力目录与原生屏幕可用性（发往 `sessionId: "control-bus"`）：
   - `operationId` 与信封内的 `request_id` 使用同一个合法 ID（正则 `^[A-Za-z0-9_-]{16,128}$`）。
   - 调用 `remote_send_message`：
     ```json
     {
       "hostId": "<HOST_ID>",
       "backend": "harness",
       "workspaceId": "phone-capabilities",
       "sessionId": "control-bus",
       "operationId": "<REQUEST_ID>",
       "message": "{\"request_id\":\"<REQUEST_ID>\",\"op\":\"phone.capabilities\",\"args\":{}}"
     }
     ```

### 2.2 创建任务会话并执行操作

1. 多步任务先调用 `remote_create_session` 创建独立任务（`operationId` 必须与后续消息不同）：
   ```json
   {
     "hostId": "<HOST_ID>",
     "backend": "harness",
     "workspaceId": "phone-capabilities",
     "operationId": "<CREATE_OP_ID>"
   }
   ```
   记录返回的 `sessionId` 作为 `taskSessionId`。
2. 通过 `remote_send_message` 向该 `sessionId` 发送严格 JSON 信封（键仅限 `request_id`、`op`、`args`）或 CLI 简写（`<op> --request-id <request_id> <JSON args对象>`）：
   ```json
   {
     "request_id": "<REQUEST_ID>",
     "op": "ui.dump_tree",
     "args": {}
   }
   ```
   等价 CLI 简写（手机解析器不经过 shell，禁止管道/变量展开）：
   ```text
   ui.dump_tree --request-id <REQUEST_ID> {}
   ```
3. 等待并读取结构化结果：
   - 调用 `remote_wait_sessions({ "targets": [{ "hostId": "<HOST_ID>", "backend": "harness", "workspaceId": "phone-capabilities", "sessionId": "<TASK_SESSION_ID>" }], "timeoutMs": 30000 })`。
   - 调用 `remote_read_session({ "hostId": "<HOST_ID>", "backend": "harness", "workspaceId": "phone-capabilities", "sessionId": "<TASK_SESSION_ID>", "maxMessages": 20 })`，从 `assistant/message` 记录中匹配对应 `request_id` 的 JSON 结果。
4. 若结果包含 `artifacts[]`（如 SoM 标注截图）或因正文超过 12,000 字符而返回 `resultArtifact`（`path: "artifacts/<artifactId>"`），调用 `remote_download_file` 下载到本地 `Downloads/dsh-remote-*` 目录（上限 50 MiB）。

### 2.3 查询持久回执与精确取消

- **查询旧请求回执**：DSH 重启后 `remote_get_operation` 内存回执会丢失；请向 `control-bus` 发送 `op: "request.status"`（使用新 `request_id`），`args` 为 `{"targetRequestId":"<ORIG_REQ_ID>","targetTaskSessionId":"<ORIG_TASK_OR_control-bus>"}`。
- **精确取消单个请求**：向原 `taskSessionId` 发送 `op: "request.cancel"`（使用新 `request_id`），`args` 为 `{"targetRequestId":"<ORIG_REQ_ID>"}`；不要用 `remote_interrupt_session` 替代精确取消，后者调用 `session/cancel` 会冻结整个任务会话且不能用于 `control-bus`。

---

## 3. 入口 B：外部 Agent（`oai-android-mcp`）操作流程

### 3.1 检查前置桥状态与挂载

- 确保电脑 DSH 正在运行，且已安装仓库 `integrations/oai-android-mcp` Cordis 插件（配置 `privateConfigPath`，目录权限 `0700`、配置文件 `0600`），并将生成的 `mcpServers.oai-android` 加入外部 Agent 原生 MCP 配置。
- 先调用 `android_bridge_status({})` 检查本地桥可用性；注销 DSH、切换服务地址或卸载插件后须重新生成配置。

### 3.2 发现手机、读取能力与操作屏幕

1. 调用 `android_list_devices({})` 获取已授权且公开 `phone-capabilities` 的在线手机列表（取 `hostId`、`backend: "harness"`、`workspaceId: "phone-capabilities"`）。
2. 调用 `android_list_capabilities({ hostId, backend: "harness", workspaceId: "phone-capabilities" })` 核对已验证插件能力及 `nativeScreen.available`。
3. 屏幕交互遵循 **“读树 → 按需 SoM → 动作 → 核对新快照”**：
   - 首次调用 `android_get_ui_tree`（或 `android_capture_som_screenshot`）可省略 `taskSessionId`，桥会自动创建任务并在结果中返回 `taskSessionId` 与 `snapshotId`。
   - **后续所有屏幕动作**（`android_tap`、`android_long_press`、`android_swipe`、`android_drag`、`android_input_text`、`android_scroll`、`android_system_nav`、`android_launch_app`）**必须显式传入原 `taskSessionId` 和最新 `snapshotId`**；缺任务禁止暗中新建。
   - 只有语义节点不足或需要视觉定位时再调用 `android_capture_som_screenshot`，随后用 `android_read_artifact` 下载生成的 WebP 产物。
4. 数据与能力调用：
   - 快捷工具：`android_query_sms`、`android_read_notifications`、`android_query_call_log`、`android_send_sms`（均须传 `taskSessionId`，单步可用 `"control-bus"` 或复用当前任务 ID，参数置于 `params`）。
   - 通用已验证能力（如短信定时 `sms.schedule` / `sms.job-status` / `sms.cancel`）：使用 `android_invoke_capability`。
   - **严禁**把屏幕插件 `org.openandroidintelligence.screen-*` 传给 `plugin.invoke` / `android_invoke_capability`（内核会拒绝并报错 `SCREEN_NATIVE_OPERATION_REQUIRED`）。

### 3.3 等待确认、状态恢复与主动推送取件

- **等待手机人工确认**：返回 `state: "awaiting_confirmation"`（或会话投影为 `needs_user_input`）时，提示用户在手机悬浮确认浮窗点击确认；随后用 `android_request_result` 继续读取当前进程结果，或用 `android_request_status` 查询持久回执，或用 `android_cancel_request` 精确取消原请求。
- **主动推送取件（`android_read_delivery` → `android_ack_delivery`）**：
  - 推送消息只含 `deliveryId`、`eventId`、`payloadDigest` 引用；调用 `android_read_delivery` 读取正文（首次读取需手机本机确认将该来访 Client 绑定到目标端点）。
  - 若 `android_read_delivery` 超时或结果未知，优先调用 `android_request_result`；若桥已重启，可传入原 `taskSessionId`、原 `request_id` 与 `resume: true` 做**只读恢复查询**（禁止换新 `request_id` 且 `resume: false` 盲目重发）。
  - 只有实际解析并消费事件后，才用**新 `request_id`** 调用 `android_ack_delivery` 原子清理手机正文。

---

## 4. 安全与幂等铁律

1. **幂等键与未知结果禁止重放**：`request_id` / `operationId` 须符合 `^[A-Za-z0-9_-]{16,128}$`。一旦进入 `outcome_unknown`、`accepted` 或等待超时，**严禁生成新 `request_id` 重试写操作**；必须用原 ID 查 `request.status` / `android_request_status` / `android_request_result` / `remote_read_session`。同 ID 换参数会触发 `REQUEST_ID_CONFLICT`。
2. **重启不续做控制任务**：控制任务正文、树、截图和 artifact 仅存手机内存；手机重启或急停后旧 `taskSessionId` 失效，`request.status` 只返回最小元数据（`resultAvailable: false`），不得伪造结果或继续对旧任务发动作。
3. **400ms / 3000ms 动作收敛分离**：手机在屏幕动作后等待 400ms 布局静默或 3000ms 硬超时，分别回报 `execution.status` 与 `ui.settled`（`quiet` / `timeout`）。**超时（`settled: false`）不代表点击未执行，严禁因此重复点击。**
4. **快照绑定与参数互斥**：`snapshotId` 绑定任务、窗口、前台包名与旋转尺寸；`SNAPSHOT_STALE` 时重新读树，禁止凭记忆点旧坐标。`ui.tap` / `ui.long_press` 中 `nodeId` 与 `x/y` **二选一互斥**；`ui.input_text` 必须指定可编辑 `nodeId`，禁止向未核对焦点盲输。
5. **非终态绝不报成功**：`accepted`、`running`、`awaiting_confirmation`、`needs_user_input`、`idle` 均不等于业务成功。远端智能体无法审批手机本地确认弹窗，也无法点击悬浮窗或急停胶囊。
6. **锁屏与最小暴露**：手机锁屏时所有 `ui.*` 读树、截图和手势一律拒绝；通知、短信和屏幕内容属敏感隐私，严格按任务范围最小读取。
