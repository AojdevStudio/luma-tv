#!/bin/bash
# Builds Luma, the fixture app and the media E2E instrumentation, installs them on the one connected Android TV
# emulator (or ANDROID_SERIAL), and runs the test against the real Watch Next provider.
# It uninstalls and reinstalls Luma and the fixture, so point it only at a test emulator.
# Env: ANDROID_SDK_ROOT (or ANDROID_HOME), LUMA_JDK (JDK 17). Exit 0 only when the test prints E2E PASS.
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

bash build.sh
rm -rf "$OUT" && mkdir -p "$OUT"

# Builds one test APK from tools/e2e/<name>: its manifest, its sources, and any extra compile-only classpath.
apk() {
    local name=$1 classpath=$2 dir="$OUT/$1"
    mkdir -p "$dir/classes" "$dir/dex"
    "$BT/aapt2" link -o "$dir/unsigned.apk" -I "$JAR" --manifest "tools/e2e/$name/AndroidManifest.xml"
    find "tools/e2e/$name/src" -name '*.java' > "$dir/sources.txt"
    javac -encoding UTF-8 --release 8 -classpath "$JAR${classpath:+:$classpath}" -d "$dir/classes" @"$dir/sources.txt"
    find "$dir/classes" -name '*.class' > "$dir/classes.txt"
    "$BT/d8" --lib "$JAR" ${classpath:+--classpath "$classpath"} --min-api 26 --output "$dir/dex" @"$dir/classes.txt"
    (cd "$dir/dex" && zip -q -u ../unsigned.apk classes.dex)
    "$BT/zipalign" -f -p 4 "$dir/unsigned.apk" "$dir/aligned.apk"
    "$BT/apksigner" sign --ks .signing/local.keystore --ks-pass pass:android --ks-key-alias luma-local --out "$OUT/$name.apk" "$dir/aligned.apk"
}
apk fixture ""
apk instrument build/classes

for pkg in dev.luma.tv.e2e dev.luma.tv.fixture dev.luma.tv; do "$ADB" uninstall "$pkg" >/dev/null 2>&1 || true; done
"$ADB" install dist/luma-tv.apk
"$ADB" install "$OUT/fixture.apk"
"$ADB" install "$OUT/instrument.apk"
"$ADB" shell pm grant dev.luma.tv android.permission.READ_TV_LISTINGS
"$ADB" shell am instrument -w -r dev.luma.tv.e2e/dev.luma.tv.MediaE2E | tee "$OUT/result.txt"
grep -q "E2E PASS" "$OUT/result.txt"
