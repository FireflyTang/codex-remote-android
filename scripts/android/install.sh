#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_sdk
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
apk="${1:-}"
if [[ -z "$apk" ]]; then
  apk="$(find "$REPO_ROOT/app/build/outputs/apk" -type f -name '*debug*.apk' -print 2>/dev/null | sort | tail -n 1)"
fi
[[ -n "$apk" && -f "$apk" ]] || { echo "error: APK not found; pass its path or run build.sh" >&2; exit 1; }
"$ADB" wait-for-device
"$ADB" install -r -t "$apk"
echo "Installed: $apk"
