#!/usr/bin/env bash
set -euo pipefail

readonly TSNET_GO_ARCHIVE='go1.26.5.linux-amd64.tar.gz'
readonly TSNET_GO_ARCHIVE_SHA256='5c2c3b16caefa1d968a94c1daca04a7ca301a496d9b086e17ad77bb81393f053'
readonly TSNET_GO_VERSION='1.26.5'
readonly TSNET_GOMOBILE_MODULE='golang.org/x/mobile'
readonly TSNET_GOMOBILE_VERSION='v0.0.0-20240806205939-81131f6468ab'
readonly TSNET_GOMOBILE_SUM='h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k='
readonly TSNET_NDK_ARCHIVE='android-ndk-r27c-linux.zip'
readonly TSNET_NDK_ARCHIVE_SHA256='59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc'
readonly TSNET_NDK_REVISION='27.2.12479018'
readonly TSNET_JDK_VERSION='17.0.20+8'

fail() {
  printf 'TSNET_TOOLCHAIN_BLOCKED: %s\n' "$1" >&2
  exit 2
}

sha256_file() {
  sha256sum -- "$1" | awk '{print $1}'
}

directory_manifest_sha256() {
  python3 - "$1" <<'PY'
import hashlib
import os
import pathlib
import stat
import sys

root = pathlib.Path(sys.argv[1])
if not root.is_dir() or root.is_symlink():
    raise SystemExit(1)

rows = []
for path in sorted(root.rglob("*"), key=lambda p: os.fsencode(p.relative_to(root).as_posix())):
    relative = path.relative_to(root).as_posix()
    info = path.lstat()
    mode = stat.S_IMODE(info.st_mode)
    if path.is_symlink():
        kind = "l"
        payload = os.fsencode(os.readlink(path))
    elif path.is_file():
        kind = "f"
        payload = path.read_bytes()
    elif path.is_dir():
        kind = "d"
        payload = b""
    else:
        raise SystemExit(f"unsupported entry in manifest: {relative}")
    rows.append(
        f"{kind}\t{mode:04o}\t{len(payload)}\t{hashlib.sha256(payload).hexdigest()}\t{relative}\n".encode()
    )

print(hashlib.sha256(b"".join(rows)).hexdigest())
PY
}

