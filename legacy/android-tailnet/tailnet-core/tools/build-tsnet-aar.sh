#!/usr/bin/env bash
# Hermetic, reproducible build of the locked tsnet Android AAR.
#
# Stages the pinned Tailscale source (v1.98.10, commit from the lock), renders
# the wrapper module against the staged read-only source, runs the lock's exact
# gomobile bind vector for android/arm64 + android/amd64, normalizes the AAR,
# and writes the four tracked outputs into apps/android/tailnet-core/libs/:
#   tsnet-android-1.98.10.aar / .sha256 / .provenance.json / .sbom.json
#
# Offline by design: GOPROXY is a local file proxy over the pinned workspace
# module-cache mirror, GOSUMDB=off. No build step downloads anything.
set -euo pipefail

SOURCE_DATE_EPOCH='1785276305'   # commit 36550d57 commit time (UTC)
readonly SOURCE_COMMIT='36550d57f4a4055246ef7412f4e650a012a465f1'
readonly SOURCE_TAG_OBJECT='0ee734d3089846b27bc6ebcddd3d6ee5ec13e04d'
readonly SOURCE_RELEASE='v1.98.10'
readonly TARGET_VECTOR=(
  -target=android/arm64,android/amd64
  -androidapi=34
  -trimpath
  -tags=ts_omit_cachenetmap
  "-ldflags=-buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384"
  -o tsnet-android-1.98.10.raw.aar
  ./tsnetbridge
)

fail() { printf 'TSNET_AAR_BUILD_BLOCKED: %s\n' "$1" >&2; exit 2; }

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../../.." && pwd)"
toolchains="$repo_root/.toolchains"
lock_file="$repo_root/apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json"
wrapper_dir="$repo_root/apps/android/tailnet-core/native/tsnetbridge"
staged_source="$toolchains/tsnet-src-v1.98.10"
build_root="$toolchains/tsnet-build"
out_dir="$repo_root/apps/android/tailnet-core/libs"

readonly NDK_ROOT="$toolchains/android-sdk/ndk/27.2.12479018"
readonly JDK_ROOT="$toolchains/jdk-17.0.20+8"
GOMODCACHE="$toolchains/gomodcache"
GOCACHE="$toolchains/go-build-cache"
GO_BIN='/usr/sbin/go'
GOMOBILE_BIN="$repo_root/.toolchains/go-workspace/bin/gomobile"
GOBIND_BIN="$repo_root/.toolchains/go-workspace/bin/gobind"

[[ -x "$GO_BIN" ]] || fail "go binary missing: $GO_BIN"
[[ -x "$GOMOBILE_BIN" ]] || fail "gomobile binary missing: $GOMOBILE_BIN"
[[ -x "$GOBIND_BIN" ]] || fail "gobind binary missing: $GOBIND_BIN"
[[ -f "$lock_file" ]] || fail "lock file missing: $lock_file"
[[ -d "$staged_source" ]] || fail "staged source missing (run stage-tsnet-source.sh first): $staged_source"
[[ -f "$staged_source/source-metadata.json" ]] || fail "staged source has no source-metadata.json"

export HOME="$toolchains/tsnet-home"
export TMPDIR="$toolchains/tsnet-tmp"
export PATH="/usr/sbin:/usr/bin:/bin:$(dirname "$GOMOBILE_BIN"):$JDK_ROOT/bin"
export GOROOT='/usr/lib/go'
export GOPATH='/home/djbd/go'
export GOMODCACHE="$GOMODCACHE"
export GOPROXY="file://$toolchains/gomodcache/cache/download"
export GOSUMDB='off'
export GOTOOLCHAIN='local'
export GOCACHE="$toolchains/tsnet-go-build-cache-aar"
export ANDROID_HOME="$toolchains/android-sdk"
export ANDROID_NDK_HOME="$NDK_ROOT"
export JAVA_HOME="$JDK_ROOT"
export CGO_ENABLED='1'
export TZ='UTC'
export LC_ALL='C.UTF-8'
export LANG='C.UTF-8'
export JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
export SOURCE_DATE_EPOCH="$SOURCE_DATE_EPOCH"

mkdir -p "$HOME" "$TMPDIR" "$GOCACHE" "$out_dir"

