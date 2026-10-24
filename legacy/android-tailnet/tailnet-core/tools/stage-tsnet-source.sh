#!/usr/bin/env bash
set -euo pipefail
export GIT_NO_LAZY_FETCH=1

fail() {
  printf 'TSNET_AAR_INPUTS_BLOCKED: %s\n' "$1" >&2
  exit 2
}

controller_fetch_prerequisite() {
  printf '%s\n' 'git -C third_party/tailscale fetch --filter=blob:none origin refs/tags/v1.98.10:refs/tags/v1.98.10' >&2
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/verify-tsnet-aar-inputs.sh"

stage_tsnet_source() (
expected_tag_object="$1"
expected_commit="$2"
shift 2

source_dir=''
output_dir=''
lock_file="$script_dir/../native/tsnetbridge/tsnet-aar.lock.json"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)
      [[ $# -ge 2 ]] || fail '--source requires a path'
      source_dir="$2"
      shift 2
      ;;
    --output)
      [[ $# -ge 2 ]] || fail '--output requires a path'
      output_dir="$2"
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
[[ -n "$output_dir" ]] || fail '--output is required'
[[ -d "$source_dir" ]] || fail "source checkout is missing: $source_dir"
[[ -d "$output_dir" && ! -L "$output_dir" ]] || fail 'output must be an existing directory, not a symlink'
[[ -z "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]] || fail 'output directory must be empty'
source_real="$(realpath -e "$source_dir")" || fail 'source checkout path cannot be resolved'
output_real="$(realpath -e "$output_dir")" || fail 'output directory path cannot be resolved'
worktree_root="$(git -C "$source_dir" rev-parse --show-toplevel 2>/dev/null)" || fail 'source worktree root cannot be resolved'
worktree_real="$(realpath -e "$worktree_root")" || fail 'source worktree root path cannot be resolved'
[[ "$source_real" == "$worktree_real" ]] || fail 'source must be the Git worktree root'
case "$output_real/" in
  "$worktree_real/"*) fail 'output directory must be outside the source worktree' ;;
esac

verify_tsnet_aar_inputs "$expected_tag_object" "$expected_commit" --source "$source_dir" --lock "$lock_file"

upstream_url='https://github.com/tailscale/tailscale.git'
release='v1.98.10'
tag_object="$expected_tag_object"
commit="$expected_commit"

archive_tar="$(mktemp)"
manifest_tmp="$(mktemp)"
tree_paths_tmp="$(mktemp)"
trap 'rm -f "$archive_tar" "$manifest_tmp" "$tree_paths_tmp"' EXIT
if ! git -C "$source_dir" ls-tree -r -z --name-only "$commit" >"$tree_paths_tmp"; then
  controller_fetch_prerequisite
  fail 'pinned archive tree objects are incomplete'
fi
if ! python3 - "$tree_paths_tmp" <<'PY'
import pathlib
import sys

paths = pathlib.Path(sys.argv[1]).read_bytes().split(b"\0")
for path in paths:
    if path and b".git" in path.split(b"/"):
        sys.exit(1)
PY
then
  fail 'archive contains a forbidden .git path component'
fi
if ! git -C "$source_dir" archive --format=tar --output="$archive_tar" "$commit"; then
  controller_fetch_prerequisite
  fail 'pinned archive objects are incomplete'
fi
tar -xf "$archive_tar" -C "$output_dir" || fail 'pinned source archive could not be extracted'
rm -f "$archive_tar"
if find -P "$output_dir" -mindepth 1 -name .git -print -quit | grep -q .; then
  fail 'archive contains a forbidden .git path component'
fi

python3 - "$output_dir/source-metadata.json" "$upstream_url" "$release" "$tag_object" "$commit" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
metadata = {
    "schemaVersion": 1,
    "upstreamUrl": sys.argv[2],
    "release": sys.argv[3],
    "tagObject": sys.argv[4],
    "commit": sys.argv[5],
    "archiveFormat": "git-archive-tar",
}
path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

manifest="$output_dir/source-manifest.sha256"
if ! python3 - "$output_dir" >"$manifest_tmp" <<'PY'
import hashlib
import os
import stat
import sys

root = os.fsencode(sys.argv[1])
entries = []

def hash_regular(path):
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    digest = hashlib.sha256()
    descriptor = os.open(path, flags)
    try:
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                return digest.hexdigest().encode("ascii")
            digest.update(chunk)
    finally:
        os.close(descriptor)

def visit(directory, relative_prefix=b""):
    for entry in os.scandir(directory):
        name = entry.name
        relative = name if not relative_prefix else relative_prefix + b"/" + name
        mode = entry.stat(follow_symlinks=False).st_mode
        if stat.S_ISDIR(mode):
            visit(entry.path, relative)
        elif stat.S_ISREG(mode):
            entries.append((relative, hash_regular(entry.path)))
        elif stat.S_ISLNK(mode):
            target = os.readlink(entry.path)
            entries.append((relative, hashlib.sha256(target).hexdigest().encode("ascii")))
        else:
            raise ValueError(f"unsupported staged file type: {os.fsdecode(relative)!r}")

def manifest_path(path):
    if b"\\" not in path and b"\n" not in path:
        return b"", path
    escaped = path.replace(b"\\", b"\\\\").replace(b"\n", b"\\n")
    return b"\\", escaped

visit(root)
for relative, digest in sorted(entries, key=lambda item: item[0]):
    prefix, encoded_path = manifest_path(relative)
    sys.stdout.buffer.write(prefix + digest + b"  " + encoded_path + b"\n")
PY
then
  fail 'staged source inventory could not be generated without following symlinks'
fi
mv -- "$manifest_tmp" "$manifest"

chmod -R a-w "$output_dir"
printf 'TSNET_SOURCE_STAGED\n'
printf 'source_commit=%s\n' "$commit"
printf 'output=%s\n' "$output_dir"
)

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  stage_tsnet_source "$TSNET_CANONICAL_TAG_OBJECT" "$TSNET_CANONICAL_COMMIT" "$@"
fi
