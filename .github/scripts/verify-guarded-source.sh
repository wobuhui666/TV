#!/usr/bin/env bash
set -euo pipefail

out=artifacts/guarded-source
pkg=com.fongmi.android.tv.sourceprobe
test_pkg=com.fongmi.android.tv.sourceprobe.test
guarded_temp=$(mktemp -d "${RUNNER_TEMP:-/tmp}/guarded-source.XXXXXX")
guarded_uid=''
guarded_instrument_exit=-1
mkdir -p "$out"

collect() {
    # Only this explicitly sanitized report leaves the app's external files directory.
    adb pull "/sdcard/Android/data/$pkg/files/guarded-source-verification/report.json" "$guarded_temp/report.json" >/dev/null 2>&1 || true
    adb logcat -b all -d -v brief > "$guarded_temp/system-logcat.txt" 2>&1 || true
    if [[ "$guarded_uid" =~ ^[0-9]+$ ]]; then
        adb logcat -b all -d -v brief --uid="$guarded_uid" > "$guarded_temp/app-logcat.txt" 2>&1 || true
    fi
    python3 - "$guarded_temp" "$out" "$guarded_instrument_exit" <<'PY'
import json, pathlib, re, sys
private, public = map(pathlib.Path, sys.argv[1:3])
def write(name, value):
    (public / name).write_text(json.dumps(value, indent=2) + '\n')
def read(name):
    path = private / name
    return path.read_text(errors='replace') if path.exists() else ''

# Retain only application-authored counters, architecture, statuses and exception class names.
fields = {'schema', 'passed', 'nativeArmPlaybackVerified', 'deviceAbis', 'devicePrimaryAbi', 'process64Bit',
          'processArchitecture', 'primaryCpuAbi', 'secondaryCpuAbi', 'nativeBridge', 'configStatus',
          'configLoadMs', 'siteCount', 'pluginInitializationVerified', 'sourceContentVerified',
          'searchProbeCount', 'sourceAvailability', 'failureTypes', 'workerCleanupComplete', 'nativeBridgeMapped'}
stage_fields = {'phase', 'siteIndex', 'status', 'spiderClass', 'pluginInitialized', 'contentReturned',
                'itemCount', 'categoryCount', 'elapsedMs', 'failureTypes', 'guardStatus', 'guardFailureTypes', 'dialogRequired'}
def safe(value):
    if isinstance(value, (bool, int, float)) or value is None:
        return value
    if isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_$.-]{1,200}', value):
        return value
    if isinstance(value, list) and len(value) <= 32:
        return [safe(item) for item in value]
    raise ValueError('Unexpected report value')
if (private / 'report.json').exists():
    try:
        source = json.loads(read('report.json'))
        assert source['schema'] == 'tv.guarded-source-verification.v1'
        result = {key: safe(value) for key, value in source.items() if key in fields}
        assert isinstance(source.get('stages', []), list) and len(source.get('stages', [])) <= 16
        result['stages'] = [{key: safe(value) for key, value in stage.items() if key in stage_fields}
                            for stage in source.get('stages', [])]
        write('report.json', result)
    except Exception as error:
        write('report-collection.json', {'status': 'invalid-report', 'failureType': type(error).__name__})

app, system = read('app-logcat.txt'), read('system-logcat.txt')
write('health.json', {
    'fatalExceptionCount': len(re.findall(r'FATAL EXCEPTION:', app)),
    'anrCount': len(re.findall(r'ANR in com\.fongmi\.android\.tv\.sourceprobe\b', system)),
    'nativeFatalSignalCount': len(re.findall(r'Fatal signal \d+', app)),
})
write('source-runtime-diagnostics.json', {
    'wrongElfMachine': bool(re.search(r'EM_AARCH64[^\n]*EM_X86_64', app)),
    'failureTypes': [name for name in ('UnsatisfiedLinkError', 'ExceptionInInitializerError',
        'NoClassDefFoundError', 'InvocationTargetException', 'SecurityException') if name in app],
})
instrumentation = read('instrumentation.txt')
match = re.search(r'^OK \((\d+) tests?\)', instrumentation, re.M)
write('instrumentation-summary.json', {
    'executed': int(sys.argv[3]) >= 0,
    'exitCode': int(sys.argv[3]),
    'passed': bool(match and int(match.group(1)) == 1 and int(sys.argv[3]) == 0),
    'testCount': int(match.group(1)) if match else 0,
    'failureTypes': sorted(set(re.findall(
        r'\b((?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error))\b', instrumentation))),
})
PY
}
trap collect EXIT

