#!/bin/bash
# Builds Herbarium.apk without Gradle: aapt2 (resources) → javac → d8 (dex) → zipalign → apksigner.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; BT=$SDK/build-tools/36.1.0; JAR=$SDK/platforms/android-36/android.jar
rm -rf build; mkdir -p build/res build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
read -r VCODE VNAME < VERSION
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -A assets --version-code "$VCODE" --version-name "$VNAME" --replace-version -o build/base.apk --java build/gen build/res.zip --auto-add-overlay
# Herbarium's own speech recogniser for voice typing without Google: Vosk (+ JNA), from libs/ (see libs/README)
LIBJARS="libs/vosk/classes.jar:libs/jna/classes.jar"
javac -source 17 -target 17 -nowarn -Xlint:-options -encoding UTF-8 -cp "$JAR:$LIBJARS" -d build/classes $(find src build/gen vendor -name '*.java')
# the rules that matter most (who may ring, words people read), checked on this computer; a failure stops the build
mkdir -p build/checks
javac -source 17 -target 17 -nowarn -Xlint:-options -encoding UTF-8 -cp "build/classes:$JAR:$LIBJARS" -d build/checks tests/*.java
java -cp "build/checks:build/classes:$JAR" com.herbarium.home.Checks
"$BT/d8" --min-api 29 --lib "$JAR" --output build/dex $(find build/classes -name '*.class') libs/vosk/classes.jar libs/jna/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -j ../unsigned.apk classes*.dex)
for abi in arm64-v8a armeabi-v7a x86_64; do     # 64-bit phones, 32-bit-only budget phones, and the x86_64 emulator
  mkdir -p build/lib/$abi; cp libs/vosk/jni/$abi/libvosk.so libs/jna/jni/$abi/libjnidispatch.so build/lib/$abi/
done
(cd build && zip -q -r unsigned.apk lib)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
KS=$HOME/.config/herbarium/android-signing.jks
if [[ ! -f $KS ]]; then
  mkdir -p "$(dirname "$KS")"; chmod 700 "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass herbarium-local -keypass herbarium-local -alias herbarium \
    -keyalg RSA -keysize 3072 -validity 36500 -dname "CN=Herbarium (local build)" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:herbarium-local --ks-key-alias herbarium --out Herbarium.apk build/aligned.apk
echo "built $(pwd)/Herbarium.apk ($(du -h Herbarium.apk | cut -f1))"
