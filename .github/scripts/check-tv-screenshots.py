"""Check screenshot completeness and distinct bright/dark surfaces; visual review still applies."""
import pathlib
import sys
from PIL import Image, ImageDraw, ImageStat

root = pathlib.Path(sys.argv[1])
scenes = ["01-home", "14-rex-spotlight", "15-rex-spotlight-touch-dpad", "16-rex-spotlight-swipe",
          "17-rex-mixed", "18-rex-mixed-touch-dpad", "19-rex-mixed-swipe", "20-rex-shelves",
          "00-controls-home", "02-navigation-touch-dpad", "03-chips-touch-dpad", "04-cards-touch-dpad",
          "05-grid-swipe", "06-search", "07-detail", "08-dialog", "09-playback", "09-settings",
          "10-settings-switched", "11-native-grid-probe", "12-library", "13-native-controls",
          "21-retained-native-controls", "22-retained-dialog",
          "23-home-history-order", "24-home-toolbar-touch", "25-home-toolbar-longpress",
          "26-home-toolbar-restored", "27-home-title-source", "28-home-empty-history",
          "29-discover-detail", "30-discover-detail-empty"]
system_scenes = ["01-settings-light", "02-settings-dark", "03-retained-light", "04-recreated-light"]
for scene in system_scenes:
    assert (root / f"system-{scene}.png").is_file(), f"Missing system theme scene: {scene}"
    with Image.open(root / f"system-{scene}.png") as screenshot:
        assert screenshot.size == (1920, 1080), (scene, screenshot.size)
        assert max(ImageStat.Stat(screenshot.convert("RGB")).stddev) > 3, f"Blank system theme screenshot: {scene}"
assert (root / "light-11-recreated.png").is_file(), "Missing recreated light-theme home"
for theme in ("dark", "light"):
    for scene in scenes:
        assert (root / f"{theme}-{scene}.png").is_file(), f"Missing {theme} scene: {scene}"
    screenshots = list(root.glob(f"{theme}-*.png"))
    for screenshot in screenshots:
        with Image.open(screenshot) as image:
            assert image.size == (1920, 1080), (screenshot, image.size)
            # Native control probes intentionally occupy a small part of the screen.
            # Reject solid frames without treating sparse, high-contrast UI as blank.
            variation = max(ImageStat.Stat(image.convert("RGB")).stddev)
            assert variation > 3, f"Blank screenshot: {screenshot} (variation={variation:.2f})"
with Image.open(root / "light-01-home.png") as light, Image.open(root / "dark-01-home.png") as dark:
    region = (0, 0, 120, 1080)
    light_mean = sum(ImageStat.Stat(light.crop(region).convert("RGB")).mean) / 3
    dark_mean = sum(ImageStat.Stat(dark.crop(region).convert("RGB")).mean) / 3
    assert light_mean > dark_mean + 80, (light_mean, dark_mean)
result = (root / "result.txt").read_text()
assert "PASS:" in result and "PosterHomeController/RexSpotlightPresenter/RexPosterPresenter" in result
print(f"PASS: {len(scenes) * 2 + 1 + len(system_scenes)} required screenshots, nonblank UI, distinct light/dark backgrounds and interaction assertions")

comparison_scenes = ["01-home", "14-rex-spotlight", "17-rex-mixed", "20-rex-shelves",
                     "03-chips-touch-dpad", "06-search", "07-detail", "29-discover-detail", "30-discover-detail-empty",
                     "08-dialog", "09-playback", "09-settings", "21-retained-native-controls"]
sheet = Image.new("RGB", (960, len(comparison_scenes) * 294), "#e7e9ef")
draw = ImageDraw.Draw(sheet)
for row, scene in enumerate(comparison_scenes):
    for column, theme in enumerate(("dark", "light")):
        with Image.open(root / f"{theme}-{scene}.png") as screenshot:
            screenshot.thumbnail((480, 270))
            sheet.paste(screenshot.convert("RGB"), (column * 480, row * 294 + 24))
        draw.text((column * 480 + 8, row * 294 + 5), f"{theme}: {scene}", fill="#1a2030")
sheet.save(root / "theme-comparison.png")
rex_scenes = ["23-home-history-order", "24-home-toolbar-touch", "28-home-empty-history",
              "01-home", "14-rex-spotlight", "15-rex-spotlight-touch-dpad", "17-rex-mixed", "18-rex-mixed-touch-dpad", "20-rex-shelves"]
rex_sheet = Image.new("RGB", (1920, len(rex_scenes) * 564), "#e7e9ef")
draw = ImageDraw.Draw(rex_sheet)
for row, scene in enumerate(rex_scenes):
    for column, theme in enumerate(("dark", "light")):
        with Image.open(root / f"{theme}-{scene}.png") as screenshot:
            screenshot.thumbnail((960, 540))
            rex_sheet.paste(screenshot.convert("RGB"), (column * 960, row * 564 + 24))
        draw.text((column * 960 + 8, row * 564 + 5), f"{theme}: {scene}", fill="#1a2030")
rex_sheet.save(root / "rex-theme-comparison.png")
