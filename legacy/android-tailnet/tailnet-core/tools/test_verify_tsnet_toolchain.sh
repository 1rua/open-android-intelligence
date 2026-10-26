#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
verifier_source="$repo_root/apps/android/tailnet-core/tools/verify-tsnet-toolchain.sh"
bootstrap_source="$repo_root/apps/android/tailnet-core/tools/bootstrap-tsnet-toolchain.sh"
tmp_root="$(mktemp -d)"
trap 'chmod -R u+w "$tmp_root" 2>/dev/null || true; rm -rf "$tmp_root"' EXIT

[[ -f "$verifier_source" ]] || {
  echo 'not ok - offline verifier does not exist' >&2
  exit 1
}
[[ -f "$bootstrap_source" ]] || {
  echo 'not ok - controller bootstrap does not exist' >&2
  exit 1
}

fixture_root="$tmp_root/repository"
tools_dir="$fixture_root/apps/android/tailnet-core/tools"
lock_dir="$fixture_root/apps/android/tailnet-core/native/tsnetbridge"
toolchains="$fixture_root/.toolchains"
downloads="$toolchains/downloads"
go_root="$toolchains/go-1.26.5"
gomobile_root="$toolchains/gomobile-v0.0.0-20240806205939-81131f6468ab"
sdk_root="$toolchains/android-sdk"
ndk_root="$sdk_root/ndk/27.2.12479018"
jdk_root="$toolchains/jdk-17.0.20+8"
module_cache="$toolchains/go-module-cache"
go_cache="$toolchains/go-build-cache"
lock_file="$lock_dir/tsnet-aar.lock.json"
mkdir -p "$tools_dir" "$lock_dir" "$downloads" "$go_root/bin" "$gomobile_root/bin" \
  "$sdk_root/platforms/android-34" "$sdk_root/platforms/android-35" "$ndk_root" \
  "$jdk_root/bin" "$module_cache" "$go_cache"
cp "$verifier_source" "$tools_dir/verify-tsnet-toolchain.sh"
cp "$bootstrap_source" "$tools_dir/bootstrap-tsnet-toolchain.sh"

printf 'fixture go archive\n' >"$downloads/go1.26.5.linux-amd64.tar.gz"
printf 'fixture ndk archive\n' >"$downloads/android-ndk-r27c-linux.zip"
fixture_go_archive_sha="$(sha256sum "$downloads/go1.26.5.linux-amd64.tar.gz" | awk '{print $1}')"
fixture_ndk_archive_sha="$(sha256sum "$downloads/android-ndk-r27c-linux.zip" | awk '{print $1}')"
printf 'android api 34\n' >"$sdk_root/platforms/android-34/package.xml"
printf 'Pkg.Revision=1\nAndroidVersion.ApiLevel=34\n' >"$sdk_root/platforms/android-34/source.properties"
printf 'android api 35\n' >"$sdk_root/platforms/android-35/package.xml"
printf 'Pkg.Revision=2\nAndroidVersion.ApiLevel=35\n' >"$sdk_root/platforms/android-35/source.properties"
printf 'Pkg.Revision = 27.2.12479018\n' >"$ndk_root/source.properties"
printf 'ndk payload\n' >"$ndk_root/NOTICE"

write_go() {
  local version_line="$1"
  cat >"$go_root/bin/go" <<EOF
#!/usr/bin/env bash
if [[ "\${1:-}" == version && "\${2:-}" == -m ]]; then
  printf '%s\n' "\${2:-}: go1.26.5" 'path golang.org/x/mobile/cmd/tool' 'mod golang.org/x/mobile v0.0.0-20240806205939-81131f6468ab h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k='
elif [[ "\${1:-}" == version ]]; then
  printf '%s\n' '$version_line'
else
  exit 64
fi
EOF
  chmod 0755 "$go_root/bin/go"
}

