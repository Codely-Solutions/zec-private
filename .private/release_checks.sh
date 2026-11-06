#!/usr/bin/env bash
# One finite Gradle process/configuration per candidate; no persistent daemon.
set -euo pipefail
chmod +x gradlew
tasks=(:app:privateRuntimeInventory :ui-lib:testZcashmainnetFossReleaseUnitTest detektAll :app:lintZcashmainnetFossRelease :app:assembleZcashmainnetFossRelease)
init=(-I .private/runtime-inventory.gradle -I .private/robolectric-ci.gradle -I .private/release-analysis.gradle)
if [[ "${BUILD_EMULATOR:-false}" == true ]]; then
  init+=(-I .private/tor-ui-abi.gradle)
  tasks+=(:app:assembleZcashmainnetFossDebug)
fi
start=$(date +%s)
./gradlew "${init[@]}" --profile --no-daemon --no-watch-fs --max-workers=2 -Dorg.gradle.jvmargs='-Xmx3g -XX:MaxMetaspaceSize=1g' -Pkotlin.compiler.execution.strategy=in-process "${tasks[@]}"
python3 .private/check_exclusions.py artifact/runtime.json
python3 - "$start" <<'PY'
import json,sys,time
from pathlib import Path
Path('artifact/gradle-timing.json').write_text(json.dumps({'seconds':int(time.time())-int(sys.argv[1]),'invocations':1})+'\n')
PY
python3 .private/proof.py
