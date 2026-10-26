#!/usr/bin/env bash
set -euo pipefail
export GIT_NO_LAZY_FETCH=1

readonly TSNET_CANONICAL_TAG_OBJECT='0ee734d3089846b27bc6ebcddd3d6ee5ec13e04d'
readonly TSNET_CANONICAL_COMMIT='36550d57f4a4055246ef7412f4e650a012a465f1'

fail() {
  printf 'TSNET_AAR_INPUTS_BLOCKED: %s\n' "$1" >&2
  exit 2
}

controller_fetch_prerequisite() {
  printf '%s\n' 'git -C third_party/tailscale fetch --filter=blob:none origin refs/tags/v1.98.10:refs/tags/v1.98.10' >&2
}

verify_tsnet_aar_inputs() (
expected_tag_object="$1"
expected_commit="$2"
shift 2

source_dir=''
lock_file=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)
      [[ $# -ge 2 ]] || fail '--source requires a path'
      source_dir="$2"
      shift 2
      ;;
    --lock)
      [[ $# -ge 2 ]] || fail '--lock requires a path'
      lock_file="$2"
      shift 2
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

[[ -n "$source_dir" ]] || fail '--source is required'
[[ -n "$lock_file" ]] || fail '--lock is required'
[[ -f "$lock_file" ]] || fail "lock file is missing: $lock_file"
[[ -d "$source_dir" ]] || fail "source checkout is missing: $source_dir"
command -v python3 >/dev/null 2>&1 || fail 'python3 is required to validate the lock'
command -v git >/dev/null 2>&1 || fail 'git is required to verify the source checkout'
command -v realpath >/dev/null 2>&1 || fail 'realpath is required to verify the worktree boundary'

lock_values_file="$(mktemp)"
trap 'rm -f "$lock_values_file"' EXIT
if ! python3 - "$lock_file" "$expected_tag_object" "$expected_commit" >"$lock_values_file" <<'PY'
import json
import pathlib
import re
import sys

lock_path = pathlib.Path(sys.argv[1])
expected_tag_object = sys.argv[2]
expected_commit = sys.argv[3]

def reject(message):
    raise ValueError(message)

def exact_keys(value, expected, path):
    if not isinstance(value, dict):
        reject(f"{path} must be an object")
    actual = set(value)
    expected_set = set(expected)
    missing = sorted(expected_set - actual)
    unknown = sorted(actual - expected_set)
    if missing:
        reject(f"{path} missing keys: {', '.join(missing)}")
    if unknown:
        reject(f"{path} unknown keys: {', '.join(unknown)}")

try:
    data = json.loads(lock_path.read_text(encoding="utf-8"))
    exact_keys(data, ["schemaVersion", "source", "toolchain", "abi", "build", "dependencies", "outputs"], "lock")
    exact_keys(data["source"], ["upstreamUrl", "release", "tagObject", "commit", "sourceFiles"], "source")
    exact_keys(data["source"]["sourceFiles"], ["version", "module"], "source.sourceFiles")
    exact_keys(data["source"]["sourceFiles"]["version"], ["path", "value"], "source.sourceFiles.version")
    exact_keys(data["source"]["sourceFiles"]["module"], ["path", "module", "goDirective"], "source.sourceFiles.module")
    exact_keys(data["toolchain"], ["go", "gomobile", "androidNdk", "jdk", "androidSdk", "gradle"], "toolchain")
    exact_keys(data["toolchain"]["go"], ["archive", "sha256"], "toolchain.go")
    exact_keys(data["toolchain"]["gomobile"], ["module", "tools"], "toolchain.gomobile")
    exact_keys(data["toolchain"]["androidNdk"], ["archive", "revision", "sha256", "provisionalDigest"], "toolchain.androidNdk")
    exact_keys(data["toolchain"]["jdk"], ["distribution", "version"], "toolchain.jdk")
    exact_keys(data["toolchain"]["androidSdk"], ["gomobileApi", "compileSdk"], "toolchain.androidSdk")
    exact_keys(data["toolchain"]["gradle"], ["agp", "gradle", "kotlin"], "toolchain.gradle")
    exact_keys(data["abi"], ["gomobileTargets", "aarAbis"], "abi")
    exact_keys(data["build"], ["argumentVector", "minimumElfLoadAlignment", "maximumAarBytes", "wireFrame"], "build")
    exact_keys(data["build"]["wireFrame"], ["transport", "minimumBytes", "maximumBytes"], "build.wireFrame")
    exact_keys(data["dependencies"], ["golang.org/x/mobile", "github.com/coder/websocket"], "dependencies")
    exact_keys(data["dependencies"]["golang.org/x/mobile"], ["version", "sum"], "dependencies.golang.org/x/mobile")
    exact_keys(data["dependencies"]["github.com/coder/websocket"], ["version", "sum"], "dependencies.github.com/coder/websocket")
    exact_keys(data["outputs"], ["rawAar", "aar", "aarSha256", "provenance", "sbom", "notices"], "outputs")

    source = data["source"]
    version_file = source["sourceFiles"]["version"]
    module_file = source["sourceFiles"]["module"]
    expected_constants = {
        "schemaVersion": (data["schemaVersion"], 1),
        "source.upstreamUrl": (source["upstreamUrl"], "https://github.com/tailscale/tailscale.git"),
        "source.release": (source["release"], "v1.98.10"),
        "source.tagObject": (source["tagObject"], expected_tag_object),
        "source.commit": (source["commit"], expected_commit),
        "source.sourceFiles.version.path": (version_file["path"], "VERSION.txt"),
        "source.sourceFiles.version.value": (version_file["value"], "1.98.10"),
        "source.sourceFiles.module.path": (module_file["path"], "go.mod"),
        "source.sourceFiles.module.module": (module_file["module"], "tailscale.com"),
        "source.sourceFiles.module.goDirective": (module_file["goDirective"], "1.26.5"),
        "toolchain": (data["toolchain"], {
            "go": {"archive": "go1.26.5.linux-amd64.tar.gz", "sha256": "5c2c3b16caefa1d968a94c1daca04a7ca301a496d9b086e17ad77bb81393f053"},
            "gomobile": {"module": "golang.org/x/mobile", "tools": ["gomobile", "gobind"]},
            "androidNdk": {"archive": "android-ndk-r27c-linux.zip", "revision": "27.2.12479018", "sha256": "59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc", "provisionalDigest": True},
            "jdk": {"distribution": "Temurin", "version": "17.0.20+8"},
            "androidSdk": {"gomobileApi": 34, "compileSdk": 35},
            "gradle": {"agp": "8.9.2", "gradle": "8.12", "kotlin": "2.1.20"},
        }),
        "abi": (data["abi"], {"gomobileTargets": ["android/arm64", "android/amd64"], "aarAbis": ["arm64-v8a", "x86_64"]}),
        "build": (data["build"], {
            "argumentVector": ["gomobile", "bind", "-target=android/arm64,android/amd64", "-androidapi=34", "-trimpath", "-tags=ts_omit_cachenetmap", "-ldflags=-buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384", "-o", "tsnet-android-1.98.10.raw.aar", "./tsnetbridge"],
            "minimumElfLoadAlignment": 16384,
            "maximumAarBytes": 83886080,
            "wireFrame": {"transport": "binary-wss-message", "minimumBytes": 1, "maximumBytes": 262144},
        }),
        "dependencies": (data["dependencies"], {
            "golang.org/x/mobile": {"version": "v0.0.0-20240806205939-81131f6468ab", "sum": "h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k="},
            "github.com/coder/websocket": {"version": "v1.8.12", "sum": None},
        }),
        "outputs": (data["outputs"], {
            "rawAar": "tsnet-android-1.98.10.raw.aar",
            "aar": "tsnet-android-1.98.10.aar",
            "aarSha256": "tsnet-android-1.98.10.aar.sha256",
            "provenance": "tsnet-android-1.98.10.provenance.json",
            "sbom": "tsnet-android-1.98.10.sbom.json",
            "notices": "THIRD_PARTY_NOTICES.md",
        }),
    }
    for path, (actual, expected) in expected_constants.items():
        if actual != expected:
            reject(f"{path} does not match the canonical value")

    for name in ("tagObject", "commit"):
        if not isinstance(source[name], str) or not re.fullmatch(r"[0-9a-f]{40}", source[name]):
            reject(f"source.{name} must be a lowercase 40-character object id")

    print(source["upstreamUrl"])
    print(source["release"])
    print(source["tagObject"])
    print(source["commit"])
    print(version_file["path"])
    print(version_file["value"])
    print(module_file["path"])
    print(module_file["module"])
    print(module_file["goDirective"])
except (OSError, UnicodeError, json.JSONDecodeError, ValueError, TypeError) as error:
    print(f"invalid lock: {error}", file=sys.stderr)
    sys.exit(1)
PY
then
  fail 'lock schema or canonical values are invalid'
fi

mapfile -t lock_values <"$lock_values_file"
[[ "${#lock_values[@]}" -eq 9 ]] || fail 'lock parser returned an incomplete source contract'
upstream_url="${lock_values[0]}"
release="${lock_values[1]}"
tag_object="${lock_values[2]}"
commit="${lock_values[3]}"
version_path="${lock_values[4]}"
version_value="${lock_values[5]}"
module_path="${lock_values[6]}"
module_value="${lock_values[7]}"
go_value="${lock_values[8]}"

git -C "$source_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail 'source is not a Git checkout'
source_real="$(realpath -e "$source_dir")" || fail 'source checkout path cannot be resolved'
worktree_root="$(git -C "$source_dir" rev-parse --show-toplevel 2>/dev/null)" || fail 'source worktree root cannot be resolved'
worktree_real="$(realpath -e "$worktree_root")" || fail 'source worktree root path cannot be resolved'
[[ "$source_real" == "$worktree_real" ]] || fail 'source must be the Git worktree root'
origin_url="$(git -C "$source_dir" remote get-url origin 2>/dev/null)" || fail 'source checkout has no origin remote'
normalized_origin="${origin_url%/}"
[[ "$normalized_origin" == "$upstream_url" ]] || fail "origin URL does not match the official upstream: $origin_url"
if ! checkout_status="$(git -C "$source_dir" status --porcelain=v1 --untracked-files=all 2>/dev/null)"; then
  fail 'source checkout status cannot be read'
fi
[[ -z "$checkout_status" ]] || fail 'source checkout is dirty'

if ! tag_type="$(git -C "$source_dir" cat-file -t "$tag_object" 2>/dev/null)"; then
  controller_fetch_prerequisite
  fail "pinned tag object is missing: $tag_object"
fi
[[ "$tag_type" == 'tag' ]] || fail "pinned tag object is not annotated: $tag_object"

tag_contents="$(git -C "$source_dir" cat-file tag "$tag_object" 2>/dev/null)" || fail 'annotated tag object cannot be read'
tag_name="$(printf '%s\n' "$tag_contents" | sed -n 's/^tag //p')"
[[ "$tag_name" == "$release" ]] || fail "annotated tag name does not match release: $tag_name"

if ! peeled_commit="$(git -C "$source_dir" rev-parse "$tag_object^{commit}" 2>/dev/null)"; then
  controller_fetch_prerequisite
  fail "pinned commit object is missing: $commit"
fi
[[ "$peeled_commit" == "$commit" ]] || fail "annotated tag peels to $peeled_commit instead of $commit"
if ! git -C "$source_dir" cat-file -e "$commit^{commit}" 2>/dev/null; then
  controller_fetch_prerequisite
  fail "pinned commit object is missing: $commit"
fi

version_blob="$(git -C "$source_dir" rev-parse "$commit:$version_path" 2>/dev/null)" || fail "$version_path is missing from pinned commit"
if ! version_blob_type="$(git -C "$source_dir" cat-file -t "$version_blob" 2>/dev/null)"; then
  controller_fetch_prerequisite
  fail "$version_path blob is missing from the local object database"
fi
[[ "$version_blob_type" == 'blob' ]] || fail "$version_path does not resolve to a blob"
actual_version="$(git -C "$source_dir" show "$commit:$version_path" 2>/dev/null)" || fail "$version_path blob cannot be read"
[[ "$actual_version" == "$version_value" ]] || fail "$version_path does not match lock"

module_blob="$(git -C "$source_dir" rev-parse "$commit:$module_path" 2>/dev/null)" || fail "$module_path is missing from pinned commit"
if ! module_blob_type="$(git -C "$source_dir" cat-file -t "$module_blob" 2>/dev/null)"; then
  controller_fetch_prerequisite
  fail "$module_path blob is missing from the local object database"
fi
[[ "$module_blob_type" == 'blob' ]] || fail "$module_path does not resolve to a blob"
go_mod="$(git -C "$source_dir" show "$commit:$module_path" 2>/dev/null)" || fail "$module_path blob cannot be read"
actual_module="$(printf '%s\n' "$go_mod" | sed -n 's/^module[[:space:]]\{1,\}//p')"
actual_go="$(printf '%s\n' "$go_mod" | sed -n 's/^go[[:space:]]\{1,\}//p')"
[[ "$actual_module" == "$module_value" ]] || fail "$module_path module does not match lock"
[[ "$actual_go" == "$go_value" ]] || fail "$module_path Go directive does not match lock"

printf 'TSNET_AAR_INPUTS_READY\n'
printf 'source_commit=%s\n' "$commit"
printf 'source_tag_object=%s\n' "$tag_object"
)

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  verify_tsnet_aar_inputs "$TSNET_CANONICAL_TAG_OBJECT" "$TSNET_CANONICAL_COMMIT" "$@"
fi
