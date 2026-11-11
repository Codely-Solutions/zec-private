#!/usr/bin/env bash
set -euo pipefail
fixture="$1"
mkdir -m 700 "$fixture"
trap 'rm -f -- "$fixture/auth.json"' EXIT
selected=.private/hosted-fixture.json
tag=$(jq -er .release "$selected")
reference=$(jq -er .reference "$selected")
[[ "$reference" =~ ^ghcr\.io/codely-solutions/zec-private-upgrade-fixtures@sha256:[0-9a-f]{64}$ ]]
export ARCHIVE_DIGEST=$(jq -er .digest "$selected")
export OCI_DIGEST=${reference##*@}
printf '%s' "$REGISTRY_TOKEN" | oras login ghcr.io --username "$GITHUB_ACTOR" --password-stdin --registry-config "$fixture/auth.json"
unset REGISTRY_TOKEN
oras pull --registry-config "$fixture/auth.json" --output "$fixture" "$reference"
python3 -B .private/hosted_fixture.py verify --tag "$tag" --directory "$fixture" --extract
diff <(jq -S . "$selected") <(jq -S . "$fixture/fixture-binding.json")
