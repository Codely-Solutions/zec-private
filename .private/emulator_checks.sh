#!/usr/bin/env bash
# Isolated debug variant; production checks run independently on the same source.
set -euo pipefail
chmod +x gradlew
./gradlew -I .private/tor-ui-abi.gradle -I .private/task-timing.gradle --profile --no-daemon --no-watch-fs --max-workers=2 -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=1g' -Pkotlin.compiler.execution.strategy=in-process :app:assembleZcashmainnetFossDebug
mkdir -p emulator-artifact
cp app/build/outputs/apk/zcashmainnetFoss/debug/*.apk emulator-artifact/
git rev-parse HEAD > emulator-artifact/source-sha.txt
(cd emulator-artifact && sha256sum ./*.apk source-sha.txt > SHA256SUMS)