write_mobile_tool() {
  local name="$1"
  cat >"$gomobile_root/bin/$name" <<EOF
#!/usr/bin/env bash
printf '%s\n' '$name fixture'
EOF
  chmod 0755 "$gomobile_root/bin/$name"
}

write_java() {
  local output="$1"
  cat >"$jdk_root/bin/java" <<EOF
#!/usr/bin/env bash
printf '%s\n' '$output' >&2
EOF
  chmod 0755 "$jdk_root/bin/java"
}

write_go 'go version go1.26.5 linux/amd64'
write_mobile_tool gomobile
write_mobile_tool gobind
write_java 'openjdk version "17.0.20" 2026-07-14 LTS; OpenJDK Runtime Environment Temurin-17.0.20+8 (build 17.0.20+8)'

directory_digest() {
  python3 - "$1" <<'PY'
import hashlib, os, pathlib, stat, sys
root = pathlib.Path(sys.argv[1])
rows = []
for path in sorted(root.rglob("*"), key=lambda p: os.fsencode(p.relative_to(root).as_posix())):
    relative = path.relative_to(root).as_posix()
    info = path.lstat()
    mode = stat.S_IMODE(info.st_mode)
    if path.is_symlink():
        kind, payload = "l", os.fsencode(os.readlink(path))
    elif path.is_file():
        kind, payload = "f", path.read_bytes()
    elif path.is_dir():
        kind, payload = "d", b""
    else:
        raise SystemExit(f"unsupported fixture entry: {relative}")
    rows.append(f"{kind}\t{mode:04o}\t{len(payload)}\t{hashlib.sha256(payload).hexdigest()}\t{relative}\n".encode())
print(hashlib.sha256(b"".join(rows)).hexdigest())
PY
}

