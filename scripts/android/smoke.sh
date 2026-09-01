#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_sdk
require_command python3

ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
PACKAGE="com.firefly.codexremote"
ACTIVITY="$PACKAGE/.MainActivity"
TIMEOUT="${ANDROID_SMOKE_TIMEOUT:-120}"
CONNECT_ATTEMPTS="${ANDROID_SMOKE_CONNECT_ATTEMPTS:-2}"
HOST_ENDPOINT="${ANDROID_SMOKE_HOST_ENDPOINT:-}"
CODEX_TITLE="${ANDROID_SMOKE_CODEX_TITLE:-}"
stamp="$(date +%Y%m%d-%H%M%S)"
ARTIFACTS="${ANDROID_SMOKE_ARTIFACTS:-$ANDROID_WORK_DIR/smoke/$stamp}"
UI_XML="$ARTIFACTS/window.xml"
mkdir -p "$ARTIFACTS"

die() {
  echo "error: $*" >&2
  exit 1
}

[[ "$TIMEOUT" =~ ^[1-9][0-9]*$ ]] || die "ANDROID_SMOKE_TIMEOUT must be a positive integer"
[[ "$CONNECT_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || die "ANDROID_SMOKE_CONNECT_ATTEMPTS must be a positive integer"

select_device() {
  local lines count state
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    state="$($ADB -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)"
    [[ "$state" == device ]] || die "ANDROID_SERIAL=$ANDROID_SERIAL is not authorized and online (state: ${state:-unavailable})"
    printf '%s\n' "$ANDROID_SERIAL"
    return
  fi
  lines="$($ADB devices | awk 'NR > 1 && NF { print $1 " " $2 }')"
  count="$(grep -c . <<<"$lines" || true)"
  (( count > 0 )) || die "no adb device found; start an emulator or connect and authorize a device"
  (( count == 1 )) || die "multiple adb devices found; set ANDROID_SERIAL explicitly"
  read -r ANDROID_SERIAL state <<<"$lines"
  [[ "$state" == device ]] || die "adb device $ANDROID_SERIAL is not authorized and online (state: $state)"
  printf '%s\n' "$ANDROID_SERIAL"
}

SERIAL="$(select_device)"
adb_device() { "$ADB" -s "$SERIAL" "$@"; }

capture_app_log() {
  local destination=$1 pid
  pid="$(adb_device shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' | awk '{ print $1 }')"
  if [[ -n "$pid" ]]; then
    adb_device logcat -d -v threadtime --pid="$pid" > "$destination" 2>/dev/null || true
  else
    adb_device logcat -d -v threadtime -t 500 > "$destination" 2>/dev/null || true
  fi
}

capture_failure() {
  local status=$?
  if (( status != 0 )); then
    adb_device exec-out screencap -p > "$ARTIFACTS/failure.png" 2>/dev/null || true
    capture_app_log "$ARTIFACTS/logcat.txt"
    echo "Smoke artifacts: $ARTIFACTS" >&2
  fi
  exit "$status"
}
trap capture_failure EXIT

dump_ui() {
  local attempt
  for attempt in 1 2 3; do
    if adb_device shell uiautomator dump /sdcard/codex-remote-window.xml >/dev/null 2>&1 &&
      adb_device pull /sdcard/codex-remote-window.xml "$UI_XML" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# Prints the center point and matching label of the first node. Modes are:
# text, text-prefix, desc, desc-prefix, resource-suffix, and class.
find_node() {
  local mode=$1 value=${2:-}
  python3 - "$UI_XML" "$mode" "$value" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, mode, value = sys.argv[1:]
root = ET.parse(path).getroot()
for node in root.iter("node"):
    a = node.attrib
    text = a.get("text", "")
    desc = a.get("content-desc", "")
    resource = a.get("resource-id", "")
    matched = {
        "text": text == value,
        "text-prefix": text.startswith(value),
        "desc": desc == value,
        "desc-prefix": desc.startswith(value),
        "resource-suffix": resource.endswith(value),
        "class": a.get("class") == value,
    }.get(mode, False)
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
    if matched and bounds:
        x1, y1, x2, y2 = map(int, bounds.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2, text or desc or resource)
        break
PY
}

has_node() {
  [[ -n "$(find_node "$1" "${2:-}")" ]]
}

tap_node() {
  local result x y
  result="$(find_node "$1" "${2:-}")"
  [[ -n "$result" ]] || return 1
  read -r x y _ <<<"$result"
  adb_device shell input tap "$x" "$y"
}

wait_for_node() {
  local mode=$1 value=${2:-} description=${3:-$2} failure_mode=${4:-die}
  local deadline empty_since=0 error_since=0
  deadline=$((SECONDS + TIMEOUT))
  while (( SECONDS < deadline )); do
    dump_ui || { sleep 1; continue; }
    if has_node "$mode" "$value"; then
      return 0
    fi
    if [[ "$mode" == desc-prefix && "$value" == "打开会话：" ]] && has_node text "Host 暂无 Codex"; then
      if (( empty_since == 0 )); then
        empty_since=$SECONDS
      elif (( SECONDS - empty_since >= 5 )); then
        die "Host is connected but its Codex list is empty; create/import a session and rerun"
      fi
    else
      empty_since=0
    fi
    if has_node text "打开 Tailscale 登录" || has_node text "需要登录 Tailscale"; then
      die "Tailnet is not authorized; complete Tailscale login manually and rerun (app data was preserved)"
    fi
    if has_node text "连接失败"; then
      if (( error_since == 0 )); then
        error_since=$SECONDS
      elif (( SECONDS - error_since >= 3 )); then
        if [[ "$failure_mode" == return ]]; then
          echo "warning: Host connection attempt failed; retrying if attempts remain" >&2
          return 1
        fi
        die "Host connection failed; inspect $UI_XML and rerun with a reachable ANDROID_SMOKE_HOST_ENDPOINT"
      fi
    else
      error_since=0
    fi
    sleep 1
  done
  if [[ "$failure_mode" == return ]]; then
    echo "warning: timed out after ${TIMEOUT}s waiting for $description" >&2
    return 1
  fi
  die "timed out after ${TIMEOUT}s waiting for $description"
}

apk="${1:-}"
if [[ -z "$apk" ]]; then
  apk="$(find "$REPO_ROOT/app/build/outputs/apk" -type f -name '*debug*.apk' -print 2>/dev/null | sort | tail -n 1)"
fi
[[ -n "$apk" && -f "$apk" ]] || die "debug APK not found; pass its path or run scripts/android/build.sh"

echo "Device: $SERIAL"
echo "Installing while preserving existing app data and Tailnet state..."
adb_device install -r -t "$apk" >/dev/null
if [[ "${ANDROID_SMOKE_CLEAR_DATA:-0}" == 1 ]]; then
  echo "ANDROID_SMOKE_CLEAR_DATA=1: explicitly clearing app data and Tailnet state"
  adb_device shell pm clear "$PACKAGE" >/dev/null
fi

adb_device shell am force-stop "$PACKAGE"
adb_device shell am start -W -n "$ACTIVITY" > "$ARTIFACTS/am-start.txt"
wait_for_node text "Codex Remote" "app home screen"

if [[ -n "$HOST_ENDPOINT" ]]; then
  tap_node class android.widget.EditText || die "Host address field was not found"
  adb_device shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_A
  # Compose may drop/reorder a burst of synthetic key events. Send this short
  # endpoint one character at a time; this also makes persistence deterministic.
  for ((i = 0; i < ${#HOST_ENDPOINT}; i++)); do
    adb_device shell input text "${HOST_ENDPOINT:i:1}"
    sleep "${ANDROID_SMOKE_INPUT_DELAY:-0.04}"
  done
  adb_device shell input keyevent KEYCODE_BACK
fi

connected=0
for ((attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++)); do
  dump_ui
  if has_node text "已连接"; then
    connected=1
    break
  fi
  tap_node text "连接" || tap_node text "重新连接" || die "Connect button was not found"
  if wait_for_node text "已连接" "read-only Host connection" return; then
    connected=1
    break
  fi
  sleep 2
done
(( connected == 1 )) || die "Host connection failed after $CONNECT_ATTEMPTS attempts; inspect $UI_XML"

# A ready Host with no sessions is valid for the app, but cannot satisfy this
# navigation smoke. Refresh once, then require a stable session description.
dump_ui
tap_node text "刷新" || true
wait_for_node desc-prefix "打开会话：" "a Codex session in the Host list"
dump_ui
if [[ -n "$CODEX_TITLE" ]]; then
  tap_node desc "打开会话：$CODEX_TITLE" || die "Codex session title not found: $CODEX_TITLE"
else
  tap_node desc-prefix "打开会话：" || die "no clickable Codex session was found"
fi

# This smoke intentionally never finds or taps `发送消息` / `停止任务`.
if dump_ui && has_node desc "会话页面"; then
  :
else
  wait_for_node resource-suffix "conversation-screen" "conversation screen"
fi
if dump_ui && has_node desc "会话历史"; then
  :
else
  wait_for_node resource-suffix "conversation-history" "loaded conversation history"
fi

dump_ui
tap_node desc "返回会话列表" || die "conversation back button was not found"
wait_for_node desc-prefix "打开会话：" "return to the Codex list"

adb_device exec-out screencap -p > "$ARTIFACTS/success.png"
capture_app_log "$ARTIFACTS/logcat.txt"
trap - EXIT
echo "PASS: installed, connected read-only, opened one Codex session, and returned without StartTurn"
echo "Artifacts: $ARTIFACTS"
