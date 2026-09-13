#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Open Android Intelligence - E2E Testing Environment Preparation Script
# ==============================================================================

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
export LC_ALL="C.UTF-8"
export LANG="C.UTF-8"
export PATH="$HOME/.local/bin:$PROJECT_ROOT/.toolchains/android-sdk/platform-tools:$PROJECT_ROOT/.toolchains/android-sdk/cmdline-tools/latest/bin:$PATH"
export ANDROID_HOME="$PROJECT_ROOT/.toolchains/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-/tmp/android-user-home}"
mkdir -p "$ANDROID_USER_HOME"

echo "=== [1/5] Checking Toolchain & Binaries ==="
echo "Java Version:"
java -version 2>&1 | head -n 2

echo "Android CLI:"
if command -v android &>/dev/null; then
    android --version 2>/dev/null || echo "android CLI present"
else
    echo "WARNING: 'android' command not in PATH!"
fi

echo "ADB Version:"
adb version | head -n 2

echo "=== [2/5] Checking Hardware Virtualization (KVM) ==="
if [ -e "/dev/kvm" ] && [ -r "/dev/kvm" ] && [ -w "/dev/kvm" ]; then
    echo "KVM acceleration: AVAILABLE (/dev/kvm is read/writeable)"
else
    echo "KVM acceleration: UNAVAILABLE or permission denied"
fi

echo "=== [3/5] Checking Connected Devices / Emulators ==="
adb devices -l

echo "=== [4/5] Checking Target APK ==="
APK_PATH="$PROJECT_ROOT/apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk"
if [ -f "$APK_PATH" ]; then
    echo "APK already built: $APK_PATH ($(du -h "$APK_PATH" | cut -f1))"
else
    echo "APK not found. Building now..."
    cd "$PROJECT_ROOT/apps/android"
    ./gradlew :app:assembleFullDebug
    echo "Build completed: $APK_PATH"
fi

echo "=== [5/5] Validating Test Journeys & Manifest ==="
python3 "$PROJECT_ROOT/apps/android/journeys/validate_journeys.py"

echo "=============================================================================="
echo "Environment & Test Assets Preparation is READY."
echo "=============================================================================="