write_lock() {
  local output="$1"
  local mobile_version="${2:-v0.0.0-20240806205939-81131f6468ab}"
  local mobile_sum="${3:-h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k=}"
  local go_archive_sha ndk_archive_sha
  go_archive_sha="$(sha256sum "$downloads/go1.26.5.linux-amd64.tar.gz" | awk '{print $1}')"
  ndk_archive_sha="$(sha256sum "$downloads/android-ndk-r27c-linux.zip" | awk '{print $1}')"
  python3 - "$output" "$go_archive_sha" "$ndk_archive_sha" "$mobile_version" "$mobile_sum" \
    "$(sha256sum "$go_root/bin/go" | awk '{print $1}')" "$(directory_digest "$go_root")" \
    "$(sha256sum "$gomobile_root/bin/gomobile" | awk '{print $1}')" \
    "$(sha256sum "$gomobile_root/bin/gobind" | awk '{print $1}')" "$(directory_digest "$gomobile_root")" \
    "$(directory_digest "$ndk_root")" "$(directory_digest "$sdk_root/platforms/android-34")" \
    "$(directory_digest "$sdk_root/platforms/android-35")" \
    "$(sha256sum "$jdk_root/bin/java" | awk '{print $1}')" "$(directory_digest "$jdk_root")" <<'PY'
import json, pathlib, sys
(path, go_archive, ndk_archive, mobile_version, mobile_sum, go_binary, go_dir,
 mobile_binary, gobind_binary, mobile_dir, ndk_dir, api34_dir, api35_dir,
 java_binary, jdk_dir) = sys.argv[1:]
data = {
  "schemaVersion": 1,
  "source": {"upstreamUrl": "https://github.com/tailscale/tailscale.git", "release": "v1.98.10", "tagObject": "0ee734d3089846b27bc6ebcddd3d6ee5ec13e04d", "commit": "36550d57f4a4055246ef7412f4e650a012a465f1", "sourceFiles": {"version": {"path": "VERSION.txt", "value": "1.98.10"}, "module": {"path": "go.mod", "module": "tailscale.com", "goDirective": "1.26.5"}}},
  "toolchain": {
    "go": {"archive": "go1.26.5.linux-amd64.tar.gz", "sha256": go_archive},
    "gomobile": {"module": "golang.org/x/mobile", "tools": ["gomobile", "gobind"]},
    "androidNdk": {"archive": "android-ndk-r27c-linux.zip", "revision": "27.2.12479018", "sha256": ndk_archive, "provisionalDigest": False},
    "jdk": {"distribution": "Temurin", "version": "17.0.20+8"},
    "androidSdk": {"gomobileApi": 34, "compileSdk": 35},
    "gradle": {"agp": "8.9.2", "gradle": "8.12", "kotlin": "2.1.20"}},
  "abi": {"gomobileTargets": ["android/arm64", "android/amd64"], "aarAbis": ["arm64-v8a", "x86_64"]},
  "build": {"argumentVector": ["gomobile", "bind", "-target=android/arm64,android/amd64", "-androidapi=34", "-trimpath", "-tags=ts_omit_cachenetmap", "-ldflags=-buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384", "-o", "tsnet-android-1.98.10.raw.aar", "./tsnetbridge"], "minimumElfLoadAlignment": 16384, "maximumAarBytes": 83886080, "wireFrame": {"transport": "binary-wss-message", "minimumBytes": 1, "maximumBytes": 262144}},
  "dependencies": {"golang.org/x/mobile": {"version": mobile_version, "sum": mobile_sum}, "github.com/coder/websocket": {"version": "v1.8.12", "sum": None}},
  "outputs": {"rawAar": "tsnet-android-1.98.10.raw.aar", "aar": "tsnet-android-1.98.10.aar", "aarSha256": "tsnet-android-1.98.10.aar.sha256", "provenance": "tsnet-android-1.98.10.provenance.json", "sbom": "tsnet-android-1.98.10.sbom.json", "notices": "THIRD_PARTY_NOTICES.md"},
  "resolvedDigests": {
    "go": {"archiveSha256": go_archive, "binarySha256": go_binary, "directoryManifestSha256": go_dir},
    "gomobile": {"moduleSum": mobile_sum, "gomobileBinarySha256": mobile_binary, "gobindBinarySha256": gobind_binary, "directoryManifestSha256": mobile_dir},
    "androidNdk": {"archiveSha256": ndk_archive, "directoryManifestSha256": ndk_dir},
    "androidSdk": {"api34DirectoryManifestSha256": api34_dir, "api35DirectoryManifestSha256": api35_dir},
    "jdk": {"javaBinarySha256": java_binary, "directoryManifestSha256": jdk_dir}
  }
}
pathlib.Path(path).write_text(json.dumps(data, indent=2) + "\n")
PY
}

write_lock "$lock_file"

git init -q "$fixture_root"
git -C "$fixture_root" config user.name 'Tsnet Toolchain Test'
git -C "$fixture_root" config user.email 'tsnet-toolchain@example.invalid'
git -C "$fixture_root" add apps
git -C "$fixture_root" commit -q -m fixture

pass_count=0
pass() {
  pass_count=$((pass_count + 1))
  printf 'ok %d - %s\n' "$pass_count" "$1"
}

run_verify() {
  local output="$1"
  local env_file="$2"
  shift 2
  env -i PATH="${PATH:-/usr/bin:/bin}" "$@" \
    bash -c 'source "$1"; shift; verify_tsnet_toolchain "$@"' bash \
    "$tools_dir/verify-tsnet-toolchain.sh" "$fixture_root" "$fixture_go_archive_sha" "$fixture_ndk_archive_sha" \
    --lock "$lock_file" --emit-env "$env_file" >"$output" 2>&1
}

expect_blocked() {
  local name="$1"
  local expected="$2"
  shift 2
  local output="$tmp_root/output-$pass_count.txt"
  local env_file="$tmp_root/env-$pass_count.sh"
  local status=0
  run_verify "$output" "$env_file" "$@" || status=$?
  [[ "$status" -eq 2 ]] || {
    printf 'not ok - %s (expected exit 2, got %s)\n' "$name" "$status" >&2
    cat "$output" >&2
    exit 1
  }
  grep -F 'TSNET_TOOLCHAIN_BLOCKED' "$output" >/dev/null || { cat "$output" >&2; exit 1; }
  grep -F "$expected" "$output" >/dev/null || { cat "$output" >&2; exit 1; }
  [[ ! -e "$env_file" ]] || { echo 'blocked verification emitted an env file' >&2; exit 1; }
  pass "$name"
}

