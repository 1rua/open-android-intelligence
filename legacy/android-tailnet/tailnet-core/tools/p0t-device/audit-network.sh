#!/usr/bin/env bash
# Capture a sanitized network/VPN/route/DNS snapshot from the connected device.
#
# The exact OS surfaces vary by OEM/build: `dumpsys vpn` is not exposed on some
# Android 16 builds (prior run: "Can't find service: vpn"); this collector
# therefore reads connectivity for VPN network agents, and reports raw dumpsys
# responses with an explicit availability marker so a missing service is never
# mistaken for "no VPN".
#
# Usage: audit-network.sh <label> <dest-dir>
# Writes: <dest-dir>/<label>.sanitized.json
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

label="${1:?label required}"
dest_dir="${2:?dest dir required}"
mkdir -p "$dest_dir"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

capture() {
  local name="$1"; shift
  set +e
  adb_dev shell "$@" >"$tmpdir/$name.raw" 2>&1
  set -e
}

capture connectivity dumpsys connectivity
capture vpn dumpsys vpn
capture route cat /proc/net/route
capture route6 cat /proc/net/ipv6_route
capture props getprop
capture package dumpsys package com.openandroidintelligence.mobile

# Summaries derived from the raw connectivity dump.
python3 - "$tmpdir" "$dest_dir" "$label" <<'PY'
import json, pathlib, re, sys
tmp = pathlib.Path(sys.argv[1]); dest = pathlib.Path(sys.argv[2]); label = sys.argv[3]
conn_raw = (tmp / 'connectivity.raw').read_text(errors='replace')
vpn_raw = (tmp / 'vpn.raw').read_text(errors='replace')
route_raw = (tmp / 'route.raw').read_text(errors='replace')

vpn_agents = len(re.findall(r'TRANSPORT_VPN', conn_raw))
wifi_lines = [l for l in conn_raw.splitlines() if 'WIFI' in l or 'CELLULAR' in l]
default_agent = next((l for l in conn_raw.splitlines() if 'DEFAULT' in l or 'FALLBACK' in l), None)

dns = re.findall(r'Domain:' if False else r'(?:dns|DNS)[^\n]*', conn_raw)
dns_servers = [d for d in dns if '=' in d or re.search(r'\d+\.\d+\.\d+\.\d+', d)]

summary = {
    'label': label,
    'vpn_agents_matching_transport_vpn_regexp': vpn_agents,
    'dumpsys_vpn_service_available': 'Can\'t find service' not in vpn_raw,
    'dumpsys_vpn_raw_trimmed': vpn_raw.strip()[:400],
    'default_or_fallback_network_line': default_agent,
    'route_table_entries_ipv4': len([l for l in route_raw.splitlines()[1:] if l.strip()]),
    'dns_lines': dns_servers[:20],
}
(dest / f'{label}.summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False) + '\n')
PY

# Sanitized raw captures (redact MACs/IPs/serials/hex digests).
for f in "$tmpdir"/*.raw; do
  base="$(basename "$f" .raw)"
  p0t_sanitize_file "$f" >"$dest_dir/$label.$base.sanitized.txt"
done

echo "AUDIT_CAPTURED label=$label dir=$dest_dir"
