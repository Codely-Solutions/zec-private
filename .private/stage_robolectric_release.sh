#!/usr/bin/env bash
# Stage the reviewed test JVM dependencies before Gradle starts.
set -euo pipefail

stage=${1:?Expected a private staging directory}
central=https://repo.maven.apache.org/maven2
mirror=https://maven-central.storage-download.googleapis.com/maven2

fetch() {
  local coordinate=$1 destination=$2 expected=$3 max_bytes=$4 status result base
  mkdir -p "$(dirname "$destination")"
  # Mirrors may supply the same bytes only after a public transport failure.
  while read -r base; do
    result=0
    status=$(curl -q --silent --show-error --fail --proto '=https' --connect-timeout 10 \
      --max-time 60 --max-filesize "$max_bytes" --write-out '%{http_code}' \
      --output "$destination.part" "$base/$coordinate") || result=$?
    if [[ "$result:$status" == 0:200 ]]; then
      if ! echo "$expected  $destination.part" | sha256sum --check --status; then
        echo "Robolectric test dependency digest mismatch: $coordinate" >&2
        return 1
      fi
      mv -- "$destination.part" "$destination"
      return 0
    fi
    case "$result:$status" in
      *:429|*:500|*:502|*:503|*:504|5:000|6:000|7:000|18:200|28:*|56:*) ;;
      *) break ;;
    esac
  done <<< "$central"$'\n'"$mirror"
  echo "Robolectric test dependency unavailable ($status): $coordinate" >&2
  return 1
}

for version in 9-robolectric-4913185-2-i7 14-robolectric-10818077-i7; do
  if [[ "$version" == 9-* ]]; then
    pin=dbb1c88987b54cef3132695a1b4d2f4851ba06286a6fa7d15772f98fd1f0cdf7
  else
    pin=3fdac2d463f431c6b8d83b9d0ca739e795de75fffedee648c6761bd4bd864701
  fi
  jar=android-all-instrumented-$version.jar
  fetch "org/robolectric/android-all-instrumented/$version/$jar" "$stage/sdk/$jar" "$pin" 170000000
done

native=org/robolectric/nativeruntime-dist-compat/1.0.19/nativeruntime-dist-compat-1.0.19
fetch "$native.pom" "$stage/maven/$native.pom" d4918b9791ff87c4b7eb51beef56394d8b58e96a153318f978160d7807173173 4096
fetch "$native.jar" "$stage/maven/$native.jar" 33e0a0982b3ba599763af250b86eb8d67ab6c186c2fa2c5e823ee003bdcd4ed8 170000000
echo 'Verified Robolectric SDK 28/34 and native test runtime staging.'
