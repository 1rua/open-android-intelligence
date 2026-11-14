---
status: accepted
date: 2026-11-13
implementation_status: not_started
acceptance_status: pending
issue: 185
upstream_commit: ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7
---

# OAI 手机控制 RPC v1

本契约是[架构重构规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)的目标接口，
供原生 DSH 工具、电脑本机 Bridge、Stdio MCP 和 Android 实现共用。
`accepted` 表示设计已经确认；运行时实现尚未开始，互操作、权限门禁和真机验收均待完成。
上游接口事实与证据见[固定基线](../architecture/2026-11-13-dsh-remote-baseline.zh-CN.md)。

## 1. 权威与会话边界

- 手机只运行一个确定性控制路由器，不运行本地 LLM。各入口的手机操作最终进入同一
  `platform-kernel` 身份校验、`policy-engine` 裁决和原生/WASM 执行端口。
- 单一活动 Remote 账号拥有独立 Client 与 Virtual Host 两套设备身份、密钥和凭据；
  后者默认仅被控，不能因主动聊天客户端休眠而丢失待命连接。
- Virtual Host 固定暴露工作区 `phone-capabilities` 和常驻会话 `control-bus`。
  每个独立任务创建新的任务 Session；后续步骤使用该任务的 Session，不能把不同任务的
  消息、审批、节点引用和结果合并到全局 `control-bus`。
- `control-bus` 用于发现控制能力、单步快速调用与建立任务关联。新建独立任务必须走真实
  `session/create`，返回独立 Session ID；不把每次创建请求伪装成同一个 `control-bus`。
  总线中的请求按可信拥有者与 request_id 隔离，不隐式继承前一次请求的审批、快照或任务上下文。
  总线单步屏幕动作仍须显式提供绑定同一可信拥有者与 control-bus 的有效 snapshotId；
  可以复用当前进程尚有效的显式快照引用，禁止用全局“最近快照”自动补齐。快照失效照常拒绝。
  多步操作推荐创建独立任务，任务快照不得转用于总线或另一任务。
- 控制任务的消息、Prompt、轮次、语义树、截图与结果正文只保存在当前进程内存，不写入
  聊天历史或持久执行队列；进程重启后不恢复旧任务、不回放、不自动续做。正常聊天镜像、
  主动推送队列与短信定时任务仍按各自已确认功能加密持久，不受此控制任务边界替代。
- 持久化的旧任务拥有者关联和失效标记仅用于最小回执查询及防止旧 ID 被冒用，不恢复
  旧任务 Session 的可执行状态。旧 taskSessionId 不接受后续业务 Prompt；新工作建立新任务。
- 任务拥有者来自已认证 Remote 对端的身份与本机授权，不能来自 Prompt 中自报的
  `account`、`principal` 或设备 ID。任务建立时绑定 Remote Server origin、双方设备身份、
  本机授权代际和任务 Session。执行、取消、查询和文件读取都重新核对这一绑定。
- `connectionId` 是临时通道标识；重连后的同一受信任拥有者可重新核验后查询原任务，
  不能把旧连接审批句柄转移给新连接，也不能让其他同账号设备接管任务。
  持久去重以逻辑拥有者和 request_id 为键，不以 connectionId 为键；连接代际只负责活跃
  请求与审批有效性，不能借重连把同一指令当作新请求执行。
- 屏幕变更动作按手机全局互斥执行；其他任务可收到等待状态，但只有持有本次执行权的
  动作可以注入手势。离散数据读取按既有端口规则运行，不因共享 Session 而获得屏幕权限。

## 2. 唯一请求格式与操作映射

业务载荷是 UTF-8 JSON 对象，最小形式如下。字段名称固定，`args` 必须匹配 `op` 的类型。

```json
{
  "request_id": "f4b76bb2-d134-419f-a948-65059486660b",
  "op": "ui.tap",
  "args": {"snapshotId": "snapshot-a", "nodeId": "12"}
}
```

