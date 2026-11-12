#!/usr/bin/env bash
# Downloads a self-contained Android build toolchain (no Android SDK, no Gradle) into
# tools/.toolchain so that tools/build-apk.sh can produce a signed APK.
#
#   Java 17+ runtime  : system java if new enough, otherwise the portable jdk4py JRE from PyPI
#   aapt2             : aaptjs3 npm package (linux x64 / macOS x64 binaries)
#   ecj, d8, apksigner: @drxiaozhi/minapk npm package
#   android.jar (API35): Sable/android-platforms on GitHub
#   lambda stubs      : compiled from tools/stubs (ECJ needs java.lang.invoke.LambdaMetafactory to exist)
#
# Only needs: bash, curl, tar, unzip.   Override the destination with TOOLCHAIN=/some/dir.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="${TOOLCHAIN:-$ROOT/tools/.toolchain}"
DL="$TC/dl"
mkdir -p "$TC/bin" "$TC/lib" "$TC/platforms/android-35" "$DL"

JDK4PY_X86_64="https://files.pythonhosted.org/packages/02/5a/b572f441714a420d65805cbf8806d9b78a8ab70060ed251310661c0b934c/jdk4py-25.0.2.1-py3-none-manylinux_2_17_x86_64.whl"
JDK4PY_AARCH64="https://files.pythonhosted.org/packages/52/fe/e382f79e1fc1f7dfa04c4a7df765203f780590221c26ddff79a1e56aec76/jdk4py-25.0.2.1-py3-none-manylinux_2_17_aarch64.whl"
JDK4PY_MAC_ARM="https://files.pythonhosted.org/packages/95/b8/398b8cff51b1109cdaa424dc4e62ef60acb89e5eb8041fb16a40bc005b49/jdk4py-25.0.2.1-py3-none-macosx_11_0_arm64.whl"
JDK4PY_MAC_X86="https://files.pythonhosted.org/packages/97/c3/3883815de1ecb3dca14f6ada6b38c6e9bb7fc7c3585b2b1924970d4d33fc/jdk4py-25.0.2.1-py3-none-macosx_10_9_x86_64.whl"
AAPTJS3="https://registry.npmjs.org/aaptjs3/-/aaptjs3-2.0.2.tgz"
MINAPK="https://registry.npmjs.org/@drxiaozhi/minapk/-/minapk-0.5.1.tgz"
ANDROID_JAR_RAW="https://raw.githubusercontent.com/Sable/android-platforms/master/android-35/android.jar"
ANDROID_JAR_API="https://api.github.com/repos/Sable/android-platforms/git/blobs/2d6652edf0fb6046a23638f3a2f2c6246c745b08"
ANDROID_JAR_SIZE=27092450

fetch() { # url dest
  if [ ! -s "$2" ]; then
    echo "  downloading $(basename "$2")"
    curl -fL --retry 3 --progress-bar -o "$2.part" "$1"
    mv "$2.part" "$2"
  fi
}

java_major() { # prints major version of the given java binary, or 0
  "$1" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); if (v[1] == "1") print v[2]; else print v[1]; exit}' || echo 0
}

OS="$(uname -s)"
ARCH="$(uname -m)"

# ---------------------------------------------------------------- 1. Java runtime
echo "==> Java"
JAVA_BIN=""
for candidate in "${JAVA_HOME:-/nonexistent}/bin/java" "$(command -v java || true)"; do
  if [ -x "$candidate" ] && [ "$(java_major "$candidate")" -ge 17 ] 2>/dev/null; then
    JAVA_BIN="$candidate"
    break
  fi
done
if [ -n "$JAVA_BIN" ]; then
  JHOME="$(cd "$(dirname "$(readlink -f "$JAVA_BIN")")/.." && pwd)"
  echo "  using system Java at $JHOME"
  rm -rf "$TC/jdk" && ln -s "$JHOME" "$TC/jdk"
else
  case "$OS-$ARCH" in
    Linux-x86_64)  URL="$JDK4PY_X86_64" ;;
    Linux-aarch64) URL="$JDK4PY_AARCH64" ;;
    Darwin-arm64)  URL="$JDK4PY_MAC_ARM" ;;
    Darwin-x86_64) URL="$JDK4PY_MAC_X86" ;;
    *) echo "No portable JRE for $OS-$ARCH: install Java 17+ and re-run" >&2; exit 1 ;;
  esac
  fetch "$URL" "$DL/jdk4py.whl"
  rm -rf "$TC/jdk4py" && mkdir -p "$TC/jdk4py"
  unzip -q "$DL/jdk4py.whl" 'jdk4py/java-runtime/*' -d "$TC/jdk4py"
  chmod +x "$TC/jdk4py/jdk4py/java-runtime/bin/"* 2>/dev/null || true
  rm -rf "$TC/jdk" && ln -s "$TC/jdk4py/jdk4py/java-runtime" "$TC/jdk"
