#!/usr/bin/env bash
# Only fixture metadata and fixed measurements may leave the disposable preview app.
set -euo pipefail
umask 077
pkg=com.fongmi.android.tv.preview
test_pkg=com.fongmi.android.tv.preview.test
out=artifacts/community-skip-playlists
inputs="$RUNNER_TEMP/community-skip-playlist-input"
private=$(mktemp -d "$RUNNER_TEMP/community-skip-playlist-report.XXXXXX")
instrumentation_exit=-1
logcat_pid=''
mkdir -p "$out"
rm -f "$out/report.json" "$out/report-collection.json" "$out/health.json" "$out/summary.json" "$out/generated-video.png"

cleanup() {
    if [ -n "$logcat_pid" ]; then kill "$logcat_pid" >/dev/null 2>&1 || true; fi
    timeout --kill-after=5s 15s adb shell run-as "$pkg" rm -rf files/real-playlist-fixture \
        "/sdcard/Android/data/$pkg/files/community-skip-playlist-verification" >/dev/null 2>&1 || true
    rm -rf "$private" "$inputs"
}
trap cleanup EXIT

python3 - "$inputs" <<'PY'
import json, pathlib, re, subprocess, sys
directory = pathlib.Path(sys.argv[1])
package = 'com.fongmi.android.tv.preview'
key = (directory / 'tmdb-key.txt').read_text().strip()
assert re.fullmatch(r'[a-fA-F0-9]{32}', key), 'A private runtime TMDB credential is required'
fixture_bytes = (directory / 'fixtures.json').read_bytes()
assert len(fixture_bytes) <= 512 * 1024 and key.encode() not in fixture_bytes, 'Invalid fixture input'
fixture = json.loads(fixture_bytes)
assert fixture.get('schema') == 'tv.community-skip-playlist-fixture.v1'
assert isinstance(fixture.get('cases'), list) and 3 <= len(fixture['cases']) <= 16

def no_source_secrets(value):
    if isinstance(value, dict):
        assert not any(name.lower() in {'url', 'header', 'headers', 'vodid', 'sourceurl', 'apikey', 'api_key'}
                       for name in value), 'Source transport fields are forbidden in fixtures'
        for item in value.values(): no_source_secrets(item)
    elif isinstance(value, list):
        for item in value: no_source_secrets(item)
    elif isinstance(value, str):
        assert not re.search(r'(?:https?|file|content)://|\b(?:Bearer|Basic)\s+[A-Za-z0-9+/=]+', value), \
            'Source URLs and authorization values are forbidden in fixtures'

no_source_secrets(fixture)
subprocess.run(['adb', 'shell', 'run-as', package, 'mkdir', '-p', 'files/real-playlist-fixture'],
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20, check=True)
subprocess.run(['adb', 'shell', 'run-as', package, 'chmod', '700', 'files/real-playlist-fixture'],
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20, check=True)
subprocess.run(['adb', 'shell', 'run-as', package, 'rm', '-rf',
                '/sdcard/Android/data/' + package + '/files/community-skip-playlist-verification'],
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20, check=False)
for name in ('fixtures.json', 'video.mp4', 'tmdb-key.txt'):
    content = (directory / name).read_bytes()
    assert content and len(content) <= 32 * 1024 * 1024, 'Invalid private input size'
    destination = 'files/real-playlist-fixture/' + name
    # A raw shell v2 waits for cat to consume EOF; exec-in does not wait for its writer.
    command = "run-as " + package + " sh -c 'umask 077; cat > " + destination + \
              " && chmod 600 " + destination + " && test -s " + destination + "'"
    subprocess.run(['adb', 'shell', '-T', command], input=content,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60, check=True)
    size = subprocess.check_output(['adb', 'shell', '-T', 'run-as', package, 'wc', '-c', destination],
                                   stderr=subprocess.DEVNULL, timeout=20).split()[0]
    assert int(size) == len(content), 'A private input transfer was incomplete'
PY

timeout --kill-after=5s 20s adb logcat -c >/dev/null 2>&1 || true
app_uid=$(timeout --kill-after=5s 15s adb shell cmd package list packages -U "$pkg" | python3 -c \
    'import re,sys; m=re.search(r"^package:"+re.escape(sys.argv[1])+r" uid:([0-9]+)\s*$",sys.stdin.read(),re.M); assert m,"Test UID missing"; print(m.group(1))' "$pkg")
[[ "$app_uid" =~ ^[0-9]+$ ]]
timeout --kill-after=5s 540s adb logcat -b all -v threadtime > "$private/system-logcat.txt" 2>&1 &
logcat_pid=$!
printf 'Starting real playlist metadata and substituted video verification.\n'
timeout --kill-after=10s 480s adb shell am instrument -w -r \
    -e class com.fongmi.android.tv.player.skip.CommunitySkipRealPlaylistAndroidTest \
    "$test_pkg/androidx.test.runner.AndroidJUnitRunner" > "$private/instrumentation.txt" 2>&1 || instrumentation_exit=$?