`request_id` 由发起端在首次发送前生成并保存；使用上游 operationId 允许的
`[A-Za-z0-9_-]{16,128}`，使本契约请求与上游发送回执可以保持同一个标识。
创建任务、发送指令和取消任务是不同操作，各自有自己的 operationId；不能把创建回执
的 ID 复用作发送 ID。未知字段、未知操作、冲突的定位参数和不符合类型的值必须在执行前拒绝。
账号、连接及风险等级不能由载荷覆盖，也不接受任意方法名、Shell 或动态代码。

CLI 简写只是同一解析器的另一种输入，语法为
`<op> --request-id <request_id> <JSON args对象>`，例如：

```text
ui.tap --request-id f4b76bb2-d134-419f-a948-65059486660b {"snapshotId":"snapshot-a","nodeId":"12"}
```

解析后必须生成完全相同的规范化信封，再做相同的参数、身份、幂等和权限校验。
不使用 `sh`、`eval`、环境变量展开、命令替换或管道；正文不是受支持语法时返回错误，
不能转交 LLM 猜测意图。JSON 的字符串转义是文本表示，不是 Shell 转义。

| 控制操作 | 必需参数与结果 | MCP 工具 |
| --- | --- | --- |
| `ui.dump_tree` | 读取当前语义快照，返回 `snapshotId`、窗口、显示几何和节点列表；可保留现有原生查找/状态读取参数 | `android_get_ui_tree` |
| `ui.capture_som` | 按需采集并标注当前快照，返回同一 `snapshotId` 的节点映射和受限 WebP artifact | `android_capture_som_screenshot` |
| `ui.tap` | `snapshotId`；`nodeId` 或完整 `x/y` 坐标，二者互斥 | `android_tap` |
| `ui.long_press` | 同点击；可传原生端口接受的 `durationMs` | `android_long_press` |
| `ui.swipe` | `snapshotId`、`x/y/endX/endY`；可传 `durationMs` | `android_swipe` |
| `ui.input_text` | `snapshotId`、可编辑 `nodeId`、`text`；不得向未经核对的当前焦点盲输 | `android_input_text` |
| `ui.global_action` | `snapshotId`、`globalAction`；沿用 `back/home/recents/notifications/quickSettings` | `android_system_nav` |
| `ui.scroll` | `snapshotId`、可滚动 `nodeId`、原生支持的 `direction` | `android_scroll` |
| `ui.drag` | `snapshotId`、`x/y/endX/endY`；可传 `durationMs` | `android_drag` |
| `ui.launch_app` | `snapshotId`、`packageName`，由原生端口校验目标 | `android_launch_app` |
| `plugin.invoke` | `pluginId`、版本化 `capabilityId`、`method`、`params`；按已验证 `.alp` 声明与内核端口执行 | 以下数据工具及通用已授权插件调用 |
| `request.status` | `targetRequestId`、`targetTaskSessionId`；经当前 control-bus 查询该可信拥有者的原最小回执，不恢复旧任务 | `android_request_status` |
| `request.cancel` | `targetRequestId`；仅取消同一任务内该可信拥有者的指定请求，返回原请求的真实状态 | `android_cancel_request` |

滚动、拖动、启动应用和时长参数来自现有
[屏幕端口](../../apps/android/control-ports/src/main/kotlin/com/openandroidintelligence/control/ScreenContracts.kt)，
用于保留已有能力；本契约不另设动作时长、节点数量、任务数量或文件大小的产品默认值。
平台与上游既有限制仍须校验并显式返回，不能静默截断或由客户端擅自加大。

数据工具只是 `plugin.invoke` 的有类型薄封装：

| MCP 工具 | 插件能力与边界 |
| --- | --- |
| `android_query_sms` | 现有 `org.openandroidintelligence.sms.query`；查询参数和脱敏沿用真实 schema |
| `android_read_notifications` | 现有 `org.openandroidintelligence.notifications.query`；系统权限与本机范围继续有效 |
| `android_query_call_log` | 现有 `org.openandroidintelligence.call-log.query`；不伪造缺失号码或权限数据 |
| `android_send_sms` | 目标新增的短信发送能力；只有真实 schema、发送端口、结果回执和强制本机确认全部落实后才可宣告可用 |

