#!/usr/bin/env bash
#
# Build a signed debug APK using only the Android SDK build tools -- no Gradle,
# no Android Gradle Plugin, no network access.
#
# The app has no third-party dependencies, so the whole build is four steps:
# link resources, compile Java, convert to dex, sign. That keeps it buildable
# on a machine that has nothing but a JDK and build-tools installed, which is
# also what makes it reproducible in CI.
#
# For the conventional path (Android Studio, ./gradlew assembleDebug) use the
# Gradle project alongside this script; both compile the same sources.
#
# Requirements:
#   - a JDK (javac, keytool)
#   - aapt2, apksigner, zipalign  (Android SDK build-tools, or Debian/Ubuntu:
#     apt install android-sdk-build-tools)
#   - a dexer: d8 (build-tools) or dalvik-exchange (apt install dalvik-exchange)
#   - an android.jar: set ANDROID_JAR, or have $ANDROID_HOME/platforms/*/
#
# Usage:
#   ./build.sh                 # debug APK at build/adblock-debug.apk
#   ANDROID_JAR=/path/android.jar ./build.sh
#   KEYSTORE=my.jks KEY_ALIAS=upload ./build.sh   # sign with your own key

set -euo pipefail

cd "$(dirname "$0")"

APP_ID="io.github.hakansilsupur.adblock"
MIN_SDK=24
TARGET_SDK=33
BUILD_DIR="build"
OUT_APK="${BUILD_DIR}/adblock-debug.apk"

say() { printf '\033[0;32m==>\033[0m %s\n' "$*"; }
die() { printf '\033[0;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

# --- locate the toolchain --------------------------------------------------

find_tool() {
    local name="$1"
    if command -v "$name" >/dev/null 2>&1; then
        command -v "$name"
        return 0
    fi
    # Newest build-tools directory that has it.
    if [ -n "${ANDROID_HOME:-}" ]; then
        local found
        found=$(ls -1d "${ANDROID_HOME}"/build-tools/*/"$name" 2>/dev/null | sort -V | tail -1 || true)
        if [ -n "$found" ]; then
            echo "$found"
            return 0
        fi
    fi
    return 1
}

AAPT2=$(find_tool aapt2) || die "aapt2 not found. Install Android build-tools, or set ANDROID_HOME."
APKSIGNER=$(find_tool apksigner) || die "apksigner not found. Install Android build-tools."
ZIPALIGN=$(find_tool zipalign) || die "zipalign not found. Install Android build-tools."
command -v javac >/dev/null || die "javac not found. Install a JDK (17 or newer is fine)."

# d8 is the modern dexer; dalvik-exchange is Debian's packaging of the older dx.
DEXER=""
if DEXER_PATH=$(find_tool d8); then
    DEXER="d8:${DEXER_PATH}"
elif DEXER_PATH=$(find_tool dalvik-exchange); then
    DEXER="dx:${DEXER_PATH}"
elif DEXER_PATH=$(find_tool dx); then
    DEXER="dx:${DEXER_PATH}"
else
    die "No dexer found. Install build-tools (d8) or 'apt install dalvik-exchange'."
fi

if [ -z "${ANDROID_JAR:-}" ]; then
    for candidate in \
        "${ANDROID_HOME:-/nonexistent}"/platforms/*/android.jar \
        /usr/lib/android-sdk/platforms/*/android.jar
    do
        [ -f "$candidate" ] && ANDROID_JAR="$candidate"
    done
fi
[ -n "${ANDROID_JAR:-}" ] && [ -f "$ANDROID_JAR" ] \
    || die "No android.jar found. Set ANDROID_JAR=/path/to/android.jar."

say "android.jar : ${ANDROID_JAR}"
say "dexer       : ${DEXER#*:}"

# --- assets: one copy of the lists, taken from the repository --------------

rm -rf "${BUILD_DIR}/assets" "${BUILD_DIR}/gen" "${BUILD_DIR}/classes" "${BUILD_DIR}/res"
mkdir -p "${BUILD_DIR}/assets" "${BUILD_DIR}/gen" "${BUILD_DIR}/classes" "${BUILD_DIR}/res"

if [ -f ../lists/blocklist.txt ]; then
    cp ../lists/blocklist.txt "${BUILD_DIR}/assets/blocklist.txt"
else
    cp ../lists/seed.txt "${BUILD_DIR}/assets/blocklist.txt"
fi
cp ../lists/allowlist.txt "${BUILD_DIR}/assets/allowlist.txt"
say "assets      : $(grep -cv '^#' "${BUILD_DIR}/assets/blocklist.txt" || true) bundled domains"

# --- resources -------------------------------------------------------------

say "Compiling resources"
"$AAPT2" compile --dir res -o "${BUILD_DIR}/res/resources.zip"

say "Linking resources"
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -A "${BUILD_DIR}/assets" \
    -R "${BUILD_DIR}/res/resources.zip" \
    --auto-add-overlay \
    --java "${BUILD_DIR}/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    -o "${BUILD_DIR}/base.apk"

# --- Java ------------------------------------------------------------------

say "Compiling Java"
# --release 8 keeps the bytecode within what every supported dexer accepts.
find src "${BUILD_DIR}/gen" -name '*.java' > "${BUILD_DIR}/sources.txt"
javac \
    --release 8 \
    -nowarn \
    -Xlint:-options \
    -classpath "$ANDROID_JAR" \
    -d "${BUILD_DIR}/classes" \
    @"${BUILD_DIR}/sources.txt"

say "Converting to dex"
case "${DEXER%%:*}" in
    d8)
        "${DEXER#*:}" --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
            --output "${BUILD_DIR}" \
            $(find "${BUILD_DIR}/classes" -name '*.class')
        ;;
    dx)
        "${DEXER#*:}" --dex --min-sdk-version="$MIN_SDK" \
            --output="${BUILD_DIR}/classes.dex" "${BUILD_DIR}/classes"
        ;;
esac
[ -f "${BUILD_DIR}/classes.dex" ] || die "the dexer produced no classes.dex"

# --- package and sign ------------------------------------------------------

say "Packaging"
cp "${BUILD_DIR}/base.apk" "${BUILD_DIR}/unsigned.apk"
# -j keeps classes.dex at the archive root, where the runtime expects it.
zip -q -j "${BUILD_DIR}/unsigned.apk" "${BUILD_DIR}/classes.dex"
"$ZIPALIGN" -f 4 "${BUILD_DIR}/unsigned.apk" "${BUILD_DIR}/aligned.apk"

KEYSTORE="${KEYSTORE:-${BUILD_DIR}/debug.keystore}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${KEY_PASS:-android}"
STORE_PASS="${STORE_PASS:-android}"

if [ ! -f "$KEYSTORE" ]; then
    say "Creating a debug keystore"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=AdBlock Debug, O=AdBlock, C=US" >/dev/null 2>&1
fi

say "Signing"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:${STORE_PASS}" \
    --key-pass "pass:${KEY_PASS}" \
    --ks-key-alias "$KEY_ALIAS" \
    --out "$OUT_APK" \
    "${BUILD_DIR}/aligned.apk"

"$APKSIGNER" verify --verbose "$OUT_APK" | grep -E "^Verified using|^Verifies" || true

SIZE=$(du -h "$OUT_APK" | cut -f1)
say "Built ${OUT_APK} (${SIZE})"
echo
echo "Install on a connected device:  adb install -r ${OUT_APK}"
echo "Or copy it to the phone and open it (allow installing unknown apps)."
