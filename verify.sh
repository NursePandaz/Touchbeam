#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
export ANDROID_USER_HOME="$PWD/build/verification/android-tooling"
mkdir -p build/verification "$ANDROID_USER_HOME"
bash test.sh | tee build/verification/tests.txt
bash build-apk.sh | tee build/verification/build.txt
"$SDK_DIR/build-tools/35.0.0/zipalign" -c -v 4 build/manual/touchbeam-test.apk > build/verification/alignment.txt
"$SDK_DIR/build-tools/35.0.0/apksigner" verify --verbose --print-certs build/manual/touchbeam-test.apk > build/verification/signature.txt
python3 - "$SDK_DIR" <<'PY'
from pathlib import Path
import shutil,subprocess,sys,xml.etree.ElementTree as ET,zipfile
sdk=Path(sys.argv[1]);source=Path('app/src/main');scan=Path('build/verification/lint-project')
scan.mkdir(exist_ok=True)
shutil.copy('build/manual/AndroidManifest.xml',scan/'AndroidManifest.xml')
for name,origin in [('src',source/'java'),('res',source/'res'),('assets',source/'assets')]:
    if (scan/name).exists():shutil.rmtree(scan/name)
    shutil.copytree(origin,scan/name)
(scan/'project.properties').write_text('target=android-35\n')
apk=Path('build/manual/touchbeam-test.apk')
manifest=subprocess.check_output([str(sdk/'cmdline-tools/latest/bin/apkanalyzer'),'manifest','print',str(apk)],text=True)
Path('build/verification/packaged-manifest.xml').write_text(manifest)
tree=ET.fromstring(manifest);a='{http://schemas.android.com/apk/res/android}'
assert tree.get(a+'versionCode')=='15' and tree.get(a+'versionName')=='0.9.4-test'
assert tree.find('uses-sdk').get(a+'minSdkVersion')=='26'
application=tree.find('application')
assert application.get(a+'label')=='Touchbeam'
assert application.get(a+'allowBackup')=='false' and application.get(a+'dataExtractionRules')
activity=application.find('activity')
assert activity.get(a+'launchMode')=='2'
actions={x.get(a+'name') for x in activity.findall('intent-filter/action')}
assert {'android.intent.action.MAIN','android.intent.action.SEND','android.intent.action.SEND_MULTIPLE'}<=actions
service=application.find('service')
assert service.get(a+'permission')=='android.permission.BIND_NFC_SERVICE'
ready_service=next(x for x in application.findall('service') if x.get(a+'name').endswith('ReadyService'))
assert ready_service.get(a+'exported')=='false' and ready_service.get(a+'foregroundServiceType') in ('connectedDevice','0x10')
permissions={x.get(a+'name') for x in tree.findall('uses-permission')}
assert permissions=={'android.permission.BLUETOOTH','android.permission.BLUETOOTH_ADMIN','android.permission.BLUETOOTH_CONNECT','android.permission.BLUETOOTH_SCAN','android.permission.BLUETOOTH_ADVERTISE','android.permission.ACCESS_FINE_LOCATION','android.permission.ACCESS_COARSE_LOCATION','android.permission.NFC','android.permission.VIBRATE','android.permission.FOREGROUND_SERVICE','android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE','android.permission.POST_NOTIFICATIONS'}
permission_nodes={x.get(a+'name'):x for x in tree.findall('uses-permission')}
assert permission_nodes['android.permission.ACCESS_COARSE_LOCATION'].get(a+'maxSdkVersion')=='30'
assert permission_nodes['android.permission.ACCESS_FINE_LOCATION'].get(a+'maxSdkVersion')=='30'
assert permission_nodes['android.permission.BLUETOOTH_SCAN'].get(a+'usesPermissionFlags') in ('neverForLocation','0x10000')
rules=ET.parse(source/'res/xml/data_extraction_rules.xml').getroot()
domains={'root','file','database','sharedpref','external','device_root','device_file','device_database','device_sharedpref'}
for section in ('cloud-backup','device-transfer'):
    assert {x.get('domain') for x in rules.findall(section+'/exclude') if x.get('path')=='.'}==domains
with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    for name in ('beam_start.ogg','beam_end.ogg','beam_error.ogg'):
        assert z.read('res/raw/'+name)==(source/'res/raw'/name).read_bytes()
    assert any(name.endswith('/beam_star.png') for name in z.namelist())
    assert z.read('assets/NOTICE.txt')==(source/'assets/NOTICE.txt').read_bytes()
print('PASS: packaged version, share routing, HCE permission, backup exclusions, archive and original audio.')
PY
java -Xmx1024m "-Duser.home=$ANDROID_USER_HOME" "-Dcom.android.tools.lint.bindir=$SDK_DIR/cmdline-tools/latest" -classpath "$SDK_DIR/cmdline-tools/latest/lib/lint-classpath.jar" com.android.tools.lint.Main --sdk-home "$SDK_DIR" --exitcode --quiet --classpath "$PWD/build/manual/classes" --compile-sdk-version 35 --text "$PWD/build/verification/android-lint.txt" "$PWD/build/verification/lint-project"
cat build/verification/android-lint.txt
