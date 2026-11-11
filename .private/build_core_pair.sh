#!/usr/bin/env bash
set -euo pipefail
chmod +x gradlew
./gradlew -I .private/tor-ui-abi.gradle -I .private/core-wallet/include.gradle --no-daemon --no-watch-fs --max-workers=2 -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=1g' -Pkotlin.compiler.execution.strategy=in-process "$@" :app:assembleZcashtestnetFossDebug :app:assembleZcashtestnetFossDebugAndroidTest
mkdir -p core-artifact
cp app/build/outputs/apk/zcashtestnetFoss/debug/*.apk core-artifact/
cp app/build/outputs/apk/androidTest/zcashtestnetFoss/debug/*.apk core-artifact/
for apk in core-artifact/*androidTest*.apk; do
  python3 -B .private/fixture_classes.py "$apk" co.electriccoin.zcash.app.CoreWalletAcceptance
done
git rev-parse HEAD > core-artifact/source-sha.txt
(cd core-artifact && sha256sum ./*.apk > SHA256SUMS)
