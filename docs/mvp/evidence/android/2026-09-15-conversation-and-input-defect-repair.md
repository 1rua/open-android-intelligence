# 2026-09-15 对话与输入 5 项缺陷修复证据

状态：自动化测试通过；设备端人工验收待用户执行。

## 1. 缺陷、根因与修复

| # | 现象 | 根因 | 修复 |
|---|------|------|------|
| 1 | 发送按钮不可点击 | `WorkbenchScreen` 用 `state.activeThreadId != null` 当发送门槛，但新会话在首次发送前还没有 conversationId | 改用 `WorkbenchUiState.canSend`；首次发送由 `WorkbenchController` 先 `POST /conversations` 再发送，创建失败时保留草稿 |
| 2 | 图片、附件无法真正上传 | ① 登录时注册的设备公钥是 44 字节 SPKI 容器，Hermes 按 32 字节 RFC 8032 原始公钥验签，导致所有已签名请求 401；② 附件客户端在响应顶层读 `attachmentId`，而契约把结果放在 `data.attachment`；③ 消息体用了非契约形状 | ① 新增 `ed25519WirePublicKey()` 去掉 SPKI 头；② 统一经 `GatewayResponse.requireData()` 读 `data` 信封；③ 新增 `ConversationClient.sendMessage()`，按 §7 发送 `clientMessageId`/`text`/`attachments[]` |
| 3 | 会话搜索文字被裁切 | 搜索框写死 `.height(44.dp)`，低于 Material 文本字段 56dp 最小高度，装饰区被压缩后占位文字被裁 | 改为 `heightIn(min = Dimensions.MinimumTouchTarget)`，由文本框按字体缩放自然增长 |
| 4 | 点输入栏时输入栏抬起过高 | 未声明 `windowSoftInputMode`，默认 `adjustUnspecified` 可能退化为 adjustPan 整窗平移，与 `ComposerBar` 的 `imePadding()` 叠加 | `MainActivity` 声明 `android:windowSoftInputMode="adjustResize"` |
| 5 | 语音输入默认失败 | 启动前用 `Intent.resolveActivity()` 预检；Android 11+ 包可见性下系统识别服务不在可见列表，直接弹「系统语音输入不可用」 | 抽为 `launchVoiceInput()`，直接启动并在 `ActivityNotFoundException` 时才提示不可用；识别结果追加到已有草稿而不是覆盖 |

## 2. 顺带修正的同类协议缺陷

- 会话列表按 `data.conversations` 解析，不再读顶层 `threads`。
- 命令目录按 `data` 解析。
- 附件 commit 后校验 `data.attachment.state == verified`，未核验不允许被消息引用。
- 仅当 Gateway 在协商中声明 `message-batches-v1` 时才走批量接口；Hermes 不声明，因此走单条 `chat-v1` 消息。

## 3. 自动化证据（本机只读缓存，未改共享服务）

- 单元/界面测试：`gateway-client` 85、`conversation-data` 11、`conversation-ui` 30、`conversation-domain` 3、`app` 14，共 143 项，失败 0。
  - 命令：`./gradlew :gateway-client:testDebugUnitTest :conversation-data:testDebugUnitTest :conversation-ui:testDebugUnitTest :conversation-domain:testDebugUnitTest :app:testFullDebugUnitTest :app:assembleFullDebug`
- 新增回归测试均先观察到失败再修复：
  - `WorkbenchLayoutRegressionTest.firstDraftHasAnEnabledSendButtonWithoutAnExistingThread`：修复前 `[Disabled]`。
  - `WorkbenchLayoutRegressionTest.searchFieldGrowsWithFontScaleInsteadOfClippingItsPlaceholder`：改回 `height(44.dp)` 后字段高 116px < Material 最小 126px。
  - `InputPlatformRegressionTest.mainWindowResizesInsteadOfPanningTheFocusedComposer`：移除 `adjustResize` 后 softInputMode adjust 掩码为 0（`adjustUnspecified`）。
  - `GatewayWireRegressionTest`（3 项）与 `WorkbenchSendRegressionTest`（2 项）：修复前全部失败。
- Hermes 真实互通：`HermesHttpInteropTest` 起本地 Python 夹具（真实 Core、schemas、HTTP 路由、密码凭据、Ed25519 验签），走真实 HTTP 与真实签名完成
  `negotiate → password login → commands → create conversation → list conversations → attachment create/upload/commit → GET attachment → send message`，全部 200，无错误码。

## 4. 设备端待人工验收

本机无可用真机，用户将自行安装验收。产物：

- `apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk`
- sha256 `ecb91cfaec09312b6a9a526bb6017fa9158c272b6531c28d70b36a2be4aba190`

验收建议：断言 1（空会话直接输入即可发送）、2（选图片与文档均显示上传成功并可发送）、3（会话栏搜索文字完整）、4（键盘弹出后输入栏紧贴键盘上方、不再顶到屏幕顶部）、5（点麦克风直接进入系统识别界面）。

已知边界：旧安装的设备公钥编码不合法，升级后首次启动会提示「设备认证已修复，请重新登录一次以更新设备公钥」，重新登录一次即可；不修复该提示会让旧凭据继续 401。
