#!/usr/bin/env bash
# Private inputs and raw source logs never enter a public artifact or the workflow console.
set -euo pipefail
umask 077
pkg=com.fongmi.android.tv.preview
test_pkg=com.fongmi.android.tv.preview.test
out=artifacts/community-skip-live
inputs="$RUNNER_TEMP/community-skip-live-input"
private=$(mktemp -d "$RUNNER_TEMP/community-skip-live-report.XXXXXX")
instrumentation_exit=-1
logcat_pid=''
app_uid=''
mkdir -p "$out"

cleanup() {
    if [ -n "$logcat_pid" ]; then kill "$logcat_pid" >/dev/null 2>&1 || true; fi
    timeout --kill-after=5s 15s adb shell run-as "$pkg" rm -rf files/live-community-skip >/dev/null 2>&1 || true
    rm -rf "$private" "$inputs"
}
trap cleanup EXIT

python3 - "$inputs" <<'PY'
import pathlib, subprocess, sys
directory = pathlib.Path(sys.argv[1])
package = 'com.fongmi.android.tv.preview'
subprocess.run(['adb', 'shell', 'run-as', package, 'mkdir', '-p', 'files/live-community-skip'],
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20, check=True)
subprocess.run(['adb', 'shell', 'run-as', package, 'chmod', '700', 'files/live-community-skip'],
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20, check=True)
for name in ('source-url.txt', 'tmdb-key.txt'):
    content = (directory / name).read_bytes()
    assert content, 'A required private input is missing'
    # shell v2 waits for the remote writer; exec-in can return before tee closes its file.
    destination = 'files/live-community-skip/' + name
    command = "run-as " + package + " sh -c 'umask 077; cat > " + destination + \
              " && chmod 600 " + destination + " && test -s " + destination + "'"
    subprocess.run(['adb', 'shell', '-T', command], input=content,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20, check=True)
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
printf 'Starting the private live configuration verification.\n'
timeout --kill-after=10s 480s adb shell am instrument -w -r \
    -e class com.fongmi.android.tv.player.skip.CommunitySkipLiveConfigAndroidTest \
    "$test_pkg/androidx.test.runner.AndroidJUnitRunner" > "$private/instrumentation.txt" 2>&1 || instrumentation_exit=$?
if [ "$instrumentation_exit" -eq -1 ]; then instrumentation_exit=0; fi
kill "$logcat_pid" >/dev/null 2>&1 || true
logcat_pid=''
timeout --kill-after=5s 20s adb logcat -b all -d -v threadtime --uid="$app_uid" \
    > "$private/app-logcat.txt" 2>&1 || true
timeout --kill-after=5s 20s adb pull "/sdcard/Android/data/$pkg/files/community-skip-live-verification/report.json" \
    "$private/report.json" >/dev/null 2>&1 || true

python3 - "$private" "$out" "$instrumentation_exit" <<'PY'
import json, math, os, pathlib, re, sys
import base64, urllib.parse
private, public = map(pathlib.Path, sys.argv[1:3])
status = int(sys.argv[3])
inputs = pathlib.Path(os.environ['RUNNER_TEMP']) / 'community-skip-live-input'
url = (inputs / 'source-url.txt').read_text().strip()
key = (inputs / 'tmdb-key.txt').read_text().strip()
parsed = urllib.parse.urlsplit(url)
forbidden = [url, key]
if parsed.username is not None:
    username = urllib.parse.unquote(parsed.username)
    password = urllib.parse.unquote(parsed.password or '')
    forbidden.extend([username, password, base64.b64encode((username + ':' + password).encode()).decode()])
forbidden = [value for value in forbidden if len(value) >= 4]
def write(name, value):
    content = json.dumps(value, indent=2) + '\n'
    assert not any(secret in content for secret in forbidden), 'Private data is not permitted in public reports'
    (public / name).write_text(content)
def read(name):
    path = private / name
    return path.read_text(errors='replace') if path.exists() else ''

