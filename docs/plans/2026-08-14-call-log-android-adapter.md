# Android Call Log Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 open-android-intelligence Android 应用交付一个默认关闭、只读、权限与策略双重约束的通话记录适配器，使四类通话元数据能够按需读取或经独立 AES-GCM outbox 低频同步，同时不引入拨号、监听、录音、voicemail、写入或任意网络接口。

**Architecture:** 在平台无关的 `:capability-ports` 中冻结通话方向、号码展示、字段释放、历史与本机周期授权合约；在独立 `:call-log-collector` 中封装 CallLog ContentProvider、策略 authority、加密 sync state、wire codec、reconciliation、调度和撤权状态机。只把 SMS 已经验证过的“恢复 durable event → 三重 egress fence → 已配对 transport → 认证 ACK”抽入 `:capability-sync-runtime`，Provider、游标、wire 和本机策略保持能力专有。

**Tech Stack:** Kotlin 2.1.20、Android API 35/min API 34、AGP 8.9.2、Gradle 8.12、JDK 17、Android ContentResolver/CallLog.Calls/JobScheduler/Android Keystore AES-256-GCM、Kotlin coroutines 1.9.0、JUnit 4.13.2、TypeScript 7.0.2、Vitest 4.1.10、Ajv 8.20.0、JSON Schema Draft 2020-12。

## Global Constraints

- 上位规格固定为 `docs/superpowers/specs/2026-08-14-call-log-android-adapter-design.md`；发现实现所需语义与规格冲突时停止相关 task，先修订 spec 与 plan 并取得用户确认。
- 本计划只交付通话记录适配器；联系人、日历/闹钟、传感器、屏幕和后台命令继续作为后续独立垂直切片。
- 目标平台是 Android 14（API 34）及以上；compile/target SDK 保持 35，minSdk 保持 34，Java/JVM target 保持 17。
- 主 APK 唯一新增的电话相关权限是 `android.permission.READ_CALL_LOG`；`android.hardware.telephony` 必须保持 `required=false`。
- 禁止声明或使用 `WRITE_CALL_LOG`、`CALL_PHONE`、`ANSWER_PHONE_CALLS`、`READ_PHONE_STATE`、`READ_PRECISE_PHONE_STATE`、`READ_VOICEMAIL`、`WRITE_VOICEMAIL`、`RECORD_AUDIO`、`PROCESS_OUTGOING_CALLS`。
- 唯一 Provider URI 是 `CallLog.Calls.CONTENT_URI`；禁止 `CONTENT_URI_WITH_VOICEMAIL`、`CONTENT_FILTER_URI`、`CallLog.CONTENT_URI` 和任何厂商 URI。
- Provider projection 恰好是 `_ID`、`TYPE`、`DATE`、`DURATION`、`NUMBER`、`NUMBER_PRESENTATION`；Agent 不能提供 URI、projection、selection、列名、sort order、limit 或 cursor。
- 只接受 `INCOMING`、`OUTGOING`、`MISSED`、`REJECTED`；voicemail、blocked、answered externally 在 SQL 层排除，未知/OEM type 或 presentation 使整批失败。
- 对方号码只有在本机 grant 与 request 都选择 `NUMBER`、presentation 为 `ALLOWED`、值非空白且 UTF-8 不超过 256 bytes 时才释放；不做 E.164 猜测，不查联系人名称。
- `CallHistoryPolicy.fromEpochMs` 为 null 或非负；`maxRecords` 为 `1..10_000`。按需结果按 `(date DESC,id DESC)`，自动批次按 `(date ASC,id ASC)`。
- 通话 outbox 文件固定为 `noBackupFilesDir/call-log-outbox-v1.aesgcm`，alias 固定为 `open_android_intelligence_call_log_outbox_v1`。
- 通话 sync state 文件固定为 `noBackupFilesDir/call-log-sync-state-v1.aesgcm`，alias 固定为 `open_android_intelligence_call_log_state_v1`；两个 alias 不得与 SMS/notification 共用。
- AES-GCM envelope 固定为 12-byte random IV、authenticated ciphertext、128-bit tag；通话行、号码、wire 和 sync state 不得明文落盘。
- `CapabilityOutboxStore` 的 byte-key 构造器只接受 32 bytes；Android Keystore 生成器继续固定 `setKeySize(256)`，不能以既有 128/192-bit 兼容分支削弱本切片。
- 单 capability outbox 最多 10,000 events，单 event wire 最多 4 MiB；满容量拒绝新记录，不驱逐旧记录，不推进 cursor。
- `recordId = call:<positiveDecimalLong>`；`eventId = call:<sourceEpoch>:<providerId>`；source epoch 从 1 开始，不允许为 0 或回绕。
- source epoch 到达 `ULong.MAX_VALUE` 后，当前 pairing generation 永久停止该来源；只有新 pairing generation 或新安装身份可以恢复。
- 自动同步严格执行“完整标准化 → encode → durable enqueue → durable cursor advance → egress”；任何失败不得跳过当前事件去确认后续 cursor。
- 通用 dispatcher 必须在 transport open 前、open 后、sendControl 前各检查一次最新 egress policy。
- 关闭来源或改变既有 enabled policy 时先持久化 `REVOKING`；只有停止采集、清 outbox、删除旧 key、重置 state 后才能提交最终状态。
- 周期选项恰好是 `MANUAL`、`MINUTES_15`、`MINUTES_30`、`MINUTES_60`；JobService 不导出并受 `BIND_JOB_SERVICE` 保护。
- 不声明 `RECEIVE_BOOT_COMPLETED`，不注册 boot receiver，不把周期采集描述成 reboot-resilient。
- 不添加 socket、URL、HTTP client、VPN、Accessibility、MediaProjection、shell、process execution、phone listener/callback、InCallService、CallScreeningService、BroadcastReceiver 或 ContentObserver。
- 日志和异常不得包含号码、通话时间、Provider ID、cursor、selection args、wire、ACK wire 或 Android exception message。
- 新代码不得以 `catch (Throwable)` 吞掉 `VirtualMachineError`、`LinkageError` 或 `ThreadDeath`；`CancellationException` 单独处理，其余可恢复边界只捕获 `Exception` 或更窄类型。
- 当前 call record wire 与 SMS MVP record wire 同层；本计划不迁移 canonical P0a device_event envelope，也不新增 Bridge/Hermes/OpenClaw 通话产品操作。
- 每个 task 使用 TDD：先写聚焦失败测试并看到预期 RED，再写最小实现，运行聚焦测试与受影响回归，最后只暂存该 task 文件并使用中文提交说明。
- 工作区中的未跟踪 `third_party/` 和其他用户改动不得暂存、修改或删除。
- JVM、TypeScript 和 SDK-free 通过只证明 host/source 边界；没有受控 installer allowlist 与设备证据时，不宣称 READ_CALL_LOG 正向设备路径就绪。

## Planned Repository Map

```text
apps/android/
  capability-ports/
    src/main/kotlin/com/openandroidintelligence/capability/CallLogCapabilityContracts.kt
    src/main/kotlin/com/openandroidintelligence/capability/CapabilityPorts.kt
    src/main/kotlin/com/openandroidintelligence/capability/CapabilityProviderContracts.kt
    src/test/kotlin/com/openandroidintelligence/capability/CallLogCapabilityContractsTest.kt
    src/test/kotlin/com/openandroidintelligence/capability/LocalCallLogAutoSendAuthorizerTest.kt
  capability-sync-runtime/
    build.gradle.kts
    src/main/kotlin/com/openandroidintelligence/sync/CapabilityOutboxDispatcher.kt
    src/test/kotlin/com/openandroidintelligence/sync/CapabilityOutboxDispatcherTest.kt
  call-log-collector/
    build.gradle.kts
    src/main/kotlin/com/openandroidintelligence/calls/
      CallLogReader.kt
      AndroidCallLogReader.kt
      AndroidCallLogAvailability.kt
      CallLogSettingsAuthority.kt
      CallLogSyncState.kt
      CallLogAudit.kt
      AndroidCallLogCapabilityProvider.kt
      CallLogWireCodec.kt
      StrictCallLogWireDecoder.kt
      CallLogAutoSyncCoordinator.kt
      CallLogSyncScheduler.kt
      CallLogSyncJobService.kt
      CallLogRevocationCoordinator.kt
    src/test/kotlin/com/openandroidintelligence/calls/
      CallLogTestFixtures.kt
      AndroidCallLogReaderTest.kt
      AndroidCallLogAvailabilityTest.kt
      CallLogSettingsAuthorityTest.kt
      CallLogSyncStateTest.kt
      AndroidCallLogCapabilityProviderTest.kt
      CallLogWireCodecTest.kt
      CallLogAutoSyncCoordinatorTest.kt
      CallLogSyncSchedulerTest.kt
      CallLogSyncJobServiceTest.kt
      CallLogRevocationCoordinatorTest.kt
  core-model/
    src/main/kotlin/com/openandroidintelligence/core/model/CapabilityOutboxContracts.kt
    src/test/kotlin/com/openandroidintelligence/core/model/CapabilityOutboxContractsTest.kt
  encrypted-store/
    src/main/kotlin/com/openandroidintelligence/encrypted/store/AesGcmEncryptedBlobStore.kt
    src/main/kotlin/com/openandroidintelligence/encrypted/store/AndroidKeystoreOutboxKeyProvider.kt
    src/main/kotlin/com/openandroidintelligence/encrypted/store/CapabilityOutboxStore.kt
    src/test/kotlin/com/openandroidintelligence/encrypted/store/AesGcmEncryptedBlobStoreTest.kt
    src/test/kotlin/com/openandroidintelligence/encrypted/store/CapabilityOutboxStoreTest.kt
  sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsAutoSyncCoordinator.kt
  app/
    src/main/AndroidManifest.xml
    src/main/kotlin/com/openandroidintelligence/mobile/CallLogComposition.kt
    src/main/kotlin/com/openandroidintelligence/mobile/CallLogSettingsState.kt
    src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt
    src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt
    src/test/kotlin/com/openandroidintelligence/mobile/CallLogCompositionTest.kt
    src/test/kotlin/com/openandroidintelligence/mobile/CallLogSettingsStateTest.kt
  tools/test_call_log_collector_static.py
mvp-contract/
  tsconfig.json
  fixtures/v1/call-record-withheld.json
  fixtures/v1/call-record-released.json
  schemas/v1/call-record.schema.json
  src/wire-codec.ts
  test/call-contract.test.ts
docs/mvp/call-log-readiness.md
```

File responsibilities are deliberately narrow:

- `CallLogCapabilityContracts.kt` owns platform-free values and local auto-send authorization; it has no Android import.
- `AndroidCallLogReader.kt` is the only call module file allowed to invoke `ContentResolver.query`.
- `AndroidCallLogCapabilityProvider.kt` owns checked scopes, all-or-nothing normalization and stable read failures.
- `CallLogSyncState.kt` owns source epoch/cursor persistence and never sends transport bytes.
- `CallLogWireCodec.kt` emits deterministic bytes; `StrictCallLogWireDecoder.kt` parses only the closed local recovery wire.
- `CapabilityOutboxDispatcher.kt` is the only generic capability egress loop and knows nothing about Providers.
- `CallLogAutoSyncCoordinator.kt` owns reconciliation and durable ordering but consumes a narrow dispatcher.
- `CallLogRevocationCoordinator.kt` owns the crash-recoverable `REVOKING` sequence.
- `CallLogComposition.kt` centralizes filenames, aliases and fail-closed wiring.
- `CallLogSettingsState.kt` keeps UI draft validation pure; `MainActivity.kt` renders it and invokes only a local controller.

---

### Task 1: Close the platform-independent call log contract

**Files:**

