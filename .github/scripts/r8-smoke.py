"""Black-box startup checks on the unmodified ARM64 release APKs in a fresh emulator."""

import json
import os
from pathlib import Path
import re
import subprocess
import time

output = Path('artifacts/r8-comparison')
package = os.environ['R8_APPLICATION_ID']
component = package + '/com.fongmi.android.tv.ui.activity.HomeActivity'


def adb(*arguments, check=True, binary=False, timeout=60):
    return subprocess.run(['adb', *arguments], check=check, capture_output=True,
                          text=not binary, timeout=timeout).stdout


report = {'scope': 'API 34 ARM64 native bridge; launcher startup and in-place upgrade only', 'runs': []}
try:
    report['deviceAbis'] = adb('shell', 'getprop', 'ro.product.cpu.abilist').strip().split(',')
    report['nativeBridge'] = adb('shell', 'getprop', 'ro.dalvik.vm.native.bridge').strip()
    assert 'arm64-v8a' in report['deviceAbis'], 'Emulator must support ARM64'
    assert report['nativeBridge'] not in ('', '0'), 'ARM64 native bridge unavailable'
    adb('shell', 'wm', 'size', '1920x1080')
    adb('shell', 'wm', 'density', '320')
    for mode in ('baseline', 'minified'):
        result = {'mode': mode, 'passed': False}
        report['runs'].append(result)
        installed = adb('install', '--abi', 'arm64-v8a', '-r', str(output / (mode + '-arm64.apk')), timeout=180)
        assert 'Success' in installed, 'APK install failed'
        adb('shell', 'pm', 'grant', package, 'android.permission.POST_NOTIFICATIONS')
        adb('shell', 'appops', 'set', package, 'MANAGE_EXTERNAL_STORAGE', 'allow')
        uid_text = adb('shell', 'cmd', 'package', 'list', 'packages', '-U', package)
        uid = re.search(r'uid:(\d+)', uid_text)
        assert uid, 'App UID unavailable'
        adb('shell', 'am', 'force-stop', package)
        adb('logcat', '-c')
        launch = adb('shell', 'am', 'start', '-W', '-n', component)
        result['launchStatusOk'] = 'Status: ok' in launch
        time.sleep(15)
        pid = adb('shell', 'pidof', package, check=False).strip()
        activities = adb('shell', 'dumpsys', 'activity', 'activities')
        result['processAlive'] = bool(pid)
        result['homeResumed'] = any(component in line and 'ResumedActivity' in line for line in activities.splitlines())
        app_log = adb('logcat', '-d', '-b', 'all', '-v', 'brief', '--uid=' + uid[1])
        result['fatalExceptionCount'] = app_log.count('FATAL EXCEPTION:')
        result['nativeFatalSignalCount'] = len(re.findall(r'Fatal signal \d+', app_log))
        result['failureTypes'] = sorted(set(re.findall(r'\b((?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error))\b', app_log)))
        result['crashActivityVisible'] = any('CrashActivity' in line and 'ResumedActivity' in line for line in activities.splitlines())
        result['exceptionFrames'] = re.findall(r'\bat ([A-Za-z_$][\w$]*(?:\.[\w$<>]+)+\([^\n]*\))', app_log)[-80:]
        (output / (mode + '-startup.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        result['passed'] = all(result[key] for key in ('launchStatusOk', 'processAlive', 'homeResumed')) and not any(result[key] for key in ('fatalExceptionCount', 'nativeFatalSignalCount', 'crashActivityVisible'))
        assert result['passed'], mode + ' startup failed: ' + json.dumps(result)
        adb('shell', 'am', 'force-stop', package)
    report['passed'] = True
finally:
    (output / 'startup.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
