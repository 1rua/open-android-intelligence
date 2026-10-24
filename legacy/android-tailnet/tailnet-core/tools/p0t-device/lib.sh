#!/usr/bin/env bash
# Shared environment for the P0t on-device tooling (offline, no mutations to
# the pinned source/toolchain). Suppliers: adb serial, project paths and a
# deterministic sanitizer used by every captured audit.
set -euo pipefail

P0T_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../.." && pwd)"
P0T_ANDROID_ROOT="$P0T_REPO_ROOT/apps/android"
P0T_MODULE_ROOT="$P0T_ANDROID_ROOT/tailnet-core"
P0T_WRAPPER="$P0T_MODULE_ROOT/native/tsnetbridge"
P0T_TOOLCHAINS="$P0T_REPO_ROOT/.toolchains"
P0T_TMP="$P0T_TOOLCHAINS/tsnet-p0t"
P0T_EVIDENCE_ROOT="$P0T_REPO_ROOT/docs/mvp/evidence/p0t"
P0T_DEVICE_DIR="/data/local/tmp/openandroidintelligence-p0t"
P0T_BUNDLE_REMOTE="$P0T_DEVICE_DIR/failclosed.bundle"

P0T_SERIAL="${ANDROID_SERIAL:-${P0T_SERIAL:-}}"
adb_dev() {
  if [[ -n "$P0T_SERIAL" ]]; then
    adb -s "$P0T_SERIAL" "$@"
  else
    adb "$@"
  fi
}

# Sanitize a captured stream: redact MACs, IPv4/IPv6 literals e.g. wifi SSID
# and serials/IMEI-like tokens, plus likely host names, while keeping the
# structure machine-readable.
p0t_sanitize_file() {
  python3 - "$1" <<'PY'
import re, sys
path = sys.argv[1]
data = open(path, encoding='utf-8', errors='replace').read()
data = re.sub(r'([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}', '<redacted-mac>', data)
data = re.sub(r'\b(?:(?:\d{1,3}\.){3}\d{1,3})\b', '<redacted-ipv4>', data)
data = re.sub(r'\b[0-9a-fA-F]{1,4}:(?:[0-9a-fA-F]{0,4}:){2,}[0-9a-fA-F]{0,4}\b', '<redacted-ipv6>', data)
data = re.sub(r'\b(SSID|ssid)[":= ]+[A-Za-z0-9._-]+', r'\1=<redacted-ssid>', data)
data = re.sub(r'\"[^\"]*[\u4e00-\u9fff][^\"]*\"', '\"<redacted-utf8>\"', data)
data = re.sub(r'\b(serial|Serial)[^\n]{0,40}', r'\1=<redacted>', data)
data = re.sub(r'\b[0-9a-f]{8,64}\b', '<redacted-hex>', data)
sys.stdout.write(data)
PY
}

mkdir -p "$P0T_TMP" "$P0T_EVIDENCE_ROOT"