if [ "$instrumentation_exit" -eq -1 ]; then instrumentation_exit=0; fi
kill "$logcat_pid" >/dev/null 2>&1 || true
logcat_pid=''
timeout --kill-after=5s 20s adb logcat -b all -d -v threadtime --uid="$app_uid" \
    > "$private/app-logcat.txt" 2>&1 || true
timeout --kill-after=5s 20s adb pull "/sdcard/Android/data/$pkg/files/community-skip-playlist-verification/report.json" \
    "$private/report.json" >/dev/null 2>&1 || true
timeout --kill-after=5s 20s adb pull "/sdcard/Android/data/$pkg/files/community-skip-playlist-verification/generated-video.png" \
    "$private/generated-video.png" >/dev/null 2>&1 || true

python3 - "$private" "$out" "$instrumentation_exit" <<'PY'
import base64, hashlib, json, math, os, pathlib, re, sys
private, public = map(pathlib.Path, sys.argv[1:3])
status = int(sys.argv[3])
inputs = pathlib.Path(os.environ['RUNNER_TEMP']) / 'community-skip-playlist-input'
key = (inputs / 'tmdb-key.txt').read_text().strip()
forbidden = list({key, key.lower(), key.upper(), base64.b64encode(key.encode()).decode()})
fixture_bytes = (inputs / 'fixtures.json').read_bytes()
fixture = json.loads(fixture_bytes)
fixture_strings = {'caseId': set(), 'title': set(), 'flagName': set(), 'episodeLabel': set()}
for case in fixture['cases']:
    fixture_strings['caseId'].add(case['caseId'])
    fixture_strings['title'].add(case['title'])
    for flag in case['flags']:
        fixture_strings['flagName'].add(flag['name'])
        for episode in flag['episodes']:
            fixture_strings['episodeLabel'].add(episode if isinstance(episode, str) else episode['name'])

def write(name, value):
    content = json.dumps(value, ensure_ascii=False, indent=2) + '\n'
    assert not any(secret in content for secret in forbidden), 'Private data is forbidden in public reports'
    (public / name).write_text(content)

def read(name):
    path = private / name
    return path.read_text(errors='replace') if path.exists() else ''

fields = {
    'schema', 'passed', 'fixtureCaseCount', 'probeCount', 'videoDecodedCount', 'playbackProgressCount',
    'realIdentityMatchedCount', 'identityRejectedCount', 'controlledIntroSeekCount', 'controlledCreditsSeekCount',
    'consumedRangePreservedCount', 'blockedIdentityNoSeekCount', 'backgroundQueryNonBlocking',
    'playbackAdvancedWhileLookup', 'publicProviderRangeCountWithTestDuration', 'workerCleanupComplete',
    'networkPreferencesRestored', 'credentialFilesRemoved', 'videoSubstituted', 'markersControlled',
    'publicCoverageVerified', 'failureTypes', 'networkMode', 'publicProviderRequestCount',
}
case_fields = {
    'caseId', 'title', 'flagName', 'episodeLabel', 'flagIndex', 'episodeIndex', 'episodeCount',
    'sourceSeasonKnown', 'generatedLabel', 'labelKind', 'identityStatus', 'tmdbId', 'season', 'episode',
    'identityMs', 'publicProviderStatus', 'publicProviderRangeCountWithTestDuration', 'durationMs',
    'videoDecoded', 'playbackProgressVerified', 'lookupOffMainThread', 'uiTurnMs',
    'playbackAdvancedWhileLookup', 'controlledIntroSeekVerified', 'controlledCreditsSeekVerified',
    'consumedRangePreserved', 'noSeekForRejectedIdentity', 'actualSeekCount', 'workerCleanupComplete',
    'status', 'failureTypes', 'publicProviderRequestCount', 'publicProviderHttpStatuses',
    'parsedSeasonKnown', 'parsedSeason', 'parsedEpisode', 'expectedIdentityStatus',
}
# A report may name only fixed states or an exact metadata string from the supplied fixture.
fixed_strings = {
    'schema': {'tv.community-skip-real-playlist.v1'},
    'networkMode': {'ECH_OFF'},
    'labelKind': {'GENERATED', 'VARIETY', 'PREVIEW', 'AMBIGUOUS', 'SPLIT', 'EPISODE', 'UNSUPPORTED'},
    'identityStatus': {'MATCHED', 'REJECTED', 'UNCONFIRMED', 'LOOKUP_FAILED'},
    'publicProviderStatus': {'NOT_QUERIED_UNCONFIRMED', 'NO_RANGES_FOR_TEST_DURATION',
                             'RANGES_FOR_TEST_DURATION', 'LOOKUP_FAILED'},
    'expectedIdentityStatus': {'MATCHED', 'REJECT', 'MEASURE'},
    'status': {'STARTED', 'VERIFIED', 'FAILED'},
}

