#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

TOOLS_DIR="$TEST_ROOT/tools"
ANDROID_WORK_DIR="$TEST_ROOT/work"
source "$SCRIPT_DIR/emulator-start.sh"

config="$TEST_ROOT/config.ini"
cat > "$config" <<'EOF'
AvdId=test
hw.ramSize=1024
hw.cpu.ncore=1
hw.lcd.width=720
hw.ramSize=4096
hw.cpu.ncore=4
hw.lcd.width=1440
hw.lcd.height=3200
hw.lcd.density=510
vm.heapSize=512
skin.name=1440x3200
skin.path=_no_skin
disk.dataPartition.size=12G
EOF

load_emulator_profile
update_avd_config "$config"
update_avd_config "$config"

assert_one_value() {
  local key="$1" expected="$2" count actual
  count="$(awk -F= -v key="$key" '$1 == key { count++ } END { print count + 0 }' "$config")"
  [[ "$count" == 1 ]] || fail "$key occurs $count times"
  actual="$(awk -F= -v key="$key" '$1 == key { print substr($0, length(key) + 2) }' "$config")"
  [[ "$actual" == "$expected" ]] || fail "$key is $actual, expected $expected"
}

assert_one_value hw.ramSize 2048
assert_one_value hw.cpu.ncore 2
assert_one_value hw.lcd.width 1080
assert_one_value hw.lcd.height 2400
assert_one_value hw.lcd.density 420
assert_one_value vm.heapSize 256
assert_one_value skin.name 1080x2400
assert_one_value skin.path _no_skin
grep -qx 'AvdId=test' "$config" || fail 'unrelated AVD setting was lost'
grep -qx 'disk.dataPartition.size=12G' "$config" || fail 'data partition setting was lost'

ANDROID_EMULATOR_RAM_MB=1536 \
ANDROID_EMULATOR_CORES=3 \
ANDROID_EMULATOR_WIDTH=900 \
ANDROID_EMULATOR_HEIGHT=2000 \
ANDROID_EMULATOR_DENSITY=360 \
ANDROID_EMULATOR_HEAP_MB=192 \
  load_emulator_profile
update_avd_config "$config"
assert_one_value hw.ramSize 1536
assert_one_value hw.cpu.ncore 3
assert_one_value hw.lcd.width 900
assert_one_value hw.lcd.height 2000
assert_one_value hw.lcd.density 360
assert_one_value vm.heapSize 192

if (ANDROID_EMULATOR_RAM_MB=999 load_emulator_profile) >/dev/null 2>&1; then
  fail 'RAM below the supported range was accepted'
fi
if (ANDROID_EMULATOR_CORES=two load_emulator_profile) >/dev/null 2>&1; then
  fail 'non-numeric core count was accepted'
fi
if (ANDROID_EMULATOR_DENSITY=700 load_emulator_profile) >/dev/null 2>&1; then
  fail 'density above the supported range was accepted'
fi

echo 'emulator_config_test.sh: PASS'
