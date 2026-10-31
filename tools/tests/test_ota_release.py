import hashlib
import importlib.util
import json
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("ota_release", Path(__file__).parents[1] / "ota-release.py")
OTA = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OTA)


class ReleaseVersionTest(unittest.TestCase):
    def test_versions_increase_by_seconds_across_workflows(self):
        first = OTA.release_version("2026-10-07T12:00:00Z")
        second = OTA.release_version("2026-10-07T12:00:01+00:00")
        self.assertEqual(214_537_600, first[0])
        self.assertEqual(first[0] + 1, second[0])
        self.assertEqual("5.8.5+20261007.120000", first[1])

    def test_timezones_represent_the_same_build_version(self):
        self.assertEqual(OTA.release_version("2026-10-07T12:00:00Z"), OTA.release_version("2026-10-07T20:00:00+08:00"))

    def test_rejects_ambiguous_time_and_android_integer_overflow(self):
        for timestamp in ("2026-10-07T12:00:00", "2100-01-01T00:00:00Z", "2010-01-01T00:00:00Z"):
            with self.subTest(timestamp=timestamp), self.assertRaises(ValueError):
                OTA.release_version(timestamp)


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.paths = {}
        for abi, native in OTA.ABIS.items():
            folder = self.root / "leanback" / abi / "release"
            folder.mkdir(parents=True)
            apk = folder / f"leanback-{abi}.apk"
            self.write_apk(apk, native)
            metadata = {
                "version": 3,
                "applicationId": OTA.PACKAGE_NAME,
                "elements": [{"versionCode": 214_537_600, "versionName": "5.8.5+20261007.120000", "outputFile": apk.name}],
            }
            metadata_path = folder / "output-metadata.json"
            metadata_path.write_text(json.dumps(metadata))
            self.paths[abi] = (apk, metadata_path)

    def write_apk(self, path, native):
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00fixture")
            apk.writestr("classes.dex", b"dex\n035\x00fixture")
            apk.writestr(f"lib/{native}/libfixture.so", b"\x7fELFfixture")

    def mutate_metadata(self, abi, mutate):
        path = self.paths[abi][1]
        value = json.loads(path.read_text())
        mutate(value)
        path.write_text(json.dumps(value))

    def manifest(self, **kwargs):
        return OTA.build_manifest(self.root, kwargs.get("repository", "wobuhui666/TV"), kwargs.get("tag", "build-37500000000-1"), desc="遥控体验改进")

    def test_coexist_manifest_uses_coexist_package_and_assets(self):
        for abi, (apk, metadata_path) in self.paths.items():
            coexist = apk.with_name(f"leanback-{abi}-coexist.apk")
            apk.rename(coexist)
            value = json.loads(metadata_path.read_text())
            value["applicationId"] = OTA.COEXIST_PACKAGE_NAME
            value["elements"][0]["outputFile"] = coexist.name
            metadata_path.write_text(json.dumps(value))
        result = OTA.build_manifest(self.root, "wobuhui666/TV", "build-37500000000-1", coexist=True)
        self.assertEqual(OTA.COEXIST_PACKAGE_NAME, result["packageName"])
        self.assertTrue(result["assets"]["arm64_v8a"]["url"].endswith("/leanback-arm64_v8a-coexist.apk"))
        with self.assertRaises(ValueError):
            self.manifest()

    def test_manifest_uses_actual_outputs_and_tag_pinned_downloads(self):
        result = self.manifest()
        self.assertEqual(1, result["schema"])
        self.assertEqual(214_537_600, result["code"])
        self.assertEqual("5.8.5+20261007.120000", result["name"])
        self.assertEqual(OTA.PACKAGE_NAME, result["packageName"])
        self.assertEqual("leanback", result["mode"])
        self.assertEqual("遥控体验改进", result["desc"])
        self.assertEqual(set(OTA.ABIS), set(result["assets"]))
        for abi, asset in result["assets"].items():
            content = self.paths[abi][0].read_bytes()
            self.assertEqual(hashlib.sha256(content).hexdigest(), asset["sha256"])
            self.assertEqual(len(content), asset["size"])
            self.assertEqual(f"https://github.com/wobuhui666/TV/releases/download/build-37500000000-1/leanback-{abi}.apk", asset["url"])

    def test_reruns_use_distinct_urls_and_legacy_tags_remain_readable(self):
        first = self.manifest(tag="build-37500000000-1")
        retry = self.manifest(tag="build-37500000000-2")
        legacy = self.manifest(tag="build-37500000000")
        for abi in OTA.ABIS:
            self.assertNotEqual(first["assets"][abi]["url"], retry["assets"][abi]["url"])
            self.assertIn("/build-37500000000/", legacy["assets"][abi]["url"])

    def test_cli_preserves_release_notes_as_literal_text(self):
        notes = "--quoted 'notes' with `backticks` and $(commands)\n第二行"
        output = self.root / "manifest.json"
        result = subprocess.run([
            sys.executable, str(Path(OTA.__file__)), "manifest",
            "--apk-root", str(self.root), "--repository", "wobuhui666/TV",
            "--tag", "build-37500000000-2", "--desc=" + notes, "--output", str(output),
        ], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(notes, json.loads(output.read_text())["desc"])

    def test_rejects_partial_release(self):
        self.paths["arm64_v8a"][1].unlink()
        with self.assertRaisesRegex(ValueError, "Both ARM64 and ARMv7"):
            self.manifest()

    def test_rejects_mixed_versions_and_wrong_package(self):
        self.mutate_metadata("arm64_v8a", lambda data: data["elements"][0].update(versionCode=214_537_601))
        with self.assertRaisesRegex(ValueError, "inconsistent versions"):
            self.manifest()
        self.mutate_metadata("arm64_v8a", lambda data: data.update(applicationId="com.fongmi.android.tv.preview"))
        with self.assertRaisesRegex(ValueError, "package name"):
            self.manifest()

    def test_rejects_wrong_abi_and_html_disguised_as_apk(self):
        apk = self.paths["arm64_v8a"][0]
        self.write_apk(apk, "armeabi-v7a")
        with self.assertRaisesRegex(ValueError, "Wrong native ABI"):
            self.manifest()
        apk.write_text("<html>upstream unavailable</html>")
        with self.assertRaises(zipfile.BadZipFile):
            self.manifest()

    def test_rejects_non_apk_zip(self):
        with zipfile.ZipFile(self.paths["arm64_v8a"][0], "w") as archive:
            archive.writestr("AndroidManifest.xml", "<manifest />")
        with self.assertRaisesRegex(ValueError, "binary Android manifest"):
            self.manifest()

    def test_rejects_stale_local_version(self):
        for code in (555, 585):
            with self.subTest(code=code):
                self.mutate_metadata("arm64_v8a", lambda data: data["elements"][0].update(versionCode=code))
                with self.assertRaisesRegex(ValueError, "CI versionCode"):
                    self.manifest()

    def test_rejects_ambiguous_duplicate_variant_outputs(self):
        apk, metadata = self.paths["arm64_v8a"]
        duplicate = self.root / "duplicate"
        duplicate.mkdir()
        (duplicate / apk.name).write_bytes(apk.read_bytes())
        (duplicate / metadata.name).write_bytes(metadata.read_bytes())
        with self.assertRaisesRegex(ValueError, "Multiple outputs"):
            self.manifest()

    def test_rejects_path_escape_and_mutable_or_foreign_release_urls(self):
        for kwargs in ({"repository": "FongMi/Release"}, {"tag": "latest"}, {"tag": "build-1/../../latest"}, {"tag": "build-1-0"}, {"tag": "build-1-2-3"}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                self.manifest(**kwargs)
        self.mutate_metadata("arm64_v8a", lambda data: data["elements"][0].update(outputFile="../../leanback-arm64_v8a.apk"))
        with self.assertRaisesRegex(ValueError, "plain filename"):
            self.manifest()


if __name__ == "__main__":
    unittest.main()
