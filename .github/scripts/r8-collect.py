"""Verify and retain the actual release APK from each side of the R8 experiment."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import zipfile


def activity_fields(data, target='Lcom/fongmi/android/tv/ui/activity/VideoActivity;'):
    def u32(offset):
        return struct.unpack_from('<I', data, offset)[0]

    def uleb(offset):
        value = shift = 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
            shift += 7

    def string(offset):
        _, offset = uleb(offset)
        return data[offset:data.index(0, offset)].decode('utf-8', 'replace')

    strings = [string(u32(u32(60) + 4 * i)) for i in range(u32(56))]
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    field_ids = [struct.unpack_from('<HHI', data, u32(84) + 8 * i) for i in range(u32(80))]
    for i in range(u32(96)):
        values = struct.unpack_from('<8I', data, u32(100) + i * 32)
        if types[values[0]] != target:
            continue
        offset = values[6]
        counts = []
        for _ in range(4):
            count, offset = uleb(offset)
            counts.append(count)
        result = []
        for count, static in [(counts[0], True), (counts[1], False)]:
            index = 0
            for _ in range(count):
                delta, offset = uleb(offset)
                _, offset = uleb(offset)
                index += delta
                _, field_type, name = field_ids[index]
                if not static:
                    result.append({'name': strings[name], 'type': types[field_type]})
        return result
    return None


mode = sys.argv[1]
assert mode in ('baseline', 'minified')
output = Path('artifacts/r8-comparison')
output.mkdir(parents=True, exist_ok=True)
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0'
apks = list(Path('app/build/outputs/apk/leanbackArm64_v8a/release').glob('*.apk'))
assert len(apks) == 1, 'Expected one ARM64 release APK'
source = apks[0]
badging = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(source)], text=True)
package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
assert package and package[1] == os.environ['R8_APPLICATION_ID'], 'Unexpected application ID'
assert 'application-debuggable' not in badging, 'Expected a non-debuggable release build'
manifest = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', str(source)], text=True)
assert re.search(r'E: profileable[^\n]*\n\s*A: [^\n]*shell[^\n]*=true', manifest), 'Expected profileable APK'
signed = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(source)], text=True)
certificate = re.search(r'(?:Signer #[0-9]+|V[0-9]+ Signer): certificate SHA-256 digest: (\S+)', signed)
assert certificate, 'Missing signing certificate'
fields = None
with zipfile.ZipFile(source) as archive:
    abis = sorted({name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')})
    assert abis == ['arm64-v8a'], 'Unexpected native ABI'
    for name in archive.namelist():
        if re.fullmatch(r'classes\d*\.dex', name):
            found = activity_fields(archive.read(name))
            if found is not None:
                fields = found
                break
assert fields is not None, 'VideoActivity missing from APK'
destination = output / (mode + '-arm64.apk')
shutil.copyfile(source, destination)
metadata = {
    'applicationCommit': os.environ['GITHUB_SHA'],
    'media3Commit': subprocess.check_output(['git', '-C', os.environ['MEDIA3_SOURCE_DIR'], 'rev-parse', 'HEAD'], text=True).strip(),
    'applicationId': package[1], 'versionCode': package[2], 'versionName': package[3],
    'abi': 'arm64-v8a', 'buildType': 'release', 'profileable': True, 'debuggable': False,
    'minifyEnabled': mode == 'minified', 'shrinkResources': False,
    'signingCertificateSha256': certificate[1].lower(),
    'file': destination.name, 'bytes': destination.stat().st_size,
    'sha256': hashlib.sha256(destination.read_bytes()).hexdigest(),
    'videoActivityInstanceFields': fields,
}
if mode == 'minified':
    mapping = Path('app/build/outputs/mapping/leanbackArm64_v8aRelease/mapping.txt')
    assert mapping.is_file() and mapping.stat().st_size, 'R8 mapping is missing'
    baseline = json.loads((output / 'baseline.json').read_text())
    for key in ('applicationCommit', 'media3Commit', 'applicationId', 'versionCode', 'versionName', 'signingCertificateSha256'):
        assert metadata[key] == baseline[key], 'Comparison differs in ' + key
    original_names = {field['name'] for field in baseline['videoActivityInstanceFields']}
    current_names = {field['name'] for field in fields}
    assert {'mBinding', 'mViewModel', 'mVod'} <= original_names, 'Unexpected baseline fields'
    assert not ({'mBinding', 'mViewModel', 'mVod'} & current_names), 'Playback fields were not obfuscated'
    old_types = {'Lcom/fongmi/android/tv/databinding/ActivityVideoBinding;',
                 'Lcom/fongmi/android/tv/model/VideoViewModel;',
                 'Lcom/fongmi/android/tv/playback/vod/VodPlaybackController;'}
    assert not (old_types & {field['type'] for field in fields}), 'Playback class names were kept'
    # The native bridge uses FindClass for these names; Java reachability alone
    # does not protect them. The X20 MPV startup exposed this missing JNI ABI.
    nodes = ('', '$None', '$StringNode', '$BooleanNode', '$IntNode', '$DoubleNode', '$ArrayNode', '$MapNode')
    mapping_classes = set()
    for line in mapping.open():
        if line.startswith('is.xyz.mpv.MPVNode') and ' -> ' in line:
            original, renamed = line.strip().removesuffix(':').split(' -> ')
            if original == renamed:
                mapping_classes.add(original)
    assert all('is.xyz.mpv.MPVNode' + node in mapping_classes for node in nodes), 'MPV JNI node class renamed or removed'
    with zipfile.ZipFile(source) as archive:
        dex_files = [archive.read(name) for name in archive.namelist() if re.fullmatch(r'classes\d*\.dex', name)]
    for node in nodes[2:]:
        target = 'Lis/xyz/mpv/MPVNode' + node + ';'
        node_fields = next((found for dex in dex_files if (found := activity_fields(dex, target)) is not None), None)
        assert node_fields is not None and any(field['name'] == 'value' for field in node_fields), 'MPV JNI value field missing: ' + node
    metadata['mpvNodeJniNamesVerified'] = True
    metadata['mappingSha256'] = hashlib.sha256(mapping.read_bytes()).hexdigest()
(output / (mode + '.json')).write_text(json.dumps(metadata, indent=2) + '\n')
print(json.dumps(metadata, indent=2))
