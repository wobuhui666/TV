#!/usr/bin/env bash
set -euo pipefail
pkg=com.fongmi.android.tv.preview
out=artifacts/tv-ui
mkdir -p "$out"
collect() {
    adb pull "/sdcard/Android/data/$pkg/files/tv-verification/." "$out/" >/dev/null 2>&1 || true
    adb logcat -d -v brief | rg 'AndroidRuntime|FATAL|ANR|TvAppearanceTouchTest' > "$out/logcat.txt" || true
    adb shell dumpsys window | rg 'mCurrentFocus|mFocusedApp' > "$out/focus.txt" || true
}
trap collect EXIT
adb shell wm size 1920x1080
adb shell wm density 320
adb shell settings put secure immersive_mode_confirmations confirmed
adb install -r app/build/outputs/apk/leanbackArm64_v8a/preview/*.apk
adb install -r app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview/*.apk
adb logcat -c
adb shell am instrument -w -r -e class com.fongmi.android.tv.test.TvAppearanceTouchTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
if ! rg -q '^OK \(1 test\)' "$out/instrumentation.txt"; then
    echo 'TV UI instrumentation failed' >&2
    exit 1
fi
collect
python3 .github/scripts/check-tv-screenshots.py "$out"
