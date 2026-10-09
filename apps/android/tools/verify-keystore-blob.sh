#!/usr/bin/env bash
# 使用已有 encrypted-store 编译产物核验真机密钥库；不编译 APK，不读写用户 App 数据。
set -euo pipefail

if [[ $# != 1 ]]; then
    echo "用法：bash apps/android/tools/verify-keystore-blob.sh <adb 设备地址或序列号>" >&2
    exit 2
fi
probe_serial=$1
probe_adb=${OAI_ADB:-adb}
probe_adb_options=()
if [[ -n ${OAI_ADB_SERVER_PORT:-} ]]; then
    probe_adb_options=(-P "$OAI_ADB_SERVER_PORT")
fi
probe_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd -- "$probe_dir/../../.." && pwd)
probe_sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$project_dir/.toolchains/android-sdk"}}
probe_build_tools=${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}
probe_android_jar="$probe_sdk/platforms/android-35/android.jar"
probe_module_jar=${OAI_ENCRYPTED_STORE_JAR:-"$project_dir/apps/android/encrypted-store/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar"}
probe_stdlib=${KOTLIN_STDLIB_JAR:-}
if [[ -z $probe_stdlib ]]; then
    probe_stdlib=$(rg --files "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.1.20" | rg '/kotlin-stdlib-2\.1\.20\.jar$')
fi
for probe_input in "$probe_android_jar" "$probe_module_jar" "$probe_stdlib"; do
    if [[ ! -f $probe_input ]]; then
        echo "缺少探针输入，请先运行 encrypted-store 的最小编译或测试任务。" >&2
        exit 2
    fi
done
"$probe_adb" "${probe_adb_options[@]}" -s "$probe_serial" get-state >/dev/null

probe_scratch=$(mktemp -d "${TMPDIR:-/tmp}/oai-keystore-probe.XXXXXXXX")
mkdir -p "$probe_scratch/classes" "$probe_scratch/dex"
javac --release 17 -cp "$probe_android_jar:$probe_module_jar:$probe_stdlib" \
    -d "$probe_scratch/classes" "$probe_dir/AndroidKeystoreBlobProbe.java"
jar cf "$probe_scratch/probe.jar" -C "$probe_scratch/classes" .
if ! "$probe_sdk/build-tools/$probe_build_tools/d8" --min-api 26 --lib "$probe_android_jar" \
    --output "$probe_scratch/dex" "$probe_scratch/probe.jar" "$probe_module_jar" "$probe_stdlib" \
    >"$probe_scratch/d8.log" 2>&1; then
    sed -n '1,20p' "$probe_scratch/d8.log" >&2
    exit 2
fi
probe_remote="/data/local/tmp/oai-keystore-probe-$(basename -- "$probe_scratch").dex"
"$probe_adb" "${probe_adb_options[@]}" -s "$probe_serial" push "$probe_scratch/dex/classes.dex" "$probe_remote" >/dev/null 2>&1
"$probe_adb" "${probe_adb_options[@]}" -s "$probe_serial" shell "CLASSPATH='$probe_remote' app_process /system/bin AndroidKeystoreBlobProbe"
# 保留独立临时编译产物与设备探针文件，便于复核；不执行永久删除命令。
