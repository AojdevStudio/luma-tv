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
    "${adb[@]}" shell am instrument -w -r dev.luma.tv.e2e/dev.luma.tv.MediaE2E | tee "$OUT/result.txt"
    grep -q '^E2E PASS$' "$OUT/result.txt"
}

case "${1:-all}" in
    all) build_apks; install_and_test ;;
    build) build_apks ;;
    test) install_and_test ;;
    *) echo "Usage: $0 [all|build|test]" >&2; exit 2 ;;
esac
