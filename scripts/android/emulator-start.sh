#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"

validate_integer_range() {
  local name="$1" value="$2" minimum="$3" maximum="$4"
  if [[ ! "$value" =~ ^[0-9]+$ ]] || (( value < minimum || value > maximum )); then
    echo "error: $name must be an integer from $minimum to $maximum (got: $value)" >&2
    return 1
  fi
}

load_emulator_profile() {
  EMULATOR_RAM_MB="${ANDROID_EMULATOR_RAM_MB:-2048}"
  EMULATOR_CORES="${ANDROID_EMULATOR_CORES:-2}"
  EMULATOR_WIDTH="${ANDROID_EMULATOR_WIDTH:-1080}"
  EMULATOR_HEIGHT="${ANDROID_EMULATOR_HEIGHT:-2400}"
  EMULATOR_DENSITY="${ANDROID_EMULATOR_DENSITY:-420}"
  EMULATOR_HEAP_MB="${ANDROID_EMULATOR_HEAP_MB:-256}"

  validate_integer_range ANDROID_EMULATOR_RAM_MB "$EMULATOR_RAM_MB" 1024 8192 || return
  validate_integer_range ANDROID_EMULATOR_CORES "$EMULATOR_CORES" 1 8 || return
  validate_integer_range ANDROID_EMULATOR_WIDTH "$EMULATOR_WIDTH" 480 4320 || return
  validate_integer_range ANDROID_EMULATOR_HEIGHT "$EMULATOR_HEIGHT" 800 7680 || return
  validate_integer_range ANDROID_EMULATOR_DENSITY "$EMULATOR_DENSITY" 120 640 || return
  validate_integer_range ANDROID_EMULATOR_HEAP_MB "$EMULATOR_HEAP_MB" 64 1024 || return
}

update_avd_config() {
  local config="$1" temporary
  [[ -f "$config" ]] || { echo "error: AVD config does not exist: $config" >&2; return 1; }
  temporary="$(mktemp "${config}.tmp.XXXXXX")"
  awk -F= '
    $1 == "hw.device.name" ||
    $1 == "hw.lcd.width" ||
    $1 == "hw.lcd.height" ||
    $1 == "hw.lcd.density" ||
    $1 == "hw.ramSize" ||
    $1 == "hw.cpu.ncore" ||
    $1 == "vm.heapSize" ||
    $1 == "skin.name" ||
    $1 == "skin.path" { next }
    { print }
  ' "$config" > "$temporary"
  {
    printf '%s\n' \
      'hw.device.name=vivo X200 Ultra profile' \
      "hw.lcd.width=$EMULATOR_WIDTH" \
      "hw.lcd.height=$EMULATOR_HEIGHT" \
      "hw.lcd.density=$EMULATOR_DENSITY" \
      "hw.ramSize=$EMULATOR_RAM_MB" \
      "hw.cpu.ncore=$EMULATOR_CORES" \
      "vm.heapSize=$EMULATOR_HEAP_MB" \
      "skin.name=${EMULATOR_WIDTH}x${EMULATOR_HEIGHT}" \
      'skin.path=_no_skin'
  } >> "$temporary"
  mv -f -- "$temporary" "$config"
}

main() {
  local window=0 image_file system_image_package avdmanager emulator adb config
  local serial log timeout_seconds deadline page_size
  local -a args

  [[ "${1:-}" == "--window" ]] && window=1
  require_sdk
  load_emulator_profile
  timeout_seconds="${ANDROID_EMULATOR_TIMEOUT:-240}"
  validate_integer_range ANDROID_EMULATOR_TIMEOUT "$timeout_seconds" 30 1200
  image_file="$ANDROID_WORK_DIR/system-image-package.txt"
  [[ -f "$image_file" ]] || { echo "error: no selected image; rerun bootstrap.sh without --without-image" >&2; exit 1; }
  system_image_package="$(<"$image_file")"
  avdmanager="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
  emulator="$ANDROID_SDK_ROOT/emulator/emulator"
  adb="$ANDROID_SDK_ROOT/platform-tools/adb"
  config="$ANDROID_AVD_HOME/$ANDROID_AVD_NAME.avd/config.ini"

  if [[ ! -f "$config" ]]; then
    printf 'no\n' | "$avdmanager" create avd --force --name "$ANDROID_AVD_NAME" \
      --package "$system_image_package"
  fi
  update_avd_config "$config"

  serial="$($adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')"
  if [[ -n "$serial" ]]; then
    echo "An emulator is already available: $serial"
    exit 0
  fi

  args=(-avd "$ANDROID_AVD_NAME" -memory "$EMULATOR_RAM_MB" -cores "$EMULATOR_CORES"
    -no-snapshot -no-boot-anim -gpu swiftshader_indirect -no-audio -no-metrics)
  (( window )) || args+=(-no-window)
  log="$ANDROID_WORK_DIR/emulator.log"
  nohup "$emulator" "${args[@]}" >"$log" 2>&1 &
  echo $! > "$ANDROID_WORK_DIR/emulator.pid"

  deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    serial="$($adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')"
    if [[ -n "$serial" ]] && [[ "$($adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
      page_size="$($adb -s "$serial" shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r')"
      echo "Emulator ready: $serial"
      echo "Profile: ${EMULATOR_RAM_MB} MiB RAM, ${EMULATOR_CORES} cores, ${EMULATOR_WIDTH}x${EMULATOR_HEIGHT} at ${EMULATOR_DENSITY} dpi"
      echo "Guest page size: ${page_size:-unknown} bytes; image: $system_image_package"
      exit 0
    fi
    sleep 2
  done
  echo "error: emulator did not boot within ${timeout_seconds}s; see $log" >&2
  exit 1
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
