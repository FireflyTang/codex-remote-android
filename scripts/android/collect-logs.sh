#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_sdk
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
stamp="$(date +%Y%m%d-%H%M%S)"
out="${1:-$ANDROID_WORK_DIR/logs/$stamp}"
mkdir -p "$out"
"$ADB" wait-for-device
"$ADB" devices -l > "$out/devices.txt"
"$ADB" shell getprop > "$out/getprop.txt"
"$ADB" logcat -d -v threadtime > "$out/logcat.txt"
"$ADB" shell dumpsys activity activities > "$out/activity.txt"
"$ADB" shell dumpsys package > "$out/packages.txt"
if [[ "${ANDROID_COLLECT_BUGREPORT:-0}" == 1 ]]; then
  timeout 180 "$ADB" bugreport "$out/bugreport" || echo "warning: bugreport timed out or failed" >&2
fi
echo "Logs collected: $out"