fi
"$TC/jdk/bin/java" -version 2>&1 | head -1

# ---------------------------------------------------------------- 2. aapt2
echo "==> aapt2"
if [ ! -x "$TC/bin/aapt2" ]; then
  case "$OS" in
    Linux)  SUB="x64/linux/aapt2" ;;
    Darwin) SUB="x64/darwin/aapt2" ;;
    *) echo "aapt2 binary for $OS not bundled - put one at $TC/bin/aapt2" >&2; exit 1 ;;
  esac
  fetch "$AAPTJS3" "$DL/aaptjs3.tgz"
  tar -xzf "$DL/aaptjs3.tgz" -C "$DL" "package/bin/$SUB"
  mv "$DL/package/bin/$SUB" "$TC/bin/aapt2" && chmod +x "$TC/bin/aapt2"
  rm -rf "$DL/package"
fi
"$TC/bin/aapt2" version 2>&1 | head -1

# ---------------------------------------------------------------- 3. ecj / d8 / apksigner
echo "==> ecj, d8, apksigner"
if [ ! -s "$TC/lib/ecj.jar" ] || [ ! -s "$TC/lib/d8.jar" ] || [ ! -s "$TC/lib/apksigner.jar" ]; then
  fetch "$MINAPK" "$DL/minapk.tgz"
  tar -xzf "$DL/minapk.tgz" -C "$DL" package/tools/ecj-3.45.0.jar package/tools/d8.jar package/tools/apksigner.jar
  mv "$DL/package/tools/ecj-3.45.0.jar" "$TC/lib/ecj.jar"
  mv "$DL/package/tools/d8.jar" "$TC/lib/d8.jar"
  mv "$DL/package/tools/apksigner.jar" "$TC/lib/apksigner.jar"
  rm -rf "$DL/package"
fi
"$TC/jdk/bin/java" -jar "$TC/lib/ecj.jar" -version 2>&1 | head -1
"$TC/jdk/bin/java" -cp "$TC/lib/d8.jar" com.android.tools.r8.D8 --version 2>&1 | head -1

# ---------------------------------------------------------------- 4. android.jar (API 35)
echo "==> android.jar (API 35)"
AJAR="$TC/platforms/android-35/android.jar"
if [ ! -s "$AJAR" ] || [ "$(wc -c < "$AJAR")" -ne "$ANDROID_JAR_SIZE" ]; then
  rm -f "$AJAR"
  if ! curl -fL --retry 2 --progress-bar -o "$AJAR.part" "$ANDROID_JAR_RAW" 2>/dev/null; then
    echo "  raw.githubusercontent.com unreachable, using the GitHub API"
    curl -fL --retry 3 --progress-bar -H "Accept: application/vnd.github.raw+json" -o "$AJAR.part" "$ANDROID_JAR_API"
  fi
  mv "$AJAR.part" "$AJAR"
fi
[ "$(wc -c < "$AJAR")" -eq "$ANDROID_JAR_SIZE" ] || { echo "android.jar size mismatch" >&2; exit 1; }
echo "  ok ($(wc -c < "$AJAR") bytes)"

# ---------------------------------------------------------------- 5. ECJ lambda stubs
echo "==> ecj-android-stubs.jar"
if [ ! -s "$TC/lib/ecj-android-stubs.jar" ]; then
  rm -rf "$TC/stubs-out" && mkdir -p "$TC/stubs-out"
  "$TC/jdk/bin/java" -jar "$TC/lib/ecj.jar" -source 8 -target 8 -proc:none -nowarn \
    -d "$TC/stubs-out" "$ROOT/tools/stubs/java/lang/invoke/"*.java
  ( cd "$TC/stubs-out" && zip -q -r "$TC/lib/ecj-android-stubs.jar" java )
  rm -rf "$TC/stubs-out"
fi
echo "  ok"

echo
echo "Toolchain ready in $TC"
echo "Next: tools/build-apk.sh"
