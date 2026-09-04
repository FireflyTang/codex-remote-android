#!/usr/bin/env bash
set -euo pipefail

mobile_commit=${MOBILECORE_MOBILE_COMMIT:-4776eadac327bcb80cebc7413c91f8b4abf8ffa1}
tools_version=${MOBILECORE_TOOLS_VERSION:-v0.49.0}
mod_version=${MOBILECORE_MOD_VERSION:-v0.39.0}
sync_version=${MOBILECORE_SYNC_VERSION:-v0.22.0}
ndk_version=30.0.16138531

root_dir=$(cd "$(dirname "$0")" && pwd)
patch_file=${MOBILECORE_GOMOBILE_PATCH:-"$root_dir/patches/gomobile-local-module.patch"}
patch_sha=$(sha256sum "$patch_file" | awk '{print $1}')
cache_key=$(printf '%s\n' "$mobile_commit" "$tools_version" "$mod_version" "$sync_version" "$patch_sha" | sha256sum | awk '{print $1}')
if [[ ${1:-} == "--print-cache-key" ]]; then
  printf '%s\n' "$cache_key"
  exit 0
fi
go_bin=${GO:-"$root_dir/../.tools/go/bin/go"}
android_home=${ANDROID_HOME:-"$root_dir/../.tools/android-sdk"}
android_ndk_home=${ANDROID_NDK_HOME:-"$android_home/ndk/$ndk_version"}
mkdir -p "$root_dir/.tools"
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/codex-remote-android-aar.XXXXXX")
cache_dir="$root_dir/.tools/gomobile-$cache_key"
tools_dir="$cache_dir"
trap 'rm -rf "$work_dir"' EXIT

if [[ ! -x "$go_bin" ]]; then
  echo "Go tool not found: $go_bin" >&2
  exit 1
fi
if [[ ! -x "$android_ndk_home/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]]; then
  echo "Android NDK $ndk_version not found: $android_ndk_home" >&2
  exit 1
fi

download_repo() {
  local repo=$1 ref=$2 destination=$3 archive
  archive=$(mktemp "$work_dir/archive.XXXXXX")
  mkdir -p "$destination"
  curl -4 --fail --location --retry 3 --connect-timeout 15 --max-time 240 \
    "https://codeload.github.com/golang/$repo/tar.gz/$ref" -o "$archive"
  tar -xzf "$archive" --strip-components=1 -C "$destination"
}

mkdir -p "$root_dir/build"
if [[ ! -x "$cache_dir/bin/gomobile" || ! -d "$cache_dir/mobile-runtime/bind" ]]; then
  build_tools="$work_dir/toolchain"
  mkdir -p "$build_tools/bin" "$build_tools/src"
  download_repo mobile "$mobile_commit" "$build_tools/src/mobile"
  download_repo tools "refs/tags/$tools_version" "$build_tools/src/tools"
  download_repo mod "refs/tags/$mod_version" "$build_tools/src/mod"
  download_repo sync "refs/tags/$sync_version" "$build_tools/src/sync"

  patch -d "$build_tools/src/mobile" -p1 < "$patch_file"
  (
    cd "$build_tools/src/mobile"
    "$go_bin" mod edit \
      -replace=golang.org/x/tools=../tools \
      -replace=golang.org/x/mod=../mod \
      -replace=golang.org/x/sync=../sync
    GOBIN="$build_tools/bin" GOPROXY=off "$go_bin" install ./cmd/gomobile ./cmd/gobind
  )

  mkdir -p "$build_tools/mobile-runtime"
  cp -a "$build_tools/src/mobile/bind" "$build_tools/mobile-runtime/"
  cp -a "$build_tools/src/mobile/internal" "$build_tools/mobile-runtime/"
  (
    cd "$build_tools/mobile-runtime"
    "$go_bin" mod init golang.org/x/mobile
    "$go_bin" mod edit -go=1.26.6
  )
  mv "$build_tools" "$cache_dir"
fi

# gobind resolves imports before gomobile writes its generated JNI go.mod.
# Bind from a temporary copy whose go.mod alone contains the x/mobile tool
# runtime, keeping the checked-in product module clean. Keep every local module
# replacement under the neutral temporary root so compiled build metadata does
# not disclose the developer's checkout path.
module_dir="$work_dir/module"
runtime_dir="$work_dir/mobile-runtime"
staged_aar="$work_dir/mobilecore.aar"
gomobile_goflags=${GOFLAGS:-}
while [[ $gomobile_goflags =~ (^|[[:space:]])-buildvcs(=[^[:space:]]+)?($|[[:space:]]) ]]; do
  matched_flag=${BASH_REMATCH[0]}
  gomobile_goflags=${gomobile_goflags/"$matched_flag"/"${BASH_REMATCH[1]}${BASH_REMATCH[3]}"}
done
gomobile_goflags="${gomobile_goflags:+$gomobile_goflags }-buildvcs=false"
mkdir -p "$module_dir"
cp "$root_dir"/*.go "$root_dir/go.mod" "$root_dir/go.sum" "$module_dir/"
cp -a "$tools_dir/mobile-runtime" "$runtime_dir"
(
  cd "$module_dir"
  "$go_bin" mod edit \
    -require=golang.org/x/mobile@v0.0.0 \
    -replace=golang.org/x/mobile="$runtime_dir"
)

(
  cd "$module_dir"
  PATH="$tools_dir/bin:$(dirname "$go_bin"):$PATH" \
  ANDROID_HOME="$android_home" \
  ANDROID_NDK_HOME="$android_ndk_home" \
  GOFLAGS="$gomobile_goflags" \
  GOMOBILE_LOCAL_MODULE_DIR="$module_dir" \
  GOMOBILE_LOCAL_MODULE_PATH=github.com/FireflyTang/codex-remote-android/mobilecore \
  GOMOBILE_BIND_RUNTIME_DIR="$runtime_dir" \
  "$tools_dir/bin/gomobile" bind \
    -target=android/arm64,android/amd64 \
    -androidapi=36 \
    -trimpath \
    -javapkg=com.firefly.codexremote \
    -o "$staged_aar" \
    .
)

if [[ ! -s "$staged_aar" ]]; then
  echo "gomobile produced an empty AAR: $staged_aar" >&2
  exit 1
fi
mv "$staged_aar" "$root_dir/build/mobilecore.aar"
sha256sum "$root_dir/build/mobilecore.aar"
