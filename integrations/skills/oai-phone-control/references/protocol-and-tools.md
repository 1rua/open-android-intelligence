# DSH Remote 手机操控协议与工具参考

本文档汇总 PR #190 确立的 `ds-harness-remote` 手机操控精确操作名、MCP 工具映射、插件参数 Schema 及常见错误码。所有字段名与约束均与手机端 `ControlRequestParser`、`PhoneControlBus`、`oai-android-mcp` 和 `plugins/schemas/*.json` 对齐。

---

## 1. 控制信封与 CLI 简写格式

发往 Virtual Host（`backend: "harness"`, `workspaceId: "phone-capabilities"`）的 `session/prompt` 正文只接受两种等效格式，不允许 `sh`、`eval`、环境变量展开或管道：

### 1.1 JSON 信封（严格三字段）

```json
{
  "request_id": "f4b76bb2-d134-419f-a948-65059486660b",
  "op": "ui.tap",
  "args": {
    "snapshotId": "snapshot-a",
    "nodeId": "12"
  }
}
```

### 1.2 CLI 简写

```text
ui.tap --request-id f4b76bb2-d134-419f-a948-65059486660b {"snapshotId":"snapshot-a","nodeId":"12"}
```

- `request_id` 正则：`^[A-Za-z0-9_-]{16,128}$`（推荐直接复用 `remote_send_message` 的 `operationId`）。
- 手机会基于 `op` 与规范化 `args` 计算 `SHA-256` 摘要；同 `request_id` 携带不同任务或参数会返回 `REQUEST_ID_CONFLICT`。

---

## 2. 操作码（`op`）与 `android_*` MCP 工具全表

### 2.1 发现、回执、取消与文件

| 用途 | 原生 DSH 操作 / 信封 `op` | 外部 Stdio MCP 工具 | 关键参数与说明 |
| --- | --- | --- | --- |
| 检查本机桥 | （不适用，原生直连） | `android_bridge_status` | `{}`；返回 `{ available, protocol, phoneOnly }` |
| 列出手机 | `remote_list_hosts` + `remote_list_workspaces` | `android_list_devices` | 筛选同时在线且公开 `harness / phone-capabilities` 的手机 |
| 查询能力目录 | `phone.capabilities`（发往 `control-bus`） | `android_list_capabilities` | 参数 `{ hostId, backend: "harness", workspaceId: "phone-capabilities" }`；返回已验证插件清单与 `nativeScreen` |
| 查持久回执 | `request.status`（发往 `control-bus`） | `android_request_status` | `args: { targetRequestId, targetTaskSessionId }`；只读元数据，不恢复旧任务 |
| 读内存结果 | `remote_read_session` | `android_request_result` | `{ ...fixed, taskSessionId, targetRequestId }`；读取当前进程内结构化结果 |
| 精确取消 | `request.cancel`（发往原 `taskSessionId`） | `android_cancel_request` | `args: { targetRequestId }`；绕过动作队列，不取消同任务其他请求 |
| 下载任务产物 | `remote_download_file` | `android_read_artifact` | 路径固定 `artifacts/<artifactId>`；仅限当前任务与当前持有者 |
| 读取主动推送 | `push.read_delivery` | `android_read_delivery` | `deliveryId` (`delivery_[0-9a-f]{32}`)、`eventId` (`push_[0-9a-f]{64}`)、`payloadDigest` (`sha256:[0-9a-f]{64}`)；可选 `taskSessionId` / `resume` |
| 确认消费推送 | `push.ack_delivery` | `android_ack_delivery` | 须与 `read_delivery` 同主体且使用新 `request_id`；原子清理手机正文 |

### 2.2 屏幕感知与控制（`ui.*`）

> 屏幕控制动作（`ui.tap` 至 `ui.launch_app`）在 MCP 中必须传显式 `taskSessionId`，且所有 `ui.*` 控制动作均需最新 `snapshotId`。

