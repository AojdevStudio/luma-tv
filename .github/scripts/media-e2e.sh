#!/bin/bash
# CI only. Runs tools/e2e/run.sh test on the emulator that android-emulator-runner started. While the suite runs it
# streams the guest's logcat, and when the suite fails it saves the guest's crash records before the action kills the
# emulator. It never retries and always exits with the suite's own status.
set -uo pipefail
cd "$(dirname "$0")/../.." || exit
ADB="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?set ANDROID_SDK_ROOT}}/platform-tools/adb"
: "${ANDROID_SERIAL:?android-emulator-runner must select the test emulator}"
out=build/e2e/evidence
mkdir -p "$out"

"$ADB" logcat -b all -v threadtime > "$out/logcat.txt" 2>&1 &
logcat=$!
status=0
bash tools/e2e/run.sh test || status=$?

if (( status != 0 )); then
    echo "Suite exited $status. Saving guest crash evidence to $out." >&2
    save() {
        local file=$1 code=0
        shift
        timeout --kill-after=2s 10s "$ADB" "$@" > "$out/$file" 2>&1 || code=$?
        (( code == 0 )) || echo "adb $* exited $code" | tee -a "$out/$file" >&2
    }
    save crash.txt logcat -d -b crash -v threadtime
    save dropbox.txt shell dumpsys dropbox --print
    save exit-info.txt shell dumpsys activity exit-info
    save meminfo.txt shell cat /proc/meminfo
    save getprop.txt shell getprop
fi
kill -KILL "$logcat" 2>/dev/null || true
wait "$logcat" 2>/dev/null || true
exit "$status"