当前[短信插件声明](../../plugins/sms/manifest.json)只有读取、定时读取、任务状态和取消，
没有发送能力。不能将定时读取改名为短信发送；现有 `sms.schedule/job-status/cancel`
继续经 `plugin.invoke` 暴露。具体能力版本以安装的已验证插件目录为准，不能由 MCP 硬编码
“已安装”或绕过能力开关。WASM 不持有网络身份、不管理 Noise，也不执行屏幕高频遍历与手势。

## 3. 受理、持久回执与结果

在调用任何有副作用的端口前，手机必须原子记录请求标识、可信拥有者、任务 Session、
操作种类、规范化参数摘要、execution/state 和取消关联。账本只保存上述最小元数据，不能保存
Prompt、输入文本、短信/通知正文、完整树、截图或结果正文。上游工具的内存 operation ledger
不是手机的持久执行账本。请求进入副作用端口前先持久记录执行栅栏；该记录之后崩溃而无法
证明是否已进入端口时保守记为未知，不能通过重放取得确定结果。
同一拥有者与 request_id 重复提交相同任务和参数时只返回已有回执；改任务或参数返回
`REQUEST_ID_CONFLICT`，不能重新执行。

最小受理回执为：

```json
{
  "request_id": "f4b76bb2-d134-419f-a948-65059486660b",
  "taskSessionId": "task-a",
  "state": "awaiting_confirmation",
  "execution": {"status": "not_started"}
}
```

`state` 取 `accepted`、`awaiting_confirmation`、`running`、`completed`、`failed`、
`denied`、`cancelled` 或 `outcome_unknown`。`execution.status` 独立取
`not_started`、`succeeded`、`failed` 或 `unknown`。执行已经成功但界面未收敛的结果例如：

```json
{
  "request_id": "f4b76bb2-d134-419f-a948-65059486660b",
  "taskSessionId": "task-a",
  "state": "completed",
  "execution": {"status": "succeeded"},
  "result": {},
  "ui": {
    "settled": false,
    "reason": "timeout",
    "packageName": "com.example.app",
    "snapshotId": "snapshot-b",
    "nodes": []
  },
  "artifacts": []
}
```

`ui` 仅用于屏幕动作后的观测；读取操作在 `result` 中返回相应快照。无法取得新树时
`snapshotId` 为空且附带观测错误，不能把旧树冒充新快照。无 artifact 时返回空数组。
`completed` 与 Session 的 `idle` 均不证明 Agent 的业务目标已经达成；调用者必须读取
匹配 request_id 的结构化结果。拒绝、失效、取消和失败使用 `error:{code,message}`；
错误文案不泄露输入文本、短信正文、凭据或内部异常。

- 手动审批前不得进入执行。执行前再次核验授权代际、锁屏状态、快照及 task owner；
  审批期间参数或界面失效必须重新拒绝或重新发起本机确认，不能沿用旧批准。
- 执行前已确定没有进入端口的请求可回报 `not_started`。若进程崩溃、断线或端口超时后
  不能证明副作用是否发生，回执为 `outcome_unknown/unknown`，不能当作失败自动重发。
- 重启恢复时保留最小回执与未知事实，不恢复结果正文或执行队列。尚未进入执行栅栏的
  待执行/待确认请求结束为未执行；进入栅栏后无法证明副作用结果的请求记为
  `outcome_unknown/unknown`。已经持久确定的原终态保持原事实，不能因正文丢失改成执行失败。
  不通过新的 request_id 或重新创建任务绕过去重。账本不可用时拒绝新的副作用操作。
- 当前进程仍保有结果正文时 `resultAvailable:true`；正文未保留时为 `false`。后者不代表
  没有执行，不能返回伪造空结果、旧树或占位截图；读取正文应明确报告不可用。
