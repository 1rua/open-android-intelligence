#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Android SDK 环境漂移修复：优先使用仓库内 .toolchains/android-sdk，
# 不依赖用户 shell 可能指向旧路径/缺失路径的 ANDROID_HOME。
ANDROID_SDK_REPO_DIR="${ROOT_DIR}/.toolchains/android-sdk"
if [ -d "${ANDROID_SDK_REPO_DIR}" ]; then
  export ANDROID_HOME="${ANDROID_SDK_REPO_DIR}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_REPO_DIR}"
fi
MODE="${1:-}"
if [[ "$MODE" != "--sdk-free" && "$MODE" != "--release" ]]; then
  echo "usage: $0 --sdk-free|--release" >&2
  exit 2
fi

# The smoke command is the executable test gate. The readiness tool then
# audits WP-00..WP-10 artifacts and reports external release blockers. A
# pending lock/device is expected in SDK-free mode, never converted to PASS.
SMOKE_STATUS=0
"$ROOT_DIR/e2e/mvp/run-smoke.sh" "$MODE" || SMOKE_STATUS=$?

READINESS_STATUS=0
"$ROOT_DIR/tools/run-node24" node --experimental-strip-types \
  "$ROOT_DIR/legacy/mvp-contract/tools/mvp-readiness.ts" "$MODE" || READINESS_STATUS=$?

if [[ "$SMOKE_STATUS" -ne 0 || "$READINESS_STATUS" -ne 0 ]]; then
  if [[ "$MODE" == "--release" ]]; then
    echo "RELEASE_GATE_BLOCKED: readiness report or executable gate failed"
  else
    echo "SDK_FREE_GATE_BLOCKED: contract/static smoke or artifact audit failed"
  fi
  exit 1
fi

if [[ "$MODE" == "--release" ]]; then
  echo "RELEASE_GATE_PASS"
else
  echo "SDK_FREE_GATE_PASS (production gate remains separate)"
fi
