#!/usr/bin/env bash
set -euo pipefail

out=artifacts/library-native-probe
mkdir -p "$out"
test "${TV_EMULATOR_API:?Missing emulator API}" = 31
test "${TV_LIBRARY_NATIVE_PROBE:-false}" = true
test -n "${GITHUB_SHA:?Missing source commit}"
mapfile -t packages < <(python3 - <<'PY'
import json
from pathlib import Path
main = json.loads(Path('app/build/outputs/apk/leanbackArm64_v8a/preview/output-metadata.json').read_text())
tests = json.loads(Path('app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview/output-metadata.json').read_text())
package, runner = main['applicationId'], tests['applicationId']
assert package.endswith('.preview') and runner == package + '.test'
print(package)
print(runner)
PY
)
test "${#packages[@]}" = 2
pkg=${packages[0]}
test_pkg=${packages[1]}
report_path="/sdcard/Android/data/$pkg/files/ech-validation/preview-native-probe.json"

collect_probe() {
    adb pull "$report_path" "$out/report.json" >/dev/null 2>&1 || true
    adb logcat -b crash -d -v threadtime > "$out/crash-buffer.txt" 2>&1 || true
    adb shell dumpsys activity exit-info "$pkg" > "$out/exit-info.txt" 2>&1 || true
}
trap collect_probe EXIT
adb shell am force-stop "$pkg"
adb shell rm -f "$report_path" "$report_path.tmp"
adb logcat -b crash -c
set +e
timeout 960s adb shell am instrument -w -r \
    -e class com.fongmi.android.tv.test.PreviewConscryptNativeProbeAndroidTest \
    -e library_native_probe true \
    -e probe_expected_api "$TV_EMULATOR_API" \
    -e probe_source_sha "$GITHUB_SHA" \
    "$test_pkg/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrumentation.txt"
instrument_status=${PIPESTATUS[0]}
set -e
collect_probe
python3 - "$instrument_status" "$pkg" <<'PY'
import json
import os
from pathlib import Path
import re
import sys

root = Path('artifacts/library-native-probe')
status, package = int(sys.argv[1]), sys.argv[2]
summary = {'sourceSha': os.environ['GITHUB_SHA'], 'instrumentationExitCode': status, 'passed': False,
           'scope': 'API31 native-x86 TLS/certificate-release diagnostic; no real-source acceptance'}
try:
    output = (root / 'instrumentation.txt').read_text()
    match = re.search(r'^OK \(([0-9]+) tests?\)\s*$', output, re.M)
    assert status == 0 and match and int(match[1]) == 1, 'Require one executed instrumentation case'
    report = json.loads((root / 'report.json').read_text())
    assert report['schema'] == 'tv.preview-conscrypt-native-probe.v1'
    assert report['state'] == 'PASS' and report['phase'] == 'FINISHED'
    assert report['sourceSha'] == os.environ['GITHUB_SHA'] and report['packageName'] == package
    assert report['sdk'] == report['expectedSdk'] == 31
    assert report['pid'] == report['pidAfter'] and report['pid'] > 0
    assert report['artifact'] == 'org.conscrypt:conscrypt-android:2.9.0'
    assert report['mode'] == 'TLS_ONLY_NO_SOURCE_UI'
    assert report['uiLaunchedByProbe'] is False and report['realSourceRequestsByProbe'] == 0
    assert report['echAndDohUnchanged'] is True and report['preferencesWritten'] is False
    assert report['echKeyInitiallyAbsent'] is True
    assert report['traceBodyStored'] is False and report['encodedCertificatesStored'] is False
    assert report['credentialHeadersAllowed'] is False and report['customTrustAcceptance'] is False
    runtime = report['runtime']
    assert runtime['osArch'] in ('x86_64', 'amd64') and runtime['is64Bit'] is True
    assert runtime['conscryptElfMachine'] == 62
    assert runtime['conscryptJniSha256'] == '1018d9c3878e0bba16aa038b2875506efb31459bb2b6b0b4c7f72418d9408fa8'
    assert runtime['conscryptNativeX86Mapped'] is True and runtime['conscryptArmMapped'] is False
    assert re.fullmatch('[0-9a-f]{64}', runtime['installedApkSha256'])
    requests = report['requests']
    assert len(requests) == report['closedTlsSockets'] == 34
    for request in requests:
        assert request['state'] == 'PASS' and request['sni'] == 'encrypted' and request['tls'] == 'TLSv1.3'
        assert request['socketClosed'] is True and request['conscryptSocket'] is True
        assert request['secureConnectEnds'] == request['connectionsAcquired'] == 1
        assert request['productionFactoryPreserved'] is True and request['credentialsAdded'] is False
        assert request['peerCertificates'], 'Require real peer certificates'
        assert all(re.fullmatch('[0-9a-f]{64}', peer['derSha256']) for peer in request['peerCertificates'])
    groups = {group['kind']: group for group in report['certificateGroups']}
    assert set(groups) == {'PLATFORM', 'BUNDLED'}
    prefixes = {'PLATFORM': 'com.android.org.conscrypt.OpenSSLX509Certificate',
                'BUNDLED': 'org.conscrypt.OpenSSLX509Certificate'}
    for kind, group in groups.items():
        assert group['allocated'] >= 512 and group['collected'] * 10 >= group['allocated'] * 9
        assert group['actualClasses'] and all(c.startswith(prefixes[kind]) for c in group['actualClasses'])
        assert group['provider'] and group['providerClass']
    assert groups['PLATFORM']['providerClass'] != groups['BUNDLED']['providerClass']
    assert len(report['gcRounds']) == 4 and report['explicitGcCalls'] == 12
    assert report['finalizerWitnessCount'] >= 4
    assert report['registryCallbackCompletionProven'] is False
    summary.update(passed=True, executedTests=1, pid=report['pid'], sdk=31, runtime=runtime,
                   certificateGroups=report['certificateGroups'], tlsRequests=34,
                   finalizerWitnessCount=report['finalizerWitnessCount'], explicitGcCalls=12)
except (AssertionError, KeyError, ValueError, OSError) as error:
    summary['errorType'] = type(error).__name__
finally:
    (root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))
if not summary['passed']:
    raise SystemExit('Native Preview TLS/certificate release diagnostic did not complete')
PY
