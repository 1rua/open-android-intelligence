#!/usr/bin/env bash
# Orchestrate a small-step P0t connected run with per-case sanitized audits.
#
# Usage:
#   run-p0t-smallstep.sh --run-id <id> [--module-class <FQCN>]...
#
# Steps: gate device -> provision fail-closed bundle -> for each requested
# case: BEFORE audit -> am instrument -> AFTER audit -> emit per-case records
# and an aggregate summary. Nothing is reported as PASS unless `am instrument`
# exits 0 with a matching "OK (N tests)" line and no FAILURES.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

RUN_ID=""
CASES=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-id) RUN_ID="$2"; shift 2;;
    --module-class) CASES+=("$2"); shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done
[[ -n "$RUN_ID" ]] || { echo "--run-id required" >&2; exit 2; }

EVIDENCE_DIR="$P0T_EVIDENCE_ROOT/$RUN_ID"
AUDIT_DIR="$EVIDENCE_DIR/audits"
mkdir -p "$AUDIT_DIR"
SUMMARY="$EVIDENCE_DIR/run-summary.json"
RESULTS="$EVIDENCE_DIR/am-instrument"

STATE="$(adb_dev get-state 2>/dev/null || echo offline)"
API="$(adb_dev shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
MODEL="$(adb_dev shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
ABI="$(adb_dev shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true)"
RELEASE="$(adb_dev shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || true)"
PAGE="$(adb_dev shell "getconf PAGE_SIZE" 2>/dev/null | tr -d '\r' || true)"
echo "DEVICE_STATE=$STATE model=$MODEL api=$API abi=$ABI release=$RELEASE page=$PAGE serial=<redacted>"
[[ "$STATE" == "device" ]] || { echo "P0T_BLOCKED: device not authorized (state=$STATE)" >&2; exit 2; }
[[ "$API" =~ ^(3[4-9]|4[0-9])$ ]] || { echo "P0T_BLOCKED: API 34+ required (got '$API')" >&2; exit 2; }

echo '{"schema_version": "p0t-gate-smallstep/v1"}' >"$SUMMARY"
python3 - "$SUMMARY" "$STATE" "$MODEL" "$API" "$ABI" "$RELEASE" "$PAGE" <<'PY'
import json, sys
path, state, model, api, abi, release, page = sys.argv[1:]
d = json.load(open(path))
d["device"] = {
  "state": state, "model": model, "api_level": api, "release": release,
  "abi": abi, "page_size": page, "serial": "<redacted-device-serial>",
  "api_34_plus": api.isdigit() and int(api) >= 34,
}
json.dump(d, open(path, "w"), indent=2)
PY

bash "$(dirname "${BASH_SOURCE[0]}")/provision-failclosed-bundle.sh"

instrument() {
  local pkg="$1" arg="$2" out="$3"
  adb_dev shell am instrument -w -r \
    -e p0tFailClosedBundle /data/local/tmp/openandroidintelligence-p0t/failclosed.bundle \
    $arg "$pkg/androidx.test.runner.AndroidJUnitRunner" >"$out" 2>&1 || true
}

score_case() {
  local label="$1" out="$2"
  python3 - "$SUMMARY" "$label" "$out" <<'PY'
import json, re, sys
path, label, out = sys.argv[1:]
d = json.load(open(path))
raw = open(out, errors='replace').read()
ok = re.search(r'OK \((\d+) tests?\)', raw)
bad = re.search(r'FAILURES!!!', raw)
if ok and not bad:
    status = 'PASS'
elif bad:
    status = 'FAIL'
else:
    status = 'NO_RESULT'
d.setdefault("cases", {})[label] = {
    "status": status,
    "tests_reported": int(ok.group(1)) if ok else None,
    "instrument_output_captured": bool(raw.strip()),
}
json.dump(d, open(path, "w"), indent=2)
PY
}

if [[ ${#CASES[@]} -gt 0 ]]; then
  for case in "${CASES[@]}"; do
    label="case-$(printf '%s' "$case" | tr '.#:' '___')"
    mm="${case%#*}"
    case "$mm" in
      com.openandroidintelligence.tailnet.core.*) pkg="com.openandroidintelligence.tailnet.core.test";;
      com.openandroidintelligence.transport.*)    pkg="com.openandroidintelligence.transport.test";;
      com.openandroidintelligence.mobile.*)       pkg="com.openandroidintelligence.mobile.test";;
      *) echo "P0T_BLOCKED: unknown module for $case" >&2; exit 2;;
    esac
    arg="-e class $case"
    out="$RESULTS.$label.txt"
    bash "$(dirname "${BASH_SOURCE[0]}")/audit-network.sh" "$label-before" "$AUDIT_DIR"
    instrument "$pkg" "$arg" "$out"
    bash "$(dirname "${BASH_SOURCE[0]}")/audit-network.sh" "$label-after" "$AUDIT_DIR"
    score_case "$label" "$out"
    echo "CASE $case status=$(python3 -c "import json,sys;print(json.load(open('$SUMMARY'))['cases']['$label']['status'])")"
  done
else
  echo "NO_CASES_SELECTED (pass --module-class ...)"
fi

echo "RUN_COMPLETE run_id=$RUN_ID evidence=$EVIDENCE_DIR"
