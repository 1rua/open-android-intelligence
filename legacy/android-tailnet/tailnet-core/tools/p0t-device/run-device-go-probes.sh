#!/usr/bin/env bash
# Push and run the two device Go resolver probes (pure-Go vs cgo) to determine
# whether the AAR's Go runtime can resolve/connect to the Tailscale control
# plane on this device. Requires a connected, authorized device.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
probe_dir="${1:-/tmp}"

[[ -f "$probe_dir/goprobe-arm64" ]] && [[ -f "$probe_dir/goprobe-cgo-arm64" ]] || { echo "probes missing in $probe_dir" >&2; exit 1; }
STATE="$(adb_dev get-state 2>/dev/null || echo offline)"
echo "DEVICE_STATE=$STATE"
[[ "$STATE" == "device" ]] || { echo "PROBE_SKIPPED: no authorized device" >&2; exit 2; }

adb_dev push "$probe_dir/goprobe-arm64" /data/local/tmp/goprobe-arm64 >/dev/null
adb_dev push "$probe_dir/goprobe-cgo-arm64" /data/local/tmp/goprobe-cgo-arm64 >/dev/null
adb_dev shell "chmod 755 /data/local/tmp/goprobe-arm64 /data/local/tmp/goprobe-cgo-arm64" >/dev/null
echo "=== PURE-GO resolver probe ==="
adb_dev shell /data/local/tmp/goprobe-arm64 | tr -d '\r'
echo "=== CGO(bionic) resolver probe ==="
adb_dev shell /data/local/tmp/goprobe-cgo-arm64 | tr -d '\r'