resolve_toolchain_owner() {
  local checkout_root="$1"
  local common_git_dir=''
  local common_real=''
  local checkout_git_real=''
  local linked_git_dir=''
  local linked_git_real=''

  common_git_dir="$(env -u GIT_DIR -u GIT_COMMON_DIR -u GIT_WORK_TREE -u GIT_CEILING_DIRECTORIES \
    git -C "$checkout_root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" \
    || fail "cannot resolve the checkout Git common directory: $checkout_root"
  common_real="$(realpath -e "$common_git_dir")" \
    || fail "cannot canonicalize the Git common directory: $common_git_dir"
  [[ "$(basename "$common_real")" == '.git' ]] \
    || fail "Git common directory is not a canonical project .git directory: $common_real"

  if [[ -d "$checkout_root/.git" && ! -L "$checkout_root/.git" ]]; then
    checkout_git_real="$(realpath -e "$checkout_root/.git")" \
      || fail 'cannot canonicalize checkout .git directory'
    [[ "$checkout_git_real" == "$common_real" ]] \
      || fail 'standalone checkout .git does not match its Git common directory'
  elif [[ -f "$checkout_root/.git" && ! -L "$checkout_root/.git" ]]; then
    linked_git_dir="$(env -u GIT_DIR -u GIT_COMMON_DIR -u GIT_WORK_TREE -u GIT_CEILING_DIRECTORIES \
      git -C "$checkout_root" rev-parse --path-format=absolute --git-dir 2>/dev/null)" \
      || fail 'cannot resolve linked-worktree Git directory'
    linked_git_real="$(realpath -e "$linked_git_dir")" \
      || fail "cannot canonicalize linked-worktree Git directory: $linked_git_dir"
    case "$linked_git_real" in
      "$common_real"/worktrees/*) ;;
      *) fail "linked-worktree Git directory is outside the common worktrees directory: $linked_git_real" ;;
    esac
  else
    fail "checkout .git boundary is missing or is a symlink: $checkout_root/.git"
  fi

  dirname "$common_real"
}

verify_tsnet_toolchain() (
repo_root="$1"
expected_go_archive_sha="$2"
expected_ndk_archive_sha="$3"
shift 3

lock_file=''
emit_env=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --lock)
      [[ $# -ge 2 ]] || fail '--lock requires a path'
      lock_file="$2"
      shift 2
      ;;
    --emit-env)
      [[ $# -ge 2 ]] || fail '--emit-env requires a path'
      emit_env="$2"
      shift 2
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

[[ -n "$lock_file" ]] || fail '--lock is required'
[[ -n "$emit_env" ]] || fail '--emit-env is required'
[[ -f "$lock_file" && ! -L "$lock_file" ]] || fail "lock file is missing or is a symlink: $lock_file"
command -v python3 >/dev/null 2>&1 || fail 'python3 is required to read the lock offline'
command -v sha256sum >/dev/null 2>&1 || fail 'sha256sum is required to verify toolchain bytes offline'

values_file="$(mktemp)"
env_candidate=''
cleanup() {
  rm -f -- "$values_file"
  [[ -z "$env_candidate" ]] || rm -f -- "$env_candidate"
}
trap cleanup EXIT

if ! python3 - "$lock_file" "$expected_go_archive_sha" "$expected_ndk_archive_sha" >"$values_file" <<'PY'
import json
import pathlib
import sys

expected_go_archive_sha = sys.argv[2]
expected_ndk_archive_sha = sys.argv[3]

def exact_keys(value, expected, path):
    if not isinstance(value, dict):
        raise ValueError(f"{path} must be an object")
    missing = sorted(set(expected) - set(value))
    unknown = sorted(set(value) - set(expected))
    if missing:
        raise ValueError(f"{path} missing keys: {', '.join(missing)}")
    if unknown:
        raise ValueError(f"{path} unknown keys: {', '.join(unknown)}")

try:
    data = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
    exact_keys(data, ["schemaVersion", "source", "toolchain", "abi", "build", "dependencies", "outputs", "resolvedDigests"], "lock")
    exact_keys(data["toolchain"], ["go", "gomobile", "androidNdk", "jdk", "androidSdk", "gradle"], "toolchain")
    exact_keys(data["toolchain"]["go"], ["archive", "sha256"], "toolchain.go")
    exact_keys(data["toolchain"]["gomobile"], ["module", "tools"], "toolchain.gomobile")
    exact_keys(data["toolchain"]["androidNdk"], ["archive", "revision", "sha256", "provisionalDigest"], "toolchain.androidNk")
    exact_keys(data["toolchain"]["jdk"], ["distribution", "version"], "toolchain.jdk")
    exact_keys(data["toolchain"]["androidSdk"], ["gomobileApi", "compileSdk"], "toolchain.androidSdk")
    exact_keys(data["toolchain"]["gradle"], ["agp", "gradle", "kotlin"], "toolchain.gradle")
    exact_keys(data["dependencies"]["golang.org/x/mobile"], ["version", "sum"], "dependencies.golang.org/x/mobile")
    resolved = data["resolvedDigests"]
    exact_keys(resolved, ["go", "gomobile", "androidNdk", "androidSdk", "jdk"], "resolvedDigests")
    exact_keys(resolved["go"], ["archiveSha256", "binarySha256", "directoryManifestSha256"], "resolvedDigests.go")
    exact_keys(resolved["gomobile"], ["moduleSum", "gomobileBinarySha256", "gobindBinarySha256", "directoryManifestSha256"], "resolvedDigests.gomobile")
    exact_keys(resolved["androidNdk"], ["archiveSha256", "directoryManifestSha256"], "resolvedDigests.androidNdk")
    exact_keys(resolved["androidSdk"], ["api34DirectoryManifestSha256", "api35DirectoryManifestSha256"], "resolvedDigests.androidSdk")
    exact_keys(resolved["jdk"], ["javaBinarySha256", "directoryManifestSha256"], "resolvedDigests.jdk")

    mobile = data["dependencies"]["golang.org/x/mobile"]
    expected = {
        "schemaVersion": (data["schemaVersion"], 1),
        "toolchain.go.archive": (data["toolchain"]["go"]["archive"], "go1.26.5.linux-amd64.tar.gz"),
        "toolchain.go.sha256": (data["toolchain"]["go"]["sha256"], expected_go_archive_sha),
        "toolchain.gomobile.module": (data["toolchain"]["gomobile"]["module"], "golang.org/x/mobile"),
        "toolchain.gomobile.tools": (data["toolchain"]["gomobile"]["tools"], ["gomobile", "gobind"]),
        "toolchain.androidNdk.archive": (data["toolchain"]["androidNdk"]["archive"], "android-ndk-r27c-linux.zip"),
        "toolchain.androidNdk.revision": (data["toolchain"]["androidNdk"]["revision"], "27.2.12479018"),
        "toolchain.androidNdk.sha256": (data["toolchain"]["androidNdk"]["sha256"], expected_ndk_archive_sha),
        "toolchain.androidNdk.provisionalDigest": (data["toolchain"]["androidNdk"]["provisionalDigest"], False),
        "toolchain.jdk.distribution": (data["toolchain"]["jdk"]["distribution"], "Temurin"),
        "toolchain.jdk.version": (data["toolchain"]["jdk"]["version"], "17.0.20+8"),
        "toolchain.androidSdk.gomobileApi": (data["toolchain"]["androidSdk"]["gomobileApi"], 34),
        "toolchain.androidSdk.compileSdk": (data["toolchain"]["androidSdk"]["compileSdk"], 35),
        "toolchain.gradle": (data["toolchain"]["gradle"], {"agp": "8.9.2", "gradle": "8.12", "kotlin": "2.1.20"}),
        "dependencies.golang.org/x/mobile.version": (mobile["version"], "v0.0.0-20240806205939-81131f6468ab"),
        "dependencies.golang.org/x/mobile.sum": (mobile["sum"], "h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k="),
        "resolvedDigests.go.archiveSha256": (resolved["go"]["archiveSha256"], expected_go_archive_sha),
        "resolvedDigests.androidNdk.archiveSha256": (resolved["androidNdk"]["archiveSha256"], expected_ndk_archive_sha),
    }
    for name, (actual, wanted) in expected.items():
        if actual != wanted:
            raise ValueError(f"{name} does not match the canonical value")

    values = [
        data["toolchain"]["go"]["sha256"],
        data["toolchain"]["androidNdk"]["sha256"],
        mobile["version"], mobile["sum"],
        resolved["go"]["archiveSha256"], resolved["go"]["binarySha256"], resolved["go"]["directoryManifestSha256"],
        resolved["gomobile"]["moduleSum"], resolved["gomobile"]["gomobileBinarySha256"], resolved["gomobile"]["gobindBinarySha256"], resolved["gomobile"]["directoryManifestSha256"],
        resolved["androidNdk"]["archiveSha256"], resolved["androidNdk"]["directoryManifestSha256"],
        resolved["androidSdk"]["api34DirectoryManifestSha256"], resolved["androidSdk"]["api35DirectoryManifestSha256"],
        resolved["jdk"]["javaBinarySha256"], resolved["jdk"]["directoryManifestSha256"],
    ]
    if any(not isinstance(value, str) or not value for value in values):
        raise ValueError("digest and module values must be non-empty strings")
    print("\n".join(values))
except (OSError, UnicodeError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
    print(f"invalid lock: {error}", file=sys.stderr)
    raise SystemExit(1)
PY
then
  fail 'lock schema or canonical toolchain values are invalid'
fi

mapfile -t values <"$values_file"
[[ "${#values[@]}" -eq 17 ]] || fail 'lock parser returned incomplete resolved digests'
go_archive_lock_sha="${values[0]}"
ndk_archive_lock_sha="${values[1]}"
mobile_version="${values[2]}"
mobile_sum="${values[3]}"

command -v git >/dev/null 2>&1 || fail 'git is required to resolve the shared toolchain root'
command -v realpath >/dev/null 2>&1 || fail 'realpath is required to resolve the shared toolchain root'
toolchain_owner="$(resolve_toolchain_owner "$repo_root")"
downloads="$toolchain_owner/.toolchains/downloads"
go_root="$toolchain_owner/.toolchains/go-$TSNET_GO_VERSION"
gomobile_root="$toolchain_owner/.toolchains/gomobile-$TSNET_GOMOBILE_VERSION"
sdk_root="$toolchain_owner/.toolchains/android-sdk"
ndk_root="$sdk_root/ndk/$TSNET_NDK_REVISION"
jdk_root="$toolchain_owner/.toolchains/jdk-$TSNET_JDK_VERSION"
module_cache="$toolchain_owner/.toolchains/go-module-cache"
go_cache="$toolchain_owner/.toolchains/go-build-cache"
go_archive="$downloads/$TSNET_GO_ARCHIVE"
ndk_archive="$downloads/$TSNET_NDK_ARCHIVE"
go_binary="$go_root/bin/go"
gomobile_binary="$gomobile_root/bin/gomobile"
gobind_binary="$gomobile_root/bin/gobind"
java_binary="$jdk_root/bin/java"
api34_root="$sdk_root/platforms/android-34"
api35_root="$sdk_root/platforms/android-35"

for root in "$go_root" "$gomobile_root" "$sdk_root" "$ndk_root" "$jdk_root"; do
  [[ -d "$root" && ! -L "$root" ]] || {
    if [[ "$root" == "$ndk_root" ]]; then
      for properties in "$sdk_root"/ndk/*/source.properties; do
        [[ -f "$properties" ]] || continue
        if grep -Eq '^[[:space:]]*Pkg\.Revision[[:space:]]*=[[:space:]]*27\.2\.12479018[[:space:]]*$' "$properties"; then
          fail "NDK 27.2.12479018 exists outside the correctly named NDK directory: $(dirname "$properties")"
        fi
      done
    fi
    fail "locked toolchain directory is missing or is a symlink: $root"
  }
