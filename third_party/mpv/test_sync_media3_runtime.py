import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from sync_media3_runtime import synchronize


def digest(data):
    return hashlib.sha256(data).hexdigest()


class SharedRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.runtime = self.root / "runtime"
        self.media = self.root / "media3"
        self.base = self.media / "libraries/decoder_ffmpeg/src/main"
        (self.runtime / "patches").mkdir(parents=True)
        (self.runtime / "patches/ffmpeg-hisi-nv21.patch").write_bytes(b"patch")
        original, builds = {}, {}
        with zipfile.ZipFile(self.runtime / "mpv-android-ffmpeg9-native.zip", "w") as bundle:
            for abi in ("arm64-v8a", "armeabi-v7a"):
                name = f"{abi}/libavcodec.so"
                old, new = (abi + "-old").encode(), (abi + "-patched").encode()
                target = self.base / "jniLibs" / name
                target.parent.mkdir(parents=True)
                target.write_bytes(old)
                original[name] = digest(old)
                builds[abi] = {"libraries": {"libavcodec.so": digest(new)}}
                bundle.writestr(name, new)
        metadata = self.base / "resources/META-INF/androidx.media3.decoder.ffmpeg/native-dependencies.properties"
        metadata.parent.mkdir(parents=True)
        metadata.write_text("schema.version=3\n")
        self.manifest = {"archiveSha256": digest((self.runtime / "mpv-android-ffmpeg9-native.zip").read_bytes()),
                         "patchSha256": digest(b"patch"), "originalLibraries": original,
                         "builds": builds, "nativeBuildRun": "123"}
        (self.runtime / "native-hisi-provenance.json").write_text(json.dumps(self.manifest))

    def test_updates_both_abis_and_is_idempotent(self):
        self.assertEqual(3, synchronize(self.media, self.runtime))
        self.assertEqual(0, synchronize(self.media, self.runtime))
        for abi in self.manifest["builds"]:
            self.assertEqual((abi + "-patched").encode(),
                             (self.base / "jniLibs" / abi / "libavcodec.so").read_bytes())

    def test_refuses_unknown_second_abi_before_modifying_first(self):
        (self.base / "jniLibs/armeabi-v7a/libavcodec.so").write_bytes(b"user-change")
        with self.assertRaisesRegex(RuntimeError, "Unknown local"):
            synchronize(self.media, self.runtime)
        self.assertEqual(b"arm64-v8a-old", (self.base / "jniLibs/arm64-v8a/libavcodec.so").read_bytes())

    def test_upgrades_a_recorded_previous_patch_and_keeps_unknown_edits(self):
        previous = {}
        for abi in self.manifest["builds"]:
            name = f"{abi}/libavcodec.so"
            data = (abi + "-previous-patch").encode()
            (self.base / "jniLibs" / name).write_bytes(data)
            previous[name] = [digest(data)]
        self.manifest["previousLibraries"] = previous
        (self.runtime / "native-hisi-provenance.json").write_text(json.dumps(self.manifest))
        second = self.base / "jniLibs/armeabi-v7a/libavcodec.so"
        second.write_bytes(b"user-change")
        with self.assertRaisesRegex(RuntimeError, "Unknown local"):
            synchronize(self.media, self.runtime)
        self.assertEqual(b"arm64-v8a-previous-patch",
                         (self.base / "jniLibs/arm64-v8a/libavcodec.so").read_bytes())
        second.write_bytes(b"armeabi-v7a-previous-patch")
        self.assertEqual(3, synchronize(self.media, self.runtime))
        self.assertEqual(0, synchronize(self.media, self.runtime))

    def test_rejects_a_different_patch_without_touching_binaries(self):
        (self.runtime / "patches/ffmpeg-hisi-nv21.patch").write_bytes(b"different")
        with self.assertRaisesRegex(RuntimeError, "different patch"):
            synchronize(self.media, self.runtime)
        self.assertEqual(b"arm64-v8a-old", (self.base / "jniLibs/arm64-v8a/libavcodec.so").read_bytes())

    def test_refuses_a_library_outside_the_checkout(self):
        target = self.base / "jniLibs/arm64-v8a/libavcodec.so"
        outside = self.root / "outside.so"
        outside.write_bytes(target.read_bytes())
        target.unlink()
        target.symlink_to(outside)
        with self.assertRaisesRegex(RuntimeError, "Invalid Media3"):
            synchronize(self.media, self.runtime)
        self.assertEqual(b"arm64-v8a-old", outside.read_bytes())


if __name__ == "__main__":
    unittest.main()
