#!/bin/bash
# Host checks for the separate Review package; never signs or installs an APK.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-/Users/diego/Library/Android/sdk}}"
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME ANDROID_HOME
python_bin="${REDWOOD_PYTHON:-python3}"
"$python_bin" tools/fetch_runtime.py --tests
mkdir -p build/review/classes build/review/tests
classpath="$ANDROID_HOME/platforms/android-34/android.jar:deps/arcore.jar:deps/opencv.jar:deps/annotation.jar:deps/commons-math3.jar"
"$JAVA_HOME/bin/javac" --release 8 -encoding UTF-8 -cp "$classpath" -d build/review/classes src/org/redwoodarrows/*.java src/com/google/ar/core/examples/java/common/rendering/*.java
test_classpath="build/review/classes:deps/test/json-20240303.jar:$classpath"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$test_classpath" -d build/review/tests tests/*.java
for name in CenterDistance PhotoReviewState LibrarySnapshot LibraryFilter ActivityStats; do
    "$JAVA_HOME/bin/java" -cp "build/review/tests:$test_classpath" "org.redwoodarrows.${name}Test"
done
"$python_bin" tests/test_review_sync_guard.py