baseline_output="$tmp_root/baseline-output.txt"
baseline_env="$tmp_root/baseline-env.sh"
run_verify "$baseline_output" "$baseline_env"
grep -Fx 'TSNET_TOOLCHAIN_READY' "$baseline_output" >/dev/null
bash -n "$baseline_env"
python3 - "$baseline_env" "$fixture_root" <<'PY'
import pathlib, shlex, sys
env_path, root = map(pathlib.Path, sys.argv[1:])
allowed = {"JAVA_HOME", "ANDROID_HOME", "ANDROID_NDK_HOME", "GOROOT", "GOMOBILE", "GOBIND", "GOMODCACHE", "GOCACHE"}
seen = set()
for line in env_path.read_text().splitlines():
    assert line.startswith("export ") and "=" in line
    key, raw = line[7:].split("=", 1)
    assert key in allowed and key not in seen
    value = pathlib.Path(shlex.split(raw)[0])
    assert value.is_absolute()
    assert value == root or root in value.parents
    seen.add(key)
assert seen == allowed
PY
pass 'locked fixture verifies offline and emits only absolute nonsecret paths'

linked_root="$tmp_root/linked-worktree"
git -C "$fixture_root" worktree add -q -b linked-fixture "$linked_root"
linked_output="$tmp_root/linked-output.txt"
linked_env="$tmp_root/linked-env.sh"
env -i PATH="${PATH:-/usr/bin:/bin}" \
  bash -c 'source "$1"; shift; verify_tsnet_toolchain "$@"' bash \
  "$linked_root/apps/android/tailnet-core/tools/verify-tsnet-toolchain.sh" \
  "$linked_root" "$fixture_go_archive_sha" "$fixture_ndk_archive_sha" \
  --lock "$linked_root/apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json" \
  --emit-env "$linked_env" >"$linked_output" 2>&1
grep -Fx 'TSNET_TOOLCHAIN_READY' "$linked_output" >/dev/null
grep -F "$(printf '%q' "$fixture_root/.toolchains/go-1.26.5")" "$linked_env" >/dev/null
pass 'linked worktrees resolve the shared git-common-dir toolchain root'

hostile_git="$tmp_root/hostile-git"
git init -q "$hostile_git"
hostile_output="$tmp_root/hostile-output.txt"
hostile_env="$tmp_root/hostile-env.sh"
env -i PATH="${PATH:-/usr/bin:/bin}" \
  GIT_DIR="$hostile_git/.git" GIT_COMMON_DIR="$hostile_git/.git" \
  GIT_WORK_TREE="$hostile_git" GIT_CEILING_DIRECTORIES="$tmp_root" \
  bash -c 'source "$1"; shift; verify_tsnet_toolchain "$@"' bash \
  "$linked_root/apps/android/tailnet-core/tools/verify-tsnet-toolchain.sh" \
  "$linked_root" "$fixture_go_archive_sha" "$fixture_ndk_archive_sha" \
  --lock "$linked_root/apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json" \
  --emit-env "$hostile_env" >"$hostile_output" 2>&1
grep -Fx 'TSNET_TOOLCHAIN_READY' "$hostile_output" >/dev/null
grep -F "$(printf '%q' "$fixture_root/.toolchains/go-1.26.5")" "$hostile_env" >/dev/null
pass 'hostile ambient Git variables cannot redirect the shared toolchain root'

rmdir "$go_cache"
expect_blocked 'offline verifier rejects a missing cache without creating it' 'locked cache directory is missing'
[[ ! -e "$go_cache" ]] || { echo 'offline verifier mutated a missing cache directory' >&2; exit 1; }
mkdir "$go_cache"

