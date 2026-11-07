#!/usr/bin/env python3
"""Exercise the bundled LAN webpage in Chromium using controlled HTTP fixtures."""

import argparse
import json
import mimetypes
import os
from pathlib import Path
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

from playwright.sync_api import sync_playwright, expect


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"
OUTPUT = Path(os.environ.get("WEB_VERIFY_OUTPUT", "artifacts/material-web"))
STATE = {"requests": [], "fail": set(), "errorStatus": {}, "files": {}, "media": {}}
LOCK = threading.Lock()


def reset_fixture():
    with LOCK:
        STATE["requests"] = []
        STATE["fail"] = set()
        STATE["errorStatus"] = {}
        STATE["files"] = {
            "": [
                {"name": "剧集", "path": "/剧集", "dir": 1, "time": "2026/11/07 12:00"},
                {"name": "空目录", "path": "/空目录", "dir": 1, "time": "2026/11/07 12:00"},
                {"name": "配置 2026.json", "path": "/配置 2026.json", "dir": 0, "time": "2026/11/07 12:00"},
                {"name": "第三集 '彩蛋'#.srt", "path": "/第三集 '彩蛋'#.srt", "dir": 0, "time": "2026/11/07 12:00"},
                {"name": "<img src=x onerror=window.__injected=1>.srt", "path": "/<img src=x onerror=window.__injected=1>.srt", "dir": 0, "time": "2026/11/07 12:00"},
            ],
            "/剧集": [{"name": "第03集.mp4", "path": "/剧集/第03集.mp4", "dir": 0, "time": "2026/11/07 12:00"}],
            "/空目录": [],
        }
        STATE["media"] = {"state": 2, "title": "山海之间", "artist": "第一季 · 第03集", "duration": 5400000,
                          "position": 1234000, "speed": 1.25, "url": "https://example.invalid/episode3.mp4", "artwork": ""}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, content, mime="application/json; charset=utf-8"):
        if isinstance(content, (dict, list)):
            content = json.dumps(content, ensure_ascii=False)
        data = content if isinstance(content, bytes) else content.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = unquote(urlsplit(self.path).path)
        with LOCK:
            STATE["requests"].append({"method": "GET", "path": path})
            failing = path in STATE["fail"]
            media = dict(STATE["media"])
        if failing:
            return self.reply(503, "Fixture unavailable", "text/plain")
        if path == "/device":
            return self.reply(200, {"name": "客厅电视", "type": 0, "ip": "http://127.0.0.1:" + str(self.server.server_port)})
        if path == "/media":
            return self.reply(200, media)
        if path == "/file" or path.startswith("/file/"):
            folder = path[5:].rstrip("/")
            with LOCK:
                entries = STATE["files"].get(folder)
            if entries is not None:
                parent = "." if not folder else folder.rpartition("/")[0]
                return self.reply(200, {"parent": parent, "files": entries})
            return self.reply(200, b"fixture subtitle", "application/octet-stream")
        target = ASSETS / (path.lstrip("/") or "index.html")
        if not target.resolve().is_relative_to(ASSETS.resolve()) or not target.is_file():
            return self.reply(404, "Not found", "text/plain")
        return self.reply(200, target.read_bytes(), mimetypes.guess_type(target.name)[0] or "application/octet-stream")

    def do_POST(self):
        path = urlsplit(self.path).path
        payload = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        content_type = self.headers.get("Content-Type", "")
        fields = {key: values[0] for key, values in parse_qs(payload.decode("utf-8", errors="replace"), keep_blank_values=True).items()} if "urlencoded" in content_type else {}
        with LOCK:
            STATE["requests"].append({"method": "POST", "path": path, "fields": fields,
                                      "contentType": content_type, "bytes": len(payload)})
            failing = path in STATE["fail"]
            if path == "/action" and fields.get("do") == "control":
                STATE["media"]["state"] = 3 if fields.get("type") == "play" else 2
        if failing:
            return self.reply(STATE["errorStatus"].get(path, 500), "Fixture operation failed", "text/plain")
        if path in ("/rename", "/move", "/delFile", "/delFolder"):
            source = fields.get("path", "")
            parent = source.rpartition("/")[0]
            with LOCK:
                entries = STATE["files"].get(parent, [])
                selected = next((item for item in entries if item["path"] == source), None)
                if selected is not None:
                    entries.remove(selected)
                    if path in ("/rename", "/move"):
                        target = parent if path == "/rename" else fields.get("target", "").rstrip("/")
                        selected = dict(selected)
                        selected["name"] = fields["name"] if path == "/rename" else selected["name"]
                        selected["path"] = target + "/" + selected["name"]
                        STATE["files"].setdefault(target, []).append(selected)
        if path == "/remote/key":
            return self.reply(200, {"accepted": True})
        return self.reply(200, "OK", "text/plain; charset=utf-8")


class MaterialWebTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        OUTPUT.mkdir(parents=True, exist_ok=True)
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = "http://127.0.0.1:" + str(cls.server.server_port)
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch(headless=True, args=["--no-sandbox"])

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        reset_fixture()
        self.context = self.browser.new_context(viewport={"width": 1440, "height": 1040}, locale="zh-CN", color_scheme="light")
        self.errors = []
        self.page = self.context.new_page()
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))
        self.page.goto(self.base)
        expect(self.page.locator("#device-name")).to_contain_text("客厅电视")

    def tearDown(self):
        self.page.screenshot(path=str(OUTPUT / (self._testMethodName + ".png")), full_page=True)
        self.context.close()
        self.assertEqual([], self.errors, "Browser runtime errors")

    def requests(self, path):
        with LOCK:
            return [item for item in STATE["requests"] if item["method"] == "POST" and item["path"] == path]

    def await_request(self, path, action=None):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            matches = [item for item in self.requests(path) if action is None or item["fields"].get("do") == action]
            if matches:
                return matches[-1]
            self.page.wait_for_timeout(50)
        self.fail("Expected request to " + path + " " + str(action))

    def panel(self, number):
        self.page.locator("#tab" + str(number)).click()
        expect(self.page.locator("#panel" + str(number))).to_be_visible()

    def test_default_simplified_and_responsive_light_dark(self):
        self.assertEqual("zh-CN", self.page.locator("html").get_attribute("lang"))
        expect(self.page.locator("h1")).not_to_be_empty()
        self.page.screenshot(path=str(OUTPUT / "desktop-light.png"), full_page=True)
        light = self.page.locator("body").evaluate("e => getComputedStyle(e).backgroundColor")
        self.page.locator("#theme-select").select_option("dark")
        expect(self.page.locator("html")).to_have_attribute("data-theme", "dark")
        self.page.wait_for_timeout(400)
        self.assertNotEqual(light, self.page.locator("body").evaluate("e => getComputedStyle(e).backgroundColor"))
        self.page.screenshot(path=str(OUTPUT / "desktop-dark.png"), full_page=True)
        self.page.reload()
        expect(self.page.locator("html")).to_have_attribute("data-theme", "dark")
        self.page.wait_for_timeout(400)
        for width in (375, 768):
            self.page.set_viewport_size({"width": width, "height": 860})
            for number in range(1, 6):
                self.panel(number)
                self.assertLessEqual(self.page.evaluate("document.documentElement.scrollWidth"), width + 1, "Horizontal overflow")
            self.panel(1)
            self.page.screenshot(path=str(OUTPUT / ("mobile-dark.png" if width == 375 else "tablet-dark.png")), full_page=width != 375)
        self.page.locator("#theme-select").select_option("light")
        self.page.wait_for_timeout(400)
        self.page.set_viewport_size({"width": 375, "height": 860})
        self.page.screenshot(path=str(OUTPUT / "mobile-light.png"), full_page=False)
        self.page.locator("#now-playing").scroll_into_view_if_needed()
        self.page.screenshot(path=str(OUTPUT / "mobile-player.png"), full_page=False)

    def test_languages_and_theme_follow_system(self):
        self.page.locator("#language-select").select_option("en")
        expect(self.page.locator("html")).to_have_attribute("lang", "en")
        expect(self.page.locator("#tab1")).to_contain_text("Search")
        self.page.reload()
        expect(self.page.locator("html")).to_have_attribute("lang", "en")
        self.page.locator("#language-select").select_option("zh-TW")
        expect(self.page.locator("#tab1")).to_contain_text("搜尋")
        self.page.locator("#language-select").select_option("zh-CN")
        expect(self.page.locator("#tab1")).to_contain_text("搜索")
        self.page.locator("#theme-select").select_option("auto")
        self.page.emulate_media(color_scheme="dark")
        expect(self.page.locator("html")).to_have_attribute("data-theme", "dark")

    def test_search_push_history_and_config_privacy(self):
        self.page.locator("#keyword").fill("  中文节目 第三季  ")
        self.page.locator("#keyword").press("Enter")
        self.assertEqual({"do": "search", "word": "中文节目 第三季"}, self.await_request("/action")["fields"])
        self.panel(2)
        link = "https://example.invalid/watch?v=3&title=中文#下期"
        self.page.locator("#push_url").fill(link)
        self.page.locator("#push-form button[type=submit]").click()
        self.assertEqual({"do": "push", "url": link}, self.await_request("/action", "push")["fields"])
        self.panel(4)
        self.page.locator("#setting_name").fill("测试配置")
        self.page.locator("#setting_text").fill("https://example.invalid/config?token=fake-private-value")
        self.page.locator("#settings-form button[type=submit]").click()
        fields = self.await_request("/action", "setting")["fields"]
        self.assertEqual("setting", fields["do"])
        self.assertEqual("测试配置", fields["name"])
        self.assertNotIn("fake-private-value", self.page.evaluate("JSON.stringify(localStorage)"))
        self.assertIn("中文节目 第三季", self.page.evaluate("JSON.stringify(localStorage)"))

    def test_file_rename_move_and_cancelled_delete(self):
        self.panel(5)
        item = self.page.locator(".file-item").filter(has_text="配置 2026.json")
        item.locator("button.icon-button").click()
        self.page.get_by_role("button", name="重命名", exact=True).click()
        self.page.locator("#rename-name").fill("新配置.json")
        self.page.locator("#dialog-actions button").last.click()
        request = self.await_request("/rename")
        self.assertEqual({"path": "/配置 2026.json", "name": "新配置.json"}, request["fields"])
        expect(self.page.locator("#file_list")).to_contain_text("新配置.json")
        self.page.locator(".file-item").filter(has_text="新配置.json").locator("button.icon-button").click()
        self.page.get_by_role("button", name="移动", exact=True).click()
        self.page.locator("#move-target").fill("/剧集")
        self.page.locator("#dialog-actions button").last.click()
        self.assertEqual({"path": "/新配置.json", "target": "/剧集"}, self.await_request("/move")["fields"])
        expect(self.page.locator("#file_list")).not_to_contain_text("新配置.json")
        self.page.locator(".file-item").filter(has_text="第三集 '彩蛋'#.srt").locator("button.icon-button").click()
        self.page.get_by_role("button", name="删除", exact=True).click()
        expect(self.page.locator("#dialog-title")).to_contain_text("删除")
        self.page.get_by_role("button", name="取消", exact=True).click()
        self.assertEqual([], self.requests("/delFile"))

    def test_failed_file_mutation_keeps_the_dialog_and_retry_enabled(self):
        self.panel(5)
        self.page.locator(".file-item").filter(has_text="配置 2026.json").locator("button.icon-button").click()
        self.page.get_by_role("button", name="重命名", exact=True).click()
        self.page.locator("#rename-name").fill("新配置.json")
        with LOCK:
            STATE["fail"].add("/rename")
            STATE["errorStatus"]["/rename"] = 409
        self.page.locator("#dialog-actions button").last.click()
        self.await_request("/rename")
        expect(self.page.locator("#action-dialog")).to_be_visible()
        expect(self.page.locator("#rename-name")).to_have_value("新配置.json")
        expect(self.page.locator("#dialog-actions button").last).to_be_enabled()
        expect(self.page.locator("#snackbar-message")).to_contain_text("同名")

    def test_remote_taps_use_supported_keys_and_modal_keyboard(self):
        self.page.locator("#remote-button").click()
        expect(self.page.locator("#action-dialog")).to_be_visible()
        self.page.locator('[data-remote-key="right"]').click()
        self.assertEqual({"key": "right", "action": "press"}, self.await_request("/remote/key")["fields"])
        self.page.keyboard.press("ArrowUp")
        deadline = time.monotonic() + 5
        while len(self.requests("/remote/key")) < 2 and time.monotonic() < deadline:
            self.page.wait_for_timeout(50)
        self.assertEqual("up", self.requests("/remote/key")[-1]["fields"]["key"])
        self.page.screenshot(path=str(OUTPUT / "remote-control.png"), full_page=True)
        self.page.keyboard.press("Escape")
        expect(self.page.locator("#action-dialog")).not_to_be_visible()

    def test_validation_and_failed_action_preserves_input(self):
        self.page.locator("#keyword").fill("   ")
        self.page.locator("#keyword").press("Enter")
        self.assertEqual([], self.requests("/action"))
        with LOCK:
            STATE["fail"].add("/action")
        self.page.locator("#keyword").fill("保留我的输入")
        self.page.locator("#keyword").press("Enter")
        self.await_request("/action")
        expect(self.page.locator("#snackbar")).to_be_visible()
        expect(self.page.locator("#keyword")).to_have_value("保留我的输入")

    def test_danmaku_and_playback_controls_use_real_protocol(self):
        expect(self.page.locator("#player-title")).to_have_text("山海之间")
        self.page.locator("#playback-toggle").click()
        request = self.await_request("/action", "control")
        self.assertEqual({"do": "control", "type": "play"}, request["fields"])
        self.panel(3)
        self.page.locator("#danmaku_text").fill("这期真好看")
        self.page.locator("#danmaku-form button[type=submit]").click()
        request = self.await_request("/action", "danmaku")
        self.assertEqual("danmaku", request["fields"]["do"])
        self.assertTrue(request["fields"]["text"].endswith("这期真好看"))
        self.assertTrue(request["fields"]["text"].startswith("[0.0,1,25,16777215]"))

    def test_no_active_media_disables_playback(self):
        with LOCK:
            STATE["media"] = {}
        self.page.reload()
        expect(self.page.locator("#playback-toggle")).to_be_disabled()
        expect(self.page.locator("#player-title")).not_to_have_text("山海之间")

    def test_file_names_are_literal_and_paths_are_encoded(self):
        self.panel(5)
        expect(self.page.locator("#file_list")).to_contain_text("<img src=x onerror=window.__injected=1>.srt")
        self.assertFalse(self.page.evaluate("Boolean(window.__injected)"))
        self.page.get_by_text("第三集 '彩蛋'#.srt", exact=True).click()
        expect(self.page.locator("#action-dialog")).to_be_visible()
        self.page.screenshot(path=str(OUTPUT / "file-actions.png"), full_page=True)
        self.page.keyboard.press("Escape")
        expect(self.page.locator("#action-dialog")).not_to_be_visible()
        self.page.get_by_text("剧集", exact=True).first.click()
        expect(self.page.locator("#file_list")).to_contain_text("第03集.mp4")
        self.assertTrue(any(item["path"] == "/file/剧集" for item in STATE["requests"]))

    def test_deep_links_empty_and_error_states(self):
        self.page.goto(self.base + "/?tab=5")
        expect(self.page.locator("#panel5")).to_be_visible()
        self.page.get_by_text("空目录", exact=True).first.click()
        expect(self.page.locator("#file-empty")).to_be_visible()
        self.page.goto(self.base + "/?tab=999")
        expect(self.page.locator("#panel1")).to_be_visible()
        with LOCK:
            STATE["fail"].add("/file")
        self.page.goto(self.base + "/?tab=5")
        expect(self.page.locator("#retry-files")).to_be_visible()

    def test_keyboard_navigation_and_dialog_focus(self):
        self.page.locator("#tab1").focus()
        self.page.keyboard.press("ArrowRight")
        expect(self.page.locator("#tab2")).to_be_focused()
        expect(self.page.locator("#panel2")).to_be_visible()
        self.panel(5)
        self.page.locator("#new-folder-button").click()
        expect(self.page.locator("#action-dialog")).to_be_visible()
        self.assertTrue(self.page.evaluate("document.querySelector('#action-dialog').contains(document.activeElement)"))
        for _ in range(10):
            self.page.keyboard.press("Tab")
            self.assertTrue(self.page.evaluate("document.querySelector('#action-dialog').contains(document.activeElement)"))
        self.page.keyboard.press("Escape")
        expect(self.page.locator("#new-folder-button")).to_be_focused()

    def test_upload_reports_real_multipart_completion(self):
        self.panel(5)
        self.page.locator("#file_uploader").set_input_files({"name": "中文字幕.srt", "mimeType": "application/x-subrip", "buffer": b"1\n00:00:00,000 --> 00:00:01,000\ncaption"})
        self.page.wait_for_timeout(400)
        if self.page.locator("#action-dialog").is_visible():
            self.page.locator("#dialog-actions button").last.click()
        self.page.wait_for_timeout(500)
        requests = self.requests("/upload")
        self.assertEqual(1, len(requests))
        self.assertIn("multipart/form-data", requests[0]["contentType"])
        self.assertGreater(requests[0]["bytes"], 30)


def main():
    global OUTPUT
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default=str(OUTPUT))
    args, remaining = parser.parse_known_args()
    OUTPUT = Path(args.output)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(MaterialWebTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    summary = {"headSha": os.environ.get("GITHUB_SHA"), "browser": "Chromium", "tests": result.testsRun,
               "failures": len(result.failures), "errors": len(result.errors), "passed": result.wasSuccessful()}
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary))
    raise SystemExit(0 if result.wasSuccessful() else 1)


if __name__ == "__main__":
    main()
