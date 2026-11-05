#!/usr/bin/env bash
set -euo pipefail
# Ubuntu-hosted runner only. Use the security-maintained OS package, record its
# exact candidate, and stop the package service before our bounded private Tor.
sudo apt-get update -qq
candidate="$(apt-cache policy tor | awk '/Candidate:/ {print $2}')"
test -n "$candidate" && test "$candidate" != '(none)'
sudo apt-get install -y --no-install-recommends "tor=$candidate"
sudo systemctl stop tor.service tor@default.service || true
/usr/bin/tor --version
python3 -B .private/tor_contract.py
