#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-/Users/diego/Library/Android/sdk}}"
: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME ANDROID_HOME
python_bin="${REDWOOD_PYTHON:-python3}"
"$python_bin" tools/fetch_runtime.py --tests
mkdir -p build/shooter-tests
classpath="deps/test/json-20240303.jar:$ANDROID_HOME/platforms/android-34/android.jar:deps/commons-math3.jar"
"$JAVA_HOME/bin/javac" --release 8 -cp "$classpath" -d build/shooter-tests src/org/redwoodarrows/{Geometry,TargetPreset,TargetCatalog,ShooterRoster,Practice,Store,SyncFiles,SyncHttp,SyncEngine}.java tests/SyncEngineTest.java
"$python_bin" tests/test_sync_server.py
