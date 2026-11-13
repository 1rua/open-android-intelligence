# Standards 轴独立审查（844b6cc..0334789）

**审查日期：** 2026-09-01
**审查对象：** `git diff 844b6cc..0334789`（6 个提交：`753e179` 协议/Schema/向量、`4d1c769` gateway-client、`d24f63e` conversation-domain/conversation-ui 基础、`cb04b06` 对话工作台与助理选区、`ea76e8` 双宿主向量、`54ad9e8` 旧最终报告）
**结论：FAIL**

## 关于「独立审查」的执行说明

交接文档要求使用与主 Agent 同模型的独立子 Agent 完成本轴审查。本会话已尝试派发两次独立审查子 Agent（Standards / Spec），**两者均在未完成审查时返回截断结果，未写入任何报告文件**（`docs/superpowers/reviews/` 下无新文件）。这与 2026-08-29/30 记录的编排工具失效一致，属第三次复现。

按交接文档「不要把错误当作审查完成」的约束，本轴改由主 Agent 直接执行，审查结论**全部基于现场 `git`、源码与命令输出**，不引用任何历史报告作为证据。此执行方式的变化本身已如实记录，不以「独立审查已完成」陈述。

## 硬违规

### S1. 重构未原子化，新旧两套架构长期并存

**规范：** `AGENTS.md` →「重构必须原子化清除废弃旧代码：引入新方案时必须同步清理被替代的旧实现与调用方，严禁新旧两套架构长期并存。」

**证据：**

- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt:24` 仍为 `class MainActivity : Activity()`，`MainActivity.kt:26-28` 直接持有 `GatewayPresenter()`、`ConversationPresenter()`、`AttachmentPresenter()`；`MainActivity.kt:31` 仍以 `private var currentTab: String = "gateway"` 保存当前屏幕。
- 被替代的旧实现全部仍在：`GatewayScreen.kt`、`ConversationScreen.kt`、`AttachmentPicker.kt`、`PlatformSettingsScreen.kt` 均存在于 `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/`。
- 新的 `conversation-domain` 与这些旧 Presenter 无任何调用关系，形成两套并行状态源。

### S2. 领域契约被私自退化

**规范：** `AGENTS.md` →「严禁私自退化契约与魔数硬编码：领域模型、多态结构、接口契约……必须 100% 严谨还原，不得擅自简化。」

**证据（计划 `Task 4` Step 3/5 与实现的逐项偏差）：**

| 计划要求的契约 | 实际实现 | 位置 |
|---|---|---|
| `ConversationScope(profile, gateway, account, install)` 四元组 | `data class ConversationScope(val accountId: String, val gatewayId: String)` 退化为二元组 | `conversation-domain/.../ports/ConversationPorts.kt:6` |
| `sealed interface VerifiedConversationEvent`（7 个具名变体） | `data class VerifiedConversationEvent(val eventType: String, val payload: Map<String, Any?>)` 退化为字符串 + 任意 Map | `conversation-domain/.../ports/ConversationPorts.kt`（VerifiedConversationEvent 定义） |
| `AgentCommandCatalog` 含具名 `AgentCommand` 列表 | `data class AgentCommandCatalog(val version: CatalogVersion, val commands: List<String>)` | 同上 |
| `CancelGenerationResult` 区分 `CANCELLED/ALREADY_COMPLETED/UNSUPPORTED/OUTCOME_UNKNOWN` | `data class CancelGenerationResult(val success: Boolean, val message: String? = null)` 退化为布尔 | 同上 |
| `PendingSubmissionIntent` 含 `clientMessageId`/`text`/`attachments`/`requestIds`/`state` | `data class PendingSubmissionIntent(val intentId: SubmitIntentId, val revision: Long)` | 同上 |
| value class 构造器「拒绝空白**和非 wire-ID 字符**」 | 仅 `require(value.isNotBlank())`，不校验字符集 | `conversation-domain/.../model/Identifiers.kt:5-45` |

`VerifiedConversationEvent` 退化为 `Map<String, Any?>` 尤其严重：它使计划 `Task 5` Step 5 要求的「sequence 连续、终态唯一、revision 单调」检查在类型层面无法表达，只能靠运行期字符串约定，等于把协议不变量降级为口头约定。

### S3. 终态唯一性未在 reducer 中完整强制

**规范：** 计划 `Task 4` Step 6 →「非法组合抛 `InvalidConversationTransition`」；`Task 1` Step 4 →「终态唯一、终态后增量失败关闭」。

**证据：** `conversation-domain/.../reducer/ConversationReducer.kt` 中仅 `GenerationCompleted` 对 `OUTCOME_UNKNOWN` 做了拦截：

```kotlin
is ConversationEvent.GenerationCompleted -> {
    if (state.generation == GenerationState.OUTCOME_UNKNOWN) {
        throw InvalidConversationTransition("Cannot complete generation directly from OUTCOME_UNKNOWN")
    }
    state.copy(generation = GenerationState.COMPLETED)
}
```

而 `GenerationCancelled`、`GenerationFailed`、`GenerationOutcomeUnknown`、`GenerationUnsupported` 均无条件 `state.copy(...)`。因此 `COMPLETED -> CANCELLED`、`CANCELLED -> COMPLETED`、`FAILED -> COMPLETED` 全部被静默接受，与「终态唯一」直接冲突。

### S4. 与 accepted 设计调色板不一致（同时是 Spec 违规）

**规范：** 计划 Global Constraints →「V1 不使用系统动态取色。浅/深主题严格使用『设计系统』语义令牌。」

**证据：** `conversation-ui/.../theme/AppColors.kt` 实际取值与计划「视觉命题锁定」章节要求的令牌**无一匹配**：

| 令牌 | 规范要求（浅/深） | 实际实现 |
|---|---|---|
| Canvas | `#F1F4F1` / `#101613` | `#F7FAFC` / `#12161A` |
| Surface | `#E3ECE7` / `#19231F` | `#FFFFFF` / `#1A202C` |
| Primary | `#2F645C` / `#88BAAE` | `#2E6B4F` / `#68D391` |
| Accent | `#B8874A` / `#D3A86F` | 缺失（无 Accent 令牌） |
| Error | `#A95E50` / `#E1998C` | 缺失 |

