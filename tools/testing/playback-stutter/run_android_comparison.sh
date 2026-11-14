#!/usr/bin/env bash
set -euo pipefail

output=artifacts/playback-stutter
mkdir -p "$output"
python3 - <<'PY'
import json
from pathlib import Path
import subprocess
for name in ('app/build/outputs/apk/leanbackArm64_v8a/preview',
             'app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview'):
    root = Path(name)
    metadata = json.loads((root / 'output-metadata.json').read_text())
    subprocess.run(['adb', 'install', '-r', str(root / metadata['elements'][0]['outputFile'])], check=True)
PY
adb shell wm size 1920x1080
adb shell wm density 320
tar -cf "$RUNNER_TEMP/playback-media.tar" -C "$RUNNER_TEMP/playback-media" .
adb push "$RUNNER_TEMP/playback-media.tar" /data/local/tmp/playback-media.tar
adb shell 'run-as com.fongmi.android.tv.preview mkdir -p files/core-fixtures'
adb shell 'cat /data/local/tmp/playback-media.tar | run-as com.fongmi.android.tv.preview tar -xf - -C files/core-fixtures'
adb shell getprop > "$output/device-properties.txt"
adb shell dumpsys media.codec > "$output/codecs-before.txt"
adb logcat -c
collect() {
    adb pull /sdcard/Android/data/com.fongmi.android.tv.preview/files/playback-stutter-evidence "$output/" >/dev/null 2>&1 || true
    adb logcat -b all -d -v threadtime > "$output/logcat.txt" 2>&1 || true
    adb shell dumpsys meminfo com.fongmi.android.tv.preview > "$output/meminfo-after.txt" 2>&1 || true
    adb shell dumpsys media.codec > "$output/codecs-after.txt" 2>&1 || true
}
trap collect EXIT
adb shell am instrument -w -r -e core_fixture_root core-fixtures \
  -e class com.fongmi.android.tv.player.exo.ExoDecodeSelectionAndroidTest,com.fongmi.android.tv.player.exo.ExoHighBitratePlaybackTest \
  com.fongmi.android.tv.preview.test/androidx.test.runner.AndroidJUnitRunner | tee "$output/instrumentation.txt"
collect
python3 - <<'PY'
import json
from pathlib import Path
import re
root = Path('artifacts/playback-stutter')
value = (root / 'instrumentation.txt').read_text()
assert re.search(r'^OK \(7 tests\)', value, re.M), 'Expected all six mapping tests and one playback measurement test'
assert not re.search(r'INSTRUMENTATION_STATUS_CODE: -(3|4)', value), 'No ignored or assumption-skipped tests'
reports = list(root.rglob('playback-summary.json'))
assert len(reports) == 1, reports
report = json.loads(reports[0].read_text())
assert report['controlsPassed'] and len(report['cases']) == 20
assert any(case['file'] == 'high2160p.mp4' for case in report['cases'])
assert {case['danmaku'] for case in report['cases']} >= {'none', 'patched', 'legacy'}
log = (root / 'logcat.txt').read_text()
assert not re.search(r'Libass.*(Initialization failed|Native renderer unavailable|Render failed)', log), 'Native ASS comparison must render successfully'
print(json.dumps({'controlsPassed': report['controlsPassed'], 'cases': len(report['cases']),
                  'fourKCases': [case for case in report['cases'] if case['file'] == 'high2160p.mp4']}, indent=2))
PY
