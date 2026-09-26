#!/usr/bin/env bash
# Rebuilds the watch face from the instrument: renders its layers on a connected Wear OS watch or
# emulator (454 px round, as the Wear app is tested on), regenerates res/raw/watchface.xml, checks
# every expression against the instrument's astronomy, then runs Google's format validator and
# memory-footprint check.
#
#   watchface/tools/build-assets.sh
#
# Needs Python 3 with Pillow, and wff-validator.jar and memory-footprint.jar from
# https://github.com/google/watchface/releases in $WFF_TOOLS (default ~/Android/wff-tools).
set -euo pipefail
cd "$(dirname "$0")/../.."
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
tools=${WFF_TOOLS:-$HOME/Android/wff-tools}
adb=${ADB:-adb}

./gradlew -q :wear:assembleDebug :wear:assembleDebugAndroidTest \
    :core:testDebugUnitTest --tests '*WatchFaceReferenceTest*'
"$adb" install -r -t wear/build/outputs/apk/debug/wear-debug.apk
"$adb" install -r -t wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk
"$adb" shell am instrument -w -e watchFaceAssets true \
    -e class com.metavirtuoso.sundial.wear.WatchFaceAssetsCapture \
    com.metavirtuoso.sundial.test/androidx.test.runner.AndroidJUnitRunner

assets=$(mktemp -d)
trap 'rm -rf "$assets"' EXIT
"$adb" pull /sdcard/Android/data/com.metavirtuoso.sundial/files/watchface "$assets" >/dev/null
python3 watchface/tools/generate.py "$assets/watchface" --check core/build/watchface-reference.json

"$JAVA_HOME/bin/java" -jar "$tools/wff-validator.jar" 1 watchface/src/main/res/raw/watchface.xml
./gradlew -q :watchface:assembleDebug
"$JAVA_HOME/bin/java" -jar "$tools/memory-footprint.jar" --schema-version 1 \
    --watch-face watchface/build/outputs/apk/debug/watchface-debug.apk \
    --ambient-limit-mb 10 --active-limit-mb 100
