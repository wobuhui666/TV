"""Narrow the pinned AAR's global class-name rule for this isolated experiment."""

import hashlib
import json
from pathlib import Path
import zipfile

source = Path('app/libs/pine-core.aar')
before = hashlib.sha256(source.read_bytes()).hexdigest()
assert before == '0e9916fd601646273ff71ea64b08fcb658f9005043443d981691c83cfa71ab46', 'Unexpected Pine artifact'
old = '-keep class * {\n    native <methods>;\n}'
new = '-keepclasseswithmembers,includedescriptorclasses class * {\n    native <methods>;\n}'
temporary = source.with_suffix('.r8.aar')
with zipfile.ZipFile(source) as original, zipfile.ZipFile(temporary, 'w') as adjusted:
    for entry in original.infolist():
        data = original.read(entry.filename)
        if entry.filename == 'proguard.txt':
            rules = data.decode().replace('\r\n', '\n')
            assert rules.count(old) == 1, 'Expected exactly one global native keep rule'
            data = rules.replace(old, new).encode()
        adjusted.writestr(entry, data)
with zipfile.ZipFile(source) as original, zipfile.ZipFile(temporary) as adjusted:
    changed = [name for name in original.namelist() if original.read(name) != adjusted.read(name)]
    assert changed == ['proguard.txt'], 'Pine bytecode, JNI and resources must remain identical'
temporary.replace(source)
report = {'inputSha256': before, 'outputSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
          'changedEntries': changed, 'originalRule': old, 'replacementRule': new}
destination = Path('artifacts/r8-comparison')
destination.mkdir(parents=True, exist_ok=True)
(destination / 'pine-rule-adjustment.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
