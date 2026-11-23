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
serial=${ANDROID_SERIAL:-$("$ADB" -e get-serialno)}
adb=("$ADB" -s "$serial")
if [[ $("${adb[@]}" shell getprop ro.kernel.qemu) != 1 ]]; then
    echo "Run the suite on an Android TV emulator." >&2
    exit 1
fi
export LUMA_KEYSTORE="${LUMA_KEYSTORE:-.signing/local.keystore}"

bash build.sh
rm -rf "$OUT" && mkdir -p "$OUT"

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
apk fixture ""
apk instrument build/classes

target=dev.luma.tv.e2e.target
mkdir -p "$OUT/target"
"$BT/aapt2" link -o "$OUT/target/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml \
    --rename-manifest-package "$target" --auto-add-overlay build/resources.zip -R build/overlay.zip
(cd build/dex && zip -q ../e2e/target/unsigned.apk classes*.dex)
sign target
for name in target fixture instrument; do
    "${adb[@]}" install -r "$OUT/$name.apk"
done
"${adb[@]}" shell pm grant "$target" android.permission.READ_TV_LISTINGS
"${adb[@]}" shell am instrument -w -r dev.luma.tv.e2e/dev.luma.tv.MediaE2E | tee "$OUT/result.txt"
grep -q '^E2E PASS$' "$OUT/result.txt"
