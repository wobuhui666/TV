#!/usr/bin/env bash
# Run only on the disposable GHA preview emulator, using a credential outside the APK.
set -euo pipefail
out=artifacts/live-home
mkdir -p "$out"
python3 - <<'PY'
import json, os, pathlib, subprocess

for root in ('app/build/outputs/apk/leanbackArm64_v8a/preview',
             'app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview'):
    directory = pathlib.Path(root)
    data = json.loads((directory / 'output-metadata.json').read_text())
    assert len(data['elements']) == 1
    assert data['applicationId'] in ('com.fongmi.android.tv.preview', 'com.fongmi.android.tv.preview.test')
    subprocess.run(['adb', 'install', '-r', str(directory / data['elements'][0]['outputFile'])], check=True)

credential = pathlib.Path(os.environ['RUNNER_TEMP']) / 'tv-tmdb-preview.key'
content = credential.read_bytes()
assert len(content.strip()) == 32, 'A runtime TMDB credential is required'
subprocess.run(['adb', 'shell', 'run-as', 'com.fongmi.android.tv.preview', 'mkdir', '-p', 'files'], check=True)
subprocess.run(['adb', 'exec-in', 'run-as', 'com.fongmi.android.tv.preview',
                'tee', 'files/tmdb-preview.key'], input=content, stdout=subprocess.DEVNULL, check=True)
PY
adb shell wm size 1920x1080
adb shell wm density 320
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell pm grant com.fongmi.android.tv.preview android.permission.POST_NOTIFICATIONS || true
collect_live() {
    adb pull /sdcard/Android/data/com.fongmi.android.tv.preview/files/native-usability-evidence "$out/" >/dev/null 2>&1 || true
    adb pull /sdcard/Android/data/com.fongmi.android.tv.preview/files/live-home-verification "$out/" >/dev/null 2>&1 || true
    adb logcat -b all -d -v threadtime > "$out/logcat.txt" 2>&1 || true
    adb exec-out screencap -p > "$out/last-screen.png" 2>/dev/null || true
    adb shell run-as com.fongmi.android.tv.preview rm files/tmdb-preview.key >/dev/null 2>&1 || true
    python3 - <<'PY'
import os, pathlib, re
credential = pathlib.Path(os.environ['RUNNER_TEMP']) / 'tv-tmdb-preview.key'
secret = credential.read_text().strip() if credential.exists() else ''
for path in pathlib.Path('artifacts/live-home').rglob('*'):
    if path.is_file() and path.suffix in ('.txt', '.json', '.xml', '.log'):
        content = path.read_text(errors='replace')
        if secret:
            content = content.replace(secret, '[REDACTED]')
        content = re.sub(r'(?i)(api_key(?:=|%3[dD]))[a-z0-9]+', r'\1[REDACTED]', content)
        path.write_text(content)
PY
}
trap collect_live EXIT
adb logcat -c
# Keep runner output private until redaction. URLs in exception text can carry API credentials.
set +e
adb shell am instrument -w -r -e native_ui_evidence true \
    -e class com.fongmi.android.tv.test.LiveHomePreviewTest \
    com.fongmi.android.tv.preview.test/androidx.test.runner.AndroidJUnitRunner > "$out/instrumentation.txt" 2>&1
runner_status=$?
set -e
collect_live
python3 - "$runner_status" <<'PY'
import json, os, pathlib, re, sys
root = pathlib.Path('artifacts/live-home')
content = (root / 'instrumentation.txt').read_text()
print(content)
match = re.search(r'^OK \(([1-9][0-9]*) tests?\)', content, re.M)
summary = {'headSha': os.environ['GITHUB_SHA'], 'passed': bool(match) and sys.argv[1] == '0',
           'passedTests': int(match[1]) if match else 0, 'liveMetadata': True, 'connection': 'direct'}
(root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
if not summary['passed']:
    raise SystemExit('Live homepage verification failed')
PY