- Create: `apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/CallLogCapabilityContracts.kt`
- Modify: `apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/CapabilityPorts.kt`
- Modify: `apps/android/capability-ports/src/main/kotlin/com/openandroidintelligence/capability/CapabilityProviderContracts.kt`
- Create: `apps/android/capability-ports/src/test/kotlin/com/openandroidintelligence/capability/CallLogCapabilityContractsTest.kt`
- Create: `apps/android/capability-ports/src/test/kotlin/com/openandroidintelligence/capability/LocalCallLogAutoSendAuthorizerTest.kt`
- Modify: `apps/android/capability-ports/src/test/kotlin/com/openandroidintelligence/capability/CapabilityProviderContractsTest.kt`
- Modify: `apps/android/tools/test_capability_ports_static.py`

**Interfaces:**

- Consumes: `CapabilityGrant`, `AuthorizedReadScope`, `AuthorizedAutoSendScope`, `NormalizedContent` and `CallsCapabilityProvider`.
- Produces: `CallDirection`, `CallNumberPresentation`, `CallCounterpartyAccess`, `CallHistoryPolicy`, `CallLogSyncInterval`, `CapabilityFilter.Calls`, expanded `CallsMetadata`, redacted `CallsPayload`, both `normalizeCallCounterpartyNumber` overloads and `LocalCallLogAutoSendAuthorizer`.

- [ ] **Step 1: Write failing value-object and authorization tests**

Create literal tests for the closed values and validation edges:

```kotlin
@Test
fun calls_filter_rejects_empty_directions_and_authorizer_requires_exact_filter() {
    assertThrows(IllegalArgumentException::class.java) {
        CapabilityFilter.Calls(emptySet(), CallCounterpartyAccess.WITHHELD)
    }
    val grantFilter = CapabilityFilter.Calls(
        setOf(CallDirection.INCOMING, CallDirection.MISSED),
        CallCounterpartyAccess.WITHHELD,
    )
    val request = AgentDataRequest(
        "calls-read",
        MobileDataCapability.CALLS,
        DataSyncMode.ON_DEMAND,
        CapabilityFilter.Calls(setOf(CallDirection.INCOMING), CallCounterpartyAccess.WITHHELD),
        7u,
    )
    assertEquals(
        AgentRequestAuthorization.Denied(AgentRequestDenialReason.FILTER_NOT_GRANTED),
        DefaultAgentRequestAuthorizer().authorize(
            request,
            CapabilityGrant(MobileDataCapability.CALLS, grantFilter, true, false, true, 7u),
            CapabilityAvailability.READY,
        ),
    )
}

@Test
fun counterparty_number_releases_only_for_number_allowed_and_non_blank() {
    val numberScope = callReadScope(CallCounterpartyAccess.NUMBER)
    val withheldScope = callReadScope(CallCounterpartyAccess.WITHHELD)
    assertEquals(
        NormalizedContent.Released("+8613800000000"),
        normalizeCallCounterpartyNumber("+8613800000000", CallNumberPresentation.ALLOWED, numberScope),
    )
    assertEquals(
        NormalizedContent.Withheld,
        normalizeCallCounterpartyNumber("+8613800000000", CallNumberPresentation.RESTRICTED, numberScope),
    )
    assertEquals(
        NormalizedContent.Withheld,
        normalizeCallCounterpartyNumber("   ", CallNumberPresentation.ALLOWED, numberScope),
    )
    assertEquals(
        NormalizedContent.Withheld,
        normalizeCallCounterpartyNumber("+8613800000000", CallNumberPresentation.ALLOWED, withheldScope),
    )
}
```

Add tests for negative history start, max 0/10,001, all enum entries, four interval milliseconds, number UTF-8 length 256/257, positive decimal Long IDs including `Long.MAX_VALUE`, exact end-time arithmetic/overflow, redacted history/metadata/payload/released-content/event diagnostics, local auto-send ignoring `agentMayRequest` while binding both revisions, and capability/mode/revision mismatch.

- [ ] **Step 2: Run the focused tests and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :capability-ports:testDebugUnitTest \
  --tests 'com.openandroidintelligence.capability.CallLogCapabilityContractsTest' \
  --tests 'com.openandroidintelligence.capability.LocalCallLogAutoSendAuthorizerTest'
```

Expected: compilation fails because the call-specific types and normalizers do not exist.

- [ ] **Step 3: Add the exact closed contract**

```kotlin
const val MAX_CALL_LOG_BATCH_RECORDS: Int = 10_000
const val MAX_CALL_COUNTERPARTY_UTF8_BYTES: Int = 256

enum class CallDirection { INCOMING, OUTGOING, MISSED, REJECTED }
enum class CallNumberPresentation { ALLOWED, RESTRICTED, UNKNOWN, PAYPHONE, UNAVAILABLE }
enum class CallCounterpartyAccess { WITHHELD, NUMBER }

data class CallHistoryPolicy(val fromEpochMs: Long?, val maxRecords: Int) {
    init {
        require(fromEpochMs == null || fromEpochMs >= 0)
        require(maxRecords in 1..MAX_CALL_LOG_BATCH_RECORDS)
    }
    override fun toString(): String =
        "CallHistoryPolicy(fromEpochPresent=${fromEpochMs != null},maxRecords=$maxRecords)"
}

enum class CallLogSyncInterval(val periodMs: Long?) {
    MANUAL(null),
    MINUTES_15(15 * 60 * 1000L),
    MINUTES_30(30 * 60 * 1000L),
    MINUTES_60(60 * 60 * 1000L),
}

fun callEndEpochMs(startedAtEpochMs: Long, durationSeconds: Long): Long =
    Math.addExact(startedAtEpochMs, Math.multiplyExact(durationSeconds, 1_000L))
```

Replace the object filter with:

```kotlin
data class Calls(
    val directions: Set<CallDirection>,
    val counterpartyAccess: CallCounterpartyAccess,
) : CapabilityFilter {
    override val capability = MobileDataCapability.CALLS
    init { require(directions.isNotEmpty()) }
    fun canonicalDirections(): List<CallDirection> =
        CallDirection.entries.filter(directions::contains)
}
```

Every asynchronous/persistent caller copies directions with `toSet()`; query/wire code calls `canonicalDirections()` rather than iterating the caller Set.

- [ ] **Step 4: Replace provisional metadata/content**

Move `CallsMetadata` into the new file and implement:

```kotlin
data class CallsMetadata(
    override val recordId: String,
    override val observedAtEpochMs: Long,
    val direction: CallDirection,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationSeconds: Long,
    val numberPresentation: CallNumberPresentation,
) : CapabilityMetadata {
    init {
        requireMetadata(recordId, observedAtEpochMs)
        require(Regex("call:[1-9][0-9]*").matches(recordId))
        require(recordId.removePrefix("call:").toLongOrNull() != null)
        require(startedAtEpochMs >= 0)
        require(durationSeconds >= 0)
        require(endedAtEpochMs == callEndEpochMs(startedAtEpochMs, durationSeconds))
    }
    override fun toString(): String =
        "CallsMetadata(direction=$direction,durationSeconds=$durationSeconds,numberPresentation=$numberPresentation)"
}

data class CallsPayload(
    val metadata: CallsMetadata,
    val counterpartyNumber: NormalizedContent<String>,
) : CapabilityPayload {
    override fun toString(): String =
        "CallsPayload(metadata=$metadata,counterpartyNumber=<redacted>)"
}
```

Both normalizer overloads require a CALLS scope and Calls filter; release only for `NUMBER + ALLOWED + nonblank + <=256 UTF-8 bytes`. Update `CallsPayloadNormalizer` to use metadata presentation and the renamed field. Do not enable Calls in generic `allowsContentDisclosure`.

Because a released phone number must not leak through diagnostics, change `NormalizedContent.Released.toString()` to `Released(<redacted>)`. Override `CapabilityEvent.toString()` to include capability and policy revision only, with event ID and record rendered as `<redacted>`. These generic changes also prevent accidental SMS body/event identity logging.

Add the device-local scheduler authorizer with these exact types:

```kotlin
data class LocalCallLogAutoSendRequest(
    val policyRevision: ULong,
    val authorizationRevision: ULong,
)

data class LocalCallLogAutoSendState(
    val grant: CapabilityGrant?,
    val authorizationRevision: ULong,
)

class LocalCallLogAutoSendAuthorizer {
    fun authorize(
        request: LocalCallLogAutoSendRequest,
        localState: LocalCallLogAutoSendState,
        availability: CapabilityAvailability,
    ): AuthorizedAutoSendSubscription?
}
```

It returns null unless availability is READY, capability/filter are exact, `autoSendEnabled` is true and both revisions match. It deliberately does not inspect `agentMayRequest`; its internally minted request ID is the constant `local-call-log-auto-sync`.

- [ ] **Step 5: Run contract and static regressions**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain :capability-ports:testDebugUnitTest
cd /home/djbd/项目/open-android-intelligence
python3 -m unittest apps/android/tools/test_capability_ports_static.py
```

Expected: all capability-port Kotlin and static tests pass.

- [ ] **Step 6: Commit the contract**

```bash
git add apps/android/capability-ports apps/android/tools/test_capability_ports_static.py
git diff --cached --check
git diff --cached --name-only
git commit -m "新增: 定义通话记录能力合约"
```

Expected: staged files match this task only and the Chinese commit succeeds.

---

### Task 2: Register the call module and freeze the manifest permission boundary

**Files:**

- Create: `apps/android/call-log-collector/build.gradle.kts`
- Modify: `apps/android/settings.gradle.kts`
- Modify: `apps/android/app/build.gradle.kts`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Modify: `apps/android/gradle/mvp-forbidden-surfaces.gradle.kts`
- Create: `apps/android/tools/test_call_log_collector_static.py`
- Modify: `apps/android/tools/test_sms_collector_static.py`

**Interfaces:**

- Produces registered module `:call-log-collector`, namespace `com.openandroidintelligence.calls`, and the only new phone permission `READ_CALL_LOG`.
- Consumes `:capability-ports`, `:core-model` and coroutines; no transport dependency is permitted.

- [ ] **Step 1: Write failing registration/manifest tests**

Parse every manifest and assert exact main permissions:

```python
self.assertEqual(
    [
        "android.permission.INTERNET",
        "android.permission.READ_SMS",
        "android.permission.READ_CALL_LOG",
    ],
    declared_permissions,
)
self.assertEqual("false", telephony_feature.get(ANDROID_REQUIRED))
```

Assert module registration/App dependency, forbid the ten phone permissions, and assert assistant-holder has neither dependency nor permission. Update the SMS exact permission expectation while continuing to forbid send/write/control permissions.

- [ ] **Step 2: Run static tests and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence
python3 -m unittest \
  apps/android/tools/test_call_log_collector_static.py \
  apps/android/tools/test_sms_collector_static.py
```

Expected: module, dependency and permission are missing.

- [ ] **Step 3: Add only module/permission scaffolding**

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android { namespace = "com.openandroidintelligence.calls" }
dependencies {
    implementation(project(":capability-ports"))
    implementation(project(":core-model"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
```

Register module, add App dependency and exactly:

```xml
<uses-permission android:name="android.permission.READ_CALL_LOG" />
```

Do not add a role, receiver or service yet. Add call-log-collector to the root forbidden-surface scan.

- [ ] **Step 4: Verify module and manifest scaffolding**

```bash
cd /home/djbd/项目/open-android-intelligence
python3 -m unittest \
  apps/android/tools/test_call_log_collector_static.py \
  apps/android/tools/test_sms_collector_static.py
cd apps/android
./gradlew --no-daemon --console=plain :call-log-collector:testDebugUnitTest
```

Expected: both static tests pass and the empty module test task completes.

- [ ] **Step 5: Commit the module boundary**

```bash
cd /home/djbd/项目/open-android-intelligence
git add \
  apps/android/settings.gradle.kts \
  apps/android/app/build.gradle.kts \
  apps/android/app/src/main/AndroidManifest.xml \
  apps/android/call-log-collector/build.gradle.kts \
  apps/android/gradle/mvp-forbidden-surfaces.gradle.kts \
  apps/android/tools/test_call_log_collector_static.py \
  apps/android/tools/test_sms_collector_static.py
git diff --cached --check
git diff --cached --name-only
git commit -m "新增: 注册通话记录只读模块"
```

