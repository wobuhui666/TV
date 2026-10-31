#!/usr/bin/env bash
set -euo pipefail
out=artifacts/app-responsiveness
mkdir -p "$out"
read_metadata() {
    python3 - "$1" "$2" <<'PY'
import json, pathlib, re, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
assert data['applicationId'] == 'com.fongmi.android.tv.preview' + ('.test' if 'androidTest' in path.parts else '')
assert len(data['elements']) == 1
print(data['applicationId'] if sys.argv[2] == 'package' else path.parent / data['elements'][0]['outputFile'])
PY
}
metadata=app/build/outputs/apk/leanbackArm64_v8a/preview/output-metadata.json
test_metadata=app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview/output-metadata.json
pkg=$(read_metadata "$metadata" package)
test_pkg=$(read_metadata "$test_metadata" package)
uid=''
collect() {
    adb pull "/sdcard/Android/data/$pkg/files/responsiveness-verification/." "$out/" >/dev/null 2>&1 || true
    adb shell dumpsys meminfo "$pkg" > "$out/meminfo.txt" 2>&1 || true
    adb shell dumpsys gfxinfo "$pkg" > "$out/gfxinfo.txt" 2>&1 || true
    # Live source logging may contain URLs/headers. Export counts, never raw source logs.
    adb logcat -b all -d -v brief > "$RUNNER_TEMP/responsiveness-logcat.txt" 2>&1 || true
    if [[ "$uid" =~ ^[0-9]+$ ]]; then
        adb logcat -b all -d -v brief --uid="$uid" > "$RUNNER_TEMP/responsiveness-app-logcat.txt" 2>&1 || true
    else
        touch "$RUNNER_TEMP/responsiveness-app-logcat.txt"
    fi
    python3 - "$RUNNER_TEMP/responsiveness-app-logcat.txt" "$RUNNER_TEMP/responsiveness-logcat.txt" "$out/health.json" <<'PY'
import json, pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')
system = pathlib.Path(sys.argv[2]).read_text(errors='replace')
data = {'fatalExceptionCount': len(re.findall(r'FATAL EXCEPTION:', text)),
        'anrCount': len(re.findall(r'ANR in com\.fongmi\.android\.tv\.preview', system)),
        'nativeFatalSignalCount': len(re.findall(r'Fatal signal \d+', text))}
pathlib.Path(sys.argv[3]).write_text(json.dumps(data, indent=2) + '\n')
source = {'wrongElfMachine': bool(re.search(r'EM_AARCH64[^\n]*EM_X86_64', text)),
          'failureTypes': [name for name in ('UnsatisfiedLinkError', 'ExceptionInInitializerError',
              'NoClassDefFoundError', 'InvocationTargetException') if name in text]}
pathlib.Path(sys.argv[3]).with_name('source-runtime-diagnostics.json').write_text(json.dumps(source, indent=2) + '\n')
PY
}
trap collect EXIT
adb shell wm size 1920x1080
adb shell wm density 320
adb shell settings put secure immersive_mode_confirmations confirmed
# Preserve production animation timing for both comparison runs.
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$setting" 1
done
adb install -r "$(read_metadata "$metadata" apk)"
adb install -r "$(read_metadata "$test_metadata" apk)"
uid=$(adb shell cmd package list packages -U "$pkg" | python3 -c 'import re, sys; match = re.search(r"^package:" + re.escape(sys.argv[1]) + r" uid:([0-9]+)\s*$", sys.stdin.read(), re.M); assert match, "Target application UID missing"; print(match.group(1))' "$pkg")
[[ "$uid" =~ ^[0-9]+$ ]]
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
if [[ "${RESPONSIVENESS_REVISION:-optimized}" == optimized ]]; then
    # Existing fixture tests synchronize on UI idle. Keep their animation policy,
    # then restore production timing before collecting any live measurements.
    for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
        adb shell settings put global "$setting" 0
    done
    classes='com.fongmi.android.tv.player.parse.ParseJobLifecycleTest,com.fongmi.android.tv.ui.NativeBrowseIntegrationTest#searchReleasesOffscreenViewsAndKeepsLoadedPagesAndLateResults,com.fongmi.android.tv.ui.HistoryActionsIntegrationTest#legacyHomeClearRunsInTheQueueAndKeepsTheCapturedConfiguration'
    timeout 240s adb shell am instrument -w -r -e class "$classes" "$test_pkg/androidx.test.runner.AndroidJUnitRunner" | tee "$out/regression.txt"
    grep -Eq '^OK [(][1-9][0-9]* tests?[)]' "$out/regression.txt"
    # The live probe starts with a fresh application/configuration after fixture-based regressions.
    adb shell am force-stop "$pkg"
    adb shell pm clear "$pkg" >/dev/null
    adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
fi
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$setting" 1
done
adb logcat -c
python3 - "$out/config-metadata.json" <<'PY'
import hashlib, json, pathlib, sys, urllib.request
with urllib.request.urlopen('https://9280.kstore.vip/aiwex.json', timeout=30) as response:
    body = response.read(2 * 1024 * 1024 + 1)
assert len(body) <= 2 * 1024 * 1024, 'Unexpected configuration size'
config = json.loads(body)
pathlib.Path(sys.argv[1]).write_text(json.dumps({'sha256': hashlib.sha256(body).hexdigest(),
    'bytes': len(body), 'siteCount': len(config.get('sites', []))}, indent=2) + '\n')
PY
timeout 420s adb shell am instrument -w -r \
    -e performance_config_url https://9280.kstore.vip/aiwex.json \
    -e class com.fongmi.android.tv.test.AppResponsivenessTest \
    "$test_pkg/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
grep -Eq '^OK [(]1 test[)]' "$out/instrumentation.txt"
collect
python3 - "$out" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
report = json.loads((root / 'report.json').read_text())
assert report['passed'], 'Live configuration/navigation probe failed'
assert report['frameCount'] > 0, 'No rendered frame metrics were captured'
health = json.loads((root / 'health.json').read_text())
assert not any(health.values()), 'Crash or ANR found during live probe'
print(json.dumps({'siteCount': report['siteCount'], 'configLoadMs': report['configLoadMs'],
                  'frameCount': report['frameCount'], 'sourceAvailability': report['sourceAvailability'],
                  'sourceHomeContentVerified': report['sourceHomeContentVerified'],
                  'sourceSearchContentVerified': report['sourceSearchContentVerified'],
                  'ordinarySearchHasResults': report['ordinarySearchHasResults'], 'health': health}, indent=2))
PY