| `op` | MCP 工具 | `args` 字段与校验规则 |
| --- | --- | --- |
| `ui.dump_tree` | `android_get_ui_tree` | 可选：`operation`（`snapshot` \| `search` \| `state`，默认 `snapshot`）、`maxNodes`（1..512，默认 128）、`maxDepth`（0..32，默认 24）、`maxBytes`（4096..48000，默认 48000）、`text`、`resourceId`、`description`、`previousHash`；首次可省 `taskSessionId` |
| `ui.capture_som` | `android_capture_som_screenshot` | `{}`（严格空对象）；首次可省 `taskSessionId`；Android 34+ 窗口排除悬浮窗后绘制 Canvas SoM 并输出 WebP artifact；低于 API 34 返回 `SCREEN_OVERLAY_EXCLUSION_UNSUPPORTED` |
| `ui.tap` | `android_tap` | 必选 `snapshotId`；`nodeId` 与 `(x, y)` 二选一互斥（坐标非负整数） |
| `ui.long_press` | `android_long_press` | 必选 `snapshotId`；`nodeId` 与 `(x, y)` 二选一互斥；可选 `durationMs`（50..3000，默认 600） |
| `ui.swipe` | `android_swipe` | 必选 `snapshotId`、`x`、`y`、`endX`、`endY`；可选 `durationMs`（50..3000，默认 400） |
| `ui.drag` | `android_drag` | 必选 `snapshotId`、`x`、`y`、`endX`、`endY`；可选 `durationMs`（50..3000，默认 800） |
| `ui.input_text` | `android_input_text` | 必选 `snapshotId`、`nodeId`、`text`（最长 4096 字符，允许空串） |
| `ui.scroll` | `android_scroll` | 必选 `snapshotId`、`nodeId`、`direction`（`forward` \| `backward` \| `up` \| `down` \| `left` \| `right`） |
| `ui.global_action` | `android_system_nav` | 必选 `snapshotId`、`globalAction`（`back` \| `home` \| `recents` \| `notifications` \| `quickSettings`） |
| `ui.launch_app` | `android_launch_app` | 必选 `snapshotId`、`packageName`（标准包名格式，长度 <= 256） |

---

## 3. 离散数据能力（`plugin.invoke`）与 Schema

通过 `plugin.invoke` 调用已验证 `.alp` 插件（要求 `pluginId`、`capabilityId`、`method`、`params` 四字段精确匹配，且 `method` 必须等于 `capabilityId` 去掉 `@version` 后的最后一段）。

### 3.1 常用插件能力与 MCP 封装

| MCP 工具 / 场景 | `pluginId` | `capabilityId` | `method` | `params` Schema（`additionalProperties: false`） |
| --- | --- | --- | --- | --- |
| `android_query_sms` | `org.openandroidintelligence.sms` | `org.openandroidintelligence.sms.query@1.0.0` | `query` | `limit` (1..100)、`query` (<=2048)、`receivedAfter` (ISO date-time)、`senders` (string[], <=100 项) |
| `android_read_notifications` | `org.openandroidintelligence.notifications` | `org.openandroidintelligence.notifications.query@1.0.0` | `query` | `limit` (1..100)、`query` (<=2048)、`receivedAfter` (ISO date-time)、`packageIds` (包名数组, <=100 项) |
| `android_query_call_log` | `org.openandroidintelligence.call-log` | `org.openandroidintelligence.call-log.query@1.0.0` | `query` | `limit` (1..100)、`query` (<=2048)、`receivedAfter` (ISO date-time)、`directions` (`incoming` \| `outgoing` \| `missed`, <=3 项去重) |
| `android_send_sms` | `org.openandroidintelligence.sms` | `org.openandroidintelligence.sms.send@1.0.0` | `send` | **必选** `subscriptionId` (>=0 整数)、`recipient` (1..256)、`text` (1..65535)；强制本机确认，缺失回执记为 `outcome_unknown` |
| `android_invoke_capability`（定时短信） | `org.openandroidintelligence.sms` | `org.openandroidintelligence.sms.schedule@1.0.0` | `schedule` | **必选** `operation: "schedule"`、`runAt` (date-time)、`query` (`{ limit?, query?, receivedAfter? }`) |
| `android_invoke_capability`（查定时任务） | `org.openandroidintelligence.sms` | `org.openandroidintelligence.sms.job-status@1.0.0` | `job-status` | **必选** `operation: "job-status"`、`jobId` (`^[A-Za-z0-9._~-]{1,128}$`) |
| `android_invoke_capability`（取消定时） | `org.openandroidintelligence.sms` | `org.openandroidintelligence.sms.cancel@1.0.0` | `cancel` | **必选** `operation: "cancel"`、`jobId` (`^[A-Za-z0-9._~-]{1,128}$`) |

