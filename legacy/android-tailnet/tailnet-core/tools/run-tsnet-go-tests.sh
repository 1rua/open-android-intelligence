#!/usr/bin/env bash
# Hermetic, offline runner for the tsnetbridge wrapper module test suite.
#
# Renders the wrapper go.mod.template against the pinned staged Tailscale
# source (same offline render mode as build-tsnet-aar.sh), then runs
# `go test` with the locked Go toolchain and a file-only module proxy. No test
# step downloads anything.
#
# Usage:
#   run-tsnet-go-tests.sh [go test flags] <package pattern...>
#
# stdout is machine-readable only (status token + per-package summary);
# diagnostics and failure output go to stderr.
set -euo pipefail

export PATH='/usr/sbin:/usr/bin:/bin'

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../../.." && pwd)"
GO_BIN='/usr/sbin/go'
export GOROOT='/usr/lib/go'
export GOPATH='/home/djbd/go'
toolchains="$repo_root/.toolchains"
wrapper_dir="$repo_root/apps/android/tailnet-core/native/tsnetbridge"
staged_source="$toolchains/tsnet-src-v1.98.10"
build_root="$toolchains/tsnet-go-test"

fail() { printf 'TSNET_GO_TESTS_BLOCKED: %s\n' "$1" >&2; exit 2; }

[[ -x "$GO_BIN" ]] || fail "go binary missing: $GO_BIN"
[[ -d "$staged_source" ]] || fail "staged source missing: $staged_source"
[[ -f "$staged_source/source-metadata.json" ]] || fail "staged source has no source-metadata.json"
[[ -f "$wrapper_dir/go.mod.template" ]] || fail "go.mod.template missing"
[[ -f "$wrapper_dir/go.sum" ]] || fail "go.sum missing"

export HOME="$toolchains/tsnet-home"
export TMPDIR="$toolchains/tsnet-tmp"
export GOMODCACHE="$toolchains/gomodcache"
export GOPROXY="file://$toolchains/gomodcache/cache/download"
export GOSUMDB='off'
export GOTOOLCHAIN='local'
export GOCACHE="$toolchains/go-build-cache"
export CGO_ENABLED='1'
export TZ='UTC'
export LC_ALL='C.UTF-8'
export LANG='C.UTF-8'

mkdir -p "$HOME" "$TMPDIR" "$GOCACHE"

# Fresh build directory with the rendered wrapper module.
rm -rf "$build_root"
mkdir -p "$build_root/tsnetbridge"
(cd "$wrapper_dir" && find . -name '*.go' -print0 | tar --null -cf - -T -) |
  tar -xf - -C "$build_root/tsnetbridge"

python3 - "$wrapper_dir/go.mod.template" "$staged_source" "$build_root/go.mod" <<'PY' >/dev/null
import pathlib, sys
src = pathlib.Path(sys.argv[1]).read_text()
staged = pathlib.Path(sys.argv[2]).resolve()
lines = []
for line in src.splitlines():
    if line.startswith('replace tailscale.com'):
        lines.append(f'replace tailscale.com => {staged}')
    else:
        lines.append(line)
pathlib.Path(sys.argv[3]).write_text('\n'.join(lines) + '\n')
PY
cp "$wrapper_dir/go.sum" "$build_root/go.sum"

log="$build_root/test.log"
if (cd "$build_root" && "$GO_BIN" test "$@" ) >"$log" 2>&1; then
  ok_lines="$(grep -E '^(ok|PASS|FAIL|no test files)' "$log" || true)"
  printf 'TSNET_GO_TESTS_OK\n'
  printf '%s\n' "$ok_lines"
  exit 0
fi

# Point the failure diagnostics at the retained log.
printf 'TSNET_GO_TESTS_FAILED\n' >&2
grep -E '^(ok|PASS|FAIL|--- FAIL|=== RUN)' "$log" | head -80 >&2 || true
printf 'log: %s\n' "$log" >&2
exit 1