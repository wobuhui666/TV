"""Check screenshot completeness and distinct bright/dark surfaces; visual review still applies."""
import pathlib
import sys
from PIL import Image, ImageDraw, ImageStat

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

scenes = ["11-home-layout", "03-chips-touch-dpad", "06-search", "07-detail", "08-dialog", "09-playback", "09-settings"]
sheet = Image.new("RGB", (960, len(scenes) * 294), "#e7e9ef")
draw = ImageDraw.Draw(sheet)
for row, scene in enumerate(scenes):
    for column, theme in enumerate(("dark", "light")):
        with Image.open(root / f"{theme}-{scene}.png") as screenshot:
            screenshot.thumbnail((480, 270))
            sheet.paste(screenshot.convert("RGB"), (column * 480, row * 294 + 24))
        draw.text((column * 480 + 8, row * 294 + 5), f"{theme}: {scene}", fill="#1a2030")
sheet.save(root / "theme-comparison.png")