> 注意：`org.openandroidintelligence.screen-reader`、`screen-capture`、`screen-control` 不能通过 `plugin.invoke` 调用，必须走第 2.2 节的 `ui.*` 原生控制操作。

---

## 4. 状态枚举与常见错误排查

### 4.1 请求终态与执行状态

- `state`：`accepted`、`awaiting_confirmation`、`running`、`completed`、`failed`、`denied`、`cancelled`、`outcome_unknown`
- `execution.status`：`not_started`、`succeeded`、`failed`、`unknown`
- 若 `execution.status === "succeeded"` 但 `ui.settled === false`（`reason: "timeout"`），表示动作已注入但界面在 3000ms 内仍在变化，**不要重复执行同一动作**，应重读树核对当前界面。

### 4.2 常见错误码与处置

| 错误码 | 原因说明 | 正确处理方式 |
| --- | --- | --- |
| `PHONE_WORKSPACE_UNAVAILABLE` | 目标设备未在线或未暴露 `harness / phone-capabilities` | 让用户确认手机端已登录同一 Remote 账号且 Virtual Host 在线 |
| `BRIDGE_AUTHORITY_CHANGED` / `REMOTE_AUTHORIZATION_CHANGED` | DSH 注销、切换账号/Server 或连接代际已更新 | 停止旧桥任务；重新登录 DSH 并重新生成 `oai-android-mcp` 私有配置 |
| `TASK_SESSION_REQUIRED` | 除首次读树/截图/投递读取外未传 `taskSessionId` | 先调用 `android_get_ui_tree` 或 `remote_create_session` 获取任务 ID（或对单步数据查询显式传 `"control-bus"`） |
| `REQUEST_ID_CONFLICT` | 同一 `request_id` 复用了不同的任务或参数 | 若查原请求请保持原任务与原参数；新操作请生成新 `request_id` |
| `SNAPSHOT_STALE` | 窗口、前台应用、旋转或布局已变化，旧快照失效 | 重新调用 `ui.dump_tree` / `android_get_ui_tree` 获取新 `snapshotId` |
| `CONTROL_TARGET_CONFLICT` | `ui.tap`/`ui.long_press` 同时传入或均未传 `nodeId` 与 `x/y` | 只传 `nodeId`，或只传成对的 `(x, y)` |
| `SCREEN_OVERLAY_EXCLUSION_UNSUPPORTED` | Android 系统低于 API 34，窗口截图无法可靠排除确认浮窗 | 明确向用户说明设备版本不支持安全 SoM 排除，改用语义树 `ui.dump_tree` |
| `SCREEN_NATIVE_OPERATION_REQUIRED` | 试图经 `plugin.invoke` 调用屏幕插件 | 改用 `ui.*` 操作或对应的 `android_*` 屏幕工具 |
| `LOCAL_USER_REJECTED` / `CAPABILITY_DENIED` | 手机用户在悬浮确认拒绝，或本机能力未启用/权限缺失 | 尊重用户拒绝；如需使用，引导用户在手机 App 开关或系统权限（无障碍/通知/短信）中手动开启 |
| `BACKGROUND_PERMISSION_REQUIRED` | 手机锁屏或处于后台，且该能力未开启后台许可 | 锁屏拒绝所有 `ui.*`；数据能力需手机解锁或由用户手动授予后台许可 |
| `LOCAL_EMERGENCY_STOP` | 手机触发了悬浮急停（Kill Switch） | 停止全部任务与后续读取，提示用户已急停 |
| `SMS_SEND_OUTCOME_UNKNOWN` | 短信发送进入系统端口后中断或缺少全部分段回调 | **禁止自动重发**，告知用户短信发送结果未知并由用户核对发件箱 |
