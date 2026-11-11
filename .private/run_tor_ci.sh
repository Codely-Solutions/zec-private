#!/usr/bin/env bash
set -euo pipefail
# Verify the published Linux Expert Bundle. Keep only the runtime used by CI.
mode=${1:-}
runtime=${2:-${RUNNER_TEMP:?}/zec-tor-runtime}
files=(tor/tor tor/libcrypto.so.3 tor/libevent-2.1.so.7 tor/libssl.so.3)
if [[ "$mode" != --install-bundle ]]; then
  version=16.0.1
  base="tor-expert-bundle-linux-x86_64-$version.tar.gz"
  url="https://dist.torproject.org/torbrowser/$version/$base"
  digest=7039cb16060ab22e669852e55774d894b55f25ea8bd0ab7194d3478429a58725
  fingerprint=EF6E286DDA85EA2A4BA7DE684E2C6E8793298290
  scratch=$(mktemp -d "$RUNNER_TEMP/zec-tor-download.XXXXXX")
  trap 'rm -rf -- "$scratch"' EXIT
  fetch=(curl --fail --silent --show-error --proto '=https' --connect-timeout 10 --max-time 45)
  "${fetch[@]}" --max-filesize 40000000 -o "$scratch/bundle.tar.gz" "$url"
  printf '%s  %s\n' "$digest" "$scratch/bundle.tar.gz" | sha256sum --check --strict
  "${fetch[@]}" --max-filesize 16000 -o "$scratch/bundle.asc" "$url.asc"
  "${fetch[@]}" --max-filesize 200000 -o "$scratch/key.asc" \
    https://openpgpkey.torproject.org/.well-known/openpgpkey/torproject.org/hu/kounek7zrdx745qydx6p59t9mqjpuhdf
  gpg --homedir "$scratch" --batch --no-autostart --import "$scratch/key.asc"
  gpg --homedir "$scratch" --batch --no-autostart --output "$scratch/release.gpg" --export "$fingerprint"
  test -s "$scratch/release.gpg"
  gpgv --keyring "$scratch/release.gpg" "$scratch/bundle.asc" "$scratch/bundle.tar.gz"
  mkdir -m 700 -- "$runtime"
  tar -xzf "$scratch/bundle.tar.gz" --no-same-owner --no-same-permissions -C "$runtime" "${files[@]}"
  printf '%s\n' "$digest" > "$runtime/upstream-sha256.txt"
  (cd "$runtime" && sha256sum -- "${files[@]}" upstream-sha256.txt > SHA256SUMS)
  test "$(du -sb -- "$runtime" | cut -f1)" -lt 16000000
fi
(cd "$runtime" && sha256sum --check --strict SHA256SUMS)
chmod u+x -- "$runtime/tor/tor"
LD_LIBRARY_PATH="$runtime/tor" "$runtime/tor/tor" --version
if [[ -z "$mode" ]]; then
  trap 'rm -rf -- "$scratch" "$runtime"' EXIT
  python3 -B .private/tor_contract.py
fi
