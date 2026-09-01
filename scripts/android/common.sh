#!/usr/bin/env bash
set -euo pipefail

ANDROID_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$ANDROID_SCRIPT_DIR/../.." && pwd)"
TOOLS_DIR="${TOOLS_DIR:-$REPO_ROOT/.tools}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$TOOLS_DIR/android-sdk}"
ANDROID_HOME="$ANDROID_SDK_ROOT"
ANDROID_WORK_DIR="${ANDROID_WORK_DIR:-$REPO_ROOT/.work/android}"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$ANDROID_WORK_DIR/avd}"
ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ANDROID_WORK_DIR/user-home}"
ANDROID_EMULATOR_HOME="${ANDROID_EMULATOR_HOME:-$ANDROID_USER_HOME}"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ANDROID_WORK_DIR/gradle-home}"
ANDROID_API="${ANDROID_API:-36}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-36.0.0}"
ANDROID_NDK_VERSION="30.0.16138531"
ANDROID_AVD_NAME="${ANDROID_AVD_NAME:-codex_vivo_x200_ultra_api36}"
GOROOT="$TOOLS_DIR/go"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION}"
ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"

mkdir -p "$TOOLS_DIR" "$ANDROID_WORK_DIR" "$ANDROID_AVD_HOME" \
  "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"

is_java17_home() {
  local candidate="${1:-}" version
  [[ -n "$candidate" ]] || return 1
  [[ -x "$candidate/bin/java" ]] || return 1
  version="$("$candidate/bin/java" -version 2>&1)" || return 1
  version="${version%%$'\n'*}"
  [[ "$version" == *'version "17.'* ]]
}

find_java17_home() {
  local candidate
  for candidate in "${JAVA_HOME:-}" "$TOOLS_DIR/jdk-17"; do
    if is_java17_home "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  if command -v java >/dev/null 2>&1; then
    candidate="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    if is_java17_home "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi
  return 1
}

use_java17_if_available() {
  local selected
  selected="$(find_java17_home)" || return 1
  JAVA_HOME="$selected"
  export JAVA_HOME
}

use_java17_if_available || true

export REPO_ROOT TOOLS_DIR ANDROID_SDK_ROOT ANDROID_HOME ANDROID_WORK_DIR
export ANDROID_AVD_HOME ANDROID_USER_HOME ANDROID_EMULATOR_HOME GRADLE_USER_HOME
export ANDROID_API ANDROID_BUILD_TOOLS ANDROID_NDK_VERSION ANDROID_AVD_NAME JAVA_HOME
export GOROOT ANDROID_NDK_HOME ANDROID_NDK_ROOT
export PATH="$GOROOT/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "error: required command not found: $1" >&2; exit 1; }
}

require_sdk() {
  [[ -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]] || {
    echo "error: local Android SDK is missing; run scripts/android/bootstrap.sh" >&2
    exit 1
  }
}

gradlew() {
  [[ -x "$REPO_ROOT/gradlew" ]] || {
    echo "error: $REPO_ROOT/gradlew does not exist or is not executable" >&2
    exit 1
  }
  "$REPO_ROOT/gradlew" --no-daemon "$@"
}
