#!/usr/bin/env python3
"""Apply the reviewed Media3 patch without changing HEAD, index, or unrelated files."""

import hashlib
from pathlib import Path
import re
import stat
import subprocess
import sys


BASE_COMMIT = "342f6590e02ac390e0bd6b4488b8a1bff55747c1"
PATCH = Path(__file__).with_name("paused-danmaku-toggle.patch")
PATCH_SHA256 = "463eba72b1c56c19e38065a8d1f4476ed7a8e6b0d5bd39f0a8c05ceb47c169d7"
TARGETS = {
    "libraries/ui_danmaku/src/main/java/androidx/media3/ui/danmaku/DanmakuController.java",
    "libraries/ui_danmaku/src/main/java/androidx/media3/ui/danmaku/DanmakuView.java",
}
RENDER_INDEX_PATCH = Path(__file__).with_name("danmaku-render-index.patch")
RENDER_INDEX_SHA256 = "22343b0223dc63ebffe4e2f9ba76b408356269f172784597e799a6e38b212eda"
RENDER_INDEX_TARGETS = {
    "libraries/ui_danmaku/src/main/java/androidx/media3/ui/danmaku/DanmakuRenderPool.java",
}
PATCH_SPECS = (
    {"patch": PATCH, "sha256": PATCH_SHA256, "targets": TARGETS},
    {"patch": RENDER_INDEX_PATCH, "sha256": RENDER_INDEX_SHA256, "targets": RENDER_INDEX_TARGETS},
)


class PatchError(RuntimeError):
    pass


def git(repository, *arguments):
    return subprocess.run(
        ["git", "-C", str(repository), *arguments],
        capture_output=True, text=True, check=False,
    )


def checked_git(repository, *arguments):
    result = git(repository, *arguments)
    if result.returncode:
        raise PatchError(result.stderr.strip() or "Git command failed")
    return result.stdout.strip()


def apply_fixed_patch(repository, patch=PATCH, patch_sha256=PATCH_SHA256, base_commit=BASE_COMMIT, targets=TARGETS):
    repository = Path(repository).resolve()
    patch = Path(patch).resolve()
    if not repository.is_dir():
        raise PatchError("MEDIA3_SOURCE_DIR must point to an existing Media3 Git checkout")
    if not patch.is_file():
        raise PatchError("Required Media3 patch is missing")
    data = patch.read_bytes()
    if hashlib.sha256(data).hexdigest() != patch_sha256:
        raise PatchError("Media3 patch SHA-256 mismatch; use the reviewed patch and matching application script")
    top = Path(checked_git(repository, "rev-parse", "--show-toplevel")).resolve()
    if top != repository:
        raise PatchError("MEDIA3_SOURCE_DIR must be the root of its own Git checkout")
    head = checked_git(repository, "rev-parse", "HEAD")
    if head != base_commit:
        raise PatchError(f"Media3 HEAD is {head}; required patch baseline is {base_commit}. No checkout or reset was performed.")

    text = data.decode("utf-8")
    entries = re.findall(
        r"^diff --git a/(\S+) b/(\S+)\nindex ([0-9a-f]{40})\.\.([0-9a-f]{40}) 100644$",
        text, re.MULTILINE,
    )
    if (len(entries) != len(targets) or text.count("diff --git ") != len(targets)
            or {old for old, new, _, _ in entries if old == new} != targets):
        raise PatchError(f"Patch {patch.name} must contain only the reviewed regular files with full blob IDs")

    states = []
    for name, _, old_blob, new_blob in entries:
        path = repository / name
        mode = path.lstat().st_mode
        if not stat.S_ISREG(mode) or mode & 0o111:
            raise PatchError(f"Unexpected Media3 target file type or mode: {name}")
        if checked_git(repository, "rev-parse", f"HEAD:{name}") != old_blob:
            raise PatchError(f"Patch does not match the declared Media3 baseline: {name}")
        if checked_git(repository, "rev-parse", f":{name}") not in (old_blob, new_blob):
            raise PatchError(f"Media3 target has extra staged changes: {name}. No files were changed.")
        current = checked_git(repository, "hash-object", "--no-filters", "--", name)
        if current not in (old_blob, new_blob):
            raise PatchError(f"Media3 target has extra local changes: {name}. No files were changed.")
        states.append(current == new_blob)
    if any(states) and not all(states):
        raise PatchError(f"Media3 patch {patch.name} is only partially applied. No files were changed.")

    if all(states):
        checked_git(repository, "apply", "--reverse", "--check", str(patch))
        return f"Media3 {patch.stem} patch already applied"
    checked_git(repository, "apply", "--check", "--whitespace=error-all", str(patch))
    checked_git(repository, "apply", "--whitespace=error-all", str(patch))
    for name, _, _, new_blob in entries:
        if checked_git(repository, "hash-object", "--no-filters", "--", name) != new_blob:
            raise PatchError(f"Media3 patched content verification failed: {name}")
    return f"Applied reviewed Media3 {patch.stem} patch"


def apply_media3_patches(repository, specs=PATCH_SPECS, base_commit=BASE_COMMIT):
    return "; ".join(
        apply_fixed_patch(repository, spec["patch"], spec["sha256"], base_commit, spec["targets"])
        for spec in specs
    )


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise PatchError("Usage: python3 apply_patches.py MEDIA3_SOURCE_DIR")
        print(apply_media3_patches(sys.argv[1]))
    except (PatchError, OSError, UnicodeError) as error:
        print(f"Media3 patch error: {error}", file=sys.stderr)
        sys.exit(1)
