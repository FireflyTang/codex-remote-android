#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT="$(mktemp -d)"
ORIGINAL_PATH="$PATH"
trap 'rm -rf "$TEST_ROOT"' EXIT

make_fake_java() {
  local home="$1" output="$2" status="${3:-0}"
  mkdir -p "$home/bin"
  cat > "$home/bin/java" <<EOF
#!/usr/bin/env bash
printf '%s\n' '$output' >&2
exit $status
EOF
  chmod +x "$home/bin/java"
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

JAVA17="$TEST_ROOT/caller-17"
JAVA21="$TEST_ROOT/caller-21"
JAVA_BAD="$TEST_ROOT/caller-bad"
LOCAL17="$TEST_ROOT/tools/jdk-17"
SYSTEM17="$TEST_ROOT/system-17"
make_fake_java "$JAVA17" 'openjdk version "17.0.16" 2026-07-21'
make_fake_java "$JAVA21" 'openjdk version "21.0.8" 2026-07-15'
make_fake_java "$JAVA_BAD" 'broken java' 1
make_fake_java "$LOCAL17" 'openjdk version "17.0.16" 2026-07-21'
make_fake_java "$SYSTEM17" 'openjdk version "17.0.16" 2026-07-21'

TOOLS_DIR="$TEST_ROOT/tools"
ANDROID_WORK_DIR="$TEST_ROOT/work"
JAVA_HOME="$JAVA21"
PATH="/usr/bin:/bin"
source "$SCRIPT_DIR/common.sh"

is_java17_home "$JAVA17" || fail 'Java 17 was rejected'
if is_java17_home "$JAVA21"; then fail 'Java 21 was accepted'; fi
if is_java17_home "$JAVA_BAD"; then fail 'failing java was accepted'; fi

JAVA_HOME="$JAVA17"
[[ "$(find_java17_home)" == "$JAVA17" ]] || fail 'caller Java 17 was not preferred'

JAVA_HOME="$JAVA21"
[[ "$(find_java17_home)" == "$LOCAL17" ]] || fail 'repository-local Java 17 was not selected over caller Java 21'
use_java17_if_available || fail 'repository-local Java 17 was not activated'
[[ "$JAVA_HOME" == "$LOCAL17" ]] || fail 'JAVA_HOME was not changed to repository-local Java 17'

JAVA_HOME="$JAVA_BAD"
rm -rf "$LOCAL17"
PATH="$SYSTEM17/bin:/usr/bin:/bin"
[[ "$(find_java17_home)" == "$SYSTEM17" ]] || fail 'system Java 17 was not selected after invalid JAVA_HOME'

JAVA_HOME="$JAVA21"
mkdir -p "$TEST_ROOT/no-java"
PATH="$TEST_ROOT/no-java"
if find_java17_home >/dev/null; then fail 'Java 21 should require repository-local JDK installation'; fi
if use_java17_if_available; then fail 'Java 21 should not be activated as Java 17'; fi
[[ "$JAVA_HOME" == "$JAVA21" ]] || fail 'failed selection unexpectedly changed JAVA_HOME'
PATH="$ORIGINAL_PATH"

echo 'common_test.sh: PASS'
