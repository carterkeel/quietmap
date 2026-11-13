#!/usr/bin/env bash
# Runs the instrumented UI test on the CI emulator and always saves logcat, so crashes
# (stack traces under "FATAL EXCEPTION") end up in the ci-logs summary.
#
# Emulators on shared CI machines are sometimes slow to settle, so a failed run is retried
# once from a clean app state. A real crash fails both attempts.
set -uo pipefail
status=1
for attempt in 1 2; do
  echo "=== UI smoke test, attempt $attempt ===" | tee -a emulator.log
  adb logcat -c || true
  adb shell pm clear app.quietmap.debug >/dev/null 2>&1 || true
  ./gradlew :androidApp:connectedDebugAndroidTest --stacktrace 2>&1 | tee -a emulator.log
  status=$?
  adb logcat -d -v time >> logcat.txt || true
  [ $status -eq 0 ] && break
done
exit $status
