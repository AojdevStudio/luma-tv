#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?set ANDROID_SDK_ROOT}}"
export ANDROID_SDK_ROOT="$SDK"
export LUMA_JDK="${LUMA_JDK:-${JAVA_HOME:?set LUMA_JDK to a JDK 17}}"
export JAVA_HOME="$LUMA_JDK" PATH="$LUMA_JDK/bin:$PATH"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-34/android.jar"
ADB="$SDK/platform-tools/adb"
OUT=build/e2e
export LUMA_KEYSTORE="${LUMA_KEYSTORE:-.signing/local.keystore}"

apk() {
    local name=$1 classpath=$2 dir="$OUT/$1"
    mkdir -p "$dir/classes" "$dir/dex"
    "$BT/aapt2" link -o "$dir/unsigned.apk" -I "$JAR" --manifest "tools/e2e/$name/AndroidManifest.xml"
    find "tools/e2e/$name/src" -name '*.java' > "$dir/sources.txt"
    javac -encoding UTF-8 --release 8 -classpath "$JAR${classpath:+:$classpath}" -d "$dir/classes" @"$dir/sources.txt"
    find "$dir/classes" -name '*.class' > "$dir/classes.txt"
    "$BT/d8" --lib "$JAR" ${classpath:+--classpath "$classpath"} --min-api 26 --output "$dir/dex" @"$dir/classes.txt"
    (cd "$dir/dex" && zip -q -u ../unsigned.apk classes.dex)
    sign "$name"
}
sign() {
    local name=$1 dir="$OUT/$1"
    "$BT/zipalign" -f -p 4 "$dir/unsigned.apk" "$dir/aligned.apk"
    "$BT/apksigner" sign --ks "$LUMA_KEYSTORE" --ks-pass pass:android --ks-key-alias luma-local --out "$OUT/$name.apk" "$dir/aligned.apk"
}

target=dev.luma.tv.e2e.target
extras=(1 2 3 4 5 6 7)
build_apks() {
    bash build.sh
    rm -rf "$OUT"
    mkdir -p "$OUT/target"
    apk fixture ""
    apk instrument build/classes

    python3 - "$OUT/target/AndroidManifest.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

android = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', android)
manifest = ET.parse('AndroidManifest.xml')
for activity in manifest.findall('application/activity'):
    for intent_filter in activity.findall('intent-filter'):
        if intent_filter.find(f"category[@{{{android}}}name='android.intent.category.HOME']") is not None:
            activity.remove(intent_filter)
manifest.write(sys.argv[1], encoding='utf-8')
PY
    "$BT/aapt2" link -o "$OUT/target/unsigned.apk" -I "$JAR" --manifest "$OUT/target/AndroidManifest.xml" \
        --rename-manifest-package "$target" --auto-add-overlay build/resources.zip -R build/overlay.zip
    (cd build/dex && zip -q ../e2e/target/unsigned.apk classes*.dex)
    sign target
    # Seven code-free apps, so Home has more apps than the six it shows.
    for n in "${extras[@]}"; do
        mkdir -p "$OUT/app$n"
        sed "s/LABEL/Extra $n/g" tools/e2e/apps/AndroidManifest.xml > "$OUT/app$n/AndroidManifest.xml"
        "$BT/aapt2" link -o "$OUT/app$n/unsigned.apk" -I "$JAR" --manifest "$OUT/app$n/AndroidManifest.xml" --rename-manifest-package "dev.luma.tv.e2e.app$n"
        sign "app$n"
    done
}

