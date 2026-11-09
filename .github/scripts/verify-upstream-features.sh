#!/usr/bin/env bash
# Run after the isolated preview and instrumentation APKs have been installed.
set -euo pipefail
classes="${1:?Pass the comma-separated regression test classes}"
out="${2:-artifacts/upstream-features}"
expected_counts="${3:-}"
native_ui_evidence="${UPSTREAM_NATIVE_UI_EVIDENCE:-false}"
mkdir -p "$out"
read_package() {
    python3 - "$1" <<'PY'
import json, re, sys
package = json.load(open(sys.argv[1]))['applicationId']
assert re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+', package)
print(package)
PY
}
pkg=$(read_package app/build/outputs/apk/leanbackArm64_v8a/preview/output-metadata.json)
test_pkg=$(read_package app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview/output-metadata.json)
live_logcat_pid=''
stop_live_logcat() {
    if [ -n "$live_logcat_pid" ]; then
        kill "$live_logcat_pid" >/dev/null 2>&1 || true
        live_logcat_pid=''
    fi
}
collect() {
    stop_live_logcat
    for directory in upstream-verification spider-dialog-verification folder-filter-verification native-usability-evidence community-skip-verification; do
        timeout --kill-after=5s 30s adb pull "/sdcard/Android/data/$pkg/files/$directory" "$out/" >/dev/null 2>&1 || true
    done
    timeout --kill-after=5s 20s adb logcat -b all -d -v threadtime > "$out/logcat.txt" 2>&1 || true
    timeout --kill-after=5s 20s adb exec-out screencap -p > "$out/last-screen.png" 2>/dev/null || true
    timeout --kill-after=5s 20s adb shell dumpsys activity exit-info "$pkg" > "$out/exit-info.txt" 2>&1 || true
}
trap collect EXIT
IFS=',' read -r -a suites <<< "$classes"
for suite in "${suites[@]}"; do
    [[ "$suite" =~ ^[a-zA-Z_][a-zA-Z0-9_.]*$ ]] || exit 1
    printf '%s: Starting %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$suite" | tee -a "$out/progress.txt"
    timeout --kill-after=5s 20s adb logcat -c || true
    # Preserve logs written before a device disconnect; a final dump may no longer be available.
    timeout --kill-after=5s 390s adb logcat -b all -v threadtime > "$out/$suite-live-logcat.txt" 2>&1 &
    live_logcat_pid=$!
    # A crash in one feature must not prevent the other features from producing evidence.
    # The final summary still fails the job unless every instrumentation invocation passes.
    if timeout --kill-after=10s 360s adb shell am instrument -w -r -e native_ui_evidence "$native_ui_evidence" -e class "$suite" \
        "$test_pkg/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$out/$suite.txt"; then
        printf '0' > "$out/$suite.status"
    else
        printf '%s' "$?" > "$out/$suite.status"
    fi
    stop_live_logcat
    printf '%s: Completed %s (status=%s)\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$suite" "$(cat "$out/$suite.status")" | tee -a "$out/progress.txt"
    timeout --kill-after=5s 20s adb logcat -b all -d -v threadtime > "$out/$suite-logcat.txt" 2>&1 || true
    timeout --kill-after=5s 20s adb exec-out screencap -p > "$out/$suite-screen.png" 2>/dev/null || true
done
python3 - "$out" "$classes" "$expected_counts" <<'PY'
from pathlib import Path
import json, os, re, sys
root = Path(sys.argv[1])
results = []
output = []
names = sys.argv[2].split(',')
expected = [int(value) for value in sys.argv[3].split(',')] if sys.argv[3] else []
if expected and (len(expected) != len(names) or any(value <= 0 for value in expected)):
    raise SystemExit('Expected test counts must match the regression suites')
for index, name in enumerate(names):
    content = (root / (name + '.txt')).read_text(errors='replace')
    output.extend([name, content])
    match = re.search(r'^OK \(([1-9][0-9]*) tests?\)', content, re.M)
    tests = int(match[1]) if match else None
    passed = match is not None and (root / (name + '.status')).read_text() == '0'
    result = {'class': name, 'passed': passed, 'tests': tests}
    if expected:
        result['expectedTests'] = expected[index]
        result['passed'] = passed and tests == expected[index]
    results.append(result)
passed = all(item['passed'] for item in results)
summary = {'headSha': os.environ.get('GITHUB_SHA'), 'passed': passed, 'suites': results,
           'passedTests': sum(item['tests'] or 0 for item in results if item['passed'])}
(root / 'suite-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
(root / 'instrumentation.txt').write_text('\n'.join(output))
print(json.dumps(summary, indent=2))
if not passed:
    raise SystemExit('One or more upstream feature instrumentation suites failed')
PY
