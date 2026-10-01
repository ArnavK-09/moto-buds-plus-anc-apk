#!/bin/bash
# Provisions the build and lint toolchain into .toolchain/ (gitignored).
#
#   ./toolchain.sh              # ensure everything is present
#   ./toolchain.sh ktlint ARGS  # run ktlint with ARGS (fetching it if needed)
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOL="$ROOT/.toolchain"

KOTLIN_VERSION="2.4.20"
KOTLIN_SHA256="59e9ca74c7904ef2c122b12114937673ccce68de820a663f0ed66ccf8799e0b7"
KTLINT_VERSION="1.8.0"
KTLINT_SHA256="3722801dd119b96a2fbeda0b9d66f173994f249998c87bcf2274b51977aa8f77"
R8_VERSION="8.13.25"
R8_SHA256="0d4f2f68c4fcbc9434eff122f41feb6fb1d98efd2faa530cec1abacab1cea03d"

KOTLINC="$TOOL/kotlinc/bin/kotlinc"
KTLINT="$TOOL/ktlint-$KTLINT_VERSION/bin/ktlint"
R8="$TOOL/r8-$R8_VERSION.jar"

fetch() { # url sha256 dest
  local url="$1" sha="$2" dest="$3"
  echo "==> fetching $(basename "$dest")"
  mkdir -p "$(dirname "$dest")"
  curl -fsSL -o "$dest" "$url"
  echo "$sha  $dest" | sha256sum -c - > /dev/null
}

ensure_kotlin() {
  [ -x "$KOTLINC" ] && return 0
  local zip="$TOOL/kotlin-compiler-$KOTLIN_VERSION.zip"
  fetch \
    "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip" \
    "$KOTLIN_SHA256" "$zip"
  unzip -q -o "$zip" -d "$TOOL"
  rm -f "$zip"
  chmod +x "$KOTLINC"
}

ensure_ktlint() {
  [ -x "$KTLINT" ] && return 0
  local zip="$TOOL/ktlint-$KTLINT_VERSION.zip"
  fetch \
    "https://github.com/pinterest/ktlint/releases/download/$KTLINT_VERSION/ktlint-$KTLINT_VERSION.zip" \
    "$KTLINT_SHA256" "$zip"
  unzip -q -o "$zip" -d "$TOOL"
  rm -f "$zip"
  chmod +x "$KTLINT"
}

# R8 shrinking the Kotlin stdlib, which is 97.6% of the unshrunk dex.
# Fetched from Google Maven. Newer than the d8 in the android-14 toolchain,
# which predates Kotlin 2.4 and cannot parse its metadata.
ensure_r8() {
  [ -f "$R8" ] && return 0
  fetch \
    "https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar" \
    "$R8_SHA256" "$R8"
}

case "${1:-all}" in
  kotlin) ensure_kotlin ;;
  r8) ensure_r8 ;;
  ktlint)
    shift
    ensure_ktlint
    exec "$KTLINT" "$@"
    ;;
  *)
    ensure_kotlin
    ensure_ktlint
    ensure_r8
    echo "toolchain ready"
    ;;
esac