- 只读 `request.status` 使用新的查询 request_id，其 `args.targetRequestId` 和
  `args.targetTaskSessionId` 指向原请求与原任务。查询只能从当前 `control-bus` 发起，
  以当前可信拥有者查账本并核对原任务；传入 ID 不授予访问权。原请求属于总线时原任务为
  `control-bus`。查询自己的回执与原回执不混用，原最小回执置于本次查询的 `result` 内：

  ```json
  {
    "request_id": "status-query-0001",
    "taskSessionId": "control-bus",
    "state": "completed",
    "execution": {"status": "succeeded"},
    "result": {
      "request_id": "original-request-0001",
      "taskSessionId": "original-task",
      "state": "completed",
      "execution": {"status": "succeeded"},
      "resultAvailable": false
    }
  }
  ```

  查询不恢复原 Session、审批、快照或正文，也不重新执行原操作。查询关联只存在当前进程，
  不递归产生新的持久任务历史。未找到不能被解释为从未执行；拒绝或查不到不得泄露他人任务。
  `session/follow/page` 与电脑内存中的 `remote_get_operation` 不能替代该手机账本查询。
- 按请求取消使用 `request.cancel`，取消信封有独立 request_id，`args.targetRequestId` 指向
  原请求。路由器在普通动作排队前处理该受限管理操作，并在同一原子状态转换中核对拥有者、
  任务及目标请求，标记取消并阻止其待执行步骤；不能把取消排在正在等待本机确认的动作之后。
  迟到取消只返回原请求终态或未知事实，不能取消同任务中已开始的下一请求，也不能转为 session/cancel。
  取消记录和回执同样持久化并去重；未知目标不猜测为当前请求。管理取消只减少执行权，
  不授予业务能力，不得要求先批准仍在等待确认的原动作才能取消它。
- 取消只阻止尚未开始的执行或请求原生端口停止后续步骤，不撤销已经完成的点击、发送
  或文件导出；无法确认停止时保留未知状态。原生 session/cancel 则明确取消整个任务 Session，
  冻结该任务后续请求，不关闭 control-bus 或影响其他任务。关闭等待或 MCP 进程退出不等于取消任务。
- 每个结果在当前进程中作为该任务会话的真实 Assistant 记录发布，并保留 request_id；
  这些记录不作为持久历史，重启后不恢复。活动执行、待人工处理和终态通过真实 Session
  投影同步，不能只来回切换 running/idle。
  任务等待本机确认时必须可观察为 `needs_user_input`，不能伪装成完成。
  为兼容现有工具的状态识别，实际轮次发出 `turn/start`、`turn/end`，本机待确认发出带唯一
  ID 的 `approval/asked`，本机决定后发出对应 `approval/decided`。这些记录仅展示本机门禁
  事实，不签发可由远端回答的设备授权句柄；远端审批回复不能代替手机点击确认。

## 4. 快照、动作等待与本机门禁

`snapshotId` 是一次观测的修订标识，不是节点的永久身份。每次快照绑定任务拥有者、
任务 Session、屏幕/窗口代际、前台包名、显示尺寸与旋转方向；节点索引、SoM 标号和坐标
只在该绑定内有效。同一快照重复读取使用相同映射，布局或显示代际变化后旧引用失效。
屏幕动作获得全局执行权后还要在实际注入前核对绑定，过期返回 `SNAPSHOT_STALE`，
不得自动在新界面重定位同名文本并点击。

语义树是扁平可交互节点列表，保留 `nodeId`、类型、可见文本/描述、必要状态、
`centerX/centerY` 和可定位几何。仅剪去无语义布局容器，不能丢失可访问名称或可执行目标。
密码、敏感输入和被策略遮蔽内容不能以节点正文或截图明文绕过脱敏。截断与未连接状态显式标记。

默认先读取精简树；节点不足或调用者明确请求时采集 SoM。标注由 Kotlin Canvas 本地绘制，
输出 WebP，并返回标号到 nodeId 的映射。截图、标号、节点和坐标必须来自同一有效屏幕代际；
采集期间变更则报告失效，不能拼接两次观测。坐标使用快照显示平面的像素，限定在其可用区域；
旋转、缩放、截图裁剪信息必须可对应，不能将压缩图坐标直接当作原屏坐标。
标号只能绘制在可证明的真实节点边界上；游戏或图像界面没有有效节点时，不推测控件框。
结果报告语义节点数、实际标记数与无节点/部分覆盖状态，仍可返回已获截图授权的真实图像，
供电脑 Agent 进行视觉判断并使用绑定该 snapshotId 的坐标。手机不新增本地 LLM 或图像识别服务。