# Deny arbitrary strings and retain only named counters, identities and static status labels.
fields = {
    'schema', 'passed', 'configStatus', 'configLoadMs', 'configHttpStatus', 'configNetworkFailure',
    'configRequestCount', 'configErrorStatus', 'configObserverRestored', 'configMode',
    'defaultConfigLoaded', 'networkPreferencesRestored', 'configSha256', 'siteCount', 'sourceCount',
    'testedSourceCount', 'sourceContentVerified', 'pluginInitializationVerified', 'sourceAvailability',
    'searchProbeCount', 'searchCount', 'detailCount', 'resolvedEpisodeCount', 'playbackCount',
    'playbackVerified', 'communitySkipVerified', 'communityRecordFound', 'automaticSkipVerified',
    'introCount', 'creditsCount', 'segmentCount', 'matchedCaseCount', 'actualSeekCount',
    'lookupCount', 'lookupOffMainThread', 'playbackAdvancedWhileLookup', 'failureTypes', 'uiFailureTypes',
    'workerCleanupComplete', 'nativeArmPlaybackVerified', 'deviceAbis', 'process64Bit',
    'providerRequests', 'configFetchedByApp', 'episodeIdentityVerified', 'directPlaybackVerified',
    'configLoaded', 'configuredSiteCount', 'credentialFilesRemoved', 'sourceSearchVerified',
    'sourceDetailVerified', 'sourcePlayResolved', 'sourceParseResolved', 'playbackStarted', 'videoDecoded',
    'playbackProgressVerified', 'backgroundQueryNonBlocking', 'communityIdentityMatched',
    'communityRangesAvailable', 'automaticSeekVerified',
    'measurementCompleted', 'configVerified', 'actualPlaybackAvailable', 'configurationVerified',
    'testedSiteCount', 'testedCaseCount', 'resolvedCaseCount', 'playableCaseCount',
}
case_fields = {
    'phase', 'siteIndex', 'caseIndex', 'status', 'spiderClass', 'pluginInitialized', 'contentReturned',
    'itemCount', 'searchCount', 'detailCount', 'categoryCount', 'episodeCount', 'elapsedMs',
    'tmdbId', 'season', 'episode', 'durationMs', 'introCount', 'creditsCount', 'segmentCount',
    'lookupMs', 'lookupOffMainThread', 'playbackAdvancedWhileLookup', 'actualSeekCount',
    'sourceAvailability', 'identityStatus', 'metadataStatus', 'playbackStatus', 'skipStatus',
    'failureTypes', 'failureCategory', 'networkFailureTypes', 'tlsFailureCategory', 'mode', 'echReason',
    'observerRestored', 'guardStatus', 'guardFailureTypes', 'dialogRequired',
    'parseRequired', 'parseStatus', 'parserResolved',
    'seekable', 'playable', 'resolved', 'matched', 'verified', 'provider', 'httpStatus',
    'sourceType', 'sourceKind', 'errorTypes', 'flagCount', 'requestCount', 'seekCount',
    'sourceSeasonExplicit', 'sourceEpisodeGenerated', 'uiTurnMs', 'videoTrackSelected', 'videoDecoded',
    'playbackProgressVerified', 'episodeIdentityVerified', 'seasonConfirmedByMetadata',
    'automaticSkipVerified', 'introAutomaticSkipVerified', 'creditsAutomaticSkipVerified', 'playbackErrorCode',
}
def safe(value):
    if isinstance(value, bool) or value is None:
        return value
    if isinstance(value, (int, float)) and math.isfinite(value):
        return value
    if isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_$.-]{1,200}', value):
        return value
    if isinstance(value, list) and len(value) <= 32:
        return [safe(item) for item in value]
    raise ValueError('Unexpected report value')

report_valid = False
if (private / 'report.json').is_file():
    try:
        source = json.loads(read('report.json'))
        assert source.get('schema') == 'tv.community-skip-live.v1'
        result = {key: safe(value) for key, value in source.items() if key in fields}
        for collection in ('stages', 'cases', 'providers'):
            if collection not in source:
                continue
            values = source[collection]
            assert isinstance(values, list) and len(values) <= 32
            result[collection] = [{key: safe(value) for key, value in item.items() if key in case_fields}
                                  for item in values]
        write('report.json', result)
        report_valid = True
    except Exception as error:
        write('report-collection.json', {'status': 'invalid-report', 'failureType': type(error).__name__})

app_logs, system_logs = read('app-logcat.txt'), read('system-logcat.txt')
health = {
    'fatalExceptionCount': len(re.findall(r'FATAL EXCEPTION:', app_logs)),
    'anrCount': len(re.findall(r'ANR in com\.fongmi\.android\.tv\.preview\b', system_logs)),
    'nativeFatalSignalCount': len(re.findall(r'Fatal signal \d+', app_logs)),
}
write('health.json', health)
instrumentation = read('instrumentation.txt')
match = re.search(r'^OK \((\d+) tests?\)', instrumentation, re.M)
summary = {'headSha': os.environ.get('GITHUB_SHA'), 'exitCode': status,
           'passed': status == 0 and match is not None and int(match.group(1)) == 1 and report_valid,
           'testCount': int(match.group(1)) if match else 0,
           'reportValid': report_valid,
           'failureTypes': sorted(set(re.findall(
               r'\b((?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error))\b', instrumentation))),
           # Only repository class/method names and line numbers; never Throwable messages.
           'appFailureFrames': sorted(set(re.findall(
               r'\bat ((?:com\.fongmi\.android\.tv|com\.github\.catvod)\.[A-Za-z0-9_.$]+)'
               r'\([A-Za-z0-9_$]+\.java:([0-9]{1,6})\)', app_logs)))[:32],
           'health': health}
write('summary.json', summary)
print(json.dumps(summary, indent=2))
assert summary['passed'], 'Private live verification failed; inspect only the sanitized report'
assert result.get('passed') is True, 'The real configuration/source probe did not pass'
assert not any(health.values()), 'Application crash or ANR found during live verification'
PY
