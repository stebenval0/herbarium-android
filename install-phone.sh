#!/bin/bash
# Install the freshly built Herbarium on the plugged-in phone and keep it as the home screen
# (Android forgets the default home whenever the app is reinstalled).
set -e
cd "$(dirname "$0")"
adb install -r Herbarium.apk >/dev/null
adb shell cmd package set-home-activity com.herbarium.home/.HomeActivity >/dev/null
echo "installed; Herbarium is the home screen"
