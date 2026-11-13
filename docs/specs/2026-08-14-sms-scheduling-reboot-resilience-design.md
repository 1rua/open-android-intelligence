# SMS Scheduling Reboot Resilience Design

Status: **accepted**

## Problem

The `sms-read-readiness.md` document recorded an unresolved policy conflict: it
claimed the source configures persisted periodic JobScheduler work while the
permission allowlist forbids `RECEIVE_BOOT_COMPLETED`. Code inspection revealed
that the document was incorrect — `AndroidSmsJobSchedulerAdapter` already uses
`.setPersisted(false)`, so jobs are never persisted across reboots. The real
gap is that no component re-schedules jobs after the app process restarts.

## Decision

**Accept best-effort scheduling with app-start self-healing.** No new
permissions or Android components. When the app starts (user launch or system
trigger), `OpenAndroidIntelligenceApplication` reads the persisted SMS settings and restores
the periodic job if auto-send is enabled with a non-manual interval.

The user's product requirement is: "after reboot, if the app starts, sync
should resume." This design satisfies that requirement without introducing
`RECEIVE_BOOT_COMPLETED` or a `BroadcastReceiver`.

## Design

### Data flow

```
reboot → app starts → OpenAndroidIntelligenceApplication.onCreate()
  → read sms-settings-v1.bin
  → if autoSendEnabled && interval ∈ {15, 30, 60}min
    → AndroidSmsSyncScheduler.schedule(interval)
      → JobScheduler: periodic, not persisted
```

### App-start restoration

In `OpenAndroidIntelligenceApplication.onCreate()`, after `smsAuthority` is created, add a
restoration step:

1. Read snapshot from `smsAuthority`.
2. If `snapshot.corrupted`, skip — deny-first posture.
3. If `snapshot.granted && snapshot.autoSendEnabled && snapshot.syncInterval.periodMs != null`, call `smsJobScheduler.schedule(snapshot.syncInterval)`.
4. Catch any exception silently; scheduling failure must not block app start.

### Interface rename

`AndroidSmsJobScheduler.schedulePersistedPeriodic` is misleading — the
implementation has never persisted. Rename to `schedulePeriodic`. Update all
call sites and the test recording adapter.

### Files changed

| File | Change |
|------|--------|
| `OpenAndroidIntelligenceApplication.kt` | Add startup scheduling restoration |
| `SmsSyncScheduler.kt` | Rename `schedulePersistedPeriodic` → `schedulePeriodic` |
| `SmsSyncSchedulerTest.kt` | Update test adapter to match new method name |
| `docs/mvp/sms-read-readiness.md` | Remove the "unresolved policy conflict" paragraph; document best-effort + startup self-healing |

### Files unchanged

| File | Why |
|------|-----|
| `AndroidManifest.xml` | No new permission needed |
| `SmsSyncJobService.kt` | Job execution logic unchanged |
| `SmsSyncJobServiceTest.kt` | No behavior change in the service |

### Error handling

| Scenario | Behavior |
|----------|----------|
| Settings file corrupted / read fails | Skip restoration, no exception |
| `autoSendEnabled=false` or `MANUAL` interval | Don't schedule |
| `schedule()` throws | Catch silently, don't block app start |
| `JobScheduler` unavailable | `createSmsSchedulerFailClosed()` already returns no-op |

### Security

No new permissions. No new Android components. Attack surface unchanged. The
existing `test_sms_collector_static.py` forbidden-surface check passes without
modification.

## Rejected alternatives

### `setPersisted(true)` + `RECEIVE_BOOT_COMPLETED`

Adds a permission and relies on system-level job persistence. Rejected because
the user's requirement ("app starts → sync resumes") does not need
boot-without-app-launch resilience, and adding a permission increases audit
burden.

### `BootReceiver` + `RECEIVE_BOOT_COMPLETED` (without `setPersisted(true)`)

Introduces a `BroadcastReceiver` and a permission. Rejected because it adds
more components than the chosen approach for no additional benefit — the
`BootReceiver` would do the same restoration logic that `OpenAndroidIntelligenceApplication`
can do on start.