# Fresh build directory with the rendered wrapper module.
rm -rf "$build_root"
mkdir -p "$build_root/tsnetbridge"
cp "$wrapper_dir"/*.go "$build_root/tsnetbridge/"
cp "$wrapper_dir/go.sum" "$build_root/go.sum"
python3 - "$wrapper_dir/go.mod" "$staged_source" "$build_root/go.mod" <<'PY'
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

echo "=== toolchain ==="
"$GO_BIN" version
"$GOMOBILE_BIN" version 2>&1 | head -1 || true
java -version 2>&1 | head -1
echo "=== go mod verify ==="
(cd "$build_root" && "$GO_BIN" mod verify)

echo "=== gomobile bind ==="
(cd "$build_root" && "$GOMOBILE_BIN" bind "${TARGET_VECTOR[@]}")

raw="$build_root/tsnet-android-1.98.10.raw.aar"
[[ -f "$raw" ]] || fail "raw AAR not produced: $raw"

echo "=== normalize ==="
python3 "$script_dir/normalize-aar.py" \
  --source-date-epoch "$SOURCE_DATE_EPOCH" \
  --input "$raw" \
  --output "$build_root/tsnet-android-1.98.10.aar"

echo "=== sidecars ==="
aar="$build_root/tsnet-android-1.98.10.aar"
raw_sha="$(sha256sum "$raw" | awk '{print $1}')"
aar_sha="$(sha256sum "$aar" | awk '{print $1}')"
cp "$aar" "$out_dir/tsnet-android-1.98.10.aar"
printf '%s  %s\n' "$aar_sha" "tsnet-android-1.98.10.aar" > "$out_dir/tsnet-android-1.98.10.aar.sha256"

(cd "$build_root" && "$GO_BIN" list -m -json all) > "$build_root/modules.json"

python3 "$script_dir/generate-tsnet-provenance.py" \
  --output "$out_dir/tsnet-android-1.98.10.provenance.json" \
  --aar "$aar" --raw-aar "$raw" \
  --aar-sha256 "$aar_sha" --raw-sha256 "$raw_sha" \
  --source-manifest "$staged_source/source-manifest.sha256" \
  --source-commit "$SOURCE_COMMIT" --source-tag-object "$SOURCE_TAG_OBJECT" \
  --source-release "$SOURCE_RELEASE" --source-date-epoch "$SOURCE_DATE_EPOCH"

python3 "$script_dir/generate-tsnet-sbom.py" \
  --modules "$build_root/modules.json" \
  --provenance "$out_dir/tsnet-android-1.98.10.provenance.json" \
  --output "$out_dir/tsnet-android-1.98.10.sbom.json"

(cd "$build_root" && GOOS=android GOARCH=arm64 "$GO_BIN" list -deps -json ./tsnetbridge) > "$build_root/deps.json"
python3 - "$build_root/deps.json" "$build_root/deps-modules.json" <<'PYINNER'
import json, pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text()
decoder = json.JSONDecoder()
pkgs = []
i = 0
while i < len(text):
    while i < len(text) and text[i].isspace():
        i += 1
    if i >= len(text):
        break
    obj, i = decoder.raw_decode(text, i)
    pkgs.append(obj)
mods = {}
for pkg in pkgs:
    m = pkg.get('Module')
    if m and m.get('Version'):
        mods[m['Path']] = m['Version']
pathlib.Path(sys.argv[2]).write_text(json.dumps(
    [{'Path': k, 'Version': v} for k, v in sorted(mods.items())], indent=1) + '\n')
PYINNER
python3 "$script_dir/generate-tsnet-notices.py" \
  --modules "$build_root/deps-modules.json" \
  --gomodcache "$GOMODCACHE" \
  --staged-source "$staged_source" \
  --output "$repo_root/apps/android/tailnet-core/native/tsnetbridge/THIRD_PARTY_NOTICES.md"

echo "=== verify ==="
python3 "$script_dir/verify-tsnet-aar.py" \
  --lock "$lock_file" \
  --aar "$out_dir/tsnet-android-1.98.10.aar" \
  --provenance "$out_dir/tsnet-android-1.98.10.provenance.json" \
  --sbom "$out_dir/tsnet-android-1.98.10.sbom.json" \
  --notices "$repo_root/apps/android/tailnet-core/native/tsnetbridge/THIRD_PARTY_NOTICES.md"

printf 'TSNET_AAR_BUILT sha256=%s\n' "$aar_sha"