Expected: only the seven listed module/manifest/static files are committed.

---

### Task 3: Implement the bounded CallLog.Calls reader and availability probe

**Files:**

- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/CallLogReader.kt`
- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/AndroidCallLogReader.kt`
- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/AndroidCallLogAvailability.kt`
- Create: `apps/android/call-log-collector/src/test/kotlin/com/openandroidintelligence/calls/AndroidCallLogReaderTest.kt`
- Create: `apps/android/call-log-collector/src/test/kotlin/com/openandroidintelligence/calls/AndroidCallLogAvailabilityTest.kt`
- Modify: `apps/android/tools/test_call_log_collector_static.py`

**Interfaces:**

- Produces `CallLogRow`, `CallLogQuery`, `CallLogCursor`, `CallLogReader`, `AndroidCallLogReader`, `CallLogAvailabilitySource`, `AndroidCallLogAvailability`.
- Consumes the closed call types and Android ContentResolver.

- [ ] **Step 1: Write failing query/Cursor tests**

Use a dynamic-proxy Cursor. For INCOMING+MISSED, history 1,000, cursor `(1,500,42)`, max 3, assert:

```kotlin
assertEquals(CallLogProviderTarget.CALLS, query.target)
assertEquals(3, query.limit)
assertArrayEquals(
    arrayOf("_id", "type", "date", "duration", "number", "number_presentation"),
    query.projection,
)
assertEquals(
    "date >= ? AND type IN (?,?) AND (date > ? OR (date = ? AND _id > ?))",
    query.selection,
)
assertArrayEquals(arrayOf("1000", "1", "3", "1500", "1500", "42"), query.selectionArgs)
assertEquals("date DESC, _id DESC", query.sortOrder)
```

Add null Cursor, local max truncation, close-on-success/missing-column/bad-row, four type mappings, five presentation mappings, excluded/unknown type failure, invalid ID/time/duration/overflow, and redacted diagnostics.

- [ ] **Step 2: Write failing availability-order tests**

Inject enabled/provider/permission/probe seams. Assert Disabled, PlatformUnsupported, PermissionRequired, SecurityException→PermissionRequired and Ready in that order, and assert no later seam runs after an earlier denial.

- [ ] **Step 3: Run focused tests and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :call-log-collector:testDebugUnitTest \
  --tests 'com.openandroidintelligence.calls.AndroidCallLogReaderTest' \
  --tests 'com.openandroidintelligence.calls.AndroidCallLogAvailabilityTest'
```

Expected: compilation fails because the call reader, query and availability types do not exist.

- [ ] **Step 4: Implement platform-neutral row/query types**

```kotlin
data class CallLogCursor(
    val startedAtEpochMs: Long,
    val providerId: Long,
) : Comparable<CallLogCursor> {
    init {
        require(startedAtEpochMs >= 0)
        require(providerId > 0)
    }
    override fun compareTo(other: CallLogCursor): Int =
        compareValuesBy(this, other, CallLogCursor::startedAtEpochMs, CallLogCursor::providerId)
    override fun toString(): String = "CallLogCursor(<redacted>)"
}

class CallLogRow(
    val providerId: Long,
    val direction: CallDirection,
    val startedAtEpochMs: Long,
    val durationSeconds: Long,
    val number: String?,
    val numberPresentation: CallNumberPresentation,
) {
    val endedAtEpochMs = callEndEpochMs(startedAtEpochMs, durationSeconds)
    val cursor = CallLogCursor(startedAtEpochMs, providerId)
    override fun toString(): String =
        "CallLogRow(direction=$direction,durationSeconds=$durationSeconds,numberPresentation=$numberPresentation)"
}

data class CallLogQuery(
    val history: CallHistoryPolicy,
    val directions: Set<CallDirection>,
    val cursor: CallLogCursor? = null,
) {
    override fun toString(): String =
        "CallLogQuery(directions=${directions.size},maxRecords=${history.maxRecords}," +
            "cursorPresent=${cursor != null})"
}

interface CallLogReader {
    fun query(request: CallLogQuery): List<CallLogRow>
    fun probe()
}

fun interface CallLogAvailabilitySource {
    fun current(): CapabilityAvailability
}
```

`CallLogInvalidRowException` uses only `CALL_LOG_INVALID_ROW`; `CallLogQueryException` uses only `CALL_LOG_QUERY_FAILED`. Neither retains a cause containing a Provider message.

- [ ] **Step 5: Implement the sole ContentResolver query**

Only the public Android constructor builds:

```kotlin
val uri = CallLog.Calls.CONTENT_URI.buildUpon()
    .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
    .build()
resolver.query(uri, projection, selection, selectionArgs, sortOrder)
```

Build canonical direction args, then history/direction/cursor clauses. Never append SQL LIMIT. Resolve six indexes before reading; missing columns become query failure, invalid values become invalid-row failure, and Cursor is always closed with `use`. `probe()` performs the same six-column query with max 1 and all directions.

- [ ] **Step 6: Implement ordered availability**

`AndroidCallLogAvailability` accepts `localEnabled: () -> Boolean`; its public Android seams are:

```kotlin
context.packageManager.resolveContentProvider(CallLog.AUTHORITY, 0) != null
context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
reader.probe()
```

Probe occurs only after local enabled, provider and permission gates. SecurityException is permission-required; other probe failure is platform-unsupported.

The production constructor is:

```kotlin
class AndroidCallLogAvailability(
    context: Context,
    private val reader: CallLogReader,
    private val localEnabled: () -> Boolean,
) : CallLogAvailabilitySource
```

An internal constructor accepts four zero-argument test seams in the same order. Neither constructor caches the result.

- [ ] **Step 7: Extend and run the reader static gate**

Static tests assert the sole URI, LIMIT_PARAM_KEY, six fields, no SQL LIMIT, no insert/update/delete/bulkInsert, no forbidden CallLog URIs, phone managers/listeners/services, sockets/URLs, Accessibility/MediaProjection/process APIs.

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain :call-log-collector:testDebugUnitTest
cd /home/djbd/项目/open-android-intelligence
python3 -m unittest apps/android/tools/test_call_log_collector_static.py
```

Expected: all reader/availability tests and the static query-surface gate pass.

- [ ] **Step 8: Commit the reader**

```bash
git add apps/android/call-log-collector/src apps/android/tools/test_call_log_collector_static.py
git diff --cached --check
git diff --cached --name-only
git commit -m "新增: 对接只读通话记录 Provider"
```

Expected: only call reader/availability source, tests and its static gate are committed.

---

### Task 4: Persist deny-first local settings and the REVOKING phase

**Files:**

- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/CallLogSettingsAuthority.kt`
- Create: `apps/android/call-log-collector/src/test/kotlin/com/openandroidintelligence/calls/CallLogSettingsAuthorityTest.kt`

**Interfaces:**

- Produces `CallLogLocalPolicy`, `CallLogSettingsPhase`, `CallLogSettingsSnapshot`, persistence seams, file persistence and `PersistentCallLogSettingsAuthority`.
- Consumes closed call values and CapabilityGrant.

- [ ] **Step 1: Write failing persistence/state tests**

Test fresh Disabled/no grant, Disabled-to-Revoking bootstrap, Enabled round-trip, canonical/copy-safe directions, Revoking restart/no grant, strictly increasing policy and authorization revisions, corrupt/truncated/unknown/trailing state fail closed, epochExhausted restart, and redacted diagnostics.

```kotlin
@Test
fun revoking_target_survives_restart_and_never_exposes_a_grant() {
    val persistence = InMemoryCallLogSettingsPersistence()
    val authority = enabledAuthority(persistence)
    authority.beginRevocation(
        targetEpoch = 2u,
        targetPolicyRevision = 8u,
        targetPolicy = policy(8u, CallCounterpartyAccess.NUMBER),
        authorizationRevision = 12u,
    )
    val restored = PersistentCallLogSettingsAuthority(persistence)
    assertTrue(restored.snapshot().phase is CallLogSettingsPhase.Revoking)
    assertNull(restored.capabilityGrant())
}
```

- [ ] **Step 2: Run focused test and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :call-log-collector:testDebugUnitTest \
  --tests 'com.openandroidintelligence.calls.CallLogSettingsAuthorityTest'
```

Expected: compilation fails because the call settings policy, phases and authority do not exist.

- [ ] **Step 3: Implement policy and phases**

```kotlin
data class CallLogLocalPolicy(
    val historyPolicy: CallHistoryPolicy,
    val directions: Set<CallDirection>,
    val counterpartyAccess: CallCounterpartyAccess,
    val syncInterval: CallLogSyncInterval,
    val onDemandEnabled: Boolean,
    val autoSendEnabled: Boolean,
    val agentMayRequest: Boolean,
    val policyRevision: ULong,
) {
    init {
        require(directions.isNotEmpty())
        require(policyRevision > 0u)
    }
    fun filter() = CapabilityFilter.Calls(directions.toSet(), counterpartyAccess)
    override fun toString(): String =
        "CallLogLocalPolicy(directions=${directions.size},counterpartyAccess=$counterpartyAccess," +
            "syncInterval=$syncInterval,onDemand=$onDemandEnabled,autoSend=$autoSendEnabled," +
            "agentMayRequest=$agentMayRequest,policyRevision=$policyRevision)"
}

sealed interface CallLogSettingsPhase {
    data object Disabled : CallLogSettingsPhase
    data class Enabled(val policy: CallLogLocalPolicy) : CallLogSettingsPhase
    data class Revoking(
        val targetEpoch: ULong,
        val targetPolicyRevision: ULong,
        val targetPolicy: CallLogLocalPolicy?,
    ) : CallLogSettingsPhase {
        override fun toString(): String =
            "Revoking(targetPolicyRevision=$targetPolicyRevision," +
                "targetEnabled=${targetPolicy != null})"
    }
}
```

Snapshot contains phase, authorizationRevision, corrupted and epochExhausted. Its `toString` reports only the phase class, revisions and Boolean evidence; it never expands a target epoch/history policy. Grant exists only for healthy non-exhausted Enabled state.

The authority exposes only read access publicly and transition primitives internally:

```kotlin
class PersistentCallLogSettingsAuthority(
    private val persistence: CallLogSettingsPersistence,
) {
    fun snapshot(): CallLogSettingsSnapshot
    fun capabilityGrant(): CapabilityGrant?
    internal fun beginRevocation(
        targetEpoch: ULong,
        targetPolicyRevision: ULong,
        targetPolicy: CallLogLocalPolicy?,
        authorizationRevision: ULong,
        epochExhausted: Boolean = false,
    )
    internal fun commitRevocationTarget()
}
```

`beginRevocation` is the only path from Disabled to Enabled bootstrap and the only path out of an existing Enabled policy; there is no direct fresh-enable commit primitive. `commitRevocationTarget` derives its final Enabled/Disabled phase only from the persisted Revoking target. It cannot accept a replacement policy argument. The local controller is not created until Task 11, after cleanup effects exist.

- [ ] **Step 4: Implement versioned atomic persistence**

Encode:

```text
magic OPEN_ANDROID_INTELLIGENCE_CALL_SETTINGS_V1
phase tag 0 disabled / 1 enabled / 2 revoking
authorization revision as ULong bits
epochExhausted boolean
enabled policy or revoking target epoch/revision/nullable target policy
```

Policy encoding order is optional history start, max records, four-bit canonical direction mask, counterparty ordinal, interval ordinal, three mode booleans, policy revision. Reject unknown ordinals, invalid revisions and trailing bytes. File persistence writes atomically under `noBackupFilesDir/call-log-settings-v1.bin`.

- [ ] **Step 5: Run the settings authority suite**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain :call-log-collector:testDebugUnitTest
```

Expected: fresh, Enabled, Revoking, corruption and restart tests all pass.

- [ ] **Step 6: Commit the settings authority**

