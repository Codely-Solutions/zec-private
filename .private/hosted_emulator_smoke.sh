#!/usr/bin/env bash
# Hosted admission probe only. No wallet, APK or external guest network.
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
sudo ip netns add "$namespace"
created=true
sudo ip netns exec "$namespace" ip link set lo up
test -z "$(sudo ip -n "$namespace" route show default)"
test -z "$(sudo ip -n "$namespace" -6 route show default)"
sudo ip netns exec "$namespace" runuser -u "$(id -un)" -- env \
  ANDROID_HOME="$ANDROID_HOME" CODEX_GUARD_OUTPUT="$scratch" CODEX_EMULATOR_IMAGE="$image" \
  PYTHONPATH=.private python3 -B - <<'PY'
import os
from pathlib import Path
from emulator_session import EmulatorSession

with EmulatorSession(Path(os.environ['CODEX_GUARD_OUTPUT']) / 'session') as session:
    assert session.adb('shell', 'getprop', 'ro.product.cpu.abi').stdout.strip() == b'x86_64'
    assert session.adb('shell', 'getprop', 'ro.build.version.sdk').stdout.strip() == b'35'
assert session.cleanup == {'emulatorExited': True, 'privatePortsClosed': True}
print('API 35 x86_64 booted in a namespace without routes and closed all owned ports.')
PY
stop_owned_processes
sudo ip netns del "$namespace"
created=false
echo 'Verified that the probe namespace has no remaining processes.'
