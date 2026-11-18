#!/usr/bin/env bash
# Validate production calendar/follow activities in the isolated x86_64 TV preview.
set -euo pipefail
api="${1:?Pass the emulator API level}"
[[ "$api" == 24 || "$api" == 34 ]] || { printf 'Expected emulator API 24 or 34\n' >&2; exit 1; }
out="${2:-artifacts/airing-calendar/ui-api-$api}"
classes=com.fongmi.android.tv.ui.activity.AiringCalendarAndroidTest,com.fongmi.android.tv.ui.activity.LibraryFollowAndroidTest
mkdir -p "$out"

collect() {
    timeout --kill-after=5s 30s adb pull /sdcard/Android/data/com.fongmi.android.tv.preview/files/native-usability-evidence "$out/" >/dev/null 2>&1 || true
    timeout --kill-after=5s 20s adb logcat -b all -d -v threadtime > "$out/logcat.txt" 2>&1 || true
    timeout --kill-after=5s 20s adb exec-out screencap -p > "$out/last-screen.png" 2>/dev/null || true
    timeout --kill-after=5s 20s adb shell dumpsys window > "$out/window.txt" 2>&1 || true
}
trap collect EXIT

python3 - "$out" "$api" <<'PY' 2>&1 | tee "$out/install.txt"
import hashlib, json, os, pathlib, subprocess, sys, zipfile
root = pathlib.Path(sys.argv[1])
api = int(sys.argv[2])
actual_api = int(subprocess.check_output(['adb', 'shell', 'getprop', 'ro.build.version.sdk'], text=True).strip())
abi = subprocess.check_output(['adb', 'shell', 'getprop', 'ro.product.cpu.abi'], text=True).strip()
assert actual_api == api, f'Expected API {api}, got {actual_api}'
assert abi == 'x86_64', f'Expected the disposable x86_64 emulator, got {abi}'
outputs = (
    ('app/build/outputs/apk/leanbackArm64_v8a/preview', 'com.fongmi.android.tv.preview'),
    ('app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview', 'com.fongmi.android.tv.preview.test'),
)
packages = []
for directory, expected_package in outputs:
    directory = pathlib.Path(directory)
    metadata = json.loads((directory / 'output-metadata.json').read_text())
    assert len(metadata['elements']) == 1, 'Expected one isolated preview APK'
    assert metadata['applicationId'] == expected_package, 'Unexpected APK application ID'
    apk = directory / metadata['elements'][0]['outputFile']
    assert apk.is_file(), f'Missing preview APK: {apk}'
    libraries = []
    if expected_package == 'com.fongmi.android.tv.preview':
        with zipfile.ZipFile(apk) as bundle:
            for name in ('libquickjs-android-wrapper.so', 'libavif_android.so', 'libarchive-jni.so', 'libconscrypt_jni.so'):
                entry = 'lib/x86_64/' + name
                assert bundle.getinfo(entry).file_size > 0, f'Missing prepared preview JNI: {entry}'
                libraries.append(entry)
    subprocess.run(['adb', 'install', '-r', str(apk)], check=True)
    packages.append({'applicationId': expected_package, 'apk': str(apk),
                     'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'previewJniLibraries': libraries})
summary = {'headSha': os.environ.get('GITHUB_SHA'), 'api': actual_api, 'abi': abi,
           'previewJniPrepared': True, 'packages': packages}
(root / 'build-metadata.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
PY

adb shell wm size 1920x1080
adb shell wm density 320
adb shell wm size > "$out/display-size.txt"
adb shell wm density > "$out/display-density.txt"
adb shell settings put secure immersive_mode_confirmations confirmed
if [ "$api" -ge 33 ]; then
    adb shell pm grant com.fongmi.android.tv.preview android.permission.POST_NOTIFICATIONS
fi
# Evidence must come from this run, including when a cached APK is installed again.
adb shell run-as com.fongmi.android.tv.preview rm -rf /sdcard/Android/data/com.fongmi.android.tv.preview/files/native-usability-evidence
run_status=0
if UPSTREAM_NATIVE_UI_EVIDENCE=true bash .github/scripts/verify-upstream-features.sh "$classes" "$out" '8,4'; then
    run_status=0
else
    run_status=$?
fi
printf '%s\n' "$run_status" > "$out/run.status"
collect

python3 - "$out" "$api" "$run_status" <<'PY'
import hashlib, json, os, pathlib, re, sys, xml.etree.ElementTree as ET
from PIL import Image

root = pathlib.Path(sys.argv[1])
api = int(sys.argv[2])
run_status = int(sys.argv[3])
expected = {
    'com.fongmi.android.tv.ui.activity.AiringCalendarAndroidTest': 8,
    'com.fongmi.android.tv.ui.activity.LibraryFollowAndroidTest': 4,
}
path = root / 'suite-summary.json'
suites = json.loads(path.read_text()) if path.is_file() else {}
counts = {suite.get('class'): suite.get('tests') for suite in suites.get('suites', [])}

# Preserve individual AndroidJUnitRunner test events as a standard JUnit report.
junit = ET.Element('testsuites')
case_counts = {}
failures = skipped = 0
for name in expected:
    report = root / f'{name}.txt'
    content = report.read_text(errors='replace') if report.is_file() else ''
    fields = {}
    cases = []
    for line in content.splitlines():
        match = re.match(r'^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$', line)
        if match:
            fields[match[1]] = match[2]
        code = re.match(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', line)
        if code:
            status = int(code[1])
            if status <= 0 and fields.get('class') == name and fields.get('test'):
                cases.append({**fields, 'status': status})
            fields = {}
    case_counts[name] = len(cases)
    suite_failures = sum(case['status'] < 0 and case['status'] not in (-3, -4) for case in cases)
    suite_skipped = sum(case['status'] in (-3, -4) for case in cases)
    failures += suite_failures
    skipped += suite_skipped
    suite = ET.SubElement(junit, 'testsuite', name=name, tests=str(len(cases)),
                          failures=str(suite_failures), errors='0', skipped=str(suite_skipped))
    for case in cases:
        testcase = ET.SubElement(suite, 'testcase', classname=name, name=case['test'])
        if case['status'] in (-3, -4):
            ET.SubElement(testcase, 'skipped').text = case.get('stack', 'Android runner skipped this test')
        elif case['status'] != 0:
            ET.SubElement(testcase, 'failure', message=f"Android status {case['status']}").text = case.get('stack', content)
    ET.SubElement(suite, 'system-out').text = content
ET.ElementTree(junit).write(root / 'TEST-airing-calendar-preview.xml', encoding='utf-8', xml_declaration=True)

required_images = (
    'calendar-library.png', 'calendar-simple.png', 'calendar-classic.png',
    'calendar-artwork-dark.png', 'calendar-artwork-light.png',
    'calendar-artwork-logo-loaded.png', 'calendar-artwork-next-logo-loaded.png',
    'library-follow-no-source.png', 'library-follow-calendar.png',
)
images = []
for name in required_images:
    image_path = root / 'native-usability-evidence' / name
    result = {'file': str(image_path.relative_to(root)), 'passed': False}
    try:
        with Image.open(image_path) as image:
            image.load()
            colors = image.convert('RGB').resize((64, 36)).getcolors(64 * 36) or []
            result.update({'width': image.width, 'height': image.height, 'sampleColors': len(colors),
                           'sha256': hashlib.sha256(image_path.read_bytes()).hexdigest(),
                           'passed': image.format == 'PNG' and image.size == (1920, 1080) and len(colors) > 16})
    except (OSError, ValueError) as error:
        result['error'] = str(error)
    images.append(result)

passed = (run_status == 0 and suites.get('passed') is True and counts == expected
          and suites.get('passedTests') == 12 and case_counts == expected
          and failures == 0 and skipped == 0 and all(image['passed'] for image in images))
summary = {'headSha': os.environ.get('GITHUB_SHA'), 'api': api, 'passed': passed,
           'runStatus': run_status, 'passedTests': suites.get('passedTests', 0),
           'suites': suites.get('suites', []), 'junitCases': case_counts,
           'failures': failures, 'skipped': skipped, 'screenshots': images,
           'scope': 'Offline production TV calendar, exact-date TMDB fixtures, follow actions and rendered x86_64 preview UI'}
(root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
markdown = (f"Airing calendar API {api}: {'PASS' if passed else 'FAIL'}\n\n"
            f"Android tests: {summary['passedTests']}/12. Native screenshots: "
            f"{sum(image['passed'] for image in images)}/{len(images)}.\n")
(root / 'verification-summary.md').write_text(markdown)
step_summary = os.environ.get('GITHUB_STEP_SUMMARY')
if step_summary:
    with open(step_summary, 'a') as output:
        output.write(markdown + '\n')
print(json.dumps(summary, indent=2))
if not passed:
    raise SystemExit('Expected exactly 12 passing preview tests and all 9 native calendar/follow screenshots')
PY
