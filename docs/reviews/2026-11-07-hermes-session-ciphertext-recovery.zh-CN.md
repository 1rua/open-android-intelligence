# Hermes 在线但显示“通道已断开”、切换会话报冲突及发消息失败修复审查报告

日期：2026-11-07。开发基线：`c02a6a6e`（`dev`）。工作分支：`feature/agent-plugin-root-zero-pairing`。

## 一、背景与故障现象

在完成免密配对与跨用户插件安装接入后，客户端连接已启用 Hermes 会话适配器的远端 OAI Core 实例时出现以下组合症状：

1. **设置页与指令拉取正常**：客户端“连接与账号”设置中 Hermes 显示“在线”，在对话框输入 `/` 可以正常拉取并展示全部斜杠指令列表。
2. **对话页顶部提示“通道已断开”**：进入对话界面后，顶部副标题落入断开/故障态。
3. **切换历史会话提示操作冲突**：从侧边栏切换已有会话或刷新会话列表时，界面提示“这次请求与已有操作冲突，请刷新会话核实结果。”
4. **发送消息报错**：在会话中发送普通消息无法正常完成回复投递。

## 二、根因定位与源码证据

经源码审计与远端数据库只读副本复现，上述三项异常并非网络反代断流或并发写冲突，而是源于同一条底层因果链：**账号桥接缓存库（`gateway.sqlite`）中残留了历史已失效密钥加密的 `aead-v1:` 临时密文记录，触发 `DECRYPTION_FAILED` 后又被会话适配器错误映射为 HTTP `409`**。

具体源码事实如下：

1. **在线探测与指令目录不读取加密数据库**：
   - `GET /v2/agents` 与 `GET /v2/agents/hermes/commands` 直接读取宿主内存态与插件注册表，不触碰账号 `gateway.sqlite` 中的 `aead-v1:` 字段，因此始终返回 `200`。
2. **Hermes 持久历史与桥接临时缓存的存储边界**：
   - Hermes 真正的会话正文、标题与归档状态均以明文保存在宿主原生 `SessionDB`（`state.db`）中；`gateway.sqlite` 内的 `conversations` 与 `conversation_agent_sessions` 同样为明文表。
   - 仅桥接侧的会话扩展元数据缓存（`account_metadata` 中的 `conversation-management:*` 等）、短期事件回放队列（`events`）、观察者游标（`native-event-cursor:v1:*:ack`）及历史遗留幂等账本使用本地密钥（`aead-v1:`）封装。当部署环境经历重装或密钥轮换生成新的 `session-master-key` 后，旧密钥加密的临时缓存行无法用新密钥解开。
3. **症状一（切换会话报“这次请求与已有操作冲突”）触发路径**：
   - [HermesSessionAdapter.list_sessions](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/sessions.py) 遍历全部原生会话绑定调用 `record()`，读取历史会话的 `conversation-management:*` 元数据时抛出 `GatewayError("DECRYPTION_FAILED")`。
   - 原 `HermesSessionAdapter._account()` 将所有 `GatewayError` 无差别包装为 `AdapterError(err.code, 409)`。
   - Android 客户端 [StateViews.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt) 匹配错误消息中的 `"409"`，向用户展示“这次请求与已有操作冲突，请刷新会话核实结果。”
4. **症状二与三（顶部“通道已断开”与发送消息报错）触发路径**：
   - 即使是新建会话，客户端拉取事件快照（`GET .../events?limit=128&snapshot=true` -> `NativeEventSync._read()`）以及服务端投递 AI 回复（`runtime.send()` -> `account.events.append()`）都会先执行 [EventStore.purge_expired](../../oai-core/src/oai_core/gateway/core.py)。
   - 原 `purge_expired()` 在删除已过期事件前调用 `self.store.open_json()` 解密旧行以提取 `conversationId`；遇到历史旧密文行即抛出 `DECRYPTION_FAILED` 并返回 HTTP `409`。
   - Android 客户端 [CoreAgentConversationRepository.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt) 将 HTTP `409` 判定为不可重试的 `PROTOCOL` 错误，立即终止事件同步循环并将通道健康度置为 `StreamHealth.FAILED`（界面显示“通道已断开”），同时中断服务端消息回复落库。

## 三、修复方案与安全边界

本次修复严格遵循“不伪造业务状态、不丢失宿主原生历史、保留既有交互文案”的约束：

1. **多候选本地密钥无锁抽样优选与跨用户对齐**：
   - 在 [local_keys.py](../../oai-core/src/oai_core/gateway/local_keys.py) 新增 `count_decryptable_account_records()`，通过 SQLite 只读 URI（`?mode=ro`）抽样检测候选密钥可解密的既有 `aead-v1:` 记录数，零写入且不抢占写锁。
   - 在 [backend.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/backend.py) 新增 `_select_session_key_store()`：当未显式指定环境变量且同目录并存 `session-master-key` 与 `gateway-master-key` 时，自动选用能解密既有账号库记录最多的密钥。
   - 在 [configuration.py](../../oai-core/src/oai_core/configuration.py) 增强跨用户部署场景下的会话密钥同步（`_align_hermes_session_master_key`）及外部虚拟环境包更新（`_sync_hermes_venv_packages`）。