```bash
cd /home/djbd/项目/open-android-intelligence
git add apps/android/call-log-collector/src
git diff --cached --check
git diff --cached --name-only
git commit -m "新增: 持久化通话记录本机授权"
```

Expected: the commit contains only Task 4 production/test files.

---

### Task 5: Add encrypted sync state, outbox clearing and key destruction

**Files:**

- Modify: `apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/CapabilityOutboxContracts.kt`
- Create: `apps/android/core-model/src/test/kotlin/com/openandroidintelligence/core/model/CapabilityOutboxContractsTest.kt`
- Create: `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/AesGcmEncryptedBlobStore.kt`
- Modify: `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/AndroidKeystoreOutboxKeyProvider.kt`
- Modify: `apps/android/encrypted-store/src/main/kotlin/com/openandroidintelligence/encrypted/store/CapabilityOutboxStore.kt`
- Create: `apps/android/encrypted-store/src/test/kotlin/com/openandroidintelligence/encrypted/store/AesGcmEncryptedBlobStoreTest.kt`
- Modify: `apps/android/encrypted-store/src/test/kotlin/com/openandroidintelligence/encrypted/store/CapabilityOutboxStoreTest.kt`
- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/CallLogSyncState.kt`
- Create: `apps/android/call-log-collector/src/test/kotlin/com/openandroidintelligence/calls/CallLogSyncStateTest.kt`
- Modify: `apps/android/call-log-collector/build.gradle.kts`

**Interfaces:**

- Produces `CapabilityOutbox.clear`, `AesGcmKeyProvider.delete`, `AesGcmEncryptedBlobStore`, `CallLogSyncState`, `CallLogSyncStateStore`, `EncryptedCallLogSyncStateStore`.
- Consumes `EncryptedOutboxPersistence` and independent AES-256 keys.

- [ ] **Step 1: Write failing encrypted blob/outbox tests**

Assert persistence contains no plaintext, a fixed 32-byte test key restarts correctly, 16/24-byte outbox keys are rejected, wrong key/tag/truncation/unknown envelope/trailing bytes fail with `ENCRYPTED_BLOB_CORRUPTED`, all arrays are copied, clear removes every event/ciphertext, clear persistence failure restores memory, and Android key deletion calls `KeyStore.deleteEntry(alias)`. Add a core-model test proving `CapabilityDurableEvent.toString()` contains capability/revision but neither event ID, record ID nor wire bytes. A test persistence throwing `AssertionError` must propagate rather than be wrapped as corruption.

- [ ] **Step 2: Write failing sync-state tests**

```kotlin
@Test
fun cursor_advances_only_in_expected_epoch_and_strictly_forward() {
    val store = encryptedStateStore()
    store.initialize(CallLogSyncState(1u, null, 7u))
    assertTrue(store.advance(1u, CallLogCursor(100, 1), 7u))
    assertFalse(store.advance(1u, CallLogCursor(100, 1), 7u))
    assertFalse(store.advance(1u, CallLogCursor(99, 2), 7u))
    assertThrows(IllegalArgumentException::class.java) {
        store.advance(2u, CallLogCursor(101, 2), 7u)
    }
}

@Test
fun reset_rotates_key_resets_cursor_and_never_wraps_epoch() {
    val keys = RecordingKeyProvider()
    val store = encryptedStateStore(keys)
    store.initialize(CallLogSyncState(1u, CallLogCursor(100, 1), 7u))
    store.resetWithRotatedKey(CallLogSyncState(2u, null, 8u))
    assertEquals(1, keys.deleteCalls)
    assertEquals(CallLogSyncState(2u, null, 8u), store.snapshot())
    assertThrows(CallLogSourceEpochExhausted::class.java) {
        nextCallLogSourceEpoch(ULong.MAX_VALUE)
    }
}
```

Add zero epoch, invalid cursor, policy mismatch, restart, corrupt/truncated/unknown/trailing state, missing-file uninitialized state and redacted diagnostics.

Also prove `resetWithRotatedKey(target)` succeeds from an uninitialized store, from corrupt ciphertext without attempting decryption, after the old alias was already deleted, and when repeated with the same persisted target after each internal crash cut.

- [ ] **Step 3: Run focused tests and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :encrypted-store:testDebugUnitTest \
  :call-log-collector:testDebugUnitTest \
  --tests 'com.openandroidintelligence.encrypted.store.AesGcmEncryptedBlobStoreTest' \
  --tests 'com.openandroidintelligence.calls.CallLogSyncStateTest'
```

Expected: new key/blob/state APIs do not compile.

- [ ] **Step 4: Implement the reviewed AES-GCM blob primitive**

```kotlin
interface AesGcmKeyProvider {
    fun getOrCreate(): SecretKey
    fun delete()
}

class AesGcmEncryptedBlobStore(
    private val persistence: EncryptedOutboxPersistence,
    private val key: SecretKey,
) {
    fun readPlaintext(): ByteArray?
    fun writePlaintext(value: ByteArray)
    fun clearCiphertext()
}
```

Require AES-256, fresh 12-byte SecureRandom IV, 128-bit GCM tag, minimum IV+tag+one ciphertext byte, defensive copies and `EncryptedBlobCorrupted` without raw crypto messages. Make `AndroidKeystoreOutboxKeyProvider` implement the interface and add `delete()`.

Add `suspend fun clear()` to `CapabilityOutbox`; implement transactional `clearBlocking` in `CapabilityOutboxStore`. Change the byte-array outbox constructor to accept exactly 32 bytes while retaining the Android Keystore SecretKey constructor. Change `CapabilityDurableEvent.toString()` to `CapabilityDurableEvent(capability=<value>,policyRevision=<value>,identity=<redacted>,wire=<redacted>)`. Update every CapabilityOutbox fake, including the SMS coordinator fake, without changing runtime behavior.

While touching restore/persist code, replace broad `catch (Throwable)` with recoverable `Exception` handling after an explicit CancellationException branch where applicable.

- [ ] **Step 5: Implement encrypted call sync state**

```kotlin
data class CallLogSyncState(
    val sourceEpoch: ULong,
    val cursor: CallLogCursor?,
    val policyRevision: ULong,
) {
    init { require(sourceEpoch > 0u) }
    override fun toString(): String =
        "CallLogSyncState(cursorPresent=${cursor != null},policyRevision=$policyRevision)"
}

interface CallLogSyncStateStore {
    fun snapshot(): CallLogSyncState?
    fun initialize(state: CallLogSyncState)
    fun advance(
        expectedSourceEpoch: ULong,
        cursor: CallLogCursor,
        policyRevision: ULong,
    ): Boolean
    fun resetWithRotatedKey(state: CallLogSyncState)
}

class EncryptedCallLogSyncStateStore(
    private val persistence: EncryptedOutboxPersistence,
    private val keyProvider: AesGcmKeyProvider,
) : CallLogSyncStateStore
```

Encode magic `OPEN_ANDROID_INTELLIGENCE_CALL_SYNC_STATE_V1`, source epoch raw bits, nullable cursor and policy revision. Construction does not eagerly decrypt state. `snapshot()` performs authenticated restore and missing file returns null; authenticated/format failure throws `CallLogSyncStateCorrupted("CALL_LOG_SYNC_STATE_CORRUPTED")`, never an empty cursor. `resetWithRotatedKey` never reads or decodes old ciphertext: it idempotently deletes the old alias if present, clears ciphertext if present, creates a fresh key, writes the exact persisted target and updates memory only after success. Repeating it after any partial failure is safe and cannot derive a different epoch/revision.

Add `implementation(project(":encrypted-store"))` to `call-log-collector/build.gradle.kts`; no other new dependency belongs in this task.

- [ ] **Step 6: Run encrypted-state regressions**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :core-model:testDebugUnitTest \
  :encrypted-store:testDebugUnitTest \
  :sms-collector:testDebugUnitTest \
  :call-log-collector:testDebugUnitTest
```

Expected: core, encrypted-store, SMS and Call Log tests all pass.

- [ ] **Step 7: Commit encrypted state support**

```bash
cd /home/djbd/项目/open-android-intelligence
git add apps/android/core-model apps/android/encrypted-store apps/android/call-log-collector
git diff --cached --check
git diff --cached --name-only
git commit -m "新增: 加密通话游标与撤权清理能力"
```

Expected: the commit contains only Task 5 contracts, stores, dependencies and tests.

---

### Task 6: Implement the typed call capability provider

**Files:**

- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/AndroidCallLogCapabilityProvider.kt`
- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/CallLogAudit.kt`
- Create: `apps/android/call-log-collector/src/test/kotlin/com/openandroidintelligence/calls/CallLogTestFixtures.kt`
- Create: `apps/android/call-log-collector/src/test/kotlin/com/openandroidintelligence/calls/AndroidCallLogCapabilityProviderTest.kt`

**Interfaces:**

- Produces `CallLogProviderFailure`, `CallLogProviderException`, closed audit values/sink and `AndroidCallLogCapabilityProvider : CallsCapabilityProvider`.
- Consumes checked scopes, current settings, availability, reader and sync state.

- [ ] **Step 1: Write failing all-or-nothing provider tests**

Prove:

- on-demand is descending and cursor-free;
- auto-send is ascending by time then ID and uses durable cursor;
- directions are applied in query and payload;
- withheld and NUMBER/presentation branches are exact;
- source epoch 3 yields event ID `call:3:42`, record ID `call:42`, CALLS capability and exact revision;
- disabled/unsupported/permission missing return stable codes without query;
- `SecurityException("secret")` maps permission-required without `secret`;
- missing column/general query maps query-failed;
- invalid type/presentation/value maps invalid-row;
- one bad row discards the whole on-demand list and emits no auto events;
- phase/revision change during read discards results as policy-stale;
- CancellationException is rethrown;
- on-demand never mutates outbox/state/scheduler.
- audit output contains only the fixed capability, revision, result code, counts and latency bucket; it excludes IDs, epoch, number, call timestamps and exception messages.

- [ ] **Step 2: Run focused test and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :call-log-collector:testDebugUnitTest \
  --tests 'com.openandroidintelligence.calls.AndroidCallLogCapabilityProviderTest'
```

Expected: compilation fails because the typed call provider, failures and audit values do not exist.

- [ ] **Step 3: Implement stable failures and current-policy fencing**

```kotlin
enum class CallLogProviderFailure(val code: String) {
    SOURCE_DISABLED("CALL_LOG_DISABLED"),
    PERMISSION_REQUIRED("CALL_LOG_PERMISSION_REQUIRED"),
    PLATFORM_UNSUPPORTED("CALL_LOG_PLATFORM_UNSUPPORTED"),
    QUERY_FAILED("CALL_LOG_QUERY_FAILED"),
    INVALID_ROW("CALL_LOG_INVALID_ROW"),
    POLICY_STALE("CALL_LOG_POLICY_STALE"),
}

class CallLogProviderException(val failure: CallLogProviderFailure) :
    IllegalStateException(failure.code)

enum class CallLogLatencyBucket { LT_100_MS, LT_1_S, LT_10_S, GE_10_S }

enum class CallLogAuditResultCode {
    COMPLETE,
    CALL_LOG_DISABLED,
    CALL_LOG_PERMISSION_REQUIRED,
    CALL_LOG_PLATFORM_UNSUPPORTED,
    CALL_LOG_QUERY_FAILED,
    CALL_LOG_INVALID_ROW,
    CALL_LOG_POLICY_STALE,
    CALL_LOG_OUTBOX_FULL,
    CALL_LOG_OUTBOX_CORRUPTED,
    CALL_LOG_SYNC_STATE_CORRUPTED,
    CALL_LOG_CURSOR_NOT_DURABLE,
    CALL_LOG_POLICY_REVOKED,
    CALL_LOG_PAIRING_UNAVAILABLE,
    CALL_LOG_UNSUPPORTED_CAPABILITY,
    CALL_LOG_TRANSPORT_FAILURE,
    CALL_LOG_ACK_REJECTED,
    CALL_LOG_CANCELLED,
    CALL_LOG_REVOCATION_INCOMPLETE,
    CALL_LOG_SOURCE_EPOCH_EXHAUSTED,
}

data class CallLogAuditEvent(
    val policyRevision: ULong,
    val resultCode: CallLogAuditResultCode,
    val readCount: Int,
    val acceptedCount: Int,
    val acknowledgedCount: Int,
    val latencyBucket: CallLogLatencyBucket,
) {
    val capability: String get() = "calls.metadata"
}

fun interface CallLogAuditSink {
    fun record(event: CallLogAuditEvent)
}

class AndroidCallLogCapabilityProvider(
    private val reader: CallLogReader,
    private val settings: PersistentCallLogSettingsAuthority,
    private val availability: CallLogAvailabilitySource,
    private val syncState: CallLogSyncStateStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val auditSink: CallLogAuditSink = CallLogAuditSink {},
) : CallsCapabilityProvider
```

