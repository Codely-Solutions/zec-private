#!/usr/bin/env bash
set -euo pipefail
# Ubuntu-hosted runner only. Resolve packages against signed Ubuntu indexes.
# Hosted wallet jobs install the prefetched bundle without depending on mirrors
# after reserving a faucet claim.
mode=${1:-}
bundle=${2:-}
if [[ "$mode" != --install-bundle ]]; then
  scratch=$(mktemp -d "${RUNNER_TEMP:?}/zec-tor-apt.XXXXXX")
  trap 'sudo rm -rf -- "$scratch"' EXIT
  chmod 755 "$scratch"
  mkdir "$scratch/lists" "$scratch/archives"
  cat > "$scratch/ubuntu.sources" <<'EOF'
Types: deb
URIs: https://archive.ubuntu.com/ubuntu/
Suites: noble noble-updates
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: https://security.ubuntu.com/ubuntu/
Suites: noble-security
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
  apt_options=(-o "Dir::Etc::sourcelist=$scratch/ubuntu.sources" -o 'Dir::Etc::sourceparts=-'
               -o "Dir::State::lists=$scratch/lists" -o "Dir::Cache::archives=$scratch/archives"
               -o 'Acquire::Retries=1' -o 'Acquire::https::Timeout=12')
  sudo timeout 100 apt-get "${apt_options[@]}" --error-on=any update -qq
  candidate="$(apt-cache "${apt_options[@]}" policy tor | awk '/Candidate:/ {print $2}')"
  test -n "$candidate" && test "$candidate" != '(none)'
  sudo timeout 90 apt-get "${apt_options[@]}" install -y --no-install-recommends --download-only --reinstall "tor=$candidate"
  if [[ "$mode" == --prepare-bundle ]]; then
    mkdir -m 700 -- "$bundle"
    cp -- "$scratch"/archives/*.deb "$bundle/"
    (cd "$bundle" && sha256sum -- ./*.deb > SHA256SUMS)
    test "$(du -sb -- "$bundle" | cut -f1)" -lt 10000000
    exit 0
  fi
  bundle="$scratch/archives"
else
  (cd "$bundle" && sha256sum -c -- SHA256SUMS)
fi
sudo timeout 90 apt-get install -y --no-download --no-install-recommends -- "$bundle"/*.deb
sudo systemctl stop tor.service tor@default.service
if systemctl is-active --quiet tor@default.service; then
  echo 'The package Tor service must stop before the task-owned instance starts.' >&2
  exit 1
fi
/usr/bin/tor --version
if [[ "$mode" != --install-only && "$mode" != --install-bundle ]]; then
  python3 -B .private/tor_contract.py
fi
