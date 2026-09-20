# Android MVP skeleton

This tree is deliberately production-shaped but does not include a vendored
Android SDK or Tailscale AAR. The only network seam is
`PairedBridgeTransport.open(VerifiedPairingTransportBinding)`: callers cannot
provide an endpoint or obtain a generic socket operation.

The `tailnet-core` module is an integration spike for the confirmed project-built
minimal `tsnet-android` gomobile AAR. `TsnetLibTailscaleCore` accepts an injected
binding with `startNode`/`openPairedBridge`/`stopNode` methods and keeps node
state behind `NoBackupTailnetStateStore`; replacing that adapter must preserve
the same narrow interface.

`artifact-ports`, `capability-ports` and `control-ports` are source-only closed
contracts for selected attachments, per-capability reads, typed writes,
semantic screen actions and reviewed restricted templates. They are registered
Gradle modules and included in the root no-VPN scan even though they contain no
Android provider, screen service, encryption implementation or command
executor. The assistant-holder and main APK share a bounded text/opaque-grant
handoff contract whose default gate denies until a local user setting enables
it; no implicit IPC is implemented in this source-only slice.

## Notification vertical slice

Fresh install is deny-first: the persisted notification policy starts with an
empty package allowlist, `METADATA` field access, `ON_DEMAND` delivery, and no
local grant. The local settings page can configure package IDs, metadata versus
content access, and `ON_DEMAND` versus `AUTO_SEND`. It also opens Android's
notification-listener settings, where the user completes the system listener
authorization. The Agent has a read-only query path; it cannot change the
local policy or system authorization.

Agent reads use the typed `NotificationAgentQueryGateway`. Each request carries
an operation ID and policy revision: identical operation retries are
idempotent, while reuse with different request data is rejected. The gateway
enforces the current local grant and revision, package filtering, content
eligibility, and metadata redaction before returning records.

`AUTO_SEND` records are written to the encrypted local outbox only when local
mode and the egress policy gate both allow them. `ON_DEMAND` reads do not create
new automatic events. Dispatcher recovery and authenticated ACK handling remain
in place for pending outbox events. The local notification authority persists
as format V2; malformed, truncated, unknown, or otherwise corrupt state fails
closed to deny-first behavior.

Focused verification for this slice:

```sh
cd apps/android
./gradlew --no-daemon --console=plain \
  :notification-collector:testDebugUnitTest \
  :policy-engine:testDebugUnitTest \
  :core-model:testDebugUnitTest \
  :encrypted-store:testDebugUnitTest \
  :app:testDebugUnitTest
```

The focused tests cover the notification gateway/runtime, policy authority and
evaluator, core contracts, encrypted outbox/dispatcher behavior, and local app
settings. The SDK-free boundary check is:

```sh
python3 apps/android/tools/test_notification_runtime_static.py
python3 apps/android/tools/test_transport_boundary.py
```

These commands are scoped verification; they do not by themselves claim that
the full Android build is green.

The `sms-collector` module is a separate inbox-only read boundary. It has no
SMS-send, MMS, default-SMS, broadcast-receiver/platform-listener, VPN, generic
socket, URL, or process-execution surface. A local user controls `READ_SMS`, history start,
maximum records, on-demand/auto-send permission, Agent-request permission,
and the closed manual/15/30/60-minute interval choices. Periodic work is
best-effort JobScheduler work, with accepted event wire retained in an
encrypted outbox until a verified Bridge acknowledgement. See
[`docs/mvp/sms-read-readiness.md`](../../docs/mvp/sms-read-readiness.md) for
the evidence boundary and unresolved reboot-scheduling conflict.

Run the SDK-free source gate from the repository root:

```sh
python3 apps/android/tools/test_transport_boundary.py
python3 -m unittest discover -s apps/android/tools -p 'test_*.py'
```

With the pinned Android toolchain installed, run:

```sh
cd apps/android
./gradlew --no-daemon check
```

The source scaffold currently declares provisional Gradle 8.12, AGP 8.9.2,
Kotlin 2.1.20, compile/target SDK 35 and min SDK 34 values; these are not a
controller-approved dependency lock. This checkout does not contain the
wrapper JAR, SDK, or native AAR, so a toolchain-enabled environment must supply
them according to `MVP-DEP-ANDROID` and `MVP-DEP-TSNET` before claiming a
build or P0t device result.

The SMS slice therefore has no Android SDK/device or native AAR validation in
this checkout. Its persisted JobScheduler configuration also conflicts with
the deliberate absence of `RECEIVE_BOOT_COMPLETED`; do not represent periodic
work as reboot-resilient until that reviewed policy decision is made.

## Debug signing

Debug variants are signed with the checked-in key `app/keystore/debug.keystore`
(alias `androiddebugkey`, password `android`) instead of the per-machine
`~/.android/debug.keystore` that AGP would create on demand. A machine-local key
means every workstation — and every fresh CI runner — signs with a different
identity, so installing a new build over an existing one fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` until the app is uninstalled. With the pinned
key, local, CI and released debug APKs share one signing identity and can be
installed over each other. The configuration lives in the root `build.gradle.kts`
and applies to every `com.android.application` module (`app`, `assistant-holder`),
so signature-protected IPC between the holder and the host app cannot silently
break on a machine change.

Verify any debug APK against the pinned certificate:

```sh
apps/android/tools/verify-debug-signing.sh [path/to/app-full-debug.apk]
```

CI (`android-apk.yml`, `nightly-release.yml`) runs the same script before uploading
or publishing an APK, so a regression fails the workflow instead of reaching a
device. Key parameters and the regeneration procedure: `app/keystore/README.md`.
This key is for debugging only and must never sign a release build.