Before query require healthy Enabled snapshot and exact filter/revision. Apply availability. After building an immutable complete list, re-read and require identical phase, authorization revision, policy revision and filter.

`CallLogAuditEvent` validates nonnegative counts and uses a fixed allowlist-only `toString`. Bucket elapsed monotonic time at 100 ms, 1 s and 10 s boundaries; never put raw elapsed milliseconds in logs or metric labels.

- [ ] **Step 4: Normalize complete batches before exposure**

Validate every row direction, construct expanded metadata, invoke the correct counterparty normalizer, and retain descending order for read. For auto-send require initialized state with matching policy, map all rows first, sort ascending, then emit deterministic epoch-bearing event IDs. Catch typed failures only; propagate cancellation.

Add the single identity-to-cursor helper used later by the coordinator:

```kotlin
fun CallsMetadata.toCallLogCursor(): CallLogCursor =
    CallLogCursor(
        startedAtEpochMs = startedAtEpochMs,
        providerId = recordId.removePrefix("call:").toLong(),
    )
```

Task 1 validation makes this conversion total; no other production code reparses a call record ID.

- [ ] **Step 5: Run provider and contract regressions**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :capability-ports:testDebugUnitTest \
  :call-log-collector:testDebugUnitTest
```

Expected: all authorization, provider, ordering, failure and audit tests pass.

- [ ] **Step 6: Commit the typed provider**

```bash
cd /home/djbd/项目/open-android-intelligence
git add apps/android/call-log-collector/src
git diff --cached --check
git diff --cached --name-only
git commit -m "新增: 实现通话记录类型化读取"
```

Expected: only Task 6 production/test files are committed.

---

### Task 7: Freeze the call record schema and deterministic codecs

**Files:**

- Create: `mvp-contract/schemas/v1/call-record.schema.json`
- Create: `mvp-contract/fixtures/v1/call-record-withheld.json`
- Create: `mvp-contract/fixtures/v1/call-record-released.json`
- Create: `mvp-contract/tsconfig.json`
- Modify: `mvp-contract/src/wire-codec.ts`
- Create: `mvp-contract/test/call-contract.test.ts`
- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/CallLogWireCodec.kt`
- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/StrictCallLogWireDecoder.kt`
- Create: `apps/android/call-log-collector/src/test/kotlin/com/openandroidintelligence/calls/CallLogWireCodecTest.kt`

**Interfaces:**

- Produces schema ID `urn:open-android-intelligence:mvp:call-record:v1`, TypeScript `WireCallRecord`/encode/validate, Kotlin `CallLogEventCodec`, deterministic codec and redacted `DecodedCallLogRecord`.
- Consumes CallsPayload, source epoch, policy revision and CallLogCursor.

- [ ] **Step 1: Write TypeScript/schema RED tests**

Freeze the approved withheld/released objects. Test exact keys, nested frozen objects, canonical decimals, Long.MAX and next value, record/cursor identity, positive source epoch, record revision exactly 1, closed enums, number 1..256 UTF-8 bytes, disjoint counterparty branches, and extra/missing/null/wrong-type values. Feed raw duplicate-key and trailing-byte documents to `decodeCallRecordJson` and require `WIRE_RECORD_UNREPRESENTABLE`.

```typescript
expect(validateWireCallRecord({ ...wire, record_id: "call:01" })).toBe(false);
expect(validateWireCallRecord({ ...wire, source_epoch: "0" })).toBe(false);
expect(validateWireCallRecord({
  ...wire,
  metadata: { ...wire.metadata, direction: "blocked" },
})).toBe(false);
expect(validateWireCallRecord({
  ...wire,
  counterparty_number: { state: "withheld", value: "+1" },
})).toBe(false);
```

- [ ] **Step 2: Write Kotlin codec/strict decoder RED tests**

Assert exact UTF-8 golden bytes and fixed order. Reject duplicate root/nested keys, reordered canonical field, trailing whitespace/bytes, unknown escape, lone surrogate, missing/extra field, >256-byte number, noncanonical integer, identity/revision mismatch and unknown enum. `decode(encode(record))` returns only recovery identity/revisions/cursor and redacts diagnostics.

- [ ] **Step 3: Run both focused suites and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence
./tools/run-node24 npx --no-install vitest --root . run \
  mvp-contract/test/call-contract.test.ts
cd apps/android
./gradlew --no-daemon --console=plain \
  :call-log-collector:testDebugUnitTest \
  --tests 'com.openandroidintelligence.calls.CallLogWireCodecTest'
```

Expected: TypeScript reports missing call exports/schema and Kotlin reports missing codec/decoder APIs.

- [ ] **Step 4: Implement schema and TypeScript codec**

Root requires exactly:

```text
kind, record_id, source_epoch, record_revision,
cursor_started_at_epoch_ms, cursor_provider_id,
captured_at_epoch_ms, capture_revision, policy_revision,
metadata, counterparty_number
```

Metadata requires direction, start/end/duration/observed/presentation. Root and nested objects set both `additionalProperties:false` and `unevaluatedProperties:false`. Canonical numeric patterns match SMS bounds, except source epoch is positive and record revision is constant `"1"`. Counterparty uses disjoint exact-key `oneOf`.

Create `mvp-contract/tsconfig.json` extending `../tsconfig.json`, setting `"noEmit": true` and `"allowImportingTsExtensions": true`, and including the TypeScript files below `src` and `test`. Add frozen TypeScript types, validator and encoder beside SMS. Encoder builds fields in approved order and validates before return.

Also produce:

```typescript
export const decodeCallRecordJson = (wire: Uint8Array): WireCallRecord
```

It uses a small recursive JSON byte scanner that rejects duplicate object keys before value materialization, invalid UTF-8/escapes, non-JSON numbers and trailing bytes. It then calls `validateWireCallRecord`; it never accepts a document through `JSON.parse` alone.

- [ ] **Step 5: Implement deterministic Kotlin codec**

```kotlin
interface CallLogEventCodec {
    fun encode(
        eventId: String,
        record: CallsPayload,
        sourceEpoch: ULong,
        policyRevision: ULong,
    ): ByteArray
    fun decode(wire: ByteArray): DecodedCallLogRecord
}
```

Require event ID `call:<epoch>:<id>`, record ID `call:<id>`, source epoch >0, cursor ID/start matching record metadata, `capturedAtEpochMs == observedAtEpochMs`, `captureRevision == policyRevision`, exact metadata/end relation and number limit. The TypeScript validator enforces the same cross-field relationships. Emit registered lowercase enums and RFC 8259 strings directly; never use Map iteration.

- [ ] **Step 6: Implement the closed canonical decoder**

Use a byte cursor with only:

```kotlin
expectByte
expectAsciiName
readJsonString
readCanonicalULong
readCanonicalPositiveLong
expectComma
expectObjectEnd
expectDocumentEnd
```

Parse frozen order, each field once, valid escapes/surrogate pairs, no controls, no duplicates/reorder/extras and immediate EOF. Return:

```kotlin
data class DecodedCallLogRecord(
    val recordId: String,
    val sourceEpoch: ULong,
    val cursor: CallLogCursor,
    val captureRevision: ULong,
    val policyRevision: ULong,
)
```

Override diagnostics to omit identity/time/number.

- [ ] **Step 7: Prove cross-language fixtures**

TypeScript validates both shared fixture files. Kotlin finds the same files by walking parents from `user.dir`, removes at most one repository text-file terminal LF before decoding, and asserts encode equals normalized fixture bytes. A separate test passes the untrimmed bytes and expects trailing-byte rejection.

- [ ] **Step 8: Run cross-language wire verification**

```bash
cd /home/djbd/项目/open-android-intelligence
./tools/run-node24 npx --no-install vitest --root . run \
  mvp-contract/test/call-contract.test.ts
./tools/run-node24 npx --no-install tsc --noEmit -p mvp-contract/tsconfig.json
cd apps/android
./gradlew --no-daemon --console=plain :call-log-collector:testDebugUnitTest
```

Expected: schemas, strict TypeScript/Kotlin decoders, fixtures and typecheck all pass.

- [ ] **Step 9: Commit the wire contract**

```bash
cd /home/djbd/项目/open-android-intelligence
git add mvp-contract apps/android/call-log-collector/src
git diff --cached --check
git diff --cached --name-only
git commit -m "新增: 冻结通话记录 wire 合约"
```

Expected: only Task 7 schema, fixtures, codecs and tests are committed.

---

### Task 8: Extract the generic capability outbox dispatcher and migrate SMS

**Files:**

- Create: `apps/android/capability-sync-runtime/build.gradle.kts`
- Create: `apps/android/capability-sync-runtime/src/main/kotlin/com/openandroidintelligence/sync/CapabilityOutboxDispatcher.kt`
- Create: `apps/android/capability-sync-runtime/src/test/kotlin/com/openandroidintelligence/sync/CapabilityOutboxDispatcherTest.kt`
- Modify: `apps/android/settings.gradle.kts`
- Modify: `apps/android/gradle/mvp-forbidden-surfaces.gradle.kts`
- Modify: `apps/android/sms-collector/build.gradle.kts`
- Modify: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsAutoSyncCoordinator.kt`
- Modify: `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsAutoSyncCoordinatorTest.kt`
- Modify: `apps/android/app/build.gradle.kts`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt`

**Interfaces:**

- Produces generic binding source, egress gate, `CapabilityPendingDispatcher`, dispatch failure/result and `CapabilityOutboxDispatcher`.
- Consumes CapabilityOutbox, PairedBridgeTransport and verified binding only.
- Preserves SMS capability literal, event IDs, retention and existing tests.

- [ ] **Step 1: Write failing generic dispatcher tests**

Test successful ordered ACK deletion; mismatched capability retained while a later expected-capability event still follows the existing SMS behavior; blocked expected event stopping later expected delivery; no pairing/open/send/receive/ACK failures; revoke before open/during open/before send; exactly three gates on success; cancellation close/retention; failed expected event blocking later expected ACK; maxAttempts 1..10; redacted results.

- [ ] **Step 2: Run new module and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain :capability-sync-runtime:testDebugUnitTest
```

Expected: module is not registered.

- [ ] **Step 3: Implement the generic dispatcher**

```kotlin
fun interface CapabilityPairedBridgeBindingSource {
    fun currentBinding(): VerifiedPairingTransportBinding?
}
fun interface CapabilityEventEgressGate {
    fun allows(event: CapabilityDurableEvent): Boolean
}
enum class CapabilityDispatchFailure {
    PAIRING_UNAVAILABLE,
    POLICY_REVOKED,
    UNSUPPORTED_CAPABILITY,
    ACK_REJECTED,
    OUTBOX_FAILURE,
    TRANSPORT_FAILURE,
    CANCELLED,
}
data class CapabilityDispatchResult(
    val acknowledged: Int,
    val retained: Int,
    val failure: CapabilityDispatchFailure?,
)

fun interface CapabilityPendingDispatcher {
    suspend fun dispatchPending(
        blockedEventIds: Set<String> = emptySet(),
    ): CapabilityDispatchResult
}

