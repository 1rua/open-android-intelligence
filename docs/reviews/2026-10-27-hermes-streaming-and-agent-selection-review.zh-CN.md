# Review 审查报告：Hermes 网关消息接收崩溃与多 Agent 锁定缺陷分析

**文档版本**：v1.0  
**日期**：2026-10-27  
**审查范围**：Android 客户端与 OAI Core / Hermes 网关集成模块  
**关联任务**：Bug 1（消息接收时固定闪退）与 Bug 2（Agent 选择锁定在 dsh）  

---

## 1. 概述

近期在 Android App 连接 Hermes 网关并进行对话时，出现了两个关键缺陷：
1. **Bug 1（消息接收时固定闪退）**：App 在连接 Hermes 网关并进行任意对话后，在接收到回复消息（流式数据块 delta 或完成状态）时固定发生闪退崩溃。
2. **Bug 2（Agent 选择锁定在 dsh）**：在多 Agent 列表中，用户切换选择其他 Agent（如 Hermes）后，系统总是跳回或锁定在 `dsh` 上，无法保持用户选择。

本文档汇总团队对上述两个 Bug 的代码走查、根本原因定位、精确代码路径与修复方案，作为后续修复与测试验证的技术依据。

---

## 2. Bug 1：消息接收时固定闪退

### 2.1 缺陷现象
当 App 连接启用原生会话能力（`nativeAgentSessions = true`）的 Hermes 网关后，用户在会话窗口发送任意文本消息，只要网关开始返回回复（无论是收到第一个流式数据块 `delta` 还是处理完成的状态 `done`），App 会立刻发生无提示的直接闪退崩溃。

### 2.2 根本原因（Root Cause）