点击、长按、滑动、滚动、拖动、输入、导航或启动应用执行后，控制端口从实际动作完成开始
监听 `TYPE_WINDOW_CONTENT_CHANGED` 与 `TYPE_WINDOW_STATE_CHANGED`：连续 400ms 没有相关布局
变化时返回 `settled:true/reason:quiet`；达到 3000ms 硬超时则返回
`settled:false/reason:timeout`，仍附带能获取的最新前台包名与语义树摘要。
持续动画、无障碍事件缺失或截图失败不能改写动作执行结果，超时不能触发动作重试。
无障碍与可选 Shizuku 引擎必须共用该状态机、执行互斥和门禁，不能以更换引擎绕过拒绝。

新能力默认每次手动。用户在手机明确选择允许只读后，仅批准范围内的数据读取免逐次确认；
短信发送、敏感输入及其他高风险动作始终要求本机手动。聊天中的 Agent Approval 是后端
工具审批，不能替代手机的设备能力授权，也不能由 MCP 自动回答手机确认。

确认、执行状态和 Kill Switch 通过无障碍服务的 `TYPE_ACCESSIBILITY_OVERLAY` 呈现，
不得拉起主 Activity 抢走第三方应用焦点。胶囊及其文本、按钮不进入远端语义树、SoM 或截图；
捕获路径不能可靠排除时拒绝本次捕获，不能上传含审批界面的图。所有远端坐标与节点操作
均拒绝命中胶囊区域，远端不能点击“允许”或 Kill Switch。
Kill Switch 在手机本地立即使屏幕执行授权失效、停止后续注入并拒绝待执行动作；
正在发生且不可撤销的副作用据实记录，不把紧急停止描述成回滚成功。

语义读取、图像采集与屏幕控制分别裁决权限。节点不足时自动转 SoM 仍必须具备图像采集
许可；只有树权限不能顺带获取截图，控制许可也不能隐含授予新的图像读取范围。

锁屏时 Virtual Host 可保持出站信令。只有另有明确后台授权且系统允许的数据能力可执行；
语义树读取、截图、手势和屏幕输入一律拒绝，不能通过 Shizuku 或唤起 Activity 绕开。
没有能力、系统权限、有效任务授权或可用执行端口时默认拒绝，不能返回伪造空成功。

## 5. Session V3 与文件载体

`remote_*` 是电脑本机工具名称，不是手机收到的网络 RPC。Virtual Host 必须提供与固定
上游工具真实调用路径互操作的最小 Session V3 门面，不增加旧 Gateway RPC：

| 上游载体/端点 | Virtual Host 目标行为 |
| --- | --- |
| `harness.transport.describe` | 仅声明实际实现的 Session V3 与控制工作区能力，不宣告虚构 Harness 运行时或未实现后端 |
| `harness.remote.call/stream.*` | 传递受限 Typert carrier；调用、stream 与关闭按 connectionId 隔离 |
| `workspace/follow` | 输出真实 baseline，包含 `phone-capabilities`、私有根和可见任务 sessionIds |
| `session/list` | 返回 control-bus 与该可信拥有者可见的任务、运行状态及真实元数据 |
| `session/create` | 在该工作区建立独立空任务，持久记录拥有者，返回新 Session ID |
| `session/prompt` | 接收 `mode:queue` 的文本块，解析 JSON/CLI 控制信封；不执行自然语言或调用 LLM |
| `session/follow`、`session/page`、`session/control` | 提供 Session V3 真实基线、游标、记录、执行与待输入投影；按请求读结果 |
| `session/cancel` | 只接收 sessionId，取消整个所属任务并冻结其后续请求；返回受理而非伪造已停止，不能充当按 request_id 取消 |
| `workspaceFiles/stat/readBytes` | 按任务 Session scope 读取本任务 artifact，返回并校验文件版本、大小、块偏移和结束标记 |