class CapabilityOutboxDispatcher(
    private val expectedCapability: String,
    private val outbox: CapabilityOutbox,
    private val transport: PairedBridgeTransport,
    private val bindingSource: CapabilityPairedBridgeBindingSource,
    private val egressGate: CapabilityEventEgressGate,
    private val maxAttempts: Int,
) : CapabilityPendingDispatcher
```

`dispatchPending(blockedEventIds = emptySet())` processes insertion order. A capability mismatch is retained, records `UNSUPPORTED_CAPABILITY`, and continues without invoking its gate or transport; this preserves the existing SMS mixed-outbox regression while physical stores remain capability-specific. A blocked expected event or a delivery failure retains it and stops before later expected-capability events, so its cursor cannot be overtaken. A successful attempt checks gate before open, after open and immediately before send. Cancellation closes and remains distinct; caught messages never enter results.

All existing SMS `catch (Throwable)` branches moved with this dispatcher are narrowed to `CancellationException`, `CapabilityOutboxAckRejected`, and recoverable `Exception`; fatal JVM errors continue outward.

The new module depends on `:core-model` and coroutines, with JUnit plus test-only `:tailnet-core` for verified binding fixtures. Add `implementation(project(":capability-sync-runtime"))` to both `sms-collector` and App; App imports the generic binding/gate types directly, so it must not rely on a transitive `implementation` dependency. Add the module to the root forbidden-surface scan.

- [ ] **Step 4: Replace SMS-private dispatch**

Remove SMS binding/gate/private dispatcher types. Construct generic dispatcher for expected capability `sms`. Add exhaustive generic→SmsSyncFailure mapping while preserving SMS capture/enqueue/cursor behavior and update App imports.

- [ ] **Step 5: Run dispatcher, SMS and App regressions**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :capability-sync-runtime:testDebugUnitTest \
  :sms-collector:testDebugUnitTest \
  :app:testDebugUnitTest
cd /home/djbd/项目/open-android-intelligence
python3 -m unittest \
  apps/android/tools/test_transport_boundary.py \
  apps/android/tools/test_sms_collector_static.py
```

Expected: generic dispatcher tests pass and every pre-existing SMS/App/static behavior remains green.

- [ ] **Step 6: Commit the dispatcher extraction**

```bash
git add \
  apps/android/capability-sync-runtime \
  apps/android/settings.gradle.kts \
  apps/android/gradle/mvp-forbidden-surfaces.gradle.kts \
  apps/android/sms-collector \
  apps/android/app/build.gradle.kts \
  apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt
git diff --cached --check
git diff --cached --name-only
git commit -m "重构: 抽取能力 outbox 投递器"
```

Expected: only the new runtime, SMS migration, build registration and required App import changes are committed.

---

### Task 9: Add call reconciliation and durable auto-sync ordering

**Files:**

- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/CallLogAutoSyncCoordinator.kt`
- Create: `apps/android/call-log-collector/src/test/kotlin/com/openandroidintelligence/calls/CallLogAutoSyncCoordinatorTest.kt`
- Modify: `apps/android/call-log-collector/build.gradle.kts`

**Interfaces:**

- Produces `CallLogSyncFailure`, `CallLogSyncRunResult`, `runOnce`, `withSyncQuiesced`.
- Consumes typed provider, outbox, encrypted state, strict codec, current policy gate and `CapabilityPendingDispatcher`.

- [ ] **Step 1: Write failing recovery/ordering tests**

Prove this trace:

```kotlin
assertEquals(
    listOf(
        "reconcile",
        "enqueue:call:3:42",
        "cursor:3:1700:42",
        "dispatch",
    ),
    trace,
)
```

Cover every crash cut: query before enqueue, enqueue before state, state before send, send before ACK, ACK/delete. Add pending identity/capability/epoch/revision/cursor mismatch; highest pending cursor repair; outbox full; state false/throw; policy Revoking before enqueue; pairing/unsupported/transport/ACK mapping; cancellation; failed event blocking later event; quiescence sharing the run mutex.

- [ ] **Step 2: Run focused test and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :call-log-collector:testDebugUnitTest \
  --tests 'com.openandroidintelligence.calls.CallLogAutoSyncCoordinatorTest'
```

Expected: compilation fails because the call coordinator/result and dispatcher integration do not exist.

- [ ] **Step 3: Add the complete failure/result contract**

```kotlin
enum class CallLogSyncFailure {
    SOURCE_DISABLED,
    PERMISSION_REQUIRED,
    PLATFORM_UNSUPPORTED,
    CAPTURE_FAILURE,
    INVALID_PROVIDER_ROW,
    OUTBOX_FULL,
    OUTBOX_CORRUPTED,
    SYNC_STATE_CORRUPTED,
    CURSOR_NOT_DURABLE,
    POLICY_REVOKED,
    PAIRING_UNAVAILABLE,
    UNSUPPORTED_CAPABILITY,
    TRANSPORT_FAILURE,
    ACK_REJECTED,
    CANCELLED,
}
```

Result contains captured/enqueued/acknowledged/retained/failure only and redacts identities.

Map provider failures exhaustively: Disabled→SOURCE_DISABLED, PermissionRequired→PERMISSION_REQUIRED, PlatformUnsupported→PLATFORM_UNSUPPORTED, InvalidRow→INVALID_PROVIDER_ROW, QueryFailed→CAPTURE_FAILURE, PolicyStale→POLICY_REVOKED. Map generic dispatcher failures one-for-one to the corresponding Call Log failure, with generic OUTBOX_FAILURE becoming OUTBOX_CORRUPTED.

Use this constructor:

```kotlin
class CallLogAutoSyncCoordinator(
    private val provider: CallsCapabilityProvider,
    private val outbox: CapabilityOutbox,
    private val stateStore: CallLogSyncStateStore,
    private val codec: CallLogEventCodec,
    private val dispatcher: CapabilityPendingDispatcher,
    private val currentPolicyGate: CapabilityEventEgressGate,
    private val auditSink: CallLogAuditSink = CallLogAuditSink {},
    private val elapsedRealtimeMs: () -> Long =
        { System.nanoTime() / 1_000_000L },
) {
    suspend fun runOnce(
        subscription: AuthorizedAutoSendSubscription,
    ): CallLogSyncRunResult
    suspend fun withSyncQuiesced(block: suspend () -> Unit)
}
```

Every terminal result emits one `CallLogAuditEvent` using the same closed result mapping and count fields. A sink failure is swallowed after the event has been reduced to allowlisted fields; it cannot alter cursor/outbox semantics or expose a caught message.

- [ ] **Step 4: Implement reconciliation before capture**

Under one Mutex: load healthy current state/policy; recover pending; require capability `calls.metadata`; strict-decode every wire; bind outer event/record/policy to decoded epoch/cursor; require current epoch/revision; durably advance to maximum pending cursor; then call provider. Any decode/identity issue returns OUTBOX_CORRUPTED, retains all entries and skips capture/dispatch.

- [ ] **Step 5: Implement enqueue-before-cursor**

```kotlin
val durable = CapabilityDurableEvent(
    eventId = event.eventId,
    capability = "calls.metadata",
    recordId = event.record.metadata.recordId,
    policyRevision = scope.policyRevision,
    eventWire = codec.encode(
        event.eventId,
        event.record,
        state.sourceEpoch,
        scope.policyRevision,
    ),
)
requireCurrentEnabledPolicy(durable)
outbox.enqueueAccepted(durable)
if (!stateStore.advance(
        state.sourceEpoch,
        event.record.metadata.toCallLogCursor(),
        scope.policyRevision,
    )) {
    blockEventAndStop(durable.eventId)
}
```

Invoke dispatcher with blocked IDs after capture halts/completes. Map CapabilityOutboxFull separately; never send a newly accepted event lacking durable cursor.

Add `implementation(project(":capability-sync-runtime"))` to `call-log-collector/build.gradle.kts`. Tests inject `CapabilityPendingDispatcher` and therefore do not need a transport or pairing fixture in this module.

- [ ] **Step 6: Add quiescence**

`withSyncQuiesced(block)` uses the exact run Mutex. It performs no cleanup itself. Revocation persists REVOKING first, then uses this gate before storage/key operations.

- [ ] **Step 7: Run coordinator regressions**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :capability-sync-runtime:testDebugUnitTest \
  :encrypted-store:testDebugUnitTest \
  :call-log-collector:testDebugUnitTest
```

Expected: all three modules pass, including every reconciliation crash cut and enqueue-before-cursor ordering assertion.

- [ ] **Step 8: Commit the durable coordinator**

```bash
cd /home/djbd/项目/open-android-intelligence
git add apps/android/call-log-collector
git diff --cached --check
git diff --cached --name-only
git commit -m "新增: 实现通话记录持久同步协调"
```

Expected: only the call coordinator, its tests and the call module dependency change are committed.

---

### Task 10: Add closed low-frequency scheduling and the protected JobService

**Files:**

- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/CallLogSyncScheduler.kt`
- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/CallLogSyncJobService.kt`
- Create: `apps/android/call-log-collector/src/test/kotlin/com/openandroidintelligence/calls/CallLogSyncSchedulerTest.kt`
- Create: `apps/android/call-log-collector/src/test/kotlin/com/openandroidintelligence/calls/CallLogSyncJobServiceTest.kt`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Modify: `apps/android/tools/test_call_log_collector_static.py`

**Interfaces:**

- Produces `CallLogJobScheduler`, `AndroidCallLogSyncScheduler`, `CallLogAutoSyncRunner`, `CallLogRuntime`, runtime registry and `CallLogSyncJobService`.
- Consumes current local authority, availability, state and coordinator runner.

- [ ] **Step 1: Write failing scheduler/runtime tests**

Assert MANUAL cancels; 15/30/60 map to 900,000/1,800,000/3,600,000 ms; JobInfo always uses `setPersisted(false)`; failure throws fixed scheduling exception; every run re-reads authority/availability/state; Disabled/Revoking/corrupt/exhausted/manual/permission/stale states skip runner; local auto-send ignores `agentMayRequest`; missing runner requests retry; retained/failure requests retry; CancellationException propagates; job execution finishes once and stop suppresses late finish.

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :call-log-collector:testDebugUnitTest \
  --tests 'com.openandroidintelligence.calls.CallLogSyncSchedulerTest' \
  --tests 'com.openandroidintelligence.calls.CallLogSyncJobServiceTest'
```

Expected: compilation fails because the call scheduler, runtime registry and JobService do not exist.

- [ ] **Step 3: Implement the closed scheduler**

```kotlin
interface CallLogJobScheduler {
    fun schedule(interval: CallLogSyncInterval)
    fun cancel()
}

class AndroidCallLogSyncScheduler private constructor(
    private val jobs: AndroidCallLogJobScheduler,
) : CallLogJobScheduler {
    companion object { const val JOB_ID = 0x43414C }
}
```

Only registered non-null interval values reach `JobInfo.Builder(...).setPeriodic(periodMs).setPersisted(false)`.

- [ ] **Step 4: Implement deny-first runtime/service**

Use these service-facing types:

```kotlin
fun interface CallLogAutoSyncRunner {
    suspend fun runOnce(
        subscription: AuthorizedAutoSendSubscription,
    ): CallLogSyncRunResult
}

data class CallLogScheduledRunResult(
    val ran: Boolean,
    val retryPending: Boolean,
)

class CallLogRuntime(
    private val settings: PersistentCallLogSettingsAuthority,
    private val availability: CallLogAvailabilitySource,
    private val state: CallLogSyncStateStore,
    private val runner: CallLogAutoSyncRunner?,
    private val localAuthorizer: LocalCallLogAutoSendAuthorizer =
        LocalCallLogAutoSendAuthorizer(),
) {
    suspend fun runScheduled(): CallLogScheduledRunResult
    fun retryAfterStop(): Boolean
    companion object { fun denyFirst(): CallLogRuntime }
}

fun interface CallLogRuntimeFactory {
    fun create(context: Context): CallLogRuntime
}

object CallLogRuntimeFactoryRegistry {
    fun install(factory: CallLogRuntimeFactory)
    fun create(context: Context): CallLogRuntime
    fun reset()
}
```