printf 'Pkg.Revision=2\nAndroidVersion.ApiLevel=35\n' >"$sdk_root/platforms/android-34/source.properties"
write_lock "$lock_file"
expect_blocked 'SDK directory basename must match its API metadata' 'does not report API level 34'
printf 'Pkg.Revision=1\nAndroidVersion.ApiLevel=34\n' >"$sdk_root/platforms/android-34/source.properties"
write_lock "$lock_file"

cp "$lock_file" "$tmp_root/good.lock"

poison_marker="$tmp_root/poison-marker"
cp "$go_root/bin/go" "$tmp_root/good-go"
poison_go() {
  cat >"$go_root/bin/go" <<EOF
#!/usr/bin/env bash
printf 'executed poisoned Go\n' >>'$poison_marker'
if [[ "\${1:-}" == version && "\${2:-}" == -m ]]; then
  printf '%s\n' 'path golang.org/x/mobile/cmd/tool' 'mod golang.org/x/mobile v0.0.0-20240806205939-81131f6468ab h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k='
else
  printf 'go version go1.26.5 linux/amd64\n'
fi
EOF
  chmod 0755 "$go_root/bin/go"
}

poison_go
expect_blocked 'Go digest mismatch blocks before executing poisoned Go' 'Go binary digest does not match resolved lock'
[[ ! -e "$poison_marker" ]] || { echo 'verifier executed poisoned Go before digest admission' >&2; exit 1; }
cp "$tmp_root/good-go" "$go_root/bin/go"

cp "$jdk_root/bin/java" "$tmp_root/good-java"
cat >"$jdk_root/bin/java" <<EOF
#!/usr/bin/env bash
printf 'executed poisoned Java\n' >>'$poison_marker'
printf 'openjdk version "17.0.20"; OpenJDK Runtime Environment Temurin-17.0.20+8\n' >&2
EOF
chmod 0755 "$jdk_root/bin/java"
expect_blocked 'Java digest mismatch blocks before executing poisoned Java' 'Java binary digest does not match resolved lock'
[[ ! -e "$poison_marker" ]] || { echo 'verifier executed poisoned Java before digest admission' >&2; exit 1; }
cp "$tmp_root/good-java" "$jdk_root/bin/java"

cp "$tmp_root/good.lock" "$lock_file"
python3 - "$lock_file" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["toolchain"]["androidNdk"]["provisionalDigest"] = True
data.pop("resolvedDigests")
path.write_text(json.dumps(data, indent=2) + "\n")
PY
poison_go
expect_blocked 'missing resolved digests block before executing any locked tool' 'lock missing keys: resolvedDigests'
[[ ! -e "$poison_marker" ]] || { echo 'unresolved verifier lock executed a tool' >&2; exit 1; }
cp "$tmp_root/good-go" "$go_root/bin/go"

cp "$tmp_root/good.lock" "$lock_file"
python3 - "$lock_file" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["toolchain"]["androidNdk"]["provisionalDigest"] = True
path.write_text(json.dumps(data, indent=2) + "\n")
PY
poison_go
expect_blocked 'a complete resolved section remains blocked while NDK digest is provisional' 'toolchain.androidNdk.provisionalDigest'
[[ ! -e "$poison_marker" ]] || { echo 'provisional lock executed a tool' >&2; exit 1; }
cp "$tmp_root/good-go" "$go_root/bin/go"

cp "$tmp_root/good.lock" "$lock_file"
python3 - "$lock_file" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
replacement = "a" * 64
data["toolchain"]["go"]["sha256"] = replacement
data["resolvedDigests"]["go"]["archiveSha256"] = replacement
path.write_text(json.dumps(data, indent=2) + "\n")
PY
poison_go
expect_blocked 'coordinated Go archive SHA replacement is rejected canonically' 'toolchain.go.sha256 does not match the canonical value'
[[ ! -e "$poison_marker" ]] || { echo 'coordinated Go SHA lock executed a tool' >&2; exit 1; }
cp "$tmp_root/good-go" "$go_root/bin/go"