现有 `remote_send_message` 实际转为 `session/prompt`，其 operationId 对应信封 request_id；
Typert 外层 requestId、Remote message ID 和 connectionId 保留各自传输语义，不能混为幂等键。
`remote_wait_sessions` 只观察状态；必须再 `remote_read_session` 按 request_id 取回结果。
官方工具的 `remote_get_operation` 回执仅在电脑当前 runtime 内存中存在，手机持久回执才负责
防止跨重启重复执行。工具原生的发送大小、等待期限等限制是上游要求，不在本契约另设一套值。
门面使用真正的 Session V3 header、seq/cursor、`assistant-stream` 与最终
`assistant/message.data.stream`，不以自定义 running 布尔代替这些载体。

截图或导出文件写入手机应用私有的临时虚拟工作区根，并关联任务和请求。文件只属于当前
进程的任务生命周期，不作为控制历史持久回放；重启后旧 artifact 引用失效且不能被恢复为可读。
临时存储残留不授予访问权。正常聊天媒体镜像与用户显式导出遵循其独立保留语义。
结果仅给出 artifactId、
工作区相对 path、mimeType、bytes 与版本；只允许合法任务拥有者通过 scoped 文件面读取。
拒绝父目录跳转、绝对手机路径、跨任务引用和符号链接越界；不开放手机任意文件系统。
`remote_download_file` 调用 `workspaceFiles/stat/readBytes`，不是凭一个 URL 下载。
查询结果和文件存活状态必须真实；文件已不可用时明确返回，不用旧截图占位。
上游 `remote_read_session` 读取的是有界历史摘录，长单条记录会标记 `truncated`。
完整树或其他结构化结果超过其既有可读预算时，将完整 JSON 写为同任务的受限 artifact，
Assistant 记录保留可完整读取的 request_id、执行结论和文件引用；不能截断 JSON 后仍声称
已返回完整树。MCP 与原生入口都能继续按 artifact scope 取得完整结果。

## 6. 本机 Bridge 与手机 MCP

Bridge 是安装在电脑 DSH 进程内的 OAI 适配插件。Stdio MCP 由各 Agent 启动，
只调用 Bridge；不读出 Remote 私钥/token，不另建账号、穿透、Relay 或手机执行器。
复用 DSH 既有 WebServer 注册 loopback 专用 `/oai-android-mcp/v1` 命名空间，
沿用 Connection 的本地请求校验，并加当前 Bridge 运行期随机 bearer。
token 通过用户私有配置交给 Stdio 客户端，不进入 URL、浏览器、Prompt 或日志。
Bridge 绑定当前 Remote Server origin 与活动账号代际；退出、切换 Server 或卸载使旧授权失效。
HTTP Host/Origin 校验不因“本机调用”关闭；无浏览器 Origin 的 Stdio 客户端仍必须提交 bearer。
本机接口不跨源开放，也不成为对外发布的鉴权服务。

固定本机操作为 `status`、`list_endpoints`、`start`、`request_status`、`cancel`、
`read_artifact`，分别用于版本/可用性、已授权目标、受理有类型请求、查询原回执、请求取消、
读取本任务产物。`start` 在发送前返回或持久保存调用者可查询的 request_id 与任务关联；
RPC 等待中断后仍能用同一标识查状态。`request_status` 通过当前 control-bus 的
`session/prompt` 发送 `request.status`，原 task ID 只核对原回执归属；旧任务不必且不得恢复。
`read_artifact` 不接受任意文件路径。
接口只接受本契约的手机操作，不提供任意 namespace/method 代理或后台 Agent 委派。
所有 MCP Android 工具映射到上述操作；另暴露 `android_request_status` 和
`android_cancel_request`，保证审批等待、长任务和未知结果可以继续查询与停止。
Android 的 Bridge `cancel` 根据原回执确定任务与 `targetRequestId`，通过同一任务的
session/prompt 发送 `request.cancel` 信封；原生 remote_interrupt_session 仍采用整任务语义。
两者不能混用，也不能通过“先查请求再 session/cancel”的客户端竞态模拟精确取消。
MCP 只暴露 `android_*`，发现与选择的目标仅为 OAI Virtual Host 的 `phone-capabilities`；
Bridge 不保存或派发后台跨 workspace 的 Agent 任务。手机账本仍是手机请求执行、防重和取消的权威。
MCP 子进程不持有执行权威状态，进程重启后经 Bridge 查询最小回执；未知写入不得自动重发。