done
for archive in "$go_archive" "$ndk_archive"; do
  [[ -f "$archive" && ! -L "$archive" ]] || fail "locked archive is missing or is a symlink: $archive"
done
for executable in "$go_binary" "$gomobile_binary" "$gobind_binary" "$java_binary"; do
  if [[ ! -f "$executable" || ! -x "$executable" || -L "$executable" ]]; then
    if [[ "$executable" == "$go_binary" ]]; then
      fail "locked Go executable is missing, non-executable, or a symlink: $executable"
    fi
    fail "locked executable is missing, non-executable, or a symlink: $executable"
  fi
done

actual_go_archive_sha="$(sha256_file "$go_archive")" || fail "cannot hash $TSNET_GO_ARCHIVE"
[[ "$actual_go_archive_sha" == "$go_archive_lock_sha" && "$actual_go_archive_sha" == "${values[4]}" ]] || fail "$TSNET_GO_ARCHIVE digest does not match lock"
actual_ndk_archive_sha="$(sha256_file "$ndk_archive")" || fail "cannot hash $TSNET_NDK_ARCHIVE"
[[ "$actual_ndk_archive_sha" == "$ndk_archive_lock_sha" && "$actual_ndk_archive_sha" == "${values[11]}" ]] || fail "$TSNET_NDK_ARCHIVE digest does not match lock"

