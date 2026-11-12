#!/usr/bin/env bash
set -euo pipefail
umask 077
scratch=$(mktemp -d)
trap 'rm -f "$scratch/key.p12" "$scratch/proof.csr"; rmdir "$scratch"' EXIT
: "${RELEASE_KEYSTORE_BASE64:?Signing keystore unavailable}"
: "${RELEASE_STORE_PASSWORD:?Signing store password unavailable}"
: "${RELEASE_KEY_PASSWORD:?Signing key password unavailable}"
printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 --decode > "$scratch/key.p12"
expected=$(PYTHONPATH=.private python3 -B -c 'from publish import CERT; print(CERT)')
cert=$(keytool -J-Duser.language=en -list -v -storetype PKCS12 -keystore "$scratch/key.p12" \
  -alias zec-private -storepass:env RELEASE_STORE_PASSWORD | awk '/SHA256:/{gsub(/:/,"",$2); print tolower($2)}')
test "$cert" = "$expected"
keytool -certreq -storetype PKCS12 -keystore "$scratch/key.p12" -alias zec-private \
  -storepass:env RELEASE_STORE_PASSWORD -keypass:env RELEASE_KEY_PASSWORD -file "$scratch/proof.csr"
test -s "$scratch/proof.csr"
echo 'Verified the pinned certificate and private-key access.'