install_and_test() {
    local serial name
    serial=${ANDROID_SERIAL:-$("$ADB" -e get-serialno)}
    if [[ ! $serial =~ ^emulator-[0-9]+$ ]]; then
        echo "Run the suite on an Android TV emulator (selected: $serial)." >&2
        return 1
    fi
    local adb=("$ADB" -s "$serial")
    python3 - "$ADB" "$serial" <<'PY'
import subprocess
import sys
import time

adb, serial = sys.argv[1:]
command = [adb, '-s', serial, 'shell', 'getprop ro.kernel.qemu && getprop sys.boot_completed']
deadline = time.monotonic() + 30
last = None
while (remaining := deadline - time.monotonic()) > 0:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=min(5, remaining))
        if result.returncode == 0:
            emulator, _, boot = result.stdout.partition('\n')
            if emulator.strip() != '1':
                sys.exit(f'{serial}: expected Android emulator, ro.kernel.qemu={emulator.strip()!r}')
            status = f'ADB online, sys.boot_completed={boot.strip() or "unset"}'
            if boot.strip() == '1':
                print(f'{serial}: {status}', flush=True)
                break
        else:
            status = result.stderr.strip() or f'ADB exited {result.returncode}'
    except subprocess.TimeoutExpired:
        status = 'ADB shell timed out'
    if status != last:
        print(f'{serial}: {status}', file=sys.stderr, flush=True)
        last = status
    time.sleep(min(1, max(0, deadline - time.monotonic())))
else:
    sys.exit(f'{serial}: did not become ready for install within 30 seconds')
PY
    for name in target fixture instrument; do
        echo "Installing $name on $serial"
        "${adb[@]}" install -r "$OUT/$name.apk"
    done
    "${adb[@]}" shell pm grant "$target" android.permission.READ_TV_LISTINGS
    : > "$OUT/result.txt"
    local suite
    for suite in "${suites[@]}"; do
        if [[ $suite == JourneysE2E ]]; then
            run_journeys
        else
            run_phase "$suite" "" ""
        fi
    done
}

run_phase() {
    local suite=$1 step=$2 expected_next=$3
    local args=(shell am instrument -w -r)
    [[ -z $step ]] || args+=(-e step "$step")
    "${adb[@]}" "${args[@]}" "dev.luma.tv.e2e/dev.luma.tv.$suite" | tee "$OUT/run.txt"
    cat "$OUT/run.txt" >> "$OUT/result.txt"
    local next
    next=$(sed -n 's/^E2E NEXT //p' "$OUT/run.txt")
    if ! grep -Fxq 'INSTRUMENTATION_CODE: -1' "$OUT/run.txt" ||
       ! grep -Fxq 'E2E PASS' "$OUT/run.txt" ||
       grep -q '^E2E FAIL' "$OUT/run.txt" || [[ $next != "$expected_next" ]]; then
        echo "Failed instrumentation result for $suite step ${step:-first}" >&2
        return 1
    fi
    if [[ $suite == JourneysE2E ]] && ! grep -Eq "^INSTRUMENTATION_RESULT: stream=step ${step:-first} pid [1-9][0-9]*$" "$OUT/run.txt"; then
        echo "Missing journey result for step ${step:-first}" >&2
        return 1
    fi
}

run_journeys() (
    local existing n package status=0 pkg=""
    local installed=()
    existing=$("${adb[@]}" shell pm list packages)
    trap '
        status=$?
        if (( ${#installed[@]} )); then
            for pkg in "${installed[@]}"; do
                "${adb[@]}" uninstall "$pkg" || status=1
            done
        fi
        exit "$status"
    ' EXIT
    for n in "${extras[@]}"; do
        package="dev.luma.tv.e2e.app$n"
        if grep -Fxq "package:$package" <<< "$existing"; then continue; fi
        "${adb[@]}" install "$OUT/app$n.apk"
        installed+=("$package")
    done
    run_phase JourneysE2E "" sports-restarted
    run_phase JourneysE2E sports-restarted default-restarted
    run_phase JourneysE2E default-restarted guest-restarted
    run_phase JourneysE2E guest-restarted ""
)

mode=${1:-all}
suites=("${@:2}")
(( ${#suites[@]} )) || suites=(MediaE2E JourneysE2E)
case "$mode" in
    all) build_apks; install_and_test ;;
    build) build_apks ;;
    test) install_and_test ;;
    *) echo "Usage: $0 [all|build|test] [suite...]" >&2; exit 2 ;;
esac
