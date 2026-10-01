#!/bin/bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOL="$ROOT/.toolchain/android-14"
ANDROID_JAR="$ROOT/.toolchain/android-34/android.jar"
KOTLIN_HOME="$ROOT/.toolchain/kotlinc"
KOTLINC="$KOTLIN_HOME/bin/kotlinc"
KOTLIN_STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"
PKG="com.arnavk09.motoanc"
PKG_PATH="com/arnavk09/motoanc"
BUILD="$ROOT/build"
SRC="$ROOT/app/src/main/java/$PKG_PATH"
RES="$ROOT/app/src/main/res"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
KEYSTORE="$ROOT/moto-buds-anc.keystore"
OUT="$ROOT/MotoBudsANC.apk"

KOTLIN_VERSION="2.4.20"
KOTLIN_SHA256="59e9ca74c7904ef2c122b12114937673ccce68de820a663f0ed66ccf8799e0b7"
RULES="$ROOT/r8-rules.txt"

AAPT2="$TOOL/aapt2"
ZIPALIGN="$TOOL/zipalign"
APKSIGNER="$TOOL/apksigner"

# Fetch the Kotlin compiler and R8 into .toolchain/ on first run (gitignored).
source "$ROOT/toolchain.sh"
ensure_kotlin
ensure_r8

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/kt-classes" "$BUILD/apk"

$AAPT2 compile --dir "$RES" -o "$BUILD/res.zip"
$AAPT2 link -o "$BUILD/unsigned.apk" -I "$ANDROID_JAR" --manifest "$MANIFEST" -R "$BUILD/res.zip" --auto-add-overlay --min-sdk-version 26 --target-sdk-version 34 --version-code 4 --version-name "2.0" --java "$BUILD/gen"

# Kotlin sources. R.java is passed in as a source so kotlinc can resolve R
# symbols, but it is compiled separately by javac below.
"$KOTLINC" -nowarn -jvm-target 1.8 -classpath "$ANDROID_JAR" -d "$BUILD/kt-classes" "$BUILD/gen/$PKG_PATH/R.java" "$SRC"/*.kt

# The aapt2-generated R class, still compiled as Java.
javac --release 8 -cp "$ANDROID_JAR" -d "$BUILD/classes" "$BUILD/gen/$PKG_PATH/R.java"

jar cvf "$BUILD/classes.jar" -C "$BUILD/kt-classes" . -C "$BUILD/classes" .

# R8 shrinks the Kotlin stdlib, which is 97.6% of the unshrunk dex. Our own
# classes are kept whole by r8-rules.txt, so nothing here rewrites app logic.
java -cp "$R8" com.android.tools.r8.R8 \
  --release --min-api 26 \
  --lib "$ANDROID_JAR" \
  --pg-conf "$RULES" \
  --output "$BUILD/apk" \
  "$BUILD/classes.jar" "$KOTLIN_STDLIB"

pushd "$BUILD/apk" > /dev/null
zip -uj "$BUILD/unsigned.apk" classes.dex
popd > /dev/null

$ZIPALIGN -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

$APKSIGNER sign --ks "$KEYSTORE" --ks-pass pass:motoancpass --key-pass pass:motoancpass --out "$OUT" "$BUILD/aligned.apk"
$APKSIGNER verify "$OUT"

echo "APK: $OUT"
ls -lh "$OUT"
