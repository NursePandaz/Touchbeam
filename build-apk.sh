#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
TOOLS="$SDK_DIR/build-tools/35.0.0"
PLATFORM="$SDK_DIR/platforms/android-35/android.jar"
BUILD_DIR="$PWD/build/manual"
rm -rf "$BUILD_DIR/classes" "$BUILD_DIR/generated" "$BUILD_DIR/dex"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/generated" "$BUILD_DIR/dex"
"$TOOLS/aapt2" compile --dir app/src/main/res -o "$BUILD_DIR/resources.zip"
# Modern Gradle owns namespace, versions and SDK levels. Generate the equivalent
# standalone manifest from the same simple project configuration to avoid drift.
python3 - <<'PY'
from pathlib import Path
import re,xml.etree.ElementTree as ET
config=Path('app/build.gradle').read_text()
def number(key):return re.search(r'\b'+key+r'\s+(\d+)',config).group(1)
def value(key):return re.search(r"\b"+key+r"\s+'([^']+)'",config).group(1)
ET.register_namespace('android','http://schemas.android.com/apk/res/android')
a='{http://schemas.android.com/apk/res/android}'
tree=ET.parse('app/src/main/AndroidManifest.xml');root=tree.getroot()
root.set('package',value('applicationId'));root.set(a+'versionCode',number('versionCode'));root.set(a+'versionName',value('versionName'))
root.insert(0,ET.Element('uses-sdk',{a+'minSdkVersion':number('minSdk'),a+'targetSdkVersion':number('targetSdk')}))
ET.indent(tree);tree.write('build/manual/AndroidManifest.xml',encoding='unicode')
PY
"$TOOLS/aapt2" link -o "$BUILD_DIR/base.apk" -I "$PLATFORM" --manifest "$BUILD_DIR/AndroidManifest.xml" -A app/src/main/assets --java "$BUILD_DIR/generated" "$BUILD_DIR/resources.zip"
find app/src/main/java "$BUILD_DIR/generated" -name '*.java' -print > "$BUILD_DIR/sources.txt"
javac --release 8 -encoding UTF-8 -classpath "$PLATFORM" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
jar cf "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
"$TOOLS/d8" --lib "$PLATFORM" --min-api 26 --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar"
cp "$BUILD_DIR/base.apk" "$BUILD_DIR/unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"
if [[ ! -f "$BUILD_DIR/test-signing.p12" ]]; then
  keytool -genkeypair -keystore "$BUILD_DIR/test-signing.p12" -storepass android -keypass android -alias beam-test -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=Touchbeam Test Build" -storetype PKCS12
fi
"$TOOLS/apksigner" sign --ks "$BUILD_DIR/test-signing.p12" --ks-key-alias beam-test --ks-pass pass:android --key-pass pass:android --out "$BUILD_DIR/touchbeam-test.apk" "$BUILD_DIR/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$BUILD_DIR/touchbeam-test.apk"
echo "Built: $BUILD_DIR/touchbeam-test.apk"
