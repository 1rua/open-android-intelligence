# SMS Scheduling Reboot Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the SMS scheduling policy conflict by accepting best-effort scheduling with app-start self-healing, and fix misleading naming.

**Architecture:** `OpenAndroidIntelligenceApplication.onCreate()` reads persisted SMS settings after authority creation and restores the periodic job if auto-send is enabled with a non-manual interval. No new permissions or Android components. The `schedulePersistedPeriodic` interface method is renamed to `schedulePeriodic` to match its actual behavior (`.setPersisted(false)`).

**Tech Stack:** Kotlin, Android JobScheduler, JUnit 4

## Global Constraints

- No new Android permissions; `AndroidManifest.xml` remains unchanged
- No new Android components (no `BroadcastReceiver`, no `Activity`)
- `JobScheduler.setPersisted(false)` remains — jobs are never persisted across reboots
- Scheduling restoration must not block app startup; failures are silently caught
- Existing `test_sms_collector_static.py` forbidden-surface check must pass without modification
- All existing tests must continue to pass

---

### Task 1: Rename `schedulePersistedPeriodic` to `schedulePeriodic`

**Files:**
- Modify: `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsSyncScheduler.kt`
- Modify: `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSyncSchedulerTest.kt`

**Interfaces:**
- Consumes: (none — pure rename)
- Produces: `AndroidSmsJobScheduler.schedulePeriodic(jobId: Int, periodMs: Long): Boolean`

- [ ] **Step 1: Rename interface method and its call site**

In `apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsSyncScheduler.kt`, change the interface method name from `schedulePersistedPeriodic` to `schedulePeriodic` in two places:

Line 23 — interface declaration:
```kotlin
internal interface AndroidSmsJobScheduler {
    fun schedulePeriodic(jobId: Int, periodMs: Long): Boolean
    fun cancel(jobId: Int)
}
```

Line 51 — call site in `AndroidSmsSyncScheduler.schedule()`:
```kotlin
override fun schedule(interval: SmsSyncInterval) {
    val periodMs = interval.periodMs
    if (periodMs == null) {
        cancel()
    } else {
        if (!jobs.schedulePeriodic(JOB_ID, periodMs)) throw SmsJobSchedulingException()
    }
}
```

- [ ] **Step 2: Update test recording adapter**

In `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSyncSchedulerTest.kt`, line 56, rename the override:
```kotlin
private class RecordingAndroidSmsJobScheduler(
    private val scheduleSucceeds: Boolean = true,
) : AndroidSmsJobScheduler {
    val periodicJobs = mutableListOf<ScheduledPeriodicSmsJob>()
    val cancelledJobIds = mutableListOf<Int>()

    override fun schedulePeriodic(jobId: Int, periodMs: Long): Boolean {
        periodicJobs += ScheduledPeriodicSmsJob(jobId, periodMs, persisted = false)
        return scheduleSucceeds
    }

    override fun cancel(jobId: Int) {
        cancelledJobIds += jobId
    }
}
```

- [ ] **Step 3: Run scheduler tests to verify rename is clean**

```sh
cd apps/android
./gradlew --no-daemon :sms-collector:test --tests 'com.openandroidintelligence.sms.SmsSyncSchedulerTest'
```

Expected: all 3 tests PASS.

- [ ] **Step 4: Commit**

```sh
git add apps/android/sms-collector/src/main/kotlin/com/openandroidintelligence/sms/SmsSyncScheduler.kt apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsSyncSchedulerTest.kt
git commit -m "refactor(sms): rename schedulePersistedPeriodic to schedulePeriodic"
```

---

### Task 2: Add startup scheduling restoration

**Files:**
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt`

**Interfaces:**
- Consumes: `smsAuthority: PersistentSmsSettingsAuthority` (already created), `smsScheduler: SmsJobScheduler` (already created), `SmsSyncInterval.periodMs: Long?`, `SmsSettingsSnapshot.corrupted, .granted, .autoSendEnabled, .syncInterval`
- Produces: (none — internal side effect)

- [ ] **Step 1: Add restoration method**

In `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt`, after the `smsScheduler = createSmsSchedulerFailClosed()` line (line 95), add:

```kotlin
        restoreSmsScheduling()
```

Then add the private method anywhere in the `OpenAndroidIntelligenceApplication` class body (e.g., before `createSmsAuthorityFailClosed`):

```kotlin
    private fun restoreSmsScheduling() {
        try {
            val snapshot = smsAuthority.snapshot()
            if (snapshot.corrupted || !snapshot.granted || !snapshot.autoSendEnabled) return
            val interval = snapshot.syncInterval
            if (interval.periodMs == null) return
            smsScheduler.schedule(interval)
        } catch (_: Throwable) {
            // Scheduling restoration is best-effort; failure must not block app start.
        }
    }
```

The full `onCreate()` block (lines 86-113) with the addition:
```kotlin
    override fun onCreate() {
        super.onCreate()
        notificationAuthority = PersistentNotificationPolicyAuthority(
            FileNotificationPolicyPersistence(
                File(noBackupFilesDir, "notification-authority-v1.bin"),
            ),
        )
        notificationOutbox = createOutboxFailClosed()

        smsAuthority = createSmsAuthorityFailClosed()
        smsScheduler = createSmsSchedulerFailClosed()
        restoreSmsScheduling()
        val smsRuntime = createSmsRuntimeFailClosed()
        SmsRuntimeFactoryRegistry.install(SmsRuntimeFactory { smsRuntime })

        NotificationRuntimeFactoryRegistry.install(NotificationRuntimeFactory { scope ->
            // ... existing notification setup unchanged ...
        })
    }
```

- [ ] **Step 2: Run full SMS test suite to verify no regressions**

```sh
cd apps/android
./gradlew --no-daemon :sms-collector:test :app:test
```

Expected: all tests PASS.

- [ ] **Step 3: Run static surface check to verify no forbidden permissions**

```sh
python3 apps/android/tools/test_sms_collector_static.py
```

Expected: all tests PASS (no `RECEIVE_BOOT_COMPLETED` in manifest).

- [ ] **Step 4: Commit**

```sh
git add apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt
git commit -m "feat(sms): restore periodic scheduling on app start"
```

---

### Task 3: Update documentation

**Files:**
- Modify: `docs/mvp/sms-read-readiness.md`

**Interfaces:**
- Consumes: (none)
- Produces: (none)

- [ ] **Step 1: Replace the "unresolved policy conflict" paragraph**

In `docs/mvp/sms-read-readiness.md`, replace lines 68-75:

**Old:**
```markdown
An unresolved policy conflict remains: the source configures persisted periodic
JobScheduler work while the permission allowlist forbids
`RECEIVE_BOOT_COMPLETED`. Android ordinarily needs that permission to restore
scheduled work after reboot. Until a reviewed policy/design resolves this
persisted JobScheduler versus `RECEIVE_BOOT_COMPLETED` conflict, scheduling is
only a best-effort in-process/installed-app configuration and must not be
called reboot-resilient.
```

**New:**
```markdown
Periodic work is best-effort `JobScheduler` scheduling (`.setPersisted(false)`).
Jobs are not persisted across reboots. When the app starts, `OpenAndroidIntelligenceApplication`
reads the persisted SMS settings and restores the periodic job if auto-send is
enabled with a non-manual interval. Reboot without a subsequent app launch
results in no scheduled jobs. The app does not request `RECEIVE_BOOT_COMPLETED`
and registers no `BroadcastReceiver`.
```

- [ ] **Step 2: Commit**

```sh
git add docs/mvp/sms-read-readiness.md
git commit -m "docs(sms): document best-effort scheduling with startup self-healing"
```