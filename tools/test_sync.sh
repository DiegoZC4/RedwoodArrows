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
"$JAVA_HOME/bin/javac" --release 8 -cp "$classpath" -d build/shooter-tests src/org/redwoodarrows/ReleaseUpdate.java tests/ReleaseUpdateTest.java
"$JAVA_HOME/bin/java" -cp "build/shooter-tests:$classpath" org.redwoodarrows.ReleaseUpdateTest
"$JAVA_HOME/bin/javac" -cp "$classpath" -d build/shooter-tests src/org/redwoodarrows/{Geometry,TargetPreset,TargetCatalog,ShooterRoster,Practice,Store,BulkLabels,SyncFiles,SyncHttp,SyncEngine,SyncWakeup,SyncDiagnostics,TargetSelection,PhotoAssignments,DistanceSteps}.java tests/SyncEngineTest.java tests/SyncWakeupTest.java tests/TargetSelectionTest.java tests/PhotoAssignmentsTest.java tests/DistanceStepsTest.java
"$JAVA_HOME/bin/java" -cp "build/shooter-tests:$classpath" org.redwoodarrows.SyncWakeupTest
"$JAVA_HOME/bin/java" -cp "build/shooter-tests:$classpath" org.redwoodarrows.TargetSelectionTest
"$JAVA_HOME/bin/java" -cp "build/shooter-tests:$classpath" org.redwoodarrows.PhotoAssignmentsTest
"$JAVA_HOME/bin/java" -cp "build/shooter-tests:$classpath" org.redwoodarrows.DistanceStepsTest
"$JAVA_HOME/bin/javac" --release 8 -cp "build/shooter-tests:$classpath" -d build/shooter-tests src/org/redwoodarrows/CaptureDefaults.java tests/CaptureDefaultsTest.java
"$JAVA_HOME/bin/java" -cp "build/shooter-tests:$classpath" org.redwoodarrows.CaptureDefaultsTest
"$JAVA_HOME/bin/javac" --release 8 -cp "build/shooter-tests:$classpath" -d build/shooter-tests src/org/redwoodarrows/ProgressStats.java src/org/redwoodarrows/ConsistencyStats.java src/org/redwoodarrows/LabelCoverage.java tests/ConsistencyStatsTest.java tests/ConfidenceStatsTest.java tests/BulkLabelsTest.java
"$JAVA_HOME/bin/java" -cp "build/shooter-tests:$classpath" org.redwoodarrows.ConsistencyStatsTest
"$JAVA_HOME/bin/java" -cp "build/shooter-tests:$classpath" org.redwoodarrows.ConfidenceStatsTest
"$JAVA_HOME/bin/java" -cp "build/shooter-tests:$classpath" org.redwoodarrows.BulkLabelsTest
"$python_bin" tests/test_sync_server.py
