# Android capability ports (source-only contract)

`CapabilityPorts.kt` is an Android-side port and policy model, not a system
data collector. It deliberately contains no `ContentResolver`, notification
listener, SMS/Call Log, Contacts, Health Connect, sensor, calendar, alarm,
Accessibility or MediaProjection adapter. Those adapters remain separate
readiness items and must pass Android permission/device tests before they are
advertised as available.

The boundary is closed:

- `MobileDataCapability` names the supported data-source families. There is no
  arbitrary scope string or wildcard capability.
- `CapabilityFilter` is a sealed union. Notifications use the existing
  Unicode-code-point-sorted package allowlist and `metadata|content` field
  policy; the other future sources currently use their typed no-extra-filter
  selection. A future filter must be added as a reviewed sealed type.
- `CapabilityGrant` is local user state. An Agent request never creates or
  expands a grant. `DefaultAgentRequestAuthorizer` fails closed for a missing
  grant, disabled Agent requests, a stale policy revision, a mismatched filter,
  an ungranted mode, or an unavailable source.
- `ON_DEMAND` and `AUTO_SEND` mint different internal access objects. Ports
  expose separate `read` and `observeAutoSend` methods, so an auto-send stream
  cannot be mistaken for a one-shot read.
- `CapabilityProviderContracts.kt` gives each future source a typed provider
  interface and a narrow authorized scope. Raw records are internal and must be
  normalized to `Withheld`/`Released` content. With no reviewed non-notification
  content filter, SMS, calls, contacts, clipboard, location, health, sensors,
  calendar, alarms, current-window and screen-content normalizers all withhold
  raw content by default.
- Screen content uses an owned-copy snapshot rather than exposing a mutable
  `ByteArray` reference across the boundary.
- This module has no command execution, UI-control, or privileged backend port.

The module is wired as a source-only library so the boundary can be compiled
with the rest of the scaffold; it does not add a provider, permission or
network dependency. The dependency row still must be locked before a release.
Host verification is available without an Android SDK:

```sh
python3 -m unittest apps/android/tools/test_capability_ports_static.py
```

Passing this test proves only source-boundary invariants; it does not prove any
platform provider permission, data read, or device behavior.

## Screen capture implementation

The source-only description above applies to the typed `CapabilityPorts.kt`
policy contract. `ScreenCaptureSource` and `MediaProjectionScreenCaptureSource`
provide the real, separately tested Android capture implementation. Remote
requests use `RemoteScreenCaptureRegistry.source`, never the conversation
picker's consent result. The existing projection foreground service tracks
local and remote owners independently. System consent, an active foreground
service and a current local screen grant are all required.

No remote consent UI is wired in this change. The exact future frontend call
order and cleanup rules are in the [screen frontend handoff](../../../docs/guides/android-screen-frontend-handoff.zh-CN.md).
