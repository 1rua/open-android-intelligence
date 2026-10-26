#!/usr/bin/env bash
set -euo pipefail
export GIT_NO_LAZY_FETCH=1

readonly GO_ARCHIVE='go1.26.5.linux-amd64.tar.gz'
readonly GO_ARCHIVE_SHA256='5c2c3b16caefa1d968a94c1daca04a7ca301a496d9b086e17ad77bb81393f053'
readonly GO_URL='https://go.dev/dl/go1.26.5.linux-amd64.tar.gz'
readonly MOBILE_MODULE='golang.org/x/mobile'
readonly MOBILE_VERSION='v0.0.0-20240806205939-81131f6468ab'
readonly MOBILE_SUM='h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k='
readonly NDK_ARCHIVE='android-ndk-r27c-linux.zip'
readonly NDK_ARCHIVE_SHA256='59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc'
readonly NDK_URL='https://dl.google.com/android/repository/android-ndk-r27c-linux.zip'
readonly NDK_REVISION='27.2.12479018'
readonly SOURCE_COMMIT='36550d57f4a4055246ef7412f4e650a012a465f1'

fail() {
  printf 'TSNET_TOOLCHAIN_BLOCKED: %s\n' "$1" >&2
  exit 2
}

bootstrap_tsnet_toolchain() (
repo_root="$1"
expected_go_archive_sha="$2"
expected_ndk_archive_sha="$3"

verifier="$repo_root/apps/android/tailnet-core/tools/verify-tsnet-toolchain.sh"
[[ -x "$verifier" && ! -L "$verifier" ]] || fail "offline verifier is missing or is not executable: $verifier"
source "$verifier"
toolchain_owner="$(resolve_toolchain_owner "$repo_root")"
toolchains="$toolchain_owner/.toolchains"
downloads="$toolchains/downloads"
go_root="$toolchains/go-1.26.5"
mobile_root="$toolchains/gomobile-$MOBILE_VERSION"
sdk_root="$toolchains/android-sdk"
ndk_root="$sdk_root/ndk/$NDK_REVISION"
jdk_root="$toolchains/jdk-17.0.20+8"
module_cache="$toolchains/go-module-cache"
go_cache="$toolchains/go-build-cache"
go_path="$toolchains/go-workspace"
source_checkout="$repo_root/third_party/tailscale"
lock_file="$repo_root/apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json"

for utility in curl sha256sum tar unzip python3 git mktemp; do
  command -v "$utility" >/dev/null 2>&1 || fail "$utility is required by the controller-only bootstrap"
done
[[ -f "$lock_file" && ! -L "$lock_file" ]] || fail "lock file is missing or is a symlink: $lock_file"

lock_state_file="$(mktemp)"
trap 'rm -f -- "$lock_state_file"' EXIT
if ! python3 - "$lock_file" "$expected_go_archive_sha" "$expected_ndk_archive_sha" >"$lock_state_file" <<'PY'
import json, pathlib, sys
path, go_sha, ndk_sha = sys.argv[1:]
try:
    data = json.loads(pathlib.Path(path).read_text())
    assert data["toolchain"]["go"] == {"archive": "go1.26.5.linux-amd64.tar.gz", "sha256": go_sha}
    assert data["toolchain"]["androidNdk"]["archive"] == "android-ndk-r27c-linux.zip"
    assert data["toolchain"]["androidNdk"]["revision"] == "27.2.12479018"
    assert data["toolchain"]["androidNdk"]["sha256"] == ndk_sha
    assert data["toolchain"]["jdk"] == {"distribution": "Temurin", "version": "17.0.20+8"}
    assert data["toolchain"]["androidSdk"] == {"gomobileApi": 34, "compileSdk": 35}
    assert data["dependencies"]["golang.org/x/mobile"] == {"version": "v0.0.0-20240806205939-81131f6468ab", "sum": "h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k="}
    resolved = data.get("resolvedDigests")
    provisional = data["toolchain"]["androidNdk"]["provisionalDigest"]
    if resolved is None:
        assert provisional is True
        print("unresolved")
    else:
        assert provisional is False
        assert resolved["go"]["archiveSha256"] == go_sha
        assert resolved["androidNdk"]["archiveSha256"] == ndk_sha
        assert resolved["gomobile"]["moduleSum"] == "h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k="
        print("resolved")
        print(resolved["go"]["binarySha256"])
        print(resolved["go"]["directoryManifestSha256"])
        print(resolved["gomobile"]["gomobileBinarySha256"])
        print(resolved["gomobile"]["gobindBinarySha256"])
        print(resolved["gomobile"]["directoryManifestSha256"])
        print(resolved["androidNdk"]["directoryManifestSha256"])
        print(resolved["androidSdk"]["api34DirectoryManifestSha256"])
        print(resolved["androidSdk"]["api35DirectoryManifestSha256"])
        print(resolved["jdk"]["javaBinarySha256"])
        print(resolved["jdk"]["directoryManifestSha256"])
except (AssertionError, KeyError, OSError, json.JSONDecodeError, TypeError):
    raise SystemExit(1)
PY
then
  fail 'lock does not contain the immutable controller toolchain values'
fi
mapfile -t lock_state <"$lock_state_file"
[[ "${#lock_state[@]}" -eq 1 || "${#lock_state[@]}" -eq 11 ]] \
  || fail 'lock resolved digest state is incomplete'
resolution_state="${lock_state[0]}"

mkdir -p -- "$downloads" "$sdk_root/ndk" "$module_cache" "$go_cache" "$go_path"

for platform_spec in android-34:34 android-35:35; do
  platform="${platform_spec%%:*}"
  api_level="${platform_spec##*:}"
  platform_root="$sdk_root/platforms/$platform"
  [[ -d "$platform_root" && ! -L "$platform_root" && -f "$platform_root/package.xml" && -f "$platform_root/source.properties" ]] \
    || fail "controller-provided Android SDK package is missing or incomplete: $platform_root"
  grep -Eq "^[[:space:]]*AndroidVersion\.ApiLevel[[:space:]]*=[[:space:]]*$api_level[[:space:]]*$" "$platform_root/source.properties" \
    || fail "$platform does not report API level $api_level in source.properties"
done
[[ -d "$jdk_root" && ! -L "$jdk_root" && -x "$jdk_root/bin/java" && ! -L "$jdk_root/bin/java" ]] \
  || fail "controller-provided Temurin JDK is missing or unsafe: $jdk_root"

download_verified() {
  local name="$1"
  local url="$2"
  local expected_sha="$3"
  local destination="$downloads/$name"
  local actual_sha=''
  local rejected=''

  if [[ -e "$destination" ]]; then
    [[ -f "$destination" && ! -L "$destination" ]] || fail "locked download path is not a regular file: $destination"
    actual_sha="$(sha256sum -- "$destination" | awk '{print $1}')"
    [[ "$actual_sha" == "$expected_sha" ]] \
      || fail "$name supply-chain digest mismatch: expected $expected_sha, got $actual_sha"
    return
  fi

  local candidate
  candidate="$(mktemp "$downloads/.$name.download.XXXXXXXXXX")"
  if ! curl --fail --location --proto '=https' --tlsv1.2 --output "$candidate" "$url"; then
    rm -f -- "$candidate"
    fail "download unavailable for locked asset $name from $url"
  fi
  actual_sha="$(sha256sum -- "$candidate" | awk '{print $1}')"
  if [[ "$actual_sha" != "$expected_sha" ]]; then
    rejected="$downloads/$name.rejected-$actual_sha"
    mv -f -- "$candidate" "$rejected"
    fail "$name supply-chain digest mismatch: expected $expected_sha, got $actual_sha; bytes retained at $rejected"
  fi
  chmod 0444 "$candidate"
  mv -f -- "$candidate" "$destination"
}

download_verified "$GO_ARCHIVE" "$GO_URL" "$expected_go_archive_sha"
download_verified "$NDK_ARCHIVE" "$NDK_URL" "$expected_ndk_archive_sha"

if [[ "$resolution_state" == 'unresolved' ]]; then
  [[ ! -e "$go_root" ]] || fail "unverified pre-existing Go install requires controller review: $go_root"
  [[ ! -e "$ndk_root" ]] || fail "unverified pre-existing NDK install requires controller review: $ndk_root"
  [[ ! -e "$mobile_root" ]] || fail "unverified pre-existing gomobile install requires controller review: $mobile_root"
else
  [[ -d "$go_root" && ! -L "$go_root" && -x "$go_root/bin/go" && ! -L "$go_root/bin/go" ]] \
    || fail "resolved pre-existing Go install is missing or unsafe: $go_root"
  [[ "$(sha256sum -- "$go_root/bin/go" | awk '{print $1}')" == "${lock_state[1]}" \
    && "$(directory_manifest_sha256 "$go_root")" == "${lock_state[2]}" ]] \
    || fail 'pre-existing Go install digest does not match resolved lock'
  [[ -d "$mobile_root" && ! -L "$mobile_root" ]] \
    || fail "resolved pre-existing gomobile install is missing or unsafe: $mobile_root"
  [[ "$(sha256sum -- "$mobile_root/bin/gomobile" | awk '{print $1}')" == "${lock_state[3]}" \
    && "$(sha256sum -- "$mobile_root/bin/gobind" | awk '{print $1}')" == "${lock_state[4]}" \
    && "$(directory_manifest_sha256 "$mobile_root")" == "${lock_state[5]}" ]] \
    || fail 'pre-existing gomobile install digest does not match resolved lock'
  [[ -d "$ndk_root" && ! -L "$ndk_root" \
    && "$(directory_manifest_sha256 "$ndk_root")" == "${lock_state[6]}" ]] \
    || fail 'pre-existing NDK install digest does not match resolved lock'
  [[ "$(directory_manifest_sha256 "$sdk_root/platforms/android-34")" == "${lock_state[7]}" \
    && "$(directory_manifest_sha256 "$sdk_root/platforms/android-35")" == "${lock_state[8]}" ]] \
    || fail 'controller-provided Android SDK digest does not match resolved lock'
  [[ "$(sha256sum -- "$jdk_root/bin/java" | awk '{print $1}')" == "${lock_state[9]}" \
    && "$(directory_manifest_sha256 "$jdk_root")" == "${lock_state[10]}" ]] \
    || fail 'controller-provided JDK digest does not match resolved lock'
fi

java_output="$("$jdk_root/bin/java" -version 2>&1)" || fail 'controller-provided JDK cannot report its version'
[[ "$java_output" == *'openjdk version "17.0.20"'* && "$java_output" == *'Temurin-17.0.20+8'* ]] \
  || fail 'controller-provided JDK must be Temurin 17.0.20+8'

install_go() {
  local version_output=''
  if [[ -e "$go_root" ]]; then
    return
  fi

  local extract_root
  extract_root="$(mktemp -d "$toolchains/.go-1.26.5.install.XXXXXXXXXX")"
  if ! tar -xzf "$downloads/$GO_ARCHIVE" -C "$extract_root"; then
    rm -rf -- "$extract_root"
    fail "$GO_ARCHIVE could not be extracted"
  fi
  [[ -d "$extract_root/go" && ! -L "$extract_root/go" && -x "$extract_root/go/bin/go" ]] || {
    rm -rf -- "$extract_root"
    fail "$GO_ARCHIVE did not contain the expected go/ tree"
  }
  version_output="$("$extract_root/go/bin/go" version 2>/dev/null)" || {
    rm -rf -- "$extract_root"
    fail 'downloaded stock Go cannot report its version'
  }
  [[ "$version_output" == 'go version go1.26.5 linux/amd64' ]] || {
    rm -rf -- "$extract_root"
    fail "downloaded Go archive produced unexpected version: $version_output"
  }
  mv -- "$extract_root/go" "$go_root"
  rmdir -- "$extract_root"
}

install_ndk() {
  local properties=''
  local extract_root=''
  local extracted=''
  if [[ -e "$ndk_root" ]]; then
    return
  fi

  for properties in "$sdk_root"/ndk/*/source.properties; do
    [[ -f "$properties" ]] || continue
    if grep -Eq '^[[:space:]]*Pkg\.Revision[[:space:]]*=[[:space:]]*27\.2\.12479018[[:space:]]*$' "$properties"; then
      printf 'rejecting misnamed NDK install as a fallback: %s\n' "$(dirname "$properties")" >&2
    fi
  done

  extract_root="$(mktemp -d "$toolchains/.ndk-$NDK_REVISION.install.XXXXXXXXXX")"
  if ! unzip -q "$downloads/$NDK_ARCHIVE" -d "$extract_root"; then
    rm -rf -- "$extract_root"
    fail "$NDK_ARCHIVE could not be extracted"
  fi
  extracted="$extract_root/android-ndk-r27c"
  [[ -d "$extracted" && ! -L "$extracted" ]] || {
    rm -rf -- "$extract_root"
    fail "$NDK_ARCHIVE did not contain android-ndk-r27c/"
  }
  grep -Eq '^[[:space:]]*Pkg\.Revision[[:space:]]*=[[:space:]]*27\.2\.12479018[[:space:]]*$' "$extracted/source.properties" || {
    rm -rf -- "$extract_root"
    fail "$NDK_ARCHIVE does not report revision $NDK_REVISION"
  }
  mv -- "$extracted" "$ndk_root"
  rmdir -- "$extract_root"
}

install_go
install_ndk
go_binary="$go_root/bin/go"

go_env=(
  env -i
  "PATH=$go_root/bin:/usr/bin:/bin"
  "GOROOT=$go_root"
  "GOPATH=$go_path"
  "GOBIN=$mobile_root.candidate/bin"
  "GOMODCACHE=$module_cache"
  "GOCACHE=$go_cache"
  'GOPROXY=https://proxy.golang.org'
  'GOSUMDB=sum.golang.org'
  'GONOSUMDB='
  'GONOPROXY='
  'GOPRIVATE='
  'GOTOOLCHAIN=local'
  'GOWORK=off'
)

module_json="$("${go_env[@]}" "$go_binary" mod download -json "$MOBILE_MODULE@$MOBILE_VERSION")" \
  || fail "module download unavailable for $MOBILE_MODULE@$MOBILE_VERSION"
resolved_sum="$(printf '%s\n' "$module_json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("Sum", ""))')" \
  || fail 'gomobile module download metadata is invalid'
[[ "$resolved_sum" == "$MOBILE_SUM" ]] \
  || fail "$MOBILE_MODULE@$MOBILE_VERSION supply-chain module sum mismatch: expected $MOBILE_SUM, got $resolved_sum"

if [[ ! -d "$mobile_root" ]]; then
  mobile_candidate="$mobile_root.candidate"
  [[ ! -e "$mobile_candidate" ]] || fail "stale gomobile install candidate requires controller review: $mobile_candidate"
  mkdir -p "$mobile_candidate/bin" "$mobile_candidate/build-info"
  if ! "${go_env[@]}" "$go_binary" install "$MOBILE_MODULE/cmd/gomobile@$MOBILE_VERSION" "$MOBILE_MODULE/cmd/gobind@$MOBILE_VERSION"; then
    fail "gomobile/gobind installation failed for $MOBILE_MODULE@$MOBILE_VERSION"
  fi
  for tool in gomobile gobind; do
    "$go_binary" version -m "$mobile_candidate/bin/$tool" >"$mobile_candidate/build-info/$tool.txt" \
      || fail "cannot record build metadata for $tool"
    awk -v module="$MOBILE_MODULE" -v version="$MOBILE_VERSION" -v sum="$MOBILE_SUM" \
      '$1 == "mod" && $2 == module && $3 == version && $4 == sum { found = 1 } END { exit !found }' \
      "$mobile_candidate/build-info/$tool.txt" \
      || fail "$tool build metadata does not contain the locked module sum"
  done
  mv -- "$mobile_candidate" "$mobile_root"
fi

[[ -d "$source_checkout" ]] || fail "pinned Tailscale checkout is missing: $source_checkout"
module_workspace="$(mktemp -d "$toolchains/.tailscale-module.XXXXXXXXXX")"
trap 'rm -rf -- "$module_workspace"' EXIT
git -C "$source_checkout" show "$SOURCE_COMMIT:go.mod" >"$module_workspace/go.mod" \
  || fail 'pinned Tailscale go.mod is unavailable; hydrate the locked source first'
git -C "$source_checkout" show "$SOURCE_COMMIT:go.sum" >"$module_workspace/go.sum" \
  || fail 'pinned Tailscale go.sum is unavailable; hydrate the locked source first'
if ! (cd "$module_workspace" && "${go_env[@]}" "$go_binary" mod download all); then
  fail 'pinned Tailscale module graph could not be populated through proxy.golang.org and sum.golang.org'
fi
rm -rf -- "$module_workspace"
trap - EXIT

candidate_lock="$(mktemp "$(dirname "$lock_file")/.tsnet-aar.lock.candidate.XXXXXXXXXX")"
python3 - "$lock_file" "$candidate_lock" "$expected_go_archive_sha" "$expected_ndk_archive_sha" \
  "$(sha256sum -- "$go_binary" | awk '{print $1}')" "$(directory_manifest_sha256 "$go_root")" \
  "$(sha256sum -- "$mobile_root/bin/gomobile" | awk '{print $1}')" \
  "$(sha256sum -- "$mobile_root/bin/gobind" | awk '{print $1}')" "$(directory_manifest_sha256 "$mobile_root")" \
  "$(directory_manifest_sha256 "$ndk_root")" \
  "$(directory_manifest_sha256 "$sdk_root/platforms/android-34")" \
  "$(directory_manifest_sha256 "$sdk_root/platforms/android-35")" \
  "$(sha256sum -- "$jdk_root/bin/java" | awk '{print $1}')" "$(directory_manifest_sha256 "$jdk_root")" <<'PY'
import json, pathlib, sys
(source, target, go_archive, ndk_archive, go_binary, go_dir, gomobile_binary, gobind_binary, gomobile_dir,
 ndk_dir, api34_dir, api35_dir, java_binary, jdk_dir) = sys.argv[1:]
data = json.loads(pathlib.Path(source).read_text())
data["toolchain"]["androidNdk"]["provisionalDigest"] = False
data["resolvedDigests"] = {
    "go": {"archiveSha256": go_archive, "binarySha256": go_binary, "directoryManifestSha256": go_dir},
    "gomobile": {"moduleSum": "h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k=", "gomobileBinarySha256": gomobile_binary, "gobindBinarySha256": gobind_binary, "directoryManifestSha256": gomobile_dir},
    "androidNdk": {"archiveSha256": ndk_archive, "directoryManifestSha256": ndk_dir},
    "androidSdk": {"api34DirectoryManifestSha256": api34_dir, "api35DirectoryManifestSha256": api35_dir},
    "jdk": {"javaBinarySha256": java_binary, "directoryManifestSha256": jdk_dir},
}
pathlib.Path(target).write_text(json.dumps(data, indent=2) + "\n")
PY

candidate_env="$(mktemp)"
if ! verify_tsnet_toolchain "$repo_root" "$expected_go_archive_sha" "$expected_ndk_archive_sha" \
  --lock "$candidate_lock" --emit-env "$candidate_env"; then
  rm -f -- "$candidate_lock" "$candidate_env"
  fail 'offline verifier rejected the independently recomputed candidate lock'
fi
rm -f -- "$candidate_env"
chmod --reference="$lock_file" "$candidate_lock"
mv -f -- "$candidate_lock" "$lock_file"
printf 'TSNET_TOOLCHAIN_BOOTSTRAPPED\n'
)

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  [[ "${1:-}" == '--download' && $# -eq 1 ]] \
    || fail 'controller-only bootstrap requires exactly --download'
  repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
  bootstrap_tsnet_toolchain "$repo_root" "$GO_ARCHIVE_SHA256" "$NDK_ARCHIVE_SHA256"
fi
