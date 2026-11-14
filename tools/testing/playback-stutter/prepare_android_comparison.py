#!/usr/bin/env python3
"""Add a test-only legacy danmaku view/pool from the pinned baseline; never change production."""

import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
MEDIA = Path(os.environ.get("MEDIA3_SOURCE_DIR", ROOT / ".media3"))
BASE = "342f6590e02ac390e0bd6b4488b8a1bff55747c1"
SOURCE = "libraries/ui_danmaku/src/main/java/androidx/media3/ui/danmaku/"
DESTINATION = ROOT / "app/src/androidTest/java/androidx/media3/ui/danmaku"


def main():
    head = subprocess.check_output(["git", "-C", str(MEDIA), "rev-parse", "HEAD"], text=True).strip()
    if head != BASE:
        raise SystemExit("Comparison must use the fixed Media3 baseline")
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for name in ("DanmakuView", "DanmakuRenderPool"):
        value = subprocess.check_output(["git", "-C", str(MEDIA), "show", BASE + ":" + SOURCE + name + ".java"], text=True)
        value = value.replace("DanmakuRenderPool", "LegacyDanmakuRenderPool").replace("DanmakuView", "LegacyDanmakuView")
        (DESTINATION / ("Legacy" + name + ".java")).write_text(value)
    print("Generated test-only legacy danmaku classes from " + BASE)


if __name__ == "__main__":
    main()
