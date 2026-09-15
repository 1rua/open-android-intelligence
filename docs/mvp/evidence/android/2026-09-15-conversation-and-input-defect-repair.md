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

## 4. 第二轮：真机复测暴露的网关侧缺陷（2026-09-15 晚）

用户在真机复测后反馈"点击发送后无反应、附件仍然失败"。核对本机运行中的 `hermes-gateway.service`（即手机连接的 192.168.100.114:11451）后确认：

- 运行中的网关通过符号链接 `~/.hermes/plugins/agent-life-gateway/open_android_intelligence_gateway -> 仓库` 直接加载仓库代码，服务端即当前源码，不是旧安装副本。
- 手机端在 22:03:17 完成了协商与密码登录（均 200），并在 `device_keys` 注册了长度为 43 的原始公钥，证明第一轮的设备公钥修复已生效。
- 但同一时刻 `GET /conversations`、`GET /commands`、`POST /conversations`、`POST /attachments` **全部返回 400**。
- 用账号数据副本复现，Core 返回的确切错误码是 `MASTER_KEY_UNAVAILABLE`：`account_metadata.master_key_ref` 为空字符串，`account.store.aead` 为 `None`。该账号的 `attachments`、`conversations`、`messages` 三张表记录数均为 0，说明这台网关的已认证业务接口从未成功过。
- 根因：ADR 0023 要求"宿主机优先提供 Secret Store，没有时由部署者提供权限受限的专用密钥文件，密钥缺失时网关拒绝启动"。Hermes 的 `PluginContext` 并不提供 `secret_store`，而实现只是静默降级为空主密钥，于是把"未配置密钥"变成了"登录成功但每个业务请求 400"。

修复：新增 `local_keys.py` 提供 ADR 0023 允许的受限密钥文件来源（AES-256-GCM，按账号 HKDF 派生，拒绝符号链接、非本人所有、组/其他可读、缺失或长度不合法），`plugin.py` 在宿主没有 Secret Store 时回退到该文件，`adapter.connect()` 在主密钥不可用时拒绝启动并打印可操作原因，`hermes-account.py init-key` 负责生成 0600 密钥文件。

验证：`test_local_master_key.py` 10 项通过（含缺失/权限过宽/符号链接被拒、账号间密钥隔离、篡改与 AAD 不匹配被拒、无密钥时平台拒绝启动、有密钥时平台正常启动、完整附件三步 + 发消息闭环且暂存字节为 `aead-v1:` 密文）。Hermes 全套 128 项通过。用**真实账号数据副本**验证：设置密钥文件后，原本返回 `MASTER_KEY_UNAVAILABLE` 的请求变为 `error=None`，`master_key_ref` 由空字符串平滑升级为 `local-key-v1:...`。

同时改进 App 的失败反馈：发送失败不再只显示原始错误码，而是映射为可操作的中文说明；新增回归测试覆盖。

## 4.1 自动配置与部署（2026-09-15 23:02）

用户要求"安装插件后自动配置好，只需在 Agent 端装插件并用 `hermes gateway setup` 配好账号密码即可配对"。据此把"提供密钥来源"绑定到安装插件本身：插件加载时若宿主没有秘密存储，就在 `~/.open-android-intelligence/gateway-master-key` 自动生成 32 字节随机密钥（0600，目录仅在本次创建时收紧到 0700）；`hermes-account.py` 也改为使用同一来源，避免命令行创建的账号缺少主密钥。路径可用 `OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE` 覆盖，`init-key` 与自动路径都绝不覆盖已有密钥。决策记录在 ADR 0048，`CONTEXT.md` 与 Hermes 集成 README 同步更新。

部署（已获用户确认）：23:02:08 重启 `hermes-gateway.service`，`ActiveEnterTimestamp=23:02:08`，`MainPID=735616`，服务 `active`；日志显示 `Gateway Protocol v2 server listening on 0.0.0.0:11451` 且 `open_android connected`。密钥文件与本次重启同一秒出现（`-rw------- 32 字节`，目录 `700`），这本身就是运行进程已加载新代码并成功解析出真实密钥来源的直接证据。

账号 `master_key_ref` 仍为空，属预期：空引用会在该账号下一次被打开时（即手机下一次请求）就地升级为 `local-key-v1:...`，已用真实数据副本证明可行且不会报 `MASTER_KEY_REFERENCE_MISMATCH`。

CLI 子进程级回归测试已加入：在临时 HOME 下执行 `./hermes-account.py create phone1 --password ...`，断言账号 `master_key_ref` 以 `local-key-v1:` 开头且密钥文件为 0600。

## 5. 设备端待人工验收

本机无可用真机，用户将自行安装验收。产物：

- `apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk`
- sha256 `ecb91cfaec09312b6a9a526bb6017fa9158c272b6531c28d70b36a2be4aba190`

验收建议：断言 1（空会话直接输入即可发送）、2（选图片与文档均显示上传成功并可发送）、3（会话栏搜索文字完整）、4（键盘弹出后输入栏紧贴键盘上方、不再顶到屏幕顶部）、5（点麦克风直接进入系统识别界面）。

已知边界：旧安装的设备公钥编码不合法，升级后首次启动会提示「设备认证已修复，请重新登录一次以更新设备公钥」，重新登录一次即可；不修复该提示会让旧凭据继续 401。
