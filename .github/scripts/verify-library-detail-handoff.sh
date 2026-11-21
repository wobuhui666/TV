#!/usr/bin/env bash
# Verify the production handoff and controller using offline fixtures in one API 34 process.
set -euo pipefail
out=artifacts/library-detail-handoff
class=com.fongmi.android.tv.playback.vod.LibraryDetailHandoffAndroidTest
mkdir -p "$out/unit-tests"

python3 - "$out" <<'PY'
import json, os, pathlib, shutil, subprocess, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
expected = {
    'com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetchTest': 11,
    'com.fongmi.android.tv.playback.vod.VodPlaybackControllerTest': 8,
}
suites = []
for path in sorted(pathlib.Path('app/build/test-results/testLeanbackArm64_v8aPreviewUnitTest').glob('TEST-*.xml')):
    suite = ET.parse(path).getroot()
    suites.append({'class': suite.get('name'),
                   **{field: int(suite.get(field, 0)) for field in ('tests', 'failures', 'errors', 'skipped')}})
    shutil.copy2(path, root / 'unit-tests' / path.name)
assert {suite['class']: suite['tests'] for suite in suites} == expected, suites
assert all(suite['failures'] == suite['errors'] == suite['skipped'] == 0 for suite in suites), suites
(root / 'unit-tests/summary.json').write_text(json.dumps({
    'headSha': os.environ['GITHUB_SHA'], 'passed': True, 'tests': 19, 'suites': suites,
}, indent=2) + '\n')

api = int(subprocess.check_output(['adb', 'shell', 'getprop', 'ro.build.version.sdk'], text=True).strip())
abi = subprocess.check_output(['adb', 'shell', 'getprop', 'ro.product.cpu.abi'], text=True).strip()
assert api == 34 and abi == 'x86_64', (api, abi)
outputs = (
    ('app/build/outputs/apk/leanbackArm64_v8a/preview', 'com.fongmi.android.tv.preview'),
    ('app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview', 'com.fongmi.android.tv.preview.test'),
)
packages = []
for directory, expected_package in outputs:
    directory = pathlib.Path(directory)
    metadata = json.loads((directory / 'output-metadata.json').read_text())
    assert len(metadata['elements']) == 1 and metadata['applicationId'] == expected_package, metadata
    apk = directory / metadata['elements'][0]['outputFile']
    subprocess.run(['adb', 'install', '-r', str(apk)], check=True)
    packages.append({'applicationId': expected_package, 'apk': str(apk)})
(root / 'build-metadata.json').write_text(json.dumps({
    'headSha': os.environ['GITHUB_SHA'], 'api': api, 'abi': abi, 'packages': packages,
}, indent=2) + '\n')
PY

run_status=0
UPSTREAM_NATIVE_UI_EVIDENCE=false bash .github/scripts/verify-upstream-features.sh "$class" "$out" '3' || run_status=$?

python3 - "$out" "$class" "$run_status" <<'PY'
import json, os, pathlib, re, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
name = sys.argv[2]
expected = {
    'shouldDeepCopyTheSelectedPlaylistAndConsumeItsTokenOnce',
    'shouldInvalidateAHandedOffDetailWhenItsPlaybackContextChanges',
    'shouldReusePreparedDetailWithoutRepeatingTheSourceRequest',
}
content = (root / (name + '.txt')).read_text(errors='replace')
fields, cases = {}, []
for line in content.splitlines():
    match = re.match(r'^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$', line)
    if match:
        fields[match[1]] = match[2]
    code = re.match(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', line)
    if code:
        status = int(code[1])
        if status <= 0 and fields.get('class') == name and fields.get('test'):
            cases.append({'test': fields['test'], 'status': status})
        fields = {}
summary = json.loads((root / 'suite-summary.json').read_text())
passed = (int(sys.argv[3]) == 0 and summary.get('passed') is True
          and summary.get('passedTests') == 3 and len(cases) == 3
          and {case['test'] for case in cases} == expected
          and all(case['status'] == 0 for case in cases))
suite = ET.Element('testsuite', name=name, tests=str(len(cases)), errors='0',
                   failures=str(sum(case['status'] not in (0, -3, -4) for case in cases)),
                   skipped=str(sum(case['status'] in (-3, -4) for case in cases)))
for case in cases:
    testcase = ET.SubElement(suite, 'testcase', classname=name, name=case['test'])
    if case['status'] in (-3, -4):
        ET.SubElement(testcase, 'skipped')
    elif case['status'] != 0:
        ET.SubElement(testcase, 'failure', message=f"Android status {case['status']}").text = content
ET.ElementTree(suite).write(root / 'TEST-library-detail-handoff.xml', encoding='utf-8', xml_declaration=True)
result = {'headSha': os.environ['GITHUB_SHA'], 'api': 34, 'passed': passed,
          'expectedTests': 3, 'completedTests': len(cases), 'cases': cases,
          'scope': 'Offline production detail handoff, Parcel deep copy, one-time token, context rejection and controller reuse'}
(root / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
if not passed:
    raise SystemExit('Require all three library detail handoff Android tests, no failures or skips')
PY
