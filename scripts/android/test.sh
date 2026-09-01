#!/usr/bin/env bash
set -euo pipefail
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_sdk
if [[ "${1:-}" == "--instrumented" ]]; then
  shift
  gradlew :app:connectedDebugAndroidTest "$@"
else
  if (( $# )); then
    gradlew "$@"
  else
    gradlew :app:testDebugUnitTest
  fi
fi
