# Android control ports (source-only contract)

This source-only module defines the high-risk command Interface without implementing an
Android provider or executor. Typed writes, semantic screen actions and a
small set of reviewed restricted templates all require a local grant, exact
revision/generation fencing, an unexpired parameter-bound confirmation and a
server-issued operation identity. The ports fail closed; they do not run a
process, accept a command string or expose a generic automation surface.

The source is intentionally independent of platform services. `screen-control`
now implements the native screen backend and `device-primitives` connects it
to the existing execution ledger. `ScreenLocalAuthorization` retains only
explicit, expiring in-process decisions; it does not grant Android permissions.
The host integration lives behind `ScreenHostRegistry.current`. Missing local
UI authorization entry points keep remote access unavailable.

```sh
python3 -m unittest apps/android/tools/test_control_ports_static.py
```

Passing this check proves only the closed source boundary, not a working
screen controller or background command executor.

See the [screen Interface](../../../docs/guides/android-screen-interfaces.zh-CN.md)
and [frontend handoff](../../../docs/guides/android-screen-frontend-handoff.zh-CN.md)
for the implementation, authorization order and remaining device validation.
