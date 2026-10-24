#!/usr/bin/env bash
set -euo pipefail
out=artifacts/tv-ui
mkdir -p "$out"
# Gradle metadata follows tvApplicationId / TV_APPLICATION_ID, including coexist builds.
metadata=app/build/outputs/apk/leanbackArm64_v8a/preview/output-metadata.json
test_metadata=app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview/output-metadata.json
read_metadata() {
    python3 - "$1" "$2" <<'PY'
import json, pathlib, re, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
assert re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+", data["applicationId"])
assert len(data["elements"]) == 1, "Expected one ARM64 APK"
print(data["applicationId"] if sys.argv[2] == "package" else path.parent / data["elements"][0]["outputFile"])
PY
}
pkg=$(read_metadata "$metadata" package)
test_pkg=$(read_metadata "$test_metadata" package)
apk=$(read_metadata "$metadata" apk)
test_apk=$(read_metadata "$test_metadata" apk)
printf 'Application: %s\nInstrumentation: %s\n' "$pkg" "$test_pkg" > "$out/packages.txt"
collect() {
    adb pull "/sdcard/Android/data/$pkg/files/tv-verification/." "$out/" >/dev/null 2>&1 || true
    adb logcat -b all -d -v threadtime > "$out/logcat-full.txt" 2>&1 || true
    grep -E 'AndroidRuntime|FATAL|ANR|TvAppearanceTouchTest|CustomActivityOnCrash' "$out/logcat-full.txt" > "$out/logcat.txt" || true
    adb shell dumpsys activity exit-info "$pkg" > "$out/exit-info.txt" 2>&1 || true
    adb shell dumpsys meminfo "$pkg" > "$out/meminfo.txt" 2>&1 || true
    adb shell dumpsys dropbox --print data_app_crash > "$out/dropbox-java-crashes.txt" 2>&1 || true
    adb shell dumpsys dropbox --print data_app_native_crash > "$out/dropbox-native-crashes.txt" 2>&1 || true
    adb exec-out run-as "$pkg" tar -c -f - files/diagnostics > "$out/app-diagnostics.tar" 2> "$out/app-diagnostics-collection.txt" || true
    adb exec-out screencap -p > "$out/last-screen.png" 2>/dev/null || true
    adb shell uiautomator dump /sdcard/tv-ui-last.xml >/dev/null 2>&1 || true
    adb pull /sdcard/tv-ui-last.xml "$out/last-hierarchy.xml" >/dev/null 2>&1 || true
    adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' > "$out/focus.txt" || true
}
trap collect EXIT
adb shell wm size 1920x1080
adb shell wm density 320
adb shell settings put secure immersive_mode_confirmations confirmed
adb install -r "$apk"
adb install -r "$test_apk"
adb shell pm list instrumentation | tee "$out/runners.txt"
grep -F "instrumentation:$test_pkg/androidx.test.runner.AndroidJUnitRunner (target=$pkg)" "$out/runners.txt"
adb logcat -c
adb shell am instrument -w -r -e class com.fongmi.android.tv.test.TvAppearanceTouchTest "$test_pkg/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
if ! grep -Eq '^OK [(]1 test[)]' "$out/instrumentation.txt"; then
    echo 'TV UI instrumentation failed' >&2
    exit 1
fi
collect
python3 .github/scripts/check-tv-screenshots.py "$out"
