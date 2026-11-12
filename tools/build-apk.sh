#!/usr/bin/env bash
# Gradle-free APK build for OPCam Pro.
#
#   tools/bootstrap.sh   # once: downloads a JRE, aapt2, ecj, d8, apksigner, android.jar
#   tools/build-apk.sh   # compiles resources + Java, dexes, packages, signs -> release/
#
# Environment overrides:
#   TOOLCHAIN   directory created by bootstrap.sh   (default: tools/.toolchain, falls back to ~/.cache/toolchain)
#   KEYSTORE / KS_PASS / KEY_ALIAS / KEY_PASS         release signing (defaults to the bundled debug keystore)
#   VERSION_CODE / VERSION_NAME                       default 1 / 1.0.0
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="${TOOLCHAIN:-$ROOT/tools/.toolchain}"
if [ ! -d "$TC" ] && [ -d "$HOME/.cache/toolchain" ]; then
  TC="$HOME/.cache/toolchain"
fi
if [ ! -x "$TC/jdk/bin/java" ]; then
  echo "Toolchain not found at $TC - run tools/bootstrap.sh first" >&2
  exit 1
fi

JAVA="$TC/jdk/bin/java"
# Newer JDKs print native-access warnings for apksigner's bundled conscrypt; silence when supported.
JAVA_FLAGS=""
if "$JAVA" --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then
  JAVA_FLAGS="--enable-native-access=ALL-UNNAMED"
fi
AAPT2="$TC/bin/aapt2"
AJAR="$TC/platforms/android-35/android.jar"
ECJ="$TC/lib/ecj.jar"
D8="$TC/lib/d8.jar"
APKSIGNER="$TC/lib/apksigner.jar"
STUBS="$TC/lib/ecj-android-stubs.jar"

PKG="io.github.version9133.opcam"
MIN_SDK=26
TARGET_SDK=35
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"

APP="$ROOT/app/src/main"
OUT="$ROOT/build-nogradle"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$ROOT/release"

echo "==> aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "==> aapt2 link"
# The manifest in the repo is package-less (Gradle injects it from the namespace); do the same here.
sed "s#<manifest #<manifest package=\"$PKG\" #" "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT2" link \
  -I "$AJAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  --no-version-vectors \
  -o "$OUT/unsigned.apk" \
  "$OUT/res.zip"

echo "==> javac (ecj)"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVA" -jar "$ECJ" \
  -source 8 -target 8 -encoding UTF-8 -proc:none -nowarn -g \
  -bootclasspath "$AJAR:$STUBS" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "==> d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$JAVA" -cp "$D8" com.android.tools.r8.D8 \
  --release --min-api "$MIN_SDK" --lib "$AJAR" \
  --output "$OUT/dex" \
  @"$OUT/classes.txt"

echo "==> package"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex )

echo "==> sign"
# Default: the shared, non-secret debug key in keystore/ (see keystore/README.md for release signing).
KEYSTORE="${KEYSTORE:-$ROOT/keystore/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-opcam-debug}"
KEY_PASS="${KEY_PASS:-$KS_PASS}"
APK_NAME="OPCamPro-v${VERSION_NAME}.apk"
"$JAVA" $JAVA_FLAGS -jar "$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASS" \
  --min-sdk-version "$MIN_SDK" --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$ROOT/release/$APK_NAME" "$OUT/unsigned.apk"
rm -f "$ROOT/release/$APK_NAME.idsig"
"$JAVA" $JAVA_FLAGS -jar "$APKSIGNER" verify --min-sdk-version "$MIN_SDK" "$ROOT/release/$APK_NAME"

ls -l "$ROOT/release/$APK_NAME"
echo "OK -> release/$APK_NAME"
