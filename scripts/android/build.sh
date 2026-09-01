#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_sdk
if (( $# )); then
  gradlew "$@"
else
  gradlew :app:assembleDebug
fi