## Judgement call（Fowler 坏味道基线）

- **Primitive Obsession（judgement call）** — `conversation-domain/.../ports/ConversationPorts.kt` 中 `VerifiedConversationEvent(eventType: String, payload: Map<String, Any?>)`、`CancelGenerationResult(success: Boolean)`、`AgentCommandCatalog(commands: List<String>)` 三处同时用原始类型/字符串替代领域类型。此处与 S2 重叠，但作为坏味道而非硬违规单独提示：即使计划未写明，事件类型也应是有穷封闭类型。
- **Data Clumps（judgement call）** — `CancelGenerationResult(success, message)`、`MessageAcceptance(messageId, correlationId)`、`BatchAcceptance(batchId, acceptedMessageIds)` 各自携带裸 `String` 标识符，未按计划使用 `GenerationId`/`ConversationId` 等不透明类型。
- **Duplicated Code（judgement call）** — `VerifiedConversationEvent` 在 `conversation-domain` 与 `gateway-client` 两处各有一份形状不同的定义（见 Spec 轴 S-3）。若后续合并，需要一个共享 seam。

## 明确排除的误报

- `gateway-client` 测试中的 `https://gateway.example.com`（`GatewaySessionManagerTest.kt:30`、`ConversationClientTest.kt:47`、`CanonicalTargetTest.kt:58`、`PinnedTlsInstrumentedTest.kt:31,40`）：这是 TLS/证书固定与 URL 规范化测试的**负向夹具**，用于断言非法主机被拒绝，不属于生产路径的硬编码默认 URL。计划 `Task 18` Step 8 的扫描会命中它们，届时应按「显式 negative fixture」处理，不应据此判定存在假状态。
- `MotionSpecs.StandardSpring` 使用 `Spring.DampingRatioNoBouncy` 而非数值常量：这是平台官方弹簧原语，符合「优先使用平台标准原语」，不构成魔数硬编码。

## 未覆盖范围

- 真机/仪表化行为：当前 `adb devices -l` 无设备，所有 `connectedDebugAndroidTest` 无法执行，本轴对运行时行为不作结论。
- `apps/android/assistant-holder` 的进程/权限模型未逐行审查，仅由 Spec 轴覆盖其构建配置。
- Motion 轴（手势速度交接、可中断 Morph、reduced motion 消费）不在本轴范围，见独立 Motion 审查。