#!/usr/bin/env bash
# ==============================================================================
# Open Android Intelligence - E2E Multi-Agent Testing Pipeline Launcher
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

export LC_ALL="C.UTF-8"
export LANG="C.UTF-8"

# 优先使用项目内固定工具链
export PATH="${HOME}/.local/bin:${REPO_ROOT}/.toolchains/android-sdk/platform-tools:${PATH}"
export ANDROID_HOME="${REPO_ROOT}/.toolchains/android-sdk"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"
# 优先使用项目内固定工具链，否则回退至系统已有环境变量
if [ -d "${REPO_ROOT}/.toolchains/android-sdk" ]; then
    export ANDROID_HOME="${REPO_ROOT}/.toolchains/android-sdk"
    export ANDROID_SDK_ROOT="${ANDROID_HOME}"
    export PATH="${HOME}/.local/bin:${ANDROID_HOME}/platform-tools:${PATH}"
elif [ -n "${ANDROID_HOME:-}" ]; then
    export PATH="${HOME}/.local/bin:${ANDROID_HOME}/platform-tools:${PATH}"
else
    export PATH="${HOME}/.local/bin:${PATH}"
fi

echo "=============================================================================="
echo "Open Android Intelligence E2E Multi-Agent Orchestrator"
echo "Root: ${REPO_ROOT}"
echo "Time: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=============================================================================="

chmod +x "${SCRIPT_DIR}/run-e2e-orchestrator.py"

python3 "${SCRIPT_DIR}/run-e2e-orchestrator.py" "$@"

