#!/usr/bin/env python3
"""Generate an old ASS renderer inside the current pipeline, only in androidTest."""

import hashlib
import json
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[3]
BASE = "6bf8b92be7311ea03cf103c028cf3d0a94d822bc"
RENDERER_SHA256 = "6f6eef91bbe43a668c5cff65de5478398566fdd84971eb08a2b2fa3101164415"
PACKAGE = Path("com/fongmi/android/tv/player")
CLASSES = {
    "AssTextRenderer": PACKAGE / "subtitle/AssTextRenderer.java",
    "AdvancedSubtitleController": PACKAGE / "subtitle/AdvancedSubtitleController.java",
    "MediaSourceFactory": PACKAGE / "exo/MediaSourceFactory.java",
    "NextMediaPreload": PACKAGE / "exo/NextMediaPreload.java",
    "ExoPlayerEngine": PACKAGE / "exo/ExoPlayerEngine.java",
}


def digest(value):
    return hashlib.sha256(value).hexdigest()


def main():
    exists = subprocess.run(["git", "-C", str(ROOT), "cat-file", "-e", BASE + "^{commit}"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not exists:
        # GitHub Actions checks out one commit. Fetch only this immutable public baseline.
        subprocess.run(["git", "-C", str(ROOT), "fetch", "--no-tags", "--depth=1", "origin", BASE], check=True)
    report = {
        "baselineCommit": BASE,
        "substitution": "Only AssTextRenderer behavior comes from the old commit; wrappers use current source.",
        "cacheOwnership": "The generated media factory delegates getCache() to the production factory.",
        "files": [],
    }
    pattern = re.compile(r"\b(" + "|".join(CLASSES) + r")\b")
    for name, relative in CLASSES.items():
        source = Path("app/src/main/java") / relative
        if name == "AssTextRenderer":
            original = subprocess.check_output(["git", "-C", str(ROOT), "show", BASE + ":" + source.as_posix()])
            if digest(original) != RENDERER_SHA256:
                raise SystemExit("Unexpected baseline ASS renderer checksum")
        else:
            original = (ROOT / source).read_bytes()
        value = pattern.sub(lambda match: "Legacy" + match.group(0), original.decode("utf-8"))
        if name == "MediaSourceFactory":
            # Renamed classes have independent static fields. Retain the real cache owner,
            # otherwise the comparison would open a second SimpleCache on the same directory.
            value, count = re.subn(r"(?s)(    static synchronized Cache getCache\(\) \{).*?\n    }",
                                  r"\1\n        return MediaSourceFactory.getCache();\n    }", value)
            if count != 1:
                raise SystemExit("Could not share production cache in ASS comparison")
        generated = Path("app/src/androidTest/java") / relative.parent / ("Legacy" + name + ".java")
        target = ROOT / generated
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value)
        report["files"].append({
            "source": source.as_posix(), "generated": generated.as_posix(),
            "sourceCommit": BASE if name == "AssTextRenderer" else "current-workspace",
            "sourceSha256": digest(original), "generatedSha256": digest(value.encode("utf-8")),
        })
    output = ROOT / "artifacts/playback-stutter/ass-comparison-sources.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print("Generated test-only original ASS renderer with current pipeline wrappers from " + BASE)


if __name__ == "__main__":
    main()