cp "$tmp_root/good.lock" "$lock_file"
python3 - "$lock_file" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
replacement = "b" * 64
data["toolchain"]["androidNdk"]["sha256"] = replacement
data["resolvedDigests"]["androidNdk"]["archiveSha256"] = replacement
path.write_text(json.dumps(data, indent=2) + "\n")
PY
poison_go
expect_blocked 'coordinated NDK archive SHA replacement is rejected canonically' 'toolchain.androidNdk.sha256 does not match the canonical value'
[[ ! -e "$poison_marker" ]] || { echo 'coordinated NDK SHA lock executed a tool' >&2; exit 1; }
cp "$tmp_root/good-go" "$go_root/bin/go"

cp "$tmp_root/good.lock" "$lock_file"
write_go 'go version go1.26.5-X:nodwarf5 linux/amd64'
write_lock "$lock_file"
expect_blocked 'custom Go version suffix is rejected' 'stock go1.26.5'

write_go 'go version go1.25.0 linux/amd64'
write_lock "$lock_file"
expect_blocked 'Go 1.25 is rejected' 'stock go1.26.5'

write_go 'go version go1.26.5 linux/amd64'
write_lock "$lock_file" 'v0.0.0-20240806205939-deadbeefdead'
expect_blocked 'wrong gomobile module revision is rejected' 'dependencies.golang.org/x/mobile.version'

write_lock "$lock_file" 'v0.0.0-20240806205939-81131f6468ab' 'h1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
expect_blocked 'unexpected gomobile module sum is rejected' 'dependencies.golang.org/x/mobile.sum'

write_lock "$lock_file"
mv "$ndk_root" "$sdk_root/ndk/27.0.12077973"
expect_blocked 'NDK basename must equal Pkg.Revision' 'correctly named NDK directory'
mv "$sdk_root/ndk/27.0.12077973" "$ndk_root"

mv "$sdk_root/platforms/android-34" "$tmp_root/android-34"
expect_blocked 'missing Android API 34 is rejected' 'android-34'
mv "$tmp_root/android-34" "$sdk_root/platforms/android-34"

mv "$sdk_root/platforms/android-35" "$tmp_root/android-35"
expect_blocked 'missing compile SDK 35 is rejected' 'android-35'
mv "$tmp_root/android-35" "$sdk_root/platforms/android-35"

write_java 'openjdk version "17.0.19"; OpenJDK Runtime Environment Temurin-17.0.19+7'
write_lock "$lock_file"
expect_blocked 'wrong JDK version is rejected' 'Temurin 17.0.20+8'

write_java 'openjdk version "17.0.20"; OpenJDK Runtime Environment OpenJDK-17.0.20+8'
write_lock "$lock_file"
expect_blocked 'non-Temurin JDK is rejected' 'Temurin 17.0.20+8'

write_java 'openjdk version "17.0.20"; OpenJDK Runtime Environment Temurin-17.0.20+8'
write_lock "$lock_file"
ambient_bin="$tmp_root/ambient-bin"
mkdir "$ambient_bin"
cp "$go_root/bin/go" "$ambient_bin/go"
rm "$go_root/bin/go"
expect_blocked 'PATH Go is never used as a fallback' 'locked Go executable is missing' PATH="$ambient_bin:/usr/bin:/bin"

cp "$tmp_root/good.lock" "$lock_file"
if env -i PATH="${PATH:-/usr/bin:/bin}" bash "$tools_dir/bootstrap-tsnet-toolchain.sh" >"$tmp_root/bootstrap-output.txt" 2>&1; then
  echo 'bootstrap unexpectedly ran without --download' >&2
  exit 1
fi
grep -F 'controller-only' "$tmp_root/bootstrap-output.txt" >/dev/null
pass 'bootstrap requires the explicit controller-only --download flag'