2. **孤立历史密文启动自愈隔离（完整保留原生会话与聊天记录）**：
   - 在 [backend.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/backend.py) 新增 `_repair_unreadable_session_records()`，于 `SessionBackend.acquire_accounts()` 获取独占连接后执行。
   - 仅隔离清理确认触发 `DECRYPTION_FAILED` 的临时缓存密文（`account_metadata` 损坏项、不可解密的 `:ack` 游标、`events` 孤立行、未迁移的加密 `messages.text` 及历史遗留 `idempotency_ledger`），**完整保留 `conversations`、`conversation_agent_sessions` 与宿主原生 `state.db` 中的全部会话及历史消息**。
   - 清理不可读 `events` 行时，在同一事务内对所有会话保守写入 `conversation-event-pruned:v1:` 最大序列水位标记，确保客户端按既有协议重做快照同步而不遗漏状态。
3. **过期事件清理容错加固**：
   - 加固 [EventStore.purge_expired](../../oai-core/src/oai_core/gateway/core.py)：当过期事件行或过期 `device.requested` 记录无法被当前密钥解密时，保守推进全部会话的 `conversation-event-pruned:v1:` 序列边界并安全清理该过期行，避免历史残留阻断后续 `append()` 与 `read_after()`。
4. **网关异常 HTTP 状态码精确映射**：
   - 在 [sessions.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/sessions.py) 与 [bridge.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/bridge.py) 新增 `gateway_error_status()`，将 `DECRYPTION_FAILED`、`ENCRYPTION_FAILED`、`MASTER_KEY_UNAVAILABLE`、`HOST_INCOMPATIBLE` 映射为 HTTP `503`（服务暂不可用），仅真实幂等或附件摘要冲突保留 `409`。

## 四、测试与端到端验证

### 1. 新增单元测试与本地门禁

新增回归测试文件 [test_hermes_session_key_recovery.py](../../oai-core/tests/test_hermes_session_key_recovery.py)，覆盖以下 4 类核心场景：

- `test_session_backend_prefers_existing_gateway_key_that_decrypts_account_db`：双本地密钥并存时自动优选可解密既有账号库的密钥；
- `test_session_backend_self_heals_orphaned_ciphertexts_from_lost_key`：历史密钥丢失后自动隔离孤立密文、保留历史原生会话绑定，且快照事件同步与新回复追加均正常完成；
- `test_event_store_purge_expired_tolerates_undecryptable_expired_rows`：`EventStore.purge_expired()` 安全清理不可解密过期行后正常追加新事件；
- `test_gateway_error_status_maps_decryption_and_storage_errors_to_503_not_409`：存储与加解密错误映射为 `503`，幂等冲突保持 `409`。

本地自动化门禁结果：

- 依赖锁与契约检查（`npm run mvp:lock:check`）：**PASS**。
- Core 单元测试套件、Hermes 原生会话与事件同步回归（含新增用例共 **59 passed**）、通信测量回归（**25 passed**）：**全部通过**。
- DSH 会话适配器测试套件（Vitest **85 passed**）：**全部通过**。
- Android 静态检查与增量单元测试（`noVpnSurfaceCheck`、`:conversation-domain:testDebugUnitTest`、JDK 21 下 `:gateway-client:testDebugUnitTest` **364 passed**）：**全部通过**。

复现与验证命令（在仓库根目录执行）：

```sh
PYTHONPATH="oai-core/src:integrations/hermes-session-adapter/src:integrations/dsh-session-adapter/src" \
  python -m pytest -q \
  oai-core/tests/test_hermes_session_key_recovery.py \
  oai-core/tests/test_native_event_sync.py \
  oai-core/tests/test_hermes.py \
  oai-core/tests/test_agent_registration.py
```

### 2. 远端实例部署与接口验证

同步更新远端服务并重启 `oai-core.service` 与宿主 `hermes-gateway.service` 后：

- 宿主日志确认启动时自动完成孤立历史密文隔离（`Quarantined unreadable legacy session ciphertexts`），后续运行无异常堆栈。
- 远端确定性探针复测结果：
  - `GET /v2/agents` -> `200`（`hermes` 在线）；
  - `GET /v2/agents/hermes/commands` -> `200`（381 项指令）；
  - `GET /v2/agents/hermes/sessions` -> 由修复前 `409 DECRYPTION_FAILED` 恢复为 **`200`**（完整恢复账号下全部 87 个历史会话）；
  - `GET /v2/agents/hermes/sessions/<id>/history` -> `200`；
  - `POST /v2/agents/hermes/sessions/<id>/messages` -> `200`（完整返回 `generation` -> `delta` -> `done` 流式帧）。

### 3. Android 客户端模拟器端到端实测（`android-cli`）

使用增量编译生成的 `app-full-debug.apk` 安装至本地 Android 模拟器，由独立验证子 Agent 通过 `android-cli`（`android layout` 语义树核验与屏幕截图）连接远端真实实例完成全链路验收：

1. **设置页在线状态**：进入“设置 -> 连接与账号”，`HTTP 连接` 显示 `可访问（HTTP 200）`，`实时事件通道` 显示 `已连接`，`Hermes` 状态显示 `在线 · 账号隔离 · 当前使用`。
2. **对话页顶部实时通道**：返回对话主界面后，顶部状态稳定显示 `已连接`，检索全部布局节点确认不再出现“通道已断开”。
3. **历史会话切换**：打开侧边抽屉连续切换多个已有历史会话，历史聊天内容正常渲染，全程未出现“这次请求与已有操作冲突，请刷新会话核实结果”。
4. **斜杠指令候选**：聚焦输入框键入 `/`，正常弹出 `/start`、`/new`、`/topic`、`/save` 等指令浮窗。
5. **真实消息收发**：发送测试消息“请只回复：安卓端连接测试通过”，状态流转至 `Agent 处理完成` 并正常渲染 AI 回复气泡“安卓端连接测试通过”，`logcat` 中 HTTP 与实时通道周期完整无报错。
