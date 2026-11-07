#!/usr/bin/env bash
# Install only the disposable preview APKs; run each Android/JNI suite independently.
# The Java runtime test also constructs the Android-compatible default ByteArrayMemory by reflection.
set -euo pipefail
api="${1:?Pass the emulator API level}"
[[ "$api" = 24 || "$api" = 34 ]] || exit 1
out="artifacts/wasm/api-$api"
mkdir -p "$out"
python3 - <<'PY'
import json, pathlib, subprocess
for root, package in (
    ('app/build/outputs/apk/leanbackArm64_v8a/preview', 'com.fongmi.android.tv.preview'),
    ('app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview', 'com.fongmi.android.tv.preview.test'),
):
    directory = pathlib.Path(root)
    metadata = json.loads((directory / 'output-metadata.json').read_text())
    assert metadata['applicationId'] == package, 'Only the isolated preview is accepted'
    assert len(metadata['elements']) == 1
    subprocess.run(['adb', 'install', '-r', str(directory / metadata['elements'][0]['outputFile'])], check=True)
PY
collect() {
    for report in quickjs-wasm-result.json quickjs-api-result.json spider-dex-result.json; do
        adb exec-out run-as com.fongmi.android.tv.preview cat "files/$report" > "$out/$report" 2>/dev/null || true
    done
    adb logcat -b all -d -v threadtime > "$out/logcat.txt" 2>&1 || true
    adb shell dumpsys activity exit-info com.fongmi.android.tv.preview > "$out/exit-info.txt" 2>&1 || true
}
trap collect EXIT
bash .github/scripts/verify-upstream-features.sh \
    com.fongmi.android.tv.test.QuickJsWasmApiTest,com.fongmi.android.tv.test.QuickJsSpiderApiTest,com.fongmi.android.tv.test.SpiderDexCompatibilityTest \
    "$out" 11,1,1
collect
python3 - "$out" "$api" <<'PY'
import json, os, pathlib, sys
root = pathlib.Path(sys.argv[1])
summary = json.loads((root / 'suite-summary.json').read_text())
summary.update({'apiLevel': int(sys.argv[2]), 'expectedTests': 13, 'applicationId': 'com.fongmi.android.tv.preview',
                'scope': 'Real Android QuickJS JNI and Chicory interpreter, Java spider ownership, ByteBufferMemory and default ByteArrayMemory read/write/grow; loopback fixtures; API 24 and 34 run independently'})
assert summary['passed'] and summary['passedTests'] == 13
wasm = json.loads((root / 'quickjs-wasm-result.json').read_text())
assert wasm['expectedTests'] == 11 and len(wasm['tests']) == 11
assert all(test['status'] == 'PASS' for test in wasm['tests'].values())
for name in ('quickjs-api-result.json', 'spider-dex-result.json'):
    assert json.loads((root / name).read_text())['status'] == 'PASS', name
(root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
PY
