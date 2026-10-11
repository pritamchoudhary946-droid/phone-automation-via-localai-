#!/usr/bin/env bash
#
# Pre-merge verification gate.
#
# Runs the exact build gate CI enforces (unit tests + debug APK) locally, and
# FAILS CLOSED if no toolchain is present. An unverified merge to `main` is what
# produced the P0 build breaks this gate exists to prevent, so "no JDK/SDK" is
# never treated as a pass.
#
# Usage:
#   bash .github/scripts/verify.sh                 # testDebugUnitTest + assembleDebug
#   SKIP_APK=1 bash .github/scripts/verify.sh      # unit tests only (faster)
#
# Exit codes: 0 = verified green, 1 = build/test failure, 2 = toolchain missing.
set -u

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

fail_toolchain() {
  echo "::error::VERIFY FAILED (no toolchain): $1" >&2
  echo "Refusing to certify an unverified change. Install JDK 21 and the Android" >&2
  echo "SDK (platform 36.1 + build-tools 36.1.0), or set JAVA_HOME / ANDROID_HOME." >&2
  exit 2
}

command -v java >/dev/null 2>&1 || fail_toolchain "'java' not found on PATH (JAVA_HOME=$JAVA_HOME)"
[ -x "$JAVA_HOME/bin/javac" ] || fail_toolchain "no javac at $JAVA_HOME/bin/javac"

if [ ! -d "$ANDROID_HOME/platforms" ]; then
  fail_toolchain "no Android SDK at ANDROID_HOME=$ANDROID_HOME"
fi

# Pin the SDK dir for Gradle without committing it (.gitignore already excludes it).
echo "sdk.dir=$ANDROID_HOME" > local.properties

tasks=":app:testDebugUnitTest"
if [ "${SKIP_APK:-0}" != "1" ]; then
  tasks="$tasks :app:assembleDebug"
fi

echo "== verify gate: ./gradlew --no-daemon $tasks =="
bash ./gradlew --no-daemon $tasks
status=$?
if [ "$status" -ne 0 ]; then
  echo "::error::VERIFY FAILED: ./gradlew --no-daemon $tasks exited $status" >&2
  exit 1
fi
echo "VERIFY OK"
