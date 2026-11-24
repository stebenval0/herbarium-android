#!/bin/bash
# Publish a new Herbarium for Android in one go:
#   1. makes sure the public repository exists (stebenval0/herbarium-android) and pushes the source + install guide (README.md)
#   2. builds the next version (./release.sh), signed with ~/.config/herbarium/android-signing.jks
#   3. publishes it as a GitHub release: Herbarium-<version>.apk, latest.json (for in-app updates),
#      and Herbarium.apk, so https://github.com/$REPO/releases/latest/download/Herbarium.apk is always the newest
# Usage: ./publish.sh "What's new, in one sentence" [version, e.g. 2.3.1]
set -e
cd "$(dirname "$0")"
REPO=stebenval0/herbarium-android
GH=$(command -v gh || echo "$HOME/.local/bin/gh")
[[ -x $GH ]] || { echo "gh isn't installed: sudo pacman -S github-cli, then gh auth login"; exit 1; }
"$GH" auth status >/dev/null 2>&1 || { echo "gh isn't logged in: run gh auth login"; exit 1; }
[[ -f $HOME/.config/herbarium/android-signing.jks ]] || { echo "The signing key is missing (~/.config/herbarium/android-signing.jks). Restore it from your backup: without it, phones can't take updates."; exit 1; }

if ! "$GH" repo view "$REPO" >/dev/null 2>&1; then
  "$GH" repo create "$REPO" --public --description "Herbarium for Android: a calm home screen. Download it from Releases."
fi

./release.sh "${1:-}" "${2:-}"
read -r CODE NAME < VERSION
# the source and the install guide (README.md) go up with every release
git add -A && git commit -q -m "Herbarium $NAME${1:+: $1}" || true
git push -q origin main
cp "dist/Herbarium-$NAME.apk" dist/Herbarium.apk
"$GH" release create "v$NAME" --repo "$REPO" --title "Herbarium $NAME" --notes "${1:-Herbarium $NAME}" \
  "dist/Herbarium-$NAME.apk" dist/Herbarium.apk dist/latest.json
echo
echo "Published Herbarium $NAME."
echo "Download link to share:  https://github.com/$REPO/releases/latest/download/Herbarium.apk"
echo "Install guide:           https://github.com/$REPO#readme"
echo "Phones with Herbarium offer the update within a day (to see it at once: Settings → Herbarium → Check for an update)."
