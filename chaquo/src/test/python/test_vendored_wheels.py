"""Keep the pinned pure-Python fallback usable by Chaquopy's binary resolver."""
import base64
import csv
from email import message_from_bytes
import hashlib
import io
import json
from pathlib import Path
import unittest
import zipfile


WHEELS = Path(__file__).resolve().parents[3] / 'wheels'
NAME = 'pywasm-1.0.8-py3-none-any.whl'
EXPECTED_SHA256 = '3651662d838fc0e83bbf8aa9a38185091ac96a40947b12c43355c9026196818a'


class VendoredWheelTest(unittest.TestCase):
    def test_pywasm_wheel_version_license_dependencies_and_record(self):
        path = WHEELS / NAME
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), EXPECTED_SHA256)
        provenance = json.loads((WHEELS / 'pywasm-1.0.8.provenance.json').read_text())
        self.assertEqual(provenance['wheel_sha256'], EXPECTED_SHA256)
        self.assertEqual(provenance['sdist_sha256'], 'bdfb0b03394c601254caee415f3982107887a04ec7f40751ed878130d2ab7e15')
        self.assertTrue(provenance['repeat_build_identical'])
        prefix = 'pywasm-1.0.8.dist-info/'
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            self.assertTrue(all(not name.startswith('/') and '..' not in Path(name).parts for name in names))
            self.assertTrue(all(not name.lower().endswith(('.so','.pyd','.dll','.dylib','.exe','.a','.o','.pyc')) for name in names))
            self.assertTrue(all(not archive.read(name).startswith(b'\x7fELF') for name in names))
            metadata = message_from_bytes(archive.read(prefix + 'METADATA'))
            self.assertEqual(metadata['Name'], 'pywasm')
            self.assertEqual(metadata['Version'], '1.0.8')
            self.assertEqual(metadata['License'], 'MIT')
            self.assertEqual(metadata['Requires-Python'], '>=3.6')
            self.assertEqual(metadata.get_all('Requires-Dist'), ['numpy'])
            wheel = message_from_bytes(archive.read(prefix + 'WHEEL'))
            self.assertEqual(wheel['Root-Is-Purelib'], 'true')
            self.assertEqual(wheel.get_all('Tag'), ['py3-none-any'])
            license = archive.read(prefix + 'LICENSE').decode()
            self.assertIn('MIT License', license)
            self.assertIn('Permission is hereby granted', license)
            records = list(csv.reader(io.StringIO(archive.read(prefix + 'RECORD').decode())))
            self.assertCountEqual([row[0] for row in records], names)
            for name, encoded, size in records:
                if name == prefix + 'RECORD':
                    self.assertEqual((encoded,size), ('',''))
                    continue
                payload = archive.read(name)
                digest = base64.urlsafe_b64encode(hashlib.sha256(payload).digest()).rstrip(b'=').decode()
                self.assertEqual(encoded, 'sha256=' + digest)
                self.assertEqual(int(size), len(payload))


if __name__ == '__main__':
    unittest.main()
