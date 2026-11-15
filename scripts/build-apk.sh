#!/system/bin/sh
# Build a signed DSH Android APK on-device (or on any Linux host) without
# Gradle: aapt2 -> javac -> d8 -> package_apk.py -> signature.
#
# The signing step uses apksigner. If that produces an unreadable archive
# (seen on some Android/Termux runtimes, where the large-file copy is cut
# short), the script falls back to scripts/sign-apk-v2.py, which inserts an
# APK Signature Scheme v2 block in place. Either way the output is signed with
# the project key and verified before the script exits.
#
# Requires:
#   * Android SDK platforms/android-36/android.jar and build-tools d8/apksigner
#   * a JDK 17+ (the Termux OpenJDK 21 build works)
#   * an Android-native aapt2 (Termux's `aapt2` package)
#   * python3
#   * the generated payload under android/app/src/main/{assets,jniLibs}
#
# Environment overrides: ANDROID_SDK_ROOT, JAVA_HOME, AAPT2, VERSION_NAME,
# VERSION_CODE.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_SDK_ROOT:-/data/user/0/com.opencode.android/files/usr/opt/android-sdk}
JAVA_HOME=${JAVA_HOME:-/data/user/0/com.opencode.android/files/usr/lib/jvm/java-21-openjdk}
BUILD_TOOLS=${BUILD_TOOLS:-$SDK/build-tools/36.0.0}
PLATFORM=${PLATFORM:-$SDK/platforms/android-36/android.jar}
AAPT2_BIN=${AAPT2:-/data/data/com.termux/files/usr/bin/aapt2}
VERSION_NAME=${VERSION_NAME:-0.1.3}
VERSION_CODE=${VERSION_CODE:-4}
MIN_SDK=${MIN_SDK:-24}
TARGET_SDK=${TARGET_SDK:-36}

APP=$ROOT/android/app
OUT=$APP/build
DIST=$ROOT/dist
KEYSTORE_DIR=$ROOT/android/keystore
KEYSTORE=$KEYSTORE_DIR/dsh-release.jks
STORE_PASS=dsh-android-release

[ -x "$JAVA_HOME/bin/java" ] || { echo "JDK not found: $JAVA_HOME" >&2; exit 1; }
[ -f "$PLATFORM" ] || { echo "android.jar not found: $PLATFORM" >&2; exit 1; }
[ -x "$AAPT2_BIN" ] || { echo "aapt2 not found: $AAPT2_BIN" >&2; exit 1; }
[ -f "$BUILD_TOOLS/lib/d8.jar" ] || { echo "d8.jar not found under $BUILD_TOOLS" >&2; exit 1; }
[ -f "$BUILD_TOOLS/lib/apksigner.jar" ] || { echo "apksigner.jar not found under $BUILD_TOOLS" >&2; exit 1; }
[ -f "$APP/src/main/assets/dsh-engine.tar.gz" ] || {
  echo "engine payload missing; run: node engine/assemble-engine.mjs" >&2; exit 1; }
[ -f "$APP/src/main/jniLibs/arm64-v8a/libnode.so" ] || {
  echo "native runtime missing; run: node engine/assemble-native.mjs" >&2; exit 1; }

export JAVA_HOME
PATH=$JAVA_HOME/bin:$PATH

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/res" "$DIST"

echo "[1/6] compiling resources"
"$AAPT2_BIN" compile --dir "$APP/src/main/res" -o "$OUT/res/resources.zip"

echo "[2/6] linking resources"
"$AAPT2_BIN" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$APP/src/main/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$OUT/res/resources.zip"

echo "[3/6] compiling Java"
find "$APP/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 17 -Xlint:-options -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] dexing"
"$JAVA_HOME/bin/jar" cf "$OUT/classes.jar" -C "$OUT/classes" .
"$JAVA_HOME/bin/java" -Xmx2g -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api "$MIN_SDK" --lib "$PLATFORM" \
  --output "$OUT/dex" "$OUT/classes.jar"

echo "[5/6] packaging"
python3 "$ROOT/scripts/package_apk.py" \
  --base "$OUT/base.apk" \
  --out "$OUT/unsigned.apk" \
  --dex "$OUT/dex/classes.dex" \
  --assets "$APP/src/main/assets" \
  --libs "$APP/src/main/jniLibs/arm64-v8a" \
  --lib-path "lib/arm64-v8a"

if [ ! -f "$KEYSTORE" ]; then
  echo "[6/6] generating release keystore"
  mkdir -p "$KEYSTORE_DIR"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" \
    -alias dsh -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
    -dname "CN=DSH Android, OU=Community Build, O=DSH Android, C=US" >/dev/null 2>&1
fi

echo "[6/6] signing"
APK=$DIST/DSH-Android-$VERSION_NAME.apk
rm -f "$APK"

sign_with_build_tools() {
  "$JAVA_HOME/bin/java" -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" --ks-key-alias dsh \
    --ks-pass "pass:$STORE_PASS" --key-pass "pass:$STORE_PASS" \
    --min-sdk-version "$MIN_SDK" --v1-signing-enabled false \
    --v2-signing-enabled true --v3-signing-enabled true \
    --v4-signing-enabled false \
    --out "$APK" "$OUT/unsigned.apk"
}

apk_is_readable() {
  python3 - "$1" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    sys.exit(0 if archive.testzip() is None else 1)
PY
}

# apksigner's large-file copy is unreliable on some Android/Termux runtimes:
# it can exit 0 after writing a truncated ZIP. Validate the archive and, if it
# is broken, sign with scripts/sign-apk-v2.py, which writes the v2 block
# directly. Both paths keep the same key and certificate.
if ! sign_with_build_tools || ! apk_is_readable "$APK"; then
  echo "apksigner produced an unreadable archive; using the built-in v2 signer"
  rm -f "$APK"
  mkdir -p "$OUT/keytool-classes"
  "$JAVA_HOME/bin/javac" -d "$OUT/keytool-classes" "$ROOT/scripts/DumpSigningKey.java"
  "$JAVA_HOME/bin/java" -cp "$OUT/keytool-classes" DumpSigningKey \
    "$KEYSTORE" dsh "$STORE_PASS" "$STORE_PASS" \
    "$OUT/signing-key.pk8" "$OUT/signing-cert.der" "$OUT/signing-public.der"
  python3 "$ROOT/scripts/sign-apk-v2.py" sign \
    --key "$OUT/signing-key.pk8" --cert "$OUT/signing-cert.der" \
    --public "$OUT/signing-public.der" \
    --in "$OUT/unsigned.apk" --out "$APK"
  rm -f "$OUT/signing-key.pk8" "$OUT/signing-cert.der" "$OUT/signing-public.der"
fi

apk_is_readable "$APK" || { echo "signing failed: $APK is not a valid ZIP" >&2; exit 1; }
python3 "$ROOT/scripts/sign-apk-v2.py" verify "$APK"
"$JAVA_HOME/bin/java" -jar "$BUILD_TOOLS/lib/apksigner.jar" verify --print-certs "$APK" | head -5
echo
echo "APK: $APK"
echo "size: $(wc -c < "$APK") bytes"
echo "sha256: $(sha256sum "$APK" | cut -d' ' -f1)"
