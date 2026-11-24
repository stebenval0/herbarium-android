#!/bin/bash
# Make a release: bump the version, build and sign, and write dist/ ready to publish:
#   dist/Herbarium-<version>.apk   the app people install from the download link
#   dist/latest.json               what Herbarium reads to offer the update
# Usage: ./release.sh "What's new, in one sentence" [version, e.g. 2.3.1; default: next minor]
set -e
cd "$(dirname "$0")"
read -r CODE NAME < VERSION
CODE=$((CODE + 1))
if [[ -n ${2:-} ]]; then NAME=$2                                   # an explicit version, e.g. 2.3.1
else MAJOR=${NAME%%.*}; REST=${NAME#*.}; MINOR=${REST%%.*}; NAME="$MAJOR.$((MINOR + 1))"; fi
echo "$CODE $NAME" > VERSION
./build.sh
mkdir -p dist
APK="Herbarium-$NAME.apk"; cp Herbarium.apk "dist/$APK"
SHA=$(sha256sum "dist/$APK" | cut -d' ' -f1)
python3 - "$CODE" "$NAME" "$APK" "$SHA" "${1:-}" > dist/latest.json <<'PY'
import json, sys
code, name, apk, sha, notes = sys.argv[1:6]
print(json.dumps({"code": int(code), "version": name, "sha256": sha, "notes": notes,
                  "apk": f"https://github.com/stebenval0/herbarium-android/releases/download/v{name}/{apk}"}, indent=1))
PY
echo "Release $NAME ready in dist/ ($APK, latest.json). Publish both as GitHub release v$NAME."