python3 - "$lock_file" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["toolchain"]["go"]["sha256"] = "5c2c3b16caefa1d968a94c1daca04a7ca301a496d9b086e17ad77bb81393f053"
data["toolchain"]["androidNdk"]["sha256"] = "59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc"
data["toolchain"]["androidNdk"]["provisionalDigest"] = True
data.pop("resolvedDigests", None)
path.write_text(json.dumps(data, indent=2) + "\n")
PY
cp "$lock_file" "$tmp_root/lock-before-bootstrap"
rm -f "$downloads/go1.26.5.linux-amd64.tar.gz"
fake_download_bin="$tmp_root/fake-download-bin"
download_trace="$tmp_root/download-trace.txt"
mkdir "$fake_download_bin"
cat >"$fake_download_bin/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$TSNET_TEST_DOWNLOAD_TRACE"
output=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      output="$2"
      shift 2
      ;;
    *) shift ;;
  esac
done
printf 'untrusted download bytes\n' >"$output"
EOF
chmod 0755 "$fake_download_bin/curl"
status=0
env -i PATH="$fake_download_bin:/usr/bin:/bin" TSNET_TEST_DOWNLOAD_TRACE="$download_trace" \
  bash "$tools_dir/bootstrap-tsnet-toolchain.sh" --download >"$tmp_root/bootstrap-download-output.txt" 2>&1 || status=$?
[[ "$status" -eq 2 ]] || { cat "$tmp_root/bootstrap-download-output.txt" >&2; exit 1; }
grep -F 'go1.26.5.linux-amd64.tar.gz' "$tmp_root/bootstrap-download-output.txt" >/dev/null
grep -F 'supply-chain digest mismatch' "$tmp_root/bootstrap-download-output.txt" >/dev/null
grep -F 'https://go.dev/dl/go1.26.5.linux-amd64.tar.gz' "$download_trace" >/dev/null
if grep -F 'dl.google.com' "$download_trace" >/dev/null; then
  echo 'bootstrap continued to NDK after the Go digest failure' >&2
  exit 1
fi
cmp -s "$lock_file" "$tmp_root/lock-before-bootstrap" || {
  echo 'bootstrap rewrote the lock to accept mismatched download bytes' >&2
  exit 1
}
pass 'bootstrap uses the official Go URL and fails closed on downloaded digest mismatch'

rm -f "$download_trace"
mv "$sdk_root/platforms/android-34" "$tmp_root/bootstrap-missing-android-34"
status=0
env -i PATH="$fake_download_bin:/usr/bin:/bin" TSNET_TEST_DOWNLOAD_TRACE="$download_trace" \
  bash "$tools_dir/bootstrap-tsnet-toolchain.sh" --download >"$tmp_root/bootstrap-prerequisite-output.txt" 2>&1 || status=$?
[[ "$status" -eq 2 ]] || { cat "$tmp_root/bootstrap-prerequisite-output.txt" >&2; exit 1; }
grep -F 'android-34' "$tmp_root/bootstrap-prerequisite-output.txt" >/dev/null
[[ ! -e "$download_trace" ]] || {
  echo 'bootstrap downloaded before validating controller-provided SDK/JDK inputs' >&2
  cat "$download_trace" >&2
  exit 1
}
mv "$tmp_root/bootstrap-missing-android-34" "$sdk_root/platforms/android-34"
pass 'bootstrap validates controller-provided SDK packages before downloading'

run_bootstrap_fixture() {
  local output="$1"
  shift
  env -i PATH="${PATH:-/usr/bin:/bin}" "$@" \
    bash -c 'source "$1"; shift; bootstrap_tsnet_toolchain "$@"' bash \
    "$tools_dir/bootstrap-tsnet-toolchain.sh" "$fixture_root" "$fixture_go_archive_sha" "$fixture_ndk_archive_sha" \
    >"$output" 2>&1
}

