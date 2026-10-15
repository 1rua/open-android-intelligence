#!/usr/bin/env bash
# 对明确指定的验证设备执行真实 MediaStore 与下载中断恢复；不清数据、不清日志、不删除公开文件。
set -euo pipefail

serial=""
apk=""
test_apk=""
evidence=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial) serial="${2:?需要设备编号}"; shift 2 ;;
    --apk) apk="${2:?需要 App APK}"; shift 2 ;;
    --test-apk) test_apk="${2:?需要测试 APK}"; shift 2 ;;
    --evidence) evidence="${2:?需要证据目录}"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done
[ -n "$serial" ] && [ -f "$apk" ] && [ -f "$test_apk" ] && [ -n "$evidence" ] || {
  echo "用法：$0 --serial 设备编号 --apk APK --test-apk 测试APK --evidence 证据目录" >&2
  exit 2
}
adb="${ADB:-adb}"
package="com.openandroidintelligence.mobile"
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
probe="$package.InterruptedDownloadProcessInstrumentedTest"
run_id="oai_$(date -u +%Y%m%d_%H%M%S)_${RANDOM}"
signal="files/download-process-$run_id.json"
mkdir -p "$evidence"

"$adb" -s "$serial" get-state > "$evidence/device-state.txt"
"$adb" -s "$serial" shell getprop ro.build.version.sdk > "$evidence/device-api.txt"
"$adb" -s "$serial" shell getprop ro.build.fingerprint > "$evidence/device-fingerprint.txt"
sha256sum "$apk" "$test_apk" > "$evidence/apk-sha256.txt"
"$adb" -s "$serial" install -r "$apk" > "$evidence/install-app.txt"
"$adb" -s "$serial" install -r "$test_apk" > "$evidence/install-test.txt"

echo "验证离线原件、同名下载和 33 MiB 附件。"
"$adb" -s "$serial" shell am instrument -w -e class "$package.ConversationDownloadsInstrumentedTest" \
  "$runner" > "$evidence/mediastore.txt"
rg -q 'OK \(3 tests\)' "$evidence/mediastore.txt" || {
  cat "$evidence/mediastore.txt"; exit 1
}

echo "验证超过系统读取窗口的大消息能够冷重开。"
"$adb" -s "$serial" shell am instrument -w -e class "$package.ConversationMirrorInstrumentedTest" \
  "$runner" > "$evidence/conversation-mirror.txt"
rg -q 'OK \(1 test\)' "$evidence/conversation-mirror.txt" || {
  cat "$evidence/conversation-mirror.txt"; exit 1
}

echo "启动真实写入，等待首块完成且文件仍未发布。"
"$adb" -s "$serial" shell am instrument -w -e processRunId "$run_id" \
  -e class "$probe#exportWaitsForExternalProcessTermination" "$runner" \
  > "$evidence/interrupted-process.txt" 2>&1 &
waiter_pid=$!
ready=false
for ((attempt=0; attempt<100; attempt++)); do
  if "$adb" -s "$serial" shell run-as "$package" cat "$signal" > "$evidence/process-ready.json" 2>/dev/null; then
    if rg -q 'waiting-for-kill' "$evidence/process-ready.json"; then ready=true; break; fi
  fi
  if ! kill -0 "$waiter_pid" 2>/dev/null; then break; fi
  sleep 0.2
done
if [ "$ready" != true ]; then
  # 只停止本脚本发起的目标验证进程，避免后台探针继续占用设备。
  "$adb" -s "$serial" shell am force-stop "$package"
  wait "$waiter_pid" || true
  cat "$evidence/interrupted-process.txt"
  echo "没有取得真实未发布下载信号，验证失败。" >&2
  exit 1
fi

echo "在写入期间终止 App 进程，再启动新进程恢复同一下载条目。"
"$adb" -s "$serial" shell am force-stop "$package"
wait "$waiter_pid" || true
"$adb" -s "$serial" shell am instrument -w -e processRunId "$run_id" \
  -e class "$probe#nextProcessRecoversTheRealPendingDownload" "$runner" \
  > "$evidence/recovered-process.txt"
rg -q 'OK \(1 test\)' "$evidence/recovered-process.txt" || {
  cat "$evidence/recovered-process.txt"; exit 1
}
"$adb" -s "$serial" shell run-as "$package" cat "$signal" > "$evidence/process-recovered.json"
rg -q 'recovered-and-verified' "$evidence/process-recovered.json"
echo "验证通过：3 项真实系统下载、大消息冷重开和新进程恢复通过，证据已保存。"
