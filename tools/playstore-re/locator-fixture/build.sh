#!/bin/sh
# Builds ../../../core/src/test/resources/playstore-locator-fixture.dex from the sources here.
# Needs javac, d8 (Android build-tools) and the platform's android.jar (ANDROID_HOME).
set -e
here=$(cd "$(dirname "$0")" && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
android_jar=$(ls "$ANDROID_HOME"/platforms/android-*/android.jar | tail -1)
javac --release 8 -cp "$android_jar" -d "$out/classes" "$here"/fx/*.java
d8 --min-api 29 --lib "$android_jar" --output "$out" "$out"/classes/fx/*.class
cp "$out/classes.dex" "$here/../../../core/src/test/resources/playstore-locator-fixture.dex"
