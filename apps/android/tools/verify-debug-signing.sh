#!/usr/bin/env bash
# 校验 APK 的签名身份是否与仓库内固定调试密钥一致。
#
# 为什么需要它：Android 覆盖安装要求新旧 APK 的签名完全相同，签名不同会直接报
# INSTALL_FAILED_UPDATE_INCOMPATIBLE，必须先卸载旧包（本地数据一并丢失）。
# 使用仓库内固定的 app/keystore/debug.keystore 后，本机、CI 与发布产物的签名完全一致；
# 本脚本用于阻止"某台机器/某次 CI 又用回随机调试密钥"这类回归。
#
# 用法：
#   apps/android/tools/verify-debug-signing.sh [APK 路径]
#   不带参数时默认校验 apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk
#
# 可用环境变量：KEYSTORE / FINGERPRINT_FILE / KEYSTORE_ALIAS / KEYSTORE_PASSWORD / APKSIGNER
#
# 退出码：0 = 签名一致；1 = 不一致或无法完成校验。

set -euo pipefail

# JDK 工具（keytool、apksigner）在非 UTF-8 locale 下会把非 ASCII 路径编码成乱码，
# 于是报 "Keystore file does not exist" 之类的假故障。这里先统一切到 UTF-8 locale
# （Linux 上 C.UTF-8 一定存在），保证中文路径下也能正常校验。
case "${LC_ALL:-${LC_CTYPE:-${LANG:-}}}" in
  *UTF-8 | *utf8 | *UTF8) ;;
  *)
    exec env LANG=C.UTF-8 LC_ALL=C.UTF-8 bash "${BASH_SOURCE[0]:-$0}" "$@"
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
ANDROID_DIR="${REPO_ROOT}/apps/android"

KEYSTORE="${KEYSTORE:-${ANDROID_DIR}/app/keystore/debug.keystore}"
FINGERPRINT_FILE="${FINGERPRINT_FILE:-${ANDROID_DIR}/app/keystore/debug.keystore.fingerprint}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-androiddebugkey}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-android}"
APK="${1:-${ANDROID_DIR}/app/build/outputs/apk/full/debug/app-full-debug.apk}"

fail() {
  printf '签名校验失败：%s\n' "$1" >&2
  exit 1
}

# 归一化证书指纹：去掉冒号与空白、统一大写，便于跨 keytool / apksigner 比较。
normalize() {
  printf '%s' "$1" | tr -d ':' | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]'
}

# 仅用于打印的可读形式（冒号分隔大写），保持 POSIX sed 兼容。
prettify() {
  printf '%s' "$1" | sed 's/../&:/g; s/:$//'
}

# 统一转成绝对路径：apksigner 回退分支要临时切换工作目录，相对路径在那里会失效。
abs_path() {
  case "$1" in
    /*) printf '%s' "$1" ;;
    *) printf '%s/%s' "${PWD%/}" "$1" ;;
  esac
}

APK="$(abs_path "$APK")"
KEYSTORE="$(abs_path "$KEYSTORE")"
FINGERPRINT_FILE="$(abs_path "$FINGERPRINT_FILE")"

[ -f "$APK" ] || fail "找不到 APK：${APK}
请先构建（cd apps/android && ./gradlew :app:assembleFullDebug），或把 APK 路径作为第一个参数传入。"
[ -f "$KEYSTORE" ] || fail "找不到固定调试密钥：${KEYSTORE}"
[ -f "$FINGERPRINT_FILE" ] || fail "找不到指纹文件：${FINGERPRINT_FILE}"
command -v keytool >/dev/null 2>&1 || fail "找不到 keytool（需要 JDK，CI 上由 actions/setup-java 提供）"

PINNED="$(normalize "$(grep -vE '^[[:space:]]*(#|$)' "$FINGERPRINT_FILE" | head -n 1 || true)")"
[ -n "$PINNED" ] || fail "指纹文件没有可用内容：${FINGERPRINT_FILE}"

# 先确认仓库内的密钥本身就是被固定的那一份，避免"密钥被换掉但没人察觉"。
KEYSTORE_CERT="$(normalize "$(keytool -list -v \
  -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEYSTORE_ALIAS" 2>/dev/null \
  | awk '/SHA256:/ {print $2; exit}' || true)")"
[ -n "$KEYSTORE_CERT" ] || fail "无法从 ${KEYSTORE} 读取别名 ${KEYSTORE_ALIAS} 的证书（别名或口令是否正确？）"

if [ "$KEYSTORE_CERT" != "$PINNED" ]; then
  fail "仓库内调试密钥与固定指纹不一致（密钥疑似被替换过）：
  密钥指纹：$(prettify "$KEYSTORE_CERT")
  固定指纹：$(prettify "$PINNED")
若确实要更换调试密钥，请同步更新 ${FINGERPRINT_FILE}，
并明确代价：所有已安装的旧包都必须先卸载才能再安装。"
fi

# 读取 APK 签名者证书：优先用 JDK 自带 keytool 读 v1(JAR) 签名，
# 没有 v1 签名时（例如显式关闭了 v1）回退到 Android SDK 的 apksigner。
APK_CERT="$(normalize "$(keytool -printcert -jarfile "$APK" 2>/dev/null \
  | awk '/SHA256:/ {print $2; exit}' || true)")"

if [ -z "$APK_CERT" ]; then
  APKSIGNER="${APKSIGNER:-}"
  if [ -z "$APKSIGNER" ]; then
    for candidate in \
      "${ANDROID_HOME:-}/build-tools"/*/apksigner \
      "${ANDROID_SDK_ROOT:-}/build-tools"/*/apksigner \
      "${HOME}/Android/Sdk/build-tools"/*/apksigner; do
      if [ -x "$candidate" ]; then
        APKSIGNER="$candidate"
        break
      fi
    done
  fi
  if [ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ]; then
    # apksig 在中文路径等非 UTF-8 工作目录下会抛 InvalidPathException，先切到临时目录。
    APK_CERT="$(normalize "$( (cd "${TMPDIR:-/tmp}" && env LANG=C.UTF-8 LC_ALL=C.UTF-8 \
      "$APKSIGNER" verify --print-certs "$APK") 2>/dev/null \
      | awk -F': ' '/SHA-256 digest/ {print $2; exit}' || true)")"
    [ -n "$APK_CERT" ] || fail "apksigner 读不出该 APK 的签名者证书：${APK}
（APK 可能未签名或已损坏；本次使用的 apksigner：${APKSIGNER}）"
  else
    fail "该 APK 没有 v1(JAR) 签名，环境里也找不到 apksigner，无法校验签名身份：${APK}
请保持 debug 签名的 enableV1Signing = true（见 apps/android/build.gradle.kts），
或用 APKSIGNER 环境变量指定 Android SDK build-tools 目录下的 apksigner。"
  fi
fi

if [ "$APK_CERT" != "$PINNED" ]; then
  fail "APK 签名与仓库固定调试密钥不一致：
  APK 指纹：$(prettify "$APK_CERT")
  固定指纹：$(prettify "$PINNED")
这个 APK 覆盖安装到旧版本上会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
请确认构建时走的是 apps/android/app/build.gradle.kts 里 signingConfigs 的 debug 配置，
并且 app/keystore/debug.keystore 没有被本地随机密钥替换。"
fi

printf 'APK          : %s\n' "$APK"
printf '签名证书指纹 : %s\n' "$(prettify "$APK_CERT")"
printf '固定调试指纹 : %s\n' "$(prettify "$PINNED")"
printf '校验通过：该 APK 使用仓库固定调试签名，可对本机/CI 产出的调试包无缝覆盖安装。\n'
