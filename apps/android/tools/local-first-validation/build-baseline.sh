#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
worktree_root=$(cd -- "$script_dir/../../../.." && pwd)
toolchains=$(cd -- "${OAI_TOOLCHAINS_ROOT:-$worktree_root/../../.toolchains}" && pwd)
baseline_ref=733c6a3079cfc33ad3f6f7aa01b7bf242b782ee4
runtime_dir="$script_dir/build"
snapshot_dir="$runtime_dir/baseline-733c6a30"

if [[ -e "$snapshot_dir" ]]; then
  printf 'Baseline snapshot already exists; refusing to overwrite: %s\n' "$snapshot_dir" >&2
  exit 1
fi
mkdir -p "$runtime_dir"
mkdir "$snapshot_dir"
git -C "$worktree_root" archive "$baseline_ref" | tar -xf - -C "$snapshot_dir"

export JAVA_HOME="${JAVA_HOME:-$toolchains/jdk-21-extract/jbrsdk_jcef-21.0.10-linux-x64-b1163.110}"
export ANDROID_HOME="${ANDROID_HOME:-$toolchains/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$toolchains/gradle-user-home}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export OAI_GIT_COMMIT="$baseline_ref"
commit_count=$(git -C "$worktree_root" rev-list --count "$baseline_ref")
base_version=$(sed -n 's/^versionName=//p' "$snapshot_dir/apps/android/app/version.properties")
export OAI_VERSION_CODE=$((commit_count * 10))
export OAI_VERSION_NAME="${base_version}-dev.${commit_count}+${baseline_ref:0:12}"

printf 'baseline_commit=%s\nversion_code=%s\nversion_name=%s\n' \
  "$baseline_ref" "$OAI_VERSION_CODE" "$OAI_VERSION_NAME"
flock "$toolchains/android-gradle-validation.lock" \
  "$snapshot_dir/apps/android/gradlew" -p "$snapshot_dir/apps/android" \
  --max-workers=2 :app:assembleFullDebug

apk="$snapshot_dir/apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk"
sha256sum "$apk"
"$ANDROID_HOME/build-tools/${OAI_BUILD_TOOLS_VERSION:-35.0.0}/apksigner" verify --print-certs "$apk"
printf 'baseline_build=success\n'
