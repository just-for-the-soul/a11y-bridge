#!/bin/bash
# Build the OpenClaw A11y APK without Gradle
# Requires: Android SDK (ANDROID_HOME or ~/Android/Sdk), javac, keytool
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BUILD_TOOLS="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
OUT="$SCRIPT_DIR/build"
APK_OUT="$SCRIPT_DIR"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
APKSIGNER="$BUILD_TOOLS/apksigner"

echo "=== OpenClaw A11y APK Builder ==="
echo "SDK: $SDK"

# Verify tools exist
for tool in "$AAPT2" "$D8" "$APKSIGNER"; do
    if [ ! -f "$tool" ]; then
        echo "ERROR: Missing tool: $tool"
        exit 1
    fi
done

if [ ! -f "$PLATFORM" ]; then
    echo "ERROR: Missing platform: $PLATFORM"
    exit 1
fi

# Clean
rm -rf "$OUT"
mkdir -p "$OUT"/{compiled,gen,classes,dex}
mkdir -p "$APK_OUT"

echo "[1/6] Compiling resources..."
$AAPT2 compile --dir "$SCRIPT_DIR/res" -o "$OUT/compiled/"

echo "[2/6] Linking resources..."
$AAPT2 link \
    --auto-add-overlay \
    -I "$PLATFORM" \
    --manifest "$SCRIPT_DIR/AndroidManifest.xml" \
    --java "$OUT/gen" \
    -o "$OUT/base.apk" \
    "$OUT"/compiled/*.flat

echo "[3/6] Compiling Java..."
# Find all java source files
find "$SCRIPT_DIR/src" -name "*.java" > "$OUT/sources.txt"
# Add generated R.java
find "$OUT/gen" -name "*.java" >> "$OUT/sources.txt"

javac \
    -source 11 -target 11 \
    -classpath "$PLATFORM" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt" \
    2>&1

echo "[4/6] Converting to DEX..."
# Find all class files
find "$OUT/classes" -name "*.class" > "$OUT/classfiles.txt"
$D8 \
    --lib "$PLATFORM" \
    --output "$OUT/dex" \
    --min-api 24 \
    $(cat "$OUT/classfiles.txt")

echo "[5/6] Packaging APK..."
# Extract base apk, add dex, repack
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cd "$OUT/dex"
zip -u "$OUT/unsigned.apk" classes.dex
cd "$SCRIPT_DIR"

# Zipalign (optional, apksigner handles it)
if [ -f "$BUILD_TOOLS/zipalign" ]; then
    "$BUILD_TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
    mv "$OUT/aligned.apk" "$OUT/unsigned.apk"
fi

echo "[6/6] Signing APK..."
KEYSTORE="$SCRIPT_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkey -v \
        -keystore "$KEYSTORE" \
        -alias debug \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=OpenClaw,O=OpenClaw,L=Unknown,ST=Unknown,C=US"
fi

$APKSIGNER sign \
    --ks "$KEYSTORE" \
    --ks-key-alias debug \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$APK_OUT/openclaw-a11y.apk" \
    "$OUT/unsigned.apk"

# Show result
APK_SIZE=$(du -h "$APK_OUT/openclaw-a11y.apk" | cut -f1)
echo ""
echo "✅ Build complete: $APK_OUT/openclaw-a11y.apk ($APK_SIZE)"
echo ""
echo "Install:  adb install $APK_OUT/openclaw-a11y.apk"
echo "Enable:   Settings → Accessibility → OpenClaw A11y → ON"
echo "Test:     curl http://localhost:7333/ping  (via adb forward)"
