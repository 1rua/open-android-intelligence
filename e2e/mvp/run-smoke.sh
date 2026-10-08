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
NODE_LAUNCHER="$ROOT_DIR/tools/run-node24"
OPENCLAW_PLUGIN_ROOT="${OPENCLAW_PLUGIN_ROOT:-${ROOT_DIR}/.openclaw-gateway-plugin}"
export OPENCLAW_PLUGIN_ROOT
MODE="${1:-}"
if [[ "$MODE" != "--sdk-free" && "$MODE" != "--release" ]]; then
  echo "usage: $0 --sdk-free|--release" >&2
  exit 2
fi

if [[ ! -x "$NODE_LAUNCHER" ]]; then
  echo "SDK_FREE_BLOCKED: fixed Node 24 launcher missing ($NODE_LAUNCHER)" >&2
  exit 1
fi

python3 "$ROOT_DIR/tools/check-openclaw-plugin-pin.py"
python3 "$ROOT_DIR/gateway-contract/tools/check-contract-pin.py" --plugin-root-env OPENCLAW_PLUGIN_ROOT
if [[ ! -x "$OPENCLAW_PLUGIN_ROOT/node_modules/.bin/vitest" ]]; then
  "$NODE_LAUNCHER" npm ci --prefix "$OPENCLAW_PLUGIN_ROOT" --ignore-scripts
fi
OPEN_ANDROID_GATEWAY_CONTRACT_ROOT="$ROOT_DIR/gateway-contract" \
  "$NODE_LAUNCHER" npm run contract:check --prefix "$OPENCLAW_PLUGIN_ROOT"

if [[ "$MODE" == "--release" ]]; then
  if ! "$NODE_LAUNCHER" npm --prefix "$ROOT_DIR" run mvp:lock:check; then
    echo "RELEASE_GATE_BLOCKED: dependency lock is pending"
    exit 1
  fi
  if ! command -v adb >/dev/null 2>&1 || ! adb devices 2>/dev/null | grep -q '[[:space:]]device$'; then
    echo "RELEASE_GATE_BLOCKED: no adb-connected reference device"
    exit 1
  fi
  if ! command -v java >/dev/null 2>&1; then
    echo "RELEASE_GATE_BLOCKED: Java/Gradle Android toolchain is unavailable"
    exit 1
  fi
  (cd "$ROOT_DIR/apps/android" && ./gradlew --no-daemon check)
  echo "RELEASE_GATE_PASS"
  exit 0
fi

"$NODE_LAUNCHER" npx --no-install vitest --root "$ROOT_DIR" run \
  integrations \
  bridge-contract/test \
  legacy/bridge-runtime/test \
  artifact-contract/test \
  mvp-contract/test/mvp-contract.test.ts \
  mvp-contract/test/dependency-lock.test.ts

"$NODE_LAUNCHER" npm run typecheck --prefix "$OPENCLAW_PLUGIN_ROOT"
"$NODE_LAUNCHER" npm test --prefix "$OPENCLAW_PLUGIN_ROOT"
"$NODE_LAUNCHER" npm run build --prefix "$OPENCLAW_PLUGIN_ROOT"
git -C "$OPENCLAW_PLUGIN_ROOT" diff --exit-code -- runtime

python3 -m unittest discover -s "$ROOT_DIR/apps/android/tools" -p 'test_*.py'

"$NODE_LAUNCHER" npx --no-install tsc --ignoreConfig --noEmit --target ES2022 --module NodeNext \
  --moduleResolution NodeNext --strict --skipLibCheck \
  "$ROOT_DIR"/bridge-contract/src/*.ts \
  "$ROOT_DIR"/legacy/bridge-runtime/src/*.ts \
  "$ROOT_DIR"/artifact-contract/src/*.ts \
  "$ROOT_DIR"/mvp-contract/src/wire-codec.ts \
  "$ROOT_DIR"/integrations/shared/adapter.ts \
  "$ROOT_DIR"/legacy/integrations/hermes-v1/adapter.ts \
  --types node --typeRoots "$ROOT_DIR/node_modules/@types"

if "$NODE_LAUNCHER" npm --prefix "$ROOT_DIR" run mvp:lock:check >/tmp/open-android-intelligence-mvp-lock.out 2>&1; then
  echo "LOCK_GATE_PASS"
else
  echo "LOCK_GATE_PENDING"
  sed -n '1,16p' /tmp/open-android-intelligence-mvp-lock.out
fi

if command -v adb >/dev/null 2>&1 && adb devices 2>/dev/null | grep -q '[[:space:]]device$'; then
  echo "ANDROID_QA_AVAILABLE: run the locked Gradle/device smoke separately"
else
  echo "ANDROID_QA_SKIPPED: no adb/device in this environment"
fi

echo "SDK_FREE_PASS"
