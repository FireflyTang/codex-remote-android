#!/usr/bin/env bash
set -euo pipefail
# shellcheck source=common.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"

CMDLINE_TOOLS_REVISION="${CMDLINE_TOOLS_REVISION:-15859902}"
CMDLINE_TOOLS_SHA256="${CMDLINE_TOOLS_SHA256:-4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583}"
GO_VERSION="1.26.6"
WITH_IMAGE=1
case "${1:-}" in
  "") ;;
  --without-image) WITH_IMAGE=0 ;;
  *) echo "usage: $0 [--without-image]" >&2; exit 2 ;;
esac

require_command curl
require_command unzip

install_go_if_needed() {
  local go_arch go_sha archive extract installed_version
  case "$(uname -m)" in
    x86_64)
      go_arch=amd64
      go_sha=708effb774be8237570d0add163225abbdfaf4fca28b2611df167beba4feef89
      ;;
    aarch64|arm64)
      go_arch=arm64
      go_sha=d0507e9e9d7fe012aae570108cbd76c15de879e17130ab8cb90d4d7445cb1f2e
      ;;
    *) echo "error: no Go $GO_VERSION download mapping for $(uname -m)" >&2; exit 1 ;;
  esac
  if [[ -x "$TOOLS_DIR/go/bin/go" ]]; then
    installed_version="$($TOOLS_DIR/go/bin/go version)"
    if [[ "$installed_version" == "go version go${GO_VERSION} linux/${go_arch}" ]]; then
      return
    fi
  fi
  archive="$TOOLS_DIR/downloads/go${GO_VERSION}.linux-${go_arch}.tar.gz"
  extract="$TOOLS_DIR/go.extract"
  mkdir -p "$(dirname "$archive")"
  if [[ ! -f "$archive" ]] || ! echo "$go_sha  $archive" | sha256sum -c - >/dev/null 2>&1; then
    echo "Downloading repository-local Go $GO_VERSION..."
    curl -fL --retry 3 -o "$archive.part" \
      "https://go.dev/dl/go${GO_VERSION}.linux-${go_arch}.tar.gz"
    mv "$archive.part" "$archive"
  fi
  echo "$go_sha  $archive" | sha256sum -c -
  rm -rf "$extract"
  mkdir -p "$extract"
  tar -xzf "$archive" -C "$extract"
  rm -rf "$TOOLS_DIR/go"
  mv "$extract/go" "$TOOLS_DIR/go"
  rmdir "$extract"
  GOROOT="$TOOLS_DIR/go"
  export GOROOT PATH="$GOROOT/bin:$PATH"
}

install_jdk17_if_needed() {
  if use_java17_if_available; then
    return
  fi
  local arch archive temp
  case "$(uname -m)" in
    x86_64) arch=x64 ;;
    aarch64|arm64) arch=aarch64 ;;
    *) echo "error: no local JDK 17 download mapping for $(uname -m)" >&2; exit 1 ;;
  esac
  archive="$TOOLS_DIR/downloads/temurin-jdk17.tar.gz"
  temp="$TOOLS_DIR/jdk-17.extract"
  mkdir -p "$(dirname "$archive")"
  echo "Downloading repository-local Temurin JDK 17..."
  curl -fL --retry 3 -o "$archive.part" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/$arch/jdk/hotspot/normal/eclipse"
  mv "$archive.part" "$archive"
  rm -rf "$temp"
  mkdir -p "$temp"
  tar -xzf "$archive" -C "$temp" --strip-components=1
  rm -rf "$TOOLS_DIR/jdk-17"
  mv "$temp" "$TOOLS_DIR/jdk-17"
  JAVA_HOME="$TOOLS_DIR/jdk-17"
  export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
}

install_cmdline_tools_if_needed() {
  [[ -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]] && return
  local archive extract
  archive="$TOOLS_DIR/downloads/commandlinetools-linux-${CMDLINE_TOOLS_REVISION}_latest.zip"
  extract="$TOOLS_DIR/cmdline-tools.extract"
  mkdir -p "$(dirname "$archive")" "$ANDROID_SDK_ROOT/cmdline-tools"
  if [[ ! -f "$archive" ]]; then
    echo "Downloading Android command-line tools $CMDLINE_TOOLS_REVISION..."
    curl -fL --retry 3 -o "$archive.part" \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_REVISION}_latest.zip"
    mv "$archive.part" "$archive"
  fi
  echo "$CMDLINE_TOOLS_SHA256  $archive" | sha256sum -c -
  rm -rf "$extract"
  unzip -q "$archive" -d "$extract"
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  mv "$extract/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  rmdir "$extract"
}

pick_system_image() {
  local available preferred fallback
  available="$($ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --list 2>/dev/null)"
  preferred="system-images;android-${ANDROID_API};google_apis_16k;x86_64"
  fallback="system-images;android-${ANDROID_API};google_apis;x86_64"
  if grep -Fq "$preferred" <<<"$available"; then
    printf '%s\n' "$preferred"
  elif grep -Fq "$fallback" <<<"$available"; then
    echo "warning: API ${ANDROID_API} 16 KB x86_64 image is unavailable; using standard-page image" >&2
    printf '%s\n' "$fallback"
  else
    echo "error: neither $preferred nor $fallback is available" >&2
    return 1
  fi
}

install_go_if_needed
install_jdk17_if_needed
install_cmdline_tools_if_needed
SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true
packages=(
  platform-tools
  emulator
  "platforms;android-${ANDROID_API}"
  "build-tools;${ANDROID_BUILD_TOOLS}"
  "ndk;${ANDROID_NDK_VERSION}"
)
if (( WITH_IMAGE )); then
  SYSTEM_IMAGE_PACKAGE="$(pick_system_image)"
  packages+=("$SYSTEM_IMAGE_PACKAGE")
  printf '%s\n' "$SYSTEM_IMAGE_PACKAGE" > "$ANDROID_WORK_DIR/system-image-package.txt"
fi
"$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" "${packages[@]}"

echo "Android SDK: $ANDROID_SDK_ROOT"
echo "Java: $($JAVA_HOME/bin/java -version 2>&1 | head -n 1) ($JAVA_HOME)"
echo "Go: $($GOROOT/bin/go version) ($GOROOT)"
echo "Android NDK: $ANDROID_NDK_HOME"
"$ANDROID_SDK_ROOT/platform-tools/adb" version | head -n 1
"$ANDROID_SDK_ROOT/emulator/emulator" -version | head -n 1
du -sh "$TOOLS_DIR" "$ANDROID_WORK_DIR"