`runScheduled()` reads authority, availability and state every invocation, mints a local subscription with exact policy/authorization revisions, then calls the runner. `retryAfterStop()` re-reads the same sources and returns true only for a healthy Enabled, non-MANUAL, locally auto-enabled policy with READY availability and matching state. `denyFirst()` and the registry default have Disabled state and no runner.

The JobService follows the SMS lifecycle with its own scope, returns START_NOT_STICKY, cancels on stop, never deletes outbox, and returns `CallLogRuntimeFactoryRegistry.create(applicationContext).retryAfterStop()` from `onStopJob` instead of consulting a cached token.

- [ ] **Step 5: Protect service and extend static checks**

```xml
<service
    android:name="com.openandroidintelligence.calls.CallLogSyncJobService"
    android:exported="false"
    android:permission="android.permission.BIND_JOB_SERVICE" />
```

Assert no RECEIVE_BOOT_COMPLETED, receiver or `setPersisted(true)`.

- [ ] **Step 6: Run scheduler and static regressions**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain :call-log-collector:testDebugUnitTest
cd /home/djbd/项目/open-android-intelligence
python3 -m unittest apps/android/tools/test_call_log_collector_static.py
```

Expected: scheduler/runtime/JobService tests pass and static checks confirm the closed, non-persisted service boundary.

- [ ] **Step 7: Commit low-frequency scheduling**

```bash
cd /home/djbd/项目/open-android-intelligence
git add \
  apps/android/call-log-collector/src \
  apps/android/app/src/main/AndroidManifest.xml \
  apps/android/tools/test_call_log_collector_static.py
git diff --cached --check
git diff --cached --name-only
git commit -m "新增: 添加通话记录低频调度"
```

Expected: only the scheduler/service implementation, protected manifest entry and related tests are committed.

---

### Task 11: Implement crash-recoverable revocation and cryptographic erasure

**Files:**

- Create: `apps/android/call-log-collector/src/main/kotlin/com/openandroidintelligence/calls/CallLogRevocationCoordinator.kt`
- Create: `apps/android/call-log-collector/src/test/kotlin/com/openandroidintelligence/calls/CallLogRevocationCoordinatorTest.kt`

**Interfaces:**

- Produces `CallLogRevocationEffects`, `LocalCallLogSettingsController.applyPolicy`, `disable`, `resumePendingRevocation`, stable unavailable result.
- Consumes settings authority, sync state and App-supplied quiesce/storage/key/runtime effects.

- [ ] **Step 1: Write failing transition/crash-cut tests**

Record:

```text
persist:REVOKING
quiesce
cancel-schedule
erase-outbox-ciphertext
delete-outbox-key
rotate-state-key-and-reset
install-runtime-and-schedule-or-disable
persist:FINAL
```

Inject failure after every effect, reconstruct and assert Revoking persists, grant is null, egress denied and resume uses the same target epoch. Test disabled/replacement targets, first enable epoch 1 with every crash cut from initial Revoking persistence through final Enabled commit, byte-identical no-op, every policy field change rotating epoch, revision monotonicity, MAX epoch exhaustion/no wrap, clear failure before key deletion, later failure after key deletion remaining recoverable, Enabled committed only after rebuild, Disabled installing no runtime and stable redacted audit result.

- [ ] **Step 2: Run focused test and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :call-log-collector:testDebugUnitTest \
  --tests 'com.openandroidintelligence.calls.CallLogRevocationCoordinatorTest'
```

Expected: compilation fails because the revocation effects, controller and failure type do not exist.

- [ ] **Step 3: Define narrow effects**

```kotlin
interface CallLogRevocationEffects {
    suspend fun withSyncQuiesced(block: suspend () -> Unit)
    fun cancelSchedule()
    suspend fun eraseOutboxCiphertext()
    fun deleteOutboxKey()
    fun resetSyncStateWithRotatedKey(target: CallLogSyncState)
    fun installRuntimeAndSchedule(targetPolicy: CallLogLocalPolicy?)
}
```

`eraseOutboxCiphertext` invalidates the old in-memory outbox/runtime reference and clears the underlying encrypted persistence without constructing or decrypting the old store. `resetSyncStateWithRotatedKey` is the Task 5 idempotent destructive replacement and likewise does not require old state to decrypt. No File, Context, keystore implementation, endpoint or transport enters the call module through this interface.

- [ ] **Step 4: Implement deterministic replacement**

Expose only this local mutation API:

```kotlin
data class CallLogPolicyDraft(
    val historyPolicy: CallHistoryPolicy,
    val directions: Set<CallDirection>,
    val counterpartyAccess: CallCounterpartyAccess,
    val syncInterval: CallLogSyncInterval,
    val onDemandEnabled: Boolean,
    val autoSendEnabled: Boolean,
    val agentMayRequest: Boolean,
)

class LocalCallLogSettingsController internal constructor(
    private val authority: PersistentCallLogSettingsAuthority,
    private val revocation: CallLogRevocationCoordinator,
) {
    suspend fun applyPolicy(draft: CallLogPolicyDraft): CallLogSettingsSnapshot
    suspend fun disable(): CallLogSettingsSnapshot
    suspend fun resumePendingRevocation(): CallLogSettingsSnapshot
}

class CallLogSourceUnavailable private constructor(
    val code: String,
) : IllegalStateException(code) {
    companion object {
        fun revocationIncomplete(): CallLogSourceUnavailable =
            CallLogSourceUnavailable("CALL_LOG_REVOCATION_INCOMPLETE")
    }
}
```

`applyPolicy` copies directions and validates the full draft. The authority allocates the next policy and authorization revisions; callers cannot supply them. Fresh Disabled+uninitialized state first persists the exact Revoking target for epoch 1, then enters the same resumable cleanup path as replacement; it must never initialize encrypted state or install a runtime before that durable transition exists. A byte-identical semantic draft is a no-op and allocates no revision. Every existing Enabled change calculates `nextCallLogSourceEpoch`, persists the exact Revoking target and resumes. Disable uses targetPolicy null. Calls while already Revoking ignore caller data and resume the persisted target.

When the current epoch is `ULong.MAX_VALUE`, policy replacement enters a terminal Revoking target with epoch still MAX, targetPolicy null and `epochExhausted=true`; cleanup clears/deletes the old outbox key, rotates state to `(MAX,null,targetRevision)`, installs disabled runtime and commits Disabled+exhausted. It never enables a new policy or resets to epoch 1 in the same pairing generation.

- [ ] **Step 5: Implement exact resumable cleanup**

Inside `withSyncQuiesced`: re-read the exact Revoking target; cancel schedule; invalidate the old runtime/outbox and erase outbox ciphertext without decryption; delete the outbox key; idempotently replace sync state with a fresh key at the target epoch/null cursor/target revision without decoding old state; call `installRuntimeAndSchedule` for the target or disabled runtime; commit the final phase. This one sequence handles first enable, ordinary replacement and restart after old aliases/ciphertexts were already partially erased. Runtime installation includes scheduling the target interval and must finish before Enabled is committed. Propagate cancellation. Other failures throw only `CallLogSourceUnavailable.revocationIncomplete()` while preserving Revoking; the caught cause is not attached.

- [ ] **Step 6: Run revocation regressions**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :call-log-collector:testDebugUnitTest \
  :encrypted-store:testDebugUnitTest \
  :capability-sync-runtime:testDebugUnitTest
```

Expected: all crash-cut, first-enable, replacement, disable, key-erasure and terminal-epoch tests pass.

- [ ] **Step 7: Commit the revocation state machine**

```bash
cd /home/djbd/项目/open-android-intelligence
git add apps/android/call-log-collector/src
git diff --cached --check
git diff --cached --name-only
git commit -m "新增: 实现通话记录撤权状态机"
```

Expected: only the call revocation controller/effects and their tests are committed.

---

### Task 12: Compose Android storage/runtime and add local-only settings UI

**Files:**

- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/CallLogComposition.kt`
- Create: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/CallLogSettingsState.kt`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt`
- Create: `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/CallLogCompositionTest.kt`
- Create: `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/CallLogSettingsStateTest.kt`
- Modify: `apps/android/tools/test_call_log_collector_static.py`

**Interfaces:**

- Produces fail-closed `CallLogComposition`, read-only App snapshot/availability accessors, local mutating controller and pure `CallLogSettingsPresenter`.
- Consumes existing paired Bridge registry/transport, exact no-backup files/aliases and runtime permission.

- [ ] **Step 1: Write failing composition constant/isolation tests**

```kotlin
assertEquals("call-log-outbox-v1.aesgcm", CallLogComposition.CALL_OUTBOX_FILE)
assertEquals("open_android_intelligence_call_log_outbox_v1", CallLogComposition.CALL_OUTBOX_KEY_ALIAS)
assertEquals("call-log-sync-state-v1.aesgcm", CallLogComposition.CALL_STATE_FILE)
assertEquals("open_android_intelligence_call_log_state_v1", CallLogComposition.CALL_STATE_KEY_ALIAS)
assertNotEquals(
    CallLogComposition.CALL_OUTBOX_KEY_ALIAS,
    CallLogComposition.CALL_STATE_KEY_ALIAS,
)
assertNotEquals(
    "open_android_intelligence_sms_outbox_v1",
    CallLogComposition.CALL_OUTBOX_KEY_ALIAS,
)
```

Test corrupt settings, Enabled-phase outbox/state authentication failure, missing pairing and unsupported provider fail closed. Separately prove a pending Revoking phase can erase corrupt/undecryptable old outbox and state ciphertext, resume after either alias is already absent, and install no enabled runtime before recovery completes. Exercise a recording authenticated verifier so ACKs with the wrong event/source epoch, capability, pairing generation or connection generation retain the event; only the fully bound ACK deletes it.

- [ ] **Step 2: Write failing pure presenter tests**

First-enable view is disabled and its editable defaults are all four directions, WITHHELD, now-minus-90-days, max 500, MANUAL, on-demand true, auto false, agent request false. Test empty directions, bad epoch, max bounds, NUMBER warning, hard-restricted status, best-effort text, Revoking/exhausted disabled controls, copy-safe save payload and read-only presentation.

Use these pure UI contracts:

```kotlin
enum class CallLogHistoryStartMode { ALL_HISTORY, FROM_EPOCH }

enum class CallLogSettingsStatus {
    DISABLED,
    INITIALIZING,
    REVOKING,
    EPOCH_EXHAUSTED,
    PERMISSION_REQUIRED,
    PLATFORM_UNSUPPORTED,
    READY,
}

data class CallLogSettingsViewState(
    val enabled: Boolean,
    val directions: Set<CallDirection>,
    val counterpartyAccess: CallCounterpartyAccess,
    val historyStartMode: CallLogHistoryStartMode,
    val fromEpochMs: Long?,
    val maxRecords: Int,
    val syncInterval: CallLogSyncInterval,
    val onDemandEnabled: Boolean,
    val autoSendEnabled: Boolean,
    val agentMayRequest: Boolean,
    val status: CallLogSettingsStatus,
    val showHardRestrictedWarning: Boolean = true,
    val showBestEffortWarning: Boolean = true,
) {
    override fun toString(): String =
        "CallLogSettingsViewState(enabled=$enabled,directions=${directions.size}," +
            "counterpartyAccess=$counterpartyAccess,maxRecords=$maxRecords," +
            "syncInterval=$syncInterval,status=$status)"
}

sealed interface CallLogSettingsAction {
    data class EnableOrReplace(val draft: CallLogPolicyDraft) : CallLogSettingsAction
    data object Disable : CallLogSettingsAction
}

class CallLogSettingsPresenter(
    private val snapshotSource: () -> CallLogSettingsSnapshot?,
    private val availabilitySource: () -> CapabilityAvailability,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun state(): CallLogSettingsViewState
    fun action(state: CallLogSettingsViewState): CallLogSettingsAction
}
```

