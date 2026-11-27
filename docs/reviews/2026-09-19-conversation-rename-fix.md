---
date: 2026-09-19
title: 会话重命名失效缺陷修复记录（手动重命名与自动标题）
scope: gateway-client / conversation-ui / Hermes / OpenClaw / 协议契约
---

# 会话重命名失效缺陷修复记录

## 1. 现象

App 内会话管理的手动重命名和首条消息的自动标题都不生效：界面短暂显示新标题，切换会话或重新
打开后回到原样；Gateway 侧标题从未改变。

## 2. 根因

重命名在客户端是唯一一条 `PATCH /open-android-intelligence/v2/conversations/{conversationId}`
请求（`ConversationClient.updateConversationTitle`），而这条请求在**三处**同时被挡住：

1. **签名方法集不含 PATCH**：契约 §6.2 的 method 文法只有 `GET/POST/PUT/DELETE`，
   Hermes 的 `core.request_signature_preimage`（`core.py:419`）与
   `adapter.GatewayRequestVerifier._verify`（`adapter.py:310`）都按该闭集校验，PATCH 一律
   验签失败 → HTTP 401 `AUTHENTICATION_REQUIRED`。Kotlin 的 `RequestSigner.METHODS`
   早已允许 PATCH，三个实现因此长期不一致。Hermes 的 `core.handle` 其实已实现 PATCH 路由，
   但只有绕过验签的核心级测试覆盖到它（`test_conversation_patch_updates_title_and_appends_event`），
   所以缺陷一直没被真实链路暴露。
2. **OpenClaw 完全没有该能力**：`GatewayHttpMethod` 与 `rawRequestMethod` 只有
   GET/POST/PUT/DELETE，`ConversationPort` 也没有更新标题的方法。
3. **客户端把失败伪装成成功**：`WorkbenchController.renameThread` 与自动标题分支先做本地
   乐观更新，随后忽略 `updateTitle` 的布尔结果，界面因此显示一个 Gateway 从未保存的标题。

## 3. 修复

| 位置 | 变更 |
|---|---|
| `integrations/hermes/.../core.py` | `request_signature_preimage` 方法闭集加入 `PATCH` |
| `integrations/hermes/.../adapter.py` | 验签方法闭集加入 `PATCH`（HTTP 边界本已放行，两处自此一致） |
| `integrations/openclaw/src/http/routes.ts` | `GatewayHttpMethod`/`rawRequestMethod`/`toVerifiedRequest` 支持 `PATCH` |
| `integrations/openclaw/src/core/conversation-port.ts` | 新增 `updateTitle`：存在性校验 + 行更新 + `conversation.title.updated` 审计，同一事务 |
| `integrations/openclaw/src/core/gateway-core.ts` | 新增 PATCH 会话路由，写标题并追加 `conversation.title.updated` 事件 |
| `gateway-contract/src/request-signature.ts` | 签名 method 集合同步加入 `PATCH` |
| `docs/contracts/gateway-protocol-v2.md` | §6.1 幂等绑定、§6.2 method 文法、§7 端点与请求/响应/事件、§9 V2 事件类型补 `conversation.title.updated` |
| `conversation-ui/.../WorkbenchController.kt` | 重命名与自动标题改由 Gateway 结果决定：失败时回滚标题、撤销 `userRenamed` 标记并提示 |
| `conversation-ui/.../StateViews.kt` | `readableFailure` 增加重命名失败文案 |

## 4. 验证证据

- `integrations/hermes/tests/test_conversation_rename.py`（新增 4 例，149 passed 全绿）：
  以客户端九头签名方式对真实 HTTP 边界发 PATCH，断言验签通过、标题落库、事件追加、被篡改
  请求体失败关闭、方法闭集未越界。**回退服务端修复后该文件 3 例失败**，证明其确实覆盖本次缺陷。
- `integrations/openclaw/test/gateway-capabilities.test.ts`：新增 PATCH 重命名用例，断言
  落库、列表可见、`conversation.title.updated` 事件与未知会话 `SCHEMA_INVALID`。
- 跨宿主一致性门禁 `npm run gateway:v2:conformance` 通过（TS 与 Python 仍逐字节一致）。
- Android：`:gateway-client`、`:conversation-data`、`:conversation-ui` 单测与
  `:app:testFullDebugUnitTest` 全绿；`WorkbenchTitleRegressionTest` 新增“失败回滚且必须提示”
  两例。
- 传输层事实：JDK 的 `HttpURLConnection` 以 `ProtocolException: Invalid HTTP method: PATCH`
  拒绝 PATCH，而 App 真正运行的平台实现（OkHttp 版 `HttpURLConnectionImpl`）白名单包含
  PATCH。因此“手机端确实发出 PATCH”只能由设备侧插桩测试观察：
  `gateway-client/src/androidTest/.../ConversationRenameTransportInstrumentedTest`（已编译通过，
  当前环境无真机/模拟器，尚未执行）。

## 5. 遗留

- 上述插桩测试需要在真机或模拟器上补跑一次（`./gradlew :gateway-client:connectedDebugAndroidTest`）。
- `userRenamed` 保护标记仍是内存态：App 重启后若 Agent 再次下发标题建议，可能覆盖用户手动标题；
  需要持久化该标记才算满足“永久权威”。
- OpenClaw 宿主仍未实现 SSE，`conversation.title.updated` 只能通过 `GET /events` 轮询式读取。