def safe(name, value):
    if isinstance(value, bool) or value is None: return value
    if isinstance(value, (int, float)) and math.isfinite(value): return value
    if isinstance(value, list) and name == 'failureTypes' and len(value) <= 8:
        return [safe(name, item) for item in value]
    if isinstance(value, list) and name == 'publicProviderHttpStatuses' and len(value) <= 16 \
            and all(isinstance(item, int) and not isinstance(item, bool) and 0 <= item <= 999 for item in value):
        return value
    if isinstance(value, str):
        if name in fixture_strings and value in fixture_strings[name]: return value
        if value in fixed_strings.get(name, set()): return value
        if name == 'failureTypes' and (value == 'UnknownFailure' or re.fullmatch(
                r'(?:[A-Za-z_$][A-Za-z0-9_$]*\.)*[A-Za-z_$][A-Za-z0-9_$]*(?:Exception|Error)', value)):
            return value
    raise ValueError('Unexpected report value')

report_valid = False
result = None
if (private / 'report.json').is_file():
    try:
        source = json.loads(read('report.json'))
        assert source.get('schema') == 'tv.community-skip-real-playlist.v1'
        result = {name: safe(name, value) for name, value in source.items() if name in fields}
        cases = source.get('cases')
        assert isinstance(cases, list) and len(cases) <= 256
        result['cases'] = [{name: safe(name, value) for name, value in case.items() if name in case_fields}
                           for case in cases]
        write('report.json', result)
        report_valid = True
    except Exception as failure:
        result = None
        write('report-collection.json', {'status': 'invalid-report', 'failureType': type(failure).__name__})

app_logs, system_logs = read('app-logcat.txt'), read('system-logcat.txt')
health = {
    'fatalExceptionCount': len(re.findall(r'FATAL EXCEPTION:', app_logs)),
    'anrCount': len(re.findall(r'ANR in com\.fongmi\.android\.tv\.preview\b', system_logs)),
    'nativeFatalSignalCount': len(re.findall(r'Fatal signal \d+', app_logs)),
}
write('health.json', health)
instrumentation = read('instrumentation.txt')
match = re.search(r'^OK \((\d+) tests?\)', instrumentation, re.M)
positive_fields = ('videoDecodedCount', 'playbackProgressCount', 'realIdentityMatchedCount',
                   'controlledIntroSeekCount', 'controlledCreditsSeekCount')
true_fields = ('passed', 'backgroundQueryNonBlocking', 'workerCleanupComplete', 'networkPreferencesRestored',
               'credentialFilesRemoved', 'videoSubstituted', 'markersControlled')
functional_passed = bool(result and all(result.get(name) is True for name in true_fields)
                         and all(isinstance(result.get(name), int) and not isinstance(result[name], bool)
                                 and result[name] > 0 for name in positive_fields)
                         and result.get('publicCoverageVerified') is False)
summary = {
    'headSha': os.environ.get('GITHUB_SHA'), 'exitCode': status,
    'passed': status == 0 and match is not None and int(match.group(1)) == 1 and report_valid
              and functional_passed and not any(health.values()),
    'testCount': int(match.group(1)) if match else 0, 'reportValid': report_valid,
    'functionalPassed': functional_passed, 'videoSubstituted': True, 'markersControlled': True,
    'publicCoverageVerified': False, 'fixtureSha256': hashlib.sha256(fixture_bytes).hexdigest(),
    'failureTypes': sorted(set(re.findall(
        r'\b((?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error))\b', instrumentation))),
    'health': health,
}
if result:
    summary['counts'] = {name: result.get(name, 0) for name in positive_fields}
write('summary.json', summary)
if summary['passed'] and result['videoDecodedCount'] > 0 and (private / 'generated-video.png').is_file():
    try:
        from PIL import Image
        content = (private / 'generated-video.png').read_bytes()
        assert 0 < len(content) <= 16 * 1024 * 1024
        assert not any(secret.encode() in content for secret in forbidden)
        with Image.open(private / 'generated-video.png') as screenshot:
            assert screenshot.format == 'PNG' and screenshot.width >= 320 and screenshot.height >= 180
            screenshot.load()
            assert any(high > low for low, high in screenshot.convert('RGB').getextrema())
        (public / 'generated-video.png').write_bytes(content)
    except Exception:
        pass  # Optional image collection must not change the playback assertions.
print(json.dumps(summary, indent=2))
assert summary['passed'], 'Real playlist verification failed; inspect only the sanitized report'
PY
