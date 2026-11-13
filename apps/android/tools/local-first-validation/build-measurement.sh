#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
worktree_root=$(cd -- "$script_dir/../../../.." && pwd)
toolchains=$(cd -- "${OAI_TOOLCHAINS_ROOT:-$worktree_root/../../.toolchains}" && pwd)
snapshot_dir="$script_dir/build/baseline-measure-733c6a30"
baseline_ref=733c6a3079cfc33ad3f6f7aa01b7bf242b782ee4
expected_patch=26237eab93fb6cb1bf96961b3a85edb40e583694cf34a6c620fce5e5bde8564e
actual_patch=$(sha256sum "$script_dir/baseline-measurement-v3.patch")
if [[ ${actual_patch%% *} != "$expected_patch" ]]; then
  printf 'Measurement patch digest mismatch; refusing to build.\n' >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-$toolchains/jdk-21-extract/jbrsdk_jcef-21.0.10-linux-x64-b1163.110}"
export ANDROID_HOME="${ANDROID_HOME:-$toolchains/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$toolchains/android-user-home"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$toolchains/gradle-user-home}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export OAI_GIT_COMMIT="$baseline_ref"
export OAI_VERSION_CODE=2240
export OAI_VERSION_NAME=2.2.1-dev.224.measure+733c6a3079cf

printf 'base_commit=%s\nmeasurement_patch_sha256=%s\nversion_name=%s\n' \
  "$baseline_ref" "$expected_patch" "$OAI_VERSION_NAME"
printf 'WAITING_FOR_SHARED_GRADLE_LOCK\n'
flock "$toolchains/android-gradle-validation.lock" \
  "$snapshot_dir/apps/android/gradlew" -p "$snapshot_dir/apps/android" \
  --max-workers=2 :app:assembleFullDebug
apk="$snapshot_dir/apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk"
sha256sum "$apk"
"$ANDROID_HOME/build-tools/${OAI_BUILD_TOOLS_VERSION:-35.0.0}/apksigner" verify --print-certs "$apk"
printf 'measurement_build=success\n'
