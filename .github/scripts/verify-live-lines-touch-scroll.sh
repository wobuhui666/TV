#!/usr/bin/env bash
# Install once, then verify issue #119 and existing source forms at both TV sizes.
set -euo pipefail
out=artifacts/live-lines-touch-scroll
classes=com.fongmi.android.tv.test.HomeTouchScrollTest,com.fongmi.android.tv.test.LiveLineDialogTest,com.fongmi.android.tv.model.SourceActionDialogTest,com.fongmi.android.tv.test.SpiderDialogTest
mkdir -p "$out"

python3 - "$out" <<'PY'
import json, os, pathlib, subprocess, sys
outputs = (
    ('app/build/outputs/apk/leanbackArm64_v8a/preview', 'com.fongmi.android.tv.preview'),
    ('app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview', 'com.fongmi.android.tv.preview.test'),
)
packages = []
for root, package in outputs:
    directory = pathlib.Path(root)
    metadata = json.loads((directory / 'output-metadata.json').read_text())
    assert len(metadata['elements']) == 1, 'Expected one isolated preview APK'
    assert metadata['applicationId'] == package, 'Unexpected APK application ID'
    apk = directory / metadata['elements'][0]['outputFile']
    subprocess.run(['adb', 'install', '-r', str(apk)], check=True)
    packages.append({'applicationId': package, 'apk': str(apk)})
summary = {'headSha': os.environ.get('GITHUB_SHA'), 'packages': packages}
(pathlib.Path(sys.argv[1]) / 'build-metadata.json').write_text(json.dumps(summary, indent=2) + '\n')
PY

adb shell settings put secure immersive_mode_confirmations confirmed
adb shell pm grant com.fongmi.android.tv.preview android.permission.POST_NOTIFICATIONS || true
frame_pid=''
case_out=''

stop_frames() {
    if [ -n "$frame_pid" ]; then
        kill "$frame_pid" >/dev/null 2>&1 || true
        wait "$frame_pid" >/dev/null 2>&1 || true
        frame_pid=''
    fi
}

collect_last_frame() {
    stop_frames
    if [ -n "$case_out" ]; then
        timeout --kill-after=5s 20s adb exec-out screencap -p > "$case_out/last-screen.png" 2>/dev/null || true
        timeout --kill-after=5s 20s adb shell dumpsys window > "$case_out/window.txt" 2>&1 || true
    fi
}
trap collect_last_frame EXIT

for display in 1920x1080:320 1280x720:240; do
    size="${display%:*}"
    density="${display##*:}"
    case_out="$out/${size}-density-${density}"
    mkdir -p "$case_out/frames"
    adb shell wm size "$size"
    adb shell wm density "$density"
    adb shell wm size > "$case_out/display-size.txt"
    adb shell wm density > "$case_out/display-density.txt"
    # Capture during execution: each suite closes its activity before the helper's final screenshot.
    (
        for frame in $(seq 1 240); do
            target="$case_out/frames/$(printf '%04d' "$frame").png"
            if ! timeout --kill-after=2s 10s adb exec-out screencap -p > "$target" 2>/dev/null; then
                rm -f "$target"
            fi
            sleep 3
        done
    ) &
    frame_pid=$!
    if UPSTREAM_NATIVE_UI_EVIDENCE=true bash .github/scripts/verify-upstream-features.sh "$classes" "$case_out" '3,3,6,5'; then
        printf '0\n' > "$case_out/run.status"
    else
        printf '%s\n' "$?" > "$case_out/run.status"
    fi
    collect_last_frame
done

python3 - "$out" <<'PY'
import json, os, pathlib, sys
root = pathlib.Path(sys.argv[1])
cases = []
for size, density in (('1920x1080', 320), ('1280x720', 240)):
    directory = root / f'{size}-density-{density}'
    path = directory / 'suite-summary.json'
    suites = json.loads(path.read_text()) if path.is_file() else {}
    counts = [suite.get('tests') for suite in suites.get('suites', [])]
    passed = (directory / 'run.status').read_text().strip() == '0' and suites.get('passed') is True and counts == [3, 3, 6, 5]
    cases.append({'size': size, 'density': density, 'passed': passed,
                  'passedTests': suites.get('passedTests', 0), 'suites': suites.get('suites', []),
                  'capturedFrames': len(list((directory / 'frames').glob('*.png')))})
summary = {'headSha': os.environ.get('GITHUB_SHA'), 'passed': all(case['passed'] for case in cases),
           'passedTests': sum(case['passedTests'] for case in cases), 'displays': cases,
           'scope': 'Offline production TV home, live lines, source configuration windows and Java/QuickJS forms; no live streams or Exo performance measurement'}
(root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
if not summary['passed']:
    raise SystemExit('Live line and touch scrolling UI verification failed')
PY
