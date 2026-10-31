#!/usr/bin/env bash
set -euo pipefail
out=artifacts/fongmi-features
mkdir -p "$out"
pkg=com.fongmi.android.tv.preview
test_pkg=com.fongmi.android.tv.preview.test
classes='com.fongmi.android.tv.test.SpiderDialogTest,com.fongmi.android.tv.player.media.LocalSubtitleScannerAndroidTest,com.fongmi.android.tv.ui.custom.SeekKeyOwnershipAndroidTest'
collect() {
    adb pull "/sdcard/Android/data/$pkg/files/spider-dialog-verification/." "$out/spider-dialog/" >/dev/null 2>&1 || true
    adb exec-out screencap -p > "$out/last-screen.png" 2>/dev/null || true
}
trap collect EXIT
IFS=',' read -r -a suites <<< "$classes"
for suite in "${suites[@]}"; do
    [[ "$suite" =~ ^[A-Za-z_][A-Za-z0-9_.]*$ ]]
    adb logcat -c
    if timeout 240s adb shell am instrument -w -r -e class "$suite" \
        "$test_pkg/androidx.test.runner.AndroidJUnitRunner" > "$out/$suite.txt" 2>&1; then
        printf '0' > "$out/$suite.status"
    else
        printf '1' > "$out/$suite.status"
    fi
    # These suites use only synthetic source objects and loopback media.
    adb logcat -b all -d -v threadtime > "$out/$suite-logcat.txt" 2>&1 || true
    adb exec-out screencap -p > "$out/$suite-screen.png" 2>/dev/null || true
done
collect
python3 - "$out" "$classes" <<'PY'
import json, os, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
results = []
for name in sys.argv[2].split(','):
    content = (root / (name + '.txt')).read_text(errors='replace')
    matched = re.search(r'^OK \(([1-9][0-9]*) tests?\)', content, re.M)
    passed = matched is not None and (root / (name + '.status')).read_text() == '0'
    results.append({'class': name, 'passed': passed, 'tests': int(matched[1]) if matched else 0})
summary = {'headSha': os.environ.get('GITHUB_SHA'), 'passed': all(item['passed'] for item in results),
           'suites': results, 'passedTests': sum(item['tests'] for item in results if item['passed'])}
(root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
assert summary['passed'], 'One or more FongMi feature regression suites failed'
PY