`action` validates and copies the direction set. It never calls the authority or controller.

`CallLogPolicyDraft.toString()` reports only direction count, counterparty mode, maxRecords, interval and Boolean modes; it omits history timestamps. `CallLogSettingsAction.EnableOrReplace.toString()` relies on that redacted draft.

- [ ] **Step 3: Run App tests and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :app:testDebugUnitTest \
  --tests 'com.openandroidintelligence.mobile.CallLogCompositionTest' \
  --tests 'com.openandroidintelligence.mobile.CallLogSettingsStateTest'
```

Expected: compilation fails because App composition and pure settings state do not exist.

- [ ] **Step 4: Build the exact fail-closed graph**

`CallLogComposition` creates settings persistence/authority first and inspects the phase before opening either encrypted store. For pending Revoking it keeps the registry deny-first, constructs only non-decrypting ciphertext/key erasure effects, cancels scheduling, resumes the persisted target, and then builds the normal graph. For a final healthy phase, the graph order is: lazy state key/store; reader/availability/provider; independent outbox key/store; generic dispatcher for `calls.metadata`; auto-sync coordinator; runtime registry; scheduler; concrete revocation effects/controller. Corrupt encrypted state in Enabled fails closed; Revoking recovery never needs old ciphertext to authenticate.

ACK verification delegates to the existing authenticated bound verifier with full epoch-bearing event ID. Egress re-reads Enabled phase/exact revision each invocation. Any settings/key/cipher/state construction failure installs deny-first runtime, preserves evidence files and exposes a stable unavailable state.

Composition supplies `CallLogAuditSink { event -> Log.i("OpenAndroidIntelligenceCallAudit", event.toString()) }`. Static tests permit only this allowlist event at the Log call site and assert that raw row, payload, cursor, durable event, wire, Provider exception and caught exception variables never reach `Log`.

Expose an internal `suspend fun initialize(): CallLogCompositionState`. `OpenAndroidIntelligenceApplication.onCreate` first resets the call runtime registry to deny-first, marks composition INITIALIZING, then launches initialization in an application-owned `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. A pending Revoking phase is resumed inside `initialize`; only a successful final Enabled phase installs the active runtime. JobService invocations and UI mutations before completion observe deny-first/unavailable state.

- [ ] **Step 5: Expose local-only App access**

```kotlin
fun localCallLogSettingsController(): LocalCallLogSettingsController?
fun callLogSettingsSnapshot(): CallLogSettingsSnapshot?
fun callLogAvailability(): CapabilityAvailability
```

Both nullable accessors return null during initialization or fail-closed construction; UI actions remain disabled in that state. No Agent/wire type receives the controller, scheduler or initialization scope. The scheduler is used only by concrete revocation/runtime effects. Startup completes pending Revoking before an enabled runtime install.

- [ ] **Step 6: Add the local settings screen**

Add an Open call log settings button, enabled switch, four directions, WITHHELD/NUMBER with warning, all-history/from-epoch, max records, on-demand/auto/Agent-request switches, MANUAL/15/30/60, separate permission/provider/best-effort status, request-permission, save/disable/back.

Permission action is only:

```kotlin
requestPermissions(
    arrayOf(Manifest.permission.READ_CALL_LOG),
    READ_CALL_LOG_REQUEST_CODE,
)
```

Save uses the pure presenter then dispatches `EnableOrReplace`/`Disable` to the local controller. `MainActivity` owns a `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, cancels it in `onDestroy`, and posts only the stable result/status back with `runOnUiThread`; it never blocks the UI thread on encrypted storage. No role, shell, Provider mutation or Agent mutation path exists.

- [ ] **Step 7: Extend static checks**

Assert exact files/aliases, no alias reuse, no assistant-holder dependency, permission request only in MainActivity, local controller absent from wire/capability request interfaces, and no number/time/wire logging.

- [ ] **Step 8: Run App integration and static regressions**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :call-log-collector:testDebugUnitTest \
  :app:testDebugUnitTest
cd /home/djbd/项目/open-android-intelligence
python3 -m unittest \
  apps/android/tools/test_call_log_collector_static.py \
  apps/android/tools/test_sms_collector_static.py \
  apps/android/tools/test_transport_boundary.py
```

Expected: the fail-closed composition, local-only presenter/UI and all SMS/transport boundary regressions pass.

- [ ] **Step 9: Commit App composition and local settings**

```bash
cd /home/djbd/项目/open-android-intelligence
git add apps/android/app/src apps/android/tools/test_call_log_collector_static.py
git diff --cached --check
git diff --cached --name-only
git commit -m "新增: 集成通话记录本机设置"
```

Expected: only App composition/UI, static boundary coverage and their tests are committed.

---

### Task 13: Document evidence boundaries and run the complete acceptance gate

**Files:**

- Create: `docs/mvp/call-log-readiness.md`
- Modify: `docs/mvp/capability-ports-readiness.md`
- Modify: `apps/android/README.md`
- Modify: `e2e/mvp/run-smoke.sh`
- Modify: `apps/android/tools/test_call_log_collector_static.py`

**Interfaces:**

- Produces truthful host/device readiness packet and repeatable final commands.
- Consumes all implementation/test evidence.

- [ ] **Step 1: Write failing readiness/static assertions**

Require the readiness document to state:

```text
READ_CALL_LOG is dangerous and hard-restricted
manifest declaration is not runtime grant
runtime grant is not successful Provider access
ordinary sideload negative path
controlled installer allowlist positive path
metadata only
number withheld by default
best-effort and not reboot-resilient
encrypted outbox and encrypted sync state
host/JVM evidence is not device evidence
assistant-holder does not hold call-log access
```

Assert SDK-free smoke explicitly includes the call TypeScript test.

- [ ] **Step 2: Run readiness assertion and confirm RED**

```bash
cd /home/djbd/项目/open-android-intelligence
python3 -m unittest apps/android/tools/test_call_log_collector_static.py
```

Expected: the readiness file and explicit call-contract smoke inclusion assertions fail.

- [ ] **Step 3: Write the readiness packet**

Separate delivered boundary; Kotlin/TypeScript/static evidence; ordinary sideload negative procedure; controlled allowlist positive procedure; revoke/restart/Doze/offline/ACK/equal timestamp/profile cases; unavailable evidence; prohibition on host-only positive claims.

Update capability readiness: SMS and Calls have adapters; contacts/calendar/sensors/screen/commands remain future separate slices. Update Android README with focused commands and hard-restricted caveat. Include call contract test in SDK-free smoke.

- [ ] **Step 4: Run focused Kotlin acceptance**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain \
  :capability-ports:testDebugUnitTest \
  :capability-sync-runtime:testDebugUnitTest \
  :call-log-collector:testDebugUnitTest \
  :encrypted-store:testDebugUnitTest \
  :sms-collector:testDebugUnitTest \
  :app:testDebugUnitTest
```

- [ ] **Step 5: Run schema/typecheck acceptance**

```bash
cd /home/djbd/项目/open-android-intelligence
./tools/run-node24 npx --no-install vitest --root . run \
  mvp-contract/test/call-contract.test.ts
./tools/run-node24 npx --no-install tsc --noEmit -p mvp-contract/tsconfig.json
```

- [ ] **Step 6: Run SDK-free security gates**

```bash
cd /home/djbd/项目/open-android-intelligence
python3 -m unittest \
  apps/android/tools/test_call_log_collector_static.py \
  apps/android/tools/test_sms_collector_static.py \
  apps/android/tools/test_transport_boundary.py
python3 -m unittest discover -s apps/android/tools -p 'test_*.py'
e2e/mvp/run-smoke.sh --sdk-free
e2e/mvp/run-readiness.sh --sdk-free
```

Expected: source gates pass; dependency/device status remains explicitly pending if external evidence is absent.

- [ ] **Step 7: Run full Android check when configured SDK is available**

```bash
cd /home/djbd/项目/open-android-intelligence/apps/android
./gradlew --no-daemon --console=plain check
```

If toolchain resolution is blocked, record exact command/blocker without converting it to PASS.

- [ ] **Step 8: Collect authorized device evidence only**

Ordinary side-load must remain PERMISSION_REQUIRED and attempt no fallback. Controlled installer allowlist plus runtime grant must prove four allowed directions, default withheld, NUMBER only for ALLOWED, runtime revoke, final egress fence and encrypted restart recovery.

Record only counts, stable codes, build/installer identity and permission state. Never record phone numbers, call timestamps, Provider IDs, cursors or wire bytes. If no authorized device/installer exists, mark positive device steps not run and leave release readiness incomplete.

- [ ] **Step 9: Review final diff**

```bash
cd /home/djbd/项目/open-android-intelligence
git diff --check
git status --short
git diff --name-only
rg -n -i \
  'WRITE_CALL_LOG|CALL_PHONE|ANSWER_PHONE_CALLS|READ_PHONE_STATE|READ_PRECISE_PHONE_STATE|READ_VOICEMAIL|WRITE_VOICEMAIL|RECORD_AUDIO|PROCESS_OUTGOING_CALLS|CONTENT_URI_WITH_VOICEMAIL|CONTENT_FILTER_URI|TelecomManager|TelephonyManager|ProcessBuilder|Runtime\.getRuntime' \
  apps/android/call-log-collector \
  apps/android/app/src/main/AndroidManifest.xml
```

Expected: whitespace clean; forbidden scan has no production hit; `third_party/` remains untouched.

- [ ] **Step 10: Commit readiness**

```bash
git add \
  docs/mvp/call-log-readiness.md \
  docs/mvp/capability-ports-readiness.md \
  apps/android/README.md \
  apps/android/tools/test_call_log_collector_static.py \
  e2e/mvp/run-smoke.sh
git diff --cached --check
git diff --cached --name-only
git commit -m "文档: 记录通话记录适配器证据边界"
```

## Plan Self-Review

### Spec coverage

| Approved design section | Implementing tasks |
| --- | --- |
| Scope, non-goals, vertical module boundary | Tasks 1-3, 8, 13 |
| Capability ports and exact number policy | Tasks 1, 6 |
| CallLog.Calls URI/projection/query/limit | Task 3 |
| Manifest, hard-restricted permission and availability | Tasks 2, 3, 12, 13 |
| Local settings, exact directions/history/modes | Tasks 1, 4, 12 |
| Record wire/schema/strict decoding | Task 7 |
| AES-GCM outbox/state, independent files and aliases | Tasks 5, 12 |
| Source epoch, cursor and recovery reconciliation | Tasks 5, 7, 9 |
| Enqueue-before-cursor and capacity backpressure | Tasks 5, 9 |
| Persistent REVOKING and cryptographic erasure | Tasks 4, 5, 11, 12 |
| Triple egress fence and SMS migration | Task 8 |
| Stable failures, cancellation and redacted diagnostics | Tasks 1, 3, 6-11 |
| Closed low-frequency scheduling and JobService | Task 10 |
| SDK-free, Kotlin, TypeScript and device evidence | Task 13 |

### Type consistency

- `CapabilityFilter.Calls` always carries `Set<CallDirection>` and `CallCounterpartyAccess`.
- `CallsPayload.counterpartyNumber` replaces provisional generic `content` everywhere.
- Provider event ID is `call:<sourceEpoch>:<providerId>`; metadata/wire record ID is `call:<providerId>`.
- Durable capability literal is exactly `calls.metadata`.
- Cursor field order is always `(startedAtEpochMs, providerId)`.
- `CallLogSyncStateStore` is the only mutable source-epoch/cursor seam.
- Generic dispatcher failures map exhaustively into SMS and Call Log failures.
- Revocation effects consume the already-persisted target and cannot choose a new target.

### External evidence boundary

- Kotlin, TypeScript and SDK-free evidence can complete in this workspace.
- Positive READ_CALL_LOG device readiness additionally requires an authorized device, installer-of-record allowlist and runtime grant.
- Missing device evidence never justifies shell, role, accessibility, root or alternate Provider fallbacks.