根本原因位于 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt`：

#### 2.2.1 `MessageStatus` 事件的 `messageId` 发生反转/错配，破坏了工作台的状态机与校验映射
- **契约规范**：在 OAI 会话体系（包括 `GatewayEventDecoder.kt`、`GatewayConversationRepository.kt` 与 `WorkbenchController.kt`）中，`VerifiedConversationEvent.MessageStatus` 描述的是**用户发送的那条出站消息（User Outgoing Message）在服务端的流转状态变更**（如 `QUEUED -> DELIVERED -> COMPLETED / FAILED`），其 `messageId` 必须与用户消息在服务端确认后的 `messageId`（即 `MessageAccepted` 携带的 `messageId`）保持一致。
- **实际缺陷代码**（`CoreAgentConversationRepository.kt:211-223` 以及第 235-247 行）：
  ```kotlin
  _events.emit(
      VerifiedConversationEvent.MessageStatus(
          eventId = "evt_${UUID.randomUUID()}",
          occurredAt = System.currentTimeMillis(),
          conversationId = ConversationId(conversationId),
          messageId = assistantMsgId, // ❌ 错误传入了新生成的助手消息 ID
          clientMessageId = message.clientMessageId, // 传入的是用户发送消息的 clientMessageId
          status = AgentMessageStatus.COMPLETED,
          revision = 1L,
          errorCode = null,
          generationId = genId,
      )
  )
  ```
- **连锁破坏效应**：
  1. `WorkbenchController.kt:3537`：`mirrored[event.messageId]` 会尝试使用 `assistantMsgId` 查找用户消息。由于此时 `mirrored` 中存入的是 `state = "CONFIRMED"` 的助手消息，状态机将其 `state` 强行覆盖为 `event.status.wireValue`（`"completed"`，而在工作台协议中完成的消息状态必须是 `"CONFIRMED"`），破坏了后续 `renderTimeline()` 中的状态判定。
  2. `WorkbenchController.kt:451-455`：收到 `MessageStatus` 时触发 `changeSendAtom`，因为 `messageId` 错配为助手 ID，导致本地原子存储（Send Atoms）与用户消息状态的关联彻底错乱。
  3. `WorkbenchController.kt:3560`：触发 `finishStreaming`，内部由于 ID 校验不一致抛出异常。

#### 2.2.2 `TimelineUpsert` 缺少代际标识，且动态刷新时间戳破坏 Compose 列表重组稳定性
- **实际缺陷代码**（`CoreAgentConversationRepository.kt:174-192`）：
  ```kotlin
  client.send(agent, conversationId, message.text) { delta ->
      accumulatedText.append(delta)
      val currentText = accumulatedText.toString()
      _events.emit(
          VerifiedConversationEvent.TimelineUpsert(
              eventId = "evt_${UUID.randomUUID()}",
              occurredAt = System.currentTimeMillis(),
              revision = System.currentTimeMillis(),
              message = TimelineMessage(
                  id = assistantMsgId,
                  sender = "assistant",
                  parts = listOf(MessagePart.Text(currentText)),
                  timestamp = System.currentTimeMillis(), // ❌ 动态漂移的时间戳
                  state = "STREAMING",
                  conversationId = ConversationId(conversationId),
                  // ❌ 遗漏了 generationId = genId！
              )
          )
      )
  }
  ```
- **影响分析**：
  1. **缺少 `generationId`**：导致 `WorkbenchController.kt:3642-3647` 在清理过期流式消息，以及第 4007-4028 行 `pruneConfirmedAssistantDuplicates` 清理流式草稿时，由于 `message.generationId == null` 无法精确匹配当前生成轮次。
  2. **时间戳动态漂移导致 Compose 崩溃**：在 `WorkbenchController.kt:4130-4138` 的 `renderTimeline` 中，列表是按 `entry.timestamp` 排序的。流式期间每个 delta 都会把 `timestamp` 更新为最新的 `System.currentTimeMillis()`，导致该消息在时间线列表中的位置不断漂移和跳动。`WorkbenchScreen.kt:410-420` 的 `LazyColumn` 结合 `Modifier.animateItem` 在面对快速跳跃和并发变化时，底层触发布局与动画状态不一致的致命异常（如 `Inconsistency in AnimatedVisibility/animateItem placement`）。

### 2.3 精确代码路径与行号

1. **`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt`**
   - **L174-192**：`client.send(...) { delta -> ... }`
     - 构建 `TimelineMessage` 未提供 `generationId = genId`。
     - 每次 delta 回调都采用当前时间 `System.currentTimeMillis()` 充当 `timestamp`，未固定助手消息起始时间戳。
   - **L195-209**：流式完成分发的 `TimelineUpsert`
     - 同样缺少 `generationId = genId`。
   - **L211-223**：`VerifiedConversationEvent.MessageStatus`
     - `messageId = assistantMsgId` 传入错误，应为用户发送消息的 `messageId`。
   - **L235-247**：异常分支的 `VerifiedConversationEvent.MessageStatus`
     - 同样将 `messageId` 错误赋值为 `assistantMsgId`。

2. **`apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt`**
   - **L3526-3543**：处理 `VerifiedConversationEvent.MessageStatus` 时，因 `messageId` 错配导致助手消息状态被意外改写。
   - **L4130-4138**：`renderTimeline` 根据漂移的时间戳频繁重新排序。
   - **`WorkbenchScreen.kt:410-420`**：`LazyColumn` 在快速重新排序中触发 `animateItem` 测量/重组崩溃。

### 2.4 修复方案
1. 在 `submitMessage` 开始处理助手回复前，固定助手消息的时间戳：
   ```kotlin
   val assistantTimestamp = System.currentTimeMillis()
   ```
2. 在流式阶段（`STREAMING`）以及最终完成阶段（`CONFIRMED`）构建 `TimelineMessage` 时，统一使用该固定的 `assistantTimestamp`，并补齐 `generationId = genId`。
3. 修正 `VerifiedConversationEvent.MessageStatus` 的参数：
   ```kotlin
   messageId = messageId, // 传入用户消息的 messageId
   clientMessageId = message.clientMessageId,
   ```
4. 对高频 `onDelta` 进行适当的背压/防抖聚合处理，避免极短时间内向 SharedFlow 连续大量 emit 引发 UI 渲染层线程饥饿。

---

## 3. Bug 2：Agent 选择锁定在 dsh

### 3.1 缺陷现象
在多 Agent 列表中，用户切换选择其他 Agent（例如 `Hermes`）后，界面短暂显示后又自动跳回 `dsh`，或者在应用重新连接、页面返回后又被锁死在 `dsh` 上，无法记忆用户的选择。

### 3.2 根本原因（Root Cause）

该缺陷由持久化链路断裂与连接协商覆盖两处关键问题共同引起：

#### 3.2.1 凭据保存漏掉持久化字段 `selectedAgentId`
- **代码位置**：`GatewayRuntime.kt:1839`（`saveLastProfile`）
- **逻辑缺陷**：
  在 `GatewayRuntime.kt` 的 `saveLastProfile` 方法中，当保存配置到 `AccountProfileStore` 时：
  ```kotlin
  accountProfiles.save(AccountProfile(
      localProfileId = profileId,
      gatewayBaseUrl = gatewayUrl,
      username = username,
      tlsTrustId = trustId,
      avatarBase64 = previous?.avatarBase64,
      agentName = previous?.agentName,
      // ❌ 遗漏了 selectedAgentId = previous?.selectedAgentId！
  ))
  ```
  每当用户登录成功保存凭据、或者系统在会话续期保存 profile 时，`selectedAgentId` 被直接置空（`null`）。
  一旦发生网络波动、Token 轮转或 App 重启，`accountProfiles.find(profileId)?.selectedAgentId` 读取出来的始终为 `null`，导致控制器重新初始化时回落到列表第一项（即 `dsh`）。

#### 3.2.2 连接握手与重连阶段无条件使用服务端的 `agentName` 覆盖界面状态
- **代码位置**：`GatewayRuntime.kt:1501-1506`（`establish()`）及 `MainActivity.kt:785`
- **逻辑缺陷**：
  在 OAI Core 架构中，网关协商接口 `/negotiate` 返回的 `agentName` 或 `deploymentId` 通常代表宿主部署环境标识（例如默认固定为 `"dsh"`）。
  在 `GatewayRuntime.establish()` 中：
  ```kotlin
  accountProfiles.find(profileId)?.let { accountProfiles.save(it.copy(agentName = agentName)) }
  _phase.value = ConnectionPhase.Connected(
      ...
      agentName = agentName, // 来自 negotiated.agentName ?: negotiated.deploymentId
  )
  ```
  而在 `MainActivity.kt:785` 中：
  ```kotlin
  agentName = (phase as? ConnectionPhase.Connected)?.agentName ?: profile?.agentName
  ```
  工作台的标题和状态展示直接依赖 `Connected.agentName`。即使用户通过 `selectAgent("hermes")` 切换了 Agent，只要触发一次重连、恢复会话或重协商，`establish()` 就会强行把 `agentName` 和 profile 冲刷为网关部署层面的默认值 `"dsh"`。

#### 3.2.3 `NativeAgentsController` 的并发轮询与默认首项回退
- **代码位置**：`NativeAgentsController.kt:70-114`（`refreshNow`）
- **逻辑缺陷**：
  `NativeAgentsController` 内部启动了 10 秒定时刷新任务。在先前版本中，由于没有持久化恢复值，`_state.value.selectedAgentId` 初始为 `null`，进入 `refreshNow()` 后执行：
  ```kotlin
  val selected = _state.value.selectedAgentId?.takeIf { id -> agents.any { it.id == id } }
      ?: agents.firstOrNull()?.id
  ```
  若服务端返回列表首项为 `dsh`，就会自动锁死在 `dsh`；且由于并发更新未加互斥版本保护，用户在界面点击选择的同时，挂起的 `refreshNow()` 协程可能会在返回后重新覆盖用户刚做的选择。

### 3.3 精确代码路径与行号

1. **`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt`**
   - **L1839 附近**（`saveLastProfile`）：构造新 `AccountProfile` 时遗漏了 `selectedAgentId = previous?.selectedAgentId`。
   - **L1501-1519**（`establish` 尾部）：在启用了 `nativeAgentSessions` 的情况下，用网关协商返回的 `agentName` 粗暴改写了用户的 Agent 名称选择。
   - **L1432-1470**（`nativeAgentSessions` 监听流）：首次观察与状态同步时，需防止首帧 `isFirstObservation` 与重连状态混淆。
2. **`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentsController.kt`**
   - **L70-114**（`refreshNow`）：列表刷新时回退第一项的逻辑需要在持有确认选择时保持粘滞，不能受列表波动影响。
3. **`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt`**
   - **L785**：Header 和工作台的 `agentName` 优先展示逻辑。

### 3.4 修复方案
1. **完善 `AccountProfile` 数据流完整性**：
   在 `saveLastProfile` 中必须补齐：
   ```kotlin
   selectedAgentId = previous?.selectedAgentId
   ```
   保证无论何时更新凭据，已选中的 Agent 都不被丢弃。
2. **连接协商不应覆盖多 Agent 模式下的用户选择**：
   在 `establish()` 阶段，如果检测到已启用 `nativeAgentSessions` 且本地存在已选中的 Agent，不要使用网关默认的 `deploymentId` 改写该属性；工作台和界面标题优先使用当前选中的 Agent 名称。
3. **保证 `NativeAgentsController` 状态稳定性**：
   - 使用带原子 `revision` 校验的状态机，用户主动执行 `selectAgent` 时递增 `revision`，避免并发挂起的旧 `refreshNow()` 结果覆盖新选择。
   - 保持用户选择粘滞性，已选中的 Agent 即使在拉取中间状态中也保持保留，不静默回退到 `firstOrNull()`。

---

## 4. 总结与后续实施建议

| 缺陷项 | 核心原因 | 关键修复文件 | 风险等级 |
| :--- | :--- | :--- | :--- |
| **Bug 1：接收消息固定闪退** | `MessageStatus` 的 `messageId` 错配为助手 ID；`TimelineMessage` 遗漏 `generationId` 且动态漂移时间戳引发 Compose 布局崩溃 | `CoreAgentConversationRepository.kt` | 严重（阻塞核心对话流程） |
| **Bug 2：Agent 锁定在 dsh** | `saveLastProfile` 遗漏复制 `selectedAgentId`；`establish` 协商结果覆盖用户选择；控制器首项回退竞争 | `GatewayRuntime.kt`<br>`NativeAgentsController.kt` | 高（影响多 Agent 切换体验） |

**建议实施步骤**：
1. 建立独立特性分支，按上述代码路径进行最小必要修改。
2. 针对 `CoreAgentConversationRepositoryTest` 补充流式增量生成与 `MessageStatus` 状态匹配的断言用例。
3. 针对 `GatewayRuntime` 与 `NativeAgentsController` 补充 Agent 选择持久化、重连恢复以及列表定时刷新的回归测试。
4. 跑通单元测试验证通过后提交 PR。