mapfile -t guarded_apks < <(python3 - "$out/apk-metadata.json" <<'PY'
import hashlib, json, pathlib, sys, zipfile
entries = []
paths = [pathlib.Path('app/build/outputs/apk/leanbackArm64_v8a/debug/output-metadata.json'),
         pathlib.Path('app/build/outputs/apk/androidTest/leanbackArm64_v8a/debug/output-metadata.json')]
for index, path in enumerate(paths):
    metadata = json.loads(path.read_text())
    package = 'com.fongmi.android.tv.sourceprobe' + ('.test' if index else '')
    assert metadata['applicationId'] == package, 'Unexpected application ID'
    assert len(metadata['elements']) == 1, 'Expected one isolated ARM64 APK'
    apk = path.parent / metadata['elements'][0]['outputFile']
    with zipfile.ZipFile(apk) as archive:
        libraries = [name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')]
    abis = sorted({name.split('/')[1] for name in libraries})
    assert set(abis) <= {'arm64-v8a'}, 'APK contains a different native ABI'
    if index == 0:
        assert abis == ['arm64-v8a'] and libraries, 'App must retain its production ARM64 JNI libraries'
    entries.append({'applicationId': package, 'fileName': apk.name, 'bytes': apk.stat().st_size,
                    'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                    'nativeAbis': abis, 'nativeLibraryCount': len(libraries)})
pathlib.Path(sys.argv[1]).write_text(json.dumps({'files': entries}, indent=2) + '\n')
for path in paths:
    metadata = json.loads(path.read_text())
    print(path.parent / metadata['elements'][0]['outputFile'])
PY
)
[[ ${#guarded_apks[@]} -eq 2 ]]

adb shell getprop ro.product.cpu.abilist > "$guarded_temp/device-abis.txt"
adb shell getprop ro.dalvik.vm.native.bridge > "$guarded_temp/native-bridge.txt"
python3 - "$guarded_temp" "$out/device.json" <<'PY'
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
abis = [abi.strip() for abi in (root / 'device-abis.txt').read_text().strip().split(',') if abi.strip()]
assert all(re.fullmatch(r'[A-Za-z0-9_-]{1,40}', abi) for abi in abis)
bridge = (root / 'native-bridge.txt').read_text().strip()
bridge = bridge if re.fullmatch(r'[A-Za-z0-9._-]{1,80}', bridge) else 'unknown'
ready = 'arm64-v8a' in abis and bridge not in ('0', 'unknown')
data = {'deviceAbis': abis, 'nativeBridge': bridge, 'arm64Advertised': 'arm64-v8a' in abis,
        'nativeBridgeAvailable': ready, 'status': 'ready' if ready else 'native-bridge-unavailable'}
pathlib.Path(sys.argv[2]).write_text(json.dumps(data, indent=2) + '\n')
assert ready, 'The emulator does not advertise an available ARM64 native bridge'
PY

install_apk() {
    local apk=$1
    local label=$2
    local installed=true
    adb install --abi arm64-v8a -r "$apk" > "$guarded_temp/install-$label.txt" 2>&1 || installed=false
    python3 - "$guarded_temp/install-$label.txt" "$out/install-$label.json" "$installed" <<'PY'
import json, pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')
error = re.search(r'\b(INSTALL_(?:FAILED|PARSE_FAILED)_[A-Z0-9_]+)\b', text)
result = {'installed': sys.argv[3] == 'true', 'failureCode': error.group(1) if error else None}
pathlib.Path(sys.argv[2]).write_text(json.dumps(result, indent=2) + '\n')
PY
    [[ "$installed" == true ]]
}
install_apk "${guarded_apks[0]}" app
install_apk "${guarded_apks[1]}" test

adb shell dumpsys package "$pkg" > "$guarded_temp/package.txt"
python3 - "$guarded_temp/package.txt" "$out/package.json" <<'PY'
import json, pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')
def field(name):
    match = re.search(r'\b' + name + r'=([A-Za-z0-9_-]+)', text)
    return match.group(1) if match else 'unknown'
data = {'applicationId': 'com.fongmi.android.tv.sourceprobe',
        'primaryCpuAbi': field('primaryCpuAbi'), 'secondaryCpuAbi': field('secondaryCpuAbi')}
pathlib.Path(sys.argv[2]).write_text(json.dumps(data, indent=2) + '\n')
assert data['primaryCpuAbi'] == 'arm64-v8a', 'Installed app must use the original ARM64 libraries'
PY
guarded_uid=$(adb shell cmd package list packages -U "$pkg" | python3 -c 'import re,sys; match=re.search(r"^package:"+re.escape(sys.argv[1])+r" uid:([0-9]+)\s*$",sys.stdin.read(),re.M); assert match,"Target application UID missing"; print(match.group(1))' "$pkg")
[[ "$guarded_uid" =~ ^[0-9]+$ ]]
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
adb shell wm size 1920x1080
adb shell wm density 320
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$setting" 1
done

if [[ "${FONGMI_PRELOAD_TESTS:-false}" == true ]]; then
    preload_archive="$RUNNER_TEMP/fongmi-core-fixtures.tar.gz"
    test -f "$preload_archive"
    adb push "$preload_archive" /data/local/tmp/fongmi-core-fixtures.tar.gz >/dev/null
    adb shell run-as "$pkg" mkdir -p files/core-fixtures
    adb shell "cat /data/local/tmp/fongmi-core-fixtures.tar.gz | run-as $pkg tar -xz -C files/core-fixtures"
    if ! timeout 240s adb shell am instrument -w -r \
        -e core_fixture_root core-fixtures \
        -e class com.fongmi.android.tv.player.exo.NextMediaPreloadTest \
        "$test_pkg/androidx.test.runner.AndroidJUnitRunner" > "$guarded_temp/preload-instrumentation.txt" 2>&1; then
        preload_exit=1
    else
        preload_exit=0
    fi
    cp "$guarded_temp/preload-instrumentation.txt" "$out/preload-instrumentation.txt"
    python3 - "$guarded_temp/preload-instrumentation.txt" "$out/preload-summary.json" "$preload_exit" <<'PY'
import json, pathlib, re, sys
content = pathlib.Path(sys.argv[1]).read_text(errors='replace')
match = re.search(r'^OK \(([1-9][0-9]*) tests?\)', content, re.M)
result = {'passed': match is not None and sys.argv[3] == '0',
          'testCount': int(match.group(1)) if match else 0,
          'failureTypes': sorted(set(re.findall(r'\b((?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error))\b', content)))}
pathlib.Path(sys.argv[2]).write_text(json.dumps(result, indent=2) + '\n')
assert result['passed'], 'Preload cache regression failed; inspect the test summary'
PY
    # Keep actual source checks independent of synthetic player state and fixtures.
    adb shell am force-stop "$pkg"
    adb shell pm clear "$pkg" >/dev/null
    adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
fi

python3 - "$out/config-metadata.json" <<'PY'
import hashlib, json, pathlib, sys, urllib.request
try:
    with urllib.request.urlopen('https://9280.kstore.vip/aiwex.json', timeout=30) as response:
        body = response.read(2 * 1024 * 1024 + 1)
    assert len(body) <= 2 * 1024 * 1024, 'Unexpected configuration size'
    config = json.loads(body)
    data = {'status': 'downloaded', 'sha256': hashlib.sha256(body).hexdigest(),
            'bytes': len(body), 'siteCount': len(config.get('sites', []))}
except Exception as error:
    data = {'status': 'host-download-failed', 'failureType': type(error).__name__}
pathlib.Path(sys.argv[1]).write_text(json.dumps(data, indent=2) + '\n')
PY
adb logcat -c
guarded_instrument_exit=0
timeout 360s adb shell am instrument -w -r \
    -e performance_config_url https://9280.kstore.vip/aiwex.json \
    -e class com.fongmi.android.tv.test.GuardedConfigAndroidTest \
    "$test_pkg/androidx.test.runner.AndroidJUnitRunner" > "$guarded_temp/instrumentation.txt" 2>&1 || guarded_instrument_exit=$?
collect
python3 - "$out" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
instrumentation = json.loads((root / 'instrumentation-summary.json').read_text())
report_path = root / 'report.json'
assert report_path.is_file(), 'The app did not produce a sanitized guarded-source report'
report = json.loads(report_path.read_text())
health = json.loads((root / 'health.json').read_text())
summary = {key: report.get(key) for key in ('passed', 'deviceAbis', 'primaryCpuAbi', 'nativeBridge', 'nativeBridgeMapped',
    'configStatus', 'siteCount', 'pluginInitializationVerified', 'sourceContentVerified', 'sourceAvailability')}
summary['health'] = health
print(json.dumps(summary, indent=2))
assert instrumentation['passed'], 'Guarded-source instrumentation failed; inspect error classifications'
assert report['passed'] and report['pluginInitializationVerified'] and report['sourceContentVerified'], \
    'Guarded-source loading and content have not both been verified'
assert not any(health.values()), 'Crash or ANR found during the guarded-source probe'
PY
