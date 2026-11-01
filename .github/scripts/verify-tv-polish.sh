#!/usr/bin/env bash
set -euo pipefail
out=artifacts/tv-polish
pkg=com.fongmi.android.tv.preview
test_pkg=com.fongmi.android.tv.preview.test
classes='com.fongmi.android.tv.ui.PosterWallFiltersIntegrationTest,com.fongmi.android.tv.test.TrackPresentationTest'
mkdir -p "$out"
collect() {
    adb pull "/sdcard/Android/data/$pkg/files/tv-polish-verification/." "$out/" >/dev/null 2>&1 || true
    adb exec-out screencap -p > "$out/last-screen.png" 2>/dev/null || true
}
trap collect EXIT
IFS=',' read -r -a suites <<< "$classes"
for suite in "${suites[@]}"; do
    adb logcat -c
    if timeout 300s adb shell am instrument -w -r -e class "$suite" \
        "$test_pkg/androidx.test.runner.AndroidJUnitRunner" > "$out/$suite.txt" 2>&1; then
        printf '0' > "$out/$suite.status"
    else
        printf '1' > "$out/$suite.status"
    fi
    # These suites intercept their synthetic API requests and never load the real source config.
    adb logcat -b all -d -v threadtime > "$out/$suite-logcat.txt" 2>&1 || true
    collect
done
python3 - "$out" "$classes" <<'PY'
import json, os, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
results = []
for name in sys.argv[2].split(','):
    content = (root / (name + '.txt')).read_text(errors='replace')
    match = re.search(r'^OK \(([1-9][0-9]*) tests?\)', content, re.M)
    passed = match is not None and (root / (name + '.status')).read_text() == '0'
    results.append({'class': name, 'passed': passed, 'tests': int(match[1]) if match else 0})
summary = {'headSha': os.environ.get('GITHUB_SHA'), 'passed': all(item['passed'] for item in results),
           'suites': results, 'passedTests': sum(item['tests'] for item in results if item['passed'])}
(root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
assert summary['passed'], 'Poster-wall or subtitle presentation regression failed'
PY