grep -Eq '^[[:space:]]*Pkg\.Revision[[:space:]]*=[[:space:]]*27\.2\.12479018[[:space:]]*$' "$ndk_root/source.properties" \
  || fail "NDK source.properties does not report $TSNET_NDK_REVISION"
[[ "$(basename "$ndk_root")" == "$TSNET_NDK_REVISION" ]] || fail 'NDK directory basename does not equal Pkg.Revision'

for platform_spec in "$api34_root:34" "$api35_root:35"; do
  platform="${platform_spec%:*}"
  api_level="${platform_spec##*:}"
  [[ -d "$platform" && ! -L "$platform" && -f "$platform/package.xml" && -f "$platform/source.properties" ]] \
    || fail "required Android platform package is missing or incomplete: $platform"
  grep -Eq "^[[:space:]]*AndroidVersion\.ApiLevel[[:space:]]*=[[:space:]]*$api_level[[:space:]]*$" "$platform/source.properties" \
    || fail "$(basename "$platform") does not report API level $api_level in source.properties"
done

checks=(
  "$(sha256_file "$go_binary")" "${values[5]}" 'Go binary'
  "$(directory_manifest_sha256 "$go_root")" "${values[6]}" 'Go directory manifest'
  "$(sha256_file "$gomobile_binary")" "${values[8]}" 'gomobile binary'
  "$(sha256_file "$gobind_binary")" "${values[9]}" 'gobind binary'
  "$(directory_manifest_sha256 "$gomobile_root")" "${values[10]}" 'gomobile directory manifest'
  "$(directory_manifest_sha256 "$ndk_root")" "${values[12]}" 'NDK directory manifest'
  "$(directory_manifest_sha256 "$api34_root")" "${values[13]}" 'Android API 34 directory manifest'
  "$(directory_manifest_sha256 "$api35_root")" "${values[14]}" 'Android API 35 directory manifest'
  "$(sha256_file "$java_binary")" "${values[15]}" 'Java binary'
  "$(directory_manifest_sha256 "$jdk_root")" "${values[16]}" 'JDK directory manifest'
)
for ((index = 0; index < ${#checks[@]}; index += 3)); do
  [[ "${checks[index]}" == "${checks[index + 1]}" ]] || fail "${checks[index + 2]} digest does not match resolved lock"
done

go_version_output="$("$go_binary" version 2>/dev/null)" || fail 'locked Go executable cannot report its version'
[[ "$go_version_output" == 'go version go1.26.5 linux/amd64' ]] || fail "locked Go must be stock go1.26.5 linux/amd64, got: $go_version_output"

for tool in "$gomobile_binary" "$gobind_binary"; do
  build_info="$("$go_binary" version -m "$tool" 2>/dev/null)" || fail "locked Go cannot inspect build metadata for $tool"
  printf '%s\n' "$build_info" | awk -v module="$TSNET_GOMOBILE_MODULE" -v version="$mobile_version" -v sum="$mobile_sum" \
    '$1 == "mod" && $2 == module && $3 == version && $4 == sum { found = 1 } END { exit !found }' \
    || fail "$(basename "$tool") build metadata does not contain the locked gomobile module revision and sum"
done
[[ "$mobile_version" == "$TSNET_GOMOBILE_VERSION" ]] || fail 'gomobile module revision does not match the canonical lock'
[[ "$mobile_sum" == "$TSNET_GOMOBILE_SUM" && "${values[7]}" == "$TSNET_GOMOBILE_SUM" ]] || fail 'gomobile module sum does not match the canonical lock'

java_version_output="$("$java_binary" -version 2>&1)" || fail 'locked Java executable cannot report its version'
if [[ "$java_version_output" != *'openjdk version "17.0.20"'* || "$java_version_output" != *'Temurin-17.0.20+8'* ]]; then
  fail 'locked JDK must be Temurin 17.0.20+8'
fi

for cache in "$module_cache" "$go_cache"; do
  [[ -d "$cache" && ! -L "$cache" ]] || fail "locked cache directory is missing or is a symlink: $cache"
done
emit_parent="$(dirname "$emit_env")"
[[ -d "$emit_parent" ]] || fail "emit-env parent directory does not exist: $emit_parent"
env_candidate="$(mktemp "$emit_parent/.tsnet-toolchain-env.XXXXXXXXXX")"
{
  printf 'export JAVA_HOME=%q\n' "$jdk_root"
  printf 'export ANDROID_HOME=%q\n' "$sdk_root"
  printf 'export ANDROID_NDK_HOME=%q\n' "$ndk_root"
  printf 'export GOROOT=%q\n' "$go_root"
  printf 'export GOMOBILE=%q\n' "$gomobile_binary"
  printf 'export GOBIND=%q\n' "$gobind_binary"
  printf 'export GOMODCACHE=%q\n' "$module_cache"
  printf 'export GOCACHE=%q\n' "$go_cache"
} >"$env_candidate"
chmod 0600 "$env_candidate"
mv -f -- "$env_candidate" "$emit_env"
env_candidate=''

printf 'TSNET_TOOLCHAIN_READY\n'
)

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
  verify_tsnet_toolchain "$repo_root" "$TSNET_GO_ARCHIVE_SHA256" "$TSNET_NDK_ARCHIVE_SHA256" "$@"
fi
