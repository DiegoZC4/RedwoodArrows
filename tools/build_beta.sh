#!/bin/bash
# Portable private-beta build; no personal images, signing keys or passwords in Git.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-/Users/diego/Library/Android/sdk}}"
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
toolchain="$ANDROID_HOME/build-tools/36.1.0"
platform="$ANDROID_HOME/platforms/android-34/android.jar"
python_bin="${REDWOOD_PYTHON:-python3}"
"$python_bin" tools/fetch_runtime.py
mkdir -p build/beta/classes build/beta/dex build/beta/apk/lib/arm64-v8a
classpath="$platform:deps/arcore.jar:deps/opencv.jar:deps/annotation.jar:deps/commons-math3.jar"
find src -name '*.java' -print > build/beta/sources.txt
javac --release 8 -cp "$classpath" -d build/beta/classes @build/beta/sources.txt
jar cf build/beta/classes.jar -C build/beta/classes .
"$toolchain/d8" --min-api 31 --lib "$platform" --output build/beta/dex build/beta/classes.jar deps/arcore.jar deps/opencv.jar deps/annotation.jar deps/commons-math3.jar
# Release APK is non-debuggable; local development build remains separate.
sed 's/android:debuggable="true"/android:debuggable="false"/' AndroidManifest.xml > build/beta/AndroidManifest.xml
"$toolchain/aapt" package -f -M build/beta/AndroidManifest.xml -I "$platform" -A assets -S res -F build/beta/unsigned.apk
cp build/beta/dex/classes*.dex build/beta/apk/
cp deps/lib/arm64-v8a/*.so build/beta/apk/lib/arm64-v8a/
(cd build/beta/apk && zip -q -r ../unsigned.apk .)
"$toolchain/zipalign" -f 4 build/beta/unsigned.apk build/beta/aligned.apk
: "${REDWOOD_KEYSTORE:?Set REDWOOD_KEYSTORE to your private signing keystore}"
: "${REDWOOD_STORE_PASSWORD:?Set the private keystore password in the environment}"
: "${REDWOOD_KEY_PASSWORD:=$REDWOOD_STORE_PASSWORD}"
: "${REDWOOD_KEY_ALIAS:=redwood}"
export REDWOOD_STORE_PASSWORD REDWOOD_KEY_PASSWORD
"$toolchain/apksigner" sign --ks "$REDWOOD_KEYSTORE" --ks-key-alias "$REDWOOD_KEY_ALIAS" --ks-pass env:REDWOOD_STORE_PASSWORD --key-pass env:REDWOOD_KEY_PASSWORD --out build/beta/RedwoodArrows.apk build/beta/aligned.apk
"$toolchain/apksigner" verify build/beta/RedwoodArrows.apk
"$python_bin" tools/check_apk_budget.py build/beta/RedwoodArrows.apk
echo 'Private beta: build/beta/RedwoodArrows.apk'
