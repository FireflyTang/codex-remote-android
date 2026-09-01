#!/usr/bin/env bash
# Source this file to use the repository-local Android SDK, AVD, and Gradle cache.
ANDROID_ENV_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$ANDROID_ENV_DIR/common.sh"
unset ANDROID_ENV_DIR
