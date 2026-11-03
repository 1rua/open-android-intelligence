#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
worktree_root=$(cd -- "$script_dir/../../../.." && pwd)
toolchains=$(cd -- "${OAI_TOOLCHAINS_ROOT:-$worktree_root/../../.toolchains}" && pwd)
runtime_dir="$script_dir/build/emulator"
avd_name=oai-local-first-20261103
port=5562

export JAVA_HOME="${JAVA_HOME:-$toolchains/jdk-21-extract/jbrsdk_jcef-21.0.10-linux-x64-b1163.110}"
export ANDROID_HOME="${ANDROID_HOME:-$toolchains/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$runtime_dir/android-user"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ANDROID_ADB_SERVER_PORT=5038
export ADB_SERVER_PORT=5038
export TMPDIR="$runtime_dir/tmp"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p "$ANDROID_AVD_HOME" "$TMPDIR"

if [[ -n $(ss -H -ltn "( sport = :$port or sport = :$((port + 1)) )") ]]; then
  printf 'Emulator port pair %s/%s is occupied; refusing to start.\n' "$port" "$((port + 1))" >&2
  exit 1
fi

if [[ ! -f "$ANDROID_AVD_HOME/$avd_name.ini" ]]; then
  printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
    --name "$avd_name" --package 'system-images;android-35;google_apis;x86_64' \
    --device pixel_2 --path "$ANDROID_AVD_HOME/$avd_name.avd"
fi

exec "$ANDROID_HOME/emulator/emulator" -avd "$avd_name" -port "$port" \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on
