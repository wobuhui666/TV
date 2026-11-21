"""Host-only checks for patch idempotency and preservation of local work."""

import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest

import apply_patches


class ApplyPatchesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / "media3"
        self.repository.mkdir()
        self.command("init", "-q")
        self.command("config", "user.name", "Patch fixture")
        self.command("config", "user.email", "patch-fixture@example.invalid")
        self.command("config", "core.hooksPath", str(self.root / "no-hooks"))
        self.original = {}
        self.patched = {}
        for index, name in enumerate(sorted(apply_patches.TARGETS)):
            path = self.repository / name
            path.parent.mkdir(parents=True, exist_ok=True)
            self.original[name] = f"class Fixture{index} {{\n  int value() {{ return 1; }}\n}}\n".encode()
            self.patched[name] = self.original[name].replace(b"return 1", b"return 2")
            path.write_bytes(self.original[name])
        (self.repository / "unrelated.txt").write_text("original\n")
        self.command("add", ".")
        self.command("-c", "commit.gpgsign=false", "commit", "-qm", "fixture baseline")
        self.base = self.command("rev-parse", "HEAD").strip()
        for name, data in self.patched.items():
            (self.repository / name).write_bytes(data)
        self.patch = self.root / "fixture.patch"
        self.patch.write_text(self.command("diff", "--full-index", "--", *sorted(apply_patches.TARGETS)))
        self.sha = hashlib.sha256(self.patch.read_bytes()).hexdigest()
        for name, data in self.original.items():
            (self.repository / name).write_bytes(data)

    def command(self, *arguments):
        return subprocess.check_output(["git", "-C", str(self.repository), *arguments], text=True)

    def apply(self, **overrides):
        arguments = dict(repository=self.repository, patch=self.patch, patch_sha256=self.sha, base_commit=self.base)
        arguments.update(overrides)
        return apply_patches.apply_fixed_patch(**arguments)

    def contents(self):
        return {name: (self.repository / name).read_bytes() for name in apply_patches.TARGETS}

    def assert_rejected_without_writes(self, **overrides):
        before = self.contents()
        index = self.command("write-tree")
        with self.assertRaises(apply_patches.PatchError):
            self.apply(**overrides)
        self.assertEqual(before, self.contents())
        self.assertEqual(index, self.command("write-tree"))

    def test_first_and_repeated_application_preserve_unrelated_work_and_index(self):
        unrelated = self.repository / "unrelated.txt"
        unrelated.write_text("my staged work\n")
        self.command("add", "unrelated.txt")
        unrelated.write_text("my local work\n")
        index = self.command("write-tree")
        self.assertIn("Applied", self.apply())
        self.assertEqual(self.patched, self.contents())
        self.assertIn("already applied", self.apply())
        self.assertEqual("my local work\n", unrelated.read_text())
        self.assertEqual(index, self.command("write-tree"))
        self.command("add", "--", *sorted(apply_patches.TARGETS))
        index = self.command("write-tree")
        self.assertIn("already applied", self.apply())
        self.assertEqual(index, self.command("write-tree"))

    def test_wrong_baseline_and_checksum_leave_targets_untouched(self):
        self.assert_rejected_without_writes(base_commit="0" * 40)
        self.assert_rejected_without_writes(patch_sha256="0" * 64)

    def test_extra_target_changes_are_never_overwritten(self):
        name = sorted(apply_patches.TARGETS)[0]
        (self.repository / name).write_bytes(self.original[name] + b"// local work\n")
        self.assert_rejected_without_writes()

    def test_partial_application_is_rejected(self):
        name = sorted(apply_patches.TARGETS)[0]
        (self.repository / name).write_bytes(self.patched[name])
        self.assert_rejected_without_writes()

    def test_staged_target_changes_are_preserved_and_rejected(self):
        name = sorted(apply_patches.TARGETS)[0]
        (self.repository / name).write_bytes(self.original[name] + b"// staged local work\n")
        self.command("add", "--", name)
        (self.repository / name).write_bytes(self.original[name])
        self.assert_rejected_without_writes()

    def test_extra_changes_after_application_are_rejected(self):
        self.apply()
        name = sorted(apply_patches.TARGETS)[0]
        (self.repository / name).write_bytes(self.patched[name] + b"// local work\n")
        self.assert_rejected_without_writes()

    def test_incompatible_hunks_fail_git_apply_check_without_writes(self):
        self.patch.write_bytes(self.patch.read_bytes().replace(b"-  int value()", b"-  int missing()"))
        sha = hashlib.sha256(self.patch.read_bytes()).hexdigest()
        self.assert_rejected_without_writes(patch_sha256=sha)

    def test_nested_directory_and_executable_target_are_rejected(self):
        self.assert_rejected_without_writes(repository=self.repository / "libraries")
        (self.repository / sorted(apply_patches.TARGETS)[0]).chmod(0o755)
        self.assert_rejected_without_writes()


    def test_multiple_patch_specs_apply_and_verify_independently(self):
        extra_name = "libraries/extractor/src/main/java/androidx/media3/extractor/FixtureOrder.java"
        extra = self.repository / extra_name
        extra.parent.mkdir(parents=True, exist_ok=True)
        original = b"class FixtureOrder {\n  int value() { return 1; }\n}\n"
        patched = original.replace(b"return 1", b"return 2")
        extra.write_bytes(original)
        self.command("add", ".")
        self.command("-c", "commit.gpgsign=false", "commit", "-qm", "extra baseline")
        base = self.command("rev-parse", "HEAD").strip()
        extra.write_bytes(patched)
        extra_patch = self.root / "extra.patch"
        extra_patch.write_text(self.command("diff", "--full-index", "--", extra_name))
        extra_sha = hashlib.sha256(extra_patch.read_bytes()).hexdigest()
        extra.write_bytes(original)
        specs = [
            {"patch": self.patch, "sha256": self.sha, "targets": apply_patches.TARGETS},
            {"patch": extra_patch, "sha256": extra_sha, "targets": {extra_name}},
        ]
        result = apply_patches.apply_media3_patches(self.repository, specs, base)
        self.assertIn("Applied", result)
        self.assertEqual(self.patched, self.contents())
        self.assertEqual(patched, extra.read_bytes())
        again = apply_patches.apply_media3_patches(self.repository, specs, base)
        self.assertEqual(2, again.count("already applied"))

    def test_registered_patches_match_checksums_and_independent_targets(self):
        targets = set()
        for spec in apply_patches.PATCH_SPECS:
            data = spec["patch"].read_bytes()
            self.assertEqual(spec["sha256"], hashlib.sha256(data).hexdigest())
            names = set()
            for line in data.decode("utf-8").splitlines():
                if line.startswith("diff --git "):
                    old, new = line.split()[2:]
                    self.assertEqual(old[2:], new[2:])
                    names.add(old[2:])
            self.assertEqual(spec["targets"], names)
            self.assertFalse(targets.intersection(names), "Patches must have independent targets")
            targets.update(names)


if __name__ == "__main__":
    unittest.main()
