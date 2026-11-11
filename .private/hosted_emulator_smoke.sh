#!/usr/bin/env bash
# Check a hosted Android guest with an optional verified testnet pair.
set -euo pipefail
test -c /dev/kvm
sudo setfacl -m "u:$(id -un):rw" /dev/kvm
if [[ ! -x "$ANDROID_HOME/emulator/emulator" ]]; then
  timeout 180 "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --install emulator
fi
"$ANDROID_HOME/emulator/emulator" -accel-check
scratch=$(mktemp -d "$RUNNER_TEMP/zec-hosted-probe.XXXXXX")
namespace="zec-probe-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
created=false
stop_owned_processes() {
  local pids signal
  for signal in TERM KILL; do
    pids=$(sudo ip netns pids "$namespace")
    [[ -n "$pids" ]] || return 0
    echo "Stopping remaining processes in the probe namespace ($signal)."
    printf '%s\n' "$pids" | xargs -r ps -o pid=,stat=,comm= -p || true
    printf '%s\n' "$pids" | xargs -r sudo kill "-$signal" || true
    sleep 1
  done
  [[ -z "$(sudo ip netns pids "$namespace")" ]]
}
cleanup() {
  if "$created"; then
    stop_owned_processes || true
    sudo ip netns del "$namespace"
  fi
  case "$scratch" in "$RUNNER_TEMP"/zec-hosted-probe.*) rm -rf -- "$scratch" ;; *) exit 1 ;; esac
}
trap cleanup EXIT
image=$(PYTHONPATH=.private python3 -B -c 'import sys; from emulator_session import prepare_image; print(prepare_image(sys.argv[1], environ={}))' "$scratch/image-input" | tail -n1)
test -f "$image/system.img"
transport=${ZEC_HOSTED_TRANSPORT_PROBE:-false}
execution=()
if [[ "$transport" == true ]]; then
  # Live work runs only on an ephemeral GitHub-hosted VM. The existing
  # testnet driver enforces its app-UID egress policy before creating a wallet.
  [[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_ENVIRONMENT:-}" == github-hosted && -n "${ZEC_HOSTED_FIXTURE_DIR:-}" ]]
else
  sudo ip netns add "$namespace"
  created=true
  sudo ip netns exec "$namespace" ip link set lo up
  test -z "$(sudo ip -n "$namespace" route show default)"
  test -z "$(sudo ip -n "$namespace" -6 route show default)"
  execution=(sudo ip netns exec "$namespace" runuser -u "$(id -un)" --)
fi
"${execution[@]}" env \
  ANDROID_HOME="$ANDROID_HOME" JAVA_HOME="${JAVA_HOME:-/usr}" CODEX_GUARD_OUTPUT="$scratch" CODEX_EMULATOR_IMAGE="$image" \
  ZEC_HOSTED_FIXTURE_DIR="${ZEC_HOSTED_FIXTURE_DIR:-}" ZEC_HOSTED_TRANSPORT_PROBE="$transport" \
  PYTHONPATH=.private python3 -B - <<'PY'
import os
from pathlib import Path
from emulator_session import EmulatorSession, SDK

root = Path(os.environ['CODEX_GUARD_OUTPUT'])
transport = os.environ['ZEC_HOSTED_TRANSPORT_PROBE'] == 'true'
with EmulatorSession(root / 'session', guest_memory=3072 if transport else 1536) as session:
    assert session.adb('shell', 'getprop', 'ro.product.cpu.abi').stdout.strip() == b'x86_64'
    assert session.adb('shell', 'getprop', 'ro.build.version.sdk').stdout.strip() == b'35'
    fixture = os.environ['ZEC_HOSTED_FIXTURE_DIR']
    if fixture:
        import json
        import shutil
        from core_testnet_driver import run, verify_instrumentation_pair
        from strip_emulator_apk import prepare

        source = Path(fixture)
        binding = json.loads((source / 'fixture-binding.json').read_text())
        version = binding['version_code']
        app, test = [Path(shutil.copyfile(source / name, root / name))
                     for name in ('baseline-ui.apk', 'baseline-androidTest.apk')]
        prepared = prepare((app, test), root, SDK)
        if transport:
            manifest = dict(input_fingerprint=None, runtime_input_fingerprint=None, build_input_fingerprint=None,
                baseline_source_sha=binding['source_sha'], build_source_sha=binding['source_sha'],
                baseline_version_code=version, candidate_version_code=version,
                baseline_apk_sha256=binding['candidate_apk_sha256'], candidate_apk_sha256=binding['candidate_apk_sha256'],
                baseline_instrumentation_apk_sha256=binding['instrumentation_apk_sha256'],
                instrumentation_apk_sha256=binding['instrumentation_apk_sha256'],
                upgradeBaselineInstrumentation=dict(source_sha=binding['source_sha'],
                                                    apk_sha256=binding['instrumentation_apk_sha256']))
            try:
                run(session.adb, root, (app, test), manifest, transport_only=True)
            except Exception:
                result_path = root / 'core-testnet-result.json'
                if result_path.is_file():
                    result = json.loads(result_path.read_text())
                    print('Hosted core stage:', result['last_stage'], result['failure']['type'], flush=True)
                print('Hosted emulator process exit code:', session.process.poll(), flush=True)
                raise
            print('Native Tor interruption and recovery passed with zero faucet claims or valid transaction submissions.')
        else:
            session.adb('install', '-r', str(app), timeout=90)
            session.adb('install', '-r', '-t', str(test), timeout=90)
            verify_instrumentation_pair(session.adb, version, prepared[test.name]['testedSha256'])
            print('Installed verified x86_64 testnet app and instrumentation. No wallet or funds created.')
assert session.cleanup == {'emulatorExited': True, 'privatePortsClosed': True}
print('API 35 x86_64 guest stopped and closed all owned ports.')
PY
if "$created"; then
  stop_owned_processes
  sudo ip netns del "$namespace"
  created=false
  echo 'Verified that the probe namespace has no remaining processes.'
fi
