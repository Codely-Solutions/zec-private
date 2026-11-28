#!/usr/bin/env bash
# One Gradle invocation for release tests, lint and assembly after preflight.
set -euo pipefail
chmod +x gradlew
tasks=(:wallet-foundation-lib:test :ui-design-lib:testReleaseUnitTest --tests '*MaterialThemeRolesTest' :preference-impl-android-lib:testReleaseUnitTest :feature-ledger:testZcashmainnetFossReleaseUnitTest :feature-viewing-key:testZcashmainnetFossReleaseUnitTest :feature-migration:testZcashmainnetFossReleaseUnitTest :app:privateRuntimeInventory :ui-lib:testZcashmainnetFossReleaseUnitTest :app:lintZcashmainnetFossRelease :app:assembleZcashmainnetFossRelease)
init=(-I .private/runtime-inventory.gradle -I .private/robolectric-ci.gradle -I .private/task-timing.gradle)
start=$(date +%s)
ZEC_RELEASE_ROBOLECTRIC_DIR=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/zec-release-robolectric.XXXXXXXX")
export ZEC_RELEASE_ROBOLECTRIC_DIR
trap 'rm -rf -- "$ZEC_RELEASE_ROBOLECTRIC_DIR"' EXIT
bash .private/stage_robolectric_release.sh "$ZEC_RELEASE_ROBOLECTRIC_DIR"
mkdir -p artifact
./gradlew "${init[@]}" --dependency-verification strict --profile --warning-mode all --no-daemon --no-watch-fs --max-workers=2 -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=1g' -Pkotlin.compiler.execution.strategy=in-process "${tasks[@]}"
python3 .private/check_exclusions.py artifact/runtime.json
python3 - "$start" <<'PY'
import json,sys,time
from pathlib import Path
Path('artifact/gradle-timing.json').write_text(json.dumps({'seconds':int(time.time())-int(sys.argv[1]),'invocations':1})+'\n')
PY
python3 .private/proof.py
