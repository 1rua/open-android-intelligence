# Android 屏幕能力：CLI、API 与 MCP 接口

**2026-10-28 授权规则**：设备能力由 Android 唯一授权，已安装且启用插件的普通前台能力默认开启；Core/Agent Gateway 不追加账号能力许可。系统屏幕权限、本地 READ/CAPTURE/CONTROL 许可和逐次敏感确认继续生效。规则已在 `feature/android-device-authority-20261028` 实施，待 PR 合入 `dev`。以下远程 API 示例不代表真实设备验收已经通过；当前验证范围见[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。

本文记录屏幕能力的公开调用契约。基线源码已接入 Android 的“屏幕读取与控制”设置页、
本地屏幕会话开始/撤销、无障碍设置和独立系统投屏授权。代码依据与调用顺序见
[前端交接说明](android-screen-frontend-handoff.zh-CN.md)。当前 feature 在 Android 唯一授权模型下保留本地屏幕许可；
没有有效本地授权时继续安全拒绝，源码有入口不等于真机验收通过。

## 调用链与能力隔离

调用链统一为 `Agent → OAI CLI/API/MCP → OAI Core → Android → Android 系统能力`。
CLI 与 MCP 均使用 `CoreClient` 请求既有设备 API；Core 沿用同一账号、配对、受限设备凭据、
签名设备协议、事件通道、请求队列、Claim 回执与结果确认。没有新增监听器、端口或 WebSocket，
也没有修改原生 Agent 会话接入。

| 插件 ID | 能力 ID（版本均为 `1.0.0`） | 内核原语 | 用途与风险 |
| --- | --- | --- | --- |
| `org.openandroidintelligence.screen-reader` | `org.openandroidintelligence.screen-reader.read` | `kernel.screen.read` | 读取或搜索有界 Accessibility 语义树，`read` |
| `org.openandroidintelligence.screen-capture` | `org.openandroidintelligence.screen-capture.capture` | `kernel.screen.capture` | 经本地许可与 MediaProjection 授权截图，`read` |
| `org.openandroidintelligence.screen-control` | `org.openandroidintelligence.screen-control.control` | `kernel.screen.control` | 执行明确的 Android 操作，`write`，每请求本地确认 |

三个独立受保护插件各自只声明一个内核原语。能力 ID 必须属于自身插件命名空间，不能把
不同原语放进一个包后仅靠自然语言区分权限。仅安装不启用插件；启用后其能力默认开启，
无需额外账号开关或重复配对 grant。有效的连接认证和设备绑定、Android 系统权限、
本地屏幕许可及控制逐次确认仍分别生效，启用不能替代这些条件。

公开参数 Schema 是 `plugins/schemas/screen-read.json`、`screen-capture.json` 与
`screen-control.json`，随已签名 `.alp` 发布；Android 经既有能力发布机制向 Core 注册实际
Schema。设备协议的通用请求封装与核心 Schema 哈希未因新增插件而改变。

## 能力发现与权限状态

```sh
oai capabilities
```

对应 `GET /open-android-intelligence/core/v1/capabilities`；MCP 工具为
`oai_capabilities`。调用者先使用已配对设备取得短期受限凭据，方法见
[Core 安装与设备接口](../../oai-core/README.md)。每次发现结果中应核对：

- `capabilityId`、`capabilityVersion`、`provider` 与当前 `schema`；不要缓存过期提供者身份。
- `status` 与 `androidStatus`：只有 `READY` 并有实际提供者绑定时才具备调用条件。
- `gatewayEnabled`：旧 `dev` 基线中的账号开关字段，不作为本分支设备能力许可。不要引导用户寻找 Gateway setup；新旧版本协商与当前行为见[授权实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。
- `reason`：缺失本地许可、系统权限或服务时的诊断原因，不包含屏幕正文或输入内容。

旧 `dev` 基线的目录可能返回 `GATEWAY_DISABLED`，表示额外账号策略显式关闭；这不是 Android 系统权限诊断，也不是统一的 HTTP 错误码。新授权分支不再用此账号策略裁决，当前实现及兼容边界见[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。可用性仍受本地与同步原因限制，包括 `PLUGIN_NOT_INSTALLED`、`PLUGIN_DISABLED`、
`LOCAL_GRANT_REQUIRED`、`ANDROID_PERMISSION_REQUIRED`、`PROVIDER_UNAVAILABLE`、
`PROVIDER_NOT_SELECTED`、`SYNC_REQUIRED`、`ANDROID_STATE_UNKNOWN` 与 `PAIRING_REQUIRED`。
`SYNC_REQUIRED` 表示配对或授权版本已变化，必须等待 Android 发布新快照。
库存条目本身不是授权，缺少实际可调用绑定时 Core 拒绝入队。

屏幕读取还要求用户在系统设置主动启用 OAI 无障碍服务；截图要求本地独立 CAPTURE 许可、
系统 MediaProjection 授权和有效投影前台服务；控制要求独立 CONTROL 许可、目标窗口绑定和
针对原请求参数摘要的本地确认。远程参数不能传入本地授权、账号或设备身份来提升权限。

## 屏幕读取

以下 JSON 是能力请求中的 `parameters`。根对象与查询对象都拒绝未知字段。

| 参数 | 约束与含义 |
| --- | --- |
| `operation` | 必填，`snapshot`、`search` 或 `state` |
| `maxNodes` | 可选，1–512，默认 128，限制返回节点数 |
| `maxDepth` | 可选，0–32，默认 24，限制遍历深度 |
| `maxBytes` | 可选，4096–48000，默认 48000，限制整个返回 JSON 的 UTF-8 字节数 |
| `query` | `search` 必填；至少包含 `text`、`resourceId`、`description` 之一，每个字符串 1–256 字符 |
| `previousHash` | 可选，前次返回的屏幕哈希，用于得到 `changed` |

```json
{"operation":"snapshot","maxNodes":128,"maxDepth":24,"maxBytes":48000}
```

```json
{"operation":"search","query":{"text":"确定"}}
```

```json
{"operation":"state","previousHash":"上次响应的 hash"}
```

返回对象为 `ScreenSnapshot`：

```json
{
  "revision": "当前屏幕版本",
  "hash": "当前屏幕状态摘要",
  "changed": null,
  "display": {"widthPixels":1080,"heightPixels":2400,"rotation":0},
  "foregroundPackage": "com.example.app",
  "activeWindowId": 17,
  "windows": [{
    "id":17,"type":1,"layer":0,"active":true,"focused":true,
    "bounds":{"left":0,"top":0,"right":1080,"bottom":2400}
  }],
  "nodes": [{
    "nodeId":"示例节点引用","windowId":17,"parentId":null,"depth":0,
    "text":"确定","description":null,"resourceId":"com.example.app:id/confirm",
    "className":"android.widget.Button",
    "bounds":{"left":10,"top":20,"right":210,"bottom":100},
    "clickable":true,"longClickable":false,"scrollable":false,"editable":false,
    "focused":false,"selected":false,"enabled":true,"password":false
  }],
  "truncated": false
}
```

示例中的版本与节点引用仅说明字段，必须使用设备实际返回值。`revision` 是后续操作的
前置条件，节点引用只与该版本一起有效。屏幕旋转、显示尺寸或窗口代次变化后必须重新读取；
服务会在执行前重新定位节点并再次检查版本。`hash` 用于变化检测，不能充当授权。
`changed` 未提供前次哈希时为 `null`。`truncated=true` 表示达到预算，返回结果不是完整树。
弹窗、输入法及系统界面的可见信息取决于 Android 实际向无障碍服务开放的窗口；
不绕过密码控件、锁屏或系统限制。`rotation` 为 Android `Surface.ROTATION_*` 枚举值 0–3。

```sh
oai invoke org.openandroidintelligence.screen-reader.read --version 1.0.0 \
  --parameters '{"operation":"snapshot","maxNodes":128}' \
  --idempotency-key screen-observe-001 --wait 30
```

一次重新观察必须使用新的幂等键。复用旧键会返回旧请求的结果，不能据此判断最新屏幕。

## 截图与分块读取

截图使用既有 `ScreenCaptureSource` 与 MediaProjection 实现，不创建另一套采集框架。
受保护 WASM 调用的交换区是 64 KiB，因此不把整幅 PNG 塞入能力响应，而是返回短期图片句柄，
再通过同一个能力读取固定大小的片段。

| `operation` | 参数 | 返回 |
| --- | --- | --- |
| `capture` | 可选 `maxWidth`，64–2048 | `imageId`、`width`、`height`、`sizeBytes`、`sha256`、`mimeType`、`expiresAt`、`chunkSize` |
| `read` | 必填 `imageId`、`offset` | `imageId`、`offset`、`nextOffset`、`eof`、`data`（标准 Base64） |
| `release` | 必填 `imageId` | `{"released":true}`，释放幂等 |

`mimeType` 为 `image/png`；`sha256` 为完整 PNG 的 64 位小写十六进制摘要。
`expiresAt` 为 Unix 毫秒时间戳。图片只驻留 Android 内存，最大 8 MiB；最多保留 2 幅；
有效期不超过 60 秒，且不会晚于本地屏幕会话的到期时间。
`imageId` 是随机、不透明句柄，绑定原账号、配对、连接与授权版本以及本地会话，不能跨会话转用。
撤销、换账号或断连时清空像素并使旧句柄不可用。

`chunkSize` 为 24576 个原始字节，Base64 编码后仍小于交换区预算。
首次读取 `offset=0`，后续使用前次 `nextOffset`，直到 `eof=true`；中间偏移必须是
`chunkSize` 的整数倍，且小于 `sizeBytes`。先逐块解码、按顺序拼接，再核对完整长度和 SHA-256。

```sh
oai invoke org.openandroidintelligence.screen-capture.capture --version 1.0.0 \
  --parameters '{"operation":"capture","maxWidth":1024}' \
  --idempotency-key screenshot-001 --wait 30

# 取上一次已完成请求 result.data.result 中的 imageId，按返回的 nextOffset 继续读取。
oai invoke org.openandroidintelligence.screen-capture.capture --version 1.0.0 \
  --parameters '{"operation":"read","imageId":"设备返回的 imageId","offset":0}' \
  --idempotency-key screenshot-001-chunk-0 --wait 30

oai invoke org.openandroidintelligence.screen-capture.capture --version 1.0.0 \
  --parameters '{"operation":"release","imageId":"设备返回的 imageId"}' \
  --idempotency-key screenshot-001-release --wait 30
```

每个片段是独立设备请求，使用独立幂等键并及时消费结果。图片到期后需要新系统授权时，
由本地前端流程处理，远程调用不能自动弹出授权窗口。`release` 释放 Android 图片；
`oai ack REQUEST_ID` 清理 Core 中该请求的临时结果，两者生命周期不同。已读取片段在 Core
等待消费的期间仍受既有加密结果、凭据与授权版本校验保护，消费后应逐请求 ack。

## 屏幕交互

每次控制必填 `action` 和从最近一次读取取得的 `expectedRevision`。控制不包含 shell、ADB、
Root 或任意命令执行。优先把 `nodeId` 传给语义操作；坐标用于没有可靠语义节点的场景。

| `action` | 其他参数 | Android 执行含义 |
| --- | --- | --- |
| `click` | `nodeId` 或完整的 `x,y` 二选一 | 节点点击，或坐标点击 |
| `longPress` | `nodeId` 或 `x,y`；可选 `durationMs` | 节点长按，或坐标持续按压 |
| `swipe` | `x,y,endX,endY`；可选 `durationMs` | 有界滑动手势 |
| `drag` | `x,y,endX,endY`；可选 `durationMs` | 按住起点后继续同一手势拖动 |
| `scroll` | `nodeId,direction` | 指定容器语义滚动；方向为 `forward/backward/up/down/left/right` |
| `setText` | `nodeId,text` | 可编辑节点 `ACTION_SET_TEXT`，文本最多 4096 字符 |
| `global` | `globalAction` | `back/home/recents/notifications/quickSettings` 中的系统全局操作 |
| `launchApp` | `packageName` | 使用 Android 包管理器取得启动 Intent 后启动指定已安装应用 |

坐标为当前显示区域的物理像素整数，并在执行时以当前宽高检查范围；Schema 的硬上限是
32768，超出当前实际屏幕边界仍拒绝。`durationMs` 范围 50–3000；默认长按 600、滑动 400、
拖动 800 毫秒。后台启动、无启动入口或系统不支持的操作返回明确失败，不绕过平台限制。

```sh
oai invoke org.openandroidintelligence.screen-control.control --version 1.0.0 \
  --parameters '{"action":"click","expectedRevision":"最近返回的 revision","nodeId":"最近返回的 nodeId"}' \
  --idempotency-key screen-click-001 --wait 30

oai invoke org.openandroidintelligence.screen-control.control --version 1.0.0 \
  --parameters '{"action":"global","expectedRevision":"最近返回的 revision","globalAction":"back"}' \
  --idempotency-key screen-back-001 --wait 30
```

控制能力返回对象包含 `performed`、`screen` 与 `stateError`，位于 `result.data.result`。`performed=true` 表示 Android 已接受
本次操作；操作后的屏幕观察为 `screen`，不保证目标应用已经完成业务操作或动画。
如操作已接受但后续观察失败，保留 `performed=true` 并返回 `stateError`，不能自动重试操作。
需要确认业务结果时，使用新请求重新读取屏幕。

## REST 与 MCP 的统一封装

设备 REST 前缀是 `/open-android-intelligence/core/v1`。已登录的客户端带受限 Bearer
凭据；写入请求带 `Idempotency-Key`。请求正文只包含能力标识、版本和参数：

```http
POST /open-android-intelligence/core/v1/device-requests
Authorization: Bearer <受限设备凭据>
Idempotency-Key: screen-observe-002
Content-Type: application/json

{"capabilityId":"org.openandroidintelligence.screen-reader.read","capabilityVersion":"1.0.0","parameters":{"operation":"snapshot"}}
```

响应 `data` 中的 `requestId` 是后续查询、取消与结果确认的唯一标识。
成功时能力返回对象位于请求状态的 `result.data.result`，例如语义树为
`result.data.result.nodes`、图片句柄为 `result.data.result.imageId`；
不要把外层请求回执、`result.outcome` 和能力自身返回值混为一层。
MCP 不新增各 Agent 专用工具，使用现有通用工具：

```json
{
  "name":"oai_invoke",
  "arguments":{
    "capability_id":"org.openandroidintelligence.screen-reader.read",
    "capability_version":"1.0.0",
    "parameters":{"operation":"snapshot"},
    "idempotency_key":"screen-observe-003"
  }
}
```

MCP 启动命令为 `oai mcp`，使用官方 SDK 的 stdio transport。
工具 `oai_request_status`、`oai_cancel_request`、`oai_ack_result` 均接受 `request_id`。
不接受 `accountId`、`deviceId` 或其他身份覆盖参数。

## 取消、超时与错误

```sh
oai status REQUEST_ID
oai cancel REQUEST_ID
oai ack REQUEST_ID
```

对应 `GET /device-requests/{id}`、`POST /device-requests/{id}/cancel` 与
`POST /device-requests/{id}/ack`。相同幂等键与相同能力、版本、参数返回同一请求；
参数不同返回 `IDEMPOTENCY_CONFLICT`。Claim 和 Android 本地执行记录共同保护重复副作用。
未知结果不能通过换幂等键再执行同一动作来“恢复”。

状态沿用 `pending`、`claimed`、`cancel_requested`、`succeeded`、`failed`、`denied`、
`cancelled`、`expired`、`outcome_unknown`。未 Claim 的取消可终止排队；
`cancel_requested` 仅表示已请求取消，无法证明已经派发的 Android 操作没有发生。
`--wait` 到期只停止客户端等待，不取消服务端请求；继续使用原 `requestId` 查询。
Core 沿用现有风险队列的过期预算，Android 同时检查请求期限、本地会话 TTL 与当前授权。
断连、重启或超时后若副作用结果不确定，返回或保留 `outcome_unknown`，禁止盲目重发。

HTTP 拒绝使用既有 `error.code`，如 `CAPABILITY_DENIED`、`SCHEMA_INVALID`、
`GRANT_STALE`、`PAIRING_REQUIRED`、`IDEMPOTENCY_CONFLICT` 和认证错误。
设备拒绝或失败通过原结果封装 `result.outcome` 与 `result.data.code` 返回；
如果外层内核首先拒绝则为 `result.data.code=CAPABILITY_DENIED`、具体原因在 `result.data.reason`，屏幕原因包括：

| 原因类别 | 代表错误 | 调用方处理 |
| --- | --- | --- |
| 本地授权 | `SCREEN_SESSION_REQUIRED`、`SCREEN_SESSION_EXPIRED`、`SCREEN_SESSION_BINDING_CHANGED`、`SCREEN_PERMISSION_REQUIRED` | 结束本轮控制，等待用户通过本地授权流程重新授予 |
| 系统服务 | `ACCESSIBILITY_SERVICE_UNAVAILABLE`、`SCREEN_DEVICE_LOCKED`、`SCREEN_WINDOW_UNAVAILABLE`、`SCREEN_DISPLAY_UNAVAILABLE` | 根据发现状态显示不可用，不自动授予系统权限 |
| 控件与版本 | `SCREEN_STALE_REFERENCE`、`SCREEN_NODE_NOT_FOUND`、`SCREEN_TARGET_NOT_INTERACTABLE`、`SCREEN_TARGET_NOT_EDITABLE`、`SCREEN_TARGET_NOT_SCROLLABLE` | 重新读取并由调用方重新决定动作，不自动点击替代目标 |
| 平台执行 | `SCREEN_COORDINATES_INVALID`、`SCREEN_ACTION_REJECTED`、`SCREEN_APP_NOT_FOUND`、`SCREEN_APP_LAUNCH_DENIED` | 修正参数或停止操作，遵循平台限制 |
| 图片生命周期 | `SCREEN_CAPTURE_AUTHORIZATION_REQUIRED`、`SCREEN_CAPTURE_FAILED`、`SCREEN_CAPTURE_ENCODING_FAILED`、`SCREEN_IMAGE_TOO_LARGE`、`SCREEN_IMAGE_LIMIT_REACHED`、`SCREEN_IMAGE_UNAVAILABLE` | 释放旧图、减少宽度或在有效授权下重新截图 |
| 本地控制确认 | 由既有 `ControlDenialReason` 映射的确认缺失、摘要不符、窗口变化、版本过期 | 必须重新走真实本地确认，远程参数不能代替确认 |

以实际设备结果中的错误值为准；不要把所有错误折叠成“截图为空”或“操作成功”。
日志只应记录请求 ID、阶段及错误码，避免记录屏幕文本、完整语义树、输入内容或图片 Base64。

## 代码入口与验证范围

| 模块或文件 | 职责 |
| --- | --- |
| `control-ports/ScreenContracts.kt` | 跨模块读取、节点、动作及结果结构 |
| `control-ports/ScreenLocalAuthorization.kt` | READ/CAPTURE/CONTROL 的本地会话、期限、撤销与确认 |
| `control-ports/ScreenHostLifecycle.kt` | 可信本地生命周期入口，禁止注册为远程授权能力 |
| `screen-control` | 无障碍服务、树遍历、窗口与版本检测、语义动作及手势 |
| `capability-ports` | 既有 MediaProjection 采集、前台服务及资源释放 |
| `device-primitives` | 内核原语、请求安全检查、图片短期句柄与分块响应 |
| `plugins/screen-*` | 各自独立的受保护 WASM 引用插件 |
| `oai-core/device_service.py`、`client.py`、`cli.py`、`mcp.py` | 现有统一调用面，未增加具体 Agent 适配逻辑 |
| `oai-core/tests/test_screen_capabilities.py` | 真实 HTTP、签名设备协议、CLI/MCP、幂等、取消、结果确认与 Schema 回归 |

自动化测试中的签名 Android 对端是协议测试替身；它证明 Core 调用链复用现有设备队列，
不能替代真实手机上的无障碍授予、MediaProjection 系统授权、前台服务、跨应用输入和旋转验证。
最终验收记录应分别列出本地自动化、Android 构建、远端 CI 与真机结果。
