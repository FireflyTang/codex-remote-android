#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_sdk

WINDOW=0
[[ "${1:-}" == "--window" ]] && WINDOW=1
IMAGE_FILE="$ANDROID_WORK_DIR/system-image-package.txt"
[[ -f "$IMAGE_FILE" ]] || { echo "error: no selected image; rerun bootstrap.sh without --without-image" >&2; exit 1; }
SYSTEM_IMAGE_PACKAGE="$(<"$IMAGE_FILE")"
AVDMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"

if [[ ! -f "$ANDROID_AVD_HOME/$ANDROID_AVD_NAME.avd/config.ini" ]]; then
  printf 'no\n' | "$AVDMANAGER" create avd --force --name "$ANDROID_AVD_NAME" \
    --package "$SYSTEM_IMAGE_PACKAGE"
  config="$ANDROID_AVD_HOME/$ANDROID_AVD_NAME.avd/config.ini"
  {
    echo 'hw.device.name=vivo X200 Ultra profile'
    echo 'hw.lcd.width=1440'
    echo 'hw.lcd.height=3200'
    echo 'hw.lcd.density=510'
    echo 'hw.ramSize=4096'
    echo 'hw.cpu.ncore=4'
    echo 'disk.dataPartition.size=12G'
    echo 'skin.name=1440x3200'
    echo 'skin.path=_no_skin'
  } >> "$config"
fi

serial="$($ADB devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')"
if [[ -n "$serial" ]]; then
  echo "An emulator is already available: $serial"
  exit 0
fi

args=(-avd "$ANDROID_AVD_NAME" -no-snapshot -no-boot-anim -gpu swiftshader_indirect -no-audio -no-metrics)
(( WINDOW )) || args+=(-no-window)
LOG="$ANDROID_WORK_DIR/emulator.log"
nohup "$EMULATOR" "${args[@]}" >"$LOG" 2>&1 &
echo $! > "$ANDROID_WORK_DIR/emulator.pid"

timeout_seconds="${ANDROID_EMULATOR_TIMEOUT:-240}"
deadline=$((SECONDS + timeout_seconds))
while (( SECONDS < deadline )); do
  serial="$($ADB devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')"
  if [[ -n "$serial" ]] && [[ "$($ADB -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    page_size="$($ADB -s "$serial" shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r')"
    echo "Emulator ready: $serial"
    echo "Guest page size: ${page_size:-unknown} bytes; image: $SYSTEM_IMAGE_PACKAGE"
    exit 0
  fi
  sleep 2
done
echo "error: emulator did not boot within ${timeout_seconds}s; see $LOG" >&2
exit 1
