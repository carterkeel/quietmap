#!/usr/bin/env bash
# Runs the instrumented UI test on the CI emulator and always saves logcat, so crashes
# (stack traces under "FATAL EXCEPTION") end up in the ci-logs summary.
set -uo pipefail
adb logcat -c || true
./gradlew :androidApp:connectedDebugAndroidTest --stacktrace 2>&1 | tee emulator.log
status=$?
adb logcat -d -v time > logcat.txt || true
exit $status
