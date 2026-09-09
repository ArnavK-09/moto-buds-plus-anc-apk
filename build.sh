#!/bin/bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOL="$ROOT/.toolchain/android-14"
ANDROID_JAR="$ROOT/.toolchain/android-34/android.jar"
PKG="com.dopamide.motoanc"
PKG_PATH="com/dopamide/motoanc"
BUILD="$ROOT/build"
SRC="$ROOT/app/src/main/java/$PKG_PATH"
RES="$ROOT/app/src/main/res"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
KEYSTORE="$ROOT/moto-buds-anc.keystore"
OUT="$ROOT/MotoBudsANC.apk"

AAPT2="$TOOL/aapt2"
D8="$TOOL/d8"
ZIPALIGN="$TOOL/zipalign"
APKSIGNER="$TOOL/apksigner"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/apk"

$AAPT2 compile --dir "$RES" -o "$BUILD/res.zip"
$AAPT2 link -o "$BUILD/unsigned.apk" -I "$ANDROID_JAR" --manifest "$MANIFEST" -R "$BUILD/res.zip" --auto-add-overlay --min-sdk-version 26 --target-sdk-version 34 --version-code 2 --version-name "1.1" --java "$BUILD/gen"

javac --release 8 -cp "$ANDROID_JAR" -d "$BUILD/classes" "$BUILD/gen/$PKG_PATH/R.java" "$SRC"/*.java

jar cvf "$BUILD/classes.jar" -C "$BUILD/classes" .
$D8 --release --lib "$ANDROID_JAR" --output "$BUILD/apk" "$BUILD/classes.jar"

pushd "$BUILD/apk" > /dev/null
zip -uj "$BUILD/unsigned.apk" classes.dex
popd > /dev/null

$ZIPALIGN -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

$APKSIGNER sign --ks "$KEYSTORE" --ks-pass pass:motoancpass --key-pass pass:motoancpass --out "$OUT" "$BUILD/aligned.apk"
$APKSIGNER verify "$OUT"

echo "APK: $OUT"
ls -lh "$OUT"
