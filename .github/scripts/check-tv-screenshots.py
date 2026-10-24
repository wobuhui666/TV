"""Check screenshot completeness and distinct bright/dark surfaces; visual review still applies."""
import pathlib
import sys
from PIL import Image, ImageStat

root = pathlib.Path(sys.argv[1])
for theme in ("dark", "light"):
    screenshots = list(root.glob(f"{theme}-*.png"))
    assert len(screenshots) >= 10, f"Missing {theme} screenshots: {len(screenshots)}"
    for screenshot in screenshots:
        with Image.open(screenshot) as image:
            assert image.size == (1920, 1080), (screenshot, image.size)
            assert max(ImageStat.Stat(image.convert("RGB")).stddev) > 10, f"Blank screenshot: {screenshot}"
with Image.open(root / "light-01-home.png") as light, Image.open(root / "dark-01-home.png") as dark:
    region = (0, 0, 120, 1080)
    light_mean = sum(ImageStat.Stat(light.crop(region).convert("RGB")).mean) / 3
    dark_mean = sum(ImageStat.Stat(dark.crop(region).convert("RGB")).mean) / 3
    assert light_mean > dark_mean + 80, (light_mean, dark_mean)
assert "PASS:" in (root / "result.txt").read_text()
print("PASS: complete screenshots, nonblank UI, distinct light/dark backgrounds and interaction assertions")