跨 workspace 后台调度属于上游，不作为本客户端 P0/P4 或发布的前置条件。
正常聊天中用户可随时手动切换 Host、DSH/Codex/ACP workspace 与对话。
客户端对每个选择建立独立连接或连接代际，切换时取消并排空旧 stream/pending-open，
拒绝旧结果更新新目标；已发送而结果未明的写入保持未知，不能重放、改投或自动抢回选择。
这类手动切换的本地保护必须由客户端实际实现与回归证明，不由历史后台委派探针替代。

各 Agent 通过电脑端原生配置挂载 Stdio MCP。上游 Remote ACP 的 `session/new/load`
固定使用 `mcpServers:[]`，不能声称可从手机会话参数注入 MCP。
不同后端的 Prompt、图片、模型、effort、审批和中断以真实 capability 与确认结果为准。
模型/effort 目录可能属于 Session，不能用 Endpoint 缓存替代当前会话确认状态。

MCP 首次 `android_get_ui_tree` 或截图调用可以真实创建任务，并返回 `taskSessionId` 与
`snapshotId`；后续读取、截图和动作显式携带原 `taskSessionId`，动作同时携带对应
`snapshotId`。动作缺少任务时不得暗中新建任务。并发任务分别 create，不以 MCP 进程中
全局“最近任务”选路。截图生成新快照时返回其新 ID 和对应节点映射。旧任务在重启后只可
查询最小回执，不可继续动作。`control-bus` 单步调用不隐式选择前一次快照；调用者可显式
引用仍有效且绑定同一可信拥有者/control-bus 的 snapshotId，所有快照与执行门禁保持不变。

## 7. 必须通过的验收

以下均为待验收条件，不是本次文档提交已运行的测试：

- 两种电脑入口对同一任务发出相同信封，命中同一 kernel 执行器和持久回执；JSON/CLI
  对未知字段、Shell 片段和冲突参数同样拒绝，长按与现有滚动/拖动/启动应用不丢失。
- 使用未修改上游原生工具完成工作区发现、真实任务创建、发送、等待、按 request_id
  读取结果及 `stat/readBytes` 下载；并发 stream、关闭、任务 owner 与文件 scope 不串线。
- 同任务 A 已完成、B 正在执行时迟到取消 A，只返回 A 的原终态；等待本机确认的 A 可按 ID 取消，
  取消不能被动作队列阻塞。原生整任务取消冻结后续请求，MCP 精确取消不误停 B。
- 内存历史不落盘，重启不回放/续做；旧 task 仅能从新总线查询最小回执，正文未保留不伪造结果。
- MCP 树→截图→动作复用显式 task/snapshot，并发任务不串线；动作缺失任务或使用重启前快照拒绝。
- 相同 request_id 重复、参数冲突、任务冲突、发送回执丢失、执行前/后崩溃、进程恢复、
  账本失败、取消竞态均不重复副作用；unknown 查询不被当作“尚未执行”。
- 首次手动、范围内只读免逐次确认、敏感强制确认、系统权限撤销、锁屏与授权代际变化；
  Remote Approval 不能替代手机批准，远端节点/坐标无法操作确认胶囊。
- 控制胶囊不抢焦点、不进入树/截图；无法排除的捕获显式失败。Kill Switch 可阻止后续
  注入，原生与 Shizuku 共用门禁；原生运行、前后台和锁屏真机分别验收。
- 快照跨任务、布局变化、旋转、缩放和 SoM 编号重用均拒绝旧引用；400ms 静默、3000ms
  超时、持续动画及读树失败保留动作真实结果，超时不触发重试。
- Bridge 错误 bearer、非本地/跨源请求、登出、Server 切换与旧进程凭据失效均拒绝；
  外部 Agent 的 MCP 实际加载经过各后端验证，不以空 mcpServers 创建成功代替挂载验收。
- MCP 工具白名单仅含 android_*，拒绝非手机 Host、其他 workspace 和任意 Agent 调用；
  正常聊天的 Host/workspace/对话手动切换核对独立连接、代际、旧流取消排空及迟到结果拒绝。