write_go 'go version go1.26.5 linux/amd64'
write_java 'openjdk version "17.0.20" 2026-07-14 LTS; OpenJDK Runtime Environment Temurin-17.0.20+8 (build 17.0.20+8)'
printf 'fixture go archive\n' >"$downloads/go1.26.5.linux-amd64.tar.gz"
write_lock "$lock_file"
python3 - "$lock_file" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
data["toolchain"]["androidNdk"]["provisionalDigest"] = True
data.pop("resolvedDigests")
path.write_text(json.dumps(data, indent=2) + "\n")
PY
cp "$lock_file" "$tmp_root/bootstrap-unresolved.lock"
cat >"$go_root/bin/go" <<EOF
#!/usr/bin/env bash
printf 'bootstrap executed poisoned pre-existing Go\n' >>'$poison_marker'
printf 'go version go1.26.5 linux/amd64\n'
EOF
chmod 0755 "$go_root/bin/go"
status=0
run_bootstrap_fixture "$tmp_root/bootstrap-preexisting-go.out" || status=$?
[[ "$status" -eq 2 ]] || { cat "$tmp_root/bootstrap-preexisting-go.out" >&2; exit 1; }
grep -F 'unverified pre-existing Go install' "$tmp_root/bootstrap-preexisting-go.out" >/dev/null
[[ ! -e "$poison_marker" ]] || { echo 'bootstrap executed an unverified pre-existing Go install' >&2; exit 1; }
cmp -s "$lock_file" "$tmp_root/bootstrap-unresolved.lock"
pass 'bootstrap rejects an unverified pre-existing Go install without executing it or writing the lock'

mv "$go_root" "$tmp_root/absent-go-root"
status=0
run_bootstrap_fixture "$tmp_root/bootstrap-preexisting-ndk.out" || status=$?
[[ "$status" -eq 2 ]] || { cat "$tmp_root/bootstrap-preexisting-ndk.out" >&2; exit 1; }
grep -F 'unverified pre-existing NDK install' "$tmp_root/bootstrap-preexisting-ndk.out" >/dev/null
cmp -s "$lock_file" "$tmp_root/bootstrap-unresolved.lock"
mv "$tmp_root/absent-go-root" "$go_root"
pass 'bootstrap rejects an unverified pre-existing correctly named NDK without writing the lock'

write_go 'go version go1.26.5 linux/amd64'
write_lock "$lock_file"
cp "$lock_file" "$tmp_root/bootstrap-resolved.lock"
printf 'tampered Go bytes\n' >>"$go_root/bin/go"
status=0
run_bootstrap_fixture "$tmp_root/bootstrap-resolved-go.out" || status=$?
[[ "$status" -eq 2 ]] || { cat "$tmp_root/bootstrap-resolved-go.out" >&2; exit 1; }
grep -F 'pre-existing Go install digest does not match resolved lock' "$tmp_root/bootstrap-resolved-go.out" >/dev/null
[[ ! -e "$poison_marker" ]] || { echo 'bootstrap executed a mismatched pre-existing Go install' >&2; exit 1; }
cmp -s "$lock_file" "$tmp_root/bootstrap-resolved.lock"
pass 'bootstrap rejects changed pre-existing Go against old resolved digests'

cp "$tmp_root/good-go" "$go_root/bin/go"
write_lock "$lock_file"
cp "$lock_file" "$tmp_root/bootstrap-resolved-ndk.lock"
printf 'tampered NDK bytes\n' >>"$ndk_root/NOTICE"
status=0
run_bootstrap_fixture "$tmp_root/bootstrap-resolved-ndk.out" || status=$?
[[ "$status" -eq 2 ]] || { cat "$tmp_root/bootstrap-resolved-ndk.out" >&2; exit 1; }
grep -F 'pre-existing NDK install digest does not match resolved lock' "$tmp_root/bootstrap-resolved-ndk.out" >/dev/null
cmp -s "$lock_file" "$tmp_root/bootstrap-resolved-ndk.lock"
pass 'bootstrap rejects changed pre-existing NDK against old resolved digests'

printf '1..%d\n' "$pass_count"
